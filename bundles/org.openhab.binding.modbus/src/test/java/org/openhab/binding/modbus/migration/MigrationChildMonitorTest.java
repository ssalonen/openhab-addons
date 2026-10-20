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
package org.openhab.binding.modbus.migration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.modbus.handler.ModbusPollerThingHandler;
import org.openhab.binding.modbus.internal.ModbusTransformation;
import org.openhab.binding.modbus.internal.handler.ModbusDataThingHandler;
import org.openhab.binding.modbus.internal.handler.ModbusTcpThingHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.ModbusCommunicationInterface;
import org.openhab.core.io.transport.modbus.ModbusReadCallback;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;

/** @author Sami Salonen - Cancellation after blocking on the actual child monitor */
class MigrationChildMonitorTest {
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void abandonedReadDoesNotDecodeAfterAcquiringChildMonitor(boolean interrupt) throws Exception {
        Bridge endpoint = BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), new ThingUID("modbus:tcp:monitor"))
                .build();
        var communication = mock(ModbusCommunicationInterface.class);
        var endpointHandler = mock(ModbusTcpThingHandler.class);
        when(endpointHandler.getCommunicationInterface()).thenReturn(communication);
        when(endpointHandler.getSlaveId()).thenReturn(1);
        endpoint.setHandler(endpointHandler);
        endpoint.setStatusInfo(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));
        Bridge pollerThing = BridgeBuilder
                .create(new ThingTypeUID("modbus", "poller"), new ThingUID("modbus:poller:monitor:holding"))
                .withBridge(endpoint.getUID())
                .withConfiguration(new Configuration(Map.of("start", 0, "length", 1, "type", "holding", "refresh", 0)))
                .build();
        var poller = new ModbusPollerThingHandler(pollerThing) {
            @Override
            protected @Nullable Bridge getBridge() {
                return endpoint;
            }
        };
        pollerThing.setHandler(poller);
        poller.setCallback(mock(ThingHandlerCallback.class));
        poller.initialize();
        pollerThing.setStatusInfo(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));
        var source = ThingBuilder
                .create(new ThingTypeUID("modbus", "data"), new ThingUID("modbus:data:monitor:holding:value"))
                .withBridge(pollerThing.getUID())
                .withConfiguration(new Configuration(
                        Map.of("readStart", "0", "readValueType", "uint16", "readTransform", List.of("default"))))
                .build();
        var child = new ModbusDataThingHandler(source) {
            @Override
            protected @Nullable Bridge getBridge() {
                return pollerThing;
            }
        };
        var publication = mock(ThingHandlerCallback.class);
        source.setHandler(child);
        child.setCallback(publication);
        var decoded = new AtomicInteger();
        try (var construction = mockConstruction(ModbusTransformation.class, (transform, context) -> {
            when(transform.isIdentityTransform()).thenReturn(true);
            when(transform.transformState(anyList(), any())).thenAnswer(call -> {
                decoded.incrementAndGet();
                return new DecimalType(42);
            });
        })) {
            child.initialize();
        }
        poller.childHandlerInitialized(child, source);
        clearInvocations(publication, communication);
        try {
            var selection = new MigrationReadSelection(
                    Map.of(source.getUID(), new MigrationReadSelection.Binding(source)));
            var read = poller.readForMigration(selection);
            var request = ArgumentCaptor.forClass(ModbusReadRequestBlueprint.class);
            var callback = ArgumentCaptor.forClass(ModbusReadCallback.class);
            verify(communication).submitOneTimePoll(request.capture(), callback.capture(), any());
            Thread delivery = new Thread(() -> callback.getValue()
                    .handle(new AsyncModbusReadResult(request.getValue(), new ModbusRegisterArray(42))));
            synchronized (child) {
                delivery.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (delivery.getState() != Thread.State.BLOCKED && delivery.isAlive()
                        && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertEquals(Thread.State.BLOCKED, delivery.getState(),
                        "Callback must be waiting on the real child monitor");
                Thread waiter = new Thread(() -> {
                    try {
                        if (interrupt) {
                            Thread.currentThread().interrupt();
                        }
                        read.completion().get(10, TimeUnit.MILLISECONDS);
                        fail("Pending read must be abandoned");
                    } catch (InterruptedException | TimeoutException e) {
                        read.completion().cancel(false);
                    } catch (ExecutionException e) {
                        throw new AssertionError(e);
                    }
                });
                waiter.start();
                waiter.join(5000);
                assertFalse(waiter.isAlive());
                assertTrue(read.completion().isCancelled());
            }
            delivery.join(5000);
            assertFalse(delivery.isAlive());
            assertEquals(0, decoded.get(), "Abandoned callback must not decode or transform after waiting");
            verifyNoInteractions(publication);
            assertTrue(child.getObservation().isEmpty());
            assertFalse(read.current().getAsBoolean());
        } finally {
            child.dispose();
            poller.dispose();
        }
    }
}
