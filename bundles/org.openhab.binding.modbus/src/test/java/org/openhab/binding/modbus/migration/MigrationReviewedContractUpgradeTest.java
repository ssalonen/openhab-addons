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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.link.ManagedItemChannelLinkProvider;

import com.google.gson.JsonParser;

/** @author Sami Salonen - Reviewed contract upgrade and recovery regressions */
class MigrationReviewedContractUpgradeTest {
    private final Map<String, String> persisted = new HashMap<>();

    private MigrationJournal journal() {
        return new MigrationJournal(ManagedModbusMigrationStagingTest.storage(persisted));
    }

    private MigrationJournal.Workflow historical(String outcome) throws IOException {
        String serialized;
        try (var stream = Objects
                .requireNonNull(getClass().getResourceAsStream("/migration/old-v4-staged-review.json"))) {
            serialized = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var json = JsonParser.parseString(serialized).getAsJsonObject();
        json.addProperty("outcome", outcome);
        String identity = json.getAsJsonObject("preview").get("identity").getAsString();
        persisted.put("v1:review:" + identity, json.toString());
        persisted.put("v1:workflow:" + identity, json.toString());
        return journal().workflows().getFirst();
    }

    private ModbusMigrationWorkflow workflow(MigrationApplier applier, MigrationValidation validation) {
        return new ModbusMigrationWorkflow(new ModbusMigrationPlanner(), applier, validation, journal());
    }

    @Test
    void preContractStagedReviewCannotValidateEvenWithFreshMatch() throws IOException {
        var old = historical("STAGED");
        assertEquals(1, old.reviewSafetyVersion());
        assertEquals(Map.of("address", "100", "valueType", "int16"),
                old.preview().groups().getFirst().channels().getFirst().configuration());
        var validation = mock(MigrationValidation.class);
        when(validation.observe(any(), any(), any()))
                .thenAnswer(call -> new MigrationValidation.Observation(old.preview().identity(),
                        old.pollers().getFirst().uid(), Instant.now(), MigrationValidation.Outcome.MATCH));
        var restored = workflow(mock(MigrationApplier.class), validation);

        assertThrows(IllegalStateException.class,
                () -> restored.validate(old.preview().identity(), old.pollers().getFirst().uid()));
        verifyNoInteractions(validation);
    }

    @Test
    void preContractCutoverCannotBeAuthorizedByReplanningBothSourceSnapshots() throws IOException {
        var old = historical("STAGED");
        var applier = mock(MigrationApplier.class);
        var validation = mock(MigrationValidation.class);
        when(validation.observe(any(), any(), any()))
                .thenAnswer(call -> new MigrationValidation.Observation(old.preview().identity(),
                        old.pollers().getFirst().uid(), Instant.now(), MigrationValidation.Outcome.MATCH));
        var restored = workflow(applier, validation);
        Map<String, String> before = Map.copyOf(persisted);

        assertThrows(IllegalStateException.class, () -> restored.cutover(old.preview().identity(),
                old.pollers().getFirst().uid(), old.pollers(), old.data()));
        verifyNoInteractions(validation, applier);
        assertEquals(before, persisted);
    }

    @Test
    void preContractReviewCannotBeAppliedAgainOrRewrittenByPreview() throws IOException {
        var old = historical("STAGED");
        var applier = mock(MigrationApplier.class);
        var validation = mock(MigrationValidation.class);
        var restored = workflow(applier, validation);
        Map<String, String> before = Map.copyOf(persisted);

        assertThrows(IllegalStateException.class,
                () -> restored.apply(old.preview().identity(), old.pollers(), old.data()));
        verifyNoInteractions(applier, validation);
        assertEquals(before, persisted);
        var current = restored.preview(old.pollers(), old.data());
        assertNotEquals(old.preview().identity(), current.identity());
        before.forEach((key, value) -> assertEquals(value, persisted.get(key)));
        assertEquals(old.preview(),
                journal().workflows().stream()
                        .filter(entry -> entry.preview().identity().equals(old.preview().identity())).findFirst()
                        .orElseThrow().preview());
    }

    @ParameterizedTest
    @ValueSource(ints = { -1, 3, Integer.MAX_VALUE })
    void unknownReviewVersionsCannotAuthorizeMutationsOrValidation(int version) throws IOException {
        var old = historical("STAGED");
        String identity = old.preview().identity();
        for (String prefix : List.of("v1:workflow:", "v1:review:")) {
            var json = JsonParser.parseString(Objects.requireNonNull(persisted.get(prefix + identity)))
                    .getAsJsonObject();
            json.addProperty("reviewSafetyVersion", version);
            persisted.put(prefix + identity, json.toString());
        }
        var applier = mock(MigrationApplier.class);
        var validation = mock(MigrationValidation.class);
        var restored = workflow(applier, validation);
        Map<String, String> before = Map.copyOf(persisted);
        assertEquals(MigrationWorkflowReport.Status.QUARANTINED, restored.report(identity).status());
        assertThrows(IllegalStateException.class, () -> restored.apply(identity, old.pollers(), old.data()));
        assertThrows(IllegalStateException.class, () -> restored.validate(identity, old.pollers().getFirst().uid()));
        assertThrows(IllegalStateException.class,
                () -> restored.cutover(identity, old.pollers().getFirst().uid(), old.pollers(), old.data()));
        assertThrows(IllegalStateException.class, () -> restored.rollback(identity));
        var link = old.preview().groups().getFirst().links().getFirst();
        assertThrows(IllegalStateException.class,
                () -> restored.revert(identity, link.itemName(), link.sourceChannelUid()));
        verifyNoInteractions(applier, validation);
        assertEquals(before, persisted);
    }

    @ParameterizedTest
    @ValueSource(strings = { "STAGED", "RECOVERY" })
    void missingImmutableReviewCannotUpgradeHistoricalContractDuringRevert(String outcome) throws IOException {
        var old = historical(outcome);
        String identity = old.preview().identity();
        var group = old.preview().groups().getFirst();
        var mapping = group.links().getFirst();
        persisted.remove("v1:review:" + identity);
        var recoveryApplier = mock(MigrationApplier.class);
        var restored = workflow(recoveryApplier, mock(MigrationValidation.class));
        assertEquals(MigrationWorkflowReport.Status.QUARANTINED, restored.report(identity).status());

        restored.revert(identity, mapping.itemName(), mapping.sourceChannelUid());

        verify(recoveryApplier).revertLink(old.preview(), mapping);
        assertEquals(1, journal().workflows().getFirst().reviewSafetyVersion(),
                "Recovery must preserve the persisted old contract even when its immutable review key is missing");
        var nextApplier = mock(MigrationApplier.class);
        var nextValidation = mock(MigrationValidation.class);
        when(nextValidation.observe(any(), any(), any()))
                .thenAnswer(call -> new MigrationValidation.Observation(identity, group.sourcePollerUid(),
                        Instant.now(), MigrationValidation.Outcome.MATCH));
        var reconstructed = workflow(nextApplier, nextValidation);
        assertEquals(MigrationWorkflowReport.Status.QUARANTINED, reconstructed.report(identity).status());
        assertThrows(IllegalStateException.class, () -> reconstructed.validate(identity, group.sourcePollerUid()));
        assertThrows(IllegalStateException.class,
                () -> reconstructed.cutover(identity, group.sourcePollerUid(), old.pollers(), old.data()));
        verifyNoInteractions(nextApplier, nextValidation);
    }

    @Test
    void currentContractStagedReviewRemainsActionableAfterRestart() throws IOException {
        var old = historical("STAGED");
        var applier = mock(MigrationApplier.class);
        var validation = mock(MigrationValidation.class);
        var workflow = workflow(applier, validation);
        var current = workflow.preview(old.pollers(), old.data());
        assertEquals("keepLast", current.groups().getFirst().channels().getFirst().configuration().get("errorPolicy"));
        workflow.apply(current.identity(), old.pollers(), old.data());
        when(validation.observe(any(), any(), any()))
                .thenAnswer(call -> new MigrationValidation.Observation(current.identity(),
                        old.pollers().getFirst().uid(), Instant.now(), MigrationValidation.Outcome.MATCH));
        var restarted = workflow(applier, validation);
        assertEquals(current, restarted.report(current.identity()).preview());
        assertEquals(MigrationWorkflowReport.Status.STAGED, restarted.report(current.identity()).status());
        assertEquals(MigrationValidation.Outcome.MATCH,
                restarted.validate(current.identity(), old.pollers().getFirst().uid()).outcome());
        restarted.cutover(current.identity(), old.pollers().getFirst().uid(), old.pollers(), old.data());
        verify(applier).cutover(eq(current), eq(current.groups().getFirst()), any(), any());
    }

    @Test
    void corruptJournalCannotConstructActionableWorkflow() throws IOException {
        var old = historical("STAGED");
        persisted.put("v1:workflow:" + old.preview().identity(), "{broken");
        var applier = mock(MigrationApplier.class);
        var validation = mock(MigrationValidation.class);
        assertThrows(com.google.gson.JsonParseException.class, () -> workflow(applier, validation));
        verifyNoInteractions(applier, validation);
    }

    @ParameterizedTest
    @ValueSource(strings = { "STAGED", "RECOVERY" })
    void preContractReviewRetainsExactMarkedTargetRollback(String outcome) throws IOException {
        var old = historical(outcome);
        var provider = mock(ManagedThingProvider.class);
        var links = mock(ItemChannelLinkRegistry.class);
        var things = ManagedModbusMigrationStagingTest.managedRegistry(provider);
        var applier = new ManagedModbusMigrationApplier(provider, links, journal(), things);
        applier.createTargets(old.preview());
        var targetUid = new ThingUID(old.preview().groups().getFirst().targetPollerUid());
        assertTrue(ManagedModbusMigrationAdapter.ownsTarget(things, provider, targetUid, old.preview().identity()));
        when(provider.remove(targetUid)).thenAnswer(call -> {
            when(provider.get(targetUid)).thenReturn(null);
            return null;
        });
        var restartedJournal = journal();
        var restored = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(provider, links, restartedJournal, things), restartedJournal);
        assertEquals(MigrationWorkflowReport.Status.QUARANTINED, restored.report(old.preview().identity()).status());

        restored.rollback(old.preview().identity());

        verify(provider).remove(targetUid);
        assertTrue(journal().targets(old.preview().identity()).isEmpty());
        assertEquals("ROLLED_BACK", journal().workflows().getFirst().outcome());
        assertEquals(old.preview(), journal().workflows().getFirst().preview());
        assertEquals(1, journal().workflows().getFirst().reviewSafetyVersion());
        verifyNoInteractions(links);
    }

    @ParameterizedTest
    @ValueSource(strings = { "STAGED", "RECOVERY" })
    void preContractSwitchedReviewRetainsExactSourceProfileAndMarkerForRevert(String outcome) throws IOException {
        var old = historical(outcome);
        var group = old.preview().groups().getFirst();
        var mapping = group.links().getFirst();
        String identity = old.preview().identity();
        var record = JsonParser.parseString(Objects.requireNonNull(persisted.get("v1:workflow:" + identity)))
                .getAsJsonObject();
        record.getAsJsonArray("switched")
                .add(identity + ":" + mapping.itemName() + " -> " + mapping.sourceChannelUid());
        persisted.put("v1:workflow:" + identity, record.toString());
        journal().manifest(old.preview());
        journal().intents(identity + ":" + group.sourcePollerUid(),
                List.of(new MigrationJournal.LinkIntent(mapping, MigrationJournal.LinkPhase.ADD_CONFIRMED)));
        var source = new ItemChannelLink(mapping.itemName(), new ChannelUID(mapping.sourceChannelUid()),
                new Configuration(mapping.configuration()));
        var target = new ItemChannelLink(mapping.itemName(), new ChannelUID(mapping.targetChannelUid()),
                new Configuration(mapping.targetConfiguration()));
        Map<String, ItemChannelLink> routing = new HashMap<>();
        routing.put(target.getUID(), target);
        var links = mock(ItemChannelLinkRegistry.class);
        var linkProvider = mock(ManagedItemChannelLinkProvider.class);
        when(links.get(anyString())).thenAnswer(call -> routing.get(call.getArgument(0)));
        when(links.getProvider(any(ItemChannelLink.class))).thenReturn(linkProvider);
        when(links.remove(anyString())).thenAnswer(call -> routing.remove(call.getArgument(0)));
        doAnswer(call -> {
            var link = call.getArgument(0, ItemChannelLink.class);
            routing.put(link.getUID(), link);
            return null;
        }).when(links).add(any());
        var provider = mock(ManagedThingProvider.class);
        var things = ManagedModbusMigrationStagingTest.managedRegistry(provider);
        new ManagedModbusMigrationApplier(provider, links, journal(), things, linkProvider)
                .createTargets(old.preview());
        var restartedJournal = journal();
        var restored = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(provider, links, restartedJournal, things, linkProvider),
                restartedJournal);
        assertEquals(MigrationWorkflowReport.Status.QUARANTINED, restored.report(identity).status());

        restored.revert(identity, mapping.itemName(), mapping.sourceChannelUid());

        assertNull(routing.get(target.getUID()));
        assertEquals(source, routing.get(source.getUID()));
        assertEquals(mapping.configuration(), routing.get(source.getUID()).getConfiguration().getProperties());
        assertTrue(journal().intents(identity + ":" + group.sourcePollerUid()).isEmpty());
        assertEquals(old.preview(), journal().workflows().getFirst().preview());
        assertEquals(1, journal().workflows().getFirst().reviewSafetyVersion());

        var nextApplier = mock(MigrationApplier.class);
        var nextValidation = mock(MigrationValidation.class);
        when(nextValidation.observe(any(), any(), any()))
                .thenAnswer(call -> new MigrationValidation.Observation(identity, group.sourcePollerUid(),
                        Instant.now(), MigrationValidation.Outcome.MATCH));
        var reconstructed = workflow(nextApplier, nextValidation);
        Map<String, String> beforeRefusal = Map.copyOf(persisted);
        assertEquals(MigrationWorkflowReport.Status.QUARANTINED, reconstructed.report(identity).status());
        assertThrows(IllegalStateException.class, () -> reconstructed.validate(identity, group.sourcePollerUid()));
        assertThrows(IllegalStateException.class,
                () -> reconstructed.cutover(identity, group.sourcePollerUid(), old.pollers(), old.data()));
        verifyNoInteractions(nextApplier, nextValidation);
        assertEquals(beforeRefusal, persisted);
    }
}
