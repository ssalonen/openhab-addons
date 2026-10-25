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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.modbus.handler.ModbusPoller2ThingHandler;
import org.openhab.binding.modbus.handler.ModbusPollerThingHandler;
import org.openhab.binding.modbus.internal.ModbusChannelTypeProvider;
import org.openhab.binding.modbus.internal.ModbusTransformation;
import org.openhab.binding.modbus.internal.handler.ModbusDataThingHandler;
import org.openhab.binding.modbus.internal.handler.ModbusTcpThingHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.ModbusCommunicationInterface;
import org.openhab.core.io.transport.modbus.ModbusReadCallback;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.io.transport.modbus.PollTask;
import org.openhab.core.library.types.DecimalType;
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
class MigrationAuthorizedFreshReadTest {
    private int reloadPhase;
    private boolean manualSibling;
    private int beforeObservation;
    private String contract = "number";
    private List<String> readTransform = List.of("default");
    private final AtomicInteger boundedTransformCalls = new AtomicInteger();

    @Test
    void boundedThingMapUsesFreshDetachedTransformedAndRawEvidence() throws Exception {
        contract = "switch";
        readTransform = List.of("MAP(fixture.map)");
        try (var helper = mockStatic(org.openhab.core.transform.TransformationHelper.class)) {
            var transform = mock(org.openhab.core.transform.TransformationService.class);
            helper.when(() -> org.openhab.core.transform.TransformationHelper.getTransformationService("MAP"))
                    .thenReturn(transform);
            when(transform.transform(anyString(), anyString())).thenAnswer(call -> {
                boundedTransformCalls.incrementAndGet();
                return "ON";
            });
            validationSubmitsNewReadsInsteadOfReusingPeriodicEvidence(false);
            assertEquals(2, boundedTransformCalls.get(),
                    "Only the exact selected legacy channel and target may transform");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "switch", "contact", "lastReadSuccess", "lastReadError" })
    void exactReadonlySourceContractsValidateWithoutOperationalPublication(String role) throws Exception {
        contract = role;
        validationSubmitsNewReadsInsteadOfReusingPeriodicEvidence(false);
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3, 4 })
    void authoritativeDecoderReplacementBeforeObservationRefusesUntilCurrentChildRegistered(int replacement)
            throws Exception {
        beforeObservation = replacement;
        validationSubmitsNewReadsInsteadOfReusingPeriodicEvidence(false);
    }

    @Test
    void migrationDoesNotDecodeManualCustomSibling() throws Exception {
        manualSibling = true;
        validationSubmitsNewReadsInsteadOfReusingPeriodicEvidence(false);
    }

    @ParameterizedTest
    @ValueSource(ints = { 8, 9, 11, 12 })
    void sourceReplacementInWriteAheadCallbackDoesNotMutateLinks(int phase) throws Exception {
        reloadPhase = phase;
        validationSubmitsNewReadsInsteadOfReusingPeriodicEvidence(true);
    }

    @ParameterizedTest
    @ValueSource(ints = { 5, 6, 7 })
    void workflowCompletionCallbackRetainsAuthorizationAndCompensates(int phase) throws Exception {
        reloadPhase = phase;
        validationSubmitsNewReadsInsteadOfReusingPeriodicEvidence(true);
    }

    @ParameterizedTest
    @ValueSource(ints = { 3, 4 })
    void authoritativeSourceReplacementDuringMutationRefusesAndCompensates(int phase) throws Exception {
        reloadPhase = phase;
        validationSubmitsNewReadsInsteadOfReusingPeriodicEvidence(true);
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void candidateReloadDuringMutationCompensatesLinks(int phase) throws Exception {
        reloadPhase = phase;
        validationSubmitsNewReadsInsteadOfReusingPeriodicEvidence(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void validationSubmitsNewReadsInsteadOfReusingPeriodicEvidence(boolean cutover) throws Exception {
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
        var completionChange = new AtomicReference<Runnable>(() -> {
        });
        var intentChange = new AtomicReference<Runnable>(() -> {
        });
        var storageBackend = spy(ManagedModbusMigrationStagingTest.storage(new HashMap<>()));
        doAnswer(call -> {
            Object previous = call.callRealMethod();
            String key = call.getArgument(0);
            String json = call.getArgument(1);
            if (key.startsWith("v1:workflow:") && json.contains("\"switched\":[\"")) {
                completionChange.getAndSet(() -> {
                }).run();
            }
            if (key.startsWith("v2:links:") && json.contains("REMOVE_INTENDED")) {
                intentChange.getAndSet(() -> {
                }).run();
            }
            return previous;
        }).when(storageBackend).put(anyString(), anyString());
        when(storage.<String> getStorage("modbus-migration-journal")).thenReturn(storageBackend);

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
        var sourceChannel = new ChannelUID(sourceUid, contract);
        Thing source = ThingBuilder.create(new ThingTypeUID("modbus", "data"), sourceUid)
                .withBridge(pollerThing.getUID())
                .withConfiguration(new Configuration(
                        Map.of("readStart", "100", "readValueType", "uint16", "readTransform", readTransform)))
                .withChannel(ChannelBuilder.create(sourceChannel).withAcceptedItemType(switch (contract) {
                    case "switch" -> "Switch";
                    case "contact" -> "Contact";
                    case "lastReadSuccess", "lastReadError" -> "DateTime";
                    default -> "Number";
                }).build()).build();
        if (!MigrationReadSelection.identityTransform(readTransform)) {
            source = ThingBuilder.create(source).withChannel(
                    ChannelBuilder.create(new ChannelUID(sourceUid, "number")).withAcceptedItemType("Number").build())
                    .build();
        }
        var child = new ModbusDataThingHandler(source) {
            @Override
            protected @Nullable Bridge getBridge() {
                return pollerThing;
            }
        };
        source.setHandler(child);
        var childCallback = mock(ThingHandlerCallback.class);
        child.setCallback(childCallback);
        child.initialize();
        poller.childHandlerInitialized(child, source);
        state.put(sourceUid, source);
        var originalLink = new ItemChannelLink("DelayedValue", sourceChannel);
        routing.put(originalLink.getUID(), originalLink);
        var customTransforms = new AtomicInteger();
        ModbusDataThingHandler sibling = null;
        if (manualSibling) {
            Thing manual = ThingBuilder
                    .create(new ThingTypeUID("modbus", "data"), new ThingUID("modbus:data:delayed:holding:manual"))
                    .withBridge(pollerThing.getUID())
                    .withConfiguration(new Configuration(Map.of("readStart", "100", "readValueType", "uint16",
                            "readTransform", List.of("JS:manual.js"))))
                    .withChannel(ChannelBuilder.create(new ChannelUID("modbus:data:delayed:holding:manual:number"))
                            .withAcceptedItemType("Number").build())
                    .build();
            sibling = new ModbusDataThingHandler(manual) {
                @Override
                protected @Nullable Bridge getBridge() {
                    return pollerThing;
                }
            };
            sibling.setCallback(mock(ThingHandlerCallback.class));
            try (var construction = mockConstruction(ModbusTransformation.class, (transform, context) -> {
                when(transform.isIdentityTransform()).thenReturn(false);
                when(transform.transformState(anyList(), any())).thenAnswer(call -> {
                    customTransforms.incrementAndGet();
                    return new DecimalType(42);
                });
            })) {
                sibling.initialize();
            }
            manual.setHandler(sibling);
            poller.childHandlerInitialized(sibling, manual);
            state.put(manual.getUID(), manual);
        }
        var service = new ModbusMigrationService(things, links, provider, storage,
                new LiveModbusMigrationValidation(things), linkProvider);
        ModbusPoller2ThingHandler candidate = null;
        try {
            var before = service.preview().plan();
            assertEquals(1, before.groups().size());
            service.apply(before.identity());
            oldPeriodicCallback.handle(new AsyncModbusReadResult(requests.getValue(), new ModbusRegisterArray(7, 8)));
            if (MigrationReadSelection.identityTransform(readTransform)) {
                assertEquals("7", child.getObservation().orElseThrow().evidence()
                        .get(new ChannelUID(sourceUid, "number")).value().toString());
            }
            Thing changed = ThingBuilder.create(source)
                    .withConfiguration(new Configuration(
                            Map.of("readStart", "101", "readValueType", "uint16", "readTransform", readTransform)))
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
            var candidateCallback = mock(ThingHandlerCallback.class);
            candidate.setCallback(candidateCallback);
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
            oldPeriodicCallback.handle(oldResponse);
            clearInvocations(childCallback, candidateCallback);
            customTransforms.set(0);
            boundedTransformCalls.set(0);
            var legacyBeforeValidation = child.getObservation();
            var candidateBeforeValidation = candidate.getObservation();
            clearInvocations(communication);
            if (manualSibling) {
                ThingUID manualUid = new ThingUID("modbus:data:delayed:holding:manual");
                for (var invalid : List.of(new MigrationReadSelection(Map.of()),
                        new MigrationReadSelection(Map.of(new ThingUID("modbus:data:delayed:holding:missing"),
                                new MigrationReadSelection.Binding(child.getThing()))),
                        new MigrationReadSelection(Map.of(sourceUid,
                                new MigrationReadSelection.Binding(child.getThing(), child, "stale"))),
                        new MigrationReadSelection(Map.of(manualUid,
                                new MigrationReadSelection.Binding(Objects.requireNonNull(state.get(manualUid))))))) {
                    var refused = poller.readForMigration(invalid);
                    assertTrue(refused.completion().isCompletedExceptionally());
                    assertFalse(refused.current().getAsBoolean());
                }
                assertTrue(poller.readForMigration().completion().isCompletedExceptionally());
                verify(communication, never()).submitOneTimePoll(any(), any(), any());
                assertEquals(0, customTransforms.get());
            }
            doAnswer(call -> {
                ModbusReadRequestBlueprint request = call.getArgument(0);
                ModbusReadCallback callback = call.getArgument(1);
                callback.handle(new AsyncModbusReadResult(request, new ModbusRegisterArray(42, 42)));
                return null;
            }).when(communication).submitOneTimePoll(any(), any(), any());
            if (beforeObservation > 0) {
                Thing authoritative = beforeObservation == 2 ? ThingBuilder.create(child.getThing()).build()
                        : child.getThing();
                var replacement = new ModbusDataThingHandler(authoritative) {
                    @Override
                    protected @Nullable Bridge getBridge() {
                        return pollerThing;
                    }
                };
                replacement.setCallback(mock(ThingHandlerCallback.class));
                authoritative.setHandler(beforeObservation == 3 ? null
                        : beforeObservation == 4 ? mock(ModbusTcpThingHandler.class)
                                : beforeObservation == 2 ? child : replacement);
                state.put(sourceUid, authoritative);
                try {
                    // No disposal notification: the old real decoder remains registered with identical configuration.
                    assertEquals(MigrationValidation.Outcome.UNAVAILABLE,
                            service.validate(reviewed.identity(), group.sourcePollerUid()).outcome());
                    assertThrows(IllegalStateException.class,
                            () -> service.cutover(reviewed.identity(), group.sourcePollerUid()));
                    verify(links, never()).remove(anyString());
                    verify(links, never()).add(any());
                    assertEquals(Map.of(originalLink.getUID(), originalLink), routing);
                    authoritative.setHandler(replacement);
                    replacement.initialize();
                    poller.childHandlerInitialized(replacement, authoritative);
                    clearInvocations(communication);
                    assertEquals(MigrationValidation.Outcome.MATCH,
                            service.validate(reviewed.identity(), group.sourcePollerUid()).outcome());
                    verify(communication, times(2)).submitOneTimePoll(any(), any(), any());
                    assertEquals(Map.of(originalLink.getUID(), originalLink), routing);
                    service.cutover(reviewed.identity(), group.sourcePollerUid());
                    assertFalse(routing.containsKey(originalLink.getUID()));
                    assertEquals(1, routing.size());
                } finally {
                    replacement.dispose();
                }
                return;
            }
            if (reloadPhase > 0) {
                var liveCandidate = candidate;
                if (reloadPhase >= 11) {
                    intentChange.set(() -> {
                        if (reloadPhase == 11) {
                            child.getThing().setHandler(mock(ModbusDataThingHandler.class));
                        } else {
                            liveCandidate.getThing().setHandler(mock(ModbusPoller2ThingHandler.class));
                        }
                    });
                } else if (reloadPhase >= 8) {
                    intentChange
                            .set(() -> state
                                    .put(sourceUid,
                                            ThingBuilder
                                                    .create(child
                                                            .getThing())
                                                    .withConfiguration(new Configuration(Map.of("readStart",
                                                            reloadPhase == 8 ? "100" : "101", "readValueType", "uint16",
                                                            "readTransform", List.of(reloadPhase == 8 ? "default"
                                                                    : "JS:unexpected.js"))))
                                                    .build()));
                } else if (reloadPhase >= 5) {
                    completionChange.set(() -> {
                        if (reloadPhase == 5) {
                            state.put(sourceUid,
                                    ThingBuilder.create(child.getThing())
                                            .withConfiguration(new Configuration(Map.of("readStart", "100",
                                                    "readValueType", "uint16", "readTransform", List.of("default"))))
                                            .build());
                        } else if (reloadPhase == 6) {
                            state.put(liveCandidate.getThing().getUID(),
                                    ThingBuilder.create(liveCandidate.getThing()).build());
                        } else {
                            liveCandidate.initialize();
                        }
                    });
                } else if (reloadPhase >= 3) {
                    Runnable replaceSource = () -> state.put(sourceUid,
                            ThingBuilder.create(child.getThing())
                                    .withConfiguration(new Configuration(Map.of("readStart", "100", "readValueType",
                                            "uint16", "readTransform", List.of("JS:unexpected.js"))))
                                    .build());
                    if (reloadPhase == 3) {
                        doAnswer(call -> {
                            var removed = routing.remove(call.getArgument(0));
                            replaceSource.run();
                            return removed;
                        }).when(links).remove(originalLink.getUID());
                    } else {
                        doAnswer(call -> {
                            ItemChannelLink added = call.getArgument(0);
                            routing.put(added.getUID(), added);
                            if (!added.getUID().equals(originalLink.getUID())) {
                                replaceSource.run();
                            }
                            return null;
                        }).when(links).add(any());
                    }
                } else if (reloadPhase == 1) {
                    doAnswer(call -> {
                        var removed = routing.remove(call.getArgument(0));
                        liveCandidate.initialize();
                        return removed;
                    }).when(links).remove(originalLink.getUID());
                } else {
                    doAnswer(call -> {
                        ItemChannelLink added = call.getArgument(0);
                        routing.put(added.getUID(), added);
                        if (!added.getUID().equals(originalLink.getUID())) {
                            liveCandidate.initialize();
                        }
                        return null;
                    }).when(links).add(any());
                }
                assertThrows(IllegalStateException.class,
                        () -> service.cutover(reviewed.identity(), group.sourcePollerUid()));
                assertEquals(Map.of(originalLink.getUID(), originalLink), routing);
                assertTrue(service.report(reviewed.identity()).switchedLinks().isEmpty());
                assertEquals(MigrationWorkflowReport.Status.RECOVERY, service.report(reviewed.identity()).status());
                var reconstructed = new ModbusMigrationService(things, links, provider, storage,
                        new LiveModbusMigrationValidation(things), linkProvider);
                assertTrue(reconstructed.report(reviewed.identity()).switchedLinks().isEmpty());
                assertEquals(MigrationWorkflowReport.Status.RECOVERY,
                        reconstructed.report(reviewed.identity()).status());
                if (reloadPhase >= 8) {
                    verify(links, never()).remove(anyString());
                    verify(links, never()).add(any());
                    assertSame(originalLink, routing.get(originalLink.getUID()));
                }
                return;
            }
            if (cutover) {
                service.cutover(reviewed.identity(), group.sourcePollerUid());
                assertFalse(routing.containsKey(originalLink.getUID()));
            } else {
                assertEquals(MigrationValidation.Outcome.MATCH,
                        service.validate(reviewed.identity(), group.sourcePollerUid()).outcome());
                assertEquals(Map.of(originalLink.getUID(), originalLink), routing);
            }
            assertEquals(0, customTransforms.get(), "Migration must not invoke unrelated manual transformations");
            verify(communication, times(2)).submitOneTimePoll(any(), any(), any());
            verifyNoInteractions(childCallback, candidateCallback);
            assertEquals(legacyBeforeValidation, child.getObservation(),
                    "Migration must not renew operational evidence");
            assertEquals(candidateBeforeValidation, candidate.getObservation(),
                    "Migration must decode detached evidence");
        } finally {
            if (candidate != null) {
                candidate.dispose();
            }
            if (sibling != null) {
                sibling.dispose();
            }
            child.dispose();
            poller.dispose();
        }
    }

    private static void online(Thing thing) {
        thing.setStatusInfo(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null));
    }
}
