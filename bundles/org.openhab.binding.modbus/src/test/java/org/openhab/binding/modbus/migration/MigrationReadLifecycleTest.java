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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.modbus.handler.ModbusPollerThingHandler;
import org.openhab.binding.modbus.internal.handler.ModbusTcpThingHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.modbus.AsyncModbusFailure;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.ModbusCommunicationInterface;
import org.openhab.core.io.transport.modbus.ModbusFailureCallback;
import org.openhab.core.io.transport.modbus.ModbusReadCallback;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;

/** @author Sami Salonen - Migration request abandonment regression */
class MigrationReadLifecycleTest {
    @ParameterizedTest
    @ValueSource(strings = { "cancel", "reload", "dispose", "failure", "decode-failure", "duplicate" })
    void requestLifecycleRejectsLateResponsesAndRecovers(String transition) throws Exception {
        Bridge endpoint = BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), new ThingUID("modbus:tcp:abandon"))
                .build();
        var communication = mock(ModbusCommunicationInterface.class);
        var endpointHandler = mock(ModbusTcpThingHandler.class);
        when(endpointHandler.getCommunicationInterface()).thenReturn(communication);
        when(endpointHandler.getSlaveId()).thenReturn(1);
        endpoint.setHandler(endpointHandler);
        endpoint.setStatusInfo(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));
        Bridge thing = BridgeBuilder
                .create(new ThingTypeUID("modbus", "poller"), new ThingUID("modbus:poller:abandon:holding"))
                .withBridge(endpoint.getUID())
                .withConfiguration(new Configuration(Map.of("start", 0, "length", 1, "type", "holding", "refresh", 0)))
                .build();
        var decoded = new AtomicInteger();
        var handler = new ModbusPollerThingHandler(thing) {
            @Override
            protected @Nullable Bridge getBridge() {
                return endpoint;
            }

            @Override
            protected Optional<Poller2Observation> observeMigrationResult(AsyncModbusReadResult result) {
                decoded.incrementAndGet();
                if (transition.equals("decode-failure")) {
                    throw new IllegalStateException("fixture decode failure");
                }
                return Optional.empty();
            }
        };
        thing.setHandler(handler);
        handler.setCallback(mock(ThingHandlerCallback.class));
        handler.initialize();
        try {
            var read = handler.readForMigration(
                    new MigrationReadSelection(Map.of(thing.getUID(), new MigrationReadSelection.Binding(thing))));
            var request = ArgumentCaptor.forClass(ModbusReadRequestBlueprint.class);
            var callback = ArgumentCaptor.forClass(ModbusReadCallback.class);
            verify(communication).submitOneTimePoll(request.capture(), callback.capture(), any());
            switch (transition) {
                case "cancel" -> read.completion().cancel(false);
                case "reload" -> handler.initialize();
                case "dispose" -> handler.dispose();
                case "failure" -> {
                    var failure = ArgumentCaptor.forClass(ModbusFailureCallback.class);
                    verify(communication).submitOneTimePoll(any(), any(), failure.capture());
                    failure.getValue().handle(new AsyncModbusFailure<>(request.getValue(),
                            new IllegalStateException("fixture transport failure")));
                }
                default -> {
                }
            }
            var result = new AsyncModbusReadResult(request.getValue(), new ModbusRegisterArray(42));
            callback.getValue().handle(result);
            callback.getValue().handle(result);
            boolean entered = transition.equals("decode-failure") || transition.equals("duplicate");
            assertEquals(entered ? 1 : 0, decoded.get());
            assertEquals(transition.equals("duplicate"), read.current().getAsBoolean());
            if (transition.equals("decode-failure") || transition.equals("failure")) {
                assertTrue(read.completion().isCompletedExceptionally());
            }
            if (!transition.equals("decode-failure")) {
                handler.initialize();
                clearInvocations(communication);
                var recovered = handler.readForMigration(
                        new MigrationReadSelection(Map.of(thing.getUID(), new MigrationReadSelection.Binding(thing))));
                verify(communication).submitOneTimePoll(request.capture(), callback.capture(), any());
                callback.getValue().handle(new AsyncModbusReadResult(request.getValue(), new ModbusRegisterArray(43)));
                assertTrue(recovered.completion().isDone());
                assertTrue(recovered.current().getAsBoolean());
                assertEquals(entered ? 2 : 1, decoded.get());
            }
        } finally {
            handler.dispose();
        }
    }
}
