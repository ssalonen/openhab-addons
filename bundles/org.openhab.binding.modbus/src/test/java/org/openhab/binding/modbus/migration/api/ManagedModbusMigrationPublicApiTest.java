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
package org.openhab.binding.modbus.migration.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.binding.modbus.migration.LegacyData;
import org.openhab.binding.modbus.migration.LegacyLink;
import org.openhab.binding.modbus.migration.LegacyPoller;
import org.openhab.binding.modbus.migration.ManagedModbusMigrationApplier;
import org.openhab.binding.modbus.migration.MigrationPreview;
import org.openhab.binding.modbus.migration.ModbusMigrationPlanner;
import org.openhab.core.common.registry.AbstractRegistry;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingProvider;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.link.ManagedItemChannelLinkProvider;

/** @author Sami Salonen - Initial contribution */
@NonNullByDefault
class ManagedModbusMigrationPublicApiTest {
    @Test
    void registryAwarePublicConstructorStagesCutsOverAndRollsBack() {
        ManagedThingProvider provider = mock(ManagedThingProvider.class);
        ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
        ManagedItemChannelLinkProvider linkProvider = mock(ManagedItemChannelLinkProvider.class);
        TestThingRegistry things = new TestThingRegistry();
        Map<ThingUID, Thing> targets = new HashMap<>();
        Map<String, ItemChannelLink> routing = new HashMap<>();
        when(provider.getAll()).thenAnswer(call -> List.copyOf(targets.values()));
        when(provider.get(any(ThingUID.class))).thenAnswer(call -> targets.get(call.getArgument(0)));
        doAnswer(call -> {
            Thing target = call.getArgument(0);
            targets.put(target.getUID(), target);
            things.added(provider, target);
            return null;
        }).when(provider).add(any());
        when(provider.remove(any(ThingUID.class))).thenAnswer(call -> {
            Thing removed = targets.remove(call.getArgument(0));
            if (removed != null) {
                things.removed(provider, removed);
            }
            return removed;
        });
        things.attach(provider);
        when(links.get(anyString())).thenAnswer(call -> routing.get(call.getArgument(0)));
        when(links.getProvider(any(ItemChannelLink.class))).thenReturn(linkProvider);
        when(links.remove(anyString())).thenAnswer(call -> routing.remove(call.getArgument(0)));
        doAnswer(call -> {
            ItemChannelLink link = call.getArgument(0);
            routing.put(link.getUID(), link);
            return null;
        }).when(links).add(any());
        var preview = preview();
        var group = preview.groups().getFirst();
        var mapping = group.links().getFirst();
        var source = new ItemChannelLink(mapping.itemName(), new ChannelUID(mapping.sourceChannelUid()),
                new Configuration(mapping.configuration()));
        var target = new ItemChannelLink(mapping.itemName(), new ChannelUID(mapping.targetChannelUid()),
                new Configuration(mapping.targetConfiguration()));
        routing.put(source.getUID(), source);
        var applier = new ManagedModbusMigrationApplier(provider, links, things);
        applier.createTargets(preview);
        assertEquals(1, targets.size());
        assertEquals(Map.of(source.getUID(), source), routing);
        applier.cutover(preview, group);
        assertEquals(Map.of(target.getUID(), target), routing);
        applier.removeTargets(preview);
        assertTrue(targets.isEmpty());
        assertEquals(1, routing.size());
        assertEquals(source, routing.get(source.getUID()));
        assertEquals(mapping.configuration(),
                java.util.Objects.requireNonNull(routing.get(source.getUID())).getConfiguration().getProperties());
        clearInvocations(provider, links);
        applier.removeTargets(preview);
        verifyNoInteractions(provider, links);
    }

    @Test
    @SuppressWarnings("deprecation") // Prove the retained compatibility constructor fails closed.
    void legacyPublicConstructorRefusesStagingWithoutMutations() {
        ManagedThingProvider provider = mock(ManagedThingProvider.class);
        ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
        var applier = new ManagedModbusMigrationApplier(provider, links);
        assertThrows(IllegalStateException.class, () -> applier.createTargets(preview()));
        verifyNoInteractions(provider, links);
        applier.removeTargets(preview());
        verifyNoInteractions(provider, links);
    }

    private static MigrationPreview preview() {
        return new ModbusMigrationPlanner().preview(
                List.of(new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                        Map.of("start", 100, "length", 2, "type", "holding"))),
                List.of(new LegacyData("modbus:data:tcp:plant:temperature", "modbus:poller:tcp:plant",
                        Map.of("readStart", "100", "readValueType", "int16"), List.of(new LegacyLink("Temperature",
                                "modbus:data:tcp:plant:temperature:number", "Number", Map.of())))));
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
