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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openhab.core.common.registry.Provider;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.internal.ThingRegistryImpl;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;

/** @author Sami Salonen - Initial contribution */
@org.eclipse.jdt.annotation.NonNullByDefault
class ProviderOwnedMigrationPreviewTest {
    private final ThingRegistryImpl things = mock(ThingRegistryImpl.class);
    private final ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
    private final Provider<Thing> owner = mock(Provider.class);
    private final Provider<ItemChannelLink> linkOwner = mock(Provider.class);
    private final Thing endpoint = BridgeBuilder
            .create(new ThingTypeUID("modbus", "serial"), new ThingUID("modbus:serial:plant"))
            .withConfiguration(new Configuration(Map.of("port", "/dev/ttyUSB0", "password", "SECRET"))).build();
    private final Thing poller = BridgeBuilder
            .create(new ThingTypeUID("modbus", "poller"), new ThingUID("modbus:poller:plant:holding"))
            .withBridge(endpoint.getUID())
            .withConfiguration(new Configuration(Map.of("start", 100, "length", 2, "type", "holding"))).build();
    private final Thing data = ThingBuilder
            .create(new ThingTypeUID("modbus", "data"), new ThingUID("modbus:data:plant:holding:value"))
            .withBridge(poller.getUID())
            .withConfiguration(new Configuration(Map.of("readStart", "100", "readValueType", "int16")))
            .withChannel(ChannelBuilder.create(new ChannelUID("modbus:data:plant:holding:value:number"))
                    .withAcceptedItemType("Number").build())
            .build();
    private final ItemChannelLink link = new ItemChannelLink("Value",
            new ChannelUID("modbus:data:plant:holding:value:number"),
            new Configuration(Map.of("profile", "system:default")));

    private ProviderOwnedMigrationAdapter adapter() {
        when(things.getAll()).thenReturn(List.of(data, endpoint, poller));
        when(things.get(endpoint.getUID())).thenReturn(endpoint);
        when(things.getProvider(poller)).thenReturn(owner);
        when(things.getProvider(data)).thenReturn(owner);
        when(things.getProvider(endpoint)).thenReturn(owner);
        when(links.getAll()).thenReturn(List.of(link));
        when(links.getProvider(link)).thenReturn(linkOwner);
        return new ProviderOwnedMigrationAdapter(things, links);
    }

    @Test
    void unknownProviderDoesNotGenerateAFileCandidate() {
        var adapter = adapter();
        when(things.getProvider(poller)).thenReturn(null);
        var preview = adapter.preview();
        assertTrue(preview.fileChanges().isEmpty());
        assertTrue(preview.manualWork().stream().anyMatch(work -> work.sourceUid().equals(poller.getUID().toString())));
        verifyNoInteractions(owner, linkOwner);
    }

    @Test
    void unsupportedContactConversionRemainsManual() {
        var adapter = adapter();
        Thing contact = ThingBuilder.create(data.getThingTypeUID(), data.getUID()).withBridge(poller.getUID())
                .withConfiguration(new Configuration(Map.of("readStart", "100.0", "readValueType", "bit")))
                .withChannel(ChannelBuilder.create(new ChannelUID(data.getUID(), "contact"))
                        .withAcceptedItemType("Contact").build())
                .build();
        ItemChannelLink contactLink = new ItemChannelLink("ContactValue", new ChannelUID(data.getUID(), "contact"));
        when(things.getAll()).thenReturn(List.of(contact, endpoint, poller));
        when(things.getProvider(contact)).thenReturn(owner);
        when(links.getAll()).thenReturn(List.of(contactLink));
        when(links.getProvider(contactLink)).thenReturn(linkOwner);
        var preview = adapter.preview();
        assertTrue(preview.fileChanges().isEmpty());
        assertTrue(preview.manualWork().stream()
                .anyMatch(work -> work.reason().contains("conversion is not proven compatible")));
        verifyNoInteractions(owner, linkOwner);
    }

    @Test
    void previewsNativeUnlinkedCandidateAndManualLinkPatchWithoutMutation() {
        var adapter = adapter();
        var preview = adapter.preview();
        assertEquals(1, preview.fileChanges().size());
        var change = preview.fileChanges().getFirst();
        assertEquals("modbus:poller:plant:holding", change.sourcePollerUid());
        assertEquals("modbus:poller2:plant:holding", change.targetPollerUid());
        assertTrue(change.candidateYaml().startsWith("version: 1\nthings:"));
        assertTrue(change.candidateYaml().contains("itemType: Number"));
        assertFalse(change.candidateYaml().contains("items:"));
        assertEquals("UNKNOWN_MANUAL_LOCATION", change.sources().getFirst().sourceLocation());
        assertEquals(owner.getClass().getName(),
                change.sources().stream().filter(source -> source.sourceUid().equals(poller.getUID().toString()))
                        .findFirst().orElseThrow().providerIdentity());
        var patch = change.linkPatches().getFirst();
        assertEquals(link.getLinkedUID().toString(), patch.sourceChannelUid());
        assertEquals("modbus:poller2:plant:holding:value", patch.targetChannelUid());
        assertEquals(Map.of("profile", "system:default"), patch.profileConfiguration());
        assertEquals(linkOwner.getClass().getName(), patch.providerIdentity());
        assertTrue(change.instructions().contains("Revert"));
        assertFalse(preview.toString().contains("SECRET"));
        assertFalse(preview.toString().contains("/dev/ttyUSB0"));
        assertEquals(preview, adapter.preview());
        verify(things, atLeastOnce()).getAll();
        verify(things, atLeastOnce()).get(endpoint.getUID());
        verify(things, atLeastOnce()).getProvider(poller);
        verify(things, atLeastOnce()).getProvider(data);
        verify(things, atLeastOnce()).getProvider(endpoint);
        verify(things, atLeastOnce()).get(new ThingUID(change.targetPollerUid()));
        verify(links, atLeastOnce()).getAll();
        verify(links, atLeastOnce()).getProvider(link);
        verifyNoMoreInteractions(things, links);
        verifyNoInteractions(owner, linkOwner);
    }
}
