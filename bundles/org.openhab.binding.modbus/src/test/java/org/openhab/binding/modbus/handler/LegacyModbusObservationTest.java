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
import org.openhab.core.io.transport.modbus.ModbusReadFunctionCode;
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
    private final ModbusReadRequestBlueprint request = mock(ModbusReadRequestBlueprint.class);
    private final ChannelUID uid = new ChannelUID("modbus:data:validation:holding:value:number");
    private final ModbusDataThingHandler handler;

    LegacyModbusObservationTest() throws Exception {
        Bridge bridge = BridgeBuilder
                .create(new ThingTypeUID("modbus", "poller"), new ThingUID("modbus:poller:validation:holding")).build();
        bridge.setStatusInfo(
                new ThingStatusInfo(ThingStatus.ONLINE, org.openhab.core.thing.ThingStatusDetail.NONE, null));
        ModbusPollerThingHandler poller = mock(ModbusPollerThingHandler.class);
        bridge.setHandler(poller);
        when(poller.getRequest()).thenReturn(request);
        when(request.getUnitID()).thenReturn(1);
        when(request.getReference()).thenReturn(100);
        when(request.getFunctionCode()).thenReturn(ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS);
        Thing thing = ThingBuilder.create(new ThingTypeUID("modbus", "data"), uid.getThingUID())
                .withBridge(bridge.getUID())
                .withConfiguration(new Configuration(Map.of("readStart", "100", "readValueType", "uint16",
                        "readTransform", java.util.List.of("default"))))
                .withChannel(ChannelBuilder.create(uid).withAcceptedItemType("Number").build()).build();
        handler = new ModbusDataThingHandler(thing) {
            @Override
            protected @Nullable Bridge getBridge() {
                return bridge;
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
