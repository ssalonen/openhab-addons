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
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openhab.core.common.registry.AbstractRegistry;
import org.openhab.core.common.registry.RegistryChangeListener;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.items.ItemBuilderFactory;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.storage.Storage;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingProvider;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkProvider;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.link.ManagedItemChannelLinkProvider;
import org.osgi.framework.BundleContext;

/** @author Sami Salonen - Cutover link callback regression coverage */
@NonNullByDefault
class ManagedModbusMigrationCutoverRecheckTest {
    private final MigrationPreview preview = new ModbusMigrationPlanner().preview(
            List.of(new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                    Map.of("start", 100, "length", 2, "type", "holding"))),
            List.of(new LegacyData("modbus:data:tcp:plant:temperature", "modbus:poller:tcp:plant",
                    Map.of("readStart", "100", "readValueType", "int16"),
                    List.of(new LegacyLink("First", "modbus:data:tcp:plant:temperature:number", "Number"),
                            new LegacyLink("Second", "modbus:data:tcp:plant:temperature:number", "Number")))));
    private final MigrationGroup group = preview.groups().getFirst();
    private final MigrationLink first = group.links().getFirst();
    private final MigrationLink second = group.links().get(1);
    private final String key = preview.identity() + ":" + group.sourcePollerUid();
    private final Map<String, String> persisted = new HashMap<>();
    private final Storage<String> storage = spy(ManagedModbusMigrationStagingTest.storage(persisted));
    private final List<List<MigrationLink>> linkIntents = new ArrayList<>();
    private final Map<String, ItemChannelLink> managedLinks = new HashMap<>();
    private final Map<ThingUID, Thing> managedThings = new HashMap<>();
    private final ManagedThingProvider thingProvider = mock(ManagedThingProvider.class);
    private final ManagedItemChannelLinkProvider linkProvider = mock(ManagedItemChannelLinkProvider.class);
    private final ItemChannelLinkProvider foreignProvider = mock(ItemChannelLinkProvider.class);
    private final TestThingRegistry things = new TestThingRegistry();
    private final TestLinkRegistry links = new TestLinkRegistry(things);
    private final AtomicBoolean removalNoOp = new AtomicBoolean();

    @BeforeEach
    void providers() {
        when(thingProvider.getAll()).thenAnswer(call -> List.copyOf(managedThings.values()));
        when(thingProvider.get(any(ThingUID.class))).thenAnswer(call -> managedThings.get(call.getArgument(0)));
        doAnswer(call -> {
            Thing thing = call.getArgument(0);
            managedThings.put(thing.getUID(), thing);
            things.added(thingProvider, thing);
            return null;
        }).when(thingProvider).add(any());
        when(thingProvider.remove(any(ThingUID.class))).thenAnswer(call -> {
            Thing removed = managedThings.remove(call.getArgument(0));
            if (removed != null) {
                things.removed(thingProvider, removed);
            }
            return removed;
        });
        things.attach(thingProvider);
        when(linkProvider.getAll()).thenAnswer(call -> List.copyOf(managedLinks.values()));
        when(linkProvider.get(anyString())).thenAnswer(call -> managedLinks.get(call.getArgument(0)));
        doAnswer(call -> {
            ItemChannelLink added = call.getArgument(0);
            if (managedLinks.putIfAbsent(added.getUID(), added) != null) {
                throw new IllegalArgumentException("duplicate managed link");
            }
            links.added(linkProvider, added);
            return null;
        }).when(linkProvider).add(any());
        when(linkProvider.remove(anyString())).thenAnswer(call -> {
            if (removalNoOp.get() && call.getArgument(0).equals(source(second).getUID())) {
                return null;
            }
            ItemChannelLink removed = managedLinks.remove(call.getArgument(0));
            if (removed != null) {
                // Deliver the public provider notification before remove returns, as Core providers do.
                links.removed(linkProvider, removed);
            }
            return removed;
        });
        when(foreignProvider.getAll()).thenReturn(List.of());
        links.attach(linkProvider, foreignProvider);
        applier(journal()).createTargets(preview);
        group.links().forEach(mapping -> links.add(source(mapping)));
        clearInvocations(linkProvider);
    }

    @BeforeEach
    void recordWriteAheadIntents() {
        doAnswer(call -> {
            String storageKey = call.getArgument(0);
            String value = call.getArgument(1);
            String previous = persisted.put(storageKey, value);
            if (storageKey.equals("v2:links:" + key)) {
                linkIntents.add(journal().links(key));
            }
            return previous;
        }).when(storage).put(anyString(), anyString());
        assertEquals(2, group.links().size());
        assertEquals("First", first.itemName());
        assertEquals("Second", second.itemName());
        assertTrue(ManagedModbusMigrationAdapter.ownsTarget(things, thingProvider,
                new ThingUID(group.targetPollerUid()), preview.identity()));
        assertEquals(List.of(group.targetPollerUid()), journal().targets(preview.identity()));
    }

    @Test
    void addedTargetCarriesReviewedPerLinkMarkerAndReconstructsExactRevert() {
        applier().cutover(preview, group);
        ItemChannelLink added = java.util.Objects.requireNonNull(links.get(target(first).getUID()));
        Object marker = added.getConfiguration().get("modbusMigrationOwner");
        assertInstanceOf(String.class, marker);
        assertFalse(((String) marker).isBlank());
        ItemChannelLink other = java.util.Objects.requireNonNull(links.get(target(second).getUID()));
        assertNotEquals(marker, other.getConfiguration().get("modbusMigrationOwner"));
        assertEquals("system:default", added.getConfiguration().get("profile"));
        assertTrue(preview.manifestJson().contains(marker.toString()));
        assertTrue(persisted.get("v1:manifest:" + preview.identity()).contains(marker.toString()));
        assertTrue(persisted.get("v2:links:" + key).contains(marker.toString()));
        // Ordinary UI save/restart preserves the claim, not process-local Java identity.
        replaceManaged(added, link(first, first.targetChannelUid(), added.getConfiguration().getProperties()));
        applier().revertLink(preview, first);
        assertFirstRestored();
        assertEquals(Map.of(), links.get(source(first).getUID()).getConfiguration().getProperties());
        assertEquals(List.of(second), journal().links(key));
    }

    @Test
    void reconstructedRevertRefusesChangedCandidateOwnership() {
        applier().cutover(preview, group);
        ItemChannelLink owned = java.util.Objects.requireNonNull(links.get(target(first).getUID()));
        Thing candidate = java.util.Objects.requireNonNull(managedThings.get(new ThingUID(group.targetPollerUid())));
        candidate.setProperty("modbusMigrationIdentity", "another-review");
        clearInvocations(linkProvider);

        assertThrows(IllegalStateException.class, () -> applier().revertLink(preview, first));
        assertSame(owned, links.get(owned.getUID()));
        assertNull(links.get(source(first).getUID()));
        verify(linkProvider, never()).remove(anyString());
        verify(linkProvider, never()).add(any());
        assertEquals(group.links(), journal().links(key));
    }

    @ParameterizedTest
    @ValueSource(strings = { "SOURCE_REMOVED", "ADD_ATTEMPTED" })
    void phaseWriteCreatingIdenticallyMarkedTargetBeforeAddMustNotClaimIt(String phase) {
        ItemChannelLink foreign = link(first, first.targetChannelUid(), first.targetConfiguration());
        AtomicBoolean once = new AtomicBoolean();
        doAnswer(call -> {
            String storageKey = call.getArgument(0);
            String value = call.getArgument(1);
            String previous = persisted.put(storageKey, value);
            if (storageKey.equals("v2:links:" + key)) {
                linkIntents.add(journal().links(key));
                if (value.contains("\"" + phase + "\"") && once.compareAndSet(false, true)) {
                    links.add(foreign);
                }
            }
            return previous;
        }).when(storage).put(anyString(), anyString());

        assertThrows(IllegalStateException.class, () -> applier().cutover(preview, group));
        assertTrue(once.get());
        assertSame(foreign, links.get(foreign.getUID()));
        assertNull(links.get(source(first).getUID()));
        verify(linkProvider, never()).remove(foreign.getUID());
        assertEquals(List.of(first), journal().links(key));
    }

    @Test
    void reconstructedRevertRefusesAnotherManagedProviderBeforeRemoval() {
        applier().cutover(preview, group);
        ItemChannelLink owned = java.util.Objects.requireNonNull(links.get(target(first).getUID()));
        ItemChannelLink replacement = link(first, first.targetChannelUid(), first.targetConfiguration());
        ManagedItemChannelLinkProvider another = mock(ManagedItemChannelLinkProvider.class);
        when(another.getAll()).thenReturn(List.of());
        links.attachForeign(another);
        discardManaged(owned);
        links.added(another, replacement);
        clearInvocations(linkProvider);

        assertThrows(IllegalStateException.class, () -> applier().revertLink(preview, first));
        assertSame(replacement, links.get(replacement.getUID()));
        assertNull(links.get(source(first).getUID()));
        verify(linkProvider, never()).remove(anyString());
        verify(linkProvider, never()).add(any());
        assertEquals(group.links(), journal().links(key));
    }

    @ParameterizedTest
    @ValueSource(strings = { "profile", "missing-marker", "wrong-marker", "extra-setting", "unmanaged" })
    void reconstructedRevertRefusesChangedMarkedTarget(String change) {
        applier().cutover(preview, group);
        ItemChannelLink owned = java.util.Objects.requireNonNull(links.get(target(first).getUID()));
        Map<String, Object> configuration = new HashMap<>(owned.getConfiguration().getProperties());
        switch (change) {
            case "profile" -> configuration.put("profile", "system:follow");
            case "missing-marker" -> configuration.remove("modbusMigrationOwner");
            case "wrong-marker" -> configuration.put("modbusMigrationOwner", "foreign");
            case "extra-setting" -> configuration.put("extra", true);
            default -> {
            }
        }
        ItemChannelLink replacement = link(first, first.targetChannelUid(), configuration);
        if (change.equals("unmanaged")) {
            replaceForeign(owned, replacement);
        } else {
            replaceManaged(owned, replacement);
        }
        clearInvocations(linkProvider);
        assertThrows(IllegalStateException.class, () -> applier().revertLink(preview, first));
        assertSame(replacement, links.get(replacement.getUID()));
        assertNull(links.get(source(first).getUID()));
        verify(linkProvider, never()).remove(anyString());
        verify(linkProvider, never()).add(any());
        assertEquals(group.links(), journal().links(key));
    }

    @ParameterizedTest
    @ValueSource(strings = { "historical", "unknown-version", "unknown-phase", "missing-phase" })
    void ambiguousRecoveryRecordWithoutTargetMustNotCreateSource(String kind) {
        links.remove(source(first).getUID());
        if (kind.equals("historical")) {
            journal().links(key, List.of(new MigrationLink(first.itemName(), first.sourceChannelUid(),
                    first.targetChannelUid(), first.configuration())));
        } else {
            journal().intents(key,
                    List.of(new MigrationJournal.LinkIntent(first, MigrationJournal.LinkPhase.ADD_ATTEMPTED)));
            String value = java.util.Objects.requireNonNull(persisted.get("v2:links:" + key));
            value = switch (kind) {
                case "unknown-version" -> value.replace("\"version\":1", "\"version\":99");
                case "unknown-phase" -> value.replace("ADD_ATTEMPTED", "UNRECOGNIZED");
                default -> value.replace(",\"phase\":\"ADD_ATTEMPTED\"", "");
            };
            persisted.put("v2:links:" + key, value);
        }
        Map<String, String> before = Map.copyOf(persisted);
        clearInvocations(linkProvider);
        assertThrows(IllegalStateException.class, () -> applier().removeTargets(preview));
        verify(linkProvider, never()).remove(anyString());
        verify(linkProvider, never()).add(any());
        assertEquals(before, persisted);
        assertNull(links.get(source(first).getUID()));
    }

    @Test
    void recoveryIntentCannotReplaceTheImmutableReviewedTargetClaim() {
        applier().cutover(preview, group);
        Map<String, Object> forgedConfiguration = Map.of("profile", "system:default", "modbusMigrationOwner", "copied");
        MigrationLink forged = new MigrationLink(first.itemName(), first.sourceChannelUid(), first.targetChannelUid(),
                first.configuration(), forgedConfiguration);
        replaceManaged(target(first), link(first, first.targetChannelUid(), forgedConfiguration));
        journal().intents(key,
                List.of(new MigrationJournal.LinkIntent(forged, MigrationJournal.LinkPhase.ADD_CONFIRMED)));
        Map<String, String> before = Map.copyOf(persisted);
        clearInvocations(linkProvider);
        assertThrows(IllegalStateException.class, () -> applier().removeTargets(preview));
        verify(linkProvider, never()).remove(anyString());
        verify(linkProvider, never()).add(any());
        assertEquals(before, persisted);
        assertNull(links.get(source(first).getUID()));
    }

    @Test
    void completedCutoverAllowsUnchangedSaveWithinTheSameService() {
        ManagedModbusMigrationApplier operation = applier();
        operation.cutover(preview, group);
        ItemChannelLink owned = java.util.Objects.requireNonNull(links.get(target(first).getUID()));
        replaceManaged(owned, link(first, first.targetChannelUid(), owned.getConfiguration().getProperties()));
        operation.revertLink(preview, first);
        assertFirstRestored();
        assertEquals(List.of(second), journal().links(key));
    }

    @Test
    void intentWriteChangingManagedSourceOwnerWithSameInstanceMustNotRemoveIt() {
        ItemChannelLink original = java.util.Objects.requireNonNull(links.get(source(first).getUID()));
        ManagedItemChannelLinkProvider another = mock(ManagedItemChannelLinkProvider.class);
        when(another.getAll()).thenReturn(List.of());
        links.attachForeign(another);
        onIntentWrite(() -> {
            discardManaged(original);
            links.added(another, original);
        });
        assertThrows(IllegalStateException.class, () -> applier().cutover(preview, group));
        assertSame(original, links.get(original.getUID()));
        assertSame(another, links.getProvider(original));
        verify(linkProvider, never()).remove(original.getUID());
        assertNull(links.get(target(first).getUID()));
        assertEquals(List.of(first), journal().links(key));
    }

    @Test
    void historicalUnmarkedPlanCannotStartAnotherCutover() {
        MigrationLink unmarked = new MigrationLink(first.itemName(), first.sourceChannelUid(), first.targetChannelUid(),
                first.configuration());
        MigrationGroup historical = new MigrationGroup(group.sourcePollerUid(), group.targetPollerUid(),
                group.targetBridgeUid(), group.configuration(), group.channels(), List.of(unmarked));
        assertThrows(IllegalStateException.class, () -> applier().cutover(preview, historical));
        verify(linkProvider, never()).remove(anyString());
        verify(linkProvider, never()).add(any());
        assertTrue(journal().links(key).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = { "REMOVE_INTENDED", "SOURCE_REMOVED", "ADD_ATTEMPTED", "ADD_CONFIRMED", "after-add" })
    void eachDurablePhaseCanReconstructAnExactSafeRevert(String seam) {
        if (seam.equals("after-add")) {
            doAnswer(call -> {
                ItemChannelLink added = call.getArgument(0);
                managedLinks.put(added.getUID(), added);
                links.added(linkProvider, added);
                throw new AssertionError("interrupted after provider mutation");
            }).when(linkProvider).add(argThat(link -> link.getUID().equals(target(first).getUID())));
        } else {
            doAnswer(call -> {
                String storageKey = call.getArgument(0);
                String value = call.getArgument(1);
                String previous = persisted.put(storageKey, value);
                if (storageKey.equals("v2:links:" + key) && value.contains("\"" + seam + "\"")) {
                    throw new AssertionError("interrupted after intent persistence");
                }
                return previous;
            }).when(storage).put(anyString(), anyString());
        }
        assertThrows(AssertionError.class, () -> applier().cutover(preview, group));
        assertEquals(List.of(first), journal().links(key));
        // New journal/provider-facing applier, no process-local link instances.
        var cleanStorage = ManagedModbusMigrationStagingTest.storage(persisted);
        applier(new MigrationJournal(cleanStorage)).revertLink(preview, first);
        assertFirstRestored();
        assertTrue(journal().links(key).isEmpty());
        assertNotNull(links.get(source(second).getUID()));
    }

    @Test
    void selectedRevertMustNotSilentlyAcknowledgeHistoricalUnmarkedIntent() {
        links.remove(source(first).getUID());
        ItemChannelLink unmarked = target(first);
        links.add(unmarked);
        journal().links(key, List.of(new MigrationLink(first.itemName(), first.sourceChannelUid(),
                first.targetChannelUid(), first.configuration())));
        Map<String, String> before = Map.copyOf(persisted);
        clearInvocations(linkProvider);
        assertThrows(IllegalStateException.class, () -> applier().revertLink(preview, first));
        assertSame(unmarked, links.get(unmarked.getUID()));
        assertNull(links.get(source(first).getUID()));
        verify(linkProvider, never()).remove(anyString());
        verify(linkProvider, never()).add(any());
        assertEquals(before, persisted);
    }

    @Test
    void reconstructionWithoutExpectedManagedOwnerMustNotRestoreAnAbsentSource() {
        applier().cutover(preview, group);
        links.remove(target(first).getUID());
        clearInvocations(linkProvider);
        ManagedModbusMigrationApplier unknownOwner = new ManagedModbusMigrationApplier(thingProvider, links, journal(),
                things);
        assertThrows(IllegalStateException.class, () -> unknownOwner.revertLink(preview, first));
        verify(linkProvider, never()).add(any());
        verify(linkProvider, never()).remove(anyString());
        assertNull(links.get(source(first).getUID()));
        assertEquals(group.links(), journal().links(key));
    }

    @Test
    void firstTargetAddListenerChangingSecondProfileRefusesBeforeSecondWrite() {
        ItemChannelLink changed = link(second, second.sourceChannelUid(), Map.of("profile", "system:offset"));
        onAdd(target(first), () -> replaceManaged(source(second), changed));

        assertThrows(IllegalStateException.class, () -> applier().cutover(preview, group));
        assertSame(changed, links.get(changed.getUID()));
        assertEquals(Map.of("profile", "system:offset"), changed.getConfiguration().getProperties());
        assertSame(linkProvider, links.getProvider(changed));
        assertUntouchedSecond();
        assertFirstRestored();
        assertTrue(journal().links(key).isEmpty());
    }

    @Test
    void firstTargetAddListenerReplacingSecondSourceWithIdenticalForeignLinkRefusesBeforeSecondWrite() {
        ItemChannelLink replacement = source(second);
        assertNotSame(links.get(replacement.getUID()), replacement);
        onAdd(target(first), () -> replaceForeign(source(second), replacement));

        assertThrows(IllegalStateException.class, () -> applier().cutover(preview, group));
        assertSame(replacement, links.get(replacement.getUID()));
        assertEquals(second.configuration(), replacement.getConfiguration().getProperties());
        assertSame(foreignProvider, links.getProvider(replacement));
        assertUntouchedSecond();
        assertFirstRestored();
        assertTrue(journal().links(key).isEmpty());
    }

    @Test
    void firstTargetAddListenerCreatingForeignSecondTargetDoesNotOverwriteOrRemoveIt() {
        ItemChannelLink foreign = target(second);
        ItemChannelLink original = links.get(source(second).getUID());
        onAdd(target(first), () -> links.added(foreignProvider, foreign));

        assertThrows(IllegalStateException.class, () -> applier().cutover(preview, group));
        assertSame(original, links.get(source(second).getUID()));
        assertSame(foreign, links.get(foreign.getUID()));
        assertSame(foreignProvider, links.getProvider(foreign));
        verify(linkProvider, never()).add(argThat(link -> link.getUID().equals(foreign.getUID())));
        verify(linkProvider, never()).remove(foreign.getUID());
        verify(linkProvider, never()).remove(source(second).getUID());
        assertNeverOwnedSecond();
        assertFirstRestored();
        assertTrue(journal().links(key).isEmpty());
    }

    @Test
    void preexistingForeignTargetRefusesWithoutAnyLinkMutation() {
        ItemChannelLink foreign = target(second);
        links.added(foreignProvider, foreign);

        assertThrows(IllegalStateException.class, () -> applier().cutover(preview, group));
        verify(linkProvider, never()).add(any());
        verify(linkProvider, never()).remove(anyString());
        assertSame(foreign, links.get(foreign.getUID()));
        assertSame(foreignProvider, links.getProvider(foreign));
        assertFirstRestored();
        assertTrue(journal().links(key).isEmpty());
    }

    @Test
    void sourceRemovalReturningWithoutRemovingMustNotAddSecondTarget() {
        removalNoOp.set(true);
        ItemChannelLink original = links.get(source(second).getUID());

        assertThrows(IllegalStateException.class, () -> applier().cutover(preview, group));
        assertSame(original, links.get(source(second).getUID()));
        assertNull(links.get(target(second).getUID()));
        verify(linkProvider, never()).add(argThat(link -> link.getUID().equals(target(second).getUID())));
        assertFirstRestored();
        // Removing the target is safe here: the original reviewed managed source is still present.
        assertTrue(journal().links(key).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void targetAddListenerReplacingFirstTargetMustRetainRecoveryIntent(boolean managedReplacement) {
        ItemChannelLink replacement = target(first);
        onAdd(target(first), () -> {
            if (managedReplacement) {
                replaceManaged(target(first), replacement);
            } else {
                replaceForeign(target(first), replacement);
            }
        });

        assertThrows(IllegalStateException.class, () -> applier().cutover(preview, group));
        assertSame(replacement, links.get(replacement.getUID()));
        assertSame(managedReplacement ? linkProvider : foreignProvider, links.getProvider(replacement));
        assertNull(links.get(source(first).getUID()), "Do not restore legacy delivery beside a replacement target");
        verify(linkProvider, never()).remove(replacement.getUID());
        assertUntouchedSecond();
        assertEquals(List.of(first), journal().links(key));
    }

    @Test
    void sourceRemovalListenerRecreatingSourceMustNotAddTargetBesideIt() {
        ItemChannelLink replacement = source(second);
        onRemove(source(second), () -> links.added(foreignProvider, replacement));

        assertThrows(IllegalStateException.class, () -> applier().cutover(preview, group));
        assertSame(replacement, links.get(replacement.getUID()));
        assertSame(foreignProvider, links.getProvider(replacement));
        assertNull(links.get(target(second).getUID()));
        verify(linkProvider, never()).add(argThat(link -> link.getUID().equals(target(second).getUID())));
        // L2 was removed by this cutover, so its unresolved recovery intent must not be discarded.
        assertTrue(journal().links(key).contains(second));
    }

    @Test
    void foreignFirstTargetBlockingCompensationRetainsOnlyFirstIntentNotChangedSecond() {
        ItemChannelLink foreignFirst = target(first);
        ItemChannelLink changedSecond = link(second, second.sourceChannelUid(), Map.of("profile", "system:offset"));
        onAdd(target(first), () -> {
            replaceForeign(target(first), foreignFirst);
            replaceManaged(source(second), changedSecond);
        });

        assertThrows(IllegalStateException.class, () -> applier().cutover(preview, group));
        assertSame(foreignFirst, links.get(foreignFirst.getUID()));
        assertNull(links.get(source(first).getUID()));
        assertSame(changedSecond, links.get(changedSecond.getUID()));
        verify(linkProvider, never()).remove(foreignFirst.getUID());
        assertUntouchedSecond();
        assertEquals(List.of(first), journal().links(key));
    }

    @Test
    void sourceRemovalListenerCreatingManagedTargetBeforeAddMustRetainRecoveryIntent() {
        ItemChannelLink foreign = target(first);
        onRemove(source(first), () -> links.add(foreign));
        ManagedModbusMigrationApplier operation = applier();

        assertThrows(IllegalStateException.class, () -> operation.cutover(preview, group));
        assertSame(foreign, links.get(foreign.getUID()));
        assertSame(linkProvider, links.getProvider(foreign));
        assertNull(links.get(source(first).getUID()), "Do not restore legacy delivery beside a foreign target");
        verify(linkProvider, never()).remove(foreign.getUID());
        verify(linkProvider, never()).add(argThat(link -> link.getUID().equals(source(first).getUID())));
        // The sole target add belongs to the listener, not the applier.
        verify(linkProvider).add(foreign);
        assertUntouchedSecond();
        assertEquals(List.of(first), journal().links(key));
    }

    @Test
    void intentWriteCreatingManagedTargetBeforeSourceRemovalMustRetainRecoveryIntent() {
        ItemChannelLink foreign = target(first);
        ItemChannelLink original = links.get(source(first).getUID());
        onIntentWrite(() -> links.add(foreign));

        assertThrows(IllegalStateException.class, () -> applier().cutover(preview, group));
        assertSame(foreign, links.get(foreign.getUID()));
        assertSame(linkProvider, links.getProvider(foreign));
        assertSame(original, links.get(source(first).getUID()));
        verify(linkProvider, never()).remove(foreign.getUID());
        verify(linkProvider, never()).remove(source(first).getUID());
        verify(linkProvider, never()).add(argThat(link -> link.getUID().equals(source(first).getUID())));
        verify(linkProvider).add(foreign);
        assertUntouchedSecond();
        assertEquals(List.of(first), journal().links(key));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void reconstructedRemovalOnlyIntentMustNotAuthorizeManagedTargetDeletion(boolean duringIntentWrite) {
        ItemChannelLink foreign = target(first);
        ItemChannelLink original = links.get(source(first).getUID());
        Runnable interrupt = () -> {
            links.add(foreign);
            // Bypass live compensation to reconstruct at the actual pre-add interruption seam.
            throw new AssertionError("simulated interruption before applier target addition");
        };
        if (duringIntentWrite) {
            onIntentWrite(interrupt);
        } else {
            // Core registry listeners swallow Errors; interrupt at the provider boundary instead.
            doAnswer(call -> {
                ItemChannelLink removed = java.util.Objects.requireNonNull(managedLinks.remove(source(first).getUID()));
                links.removed(linkProvider, removed);
                interrupt.run();
                return removed;
            }).when(linkProvider).remove(source(first).getUID());
        }
        assertThrows(AssertionError.class, () -> applier().cutover(preview, group));
        assertEquals(List.of(first), journal().links(key));

        assertThrows(IllegalStateException.class, () -> applier().revertLink(preview, first));
        assertSame(foreign, links.get(foreign.getUID()));
        assertSame(linkProvider, links.getProvider(foreign));
        if (duringIntentWrite) {
            assertSame(original, links.get(source(first).getUID()));
            verify(linkProvider, never()).remove(source(first).getUID());
        } else {
            assertNull(links.get(source(first).getUID()), "Do not restore legacy delivery beside a foreign target");
        }
        verify(linkProvider, never()).remove(foreign.getUID());
        verify(linkProvider, never()).add(argThat(link -> link.getUID().equals(source(first).getUID())));
        verify(linkProvider).add(foreign);
        assertUntouchedSecond();
        assertEquals(List.of(first), journal().links(key));
    }

    private void onIntentWrite(Runnable callback) {
        AtomicBoolean once = new AtomicBoolean();
        doAnswer(call -> {
            String storageKey = call.getArgument(0);
            String value = call.getArgument(1);
            String previous = persisted.put(storageKey, value);
            if (storageKey.equals("v2:links:" + key)) {
                linkIntents.add(journal().links(key));
                if (once.compareAndSet(false, true)) {
                    callback.run();
                }
            }
            return previous;
        }).when(storage).put(anyString(), anyString());
    }

    private void assertUntouchedSecond() {
        assertNull(links.get(target(second).getUID()));
        verify(linkProvider, never()).remove(source(second).getUID());
        verify(linkProvider, never()).remove(target(second).getUID());
        verify(linkProvider, never()).add(argThat(link -> link.getUID().equals(target(second).getUID())));
        assertNeverOwnedSecond();
    }

    private void assertNeverOwnedSecond() {
        assertFalse(linkIntents.isEmpty(), "The first switch must exercise write-ahead journaling");
        assertTrue(linkIntents.stream().noneMatch(intent -> intent.contains(second)),
                "An untouched changed/foreign L2 must never become owned recovery work, even transiently");
    }

    private void assertFirstRestored() {
        ItemChannelLink restored = links.get(source(first).getUID());
        assertNotNull(restored);
        assertEquals(first.configuration(), restored.getConfiguration().getProperties());
        assertSame(linkProvider, links.getProvider(restored));
        assertNull(links.get(target(first).getUID()));
    }

    private void replaceManaged(ItemChannelLink previous, ItemChannelLink replacement) {
        discardManaged(previous);
        managedLinks.put(replacement.getUID(), replacement);
        links.added(linkProvider, replacement);
    }

    private void replaceForeign(ItemChannelLink previous, ItemChannelLink replacement) {
        discardManaged(previous);
        links.added(foreignProvider, replacement);
    }

    private void discardManaged(ItemChannelLink previous) {
        ItemChannelLink current = managedLinks.remove(previous.getUID());
        assertNotNull(current);
        links.removed(linkProvider, current);
    }

    private void onAdd(ItemChannelLink watched, Runnable callback) {
        onChange(watched, callback, true);
    }

    private void onRemove(ItemChannelLink watched, Runnable callback) {
        onChange(watched, callback, false);
    }

    private void onChange(ItemChannelLink watched, Runnable callback, boolean addition) {
        AtomicBoolean once = new AtomicBoolean();
        links.addRegistryChangeListener(new RegistryChangeListener<>() {
            @Override
            public void added(ItemChannelLink element) {
                if (addition && watched.getUID().equals(element.getUID()) && once.compareAndSet(false, true)) {
                    callback.run();
                }
            }

            @Override
            public void removed(ItemChannelLink element) {
                if (!addition && watched.getUID().equals(element.getUID()) && once.compareAndSet(false, true)) {
                    callback.run();
                }
            }

            @Override
            public void updated(ItemChannelLink oldElement, ItemChannelLink element) {
            }
        });
    }

    private ItemChannelLink source(MigrationLink mapping) {
        return link(mapping, mapping.sourceChannelUid(), mapping.configuration());
    }

    private ItemChannelLink target(MigrationLink mapping) {
        return link(mapping, mapping.targetChannelUid(), mapping.configuration());
    }

    private ItemChannelLink link(MigrationLink mapping, String channel, Map<String, Object> configuration) {
        return new ItemChannelLink(mapping.itemName(), new ChannelUID(channel), new Configuration(configuration));
    }

    private MigrationJournal journal() {
        return new MigrationJournal(storage);
    }

    private ManagedModbusMigrationApplier applier() {
        return applier(journal());
    }

    private ManagedModbusMigrationApplier applier(MigrationJournal journal) {
        return new ManagedModbusMigrationApplier(thingProvider, links, journal, things, linkProvider);
    }

    private static final class TestLinkRegistry extends ItemChannelLinkRegistry {
        TestLinkRegistry(ThingRegistry things) {
            super(Map.of(), things, mock(ItemRegistry.class), mock(ItemBuilderFactory.class),
                    mock(BundleContext.class));
        }

        void attachForeign(ItemChannelLinkProvider provider) {
            addProvider(provider);
        }

        void attach(ManagedItemChannelLinkProvider managed, ItemChannelLinkProvider foreign) {
            setManagedProvider(managed);
            addProvider(managed);
            addProvider(foreign);
        }
    }

    private static final class TestThingRegistry extends AbstractRegistry<Thing, ThingUID, ThingProvider>
            implements ThingRegistry {
        TestThingRegistry() {
            super(ThingProvider.class);
        }

        void attach(ThingProvider provider) {
            addProvider(provider);
        }

        @Override
        public @Nullable Channel getChannel(ChannelUID channelUID) {
            Thing thing = get(channelUID.getThingUID());
            return thing == null ? null : thing.getChannel(channelUID.getId());
        }

        @Override
        public void updateConfiguration(ThingUID uid, Map<String, Object> configuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Nullable Thing forceRemove(ThingUID uid) {
            return remove(uid);
        }

        @Override
        public @Nullable Thing createThingOfType(ThingTypeUID type, @Nullable ThingUID uid, @Nullable ThingUID bridge,
                @Nullable String label, Configuration configuration) {
            throw new UnsupportedOperationException();
        }
    }
}
