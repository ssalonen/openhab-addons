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

import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.storage.Storage;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.link.ManagedItemChannelLinkProvider;

/** @author Sami Salonen - Unlinked staging coverage */
class ManagedModbusMigrationStagingTest {
    static ProviderOwnedMigrationPreviewTest.OwnedThingRegistry managedRegistry(ManagedThingProvider provider) {
        var registry = mock(ProviderOwnedMigrationPreviewTest.OwnedThingRegistry.class);
        trackManagedTargets(registry, provider);
        return registry;
    }

    static void trackManagedTargets(ProviderOwnedMigrationPreviewTest.OwnedThingRegistry registry,
            ManagedThingProvider provider) {
        var targets = new HashMap<ThingUID, Thing>();
        when(provider.get(any(ThingUID.class))).thenAnswer(call -> targets.get(call.getArgument(0)));
        when(registry.get(any(ThingUID.class))).thenAnswer(call -> provider.get(call.getArgument(0)));
        when(registry.getProvider(any(Thing.class))).thenReturn(provider);
        doAnswer(call -> {
            var target = call.getArgument(0, Thing.class);
            targets.put(target.getUID(), target);
            return null;
        }).when(provider).add(any());
    }

    @Test
    void stagingNeverCreatesProductionLinks() {
        ManagedThingProvider provider = mock(ManagedThingProvider.class);
        ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
        lenient().when(links.getProvider(any(ItemChannelLink.class)))
                .thenReturn(mock(ManagedItemChannelLinkProvider.class));
        new ManagedModbusMigrationApplier(provider, links, managedRegistry(provider)).createTargets(preview());
        verify(provider).add(any());
        verifyNoInteractions(links);
    }

    @Test
    void failedStageCompensationRetainsRetryableOwnedTargets() {
        ManagedThingProvider provider = mock(ManagedThingProvider.class);
        ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
        lenient().when(links.getProvider(any(ItemChannelLink.class)))
                .thenReturn(mock(ManagedItemChannelLinkProvider.class));
        List<LegacyPoller> pollers = List.of(
                new LegacyPoller("modbus:poller:tcp:a", "modbus:tcp:plant",
                        Map.of("start", 100, "length", 2, "type", "holding")),
                new LegacyPoller("modbus:poller:tcp:b", "modbus:tcp:plant",
                        Map.of("start", 100, "length", 2, "type", "holding")));
        ThingUID first = new ThingUID("modbus:poller2:tcp:a");
        var things = managedRegistry(provider);
        doNothing().doThrow(new IllegalStateException("create failed")).when(provider).add(any());
        when(provider.remove(first)).thenThrow(new IllegalStateException("compensation failed")).thenReturn(null);
        Map<String, String> persisted = new HashMap<>();
        MigrationJournal journal = new MigrationJournal(storage(persisted));
        ModbusMigrationWorkflow workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(provider, links, journal, things), journal);
        List<LegacyData> data = List.of(
                new LegacyData("modbus:data:tcp:a:value", "modbus:poller:tcp:a",
                        Map.of("readStart", "100", "readValueType", "int16"), List.of()),
                new LegacyData("modbus:data:tcp:b:value", "modbus:poller:tcp:b",
                        Map.of("readStart", "100", "readValueType", "int16"), List.of()));
        MigrationPreview preview = workflow.preview(pollers, data);
        Assertions.assertThrows(IllegalStateException.class, () -> workflow.apply(preview.identity(), pollers, data));
        MigrationJournal recovered = new MigrationJournal(storage(persisted));
        new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(provider, links, recovered, things), recovered)
                .rollback(preview.identity());
        verify(provider, times(2)).remove(first);
        verify(provider).remove(new ThingUID("modbus:poller2:tcp:b"));
        verifyNoInteractions(links);
    }

    private Map<String, ItemChannelLink> configureLinkState(ItemChannelLinkRegistry links, ItemChannelLink initial) {
        Map<String, ItemChannelLink> state = new HashMap<>();
        state.put(initial.getUID(), initial);
        when(links.get(anyString())).thenAnswer(call -> state.get(call.getArgument(0)));
        when(links.remove(anyString())).thenAnswer(call -> state.remove(call.getArgument(0)));
        doAnswer(call -> {
            var added = call.getArgument(0, ItemChannelLink.class);
            state.put(added.getUID(), added);
            return null;
        }).when(links).add(any());
        return state;
    }

    @Test
    void explicitCutoverReplacesLegacyLinkInsteadOfDualLinking() {
        ManagedThingProvider provider = mock(ManagedThingProvider.class);
        ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
        lenient().when(links.getProvider(any(ItemChannelLink.class)))
                .thenReturn(mock(ManagedItemChannelLinkProvider.class));
        var things = managedRegistry(provider);
        MigrationPreview preview = preview();
        MigrationGroup group = preview.groups().getFirst();
        MigrationLink mapping = group.links().getFirst();
        ItemChannelLink legacy = new ItemChannelLink(mapping.itemName(), new ChannelUID(mapping.sourceChannelUid()));
        configureLinkState(links, legacy);
        ModbusMigrationWorkflow workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(provider, links, new MigrationJournal(), things),
                (reviewed, candidate, stagedAt) -> new MigrationValidation.Observation(reviewed.identity(),
                        candidate.sourcePollerUid(), Instant.now(), MigrationValidation.Outcome.MATCH));
        var pollers = List.of(new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding")));
        var data = List.of(new LegacyData("modbus:data:tcp:plant:temperature", "modbus:poller:tcp:plant",
                Map.of("readStart", "100", "readValueType", "int16"), List.of(new LegacyLink("Temperature",
                        "modbus:data:tcp:plant:temperature:number", "Number", Map.of()))));
        var reviewed = workflow.preview(pollers, data);
        workflow.apply(reviewed.identity(), pollers, data);
        verifyNoInteractions(links);
        workflow.cutover(reviewed.identity(), group.sourcePollerUid(), pollers, data);
        var order = inOrder(links);
        order.verify(links).get(legacy.getUID());
        order.verify(links)
                .get(new ItemChannelLink(mapping.itemName(), new ChannelUID(mapping.targetChannelUid())).getUID());
        order.verify(links).remove(legacy.getUID());
        order.verify(links).add(argThat(link -> link.getLinkedUID().toString().equals(mapping.targetChannelUid())));
    }

    @Test
    void cutoverCompensationFailureCanBeRetriedByRollback() {
        ManagedThingProvider provider = mock(ManagedThingProvider.class);
        ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
        lenient().when(links.getProvider(any(ItemChannelLink.class)))
                .thenReturn(mock(ManagedItemChannelLinkProvider.class));
        Map<String, String> persisted = new HashMap<>();
        MigrationJournal journal = new MigrationJournal(storage(persisted));
        var things = managedRegistry(provider);
        ManagedModbusMigrationApplier applier = new ManagedModbusMigrationApplier(provider, links, journal, things);
        MigrationPreview preview = preview();
        MigrationGroup group = preview.groups().getFirst();
        MigrationLink mapping = group.links().getFirst();
        var source = new ItemChannelLink(mapping.itemName(), new ChannelUID(mapping.sourceChannelUid()));
        var target = new ItemChannelLink(mapping.itemName(), new ChannelUID(mapping.targetChannelUid()));
        var routing = configureLinkState(links, source);
        doAnswer(call -> {
            var added = call.getArgument(0, ItemChannelLink.class);
            routing.put(added.getUID(), added);
            throw new IllegalStateException("listener failed after addition");
        }).when(links).add(argThat(link -> link.getUID().equals(target.getUID())));
        when(links.remove(target.getUID())).thenThrow(new IllegalStateException("compensation failed"))
                .thenAnswer(call -> routing.remove(target.getUID()));
        var pollers = List.of(new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding")));
        var data = List.of(new LegacyData("modbus:data:tcp:plant:temperature", "modbus:poller:tcp:plant",
                Map.of("readStart", "100", "readValueType", "int16"), List.of(new LegacyLink("Temperature",
                        "modbus:data:tcp:plant:temperature:number", "Number", Map.of()))));
        ModbusMigrationWorkflow workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(), applier,
                (reviewed, candidate, stagedAt) -> new MigrationValidation.Observation(reviewed.identity(),
                        candidate.sourcePollerUid(), Instant.now(), MigrationValidation.Outcome.MATCH),
                journal);
        var reviewed = workflow.preview(pollers, data);
        workflow.apply(reviewed.identity(), pollers, data);
        var failure = Assertions.assertThrows(IllegalStateException.class,
                () -> workflow.cutover(reviewed.identity(), group.sourcePollerUid(), pollers, data));
        Assertions.assertEquals(1, failure.getSuppressed().length);
        verify(links, never()).add(source);
        MigrationJournal recovered = new MigrationJournal(storage(persisted));
        var reconstructed = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(provider, links, recovered, things,
                        (ManagedItemChannelLinkProvider) links.getProvider(source)),
                recovered);
        // An unconfirmed addition is ambiguous after reconstruction, but the live exact instance is retryable.
        Assertions.assertThrows(IllegalStateException.class,
                () -> reconstructed.revert(reviewed.identity(), mapping.itemName(), mapping.sourceChannelUid()));
        workflow.revert(reviewed.identity(), mapping.itemName(), mapping.sourceChannelUid());
        verify(provider, never()).remove(any());
        verify(links, times(2)).remove(target.getUID());
        verify(links).add(source);
    }

    @Test
    void unavailableValidationCannotAuthorizeCutover() {
        MigrationApplier applier = mock(MigrationApplier.class);
        ModbusMigrationWorkflow workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(), applier);
        var pollers = List.of(new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding")));
        var data = List.of(new LegacyData("modbus:data:tcp:plant:temperature", "modbus:poller:tcp:plant",
                Map.of("readStart", "100", "readValueType", "int16"), List.of()));
        var preview = workflow.preview(pollers, data);
        workflow.apply(preview.identity(), pollers, data);
        Assertions.assertEquals(MigrationValidation.Outcome.UNAVAILABLE,
                workflow.validate(preview.identity(), "modbus:poller:tcp:plant").outcome());
        Assertions.assertThrows(IllegalStateException.class,
                () -> workflow.cutover(preview.identity(), "modbus:poller:tcp:plant", pollers, data));
        verify(applier, never()).cutover(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = { "stale", "wrong-preview", "wrong-group", "future", "mismatch" })
    void invalidObservationCannotAuthorizeCutover(String invalid) {
        MigrationApplier applier = mock(MigrationApplier.class);
        ModbusMigrationWorkflow workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(), applier,
                (preview, group, createdAt) -> new MigrationValidation.Observation(
                        invalid.equals("wrong-preview") ? "wrong" : preview.identity(),
                        invalid.equals("wrong-group") ? "wrong" : group.sourcePollerUid(),
                        invalid.equals("stale") ? createdAt : invalid.equals("future") ? Instant.MAX : Instant.now(),
                        invalid.equals("mismatch") ? MigrationValidation.Outcome.MISMATCH
                                : MigrationValidation.Outcome.MATCH));
        var pollers = List.of(new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding")));
        var data = List.of(new LegacyData("modbus:data:tcp:plant:temperature", "modbus:poller:tcp:plant",
                Map.of("readStart", "100", "readValueType", "int16"), List.of()));
        var preview = workflow.preview(pollers, data);
        workflow.apply(preview.identity(), pollers, data);
        Assertions.assertThrows(IllegalStateException.class,
                () -> workflow.cutover(preview.identity(), "modbus:poller:tcp:plant", pollers, data));
        verify(applier, never()).cutover(any(), any(), any());
    }

    @Test
    void secondPollerCutoverAcceptsOnlyTheExpectedFirstPollerLinkRemoval() {
        MigrationApplier applier = mock(MigrationApplier.class);
        doCallRealMethod().when(applier).cutover(any(), any(), any(), any());
        ModbusMigrationWorkflow workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(), applier,
                (preview, group, createdAt) -> new MigrationValidation.Observation(preview.identity(),
                        group.sourcePollerUid(), Instant.now(), MigrationValidation.Outcome.MATCH));
        var pollers = List.of(
                new LegacyPoller("modbus:poller:tcp:a", "modbus:tcp:plant",
                        Map.of("start", 100, "length", 2, "type", "holding")),
                new LegacyPoller("modbus:poller:tcp:b", "modbus:tcp:plant",
                        Map.of("start", 100, "length", 2, "type", "holding")));
        LegacyData first = new LegacyData("modbus:data:tcp:a:value", "modbus:poller:tcp:a",
                Map.of("readStart", "100", "readValueType", "int16"),
                List.of(new LegacyLink("A", "modbus:data:tcp:a:value:number", "Number")));
        LegacyData second = new LegacyData("modbus:data:tcp:b:value", "modbus:poller:tcp:b",
                Map.of("readStart", "100", "readValueType", "int16"),
                List.of(new LegacyLink("B", "modbus:data:tcp:b:value:number", "Number")));
        var reviewed = workflow.preview(pollers, List.of(first, second));
        workflow.apply(reviewed.identity(), pollers, List.of(first, second));
        workflow.cutover(reviewed.identity(), first.pollerUid(), pollers, List.of(first, second));
        LegacyData unlinkedFirst = new LegacyData(first.uid(), first.pollerUid(), first.configuration(), List.of());
        workflow.cutover(reviewed.identity(), second.pollerUid(), pollers, List.of(unlinkedFirst, second));
        verify(applier, times(2)).cutover(argThat(plan -> plan.identity().equals(reviewed.identity())), any(), any());
    }

    @Test
    void providerThrowAfterAddStillRetainsAttemptedCandidateForCompensation() {
        ManagedThingProvider provider = mock(ManagedThingProvider.class);
        ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
        lenient().when(links.getProvider(any(ItemChannelLink.class)))
                .thenReturn(mock(ManagedItemChannelLinkProvider.class));
        MigrationPreview preview = preview();
        var target = new ThingUID(preview.groups().getFirst().targetPollerUid());
        doThrow(new IllegalStateException("listener failed after add")).when(provider).add(any());
        when(provider.remove(target)).thenThrow(new IllegalStateException("compensation failed")).thenReturn(null);
        ManagedModbusMigrationApplier applier = new ManagedModbusMigrationApplier(provider, links,
                mock(ThingRegistry.class));
        Assertions.assertThrows(ManagedModbusMigrationApplier.IncompleteStageException.class,
                () -> applier.createTargets(preview));
        applier.removeTargets(preview);
        verify(provider, times(2)).remove(target);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("recoverableProfiles")
    void restartRevertRestoresExactProfileWithoutRemovingCandidatesOrUnrelatedLinks(Map<String, Object> profile) {
        Map<String, String> persisted = new HashMap<>();
        Map<String, ItemChannelLink> routing = new HashMap<>();
        ManagedThingProvider provider = mock(ManagedThingProvider.class);
        ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
        lenient().when(links.getProvider(any(ItemChannelLink.class)))
                .thenReturn(mock(ManagedItemChannelLinkProvider.class));
        when(links.get(anyString())).thenAnswer(call -> routing.get(call.getArgument(0)));
        when(links.remove(anyString())).thenAnswer(call -> routing.remove(call.getArgument(0)));
        doAnswer(call -> {
            var link = (ItemChannelLink) call.getArgument(0);
            routing.put(link.getUID(), link);
            return null;
        }).when(links).add(any());
        var pollers = List.of(new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding")));
        var data = List.of(new LegacyData("modbus:data:tcp:plant:temperature", "modbus:poller:tcp:plant",
                Map.of("readStart", "100", "readValueType", "int16"),
                List.of(new LegacyLink("Temperature", "modbus:data:tcp:plant:temperature:number", "Number", profile),
                        new LegacyLink("Second", "modbus:data:tcp:plant:temperature:number", "Number", Map.of()))));
        var things = managedRegistry(provider);
        MigrationJournal journal = new MigrationJournal(storage(persisted));
        ModbusMigrationWorkflow workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(provider, links, journal, things),
                (preview, group, time) -> new MigrationValidation.Observation(preview.identity(),
                        group.sourcePollerUid(), Instant.now(), MigrationValidation.Outcome.MATCH),
                journal);
        var preview = workflow.preview(pollers, data);
        var mapping = preview.groups().getFirst().links().stream().filter(link -> link.itemName().equals("Temperature"))
                .findFirst().orElseThrow();
        var original = new ItemChannelLink(mapping.itemName(), new ChannelUID(mapping.sourceChannelUid()),
                new Configuration(profile));
        var unrelated = new ItemChannelLink("Other", new ChannelUID(mapping.targetChannelUid()));
        var secondMapping = preview.groups().getFirst().links().stream()
                .filter(link -> link.itemName().equals("Second")).findFirst().orElseThrow();
        var secondOriginal = new ItemChannelLink("Second", new ChannelUID(secondMapping.sourceChannelUid()));
        routing.put(secondOriginal.getUID(), secondOriginal);
        routing.put(original.getUID(), original);
        routing.put(unrelated.getUID(), unrelated);
        workflow.apply(preview.identity(), pollers, data);
        Assertions.assertDoesNotThrow(
                () -> workflow.cutover(preview.identity(), pollers.getFirst().uid(), pollers, data));
        Assertions.assertEquals(mapping.targetConfiguration(),
                Objects.requireNonNull(routing.get(
                        new ItemChannelLink(mapping.itemName(), new ChannelUID(mapping.targetChannelUid())).getUID()))
                        .getConfiguration().getProperties());
        MigrationJournal recovered = new MigrationJournal(storage(persisted));
        ModbusMigrationWorkflow restarted = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(provider, links, recovered, things,
                        (ManagedItemChannelLinkProvider) links.getProvider(original)),
                recovered);
        restarted.revert(preview.identity(), mapping.itemName(), mapping.sourceChannelUid());
        Assertions.assertEquals(profile,
                Objects.requireNonNull(routing.get(original.getUID())).getConfiguration().getProperties());
        Assertions.assertEquals(3, routing.size());
        Assertions.assertFalse(routing.containsKey(secondOriginal.getUID()));
        Assertions.assertTrue(routing.values().stream().anyMatch(link -> link.getItemName().equals("Second")
                && link.getLinkedUID().toString().equals(secondMapping.targetChannelUid())));
        Assertions.assertSame(unrelated, routing.get(unrelated.getUID()));
        verify(provider, never()).remove(any());
        Assertions.assertEquals(MigrationValidation.Outcome.UNAVAILABLE,
                restarted.validate(preview.identity(), pollers.getFirst().uid()).outcome());
    }

    static java.util.stream.Stream<Map<String, Object>> recoverableProfiles() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(Map.of("profile", "system:default")),
                ModbusMigrationPlannerTest.boundedProfiles());
    }

    static Storage<String> storage(Map<String, String> values) {
        return new Storage<>() {
            public String put(String key, String value) {
                return values.put(key, value);
            }

            public String remove(String key) {
                return values.remove(key);
            }

            public boolean containsKey(String key) {
                return values.containsKey(key);
            }

            public String get(String key) {
                return values.get(key);
            }

            public Collection<String> getKeys() {
                return values.keySet();
            }

            public Collection<String> getValues() {
                return values.values();
            }
        };
    }

    static MigrationPreview preview() {
        return new ModbusMigrationPlanner().preview(
                List.of(new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                        Map.of("start", 100, "length", 2, "type", "holding"))),
                List.of(new LegacyData("modbus:data:tcp:plant:temperature", "modbus:poller:tcp:plant",
                        Map.of("readStart", "100", "readValueType", "int16"), List.of(new LegacyLink("Temperature",
                                "modbus:data:tcp:plant:temperature:number", "Number", Map.of())))));
    }
}
