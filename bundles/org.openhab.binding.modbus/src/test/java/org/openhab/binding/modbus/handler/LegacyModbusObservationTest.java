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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.binding.modbus.internal.handler.ModbusDataThingHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.modbus.AsyncModbusFailure;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;

/** @author Sami Salonen - Initial contribution */
class LegacyModbusObservationTest {
    private final ModbusReadRequestBlueprint request;
    private final ModbusPollerThingHandler poller;
    private final org.openhab.core.io.transport.modbus.ModbusCommunicationInterface communication = mock(
            org.openhab.core.io.transport.modbus.ModbusCommunicationInterface.class);
    private final Bridge bridge;
    private final Bridge endpoint;
    private final java.util.concurrent.atomic.AtomicReference<Bridge> childBridge = new java.util.concurrent.atomic.AtomicReference<>();
    private final ChannelUID uid = new ChannelUID("modbus:data:validation:holding:value:number");
    private final ModbusDataThingHandler handler;

    LegacyModbusObservationTest() throws Exception {
        bridge = BridgeBuilder
                .create(new ThingTypeUID("modbus", "poller"), new ThingUID("modbus:poller:validation:holding"))
                .withConfiguration(new Configuration(
                        Map.of("start", 100, "length", 1, "type", "holding", "refresh", 0, "cacheMillis", 0)))
                .build();
        endpoint = BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), new ThingUID("modbus:tcp:validation"))
                .build();
        endpoint.setStatusInfo(
                new ThingStatusInfo(ThingStatus.ONLINE, org.openhab.core.thing.ThingStatusDetail.NONE, null));
        var endpointHandler = mock(org.openhab.binding.modbus.internal.handler.ModbusTcpThingHandler.class);
        endpoint.setHandler(endpointHandler);
        when(endpointHandler.getCommunicationInterface()).thenReturn(communication);
        when(endpointHandler.getSlaveId()).thenReturn(1);
        poller = new ModbusPollerThingHandler(bridge) {
            @Override
            protected @Nullable Bridge getBridge() {
                return endpoint;
            }
        };
        poller.setCallback(mock(ThingHandlerCallback.class));
        poller.initialize();
        bridge.setStatusInfo(
                new ThingStatusInfo(ThingStatus.ONLINE, org.openhab.core.thing.ThingStatusDetail.NONE, null));
        bridge.setHandler(poller);
        request = java.util.Objects.requireNonNull(poller.getRequest());
        Thing thing = ThingBuilder.create(new ThingTypeUID("modbus", "data"), uid.getThingUID())
                .withBridge(bridge.getUID())
                .withConfiguration(new Configuration(Map.of("readStart", "100", "readValueType", "uint16",
                        "readTransform", java.util.List.of("default"))))
                .withChannel(ChannelBuilder.create(uid).withAcceptedItemType("Number").build()).build();
        childBridge.set(bridge);
        handler = new ModbusDataThingHandler(thing) {
            @Override
            protected @Nullable Bridge getBridge() {
                return childBridge.get();
            }
        };
        handler.setCallback(mock(ThingHandlerCallback.class));
        handler.initialize();
    }

    private void poll(int value, boolean cached) {
        AsyncModbusReadResult result = mock(AsyncModbusReadResult.class);
        when(result.getRequest()).thenReturn(request);
        when(result.getRegisters()).thenReturn(Optional.of(new ModbusRegisterArray(value)));
        when(result.getBits()).thenReturn(Optional.empty());
        handler.onReadResult(result, cached);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false,false", "true,false", "false,true", "true,true" })
    void blockedParentDispatchCannotCrossChildReinitialization(boolean failure, boolean reparent) throws Exception {
        poller.childHandlerInitialized(handler, handler.getThing());
        poller.refresh();
        var reads = org.mockito.ArgumentCaptor.forClass(org.openhab.core.io.transport.modbus.ModbusReadCallback.class);
        var failures = org.mockito.ArgumentCaptor
                .forClass(org.openhab.core.io.transport.modbus.ModbusFailureCallback.class);
        verify(communication).submitOneTimePoll(any(), reads.capture(), failures.capture());
        var result = mock(AsyncModbusReadResult.class);
        when(result.getRequest()).thenReturn(request);
        when(result.getRegisters()).thenReturn(Optional.of(new ModbusRegisterArray(12)));
        when(result.getBits()).thenReturn(Optional.empty());
        var error = mock(AsyncModbusFailure.class);
        when(error.getRequest()).thenReturn(request);
        when(error.getCause()).thenReturn(new java.io.IOException("obsolete failure"));
        var started = new java.util.concurrent.CountDownLatch(1);
        var thread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> dispatched;
            synchronized (handler) {
                dispatched = executor.submit(() -> {
                    thread.set(Thread.currentThread());
                    started.countDown();
                    if (failure) {
                        failures.getValue().handle(error);
                    } else {
                        reads.getValue().handle(result);
                    }
                });
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (thread.get().getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertEquals(Thread.State.BLOCKED, thread.get().getState());
                if (reparent) {
                    handler.dispose();
                    var replacement = BridgeBuilder.create(new ThingTypeUID("modbus", "poller"),
                            new ThingUID("modbus:poller:validation:replacement")).build();
                    replacement.setStatusInfo(new ThingStatusInfo(ThingStatus.ONLINE,
                            org.openhab.core.thing.ThingStatusDetail.NONE, null));
                    var parent = mock(ModbusPollerThingHandler.class);
                    when(parent.getRequest()).thenReturn(request);
                    when(parent.getCommunicationInterface()).thenReturn(communication);
                    when(parent.hasCurrentReadConfiguration()).thenReturn(true);
                    replacement.setHandler(parent);
                    childBridge.set(replacement);
                    handler.thingUpdated(
                            ThingBuilder.create(handler.getThing()).withBridge(replacement.getUID()).build());
                }
                handler.initialize();
                assertTrue(handler.getObservation().isEmpty());
                if (failure) {
                    poll(43, false);
                }
            }
            dispatched.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (failure) {
                assertEquals(new DecimalType(43), handler.getObservation().orElseThrow().states().get(uid));
            } else {
                assertTrue(handler.getObservation().isEmpty(), "old dispatch must not become new lifecycle evidence");
                poll(43, false);
                assertEquals(new DecimalType(43), handler.getObservation().orElseThrow().states().get(uid));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            handler.dispose();
            poller.dispose();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void replacingParentRequestInvalidatesEvidenceUntilChildUsesNewTransport(boolean failure) {
        try {
            poll(42, false);
            var result = mock(AsyncModbusReadResult.class);
            when(result.getRequest()).thenReturn(request);
            when(result.getRegisters()).thenReturn(Optional.of(new ModbusRegisterArray(12)));
            when(result.getBits()).thenReturn(Optional.empty());
            var error = mock(AsyncModbusFailure.class);
            when(error.getRequest()).thenReturn(request);
            when(error.getCause()).thenReturn(new java.io.IOException("obsolete request"));
            Runnable old = failure ? handler.prepareReadError(poller, error)
                    : handler.prepareReadResult(poller, result, false);
            poller.initialize();
            bridge.setStatusInfo(
                    new ThingStatusInfo(ThingStatus.ONLINE, org.openhab.core.thing.ThingStatusDetail.NONE, null));
            assertTrue(handler.getObservation().isEmpty());
            old.run();
            assertTrue(handler.getObservation().isEmpty());
            handler.initialize();
            when(result.getRequest()).thenReturn(java.util.Objects.requireNonNull(poller.getRequest()));
            when(result.getRegisters()).thenReturn(Optional.of(new ModbusRegisterArray(43)));
            handler.onReadResult(result, false);
            assertEquals(new DecimalType(43), handler.getObservation().orElseThrow().states().get(uid));
        } finally {
            handler.dispose();
            poller.dispose();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void endpointReplacementCannotAuthorizeExistingTransportEvidence(boolean replaceCommunication) {
        try {
            poll(42, false);
            assertTrue(handler.getObservation().isPresent());
            if (replaceCommunication) {
                var endpointHandler = (org.openhab.binding.modbus.internal.handler.ModbusTcpThingHandler) endpoint
                        .getHandler();
                when(endpointHandler.getCommunicationInterface())
                        .thenReturn(mock(org.openhab.core.io.transport.modbus.ModbusCommunicationInterface.class));
            } else {
                endpoint.getConfiguration().put("host", "replacement-endpoint");
            }
            assertTrue(handler.getObservation().isEmpty());
            poll(43, false);
            assertTrue(handler.getObservation().isEmpty());
            handler.initialize();
            poll(44, false);
            assertTrue(handler.getObservation().isEmpty(), "child reinitialization cannot bless old parent transport");
        } finally {
            handler.dispose();
            poller.dispose();
        }
    }

    @Test
    void cachedReplayCannotCreateOrRenewLegacyEvidence() {
        try {
            poll(42, true);
            assertTrue(handler.getObservation().isEmpty());
            poll(42, false);
            var fresh = handler.getObservation().orElseThrow();
            assertNotNull(fresh.evidence().get(uid), fresh.toString());
            assertEquals(new DecimalType(42), fresh.evidence().get(uid).decoded());
            assertTrue(fresh.evidence().get(uid).informationBearing());
            poll(43, true);
            assertEquals(fresh, handler.getObservation().orElseThrow());
        } finally {
            handler.dispose();
        }
    }

    @Test
    void directReinitializeInvalidatesLegacyEvidence() {
        try {
            poll(42, false);
            assertTrue(handler.getObservation().isPresent());
            handler.initialize();
            assertTrue(handler.getObservation().isEmpty());
            poll(43, false);
            assertEquals(new DecimalType(43), handler.getObservation().orElseThrow().states().get(uid));
        } finally {
            handler.dispose();
        }
    }

    @Test
    void readFailureAndDisposeInvalidateEvidenceUntilFreshRecovery() {
        try {
            poll(42, false);
            AsyncModbusFailure<ModbusReadRequestBlueprint> failure = mock(AsyncModbusFailure.class);
            when(failure.getRequest()).thenReturn(request);
            when(failure.getCause()).thenReturn(new java.io.IOException("fixture read failure"));
            handler.handleReadError(failure);
            assertTrue(handler.getObservation().isEmpty());
            poll(42, true);
            assertTrue(handler.getObservation().isEmpty());
            poll(43, false);
            assertEquals(new DecimalType(43), handler.getObservation().orElseThrow().states().get(uid));
        } finally {
            handler.dispose();
        }
        assertTrue(handler.getObservation().isEmpty());
    }
}
