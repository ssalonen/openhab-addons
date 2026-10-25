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

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.binding.modbus.handler.ModbusPollerThingHandler;
import org.openhab.binding.modbus.internal.handler.ModbusDataThingHandler;
import org.openhab.binding.modbus.internal.handler.ModbusTcpThingHandler;
import org.openhab.binding.modbus.internal.handler.Poller2Channel;
import org.openhab.binding.modbus.internal.handler.PollerReadFailurePolicy;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.ModbusCommunicationInterface;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.transform.TransformationHelper;
import org.openhab.core.transform.TransformationService;
import org.openhab.core.types.UnDefType;

/** @author Sami Salonen - Initial contribution */
class MigrationMapEvidenceTest {
    @Test
    void intentionalUndefHasSuccessfulRawEvidenceButServiceFailureDoesNot() throws Exception {
        try (var helper = mockStatic(TransformationHelper.class)) {
            var service = mock(TransformationService.class);
            helper.when(() -> TransformationHelper.getTransformationService("MAP")).thenReturn(service);
            when(service.transform("fixture.map", "1")).thenReturn("UNDEF");
            var decoder = new Poller2Channel("100", ValueType.INT16, PollerReadFailurePolicy.KEEP_LAST,
                    List.of("MAP(fixture.map)"), "Switch");
            assertEquals(UnDefType.UNDEF, decoder.acceptRegisters(new ModbusRegisterArray(1), 100));
            assertEquals(ModbusReadEvidence.Provenance.INTENTIONAL_UNDEF, decoder.getReadEvidence().provenance());
            assertTrue(decoder.getReadEvidence().informationBearing());
            assertEquals(new DecimalType(1), decoder.getReadEvidence().decoded());
            when(service.transform("fixture.map", "2")).thenReturn("ON");
            assertEquals(OnOffType.ON, decoder.acceptRegisters(new ModbusRegisterArray(2), 100));
            helper.when(() -> TransformationHelper.getTransformationService("MAP")).thenReturn(null);
            assertEquals(OnOffType.ON, decoder.acceptRegisters(new ModbusRegisterArray(3), 100));
            assertEquals(ModbusReadEvidence.Provenance.TRANSFORM_FAILURE, decoder.getReadEvidence().provenance());
            assertFalse(decoder.getReadEvidence().informationBearing());
        }
    }

    @Test
    void mapQuantityMatchesLegacyConversionDetachedEvidenceAndTargetState() throws Exception {
        try (var helper = mockStatic(TransformationHelper.class)) {
            var service = mock(TransformationService.class);
            helper.when(() -> TransformationHelper.getTransformationService("MAP")).thenReturn(service);
            when(service.transform("fixture.map", "12")).thenReturn("12 W");
            var transformation = new org.openhab.binding.modbus.internal.ModbusTransformation(
                    List.of("MAP(fixture.map)"));
            var raw = new DecimalType(12);
            var expected = new org.openhab.core.library.types.QuantityType<>("12 W");
            // This is the exact accepted-state list and conversion used by the legacy operational handler.
            assertEquals(expected, transformation
                    .transformState(new org.openhab.core.library.items.NumberItem("").getAcceptedDataTypes(), raw));
            var detached = ModbusReadEvidence.decode(raw, "Number", transformation);
            assertEquals(expected, detached.value());
            assertEquals(raw, detached.decoded());
            assertEquals(ModbusReadEvidence.Provenance.DECODED, detached.provenance());
            assertTrue(detached.informationBearing());
            var decoder = new Poller2Channel("100", ValueType.INT16, PollerReadFailurePolicy.KEEP_LAST,
                    List.of("MAP(fixture.map)"), "Number");
            assertEquals(expected, decoder.acceptRegisters(new ModbusRegisterArray(12), 100));
            assertEquals(detached, decoder.getReadEvidence());
            helper.when(() -> TransformationHelper.getTransformationService("MAP")).thenReturn(null);
            assertEquals(expected, decoder.acceptRegisters(new ModbusRegisterArray(13), 100));
            assertEquals(ModbusReadEvidence.Provenance.TRANSFORM_FAILURE, decoder.getReadEvidence().provenance());
            assertFalse(decoder.getReadEvidence().informationBearing());
        }
    }

    @Test
    void legacyOperationalQuantityAndAuthorizedDetachedReadAgreeWithTarget() throws Exception {
        try (var helper = mockStatic(TransformationHelper.class)) {
            var service = mock(TransformationService.class);
            helper.when(() -> TransformationHelper.getTransformationService("MAP")).thenReturn(service);
            when(service.transform("fixture.map", "12")).thenReturn("12 W");
            var endpoint = BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), new ThingUID("modbus:tcp:quantity"))
                    .build();
            var communication = mock(ModbusCommunicationInterface.class);
            var endpointHandler = mock(ModbusTcpThingHandler.class);
            when(endpointHandler.getCommunicationInterface()).thenReturn(communication);
            when(endpointHandler.getSlaveId()).thenReturn(1);
            endpoint.setHandler(endpointHandler);
            endpoint.setStatusInfo(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));
            var bridge = BridgeBuilder
                    .create(new ThingTypeUID("modbus", "poller"), new ThingUID("modbus:poller:quantity:holding"))
                    .withBridge(endpoint.getUID())
                    .withConfiguration(new Configuration(
                            Map.of("start", 100, "length", 1, "type", "holding", "refresh", 0, "cacheMillis", 0)))
                    .build();
            var poller = new ModbusPollerThingHandler(bridge) {
                @Override
                protected @Nullable Bridge getBridge() {
                    return endpoint;
                }
            };
            bridge.setHandler(poller);
            poller.setCallback(mock(ThingHandlerCallback.class));
            poller.initialize();
            bridge.setStatusInfo(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));
            var uid = new ChannelUID("modbus:data:quantity:holding:value:number");
            var thing = ThingBuilder.create(new ThingTypeUID("modbus", "data"), uid.getThingUID())
                    .withBridge(bridge.getUID())
                    .withConfiguration(new Configuration(Map.of("readStart", "100", "readValueType", "int16",
                            "readTransform", List.of("MAP(fixture.map)"))))
                    .withChannel(ChannelBuilder.create(uid).withAcceptedItemType("Number").build()).build();
            var child = new ModbusDataThingHandler(thing) {
                @Override
                protected @Nullable Bridge getBridge() {
                    return bridge;
                }
            };
            thing.setHandler(child);
            var callback = mock(ThingHandlerCallback.class);
            when(callback.isChannelLinked(uid)).thenReturn(true);
            child.setCallback(callback);
            child.initialize();
            try {
                var request = java.util.Objects.requireNonNull(poller.getRequest());
                var response = new AsyncModbusReadResult(request, new ModbusRegisterArray(12));
                var expected = new QuantityType<>("12 W");
                child.onReadResult(response, false);
                verify(callback).stateUpdated(uid, expected);
                clearInvocations(callback);
                var selection = new MigrationReadSelection(
                        Map.of(thing.getUID(), new MigrationReadSelection.Binding(thing)));
                var detached = child.captureReadAuthorization(poller).observe(response, () -> true, selection)
                        .orElseThrow().evidence().get(uid);
                assertEquals(expected, detached.value());
                assertEquals(new DecimalType(12), detached.decoded());
                assertEquals(ModbusReadEvidence.Provenance.DECODED, detached.provenance());
                verifyNoInteractions(callback);
                var target = new Poller2Channel("100", ValueType.INT16, PollerReadFailurePolicy.KEEP_LAST,
                        List.of("MAP(fixture.map)"), "Number");
                assertEquals(expected, target.acceptRegisters(new ModbusRegisterArray(12), 100));
                assertEquals(detached, target.getReadEvidence());
            } finally {
                child.dispose();
                poller.dispose();
            }
        }
    }

    @Test
    void transportFailureRetainsOperationalStateAndInvalidatesEvidence() {
        var decoder = new Poller2Channel("100", ValueType.INT16, PollerReadFailurePolicy.KEEP_LAST);
        assertEquals(new DecimalType(19), decoder.acceptRegisters(new ModbusRegisterArray(19), 100));
        assertTrue(decoder.getReadEvidence().informationBearing());
        assertEquals(new DecimalType(19), decoder.acceptReadFailure());
        assertFalse(decoder.getReadEvidence().informationBearing());
    }
}
