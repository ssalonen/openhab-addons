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
    private final List<LegacyData> data = List.of(new LegacyData("modbus:data:tcp:plant:value",
            "modbus:poller:tcp:plant", Map.of("readStart", "100", "readValueType", "int16"), List.of()));

    private List<LegacyPoller> pollers(Object option) {
        return List.of(new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding", "option", option)));
    }

    private MigrationJournal journal() {
        return new MigrationJournal(ManagedModbusMigrationStagingTest.storage(persisted));
    }

    private ModbusMigrationWorkflow workflow(MigrationJournal journal) {
        return new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(provider, links, journal), journal);
    }

    @Test
    void manifestSourceSnapshotCannotBeChangedThroughCallerOwnedNestedLists() {
        List<String> option = new ArrayList<>(List.of("before"));
        ModbusMigrationWorkflow workflow = workflow(journal());
        var preview = workflow.preview(pollers(option), data);
        option.set(0, "after");
        workflow.apply(preview.identity(), pollers(List.of("before")), data);
        assertEquals(List.of("before"),
                journal().workflows().getFirst().pollers().getFirst().configuration().get("option"));
    }

    @Test
    void interruptedStageReconstructsOwnershipBeforeAddOutcomeWasRecorded() {
        MigrationJournal journal = journal();
        ModbusMigrationWorkflow workflow = workflow(journal);
        var pollers = pollers("unchanged");
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
    void reconstructedRollbackRefusesReplacementThingWithUnknownOwnership() {
        var workflow = workflow(journal());
        var pollers = pollers("unchanged");
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
        var pollers = pollers("unchanged");
        var preview = workflow.preview(pollers, data);
        doThrow(new IllegalStateException("storage unavailable")).when(failing).put(anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> workflow.apply(preview.identity(), pollers, data));
        verifyNoInteractions(provider, links);
    }
}
