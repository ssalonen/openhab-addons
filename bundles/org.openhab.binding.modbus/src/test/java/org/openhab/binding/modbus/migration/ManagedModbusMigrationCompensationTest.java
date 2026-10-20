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
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.common.registry.AbstractRegistry;
import org.openhab.core.common.registry.RegistryChangeListener;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.items.ItemBuilderFactory;
import org.openhab.core.items.ItemRegistry;
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

/** @author Sami Salonen - Initial contribution */
@NonNullByDefault
class ManagedModbusMigrationCompensationTest {
    private final MigrationPreview preview = ManagedModbusMigrationStagingTest.preview();
    private final MigrationGroup group = preview.groups().getFirst();
    private final MigrationLink mapping = group.links().getFirst();
    private final String key = preview.identity() + ":" + group.sourcePollerUid();
    private final ItemChannelLink source = link(mapping.sourceChannelUid(), mapping.configuration());
    private final ItemChannelLink target = link(mapping.targetChannelUid(), mapping.targetConfiguration());
    private final Map<String, String> persisted = new HashMap<>();
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
            if (removalNoOp.get()) {
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
        links.add(target);
        journal().intents(key,
                List.of(new MigrationJournal.LinkIntent(mapping, MigrationJournal.LinkPhase.ADD_CONFIRMED)));
        clearInvocations(linkProvider);
    }

    @Test
    void completionWriteRecreatingForeignTargetMustRetainIntentAndCompensateOwnSource() {
        var storage = spy(ManagedModbusMigrationStagingTest.storage(persisted));
        ItemChannelLink replacement = link(mapping.targetChannelUid(), mapping.targetConfiguration());
        AtomicBoolean once = new AtomicBoolean();
        doAnswer(call -> {
            String storageKey = call.getArgument(0);
            String value = call.getArgument(1);
            String previous = persisted.put(storageKey, value);
            if (storageKey.equals("v2:links:" + key) && value.equals("[]") && once.compareAndSet(false, true)) {
                links.added(foreignProvider, replacement);
            }
            return previous;
        }).when(storage).put(anyString(), anyString());

        assertThrows(IllegalStateException.class,
                () -> applier(new MigrationJournal(storage)).revertLink(preview, mapping));
        assertTrue(once.get());
        assertSame(replacement, links.get(target.getUID()));
        assertNull(links.get(source.getUID()));
        assertEquals(List.of(mapping), journal().links(key));
        verify(linkProvider).remove(source.getUID());
        clearInvocations(linkProvider);
        assertThrows(IllegalStateException.class, () -> applier(journal()).revertLink(preview, mapping));
        verifyNoInteractions(linkProvider);
        links.removed(foreignProvider, replacement);
        applier(journal()).revertLink(preview, mapping);
        assertEquals(source, links.get(source.getUID()));
        assertTrue(journal().links(key).isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void sourceAddFailureOrNoOpRetainsIntentForReconstructedRetry(boolean throwsFailure) {
        doAnswer(call -> {
            if (throwsFailure) {
                throw new IllegalStateException("source add failed before mutation");
            }
            return null;
        }).when(linkProvider).add(argThat(link -> link.getUID().equals(source.getUID())));
        assertThrows(IllegalStateException.class, () -> applier(journal()).revertLink(preview, mapping));
        assertNull(links.get(target.getUID()));
        assertNull(links.get(source.getUID()));
        assertEquals(List.of(mapping), journal().links(key));
        doAnswer(call -> {
            ItemChannelLink added = call.getArgument(0);
            managedLinks.put(added.getUID(), added);
            links.added(linkProvider, added);
            return null;
        }).when(linkProvider).add(argThat(link -> link.getUID().equals(source.getUID())));
        clearInvocations(linkProvider);
        assertSafeRestartRetry();
    }

    @Test
    void unmanagedReplacementFromRemovalListenerRetainsIntentUntilSafeRestartRetry() {
        ItemChannelLink replacement = link(mapping.targetChannelUid(), mapping.targetConfiguration());
        assertNotSame(target, replacement);
        assertEquals(target.getUID(), replacement.getUID());
        assertEquals(target.getConfiguration().getProperties(), replacement.getConfiguration().getProperties());
        onTargetRemoval(() -> links.added(foreignProvider, replacement));

        assertThrows(IllegalStateException.class, () -> applier(journal()).revertLink(preview, mapping));
        assertSame(replacement, links.get(target.getUID()));
        assertSame(foreignProvider, links.getProvider(replacement));
        assertNull(links.get(source.getUID()), "Do not restore legacy delivery beside a replacement target");
        assertEquals(List.of(mapping), journal().links(key));
        verify(linkProvider, never()).add(any());

        // A reconstructed applier must also refuse the authoritative foreign link; managed remove is a no-op for it.
        clearInvocations(linkProvider);
        assertThrows(IllegalStateException.class, () -> applier(journal()).revertLink(preview, mapping));
        verify(linkProvider, never()).remove(anyString());
        assertNull(links.get(source.getUID()));
        assertEquals(List.of(mapping), journal().links(key));

        // Only the owning provider resolves its replacement, then persisted recovery can safely restore the source.
        links.removed(foreignProvider, replacement);
        assertSafeRestartRetry();
    }

    @Test
    void sourceChangedByRemovalListenerIsNotOverwrittenOrMarkedRecovered() {
        ItemChannelLink changed = link(mapping.sourceChannelUid(), Map.of("profile", "custom:changed"));
        onTargetRemoval(() -> links.added(foreignProvider, changed));

        assertThrows(IllegalStateException.class, () -> applier(journal()).revertLink(preview, mapping));
        assertNull(links.get(target.getUID()));
        assertSame(changed, links.get(source.getUID()));
        assertSame(foreignProvider, links.getProvider(changed));
        assertEquals(Map.of("profile", "custom:changed"), changed.getConfiguration().getProperties());
        assertEquals(List.of(mapping), journal().links(key));
        verify(linkProvider, never()).add(any());

        assertThrows(IllegalStateException.class, () -> applier(journal()).revertLink(preview, mapping));
        assertEquals(List.of(mapping), journal().links(key));
        links.removed(foreignProvider, changed);
        assertSafeRestartRetry();
    }

    @Test
    void removalReturningWithoutRemovingTargetRetainsIntentAndDoesNotRestoreSource() {
        removalNoOp.set(true);
        assertSame(linkProvider, links.getProvider(target));

        assertThrows(IllegalStateException.class, () -> applier(journal()).revertLink(preview, mapping));
        assertSame(target, links.get(target.getUID()));
        assertNull(links.get(source.getUID()), "A successful return is not proof of target removal");
        assertEquals(List.of(mapping), journal().links(key));
        verify(linkProvider, never()).add(any());

        assertThrows(IllegalStateException.class, () -> applier(journal()).revertLink(preview, mapping));
        assertEquals(List.of(mapping), journal().links(key));
        removalNoOp.set(false);
        assertSafeRestartRetry();
    }

    @Test
    void sourceAddListenerRecreatingForeignTargetCompensatesOnlyOwnSourceAndRetainsIntent() {
        ItemChannelLink replacement = link(mapping.targetChannelUid(), mapping.targetConfiguration());
        AtomicBoolean once = new AtomicBoolean();
        links.addRegistryChangeListener(new RegistryChangeListener<>() {
            @Override
            public void added(ItemChannelLink element) {
                if (element.getUID().equals(source.getUID()) && once.compareAndSet(false, true)) {
                    links.added(foreignProvider, replacement);
                }
            }

            @Override
            public void removed(ItemChannelLink element) {
            }

            @Override
            public void updated(ItemChannelLink oldElement, ItemChannelLink element) {
            }
        });

        assertThrows(IllegalStateException.class, () -> applier(journal()).revertLink(preview, mapping));
        assertSame(replacement, links.get(target.getUID()));
        assertSame(foreignProvider, links.getProvider(replacement));
        assertNull(links.get(source.getUID()));
        assertEquals(List.of(mapping), journal().links(key));
        verify(linkProvider).remove(source.getUID());
        verify(linkProvider, times(1)).remove(target.getUID());
        clearInvocations(linkProvider);
        assertThrows(IllegalStateException.class, () -> applier(journal()).revertLink(preview, mapping));
        verifyNoInteractions(linkProvider);
        links.removed(foreignProvider, replacement);
        assertSafeRestartRetry();
    }

    @Test
    void identicalForeignSourceFromRemovalListenerRetainsIntent() {
        ItemChannelLink replacement = link(mapping.sourceChannelUid(), mapping.configuration());
        onTargetRemoval(() -> links.added(foreignProvider, replacement));
        assertThrows(IllegalStateException.class, () -> applier(journal()).revertLink(preview, mapping));
        assertSame(replacement, links.get(source.getUID()));
        assertEquals(List.of(mapping), journal().links(key));
        verify(linkProvider, never()).add(any());
        assertThrows(IllegalStateException.class, () -> applier(journal()).revertLink(preview, mapping));
        links.removed(foreignProvider, replacement);
        assertSafeRestartRetry();
    }

    @Test
    void registrylessStageRefusesBeforeAnyJournalOrProviderWrite() {
        Map<String, String> empty = new HashMap<>();
        ManagedModbusMigrationApplier registryless = new ManagedModbusMigrationApplier(thingProvider, links,
                new MigrationJournal(ManagedModbusMigrationStagingTest.storage(empty)));
        clearInvocations(thingProvider);
        clearInvocations(linkProvider);
        assertThrows(IllegalStateException.class, () -> registryless.createTargets(preview));
        assertTrue(empty.isEmpty());
        verifyNoInteractions(thingProvider, linkProvider);
    }

    private void assertSafeRestartRetry() {
        applier(journal()).revertLink(preview, mapping);
        assertNull(links.get(target.getUID()));
        ItemChannelLink restored = links.get(source.getUID());
        assertNotNull(restored);
        assertEquals(mapping.configuration(), restored.getConfiguration().getProperties());
        assertSame(linkProvider, links.getProvider(restored));
        assertEquals(List.of(restored), List.copyOf(links.getAll()));
        assertTrue(journal().links(key).isEmpty());
        verify(linkProvider, times(1)).add(any());
        clearInvocations(linkProvider);
        applier(journal()).revertLink(preview, mapping);
        verifyNoInteractions(linkProvider);
    }

    private void onTargetRemoval(Runnable callback) {
        AtomicBoolean once = new AtomicBoolean();
        links.addRegistryChangeListener(new RegistryChangeListener<>() {
            @Override
            public void added(ItemChannelLink element) {
            }

            @Override
            public void removed(ItemChannelLink element) {
                if (element.getUID().equals(target.getUID()) && once.compareAndSet(false, true)) {
                    callback.run();
                }
            }

            @Override
            public void updated(ItemChannelLink oldElement, ItemChannelLink element) {
            }
        });
    }

    private ItemChannelLink link(String channel, Map<String, Object> configuration) {
        return new ItemChannelLink(mapping.itemName(), new ChannelUID(channel), new Configuration(configuration));
    }

    private MigrationJournal journal() {
        return new MigrationJournal(ManagedModbusMigrationStagingTest.storage(persisted));
    }

    private ManagedModbusMigrationApplier applier(MigrationJournal journal) {
        return new ManagedModbusMigrationApplier(thingProvider, links, journal, things, linkProvider);
    }

    private static final class TestLinkRegistry extends ItemChannelLinkRegistry {
        TestLinkRegistry(ThingRegistry things) {
            super(Map.of(), things, mock(ItemRegistry.class), mock(ItemBuilderFactory.class),
                    mock(BundleContext.class));
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
