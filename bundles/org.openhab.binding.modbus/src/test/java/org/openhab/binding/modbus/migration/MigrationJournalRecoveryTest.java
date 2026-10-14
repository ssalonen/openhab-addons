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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;

/** @author Sami Salonen - Initial contribution */
class MigrationJournalRecoveryTest {
    private final Map<String, String> persisted = new HashMap<>();
    private final ManagedThingProvider provider = mock(ManagedThingProvider.class);
    private final ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
    private final ProviderOwnedMigrationPreviewTest.OwnedThingRegistry things = mock(
            ProviderOwnedMigrationPreviewTest.OwnedThingRegistry.class);

    @org.junit.jupiter.api.BeforeEach
    void ownership() {
        when(things.get(any(ThingUID.class))).thenAnswer(call -> provider.get(call.getArgument(0)));
        when(things.getProvider(any(org.openhab.core.thing.Thing.class))).thenReturn(provider);
    }

    private final List<LegacyData> data = List.of(new LegacyData("modbus:data:tcp:plant:value",
            "modbus:poller:tcp:plant", Map.of("readStart", "100", "readValueType", "int16"), List.of()));

    private List<LegacyPoller> pollers(Object option) {
        return List.of(new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding", "refresh", option)));
    }

    private MigrationJournal journal() {
        return new MigrationJournal(ManagedModbusMigrationStagingTest.storage(persisted));
    }

    private ModbusMigrationWorkflow workflow(MigrationJournal journal) {
        return new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(provider, links, journal, things), journal);
    }

    @Test
    void reviewOmitsArbitraryConfigurationsAndRetainsHashedDriftAfterRestart() {
        var workflow = workflow(journal());
        var poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding"));
        var secretLink = new LegacyLink("Value", "modbus:data:tcp:plant:value:number", "Number",
                Map.of("profile", "custom:profile", "innocuous", "SENTINEL-profile"));
        var source = new LegacyData("modbus:data:tcp:plant:value", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16"), List.of(secretLink));
        var preview = workflow.preview(List.of(poller), List.of(source));
        assertFalse(preview.toString().contains("SENTINEL"));
        assertTrue(preview.impactReport().contains("omitted"));
        assertTrue(preview.groups().getFirst().links().isEmpty());
        assertEquals(Map.of("start", 100, "length", 2, "type", "holding"), preview.groups().getFirst().configuration());
        var unsafePoller = new LegacyPoller(poller.uid(), poller.bridgeUid(),
                Map.of("start", 100, "innocuous", "SENTINEL-poller"));
        var transformed = new LegacyData(source.uid(), poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16", "readTransform", "JS(SENTINEL-transform)"),
                List.of());
        var manual = workflow.preview(List.of(unsafePoller), List.of(transformed));
        assertTrue(manual.groups().isEmpty());
        assertFalse(manual.manualWork().isEmpty());
        assertFalse(persisted.toString().contains("SENTINEL"));
        var restarted = workflow(journal());
        assertEquals(new com.google.gson.Gson().toJsonTree(preview),
                new com.google.gson.Gson().toJsonTree(restarted.report(preview.identity()).preview()));
        restarted.apply(preview.identity(), List.of(poller), List.of(source));
        var changed = new LegacyData(source.uid(), poller.uid(), source.configuration(), List.of(new LegacyLink("Value",
                secretLink.channelUid(), "Number", Map.of("profile", "custom:profile", "innocuous", "changed"))));
        assertThrows(IllegalArgumentException.class,
                () -> restarted.apply(preview.identity(), List.of(poller), List.of(changed)));
        assertFalse(persisted.toString().contains("SENTINEL"));
    }

    @Test
    void manifestSourceSnapshotCannotBeChangedThroughCallerOwnedNestedLists() {
        List<String> option = new ArrayList<>(List.of("before"));
        ModbusMigrationWorkflow workflow = workflow(journal());
        var preview = workflow.preview(pollers(option), data);
        option.set(0, "after");
        workflow.apply(preview.identity(), pollers(List.of("before")), data);
        assertFalse(journal().workflows().getFirst().pollers().getFirst().configuration().containsKey("refresh"));
        assertEquals(pollers(List.of("before")).getFirst().configurationIdentity(),
                journal().workflows().getFirst().pollers().getFirst().configurationIdentity());
        assertFalse(persisted.toString().contains("before"));
    }

    @Test
    void interruptedStageReconstructsOwnershipBeforeAddOutcomeWasRecorded() {
        MigrationJournal journal = journal();
        ModbusMigrationWorkflow workflow = workflow(journal);
        var pollers = pollers(500);
        var preview = workflow.preview(pollers, data);
        ThingUID target = new ThingUID(preview.groups().getFirst().targetPollerUid());
        doAnswer(call -> {
            assertTrue(persisted.containsKey("v1:manifest:" + preview.identity()));
            assertEquals(List.of(target.toString()), journal().targets(preview.identity()));
            assertEquals("STAGING", journal().workflows().getFirst().outcome());
            var thing = call.getArgument(0, org.openhab.core.thing.Thing.class);
            when(provider.get(target)).thenReturn(thing);
            throw new AssertionError("simulated abrupt interruption");
        }).when(provider).add(any());
        assertThrows(AssertionError.class, () -> workflow.apply(preview.identity(), pollers, data));
        workflow(journal()).rollback(preview.identity());
        verify(provider).remove(target);
        assertTrue(journal().targets(preview.identity()).isEmpty());
        assertEquals("ROLLED_BACK", journal().workflows().getFirst().outcome());
    }

    @Test
    void quarantinedHistoricalStageRetainsRollbackOwnership() {
        MigrationJournal journal = journal();
        ModbusMigrationWorkflow workflow = workflow(journal);
        var pollers = pollers(500);
        var preview = workflow.preview(pollers, data);
        ThingUID target = new ThingUID(preview.groups().getFirst().targetPollerUid());
        doAnswer(call -> {
            var thing = call.getArgument(0, org.openhab.core.thing.Thing.class);
            when(provider.get(target)).thenReturn(thing);
            throw new AssertionError("simulated interruption");
        }).when(provider).add(any());
        assertThrows(AssertionError.class, () -> workflow.apply(preview.identity(), pollers, data));
        for (String prefix : List.of("v1:workflow:", "v1:review:")) {
            String key = prefix + preview.identity();
            var old = com.google.gson.JsonParser.parseString(java.util.Objects.requireNonNull(persisted.get(key)))
                    .getAsJsonObject();
            old.remove("reviewSafetyVersion");
            old.getAsJsonObject("preview").addProperty("yaml", "SENTINEL-old-review");
            persisted.put(key, old.toString());
        }
        var restored = workflow(journal());
        assertEquals(MigrationWorkflowReport.Status.QUARANTINED, restored.report(preview.identity()).status());
        assertFalse(restored.report(preview.identity()).toString().contains("SENTINEL"));
        restored.rollback(preview.identity());
        verify(provider).remove(target);
        assertTrue(journal().targets(preview.identity()).isEmpty());
        assertEquals("ROLLED_BACK", journal().workflows().getFirst().outcome());
        assertEquals(0, journal().workflows().getFirst().reviewSafetyVersion());
    }

    @Test
    void reconstructedRollbackRefusesReplacementThingWithUnknownOwnership() {
        var workflow = workflow(journal());
        var pollers = pollers(500);
        var preview = workflow.preview(pollers, data);
        workflow.apply(preview.identity(), pollers, data);
        ThingUID target = new ThingUID(preview.groups().getFirst().targetPollerUid());
        var replacement = BridgeBuilder
                .create(org.openhab.binding.modbus.internal.ModbusBindingConstantsInternal.THING_TYPE_MODBUS_POLLER2,
                        target)
                .build();
        when(provider.get(target)).thenReturn(replacement);
        assertThrows(IllegalStateException.class, () -> workflow(journal()).rollback(preview.identity()));
        verify(provider, never()).remove(target);
        assertEquals(List.of(target.toString()), journal().targets(preview.identity()));
    }

    @Test
    void stagingRefusesPreexistingProviderOwnedTargetOutsideManagedProvider() {
        ThingRegistry registry = mock(ThingRegistry.class);
        var preview = ManagedModbusMigrationStagingTest.preview();
        ThingUID target = new ThingUID(preview.groups().getFirst().targetPollerUid());
        when(registry.get(target)).thenReturn(BridgeBuilder
                .create(org.openhab.binding.modbus.internal.ModbusBindingConstantsInternal.THING_TYPE_MODBUS_POLLER2,
                        target)
                .build());
        var applier = new ManagedModbusMigrationApplier(provider, links, journal(), registry);
        assertThrows(IllegalStateException.class, () -> applier.createTargets(preview));
        verify(provider, never()).add(any());
        verify(provider, never()).remove(any());
    }

    @Test
    void restartRevertDoesNotAddAlreadyRestoredSourceAgain() {
        MigrationJournal journal = journal();
        var preview = ManagedModbusMigrationStagingTest.preview();
        var group = preview.groups().getFirst();
        var mapping = group.links().getFirst();
        var source = new org.openhab.core.thing.link.ItemChannelLink(mapping.itemName(),
                new org.openhab.core.thing.ChannelUID(mapping.sourceChannelUid()));
        when(links.get(source.getUID())).thenReturn(source);
        doThrow(new IllegalArgumentException("duplicate source")).when(links).add(source);
        // A prior revert restored the source, but stopped before recording completion.
        journal.links(preview.identity() + ":" + group.sourcePollerUid(), List.of(mapping));
        new ManagedModbusMigrationApplier(provider, links, journal()).revertLink(preview, mapping);
        verify(links, never()).add(source);
        assertTrue(journal().links(preview.identity() + ":" + group.sourcePollerUid()).isEmpty());
    }

    @Test
    void cutoverRefusesUnmanagedSourceLinksWithoutCreatingDualDelivery() {
        var preview = ManagedModbusMigrationStagingTest.preview();
        var group = preview.groups().getFirst();
        var mapping = group.links().getFirst();
        var source = new org.openhab.core.thing.link.ItemChannelLink(mapping.itemName(),
                new org.openhab.core.thing.ChannelUID(mapping.sourceChannelUid()));
        when(links.get(source.getUID())).thenReturn(source);
        assertThrows(IllegalStateException.class,
                () -> new ManagedModbusMigrationApplier(provider, links, journal()).cutover(preview, group));
        verify(links, never()).remove(anyString());
        verify(links, never()).add(any());
    }

    @Test
    void failedJournalWriteBlocksProviderMutation() {
        var storage = ManagedModbusMigrationStagingTest.storage(persisted);
        var failing = spy(storage);
        MigrationJournal journal = new MigrationJournal(failing);
        var workflow = workflow(journal);
        var pollers = pollers(500);
        var preview = workflow.preview(pollers, data);
        doThrow(new IllegalStateException("storage unavailable")).when(failing).put(anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> workflow.apply(preview.identity(), pollers, data));
        verifyNoInteractions(provider, links);
    }
}
