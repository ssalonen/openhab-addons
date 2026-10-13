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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;

/** @author Sami Salonen - Unlinked staging coverage */
class ManagedModbusMigrationStagingTest {
    @Test
    void stagingNeverCreatesProductionLinks() {
        ManagedThingProvider provider = mock(ManagedThingProvider.class);
        ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
        new ManagedModbusMigrationApplier(provider, links).createTargets(preview());
        verify(provider).add(any());
        verifyNoInteractions(links);
    }

    @Test
    void failedStageCompensationRetainsRetryableOwnedTargets() {
        ManagedThingProvider provider = mock(ManagedThingProvider.class);
        ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
        List<LegacyPoller> pollers = List.of(
                new LegacyPoller("modbus:poller:tcp:a", "modbus:tcp:plant",
                        Map.of("start", 100, "length", 2, "type", "holding")),
                new LegacyPoller("modbus:poller:tcp:b", "modbus:tcp:plant",
                        Map.of("start", 100, "length", 2, "type", "holding")));
        org.openhab.core.thing.ThingUID first = new org.openhab.core.thing.ThingUID("modbus:poller2:tcp:a");
        doNothing().doThrow(new IllegalStateException("create failed")).when(provider).add(any());
        when(provider.remove(first)).thenThrow(new IllegalStateException("compensation failed")).thenReturn(null);
        ModbusMigrationWorkflow workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(provider, links));
        List<LegacyData> data = List.of(
                new LegacyData("modbus:data:tcp:a:value", "modbus:poller:tcp:a",
                        Map.of("readStart", "100", "readValueType", "int16"), List.of()),
                new LegacyData("modbus:data:tcp:b:value", "modbus:poller:tcp:b",
                        Map.of("readStart", "100", "readValueType", "int16"), List.of()));
        MigrationPreview preview = workflow.preview(pollers, data);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> workflow.apply(preview.identity(), pollers, data));
        workflow.rollback(preview.identity());
        verify(provider, times(2)).remove(first);
        verify(provider).remove(new org.openhab.core.thing.ThingUID("modbus:poller2:tcp:b"));
        verifyNoInteractions(links);
    }

    @Test
    void explicitCutoverReplacesLegacyLinkInsteadOfDualLinking() {
        ManagedThingProvider provider = mock(ManagedThingProvider.class);
        ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
        MigrationPreview preview = preview();
        MigrationGroup group = preview.groups().getFirst();
        MigrationLink mapping = group.links().getFirst();
        org.openhab.core.thing.link.ItemChannelLink legacy = new org.openhab.core.thing.link.ItemChannelLink(
                mapping.itemName(), new org.openhab.core.thing.ChannelUID(mapping.sourceChannelUid()));
        when(links.get(legacy.getUID())).thenReturn(legacy);
        ModbusMigrationWorkflow workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(provider, links),
                (reviewed, candidate, stagedAt) -> new MigrationValidation.Observation(reviewed.identity(),
                        candidate.sourcePollerUid(), java.time.Instant.now(), MigrationValidation.Outcome.MATCH));
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
        order.verify(links).get(new org.openhab.core.thing.link.ItemChannelLink(mapping.itemName(),
                new org.openhab.core.thing.ChannelUID(mapping.targetChannelUid())).getUID());
        order.verify(links).remove(legacy.getUID());
        order.verify(links).add(argThat(link -> link.getLinkedUID().toString().equals(mapping.targetChannelUid())));
    }

    @Test
    void cutoverCompensationFailureCanBeRetriedByRollback() {
        ManagedThingProvider provider = mock(ManagedThingProvider.class);
        ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
        ManagedModbusMigrationApplier applier = new ManagedModbusMigrationApplier(provider, links);
        MigrationPreview preview = preview();
        MigrationGroup group = preview.groups().getFirst();
        MigrationLink mapping = group.links().getFirst();
        var source = new org.openhab.core.thing.link.ItemChannelLink(mapping.itemName(),
                new org.openhab.core.thing.ChannelUID(mapping.sourceChannelUid()));
        var target = new org.openhab.core.thing.link.ItemChannelLink(mapping.itemName(),
                new org.openhab.core.thing.ChannelUID(mapping.targetChannelUid()));
        when(links.get(source.getUID())).thenReturn(source);
        doThrow(new IllegalStateException("link addition failed")).when(links).add(target);
        when(links.remove(target.getUID())).thenThrow(new IllegalStateException("compensation failed"))
                .thenReturn(null);
        var pollers = List.of(new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding")));
        var data = List.of(new LegacyData("modbus:data:tcp:plant:temperature", "modbus:poller:tcp:plant",
                Map.of("readStart", "100", "readValueType", "int16"), List.of(new LegacyLink("Temperature",
                        "modbus:data:tcp:plant:temperature:number", "Number", Map.of()))));
        ModbusMigrationWorkflow workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(), applier,
                (reviewed, candidate, stagedAt) -> new MigrationValidation.Observation(reviewed.identity(),
                        candidate.sourcePollerUid(), java.time.Instant.now(), MigrationValidation.Outcome.MATCH));
        var reviewed = workflow.preview(pollers, data);
        workflow.apply(reviewed.identity(), pollers, data);
        var failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> workflow.cutover(reviewed.identity(), group.sourcePollerUid(), pollers, data));
        org.junit.jupiter.api.Assertions.assertEquals(1, failure.getSuppressed().length);
        verify(links, never()).add(source);
        workflow.rollback(reviewed.identity());
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
        org.junit.jupiter.api.Assertions.assertEquals(MigrationValidation.Outcome.UNAVAILABLE,
                workflow.validate(preview.identity(), "modbus:poller:tcp:plant").outcome());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> workflow.cutover(preview.identity(), "modbus:poller:tcp:plant", pollers, data));
        verify(applier, never()).cutover(any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "stale", "wrong-preview", "wrong-group", "future",
            "mismatch" })
    void invalidObservationCannotAuthorizeCutover(String invalid) {
        MigrationApplier applier = mock(MigrationApplier.class);
        ModbusMigrationWorkflow workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(), applier,
                (preview, group, createdAt) -> new MigrationValidation.Observation(
                        invalid.equals("wrong-preview") ? "wrong" : preview.identity(),
                        invalid.equals("wrong-group") ? "wrong" : group.sourcePollerUid(),
                        invalid.equals("stale") ? createdAt
                                : invalid.equals("future") ? java.time.Instant.MAX : java.time.Instant.now(),
                        invalid.equals("mismatch") ? MigrationValidation.Outcome.MISMATCH
                                : MigrationValidation.Outcome.MATCH));
        var pollers = List.of(new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding")));
        var data = List.of(new LegacyData("modbus:data:tcp:plant:temperature", "modbus:poller:tcp:plant",
                Map.of("readStart", "100", "readValueType", "int16"), List.of()));
        var preview = workflow.preview(pollers, data);
        workflow.apply(preview.identity(), pollers, data);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> workflow.cutover(preview.identity(), "modbus:poller:tcp:plant", pollers, data));
        verify(applier, never()).cutover(any(), any());
    }

    @Test
    void secondPollerCutoverAcceptsOnlyTheExpectedFirstPollerLinkRemoval() {
        MigrationApplier applier = mock(MigrationApplier.class);
        ModbusMigrationWorkflow workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(), applier,
                (preview, group, createdAt) -> new MigrationValidation.Observation(preview.identity(),
                        group.sourcePollerUid(), java.time.Instant.now(), MigrationValidation.Outcome.MATCH));
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
        verify(applier, times(2)).cutover(eq(reviewed), any());
    }

    @Test
    void providerThrowAfterAddStillRetainsAttemptedCandidateForCompensation() {
        ManagedThingProvider provider = mock(ManagedThingProvider.class);
        ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
        MigrationPreview preview = preview();
        var target = new org.openhab.core.thing.ThingUID(preview.groups().getFirst().targetPollerUid());
        doThrow(new IllegalStateException("listener failed after add")).when(provider).add(any());
        when(provider.remove(target)).thenThrow(new IllegalStateException("compensation failed")).thenReturn(null);
        ManagedModbusMigrationApplier applier = new ManagedModbusMigrationApplier(provider, links);
        org.junit.jupiter.api.Assertions.assertThrows(ManagedModbusMigrationApplier.IncompleteStageException.class,
                () -> applier.createTargets(preview));
        applier.removeTargets(preview);
        verify(provider, times(2)).remove(target);
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
