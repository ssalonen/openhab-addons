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
package org.openhab.binding.modbus.internal.handler;

import java.util.concurrent.Future;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.handler.EndpointNotInitializedException;
import org.openhab.binding.modbus.handler.ModbusEndpointThingHandler;
import org.openhab.binding.modbus.internal.ModbusConfigurationException;
import org.openhab.core.io.transport.modbus.AsyncModbusFailure;
import org.openhab.core.io.transport.modbus.ModbusCommunicationInterface;
import org.openhab.core.io.transport.modbus.ModbusCommunicationInterfaceDelegate;
import org.openhab.core.io.transport.modbus.ModbusFailureCallback;
import org.openhab.core.io.transport.modbus.ModbusManager;
import org.openhab.core.io.transport.modbus.ModbusReadCallback;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteCallback;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.core.io.transport.modbus.PollTask;
import org.openhab.core.io.transport.modbus.endpoint.EndpointPoolConfiguration;
import org.openhab.core.io.transport.modbus.endpoint.ModbusSlaveEndpoint;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for Modbus Slave endpoint thing handlers
 *
 * @author Sami Salonen - Initial contribution
 *
 * @param <E> endpoint class
 * @param <C> config class
 */
@NonNullByDefault
public abstract class AbstractModbusEndpointThingHandler<E extends ModbusSlaveEndpoint, C> extends BaseBridgeHandler
        implements ModbusEndpointThingHandler {

    /**
     * Listen for communication events and update thing status accordingly
     *
     * @author Sami Salonen - Initial contribution
     *
     */
    private class CommunicationInterfaceWrapper extends ModbusCommunicationInterfaceDelegate {

        public CommunicationInterfaceWrapper(ModbusCommunicationInterface wrapped) {
            super(wrapped);
        }

        @Override
        public PollTask registerRegularPoll(ModbusReadRequestBlueprint request, long pollPeriodMillis,
                long initialDelayMillis, ModbusReadCallback resultCallback,
                ModbusFailureCallback<ModbusReadRequestBlueprint> failureCallback) {
            return super.registerRegularPoll(request, pollPeriodMillis, initialDelayMillis,
                    wrapReadSuccessCallback(resultCallback), wrapReadFailureCallback(failureCallback));
        }

        @Override
        public Future<?> submitOneTimePoll(ModbusReadRequestBlueprint request, ModbusReadCallback resultCallback,
                ModbusFailureCallback<ModbusReadRequestBlueprint> failureCallback) {
            return super.submitOneTimePoll(request, wrapReadSuccessCallback(resultCallback),
                    wrapReadFailureCallback(failureCallback));
        }

        @Override
        public Future<?> submitOneTimeWrite(ModbusWriteRequestBlueprint request, ModbusWriteCallback resultCallback,
                ModbusFailureCallback<ModbusWriteRequestBlueprint> failureCallback) {
            return super.submitOneTimeWrite(request, wrapWriteSuccessCallback(resultCallback),
                    wrapReadFailureCallback(failureCallback));
        }

        private <R> ModbusFailureCallback<R> wrapReadFailureCallback(ModbusFailureCallback<R> failureCallback) {
            ModbusFailureCallback<R> wrappedFailureCallback = result -> {
                AbstractModbusEndpointThingHandler.this.handleCommunicationError(result);
                failureCallback.handle(result);
            };
            return wrappedFailureCallback;
        }

        private ModbusReadCallback wrapReadSuccessCallback(ModbusReadCallback resultCallback) {
            ModbusReadCallback wrappedResultCallback = result -> {
                AbstractModbusEndpointThingHandler.this.resetCommunicationErrorOrUnknown();
                resultCallback.handle(result);
            };
            return wrappedResultCallback;
        }

        private ModbusWriteCallback wrapWriteSuccessCallback(ModbusWriteCallback resultCallback) {
            ModbusWriteCallback wrappedResultCallback = result -> {
                AbstractModbusEndpointThingHandler.this.resetCommunicationErrorOrUnknown();
                resultCallback.handle(result);
            };
            return wrappedResultCallback;
        }
    }

    protected volatile @Nullable C config;
    protected volatile @Nullable E endpoint;
    protected ModbusManager modbusManager;
    protected volatile @NonNullByDefault({}) EndpointPoolConfiguration poolConfiguration;
    private final Logger logger = LoggerFactory.getLogger(AbstractModbusEndpointThingHandler.class);
    private @NonNullByDefault({}) ModbusCommunicationInterface comms;

    public AbstractModbusEndpointThingHandler(Bridge bridge, ModbusManager modbusManager) {
        super(bridge);
        this.modbusManager = modbusManager;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    @Override
    public void initialize() {
        synchronized (this) {
            logger.trace("Initializing {} from status {}", this.getThing().getUID(), this.getThing().getStatus());
            if (this.getThing().getStatus().equals(ThingStatus.ONLINE)) {
                // If the bridge was online then first change it to offline.
                // this ensures that children will be notified about the change
                updateStatus(ThingStatus.OFFLINE);
            }
            try {
                configure();
                @Nullable
                E endpoint = this.endpoint;
                if (endpoint == null) {
                    throw new IllegalStateException("endpoint null after configuration!");
                }
                try {
                    comms = new CommunicationInterfaceWrapper(
                            modbusManager.newModbusCommunicationInterface(endpoint, poolConfiguration));
                    updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE,
                            "Status will be updated after first read/write operation");
                } catch (IllegalArgumentException e) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            formatConflictingParameterError());
                }
            } catch (ModbusConfigurationException e) {
                logger.debug("Exception during initialization", e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, String.format(
                        "Exception during initialization: %s (%s)", e.getMessage(), e.getClass().getSimpleName()));
            } finally {
                logger.trace("initialize() of thing {} '{}' finished", thing.getUID(), thing.getLabel());
            }
        }
    }

    @Override
    public void dispose() {
        try {
            ModbusCommunicationInterface localComms = comms;
            if (localComms != null) {
                localComms.close();
            }
        } catch (Exception e) {
            logger.warn("Error closing modbus communication interface", e);
        } finally {
            comms = null;
        }
    }

    @Override
    public @Nullable ModbusCommunicationInterface getCommunicationInterface() {
        return comms;
    }

    @Nullable
    public E getEndpoint() {
        return endpoint;
    }

    private void resetCommunicationErrorOrUnknown() {
        ThingStatusInfo statusInfo = thing.getStatusInfo();
        if (ThingStatus.UNKNOWN.equals(statusInfo.getStatus()) || (ThingStatus.OFFLINE.equals(statusInfo.getStatus())
                && ThingStatusDetail.COMMUNICATION_ERROR.equals(statusInfo.getStatusDetail()))) {
            updateStatus(ThingStatus.ONLINE);
        }
    }

    private <R> void handleCommunicationError(AsyncModbusFailure<R> result) {
        ThingStatusInfo statusInfo = thing.getStatusInfo();
        if (!ThingStatus.OFFLINE.equals(statusInfo.getStatus())) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, result.toString());
        }
    }

    @Override
    public abstract int getSlaveId() throws EndpointNotInitializedException;

    /**
     * Must be overriden by subclasses to initialize config, endpoint, and poolConfiguration
     */
    protected abstract void configure() throws ModbusConfigurationException;

    /**
     * Format error message in case some other endpoint has been configured with different
     * {@link EndpointPoolConfiguration}
     */
    protected abstract String formatConflictingParameterError();
}
