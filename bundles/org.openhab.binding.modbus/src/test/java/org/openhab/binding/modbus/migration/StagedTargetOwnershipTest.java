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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openhab.binding.modbus.handler.ModbusPollerThingHandler;
import org.openhab.binding.modbus.internal.handler.ModbusDataThingHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingProvider;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.link.ManagedItemChannelLinkProvider;

/** @author Sami Salonen - Staged target ownership regression coverage */
class StagedTargetOwnershipTest {
    private final ProviderOwnedMigrationPreviewTest.OwnedThingRegistry things = mock(
            ProviderOwnedMigrationPreviewTest.OwnedThingRegistry.class);
    private final ManagedThingProvider provider = mock(ManagedThingProvider.class);
    private final ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
    private final Map<ThingUID, Thing> inventory = new HashMap<>();
    private final Map<ThingUID, Thing> managed = new HashMap<>();
    private final Map<String, ItemChannelLink> routing = new HashMap<>();
    private final Map<String, String> persisted = new HashMap<>();
    private final ThingUID source = new ThingUID("modbus:poller:tcp:plant");
    private final ThingUID target = new ThingUID("modbus:poller2:tcp:plant");
    private final ItemChannelLink original = new ItemChannelLink("Value",
            new ChannelUID("modbus:data:tcp:plant:value:number"));
    private Runnable duringObservation = () -> {
    };

    private ModbusMigrationService service() {
        var poller = BridgeBuilder.create(new ThingTypeUID("modbus", "poller"), source)
                .withBridge(new ThingUID("modbus:tcp:plant"))
                .withConfiguration(new Configuration(Map.of("start", 100, "length", 1, "type", "holding"))).build();
        var data = ThingBuilder.create(new ThingTypeUID("modbus", "data"), original.getLinkedUID().getThingUID())
                .withBridge(source)
                .withConfiguration(new Configuration(Map.of("readStart", "100", "readValueType", "int16")))
                .withChannel(ChannelBuilder.create(original.getLinkedUID()).withAcceptedItemType("Number").build())
                .build();
        inventory.put(source, poller);
        inventory.put(data.getUID(), data);
        managed.putAll(inventory);
        routing.put(original.getUID(), original);
        when(things.getAll()).thenAnswer(call -> List.copyOf(inventory.values()));
        when(things.get(any(ThingUID.class))).thenAnswer(call -> inventory.get(call.getArgument(0)));
        when(things.getProvider(any(Thing.class))).thenReturn(provider);
        when(provider.get(any(ThingUID.class))).thenAnswer(call -> managed.get(call.getArgument(0)));
        doAnswer(call -> {
            Thing thing = call.getArgument(0);
            managed.put(thing.getUID(), thing);
            inventory.put(thing.getUID(), thing);
            return null;
        }).when(provider).add(any());
        when(provider.remove(any(ThingUID.class))).thenAnswer(call -> {
            ThingUID uid = call.getArgument(0);
            inventory.remove(uid);
            return managed.remove(uid);
        });
        when(links.getAll()).thenAnswer(call -> List.copyOf(routing.values()));
        when(links.get(anyString())).thenAnswer(call -> routing.get(call.getArgument(0)));
        when(links.getProvider(any(ItemChannelLink.class))).thenReturn(mock(ManagedItemChannelLinkProvider.class));
        when(links.remove(anyString())).thenAnswer(call -> routing.remove(call.getArgument(0)));
        doAnswer(call -> {
            ItemChannelLink link = call.getArgument(0);
            routing.put(link.getUID(), link);
            return null;
        }).when(links).add(any());
        var storage = mock(StorageService.class);
        when(storage.<String> getStorage("modbus-migration-journal"))
                .thenReturn(ManagedModbusMigrationStagingTest.storage(persisted));
        return new ModbusMigrationService(things, links, provider, storage, (preview, group, time) -> {
            duringObservation.run();
            recordFreshEvidence();
            return new LiveModbusMigrationValidation(things).observe(preview, group, time);
        });
    }

    private void recordFreshEvidence() {
        // Narrow typed transport-completion fixtures; ownership remains enforced by the real service/applier.
        Map<ThingUID, Poller2Observation> legacyValues = new HashMap<>();
        for (Thing thing : inventory.values()) {
            if (!thing.getThingTypeUID().getId().equals("data") && !thing.getUID().equals(target)) {
                continue;
            }
            var collector = new Poller2ObservationCollector();
            var channel = thing.getChannels().getFirst().getUID();
            var value = new DecimalType(42);
            collector.record(collector.beginLifecycle(thing), Instant.now(), Map.of(channel, value),
                    Map.of(channel, new ModbusReadEvidence(value, value, ModbusReadEvidence.Provenance.DECODED)));
            var observation = collector.getObservation().orElseThrow();
            if (thing.getUID().equals(target)) {
                attachRequest(thing, Map.of(thing.getUID(), observation));
            } else {
                thing.setHandler(mock(ModbusDataThingHandler.class));
                legacyValues.put(thing.getUID(), observation);
            }
        }
        attachRequest(Objects.requireNonNull(inventory.get(source)), Map.copyOf(legacyValues));
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals") // Fixture request belongs to this exact live Thing and handler.
    private void attachRequest(Thing thing, Map<ThingUID, Poller2Observation> values) {
        var handler = mock(ModbusPollerThingHandler.class);
        thing.setHandler(handler);
        when(handler.readForMigration(any(MigrationReadSelection.class)))
                .thenAnswer(call -> new MigrationRead(CompletableFuture.completedFuture(values),
                        () -> inventory.get(thing.getUID()) == thing && thing.getHandler() == handler));
    }

    private void replace(String ownership, String identity) {
        Thing staged = inventory.get(target);
        var replacement = BridgeBuilder.create(staged.getThingTypeUID(), target).withBridge(staged.getBridgeUID())
                .withConfiguration(staged.getConfiguration()).withChannels(staged.getChannels())
                .withProperties(ownership.equals("missing-marker") ? Map.of()
                        : Map.of("modbusMigrationIdentity", ownership.equals("wrong-marker") ? "other" : identity))
                .build();
        inventory.put(target, replacement);
        // A stale managed definition must not override authoritative registry ownership.
        if (ownership.equals("unmanaged")) {
            when(things.getProvider(replacement)).thenReturn(mock(ThingProvider.class));
        } else if (ownership.equals("unknown")) {
            when(things.getProvider(replacement)).thenReturn(null);
        } else if (ownership.equals("other-managed")) {
            when(things.getProvider(replacement)).thenReturn(mock(ManagedThingProvider.class));
        } else if (ownership.equals("no-managed")) {
            managed.remove(target);
        } else if (ownership.equals("missing-target")) {
            inventory.remove(target);
        } else {
            managed.put(target, replacement);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "before", "preflight-listener", "remove-listener", "add-listener", "no-journal" })
    void applierRechecksOwnershipAtLinkMutationBoundaries(String boundary) {
        var service = service();
        var plan = service.preview().plan();
        String identity = plan.identity();
        service.apply(identity);
        var journal = new MigrationJournal(ManagedModbusMigrationStagingTest.storage(persisted));
        if (boundary.equals("before")) {
            replace("unmanaged", identity);
        } else if (boundary.equals("preflight-listener")) {
            when(links.get(original.getUID())).thenAnswer(call -> {
                replace("unmanaged", identity);
                return original;
            });
        } else if (boundary.equals("remove-listener")) {
            doAnswer(call -> {
                replace("unmanaged", identity);
                return routing.remove(original.getUID());
            }).when(links).remove(original.getUID());
        } else if (boundary.equals("add-listener")) {
            doAnswer(call -> {
                ItemChannelLink link = call.getArgument(0);
                routing.put(link.getUID(), link);
                replace("unmanaged", identity);
                return null;
            }).when(links).add(argThat(link -> !link.getUID().equals(original.getUID())));
        } else {
            journal.targets(identity, List.of());
        }
        var applier = new ManagedModbusMigrationApplier(provider, links, journal, things);
        assertThrows(IllegalStateException.class, () -> applier.cutover(plan, plan.groups().getFirst()));
        if (boundary.equals("add-listener")) {
            // A foreign candidate no longer authorizes deletion of its connection or dual source delivery.
            assertFalse(routing.containsKey(original.getUID()));
            assertEquals(1, routing.size());
            assertEquals(plan.groups().getFirst().links(), journal.links(identity + ":" + source));
        } else {
            assertEquals(Map.of(original.getUID(), original), routing);
            assertTrue(journal.links(identity + ":" + source).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void ownershipDriftDuringObservationRefusesBothOperations(boolean cutover) {
        var service = service();
        String identity = service.preview().plan().identity();
        service.apply(identity);
        duringObservation = () -> replace("unmanaged", identity);
        assertThrows(IllegalArgumentException.class, () -> {
            if (cutover) {
                service.cutover(identity, source.toString());
            } else {
                service.validate(identity, source.toString());
            }
        });
        assertEquals(Map.of(original.getUID(), original), routing);
        verify(links, never()).add(any());
        verify(links, never()).remove(anyString());
    }

    @Test
    void ownershipChangeDuringWriteAheadIntentDoesNotRemoveProductionLink() {
        var service = service();
        var plan = service.preview().plan();
        service.apply(plan.identity());
        var storage = spy(ManagedModbusMigrationStagingTest.storage(persisted));
        doAnswer(call -> {
            String key = call.getArgument(0);
            String previous = persisted.put(key, call.getArgument(1));
            if (key.startsWith("v2:links:")) {
                replace("unmanaged", plan.identity());
            }
            return previous;
        }).when(storage).put(anyString(), anyString());
        var applier = new ManagedModbusMigrationApplier(provider, links, new MigrationJournal(storage), things);
        assertThrows(IllegalStateException.class, () -> applier.cutover(plan, plan.groups().getFirst()));
        assertEquals(Map.of(original.getUID(), original), routing);
        verify(links, never()).remove(original.getUID());
        verify(links, never()).add(any());
    }

    @Test
    void missingJournalCannotExemptAnOtherwiseManagedMarkedTarget() {
        var service = service();
        String identity = service.preview().plan().identity();
        service.apply(identity);
        new MigrationJournal(ManagedModbusMigrationStagingTest.storage(persisted)).targets(identity, List.of());
        var restarted = service();
        var before = Map.copyOf(persisted);
        assertThrows(IllegalArgumentException.class, () -> restarted.validate(identity, source.toString()));
        assertThrows(IllegalArgumentException.class, () -> restarted.cutover(identity, source.toString()));
        assertEquals(Map.of(original.getUID(), original), routing);
        assertEquals(before, persisted);
    }

    @Test
    void registryWithoutAuthoritativeOwnershipCannotClaimMarkedTarget() {
        var service = service();
        String identity = service.preview().plan().identity();
        service.apply(identity);
        var unknown = mock(ThingRegistry.class);
        when(unknown.getAll()).thenAnswer(call -> List.copyOf(inventory.values()));
        when(unknown.get(any(ThingUID.class))).thenAnswer(call -> inventory.get(call.getArgument(0)));
        var storage = mock(StorageService.class);
        when(storage.<String> getStorage("modbus-migration-journal"))
                .thenReturn(ManagedModbusMigrationStagingTest.storage(persisted));
        var restarted = new ModbusMigrationService(unknown, links, provider, storage,
                (plan, group, time) -> new MigrationValidation.Observation(plan.identity(), group.sourcePollerUid(),
                        Instant.now(), MigrationValidation.Outcome.MATCH));
        assertThrows(IllegalArgumentException.class, () -> restarted.validate(identity, source.toString()));
        assertThrows(IllegalArgumentException.class, () -> restarted.cutover(identity, source.toString()));
        assertThrows(IllegalStateException.class, () -> restarted.rollback(identity));
        assertEquals(Map.of(original.getUID(), original), routing);
        verify(provider, never()).remove(target);
    }

    @Test
    void reviewedManagedTargetRemainsEligibleButOrdinaryPreviewRefusesIt() {
        var service = service();
        String identity = service.preview().plan().identity();
        service.apply(identity);
        assertTrue(service.preview().plan().groups().isEmpty());
        assertEquals(MigrationValidation.Outcome.MATCH, service.validate(identity, source.toString()).outcome());
        service.cutover(identity, source.toString());
        assertEquals(1, routing.size());
        assertEquals(target, routing.values().iterator().next().getLinkedUID().getThingUID());
        service.rollback(identity);
        assertEquals(Map.of(original.getUID(), original), routing);
        assertFalse(inventory.containsKey(target));
    }

    @ParameterizedTest
    @ValueSource(strings = { "unknown", "unmanaged", "other-managed", "no-managed", "missing-target", "missing-marker",
            "wrong-marker" })
    void replacementCannotValidateCutoverOrBeRemoved(String ownership) {
        var service = service();
        var plan = service.preview().plan();
        String identity = plan.identity();
        service.apply(identity);
        assertEquals(MigrationValidation.Outcome.MATCH, service.validate(identity, source.toString()).outcome());
        replace(ownership, identity);
        Thing replacement = inventory.get(target);
        if (replacement != null) {
            recordFreshEvidence();
            var raw = new LiveModbusMigrationValidation(things).observe(plan, plan.groups().getFirst(),
                    service.report(identity).stagedAt());
            assertEquals(MigrationValidation.Outcome.MATCH, raw.outcome(), raw.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> service.validate(identity, source.toString()));
        assertThrows(IllegalArgumentException.class, () -> service.cutover(identity, source.toString()));
        assertThrows(IllegalStateException.class, () -> service.rollback(identity));
        assertSame(replacement, inventory.get(target));
        assertEquals(Map.of(original.getUID(), original), routing);
        verify(links, never()).add(any());
        verify(links, never()).remove(anyString());
        verify(provider, never()).remove(target);
    }
}
