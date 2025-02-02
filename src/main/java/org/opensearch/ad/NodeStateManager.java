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

package org.opensearch.ad;

import static org.opensearch.common.xcontent.XContentParserUtils.ensureExpectedToken;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.Strings;
import org.opensearch.action.ActionListener;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.ad.common.exception.AnomalyDetectionException;
import org.opensearch.ad.common.exception.EndRunException;
import org.opensearch.ad.common.exception.LimitExceededException;
import org.opensearch.ad.constant.CommonErrorMessages;
import org.opensearch.ad.constant.CommonName;
import org.opensearch.ad.ml.ModelPartitioner;
import org.opensearch.ad.model.AnomalyDetector;
import org.opensearch.ad.transport.BackPressureRouting;
import org.opensearch.ad.util.ClientUtil;
import org.opensearch.ad.util.ExceptionUtil;
import org.opensearch.client.Client;
import org.opensearch.common.lease.Releasable;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.common.xcontent.XContentType;

/**
 * NodeStateManager is used to manage states shared by transport and ml components
 * like AnomalyDetector object
 *
 */
public class NodeStateManager implements MaintenanceState, CleanState {
    private static final Logger LOG = LogManager.getLogger(NodeStateManager.class);
    private ConcurrentHashMap<String, NodeState> states;
    private Client client;
    private ModelPartitioner modelPartitioner;
    private NamedXContentRegistry xContentRegistry;
    private ClientUtil clientUtil;
    // map from ES node id to the node's backpressureMuter
    private Map<String, BackPressureRouting> backpressureMuter;
    private final Clock clock;
    private final Settings settings;
    private final Duration stateTtl;

    public static final String NO_ERROR = "no_error";

    /**
     * Constructor
     *
     * @param client Client to make calls to ElasticSearch
     * @param xContentRegistry ES named content registry
     * @param settings ES settings
     * @param clientUtil AD Client utility
     * @param clock A UTC clock
     * @param stateTtl Max time to keep state in memory
     * @param modelPartitioner Used to partiton a RCF forest
    
     */
    public NodeStateManager(
        Client client,
        NamedXContentRegistry xContentRegistry,
        Settings settings,
        ClientUtil clientUtil,
        Clock clock,
        Duration stateTtl,
        ModelPartitioner modelPartitioner
    ) {
        this.states = new ConcurrentHashMap<>();
        this.client = client;
        this.modelPartitioner = modelPartitioner;
        this.xContentRegistry = xContentRegistry;
        this.clientUtil = clientUtil;
        this.backpressureMuter = new ConcurrentHashMap<>();
        this.clock = clock;
        this.settings = settings;
        this.stateTtl = stateTtl;
    }

    /**
     * Get the number of RCF model's partition number for detector adID
     * @param adID detector id
     * @param detector object
     * @return the number of RCF model's partition number for adID
     * @throws LimitExceededException when there is no sufficient resource available
     */
    public int getPartitionNumber(String adID, AnomalyDetector detector) {
        NodeState state = states.get(adID);
        if (state != null && state.getPartitonNumber() > 0) {
            return state.getPartitonNumber();
        }

        int partitionNum = modelPartitioner.getPartitionedForestSizes(detector).getKey();
        state = states.computeIfAbsent(adID, id -> new NodeState(id, clock));
        state.setPartitonNumber(partitionNum);

        return partitionNum;
    }

    /**
     * Get Detector config object if present
     * @param adID detector Id
     * @return the Detecor config object or empty Optional
     */
    public Optional<AnomalyDetector> getAnomalyDetectorIfPresent(String adID) {
        NodeState state = states.get(adID);
        return Optional.ofNullable(state).map(NodeState::getDetectorDef);
    }

    public void getAnomalyDetector(String adID, ActionListener<Optional<AnomalyDetector>> listener) {
        NodeState state = states.get(adID);
        if (state != null && state.getDetectorDef() != null) {
            listener.onResponse(Optional.of(state.getDetectorDef()));
        } else {
            GetRequest request = new GetRequest(AnomalyDetector.ANOMALY_DETECTORS_INDEX, adID);
            clientUtil.<GetRequest, GetResponse>asyncRequest(request, client::get, onGetDetectorResponse(adID, listener));
        }
    }

    private ActionListener<GetResponse> onGetDetectorResponse(String adID, ActionListener<Optional<AnomalyDetector>> listener) {
        return ActionListener.wrap(response -> {
            if (response == null || !response.isExists()) {
                listener.onResponse(Optional.empty());
                return;
            }

            String xc = response.getSourceAsString();
            LOG.debug("Fetched anomaly detector: {}", xc);

            try (
                XContentParser parser = XContentType.JSON.xContent().createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, xc)
            ) {
                ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.nextToken(), parser);
                AnomalyDetector detector = AnomalyDetector.parse(parser, response.getId());
                // end execution if all features are disabled
                if (detector.getEnabledFeatureIds().isEmpty()) {
                    listener
                        .onFailure(
                            new EndRunException(adID, CommonErrorMessages.ALL_FEATURES_DISABLED_ERR_MSG, true).countedInStats(false)
                        );
                    return;
                }
                NodeState state = states.computeIfAbsent(adID, id -> new NodeState(id, clock));
                state.setDetectorDef(detector);

                listener.onResponse(Optional.of(detector));
            } catch (Exception t) {
                LOG.error("Fail to parse detector {}", adID);
                LOG.error("Stack trace:", t);
                listener.onResponse(Optional.empty());
            }
        }, listener::onFailure);
    }

    /**
     * Get a detector's checkpoint and save a flag if we find any so that next time we don't need to do it again
     * @param adID  the detector's ID
     * @param listener listener to handle get request
     */
    public void getDetectorCheckpoint(String adID, ActionListener<Boolean> listener) {
        NodeState state = states.get(adID);
        if (state != null && state.doesCheckpointExists()) {
            listener.onResponse(Boolean.TRUE);
            return;
        }

        GetRequest request = new GetRequest(CommonName.CHECKPOINT_INDEX_NAME, modelPartitioner.getRcfModelId(adID, 0));

        clientUtil.<GetRequest, GetResponse>asyncRequest(request, client::get, onGetCheckpointResponse(adID, listener));
    }

    private ActionListener<GetResponse> onGetCheckpointResponse(String adID, ActionListener<Boolean> listener) {
        return ActionListener.wrap(response -> {
            if (response == null || !response.isExists()) {
                listener.onResponse(Boolean.FALSE);
            } else {
                NodeState state = states.computeIfAbsent(adID, id -> new NodeState(id, clock));
                state.setCheckpointExists(true);
                listener.onResponse(Boolean.TRUE);
            }
        }, listener::onFailure);
    }

    /**
     * Used in delete workflow
     *
     * @param adID detector ID
     */
    @Override
    public void clear(String adID) {
        states.remove(adID);
    }

    /**
     * Clean states if it is older than our stateTtl. transportState has to be a
     * ConcurrentHashMap otherwise we will have
     * java.util.ConcurrentModificationException.
     *
     */
    @Override
    public void maintenance() {
        maintenance(states, stateTtl);
    }

    public boolean isMuted(String nodeId) {
        return backpressureMuter.containsKey(nodeId) && backpressureMuter.get(nodeId).isMuted();
    }

    /**
     * When we have a unsuccessful call with a node, increment the backpressure counter.
     * @param nodeId an ES node's ID
     */
    public void addPressure(String nodeId) {
        backpressureMuter.computeIfAbsent(nodeId, k -> new BackPressureRouting(k, clock, settings)).addPressure();
    }

    /**
     * When we have a successful call with a node, clear the backpressure counter.
     * @param nodeId an ES node's ID
     */
    public void resetBackpressureCounter(String nodeId) {
        backpressureMuter.remove(nodeId);
    }

    /**
     * Check if there is running query on given detector
     * @param detector Anomaly Detector
     * @return true if given detector has a running query else false
     */
    public boolean hasRunningQuery(AnomalyDetector detector) {
        return clientUtil.hasRunningQuery(detector);
    }

    /**
     * Get last error of a detector
     * @param adID detector id
     * @return last error for the detector
     */
    public String getLastDetectionError(String adID) {
        return Optional.ofNullable(states.get(adID)).flatMap(state -> state.getLastDetectionError()).orElse(NO_ERROR);
    }

    /**
     * Set last detection error of a detector
     * @param adID detector id
     * @param error error, can be null
     */
    public void setLastDetectionError(String adID, String error) {
        NodeState state = states.computeIfAbsent(adID, id -> new NodeState(id, clock));
        state.setLastDetectionError(error);
    }

    /**
     * Get a detector's exception.  The method has side effect.
     * We reset error after calling the method because
     * 1) We record a detector's exception in each interval.  There is no need
     *  to record it twice.
     * 2) EndRunExceptions can stop job running. We only want to send the same
     *  signal once for each exception.
     * @param adID detector id
     * @return the detector's exception
     */
    public Optional<AnomalyDetectionException> fetchExceptionAndClear(String adID) {
        NodeState state = states.get(adID);
        if (state == null) {
            return Optional.empty();
        }

        Optional<AnomalyDetectionException> exception = state.getException();
        exception.ifPresent(e -> state.setException(null));
        return exception;
    }

    /**
     * For single-stream detector, we have one exception per interval.  When
     * an interval starts, it fetches and clears the exception.
     * For HCAD, there can be one exception per entity.  To not bloat memory
     * with exceptions, we will keep only one exception. An exception has 3 purposes:
     * 1) stop detector if nothing else works;
     * 2) increment error stats to ticket about high-error domain
     * 3) debugging.
     *
     * For HCAD, we record all entities' exceptions in anomaly results. So 3)
     * is covered.  As long as we keep one exception among all exceptions, 2)
     * is covered.  So the only thing we have to pay attention is to keep EndRunException.
     * When overriding an exception, EndRunException has priority.
     * @param detectorId Detector Id
     * @param e Exception to set
     */
    public void setException(String detectorId, Exception e) {
        if (e == null || Strings.isEmpty(detectorId)) {
            return;
        }
        NodeState state = states.computeIfAbsent(detectorId, d -> new NodeState(detectorId, clock));
        Optional<AnomalyDetectionException> exception = state.getException();
        if (exception.isPresent()) {
            Exception higherPriorityException = ExceptionUtil.selectHigherPriorityException(e, exception.get());
            if (higherPriorityException != e) {
                return;
            }
        }

        AnomalyDetectionException adExep = null;
        if (e instanceof AnomalyDetectionException) {
            adExep = (AnomalyDetectionException) e;
        } else {
            adExep = new AnomalyDetectionException(detectorId, e);
        }
        state.setException(adExep);
    }

    /**
     * Whether last cold start for the detector is running
     * @param adID detector ID
     * @return running or not
     */
    public boolean isColdStartRunning(String adID) {
        NodeState state = states.get(adID);
        if (state != null) {
            return state.isColdStartRunning();
        }

        return false;
    }

    /**
     * Mark the cold start status of the detector
     * @param adID detector ID
     * @return a callback when cold start is done
     */
    public Releasable markColdStartRunning(String adID) {
        NodeState state = states.computeIfAbsent(adID, id -> new NodeState(id, clock));
        state.setColdStartRunning(true);
        return () -> {
            NodeState nodeState = states.get(adID);
            if (nodeState != null) {
                nodeState.setColdStartRunning(false);
            }
        };
    }
}
