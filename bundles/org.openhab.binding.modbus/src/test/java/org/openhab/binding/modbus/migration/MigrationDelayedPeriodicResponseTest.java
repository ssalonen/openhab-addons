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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.modbus.handler.ModbusPoller2ThingHandler;
import org.openhab.binding.modbus.handler.ModbusPollerThingHandler;
import org.openhab.binding.modbus.internal.ModbusChannelTypeProvider;
import org.openhab.binding.modbus.internal.handler.ModbusDataThingHandler;
import org.openhab.binding.modbus.internal.handler.ModbusTcpThingHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.ModbusCommunicationInterface;
import org.openhab.core.io.transport.modbus.ModbusReadCallback;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.io.transport.modbus.PollTask;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.link.ManagedItemChannelLinkProvider;

/** @author Sami Salonen - Migration boundary delayed regular-poll regression */
class MigrationDelayedPeriodicResponseTest {
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void rereviewCannotAuthorizePeriodicResponseAlreadyInFlightBeforeChildReload(boolean cutover) throws Exception {
        var provider = mock(ManagedThingProvider.class);
        var things = ManagedModbusMigrationStagingTest.managedRegistry(provider);
        Map<ThingUID, Thing> state = new HashMap<>();
        when(provider.get(any(ThingUID.class))).thenAnswer(call -> state.get(call.getArgument(0)));
        doAnswer(call -> {
            Thing added = call.getArgument(0);
            state.put(added.getUID(), added);
            return null;
        }).when(provider).add(any());
        when(provider.remove(any(ThingUID.class))).thenAnswer(call -> state.remove(call.getArgument(0)));
        when(things.getAll()).thenAnswer(call -> List.copyOf(state.values()));

        var links = mock(ItemChannelLinkRegistry.class);
        var linkProvider = mock(ManagedItemChannelLinkProvider.class);
        Map<String, ItemChannelLink> routing = new HashMap<>();
        when(links.getAll()).thenAnswer(call -> List.copyOf(routing.values()));
        when(links.get(anyString())).thenAnswer(call -> routing.get(call.getArgument(0)));
        when(links.getProvider(any(ItemChannelLink.class))).thenReturn(linkProvider);
        when(links.remove(anyString())).thenAnswer(call -> routing.remove(call.getArgument(0)));
        doAnswer(call -> {
            ItemChannelLink added = call.getArgument(0);
            routing.put(added.getUID(), added);
            return null;
        }).when(links).add(any());
        var storage = mock(StorageService.class);
        when(storage.<String> getStorage("modbus-migration-journal"))
                .thenReturn(ManagedModbusMigrationStagingTest.storage(new HashMap<>()));

        Bridge endpoint = BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), new ThingUID("modbus:tcp:delayed"))
                .build();
        online(endpoint);
        var communication = mock(ModbusCommunicationInterface.class);
        when(communication.registerRegularPoll(any(), anyLong(), anyLong(), any(), any()))
                .thenReturn(mock(PollTask.class));
        var endpointHandler = mock(ModbusTcpThingHandler.class);
        when(endpointHandler.getCommunicationInterface()).thenReturn(communication);
        when(endpointHandler.getSlaveId()).thenReturn(1);
        endpoint.setHandler(endpointHandler);
        state.put(endpoint.getUID(), endpoint);
        Bridge pollerThing = BridgeBuilder
                .create(new ThingTypeUID("modbus", "poller"), new ThingUID("modbus:poller:delayed:holding"))
                .withBridge(endpoint.getUID())
                .withConfiguration(new Configuration(
                        Map.of("start", 100, "length", 2, "type", "holding", "refresh", 1000, "cacheMillis", 0)))
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
        online(pollerThing);
        state.put(pollerThing.getUID(), pollerThing);
        var callbacks = ArgumentCaptor.forClass(ModbusReadCallback.class);
        var requests = ArgumentCaptor.forClass(ModbusReadRequestBlueprint.class);
        verify(communication).registerRegularPoll(requests.capture(), anyLong(), anyLong(), callbacks.capture(), any());
        ModbusReadCallback oldPeriodicCallback = callbacks.getValue();
        // Model a transport execution whose response is withheld until after child reload and re-review.
        var oldResponse = new AsyncModbusReadResult(requests.getValue(), new ModbusRegisterArray(42, 42));
        var sourceUid = new ThingUID("modbus:data:delayed:holding:value");
        var sourceChannel = new ChannelUID(sourceUid, "number");
        Thing source = ThingBuilder.create(new ThingTypeUID("modbus", "data"), sourceUid)
                .withBridge(pollerThing.getUID())
                .withConfiguration(new Configuration(
                        Map.of("readStart", "100", "readValueType", "uint16", "readTransform", List.of("default"))))
                .withChannel(ChannelBuilder.create(sourceChannel).withAcceptedItemType("Number").build()).build();
        var child = new ModbusDataThingHandler(source) {
            @Override
            protected @Nullable Bridge getBridge() {
                return pollerThing;
            }
        };
        source.setHandler(child);
        child.setCallback(mock(ThingHandlerCallback.class));
        child.initialize();
        poller.childHandlerInitialized(child, source);
        state.put(sourceUid, source);
        var originalLink = new ItemChannelLink("DelayedValue", sourceChannel);
        routing.put(originalLink.getUID(), originalLink);
        var service = new ModbusMigrationService(things, links, provider, storage,
                new LiveModbusMigrationValidation(things), linkProvider);
        ModbusPoller2ThingHandler candidate = null;
        try {
            var before = service.preview().plan();
            assertEquals(1, before.groups().size());
            service.apply(before.identity());
            oldPeriodicCallback.handle(new AsyncModbusReadResult(requests.getValue(), new ModbusRegisterArray(7, 8)));
            assertEquals("7", child.getObservation().orElseThrow().evidence().get(sourceChannel).value().toString());
            Thing changed = ThingBuilder.create(source)
                    .withConfiguration(new Configuration(
                            Map.of("readStart", "101", "readValueType", "uint16", "readTransform", List.of("default"))))
                    .build();
            child.thingUpdated(changed);
            child.getThing().setHandler(child);
            state.put(sourceUid, child.getThing());
            assertTrue(child.getObservation().isEmpty());
            assertThrows(IllegalArgumentException.class,
                    () -> service.validate(before.identity(), pollerThing.getUID().toString()));
            assertThrows(IllegalArgumentException.class,
                    () -> service.cutover(before.identity(), pollerThing.getUID().toString()));
            verify(links, never()).remove(anyString());
            verify(links, never()).add(any());
            assertEquals(Map.of(originalLink.getUID(), originalLink), routing);
            service.rollback(before.identity());
            var reviewed = service.preview().plan();
            assertNotEquals(before.identity(), reviewed.identity());
            assertEquals(1, reviewed.groups().size());
            service.apply(reviewed.identity());
            var group = reviewed.groups().getFirst();
            Bridge target = (Bridge) Objects.requireNonNull(state.get(new ThingUID(group.targetPollerUid())));
            candidate = new ModbusPoller2ThingHandler(target, new ModbusChannelTypeProvider()) {
                @Override
                protected @Nullable Bridge getBridge() {
                    return endpoint;
                }
            };
            target.setHandler(candidate);
            candidate.setCallback(mock(ThingHandlerCallback.class));
            clearInvocations(communication);
            candidate.initialize();
            // BaseThingHandler.updateThing replaces the instance when generated channel types are installed.
            // Mirror Core's authoritative registry callback rather than retaining the pre-initialization Thing.
            candidate.getThing().setHandler(candidate);
            state.put(target.getUID(), candidate.getThing());
            online(candidate.getThing());
            verify(communication).registerRegularPoll(requests.capture(), anyLong(), anyLong(), callbacks.capture(),
                    any());
            callbacks.getValue()
                    .handle(new AsyncModbusReadResult(requests.getValue(), new ModbusRegisterArray(42, 42)));
            assertTrue(candidate.getObservation().isPresent());
            assertEquals(MigrationValidation.Outcome.UNAVAILABLE,
                    service.validate(reviewed.identity(), group.sourcePollerUid()).outcome());
            assertThrows(IllegalStateException.class,
                    () -> service.cutover(reviewed.identity(), group.sourcePollerUid()));
            verify(links, never()).remove(anyString());
            verify(links, never()).add(any());
            assertEquals(Map.of(originalLink.getUID(), originalLink), routing);

            oldPeriodicCallback.handle(oldResponse);
            // Both observation provenance and operation refusal are checked: no fabricated collector records.
            var legacy = child.getObservation().orElseThrow();
            assertEquals(Poller2ObservationCollector.signature(child.getThing()), legacy.configurationSignature());
            assertEquals("42", legacy.evidence().get(sourceChannel).value().toString());
            var observed = service.validate(reviewed.identity(), group.sourcePollerUid());
            System.out.println("DELAYED_PERIODIC_REVIEW cutover=" + cutover + " old=" + before.identity() + " new="
                    + reviewed.identity() + " legacySignature=" + legacy.configurationSignature() + " observed="
                    + observed);
            if (cutover) {
                assertAll(
                        () -> assertThrows(IllegalStateException.class,
                                () -> service.cutover(reviewed.identity(), group.sourcePollerUid())),
                        () -> verify(links, never()).remove(anyString()), () -> verify(links, never()).add(any()),
                        () -> assertEquals(Map.of(originalLink.getUID(), originalLink), routing));
            } else {
                assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observed.outcome(),
                        "Re-review cannot turn a pre-reload regular-poll execution into fresh authorized evidence");
            }
        } finally {
            if (candidate != null) {
                candidate.dispose();
            }
            child.dispose();
            poller.dispose();
        }
    }

    private static void online(Thing thing) {
        thing.setStatusInfo(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));
    }
}
