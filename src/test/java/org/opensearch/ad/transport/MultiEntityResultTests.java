/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.opensearch.ad.transport;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.opensearch.ad.settings.AnomalyDetectorSettings.MAX_ENTITIES_PER_QUERY;
import static org.opensearch.ad.settings.AnomalyDetectorSettings.PAGE_SIZE;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.mockito.stubbing.Answer;
import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.search.SearchPhaseExecutionException;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.SearchResponse.Clusters;
import org.opensearch.action.search.SearchResponseSections;
import org.opensearch.action.search.ShardSearchFailure;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.PlainActionFuture;
import org.opensearch.action.support.master.AcknowledgedResponse;
import org.opensearch.ad.AbstractADTest;
import org.opensearch.ad.NodeStateManager;
import org.opensearch.ad.TestHelpers;
import org.opensearch.ad.breaker.ADCircuitBreakerService;
import org.opensearch.ad.caching.CacheProvider;
import org.opensearch.ad.caching.EntityCache;
import org.opensearch.ad.cluster.HashRing;
import org.opensearch.ad.common.exception.EndRunException;
import org.opensearch.ad.common.exception.LimitExceededException;
import org.opensearch.ad.constant.CommonErrorMessages;
import org.opensearch.ad.feature.CompositeRetriever;
import org.opensearch.ad.feature.FeatureManager;
import org.opensearch.ad.feature.SearchFeatureDao;
import org.opensearch.ad.indices.AnomalyDetectionIndices;
import org.opensearch.ad.ml.EntityColdStarter;
import org.opensearch.ad.ml.ModelManager;
import org.opensearch.ad.ml.ModelPartitioner;
import org.opensearch.ad.ml.ThresholdingResult;
import org.opensearch.ad.model.AnomalyDetector;
import org.opensearch.ad.model.Entity;
import org.opensearch.ad.model.IntervalTimeConfiguration;
import org.opensearch.ad.ratelimit.CheckpointReadWorker;
import org.opensearch.ad.ratelimit.ColdEntityWorker;
import org.opensearch.ad.ratelimit.ResultWriteWorker;
import org.opensearch.ad.settings.AnomalyDetectorSettings;
import org.opensearch.ad.stats.ADStat;
import org.opensearch.ad.stats.ADStats;
import org.opensearch.ad.stats.StatNames;
import org.opensearch.ad.stats.suppliers.CounterSupplier;
import org.opensearch.ad.util.ClientUtil;
import org.opensearch.ad.util.IndexUtils;
import org.opensearch.client.Client;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.SearchHits;
import org.opensearch.search.aggregations.Aggregation;
import org.opensearch.search.aggregations.Aggregations;
import org.opensearch.search.aggregations.bucket.composite.CompositeAggregation;
import org.opensearch.search.aggregations.metrics.InternalMin;
import org.opensearch.test.ClusterServiceUtils;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.Transport;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportInterceptor;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportRequestOptions;
import org.opensearch.transport.TransportResponse;
import org.opensearch.transport.TransportResponseHandler;
import org.opensearch.transport.TransportService;

import test.org.opensearch.ad.util.MLUtil;
import test.org.opensearch.ad.util.RandomModelStateConfig;

public class MultiEntityResultTests extends AbstractADTest {
    private AnomalyResultTransportAction action;
    private AnomalyResultRequest request;
    private TransportInterceptor entityResultInterceptor;
    private Clock clock;
    private AnomalyDetector detector;
    private NodeStateManager stateManager;
    private static Settings settings;
    private TransportService transportService;
    private SearchFeatureDao searchFeatureDao;
    private Client client;
    private FeatureManager featureQuery;
    private ModelManager normalModelManager;
    private ModelPartitioner normalModelPartitioner;
    private HashRing hashRing;
    private ClusterService clusterService;
    private IndexNameExpressionResolver indexNameResolver;
    private ADCircuitBreakerService adCircuitBreakerService;
    private ADStats adStats;
    private ThreadPool mockThreadPool;
    private String detectorId;
    private Instant now;
    private String modelId;
    private CacheProvider provider;
    private AnomalyDetectionIndices indexUtil;
    private ResultWriteWorker resultWriteQueue;
    private CheckpointReadWorker checkpointReadQueue;
    private EntityColdStarter coldStarer;
    private ColdEntityWorker coldEntityQueue;

    @BeforeClass
    public static void setUpBeforeClass() {
        setUpThreadPool(AnomalyResultTests.class.getSimpleName());
    }

    @AfterClass
    public static void tearDownAfterClass() {
        tearDownThreadPool();
    }

    @SuppressWarnings({ "serial", "unchecked" })
    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        now = Instant.now();
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now);

        detectorId = "123";
        modelId = "abc";
        String categoryField = "a";
        detector = TestHelpers.randomAnomalyDetectorUsingCategoryFields(detectorId, Collections.singletonList(categoryField));

        stateManager = mock(NodeStateManager.class);
        // make sure parameters are not null, otherwise this mock won't get invoked
        doAnswer(invocation -> {
            ActionListener<Optional<AnomalyDetector>> listener = invocation.getArgument(1);
            listener.onResponse(Optional.of(detector));
            return null;
        }).when(stateManager).getAnomalyDetector(anyString(), any(ActionListener.class));

        // AnomalyDetector detector = TestHelpers
        // .randomAnomalyDetectorWithInterval(new IntervalTimeConfiguration(1, ChronoUnit.MINUTES), true, true);

        settings = Settings.builder().put(AnomalyDetectorSettings.COOLDOWN_MINUTES.getKey(), TimeValue.timeValueMinutes(5)).build();

        // make sure end time is larger enough than Clock.systemUTC().millis() to get PageIterator.hasNext() to pass
        request = new AnomalyResultRequest(detectorId, 100, Clock.systemUTC().millis() + 100_000);

        transportService = mock(TransportService.class);

        client = mock(Client.class);
        ThreadContext threadContext = new ThreadContext(settings);
        mockThreadPool = mock(ThreadPool.class);
        setUpADThreadPool(mockThreadPool);
        when(client.threadPool()).thenReturn(mockThreadPool);
        when(mockThreadPool.getThreadContext()).thenReturn(threadContext);

        featureQuery = mock(FeatureManager.class);

        normalModelManager = mock(ModelManager.class);

        normalModelPartitioner = mock(ModelPartitioner.class);

        hashRing = mock(HashRing.class);

        Set<Setting<?>> anomalyResultSetting = new HashSet<>(ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        anomalyResultSetting.add(MAX_ENTITIES_PER_QUERY);
        anomalyResultSetting.add(PAGE_SIZE);
        ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, anomalyResultSetting);

        DiscoveryNode discoveryNode = new DiscoveryNode(
            "node1",
            OpenSearchTestCase.buildNewFakeTransportAddress(),
            Collections.emptyMap(),
            DiscoveryNodeRole.BUILT_IN_ROLES,
            Version.CURRENT
        );

        clusterService = ClusterServiceUtils.createClusterService(threadPool, discoveryNode, clusterSettings);

        indexNameResolver = new IndexNameExpressionResolver(new ThreadContext(Settings.EMPTY));

        adCircuitBreakerService = mock(ADCircuitBreakerService.class);
        when(adCircuitBreakerService.isOpen()).thenReturn(false);

        IndexUtils indexUtils = new IndexUtils(client, mock(ClientUtil.class), clusterService, indexNameResolver);
        Map<String, ADStat<?>> statsMap = new HashMap<String, ADStat<?>>() {
            {
                put(StatNames.AD_EXECUTE_REQUEST_COUNT.getName(), new ADStat<>(false, new CounterSupplier()));
                put(StatNames.AD_EXECUTE_FAIL_COUNT.getName(), new ADStat<>(false, new CounterSupplier()));
                put(StatNames.AD_HC_EXECUTE_REQUEST_COUNT.getName(), new ADStat<>(false, new CounterSupplier()));
                put(StatNames.AD_HC_EXECUTE_FAIL_COUNT.getName(), new ADStat<>(false, new CounterSupplier()));
            }
        };
        adStats = new ADStats(statsMap);

        searchFeatureDao = mock(SearchFeatureDao.class);

        action = new AnomalyResultTransportAction(
            new ActionFilters(Collections.emptySet()),
            transportService,
            settings,
            client,
            stateManager,
            featureQuery,
            normalModelManager,
            normalModelPartitioner,
            hashRing,
            clusterService,
            indexNameResolver,
            adCircuitBreakerService,
            adStats,
            mockThreadPool,
            xContentRegistry()
        );

        provider = mock(CacheProvider.class);
        EntityCache entityCache = mock(EntityCache.class);
        when(provider.get()).thenReturn(entityCache);
        when(entityCache.get(any(), any()))
            .thenReturn(MLUtil.randomModelState(new RandomModelStateConfig.Builder().fullModel(true).build()));
        when(entityCache.selectUpdateCandidate(any(), any(), any())).thenReturn(Pair.of(new ArrayList<Entity>(), new ArrayList<Entity>()));

        indexUtil = mock(AnomalyDetectionIndices.class);
        resultWriteQueue = mock(ResultWriteWorker.class);
        checkpointReadQueue = mock(CheckpointReadWorker.class);

        coldStarer = mock(EntityColdStarter.class);
        coldEntityQueue = mock(ColdEntityWorker.class);
    }

    @Override
    @After
    public final void tearDown() throws Exception {
        tearDownTestNodes();
        super.tearDown();
    }

    public void testColdStartEndRunException() {
        when(stateManager.fetchExceptionAndClear(anyString()))
            .thenReturn(
                Optional
                    .of(
                        new EndRunException(
                            detectorId,
                            CommonErrorMessages.INVALID_SEARCH_QUERY_MSG,
                            new NoSuchElementException("No value present"),
                            false
                        )
                    )
            );
        PlainActionFuture<AnomalyResultResponse> listener = new PlainActionFuture<>();
        action.doExecute(null, request, listener);
        assertException(listener, EndRunException.class, CommonErrorMessages.INVALID_SEARCH_QUERY_MSG);
    }

    // a handler that forwards response or exception received from network
    private <T extends TransportResponse> TransportResponseHandler<T> entityResultHandler(TransportResponseHandler<T> handler) {
        return new TransportResponseHandler<T>() {
            @Override
            public T read(StreamInput in) throws IOException {
                return handler.read(in);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleResponse(T response) {
                handler.handleResponse(response);
            }

            @Override
            public void handleException(TransportException exp) {
                handler.handleException(exp);
            }

            @Override
            public String executor() {
                return handler.executor();
            }
        };
    }

    private <T extends TransportResponse> TransportResponseHandler<T> unackEntityResultHandler(TransportResponseHandler<T> handler) {
        return new TransportResponseHandler<T>() {
            @Override
            public T read(StreamInput in) throws IOException {
                return handler.read(in);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleResponse(T response) {
                handler.handleResponse((T) new AcknowledgedResponse(false));
            }

            @Override
            public void handleException(TransportException exp) {
                handler.handleException(exp);
            }

            @Override
            public String executor() {
                return handler.executor();
            }
        };
    }

    private void setUpEntityResult() {
        // register entity result action
        new EntityResultTransportAction(
            new ActionFilters(Collections.emptySet()),
            // since we send requests to testNodes[1]
            testNodes[1].transportService,
            normalModelManager,
            adCircuitBreakerService,
            provider,
            stateManager,
            indexUtil,
            resultWriteQueue,
            checkpointReadQueue,
            coldEntityQueue,
            threadPool
        );

        when(normalModelManager.getAnomalyResultForEntity(any(), any(), any(), any(), any())).thenReturn(new ThresholdingResult(0, 1, 1));
    }

    @SuppressWarnings("unchecked")
    public void setUpNormlaStateManager() throws IOException {
        ClientUtil clientUtil = mock(ClientUtil.class);

        AnomalyDetector detector = TestHelpers
            .randomAnomalyDetectorWithInterval(new IntervalTimeConfiguration(1, ChronoUnit.MINUTES), true, true);
        doAnswer(invocation -> {
            ActionListener<GetResponse> listener = invocation.getArgument(2);
            listener.onResponse(TestHelpers.createGetResponse(detector, detectorId, AnomalyDetector.ANOMALY_DETECTORS_INDEX));
            return null;
        }).when(clientUtil).asyncRequest(any(GetRequest.class), any(), any(ActionListener.class));

        ModelPartitioner modelPartitioner = mock(ModelPartitioner.class);
        stateManager = new NodeStateManager(
            client,
            xContentRegistry(),
            settings,
            clientUtil,
            clock,
            AnomalyDetectorSettings.HOURLY_MAINTENANCE,
            modelPartitioner
        );

        action = new AnomalyResultTransportAction(
            new ActionFilters(Collections.emptySet()),
            transportService,
            settings,
            client,
            stateManager,
            featureQuery,
            normalModelManager,
            normalModelPartitioner,
            hashRing,
            clusterService,
            indexNameResolver,
            adCircuitBreakerService,
            adStats,
            mockThreadPool,
            xContentRegistry()
        );
    }

    /**
     * Test query error causes EndRunException but not end now
     * @throws InterruptedException when the await are interrupted
     * @throws IOException when failing to create anomaly detector
     */
    public void testQueryErrorEndRunNotNow() throws InterruptedException, IOException {
        setUpNormlaStateManager();

        final CountDownLatch inProgressLatch = new CountDownLatch(1);

        String allShardsFailedMsg = "all shards failed";
        // make PageIterator.next return failure
        doAnswer(invocation -> {
            ActionListener<SearchResponse> listener = invocation.getArgument(1);
            listener
                .onFailure(
                    new SearchPhaseExecutionException(
                        "search",
                        allShardsFailedMsg,
                        new ShardSearchFailure[] { new ShardSearchFailure(new IllegalArgumentException("blah")) }
                    )
                );
            inProgressLatch.countDown();
            return null;
        }).when(client).search(any(), any());

        PlainActionFuture<AnomalyResultResponse> listener = new PlainActionFuture<>();

        action.doExecute(null, request, listener);

        AnomalyResultResponse response = listener.actionGet(10000L);
        assertEquals(Double.NaN, response.getAnomalyGrade(), 0.001);

        assertTrue(inProgressLatch.await(10000L, TimeUnit.MILLISECONDS));

        PlainActionFuture<AnomalyResultResponse> listener2 = new PlainActionFuture<>();
        action.doExecute(null, request, listener2);
        Exception e = expectThrows(EndRunException.class, () -> listener2.actionGet(10000L));
        // wrapped INVALID_SEARCH_QUERY_MSG around SearchPhaseExecutionException by convertedQueryFailureException
        assertThat("actual message: " + e.getMessage(), e.getMessage(), containsString(CommonErrorMessages.INVALID_SEARCH_QUERY_MSG));
        assertThat("actual message: " + e.getMessage(), e.getMessage(), containsString(allShardsFailedMsg));
        // not end now
        assertTrue(!((EndRunException) e).isEndNow());
    }

    public void testIndexNotFound() throws InterruptedException, IOException {
        setUpNormlaStateManager();

        final CountDownLatch inProgressLatch = new CountDownLatch(1);

        // make PageIterator.next return failure
        doAnswer(invocation -> {
            ActionListener<SearchResponse> listener = invocation.getArgument(1);
            listener.onFailure(new IndexNotFoundException("", ""));
            inProgressLatch.countDown();
            return null;
        }).when(client).search(any(), any());

        PlainActionFuture<AnomalyResultResponse> listener = new PlainActionFuture<>();

        action.doExecute(null, request, listener);

        AnomalyResultResponse response = listener.actionGet(10000L);
        assertEquals(Double.NaN, response.getAnomalyGrade(), 0.001);

        assertTrue(inProgressLatch.await(10000L, TimeUnit.MILLISECONDS));

        PlainActionFuture<AnomalyResultResponse> listener2 = new PlainActionFuture<>();
        action.doExecute(null, request, listener2);
        Exception e = expectThrows(EndRunException.class, () -> listener2.actionGet(10000L));
        assertThat(
            "actual message: " + e.getMessage(),
            e.getMessage(),
            containsString(AnomalyResultTransportAction.TROUBLE_QUERYING_ERR_MSG)
        );
        assertTrue(!((EndRunException) e).isEndNow());
    }

    public void testEmptyFeatures() throws InterruptedException {
        final CountDownLatch inProgressLatch = new CountDownLatch(1);

        doAnswer(invocation -> {
            ActionListener<SearchResponse> listener = invocation.getArgument(1);
            listener.onResponse(createEmptyResponse());
            inProgressLatch.countDown();
            return null;
        }).when(client).search(any(), any());

        PlainActionFuture<AnomalyResultResponse> listener = new PlainActionFuture<>();

        action.doExecute(null, request, listener);

        AnomalyResultResponse response = listener.actionGet(10000L);
        assertEquals(Double.NaN, response.getAnomalyGrade(), 0.01);

        assertTrue(inProgressLatch.await(10000L, TimeUnit.MILLISECONDS));

        PlainActionFuture<AnomalyResultResponse> listener2 = new PlainActionFuture<>();
        action.doExecute(null, request, listener2);

        AnomalyResultResponse response2 = listener2.actionGet(10000L);
        assertEquals(Double.NaN, response2.getAnomalyGrade(), 0.01);
    }

    /**
     *
     * @return an empty response
     */
    private SearchResponse createEmptyResponse() {
        CompositeAggregation emptyComposite = mock(CompositeAggregation.class);
        when(emptyComposite.getName()).thenReturn(CompositeRetriever.AGG_NAME_COMP);
        when(emptyComposite.afterKey()).thenReturn(null);
        // empty bucket
        when(emptyComposite.getBuckets())
            .thenAnswer((Answer<List<CompositeAggregation.Bucket>>) invocation -> { return new ArrayList<CompositeAggregation.Bucket>(); });
        Aggregations emptyAggs = new Aggregations(Collections.singletonList(emptyComposite));
        SearchResponseSections emptySections = new SearchResponseSections(SearchHits.empty(), emptyAggs, null, false, null, null, 1);
        return new SearchResponse(emptySections, null, 1, 1, 0, 0, ShardSearchFailure.EMPTY_ARRAY, Clusters.EMPTY);
    }

    private <T extends TransportResponse> CountDownLatch setUpTransportInterceptor(
        Function<TransportResponseHandler<T>, TransportResponseHandler<T>> interceptor
    ) {
        // set up a non-empty response
        CompositeAggregation composite = mock(CompositeAggregation.class);
        when(composite.getName()).thenReturn(CompositeRetriever.AGG_NAME_COMP);
        Map<String, Object> afterKey = new HashMap<>();
        afterKey.put("service", "app_0");
        afterKey.put("host", "server_3");
        when(composite.afterKey()).thenReturn(afterKey);

        String featureID = detector.getFeatureAttributes().get(0).getId();
        List<CompositeAggregation.Bucket> compositeBuckets = new ArrayList<>();
        CompositeAggregation.Bucket bucket = mock(CompositeAggregation.Bucket.class);
        when(bucket.getKey()).thenReturn(Collections.singletonMap("app_0", "server_1"));
        List<Aggregation> aggList = new ArrayList<>();
        aggList.add(new InternalMin(featureID, randomDouble(), DocValueFormat.RAW, new HashMap<>()));
        Aggregations aggregations = new Aggregations(aggList);
        when(bucket.getAggregations()).thenReturn(aggregations);
        compositeBuckets.add(bucket);

        bucket = mock(CompositeAggregation.Bucket.class);
        when(bucket.getKey()).thenReturn(Collections.singletonMap("app_0", "server_2"));
        aggList = new ArrayList<>();
        aggList.add(new InternalMin(featureID, randomDouble(), DocValueFormat.RAW, new HashMap<>()));
        aggregations = new Aggregations(aggList);
        when(bucket.getAggregations()).thenReturn(aggregations);
        compositeBuckets.add(bucket);

        bucket = mock(CompositeAggregation.Bucket.class);
        when(bucket.getKey()).thenReturn(Collections.singletonMap("app_0", "server_3"));
        aggList = new ArrayList<>();
        aggList.add(new InternalMin(featureID, randomDouble(), DocValueFormat.RAW, new HashMap<>()));
        aggregations = new Aggregations(aggList);
        when(bucket.getAggregations()).thenReturn(aggregations);
        compositeBuckets.add(bucket);

        when(composite.getBuckets()).thenAnswer((Answer<List<CompositeAggregation.Bucket>>) invocation -> { return compositeBuckets; });
        Aggregations aggs = new Aggregations(Collections.singletonList(composite));

        SearchResponseSections sections = new SearchResponseSections(SearchHits.empty(), aggs, null, false, null, null, 1);
        SearchResponse response = new SearchResponse(sections, null, 1, 1, 0, 0, ShardSearchFailure.EMPTY_ARRAY, Clusters.EMPTY);

        CountDownLatch inProgress = new CountDownLatch(2);
        AtomicBoolean firstCalled = new AtomicBoolean();
        doAnswer(invocation -> {
            ActionListener<SearchResponse> listener = invocation.getArgument(1);
            if (firstCalled.get()) {
                listener.onResponse(createEmptyResponse());
                inProgress.countDown();
            } else {
                listener.onResponse(response);
                firstCalled.set(true);
                inProgress.countDown();
            }
            return null;
        }).when(client).search(any(), any());

        entityResultInterceptor = new TransportInterceptor() {
            @Override
            public AsyncSender interceptSender(AsyncSender sender) {
                return new AsyncSender() {
                    @SuppressWarnings("unchecked")
                    @Override
                    public <T2 extends TransportResponse> void sendRequest(
                        Transport.Connection connection,
                        String action,
                        TransportRequest request,
                        TransportRequestOptions options,
                        TransportResponseHandler<T2> handler
                    ) {
                        if (action.equals(EntityResultAction.NAME)) {
                            sender
                                .sendRequest(
                                    connection,
                                    action,
                                    request,
                                    options,
                                    interceptor.apply((TransportResponseHandler<T>) handler)
                                );
                        } else {
                            sender.sendRequest(connection, action, request, options, handler);
                        }
                    }
                };
            }
        };

        setupTestNodes(entityResultInterceptor, settings, MAX_ENTITIES_PER_QUERY, PAGE_SIZE);

        // mock hashing ring response. This has to happen after setting up test nodes with the failure interceptor
        when(hashRing.getOwningNode(any(String.class))).thenReturn(Optional.of(testNodes[1].discoveryNode()));

        TransportService realTransportService = testNodes[0].transportService;
        ClusterService realClusterService = testNodes[0].clusterService;

        action = new AnomalyResultTransportAction(
            new ActionFilters(Collections.emptySet()),
            realTransportService,
            settings,
            client,
            stateManager,
            featureQuery,
            normalModelManager,
            normalModelPartitioner,
            hashRing,
            realClusterService,
            indexNameResolver,
            adCircuitBreakerService,
            adStats,
            threadPool,
            xContentRegistry()
        );

        return inProgress;
    }

    public void testNonEmptyFeatures() throws InterruptedException {
        CountDownLatch inProgress = setUpTransportInterceptor(this::entityResultHandler);
        setUpEntityResult();

        PlainActionFuture<AnomalyResultResponse> listener = new PlainActionFuture<>();

        action.doExecute(null, request, listener);

        AnomalyResultResponse response = listener.actionGet(10000L);
        assertEquals(Double.NaN, response.getAnomalyGrade(), 0.01);

        assertTrue(inProgress.await(10000L, TimeUnit.MILLISECONDS));

        // since we have 3 results in the first page
        verify(resultWriteQueue, times(3)).put(any());
    }

    @SuppressWarnings("unchecked")
    public void testCircuitBreakerOpen() throws InterruptedException {
        ClientUtil clientUtil = mock(ClientUtil.class);
        doAnswer(invocation -> {
            ActionListener<GetResponse> listener = invocation.getArgument(2);
            listener.onResponse(TestHelpers.createGetResponse(detector, detectorId, AnomalyDetector.ANOMALY_DETECTORS_INDEX));
            return null;
        }).when(clientUtil).asyncRequest(any(GetRequest.class), any(), any(ActionListener.class));

        ModelPartitioner modelPartitioner = mock(ModelPartitioner.class);
        stateManager = new NodeStateManager(
            client,
            xContentRegistry(),
            settings,
            clientUtil,
            clock,
            AnomalyDetectorSettings.HOURLY_MAINTENANCE,
            modelPartitioner
        );

        action = new AnomalyResultTransportAction(
            new ActionFilters(Collections.emptySet()),
            transportService,
            settings,
            client,
            stateManager,
            featureQuery,
            normalModelManager,
            normalModelPartitioner,
            hashRing,
            clusterService,
            indexNameResolver,
            adCircuitBreakerService,
            adStats,
            mockThreadPool,
            xContentRegistry()
        );

        CountDownLatch inProgress = setUpTransportInterceptor(this::entityResultHandler);

        ADCircuitBreakerService openBreaker = mock(ADCircuitBreakerService.class);
        when(openBreaker.isOpen()).thenReturn(true);
        // register entity result action
        new EntityResultTransportAction(
            new ActionFilters(Collections.emptySet()),
            // since we send requests to testNodes[1]
            testNodes[1].transportService,
            normalModelManager,
            openBreaker,
            provider,
            stateManager,
            indexUtil,
            resultWriteQueue,
            checkpointReadQueue,
            coldEntityQueue,
            threadPool
        );

        PlainActionFuture<AnomalyResultResponse> listener = new PlainActionFuture<>();
        action.doExecute(null, request, listener);
        AnomalyResultResponse response = listener.actionGet(10000L);
        assertEquals(Double.NaN, response.getAnomalyGrade(), 0.01);

        assertTrue(inProgress.await(10000L, TimeUnit.MILLISECONDS));

        listener = new PlainActionFuture<>();
        action.doExecute(null, request, listener);
        assertException(listener, LimitExceededException.class, CommonErrorMessages.MEMORY_CIRCUIT_BROKEN_ERR_MSG);
    }

    // public void testNotAck() {
    // setUpTransportInterceptor(this::unackEntityResultHandler);
    // setUpEntityResult();
    //
    // PlainActionFuture<AnomalyResultResponse> listener = new PlainActionFuture<>();
    //
    // action.doExecute(null, request, listener);
    //
    // assertException(listener, InternalFailure.class, AnomalyResultTransportAction.NO_ACK_ERR);
    // verify(stateManager, times(1)).addPressure(anyString());
    // }
}
