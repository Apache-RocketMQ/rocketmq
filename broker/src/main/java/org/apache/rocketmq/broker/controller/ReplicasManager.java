/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.broker.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.broker.out.BrokerOuterAPI;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.common.BrokerConfig;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.Pair;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.org.slf4j.Logger;
import org.apache.rocketmq.logging.org.slf4j.LoggerFactory;
import org.apache.rocketmq.remoting.protocol.EpochEntry;
import org.apache.rocketmq.remoting.protocol.body.SyncStateSet;
import org.apache.rocketmq.remoting.protocol.header.controller.ElectMasterResponseHeader;
import org.apache.rocketmq.remoting.protocol.header.controller.GetMetaDataResponseHeader;
import org.apache.rocketmq.remoting.protocol.header.controller.GetReplicaInfoResponseHeader;
import org.apache.rocketmq.remoting.protocol.header.controller.register.ApplyBrokerIdResponseHeader;
import org.apache.rocketmq.remoting.protocol.header.controller.register.GetNextBrokerIdResponseHeader;
import org.apache.rocketmq.remoting.protocol.header.controller.register.RegisterBrokerToControllerResponseHeader;
import org.apache.rocketmq.store.config.BrokerRole;
import org.apache.rocketmq.store.ha.autoswitch.AutoSwitchHAService;
import org.apache.rocketmq.store.ha.autoswitch.BrokerMetadata;
import org.apache.rocketmq.store.ha.autoswitch.TempBrokerMetadata;

import static org.apache.rocketmq.remoting.protocol.ResponseCode.CONTROLLER_BROKER_METADATA_NOT_EXIST;

/**
 * The manager of broker replicas, including: 0.regularly syncing controller metadata, change controller leader address,
 * both master and slave will start this timed task. 1.regularly syncing metadata from controllers, and changing broker
 * roles and master if needed, both master and slave will start this timed task. 2.regularly expanding and Shrinking
 * syncStateSet, only master will start this timed task.
 */
public class ReplicasManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggerName.BROKER_LOGGER_NAME);

    private static final int RETRY_INTERVAL_SECOND = 5;

    private final ScheduledExecutorService scheduledService;
    private final ExecutorService executorService;
    private final ExecutorService scanExecutor;
    private final BrokerController brokerController;
    private final AutoSwitchHAService haService;
    private final BrokerConfig brokerConfig;
    private final String brokerAddress;
    private final BrokerOuterAPI brokerOuterAPI;
    private List<String> controllerAddresses;
    private final ConcurrentMap<String, Boolean> availableControllerAddresses;

    private volatile String controllerLeaderAddress = "";
    private volatile State state = State.INITIAL;

    private RegisterState registerState = RegisterState.INITIAL;

    private ScheduledFuture<?> checkSyncStateSetTaskFuture;
    private ScheduledFuture<?> slaveSyncFuture;

    private Long brokerControllerId;

    private Long masterBrokerId;

    private BrokerMetadata brokerMetadata;

    private TempBrokerMetadata tempBrokerMetadata;

    private Set<Long> syncStateSet;
    private int syncStateSetEpoch = 0;
    private String masterAddress = "";
    private int masterEpoch = 0;
    private long lastSyncTimeMs = System.currentTimeMillis();
    private Random random = new Random();

    public ReplicasManager(final BrokerController brokerController) {
        this.brokerController = brokerController;
        this.brokerOuterAPI = brokerController.getBrokerOuterAPI();
        this.scheduledService = Executors.newScheduledThreadPool(3, new ThreadFactoryImpl("ReplicasManager_ScheduledService_", brokerController.getBrokerIdentity()));
        this.executorService = Executors.newFixedThreadPool(3, new ThreadFactoryImpl("ReplicasManager_ExecutorService_", brokerController.getBrokerIdentity()));
        this.scanExecutor = new ThreadPoolExecutor(4, 10, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), new ThreadFactoryImpl("ReplicasManager_scan_thread_", brokerController.getBrokerIdentity()));
        this.haService = (AutoSwitchHAService) brokerController.getMessageStore().getHaService();
        this.brokerConfig = brokerController.getBrokerConfig();
        this.availableControllerAddresses = new ConcurrentHashMap<>();
        this.syncStateSet = new HashSet<>();
        this.brokerAddress = brokerController.getBrokerAddr();
        this.brokerMetadata = new BrokerMetadata(this.brokerController.getMessageStoreConfig().getStorePathBrokerIdentity());
        this.tempBrokerMetadata = new TempBrokerMetadata(this.brokerController.getMessageStoreConfig().getStorePathBrokerIdentity() + "-temp");
    }

    public long getConfirmOffset() {
        return this.haService.getConfirmOffset();
    }

    enum State {
        INITIAL,
        FIRST_TIME_SYNC_CONTROLLER_METADATA_DONE,
        REGISTER_TO_CONTROLLER_DONE,
        RUNNING,
        SHUTDOWN,
    }

    enum RegisterState {
        INITIAL,
        CREATE_TEMP_METADATA_FILE_DONE,
        CREATE_METADATA_FILE_DONE,
        REGISTERED
    }

    public void start() {
        updateControllerAddr();
        scanAvailableControllerAddresses();
        this.scheduledService.scheduleAtFixedRate(this::updateControllerAddr, 2 * 60 * 1000, 2 * 60 * 1000, TimeUnit.MILLISECONDS);
        this.scheduledService.scheduleAtFixedRate(this::scanAvailableControllerAddresses, 3 * 1000, 3 * 1000, TimeUnit.MILLISECONDS);
        if (!startBasicService()) {
            LOGGER.error("Failed to start replicasManager");
            this.executorService.submit(() -> {
                int retryTimes = 0;
                do {
                    try {
                        TimeUnit.SECONDS.sleep(RETRY_INTERVAL_SECOND);
                    } catch (InterruptedException ignored) {

                    }
                    retryTimes++;
                    LOGGER.warn("Failed to start replicasManager, retry times:{}, current state:{}, try it again", retryTimes, this.state);
                }
                while (!startBasicService());

                LOGGER.info("Start replicasManager success, retry times:{}", retryTimes);
            });
        }
    }

    private boolean startBasicService() {
        if (this.state == State.INITIAL) {
            if (schedulingSyncControllerMetadata()) {
                LOGGER.info("First time sync controller metadata success");
                this.state = State.FIRST_TIME_SYNC_CONTROLLER_METADATA_DONE;
            } else {
                return false;
            }
        }

        if (this.state == State.FIRST_TIME_SYNC_CONTROLLER_METADATA_DONE) {
            for (int retryTimes = 0; retryTimes < 5; retryTimes++) {
                if (register()) {
                    LOGGER.info("First time register broker success");
                    this.state = State.REGISTER_TO_CONTROLLER_DONE;
                    break;
                }

                // Try to avoid registration concurrency conflicts in random sleep
                try {
                    Thread.sleep(random.nextInt(1000));
                } catch (Exception ignore) {

                }
            }
            // register 5 times but still unsuccessful
            if (this.state != State.REGISTER_TO_CONTROLLER_DONE) {
                return false;
            }
        }

        if (this.state == State.REGISTER_TO_CONTROLLER_DONE) {
            // The scheduled task for heartbeat sending is not starting now, so we should manually send heartbeat request
            this.sendHeartbeatToController();
            if (this.masterBrokerId != null || brokerElect()) {
                LOGGER.info("Master in this broker set is elected");
                this.state = State.RUNNING;
                this.brokerController.setIsolated(false);
            } else {
                return false;
            }
        }

        schedulingSyncBrokerMetadata();

        // Register syncStateSet changed listener.
        this.haService.registerSyncStateSetChangedListener(this::doReportSyncStateSetChanged);
        return true;
    }

    public void shutdown() {
        this.state = State.SHUTDOWN;
        this.registerState = RegisterState.INITIAL;
        this.executorService.shutdown();
        this.scheduledService.shutdown();
    }

    public synchronized void changeBrokerRole(final Long newMasterBrokerId, final String newMasterAddress, final Integer newMasterEpoch,
                                              final Integer syncStateSetEpoch) {
        if (newMasterBrokerId != null && newMasterEpoch > this.masterEpoch) {
            if (newMasterBrokerId.equals(this.brokerControllerId)) {
                changeToMaster(newMasterEpoch, syncStateSetEpoch);
            } else {
                changeToSlave(newMasterAddress, newMasterEpoch, newMasterBrokerId);
            }
        }
    }

    public void changeToMaster(final int newMasterEpoch, final int syncStateSetEpoch) {
        synchronized (this) {
            if (newMasterEpoch > this.masterEpoch) {
                LOGGER.info("Begin to change to master, brokerName:{}, replicas:{}, new Epoch:{}", this.brokerConfig.getBrokerName(), this.brokerAddress, newMasterEpoch);

                this.masterEpoch = newMasterEpoch;

                // Change SyncStateSet
                final HashSet<Long> newSyncStateSet = new HashSet<>();
                newSyncStateSet.add(this.brokerControllerId);
                changeSyncStateSet(newSyncStateSet, syncStateSetEpoch);

                // Change record
                this.masterAddress = this.brokerAddress;
                this.masterBrokerId = this.brokerControllerId;

                // Handle the slave synchronise
                handleSlaveSynchronize(BrokerRole.SYNC_MASTER);

                // Notify ha service, change to master
                this.haService.changeToMaster(newMasterEpoch);

                this.brokerController.getBrokerConfig().setBrokerId(MixAll.MASTER_ID);
                this.brokerController.getMessageStoreConfig().setBrokerRole(BrokerRole.SYNC_MASTER);
                this.brokerController.changeSpecialServiceStatus(true);

                schedulingCheckSyncStateSet();

                this.executorService.submit(() -> {
                    // Register broker to name-srv
                    try {
                        this.brokerController.registerBrokerAll(true, false, this.brokerController.getBrokerConfig().isForceRegister());
                    } catch (final Throwable e) {
                        LOGGER.error("Error happen when register broker to name-srv, Failed to change broker to master", e);
                        return;
                    }
                    LOGGER.info("Change broker [id:{}][address:{}] to master success, masterEpoch {}, syncStateSetEpoch:{}", this.brokerConfig, this.brokerAddress, newMasterEpoch, syncStateSetEpoch);
                });
            }
        }
    }

    public void changeToSlave(final String newMasterAddress, final int newMasterEpoch, long newMasterBrokerId) {
        synchronized (this) {
            if (newMasterEpoch > this.masterEpoch) {
                LOGGER.info("Begin to change to slave, brokerName={}, brokerId={}, newMasterBrokerId={}, newMasterAddress={}, newMasterEpoch={}",
                        this.brokerConfig.getBrokerName(), this.brokerControllerId, newMasterBrokerId, newMasterAddress, newMasterEpoch);

                // Change record
                this.masterAddress = newMasterAddress;
                this.masterEpoch = newMasterEpoch;
                this.masterBrokerId = newMasterBrokerId;

                // Stop checking syncStateSet because only master is able to check
                stopCheckSyncStateSet();

                // Change config(compatibility problem)
                this.brokerController.getMessageStoreConfig().setBrokerRole(BrokerRole.SLAVE);
                this.brokerController.changeSpecialServiceStatus(false);
                // The brokerId in brokerConfig just means its role(master[0] or slave[>=1])
                this.brokerConfig.setBrokerId(brokerControllerId);

                // Handle the slave synchronise
                handleSlaveSynchronize(BrokerRole.SLAVE);

                // Notify ha service, change to slave
                this.haService.changeToSlave(newMasterAddress, newMasterEpoch, brokerControllerId);

                this.executorService.submit(() -> {
                    // Register broker to name-srv
                    try {
                        this.brokerController.registerBrokerAll(true, false, this.brokerController.getBrokerConfig().isForceRegister());
                    } catch (final Throwable e) {
                        LOGGER.error("Error happen when register broker to name server, Failed to change broker to slave", e);
                        return;
                    }

                    LOGGER.info("Change broker [id:{}][address:{}] to slave, newMasterBrokerId:{}, newMasterAddress:{}, newMasterEpoch:{}", this.brokerControllerId, this.brokerAddress, newMasterBrokerId, newMasterAddress, newMasterEpoch);
                });
            }
        }
    }

    private void changeSyncStateSet(final Set<Long> newSyncStateSet, final int newSyncStateSetEpoch) {
        synchronized (this) {
            if (newSyncStateSetEpoch > this.syncStateSetEpoch) {
                LOGGER.info("SyncStateSet changed from {} to {}", this.syncStateSet, newSyncStateSet);
                this.syncStateSetEpoch = newSyncStateSetEpoch;
                this.syncStateSet = new HashSet<>(newSyncStateSet);
                this.haService.setSyncStateSet(newSyncStateSet);
            }
        }
    }

    private void handleSlaveSynchronize(final BrokerRole role) {
        if (role == BrokerRole.SLAVE) {
            if (this.slaveSyncFuture != null) {
                this.slaveSyncFuture.cancel(false);
            }
            this.brokerController.getSlaveSynchronize().setMasterAddr(this.masterAddress);
            slaveSyncFuture = this.brokerController.getScheduledExecutorService().scheduleAtFixedRate(() -> {
                try {
                    if (System.currentTimeMillis() - lastSyncTimeMs > 10 * 1000) {
                        brokerController.getSlaveSynchronize().syncAll();
                        lastSyncTimeMs = System.currentTimeMillis();
                    }
                    //timer checkpoint, latency-sensitive, so sync it more frequently
                    brokerController.getSlaveSynchronize().syncTimerCheckPoint();
                } catch (final Throwable e) {
                    LOGGER.error("ScheduledTask SlaveSynchronize syncAll error.", e);
                }
            }, 1000 * 3, 1000 * 3, TimeUnit.MILLISECONDS);

        } else {
            if (this.slaveSyncFuture != null) {
                this.slaveSyncFuture.cancel(false);
            }
            this.brokerController.getSlaveSynchronize().setMasterAddr(null);
        }
    }

    private boolean brokerElect() {
        // Broker try to elect itself as a master in broker set.
        try {
            ElectMasterResponseHeader tryElectResponse = this.brokerOuterAPI.brokerElect(this.controllerLeaderAddress, this.brokerConfig.getBrokerClusterName(),
                    this.brokerConfig.getBrokerName(), this.brokerControllerId);
            final String masterAddress = tryElectResponse.getMasterAddress();
            final Long masterBrokerId = tryElectResponse.getMasterBrokerId();
            if (StringUtils.isEmpty(masterAddress) || masterBrokerId == null) {
                LOGGER.warn("Now no master in broker set");
                return false;
            }

            if (masterBrokerId.equals(this.brokerControllerId)) {
                changeToMaster(tryElectResponse.getMasterEpoch(), tryElectResponse.getSyncStateSetEpoch());
            } else {
                changeToSlave(masterAddress, tryElectResponse.getMasterEpoch(), tryElectResponse.getMasterBrokerId());
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to try elect", e);
            return false;
        }
    }

    public void sendHeartbeatToController() {
        final List<String> controllerAddresses = this.getAvailableControllerAddresses();
        for (String controllerAddress : controllerAddresses) {
            if (StringUtils.isNotEmpty(controllerAddress)) {
                this.brokerOuterAPI.sendHeartbeatToController(
                        controllerAddress,
                        this.brokerConfig.getBrokerClusterName(),
                        this.brokerAddress,
                        this.brokerConfig.getBrokerName(),
                        this.brokerControllerId,
                        this.brokerConfig.getSendHeartbeatTimeoutMillis(),
                        this.brokerConfig.isInBrokerContainer(), this.getLastEpoch(),
                        this.brokerController.getMessageStore().getMaxPhyOffset(),
                        this.getConfirmOffset(),
                        this.brokerConfig.getControllerHeartBeatTimeoutMills(),
                        this.brokerConfig.getBrokerElectionPriority()
                );
            }
        }
    }

    /**
     * Register broker to controller, and persist the metadata to file
     *
     * @return whether registering process succeeded
     */
    private boolean register() {
        try {
            // 1. confirm now registering state
            confirmNowRegisteringState();
            // 2. check metadata/tempMetadata if valid
            if (!checkMetadataValid()) {
                LOGGER.error("Check and find that metadata/tempMetadata invalid, you can modify the broker config to make them valid");
                return false;
            }
            // 2. get next assigning brokerId, and create temp metadata file
            if (this.registerState == RegisterState.INITIAL) {
                Long nextBrokerId = getNextBrokerId();
                if (nextBrokerId == null || !createTempMetadataFile(nextBrokerId)) {
                    LOGGER.error("Failed to create temp metadata file, nextBrokerId: {}", nextBrokerId);
                    return false;
                }
                this.registerState = RegisterState.CREATE_TEMP_METADATA_FILE_DONE;
            }
            // 3. apply brokerId to controller, and create metadata file
            if (this.registerState == RegisterState.CREATE_TEMP_METADATA_FILE_DONE) {
                if (!applyBrokerId()) {
                    // apply broker id failed, means that this brokerId has been used
                    // delete temp metadata file
                    this.tempBrokerMetadata.clear();
                    // back to the first step
                    this.registerState = RegisterState.INITIAL;
                    return false;
                }
                if (!createMetadataFileAndDeleteTemp()) {
                    LOGGER.error("Failed to create metadata file and delete temp metadata file, temp metadata: {}", this.tempBrokerMetadata);
                    return false;
                }
                this.registerState = RegisterState.CREATE_METADATA_FILE_DONE;
            }
            // 4. register
            if (this.registerState == RegisterState.CREATE_METADATA_FILE_DONE) {
                if (!registerBrokerToController()) {
                    return false;
                }
                this.registerState = RegisterState.REGISTERED;
            }
            return true;
        } catch (final Exception e) {
            LOGGER.error("Failed to register broker to controller", e);
            return false;
        }
    }

    /**
     * Send GetNextBrokerRequest to controller for getting next assigning brokerId in this broker-set
     *
     * @return next brokerId in this broker-set
     */
    private Long getNextBrokerId() {
        try {
            GetNextBrokerIdResponseHeader nextBrokerIdResp = this.brokerOuterAPI.getNextBrokerId(this.brokerConfig.getBrokerClusterName(), this.brokerConfig.getBrokerName(), this.controllerLeaderAddress);
            return nextBrokerIdResp.getNextBrokerId();
        } catch (Exception e) {
            LOGGER.error("fail to get next broker id from controller", e);
            return null;
        }
    }

    /**
     * Create temp metadata file in local file system, records the brokerId and registerCheckCode
     *
     * @param brokerId the brokerId that is expected to be assigned
     * @return whether the temp meta file is created successfully
     */

    private boolean createTempMetadataFile(Long brokerId) {
        // generate register check code, format like that: $ipAddress;$timestamp
        String registerCheckCode = this.brokerAddress + ";" + System.currentTimeMillis();
        try {
            this.tempBrokerMetadata.updateAndPersist(brokerConfig.getBrokerClusterName(), brokerConfig.getBrokerName(), brokerId, registerCheckCode);
            return true;
        } catch (Exception e) {
            LOGGER.error("update and persist temp broker metadata file failed", e);
            this.tempBrokerMetadata.clear();
            return false;
        }
    }

    /**
     * Send applyBrokerId request to controller
     *
     * @return whether controller has assigned this brokerId for this broker
     */
    private boolean applyBrokerId() {
        try {
            ApplyBrokerIdResponseHeader response = this.brokerOuterAPI.applyBrokerId(brokerConfig.getBrokerClusterName(), brokerConfig.getBrokerName(),
                    tempBrokerMetadata.getBrokerId(), tempBrokerMetadata.getRegisterCheckCode(), this.controllerLeaderAddress);
            return true;

        } catch (Exception e) {
            LOGGER.error("fail to apply broker id: {}", e, tempBrokerMetadata.getBrokerId());
            return false;
        }
    }

    /**
     * Create metadata file and delete temp metadata file
     *
     * @return whether process success
     */
    private boolean createMetadataFileAndDeleteTemp() {
        // create metadata file and delete temp metadata file
        try {
            this.brokerMetadata.updateAndPersist(brokerConfig.getBrokerClusterName(), brokerConfig.getBrokerName(), tempBrokerMetadata.getBrokerId());
            this.tempBrokerMetadata.clear();
            this.brokerControllerId = this.brokerMetadata.getBrokerId();
            return true;
        } catch (Exception e) {
            LOGGER.error("fail to create metadata file", e);
            this.brokerMetadata.clear();
            return false;
        }
    }

    /**
     * Send registerBrokerToController request to inform controller that now broker has been registered successfully and controller should update broker ipAddress if changed
     *
     * @return whether request success
     */
    private boolean registerBrokerToController() {
        try {
            RegisterBrokerToControllerResponseHeader response = this.brokerOuterAPI.registerBrokerToController(brokerConfig.getBrokerClusterName(), brokerConfig.getBrokerName(), brokerControllerId, brokerAddress, controllerLeaderAddress);
            final Long masterBrokerId = response.getMasterBrokerId();
            final String masterAddress = response.getMasterAddress();
            if (masterBrokerId == null) {
                return true;
            }
            if (this.brokerControllerId.equals(masterBrokerId)) {
                changeToMaster(response.getMasterEpoch(), response.getSyncStateSetEpoch());
            } else {
                changeToSlave(masterAddress, response.getMasterEpoch(), masterBrokerId);
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("fail to send registerBrokerToController request to controller", e);
            return false;
        }
    }

    /**
     * Confirm the registering state now
     */
    private void confirmNowRegisteringState() {
        // 1. check if metadata exist
        try {
            this.brokerMetadata.readFromFile();
        } catch (Exception e) {
            LOGGER.error("read metadata file failed", e);
        }
        if (this.brokerMetadata.isLoaded()) {
            this.registerState = RegisterState.CREATE_METADATA_FILE_DONE;
            this.brokerControllerId = brokerMetadata.getBrokerId();
            return;
        }
        // 2. check if temp metadata exist
        try {
            this.tempBrokerMetadata.readFromFile();
        } catch (Exception e) {
            LOGGER.error("read temp metadata file failed", e);
        }
        if (this.tempBrokerMetadata.isLoaded()) {
            this.registerState = RegisterState.CREATE_TEMP_METADATA_FILE_DONE;
        }
    }

    private boolean checkMetadataValid() {
        if (this.registerState == RegisterState.CREATE_TEMP_METADATA_FILE_DONE) {
            if (this.tempBrokerMetadata.getClusterName() == null || !this.tempBrokerMetadata.getClusterName().equals(this.brokerConfig.getBrokerClusterName())) {
                LOGGER.error("The clusterName: {} in broker temp metadata is different from the clusterName: {} in broker config",
                        this.tempBrokerMetadata.getClusterName(), this.brokerConfig.getBrokerClusterName());
                return false;
            }
            if (this.tempBrokerMetadata.getBrokerName() == null || !this.tempBrokerMetadata.getBrokerName().equals(this.brokerConfig.getBrokerName())) {
                LOGGER.error("The brokerName: {} in broker temp metadata is different from the brokerName: {} in broker config",
                        this.tempBrokerMetadata.getBrokerName(), this.brokerConfig.getBrokerName());
                return false;
            }
        }
        if (this.registerState == RegisterState.CREATE_METADATA_FILE_DONE) {
            if (this.brokerMetadata.getClusterName() == null || !this.brokerMetadata.getClusterName().equals(this.brokerConfig.getBrokerClusterName())) {
                LOGGER.error("The clusterName: {} in broker metadata is different from the clusterName: {} in broker config",
                        this.brokerMetadata.getClusterName(), this.brokerConfig.getBrokerClusterName());
                return false;
            }
            if (this.brokerMetadata.getBrokerName() == null || !this.brokerMetadata.getBrokerName().equals(this.brokerConfig.getBrokerName())) {
                LOGGER.error("The brokerName: {} in broker metadata is different from the brokerName: {} in broker config",
                        this.brokerMetadata.getBrokerName(), this.brokerConfig.getBrokerName());
                return false;
            }
        }
        return true;
    }

    /**
     * Scheduling sync broker metadata form controller.
     */
    private void schedulingSyncBrokerMetadata() {
        this.scheduledService.scheduleAtFixedRate(() -> {
            try {
                final Pair<GetReplicaInfoResponseHeader, SyncStateSet> result = this.brokerOuterAPI.getReplicaInfo(this.controllerLeaderAddress, this.brokerConfig.getBrokerName());
                final GetReplicaInfoResponseHeader info = result.getObject1();
                final SyncStateSet syncStateSet = result.getObject2();
                final String newMasterAddress = info.getMasterAddress();
                final int newMasterEpoch = info.getMasterEpoch();
                final Long masterBrokerId = info.getMasterBrokerId();
                synchronized (this) {
                    // Check if master changed
                    if (newMasterEpoch > this.masterEpoch) {
                        if (StringUtils.isNoneEmpty(newMasterAddress) && masterBrokerId != null) {
                            if (masterBrokerId.equals(this.brokerControllerId)) {
                                // If this broker is now the master
                                changeToMaster(newMasterEpoch, syncStateSet.getSyncStateSetEpoch());
                            } else {
                                // If this broker is now the slave, and master has been changed
                                changeToSlave(newMasterAddress, newMasterEpoch, masterBrokerId);
                            }
                        } else {
                            // In this case, the master in controller is null, try elect in controller, this will trigger the electMasterEvent in controller.
                            brokerElect();
                        }
                    } else if (newMasterEpoch == this.masterEpoch) {
                        // Check if SyncStateSet changed
                        if (isMasterState()) {
                            changeSyncStateSet(syncStateSet.getSyncStateSet(), syncStateSet.getSyncStateSetEpoch());
                        }
                    }
                }
            } catch (final MQBrokerException exception) {
                LOGGER.warn("Error happen when get broker {}'s metadata", this.brokerConfig.getBrokerName(), exception);
                if (exception.getResponseCode() == CONTROLLER_BROKER_METADATA_NOT_EXIST) {
                    try {
                        registerBrokerToController();
                        TimeUnit.SECONDS.sleep(2);
                    } catch (InterruptedException ignore) {

                    }
                }
            } catch (final Exception e) {
                LOGGER.warn("Error happen when get broker {}'s metadata", this.brokerConfig.getBrokerName(), e);
            }
        }, 3 * 1000, this.brokerConfig.getSyncBrokerMetadataPeriod(), TimeUnit.MILLISECONDS);
    }

    /**
     * Scheduling sync controller medata.
     */
    private boolean schedulingSyncControllerMetadata() {
        // Get controller metadata first.
        int tryTimes = 0;
        while (tryTimes < 3) {
            boolean flag = updateControllerMetadata();
            if (flag) {
                this.scheduledService.scheduleAtFixedRate(this::updateControllerMetadata, 1000 * 3, this.brokerConfig.getSyncControllerMetadataPeriod(), TimeUnit.MILLISECONDS);
                return true;
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException ignore) {

            }
            tryTimes++;
        }
        LOGGER.error("Failed to init controller metadata, maybe the controllers in {} is not available", this.controllerAddresses);
        return false;
    }

    /**
     * Update controller leader address by rpc.
     */
    private boolean updateControllerMetadata() {
        for (String address : this.availableControllerAddresses.keySet()) {
            try {
                final GetMetaDataResponseHeader responseHeader = this.brokerOuterAPI.getControllerMetaData(address);
                if (responseHeader != null && StringUtils.isNoneEmpty(responseHeader.getControllerLeaderAddress())) {
                    this.controllerLeaderAddress = responseHeader.getControllerLeaderAddress();
                    LOGGER.info("Update controller leader address to {}", this.controllerLeaderAddress);
                    return true;
                }
            } catch (final Exception e) {
                LOGGER.error("Failed to update controller metadata", e);
            }
        }
        return false;
    }

    /**
     * Scheduling check syncStateSet.
     */
    private void schedulingCheckSyncStateSet() {
        if (this.checkSyncStateSetTaskFuture != null) {
            this.checkSyncStateSetTaskFuture.cancel(false);
        }
        this.checkSyncStateSetTaskFuture = this.scheduledService.scheduleAtFixedRate(() -> {
            final Set<Long> newSyncStateSet = this.haService.maybeShrinkSyncStateSet();
            newSyncStateSet.add(this.brokerControllerId);
            synchronized (this) {
                if (this.syncStateSet != null) {
                    // Check if syncStateSet changed
                    if (this.syncStateSet.size() == newSyncStateSet.size() && this.syncStateSet.containsAll(newSyncStateSet)) {
                        return;
                    }
                }
            }
            doReportSyncStateSetChanged(newSyncStateSet);
        }, 3 * 1000, this.brokerConfig.getCheckSyncStateSetPeriod(), TimeUnit.MILLISECONDS);
    }

    private void doReportSyncStateSetChanged(Set<Long> newSyncStateSet) {
        try {
            final SyncStateSet result = this.brokerOuterAPI.alterSyncStateSet(this.controllerLeaderAddress, this.brokerConfig.getBrokerName(), this.brokerControllerId, this.masterEpoch, newSyncStateSet, this.syncStateSetEpoch);
            if (result != null) {
                changeSyncStateSet(result.getSyncStateSet(), result.getSyncStateSetEpoch());
            }
        } catch (final Exception e) {
            LOGGER.error("Error happen when change SyncStateSet, broker:{}, masterAddress:{}, masterEpoch:{}, oldSyncStateSet:{}, newSyncStateSet:{}, syncStateSetEpoch:{}",
                    this.brokerConfig.getBrokerName(), this.masterAddress, this.masterEpoch, this.syncStateSet, newSyncStateSet, this.syncStateSetEpoch, e);
        }
    }

    private void stopCheckSyncStateSet() {
        if (this.checkSyncStateSetTaskFuture != null) {
            this.checkSyncStateSetTaskFuture.cancel(false);
        }
    }

    private void scanAvailableControllerAddresses() {
        if (controllerAddresses == null) {
            LOGGER.warn("scanAvailableControllerAddresses addresses of controller is null!");
            return;
        }

        for (String address : availableControllerAddresses.keySet()) {
            if (!controllerAddresses.contains(address)) {
                LOGGER.warn("scanAvailableControllerAddresses remove invalid address {}", address);
                availableControllerAddresses.remove(address);
            }
        }

        for (String address : controllerAddresses) {
            scanExecutor.submit(() -> {
                if (brokerOuterAPI.checkAddressReachable(address)) {
                    availableControllerAddresses.putIfAbsent(address, true);
                } else {
                    Boolean value = availableControllerAddresses.remove(address);
                    if (value != null) {
                        LOGGER.warn("scanAvailableControllerAddresses remove unconnected address {}", address);
                    }
                }
            });
        }
    }

    private void updateControllerAddr() {
        if (brokerConfig.isFetchControllerAddrByDnsLookup()) {
            this.controllerAddresses = brokerOuterAPI.dnsLookupAddressByDomain(this.brokerConfig.getControllerAddr());
        } else {
            final String controllerPaths = this.brokerConfig.getControllerAddr();
            final String[] controllers = controllerPaths.split(";");
            assert controllers.length > 0;
            this.controllerAddresses = Arrays.asList(controllers);
        }
    }

    public int getLastEpoch() {
        return this.haService.getLastEpoch();
    }

    public BrokerRole getBrokerRole() {
        return this.brokerController.getMessageStoreConfig().getBrokerRole();
    }

    public boolean isMasterState() {
        return getBrokerRole() == BrokerRole.SYNC_MASTER;
    }

    public SyncStateSet getSyncStateSet() {
        return new SyncStateSet(this.syncStateSet, this.syncStateSetEpoch);
    }

    public String getBrokerAddress() {
        return brokerAddress;
    }

    public String getMasterAddress() {
        return masterAddress;
    }

    public int getMasterEpoch() {
        return masterEpoch;
    }

    public List<String> getControllerAddresses() {
        return controllerAddresses;
    }

    public List<EpochEntry> getEpochEntries() {
        return this.haService.getEpochEntries();
    }

    public List<String> getAvailableControllerAddresses() {
        return new ArrayList<>(availableControllerAddresses.keySet());
    }

    public Long getBrokerControllerId() {
        return brokerControllerId;
    }

    public RegisterState getRegisterState() {
        return registerState;
    }

    public State getState() {
        return state;
    }

    public BrokerMetadata getBrokerMetadata() {
        return brokerMetadata;
    }

    public TempBrokerMetadata getTempBrokerMetadata() {
        return tempBrokerMetadata;
    }
}
