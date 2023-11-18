/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
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

import static org.openhab.binding.modbus.ModbusBindingConstants.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.config.ReadChannelConfiguration;
import org.openhab.binding.modbus.config.WriteChannelConfiguration;
import org.openhab.binding.modbus.internal.AtomicStampedValue;
import org.openhab.binding.modbus.internal.ChannelConfigValidationMessage;
import org.openhab.binding.modbus.internal.ModbusBindingConstantsInternal;
import org.openhab.binding.modbus.internal.config.ModbusPollerConfiguration;
import org.openhab.binding.modbus.internal.handler.ModbusDataThingHandler;
import org.openhab.binding.modbus.internal.handler.ReadIntoChannelHandler;
import org.openhab.binding.modbus.internal.handler.ReadIntoHexStringChannelHandler;
import org.openhab.binding.modbus.internal.handler.ReadIntoNumberChannelHandler;
import org.openhab.binding.modbus.internal.handler.ReadIntoOnOffChannelHandler;
import org.openhab.binding.modbus.internal.handler.ReadIntoOpenClosedChannelHandler;
import org.openhab.binding.modbus.internal.handler.ReadIntoPercentChannelHandler;
import org.openhab.binding.modbus.internal.handler.RegisterCache;
import org.openhab.binding.modbus.internal.handler.WriteChannelHandler;
import org.openhab.binding.modbus.internal.handler.WriteCoilFromNumberHandler;
import org.openhab.binding.modbus.internal.handler.WriteCoilFromOnOffHandler;
import org.openhab.binding.modbus.internal.handler.WriteRegisterFromNumberHandler;
import org.openhab.binding.modbus.internal.handler.WriteRegisterFromOnOffHandler;
import org.openhab.binding.modbus.internal.handler.WriteRegisterFromOpenClosedHandler;
import org.openhab.binding.modbus.internal.handler.WriteRegisterFromPercentHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.modbus.AsyncModbusFailure;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.AsyncModbusWriteResult;
import org.openhab.core.io.transport.modbus.ModbusCommunicationInterface;
import org.openhab.core.io.transport.modbus.ModbusConstants;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.io.transport.modbus.ModbusFailureCallback;
import org.openhab.core.io.transport.modbus.ModbusReadCallback;
import org.openhab.core.io.transport.modbus.ModbusReadFunctionCode;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.io.transport.modbus.ModbusWriteCallback;
import org.openhab.core.io.transport.modbus.ModbusWriteCoilRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteRegisterRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprintVisitor;
import org.openhab.core.io.transport.modbus.PollTask;
import org.openhab.core.io.transport.modbus.exception.ModbusTransportException;
import org.openhab.core.io.transport.modbus.exception.ModbusUnexpectedResponseFunctionCodeException;
import org.openhab.core.io.transport.modbus.exception.ModbusUnexpectedResponseSizeException;
import org.openhab.core.io.transport.modbus.exception.ModbusUnexpectedTransactionIdException;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ModbusPollerThingHandler} is responsible for polling Modbus slaves. Errors and data is delegated to
 * child thing handlers inheriting from {@link ModbusReadCallback} -- in practice: {@link ModbusDataThingHandler}.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ModbusPollerThingHandler extends BaseBridgeHandler implements RegisterCache,
        Consumer<ModbusWriteRequestBlueprint>, ModbusWriteCallback, ModbusFailureCallback<ModbusWriteRequestBlueprint> {

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

        private volatile @Nullable AtomicStampedValue<PollResult> lastResult;

        public synchronized void handleResult(PollResult result) {
            // Ignore all incoming data and errors if configuration is not correct
            if (hasConfigurationError() || disposed) {
                return;
            }
            if (config.getCacheMillis() >= 0) {
                AtomicStampedValue<PollResult> localLastResult = this.lastResult;
                if (localLastResult == null) {
                    this.lastResult = new AtomicStampedValue<>(System.currentTimeMillis(), result);
                } else {
                    localLastResult.update(System.currentTimeMillis(), result);
                    this.lastResult = localLastResult;
                }
            }
            logger.debug("Thing {} received response {}", thing.getUID(), result);
            notifyChildren(result);
            if (result.failure != null) {
                handleCommunicationError(true, result.failure);
            } else {
                resetCommunicationError();
            }
        }

        private <R> void handleCommunicationError(boolean read, AsyncModbusFailure<R> result) {
            ThingStatusInfo statusInfo = thing.getStatusInfo();
            Exception error = result.getCause();
            String message = String.format("Error with %s: %s (%s)", read ? "read" : "write", error.getMessage(),
                    error.getClass().getSimpleName());

            // Avoid status spamming when there is no change
            if (!ThingStatus.OFFLINE.equals(statusInfo.getStatus())
                    || !ThingStatusDetail.COMMUNICATION_ERROR.equals(statusInfo.getStatusDetail())
                    || !message.equals(statusInfo.getDescription())) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, message);
            }
        }

        @Override
        public synchronized void handle(AsyncModbusReadResult result) {
            // Casting to allow registers.orElse(null) below..
            Optional<@Nullable ModbusRegisterArray> registers = (Optional<@Nullable ModbusRegisterArray>) result
                    .getRegisters();
            lastPolledDataCache.set(registers.orElse(null));
            handleResult(new PollResult(result));
        }

        @Override
        public synchronized void handle(AsyncModbusFailure<ModbusReadRequestBlueprint> failure) {
            handleResult(new PollResult(failure));
        }

        /**
         * Update children data if data is fresh enough
         *
         * @param oldestStamp oldest data that is still passed to children
         * @return whether data was updated. Data is not updated when it's too old or there's no data at all.
         */
        public boolean updateChildrenWithOldData(long oldestStamp) {
            return Optional.ofNullable(this.lastResult).map(result -> result.copyIfStampAfter(oldestStamp))
                    .map(result -> {
                        logger.debug("Thing {} reusing cached data: {}", thing.getUID(), result.getValue());
                        notifyChildren(result.getValue());
                        return true;
                    }).orElse(false);
        }

        private void notifyChildren(PollResult pollResult) {
            @Nullable
            AsyncModbusReadResult result = pollResult.result;
            @Nullable
            AsyncModbusFailure<ModbusReadRequestBlueprint> failure = pollResult.failure;
            childCallbacks.forEach(handler -> {
                if (result != null) {
                    handler.onReadResult(result);
                } else if (failure != null) {
                    handler.handleReadError(failure);
                }
            });
            readChannelHandlers.forEach((channelUID, handler) -> {
                if (result != null) {
                    handler.handle(result);
                } else if (failure != null) {
                    handler.handle(failure);
                }
            });
        }

        /**
         * Rest data caches
         */
        public void resetCache() {
            lastResult = null;
        }
    }

    /**
     * Immutable data object to cache the results of a poll request
     */
    private class PollResult {

        public final @Nullable AsyncModbusReadResult result;
        public final @Nullable AsyncModbusFailure<ModbusReadRequestBlueprint> failure;

        PollResult(AsyncModbusReadResult result) {
            this.result = result;
            this.failure = null;
        }

        PollResult(AsyncModbusFailure<ModbusReadRequestBlueprint> failure) {
            this.result = null;
            this.failure = failure;
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
    private volatile @Nullable ModbusReadRequestBlueprint request;
    private volatile boolean disposed;
    private volatile List<ModbusDataThingHandler> childCallbacks = new CopyOnWriteArrayList<>();
    private volatile AtomicReference<@Nullable ModbusRegisterArray> lastPolledDataCache = new AtomicReference<>();
    private volatile Map<ChannelUID, ReadIntoChannelHandler> readChannelHandlers = new ConcurrentHashMap<>();
    private volatile Map<ChannelUID, WriteChannelHandler> writeChannelHandlers = new ConcurrentHashMap<>();
    private @NonNullByDefault({}) ModbusCommunicationInterface comms;
    private volatile int slaveId;

    private ReadCallbackDelegator callbackDelegator = new ReadCallbackDelegator();

    private @Nullable ModbusReadFunctionCode functionCode;

    public ModbusPollerThingHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (RefreshType.REFRESH == command) {
            refresh();
        } else {
            WriteChannelHandler handler = writeChannelHandlers.get(channelUID);
            if (handler == null) {
                return;
            }
            handler.processCommand(command);
        }
    }

    private @Nullable ModbusEndpointThingHandler getEndpointThingHandler() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            logger.debug("Bridge is null");
            return null;
        }
        if (bridge.getStatus() != ThingStatus.ONLINE && bridge.getStatus() != ThingStatus.UNKNOWN
                && bridge.getStatusInfo().getStatusDetail() != ThingStatusDetail.COMMUNICATION_ERROR) {
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
        if (this.getThing().getStatus().equals(ThingStatus.ONLINE)) {
            // If the bridge was online then first change it to offline.
            // this ensures that children will be notified about the change
            updateStatus(ThingStatus.OFFLINE);
        }
        this.callbackDelegator.resetCache();
        comms = null;
        request = null;
        readChannelHandlers.clear();
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
            boolean initOk = initCommsAndValidateEndpointBridge();
            if (!initOk) {
                // Thing status already updated
                return;
            }
            if (initializeChannelHandlers()) {
                registerPollTask();
            }
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
        readChannelHandlers.clear();
        writeChannelHandlers.clear();
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

    private synchronized boolean initializeChannelHandlers() {
        boolean allValid = true;
        List<ChannelConfigValidationMessage> validationErrors = new ArrayList<>();
        for (Channel channel : thing.getChannels()) {
            List<ChannelConfigValidationMessage> validationForThisChannel = initChannelHandler(channel,
                    channel.getConfiguration());
            allValid &= validationForThisChannel.isEmpty();
            validationErrors.addAll(validationForThisChannel);
        }
        // TODO: format errors in a nice summary channel x erroFcrs: .., channel y errors: ...
        if (!validationErrors.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR);
        }
        return allValid;
    }

    private List<ChannelConfigValidationMessage> initChannelHandler(Channel channel, Configuration configuration) {
        final List<ChannelConfigValidationMessage> validationErrors;

        ChannelUID channelUID = channel.getUID();
        ChannelTypeUID channelTypeUID = channel.getChannelTypeUID();
        Objects.requireNonNull(channelTypeUID, "channel type not defined for " + channelUID);
        String channelTypeId = channelTypeUID.getId();
        ValueType valueType = null;
        boolean writingCoil = false;
        ModbusReadFunctionCode localFunctionCode = functionCode;
        // required in xml config description, cannot be null
        Objects.requireNonNull(localFunctionCode, "poller function code unknown");
        int pollStart = config.getStart();
        switch (channelTypeId) {
            case CHANNEL_READ_INTO_NUMBER:
            case CHANNEL_READ_INTO_PERCENT_COLOR:
            case CHANNEL_READ_INTO_PERCENT_DIMMER:
            case CHANNEL_READ_INTO_PERCENT_ROLLERSHUTTER:
            case CHANNEL_READ_INTO_ON_OFF_COLOR:
            case CHANNEL_READ_INTO_ON_OFF_DIMMER:
            case CHANNEL_READ_INTO_ON_OFF_SWITCH:
            case CHANNEL_READ_INTO_OPEN_CLOSED_CONTACT: {
                final ReadChannelConfiguration channelConfig = configuration.as(ReadChannelConfiguration.class);
                valueType = ValueType.fromConfigValue(channelConfig.valueType);

                validationErrors = ReadIntoChannelHandler.validateReadParameters(localFunctionCode, pollStart,
                        config.getLength(), channelConfig.address, valueType);

                if (validationErrors.isEmpty()) {
                    if (CHANNEL_READ_INTO_NUMBER.equals(channelTypeId)) {
                        readChannelHandlers.put(channelUID, new ReadIntoNumberChannelHandler(pollStart, channelConfig,
                                state -> this.tryUpdateState(channelUID, state)));
                    } else if (CHANNEL_READ_INTO_PERCENT_COLOR.equals(channelTypeId)
                            || CHANNEL_READ_INTO_PERCENT_DIMMER.equals(channelTypeId)
                            || CHANNEL_READ_INTO_PERCENT_ROLLERSHUTTER.equals(channelTypeId)) {
                        readChannelHandlers.put(channelUID, new ReadIntoPercentChannelHandler(pollStart, channelConfig,
                                state -> this.tryUpdateState(channelUID, state)));
                    } else if (CHANNEL_READ_INTO_ON_OFF_COLOR.equals(channelTypeId)
                            || CHANNEL_READ_INTO_ON_OFF_DIMMER.equals(channelTypeId)
                            || CHANNEL_READ_INTO_ON_OFF_SWITCH.equals(channelTypeId)) {
                        readChannelHandlers.put(channelUID, new ReadIntoOnOffChannelHandler(pollStart, channelConfig,
                                state -> this.tryUpdateState(channelUID, state)));
                    } else if (CHANNEL_READ_INTO_OPEN_CLOSED_CONTACT.equals(channelTypeId)) {
                        readChannelHandlers.put(channelUID, new ReadIntoOpenClosedChannelHandler(pollStart,
                                channelConfig, state -> this.tryUpdateState(channelUID, state)));
                    } else {
                        throw new IllegalStateException("Bug: missing switch statement for " + channelTypeId);
                    }
                }
                break;
            }
            case CHANNEL_READ_INTO_HEX_STRNG: {
                final ReadChannelConfiguration channelConfig = configuration.as(ReadChannelConfiguration.class);
                validationErrors = ReadIntoChannelHandler.validateReadParametersRaw(localFunctionCode, pollStart,
                        config.getLength(), channelConfig.address, channelConfig.length);

                if (validationErrors.isEmpty()) {
                    readChannelHandlers.put(channelUID, new ReadIntoHexStringChannelHandler(pollStart, channelConfig,
                            state -> this.tryUpdateState(channelUID, state)));
                }
                break;
            }
            case CHANNEL_WRITE_COIL_FROM_NUMBER:
            case CHANNEL_WRITE_COIL_FROM_ON_OFF_COLOR:
            case CHANNEL_WRITE_COIL_FROM_ON_OFF_DIMMER:
            case CHANNEL_WRITE_COIL_FROM_ON_OFF_SWITCH:
                valueType = ValueType.BIT;
                writingCoil = true;
                // intentional pass through (no break)
            case CHANNEL_WRITE_REGISTER_FROM_NUMBER:
            case CHANNEL_WRITE_REGISTER_FROM_PERCENT_COLOR:
            case CHANNEL_WRITE_REGISTER_FROM_PERCENT_DIMMER:
            case CHANNEL_WRITE_REGISTER_FROM_PERCENT_ROLLERSHUTTER:
            case CHANNEL_WRITE_REGISTER_FROM_ON_OFF_COLOR:
            case CHANNEL_WRITE_REGISTER_FROM_ON_OFF_DIMMER:
            case CHANNEL_WRITE_REGISTER_FROM_ON_OFF_SWITCH:
            case CHANNEL_WRITE_REGISTER_FROM_OPEN_CLOSED_CONTACT: {
                final WriteChannelConfiguration channelConfig = configuration.as(WriteChannelConfiguration.class);
                if (!writingCoil) {
                    // i.e., not pass-through from
                    // CHANNEL_WRITE_COIL_FROM_NUMBER/CHANNEL_WRITE_COIL_FROM_ON_OFF
                    valueType = ValueType.fromConfigValue(channelConfig.valueType);
                }
                Objects.requireNonNull(valueType); // Invariant
                validationErrors = WriteChannelHandler
                        .validateWriteParameters(localFunctionCode, pollStart, config.getLength(),
                                writingCoil ? ModbusBindingConstantsInternal.WRITE_TYPE_COIL
                                        : ModbusBindingConstantsInternal.WRITE_TYPE_HOLDING,
                                channelConfig.address, valueType);
                if (validationErrors.isEmpty()) {
                    RegisterCache cache = this;
                    Consumer<ModbusWriteRequestBlueprint> modbusWriter = this;
                    if (CHANNEL_WRITE_REGISTER_FROM_NUMBER.equals(channelTypeId)) {
                        writeChannelHandlers.put(channelUID,
                                new WriteRegisterFromNumberHandler(slaveId, channelConfig, cache, modbusWriter));
                    } else if (CHANNEL_WRITE_REGISTER_FROM_PERCENT_COLOR.equals(channelTypeId)
                            || CHANNEL_WRITE_REGISTER_FROM_PERCENT_DIMMER.equals(channelTypeId)
                            || CHANNEL_WRITE_REGISTER_FROM_PERCENT_ROLLERSHUTTER.equals(channelTypeId)) {
                        writeChannelHandlers.put(channelUID,
                                new WriteRegisterFromPercentHandler(slaveId, channelConfig, cache, modbusWriter));
                    } else if (CHANNEL_WRITE_REGISTER_FROM_ON_OFF_COLOR.equals(channelTypeId)
                            || CHANNEL_WRITE_REGISTER_FROM_ON_OFF_DIMMER.equals(channelTypeId)
                            || CHANNEL_WRITE_REGISTER_FROM_ON_OFF_SWITCH.equals(channelTypeId)) {
                        writeChannelHandlers.put(channelUID,
                                new WriteRegisterFromOnOffHandler(slaveId, channelConfig, cache, modbusWriter));
                    } else if (CHANNEL_WRITE_REGISTER_FROM_OPEN_CLOSED_CONTACT.equals(channelTypeId)) {
                        writeChannelHandlers.put(channelUID,
                                new WriteRegisterFromOpenClosedHandler(slaveId, channelConfig, cache, modbusWriter));
                    } else if (CHANNEL_WRITE_COIL_FROM_NUMBER.equals(channelTypeId)) {
                        writeChannelHandlers.put(channelUID,
                                new WriteCoilFromNumberHandler(slaveId, channelConfig, modbusWriter));
                    } else if (CHANNEL_WRITE_COIL_FROM_ON_OFF_COLOR.equals(channelTypeId)
                            || CHANNEL_WRITE_COIL_FROM_ON_OFF_DIMMER.equals(channelTypeId)
                            || CHANNEL_WRITE_COIL_FROM_ON_OFF_SWITCH.equals(channelTypeId)) {
                        writeChannelHandlers.put(channelUID,
                                new WriteCoilFromOnOffHandler(slaveId, channelConfig, modbusWriter));
                    } else {
                        throw new IllegalStateException("Bug: missing switch statement for " + channelTypeId);
                    }
                }
                break;
            }
            default:
                throw new IllegalStateException("Unexpected channel: " + channelTypeId);

        }
        return validationErrors;
    }

    /**
     * Register poll task
     *
     * @throws EndpointNotInitializedException in case the bridge initialization is not complete. This should only
     *             happen in transient conditions, for example, when bridge is initializing.
     */
    private synchronized void registerPollTask() throws EndpointNotInitializedException {
        logger.trace("registerPollTask()");
        ModbusCommunicationInterface localComms = this.comms;
        if (localComms == null) {
            throw new EndpointNotInitializedException();
        }

        ModbusReadFunctionCode localFunctionCode = functionCode;
        if (localFunctionCode == null) {
            return;
        }

        ModbusReadRequestBlueprint localRequest = new ModbusReadRequestBlueprint(slaveId, localFunctionCode,
                config.getStart(), config.getLength(), config.getMaxTries());
        this.request = localRequest;

        if (config.getRefresh() <= 0L) {
            logger.debug("Not registering polling with ModbusManager since refresh disabled");
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Not polling");
        } else {
            logger.debug("Registering polling with ModbusManager");
            pollTask = localComms.registerRegularPoll(localRequest, config.getRefresh(), 0, callbackDelegator,
                    callbackDelegator);
            assert pollTask != null;
            updateStatus(ThingStatus.UNKNOWN);
        }
    }

    public boolean initCommsAndValidateEndpointBridge() throws EndpointNotInitializedException {
        if (pollTask != null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR);
            logger.debug("pollTask should be unregistered before registering a new one!");
            return false;
        }
        if (getBridge() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, "Bridge is missing");
            return false;
        }

        ModbusEndpointThingHandler slaveEndpointThingHandler = getEndpointThingHandler();
        if (slaveEndpointThingHandler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, String.format("Bridge '%s' is offline",
                    Optional.ofNullable(getBridge()).map(b -> b.getLabel()).orElse("<null>")));
            logger.debug("No bridge handler available -- aborting init for {}", this);
            return false;
        }
        ModbusCommunicationInterface localComms = slaveEndpointThingHandler.getCommunicationInterface();
        if (localComms == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, String.format(
                    "Bridge '%s' not completely initialized", Optional.ofNullable(getBridge()).map(b -> b.getLabel())));
            logger.debug("Bridge not initialized fully (no communication interface) -- aborting init for {}", this);
            return false;
        }
        this.comms = localComms;
        this.slaveId = slaveEndpointThingHandler.getSlaveId();
        return true;
    }

    private boolean hasConfigurationError() {
        ThingStatusInfo statusInfo = getThing().getStatusInfo();
        return statusInfo.getStatus() == ThingStatus.OFFLINE
                && statusInfo.getStatusDetail() == ThingStatusDetail.CONFIGURATION_ERROR;
    }

    @Override
    public synchronized void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            logger.debug("bridgeStatusChanged to {}. Reseting handler {} to pick up possible configuration changes",
                    bridgeStatusInfo.getStatus(), this.getThing().getUID());
            this.dispose();
            this.initialize();
        }
    }

    @Override
    public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
        if (childHandler instanceof ModbusDataThingHandler modbusDataThingHandler) {
            this.childCallbacks.add(modbusDataThingHandler);
        }
    }

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
        ModbusReadRequestBlueprint localRequest = this.request;
        if (localRequest == null) {
            return;
        }
        ModbusRegisterArray possiblyMutatedCache = lastPolledDataCache.get();
        AtomicStampedValue<PollResult> lastPollResult = callbackDelegator.lastResult;
        if (lastPollResult != null && possiblyMutatedCache != null) {
            AsyncModbusReadResult lastSuccessfulPollResult = lastPollResult.getValue().result;
            if (lastSuccessfulPollResult != null) {
                ModbusRegisterArray lastRegisters = ((Optional<@Nullable ModbusRegisterArray>) lastSuccessfulPollResult
                        .getRegisters()).orElse(null);
                if (lastRegisters != null && !possiblyMutatedCache.equals(lastRegisters)) {
                    // Register has been mutated in between by a data thing that writes "individual bits"
                    // Invalidate cache for a fresh poll
                    callbackDelegator.resetCache();
                }
            }
        }

        long oldDataThreshold = System.currentTimeMillis() - cacheMillis;
        boolean cacheWasRecentEnoughForUpdate = cacheMillis > 0
                && this.callbackDelegator.updateChildrenWithOldData(oldDataThreshold);
        if (cacheWasRecentEnoughForUpdate) {
            logger.debug(
                    "Poller {} received refresh() and cache was recent enough (age at most {} ms). Reusing old response",
                    getThing().getUID(), cacheMillis);
        } else {
            // cache expired, poll new data
            logger.debug("Poller {} received refresh() but the cache is not applicable. Polling new data",
                    getThing().getUID());
            ModbusCommunicationInterface localComms = comms;
            if (localComms != null) {
                localComms.submitOneTimePoll(localRequest, callbackDelegator, callbackDelegator);
            }
        }
    }

    public AtomicReference<@Nullable ModbusRegisterArray> getLastPolledDataCache() {
        return lastPolledDataCache;
    }

    private void tryUpdateState(ChannelUID uid, State state) {
        try {
            updateState(uid, state);
        } catch (IllegalArgumentException e) {
            logger.warn("Error updating state '{}' (type {}) to channel {}: {} {}", state,
                    Optional.ofNullable(state).map(s -> s.getClass().getName()).orElse("null"), uid,
                    e.getClass().getName(), e.getMessage());
        }
    }

    @Override
    public Optional<ModbusRegisterArray> getCache(int start, int length) {
        return Optional.ofNullable(lastPolledDataCache.get())
                .map(registers -> registers.copyOfRange(start, start + length));
    }

    /**
     * Handler for write requests originating from channels (openHAB commands)
     */
    @Override
    public void accept(ModbusWriteRequestBlueprint writeRequest) {
        writeRequest.accept(new ModbusWriteRequestBlueprintVisitor() {
            @Override
            public void visit(ModbusWriteRegisterRequestBlueprint blueprint) {
                ModbusRegisterArray dataToBeWritten = blueprint.getRegisters();
                int registerIndexRelative = blueprint.getReference() - config.getStart();
                lastPolledDataCache.accumulateAndGet(dataToBeWritten,
                        (ModbusRegisterArray cache, ModbusRegisterArray writeData) -> {
                            if (cache == null) {
                                // No polled data yet, cannot update the cache
                                return null;
                            }
                            // writeData should always be non-null as dataToBeWritten is non-null
                            Objects.requireNonNull(writeData);
                            return cache.mutate(registerIndexRelative, writeData.getRegisters());
                        });
            }

            @Override
            public void visit(ModbusWriteCoilRequestBlueprint blueprint) {
                // TODO: update cache?
            }

        });
        comms.submitOneTimeWrite(writeRequest, this, this);
    }

    @Override
    public void handle(AsyncModbusFailure<ModbusWriteRequestBlueprint> failure) {
        Exception error = failure.getCause();
        final String errorMessage;
        if (error instanceof ModbusUnexpectedResponseFunctionCodeException
                || error instanceof ModbusUnexpectedResponseSizeException
                || error instanceof ModbusUnexpectedTransactionIdException) {
            errorMessage = String.format(
                    "Error writing to Modbus - response received but it did not match the request: %s (%s)",
                    error.getMessage(), error.getClass().getSimpleName());
        } else if (error instanceof ModbusTransportException) {
            // ModbusTransportException implementations should have concise error message with
            // getMessage()
            errorMessage = String.format("Error writing to Modbus - %s (%s)", error.getMessage(),
                    error.getClass().getSimpleName());
        } else {
            errorMessage = String.format("Error writing to Modbus (unexpected) - %s (%s)", error.getMessage(),
                    error.getClass().getSimpleName());
            logger.error(
                    "Thing {} '{}' had {} error on write: {} (message: {}). Stack trace follows since this is unexpected error.",
                    getThing().getUID(), getThing().getLabel(), error.getClass().getName(), error.toString(),
                    error.getMessage(), error);
        }
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, errorMessage);
        // TODO: update last erroring write timestamp
    }

    @Override
    public void handle(AsyncModbusWriteResult result) {
        logger.debug("Thing {} received response {}", thing.getUID(), result);
        resetCommunicationError();
        // TODO: update last OK write timestamp
    }

    private void resetCommunicationError() {
        ThingStatusInfo statusInfo = thing.getStatusInfo();
        if (ThingStatus.UNKNOWN.equals(statusInfo.getStatus()) || (ThingStatus.OFFLINE.equals(statusInfo.getStatus())
                && ThingStatusDetail.COMMUNICATION_ERROR.equals(statusInfo.getStatusDetail()))) {
            updateStatus(ThingStatus.ONLINE);
        }
    }
}
