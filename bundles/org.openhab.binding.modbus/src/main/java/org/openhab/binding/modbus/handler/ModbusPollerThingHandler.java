/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.modbus.handler;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.internal.AtomicStampedValue;
import org.openhab.binding.modbus.internal.ModbusBindingConstantsInternal;
import org.openhab.binding.modbus.internal.config.ModbusPollerConfiguration;
import org.openhab.binding.modbus.internal.handler.ModbusDataThingHandler;
import org.openhab.core.io.transport.modbus.AsyncModbusFailure;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.ModbusCommunicationInterface;
import org.openhab.core.io.transport.modbus.ModbusConstants;
import org.openhab.core.io.transport.modbus.ModbusFailureCallback;
import org.openhab.core.io.transport.modbus.ModbusReadCallback;
import org.openhab.core.io.transport.modbus.ModbusReadFunctionCode;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.io.transport.modbus.PollTask;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ModbusPollerThingHandler} is responsible for polling Modbus slaves. Errors and data is delegated to
 * child thing handlers inheriting from {@link ModbusReadCallback} -- in practice: {@link ModbusDataThingHandler}.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ModbusPollerThingHandler extends BaseBridgeHandler {
    private long callbackGeneration;

    /**
     * {@link ModbusReadCallback} that delegates all tasks forward.
     *
     * All instances of {@linkplain ReadCallbackDelegator} are considered equal, if they are connected to the same
     * bridge. This makes sense, as the callback delegates
     * to all child things of this bridge.
     *
     * @author Sami Salonen - Initial contribution
     *
     */
    private class ReadCallbackDelegator
            implements ModbusReadCallback, ModbusFailureCallback<ModbusReadRequestBlueprint> {

        private final long generation;
        private final @Nullable List<ModbusDataThingHandler.ReadAuthorization> authorizations;
        private volatile @Nullable AtomicStampedValue<PollResult> lastResult;

        ReadCallbackDelegator() {
            generation = ++callbackGeneration;
            authorizations = null;
        }

        ReadCallbackDelegator(long generation) {
            this.generation = generation;
            authorizations = childCallbacks.stream()
                    .map(child -> child.captureReadAuthorization(ModbusPollerThingHandler.this)).toList();
        }

        private List<ModbusDataThingHandler.ReadAuthorization> captureAuthorizations() {
            List<ModbusDataThingHandler.ReadAuthorization> captured = authorizations;
            return captured != null ? captured
                    : childCallbacks.stream()
                            .map(child -> child.captureReadAuthorization(ModbusPollerThingHandler.this)).toList();
        }

        private void handleResult(PollResult result) {
            // The handler monitor serializes lifecycle changes with decoding and publication.
            if (generation != callbackGeneration || hasConfigurationError() || disposed) {
                return;
            }
            if (config.getCacheMillis() >= 0) {
                AtomicStampedValue<PollResult> localLastResult = callbackDelegator.lastResult;
                if (localLastResult == null) {
                    callbackDelegator.lastResult = new AtomicStampedValue<>(System.currentTimeMillis(), result);
                } else {
                    localLastResult.update(System.currentTimeMillis(), result);
                    callbackDelegator.lastResult = localLastResult;
                }
            }
            logger.debug("Thing {} received response {}", thing.getUID(), result);
            notifyChildren(result, false);
            if (generation != callbackGeneration || disposed) {
                return;
            }
            if (result.failure != null) {
                Exception error = result.failure.getCause();
                assert error != null;
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        String.format("Error with read: %s: %s", error.getClass().getName(), error.getMessage()));
            } else {
                resetCommunicationError();
            }
        }

        @Override
        public void handle(AsyncModbusReadResult result) {
            synchronized (ModbusPollerThingHandler.this) {
                if (generation != callbackGeneration || disposed || hasConfigurationError()) {
                    return;
                }
                // Casting to allow registers.orElse(null) below..
                Optional<@Nullable ModbusRegisterArray> registers = (Optional<@Nullable ModbusRegisterArray>) result
                        .getRegisters();
                lastPolledDataCache.set(registers.orElse(null));
                handleResult(new PollResult(result, captureAuthorizations()));
            }
        }

        @Override
        public void handle(AsyncModbusFailure<ModbusReadRequestBlueprint> failure) {
            synchronized (ModbusPollerThingHandler.this) {
                handleResult(new PollResult(failure, captureAuthorizations()));
            }
        }

        private void resetCommunicationError() {
            ThingStatusInfo statusInfo = thing.getStatusInfo();
            if (ThingStatus.OFFLINE.equals(statusInfo.getStatus())
                    && ThingStatusDetail.COMMUNICATION_ERROR.equals(statusInfo.getStatusDetail())) {
                updateStatus(ThingStatus.ONLINE);
            }
        }

        /**
         * Update children data if data is fresh enough
         *
         * @param oldestStamp oldest data that is still passed to children
         * @return whether data was updated. Data is not updated when it's too old or there's no data at all.
         */
        @SuppressWarnings("null")
        public boolean updateChildrenWithOldData(long oldestStamp) {
            return Optional.ofNullable(this.lastResult).map(result -> result.copyIfStampAfter(oldestStamp))
                    .map(result -> {
                        logger.debug("Thing {} reusing cached data: {}", thing.getUID(), result.getValue());
                        notifyChildren(result.getValue(), true);
                        return true;
                    }).orElse(false);
        }

        private void notifyChildren(PollResult pollResult, boolean cached) {
            @Nullable
            AsyncModbusReadResult result = pollResult.result;
            @Nullable
            AsyncModbusFailure<ModbusReadRequestBlueprint> failure = pollResult.failure;
            // A cached result retains its original child permissions, including failures.
            for (ModbusDataThingHandler.ReadAuthorization authorization : pollResult.authorizations) {
                if (result != null) {
                    authorization.deliver(result, cached);
                } else if (failure != null) {
                    authorization.deliver(failure);
                }
            }
            if (generation != callbackGeneration || disposed) {
                return;
            }
            if (result != null) {
                onPollResult(result, cached);
            } else if (failure != null) {
                onPollFailure(failure);
            }
        }

        /**
         * Rest data caches
         */
        public void resetCache() {
            lastResult = null;
        }
    }

    /**
     * Receives a successful poll result after it has been delivered to legacy child handlers.
     *
     * @param result successful Modbus read result
     * @param cached whether this is a replay rather than a new device response
     */
    protected void onPollResult(AsyncModbusReadResult result, boolean cached) {
        // Subclasses may consume poller-owned channels.
    }

    /**
     * Receives a failed poll result after it has been delivered to legacy child handlers.
     *
     * @param failure failed Modbus read result
     */
    protected void onPollFailure(AsyncModbusFailure<ModbusReadRequestBlueprint> failure) {
        // Subclasses may update poller-owned channels.
    }

    /**
     * Immutable data object to cache the results of a poll request
     */
    private class PollResult {

        public final @Nullable AsyncModbusReadResult result;
        public final @Nullable AsyncModbusFailure<ModbusReadRequestBlueprint> failure;
        private final List<ModbusDataThingHandler.ReadAuthorization> authorizations;

        PollResult(AsyncModbusReadResult result, List<ModbusDataThingHandler.ReadAuthorization> authorizations) {
            this.result = result;
            this.failure = null;
            this.authorizations = authorizations;
        }

        PollResult(AsyncModbusFailure<ModbusReadRequestBlueprint> failure,
                List<ModbusDataThingHandler.ReadAuthorization> authorizations) {
            this.result = null;
            this.failure = failure;
            this.authorizations = authorizations;
        }

        @Override
        public String toString() {
            return failure == null ? String.format("PollResult(result=%s)", result)
                    : String.format("PollResult(failure=%s)", failure);
        }
    }

    private final Logger logger = LoggerFactory.getLogger(ModbusPollerThingHandler.class);

    private static final List<String> SORTED_READ_FUNCTION_CODES = ModbusBindingConstantsInternal.READ_FUNCTION_CODES
            .keySet().stream().sorted().collect(Collectors.toUnmodifiableList());

    private @NonNullByDefault({}) ModbusPollerConfiguration config;
    private long cacheMillis;
    private volatile @Nullable PollTask pollTask;
    private volatile @Nullable ReadConfiguration readConfiguration;

    private record ReadConfiguration(ModbusReadRequestBlueprint request, ModbusCommunicationInterface communication,
            ModbusEndpointThingHandler endpoint, String signature) {
    }

    private String readConfigurationSignature() {
        Bridge endpoint = getBridge();
        return org.openhab.binding.modbus.migration.Poller2ObservationCollector.signature(thing) + ":"
                + (endpoint == null || endpoint.getConfiguration() == null ? ""
                        : org.openhab.binding.modbus.migration.Poller2ObservationCollector.signature(endpoint));
    }

    /** Whether the registered transport still represents the current endpoint and poll window configuration. */
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // Equal contents cannot authorize a replaced transport instance.
    public boolean hasCurrentReadConfiguration() {
        ReadConfiguration context = readConfiguration;
        Bridge endpoint = getBridge();
        if (context == null || disposed || endpoint == null || endpoint.getHandler() != context.endpoint()
                || request != context.request() || comms != context.communication()
                || !context.signature().equals(readConfigurationSignature())) {
            return false;
        }
        return context.endpoint().getCommunicationInterface() == context.communication();
    }

    private volatile @Nullable ModbusReadRequestBlueprint request;
    private volatile boolean disposed;
    private volatile List<ModbusDataThingHandler> childCallbacks = new CopyOnWriteArrayList<>();
    private volatile AtomicReference<@Nullable ModbusRegisterArray> lastPolledDataCache = new AtomicReference<>();
    private @NonNullByDefault({}) ModbusCommunicationInterface comms;

    private ReadCallbackDelegator callbackDelegator = new ReadCallbackDelegator();

    private @Nullable ModbusReadFunctionCode functionCode;

    public ModbusPollerThingHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // No channels, no commands
    }

    private @Nullable ModbusEndpointThingHandler getEndpointThingHandler() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            logger.debug("Bridge is null");
            return null;
        }
        if (bridge.getStatus() != ThingStatus.ONLINE) {
            logger.debug("Bridge is not online");
            return null;
        }

        ThingHandler handler = bridge.getHandler();
        if (handler == null) {
            logger.debug("Bridge handler is null");
            return null;
        }

        if (handler instanceof ModbusEndpointThingHandler thingHandler) {
            return thingHandler;
        } else {
            logger.debug("Unexpected bridge handler: {}", handler);
            return null;
        }
    }

    @Override
    public synchronized void initialize() {
        dispose();
        if (this.getThing().getStatus().equals(ThingStatus.ONLINE)) {
            // If the bridge was online then first change it to offline.
            // this ensures that children will be notified about the change
            updateStatus(ThingStatus.OFFLINE);
        }
        this.callbackDelegator.resetCache();
        comms = null;
        this.callbackDelegator = new ReadCallbackDelegator();
        request = null;
        disposed = false;
        logger.trace("Initializing {} from status {}", this.getThing().getUID(), this.getThing().getStatus());
        try {
            config = getConfigAs(ModbusPollerConfiguration.class);
            String type = config.getType();
            if (!ModbusBindingConstantsInternal.READ_FUNCTION_CODES.containsKey(type)) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        String.format("No function code found for type='%s'. Was expecting one of: %s", type,
                                String.join(", ", SORTED_READ_FUNCTION_CODES)));
                return;
            }
            functionCode = ModbusBindingConstantsInternal.READ_FUNCTION_CODES.get(type);
            switch (functionCode) {
                case READ_INPUT_REGISTERS:
                case READ_MULTIPLE_REGISTERS:
                    if (config.getLength() > ModbusConstants.MAX_REGISTERS_READ_COUNT) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, String.format(
                                "Maximum of %d registers can be polled at once due to protocol limitations. Length %d is out of bounds.",
                                ModbusConstants.MAX_REGISTERS_READ_COUNT, config.getLength()));
                        return;
                    }
                    break;
                case READ_COILS:
                case READ_INPUT_DISCRETES:
                    if (config.getLength() > ModbusConstants.MAX_BITS_READ_COUNT) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, String.format(
                                "Maximum of %d coils/discrete inputs can be polled at once due to protocol limitations. Length %d is out of bounds.",
                                ModbusConstants.MAX_BITS_READ_COUNT, config.getLength()));
                        return;
                    }
                    break;
            }
            cacheMillis = this.config.getCacheMillis();
            registerPollTask();
        } catch (EndpointNotInitializedException e) {
            logger.debug("Exception during initialization", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, String
                    .format("Exception during initialization: %s (%s)", e.getMessage(), e.getClass().getSimpleName()));
        } finally {
            logger.trace("initialize() of thing {} '{}' finished", thing.getUID(), thing.getLabel());
        }
    }

    @Override
    public synchronized void dispose() {
        logger.debug("dispose()");
        // Mark handler as disposed as soon as possible to halt processing of callbacks
        disposed = true;
        unregisterPollTask();
        this.callbackDelegator.resetCache();
        comms = null;
        lastPolledDataCache.set(null);
    }

    /**
     * Unregister poll task.
     *
     * No-op in case no poll task is registered, or if the initialization is incomplete.
     */
    public synchronized void unregisterPollTask() {
        logger.trace("unregisterPollTask()");
        this.callbackDelegator = new ReadCallbackDelegator();
        if (config == null) {
            return;
        }
        PollTask localPollTask = this.pollTask;
        if (localPollTask != null) {
            logger.debug("Unregistering polling from ModbusManager");
            comms.unregisterRegularPoll(localPollTask);
        }
        this.pollTask = null;
        request = null;
        comms = null;
        updateStatus(ThingStatus.OFFLINE);
    }

    /**
     * Register poll task
     *
     * @throws EndpointNotInitializedException in case the bridge initialization is not complete. This should only
     *             happen in transient conditions, for example, when bridge is initializing.
     */
    @SuppressWarnings("null")
    private synchronized void registerPollTask() throws EndpointNotInitializedException {
        logger.trace("registerPollTask()");
        if (pollTask != null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR);
            logger.debug("pollTask should be unregistered before registering a new one!");
            return;
        }

        ModbusEndpointThingHandler slaveEndpointThingHandler = getEndpointThingHandler();
        if (slaveEndpointThingHandler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, String.format("Bridge '%s' is offline",
                    Optional.ofNullable(getBridge()).map(b -> b.getLabel()).orElse("<null>")));
            logger.debug("No bridge handler available -- aborting init for {}", this);
            return;
        }
        ModbusCommunicationInterface localComms = slaveEndpointThingHandler.getCommunicationInterface();
        if (localComms == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, String.format(
                    "Bridge '%s' not completely initialized", Optional.ofNullable(getBridge()).map(b -> b.getLabel())));
            logger.debug("Bridge not initialized fully (no communication interface) -- aborting init for {}", this);
            return;
        }
        this.comms = localComms;
        ModbusReadFunctionCode localFunctionCode = functionCode;
        if (localFunctionCode == null) {
            return;
        }

        ModbusReadRequestBlueprint localRequest = new ModbusReadRequestBlueprint(slaveEndpointThingHandler.getSlaveId(),
                localFunctionCode, config.getStart(), config.getLength(), config.getMaxTries());
        this.request = localRequest;
        readConfiguration = new ReadConfiguration(localRequest, localComms, slaveEndpointThingHandler,
                readConfigurationSignature());

        if (config.getRefresh() <= 0L) {
            logger.debug("Not registering polling with ModbusManager since refresh disabled");
            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, "Not polling");
        } else {
            logger.debug("Registering polling with ModbusManager");
            pollTask = localComms.registerRegularPoll(localRequest, config.getRefresh(), 0, callbackDelegator,
                    callbackDelegator);
            assert pollTask != null;
            updateStatus(ThingStatus.ONLINE);
        }
    }

    private boolean hasConfigurationError() {
        ThingStatusInfo statusInfo = getThing().getStatusInfo();
        return statusInfo.getStatus() == ThingStatus.OFFLINE
                && statusInfo.getStatusDetail() == ThingStatusDetail.CONFIGURATION_ERROR;
    }

    @Override
    public synchronized void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        logger.debug("bridgeStatusChanged for {}. Reseting handler", this.getThing().getUID());
        this.dispose();
        this.initialize();
    }

    @Override
    public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
        if (childHandler instanceof ModbusDataThingHandler modbusDataThingHandler) {
            this.childCallbacks.add(modbusDataThingHandler);
        }
    }

    @SuppressWarnings("unlikely-arg-type")
    @Override
    public void childHandlerDisposed(ThingHandler childHandler, Thing childThing) {
        if (childHandler instanceof ModbusDataThingHandler) {
            this.childCallbacks.remove(childHandler);
        }
    }

    /**
     * Return {@link ModbusReadRequestBlueprint} represented by this thing.
     *
     * Note that request might be <code>null</code> in case initialization is not complete.
     *
     * @return modbus request represented by this poller
     */
    public @Nullable ModbusReadRequestBlueprint getRequest() {
        return request;
    }

    /**
     * Get communication interface associated with this poller
     *
     * @return
     */
    public ModbusCommunicationInterface getCommunicationInterface() {
        return comms;
    }

    /**
     * Refresh the data
     *
     * If data or error was just recently received (i.e. cache is fresh), return the cached response.
     */
    public void refresh() {
        RefreshContext context = prepareRefresh();
        if (context != null) {
            // Transport submission must not hold the lifecycle monitor: synchronous callbacks may acquire other locks.
            context.communication().submitOneTimePoll(context.request(), context.callbacks(), context.callbacks());
        }
    }

    private record RefreshContext(ModbusReadRequestBlueprint request, ModbusCommunicationInterface communication,
            ReadCallbackDelegator callbacks) {
    }

    private synchronized @Nullable RefreshContext prepareRefresh() {
        ModbusReadRequestBlueprint localRequest = this.request;
        ModbusCommunicationInterface localComms = comms;
        ReadCallbackDelegator localCallbacks = callbackDelegator;
        if (localRequest == null || localComms == null || disposed || hasConfigurationError()) {
            return null;
        }
        ModbusRegisterArray possiblyMutatedCache = lastPolledDataCache.get();
        AtomicStampedValue<PollResult> lastPollResult = localCallbacks.lastResult;
        if (lastPollResult != null && possiblyMutatedCache != null) {
            AsyncModbusReadResult lastSuccessfulPollResult = lastPollResult.getValue().result;
            if (lastSuccessfulPollResult != null) {
                ModbusRegisterArray lastRegisters = ((Optional<@Nullable ModbusRegisterArray>) lastSuccessfulPollResult
                        .getRegisters()).orElse(null);
                if (lastRegisters != null && !possiblyMutatedCache.equals(lastRegisters)) {
                    // Register has been mutated in between by a data thing that writes "individual bits"
                    // Invalidate cache for a fresh poll
                    localCallbacks.resetCache();
                }
            }
        }

        long oldDataThreshold = System.currentTimeMillis() - cacheMillis;
        boolean cacheWasRecentEnoughForUpdate = cacheMillis > 0
                && localCallbacks.updateChildrenWithOldData(oldDataThreshold);
        if (cacheWasRecentEnoughForUpdate) {
            logger.debug(
                    "Poller {} received refresh() and cache was recent enough (age at most {} ms). Reusing old response",
                    getThing().getUID(), cacheMillis);
            return null;
        }
        // Captured callbacks reject results if this context is superseded before submission.
        return new RefreshContext(localRequest, localComms, new ReadCallbackDelegator(callbackGeneration));
    }

    /**
     * Schedules a poll that bypasses the read response cache.
     */
    protected void reconcileAfterWrite() {
        prepareWriteReconciliation().run();
    }

    /** Captures a generation-bound reconciliation; callers must run it outside the lifecycle monitor. */
    protected synchronized Runnable prepareWriteReconciliation() {
        callbackDelegator.resetCache();
        RefreshContext context = prepareRefresh();
        return () -> {
            if (context != null) {
                context.communication().submitOneTimePoll(context.request(), context.callbacks(), context.callbacks());
            }
        };
    }

    public AtomicReference<@Nullable ModbusRegisterArray> getLastPolledDataCache() {
        return lastPolledDataCache;
    }
}
