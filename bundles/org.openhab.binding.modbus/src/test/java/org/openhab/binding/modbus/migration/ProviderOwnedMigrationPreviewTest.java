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
import org.openhab.core.common.registry.AbstractRegistry;
import org.openhab.core.common.registry.Provider;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.ChannelUID;
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

/** @author Sami Salonen - Initial contribution */
@org.eclipse.jdt.annotation.NonNullByDefault
class ProviderOwnedMigrationPreviewTest {
    abstract static class OwnedThingRegistry extends AbstractRegistry<Thing, ThingUID, ThingProvider>
            implements ThingRegistry {
        OwnedThingRegistry() {
            super(ThingProvider.class);
        }
    }

    private final OwnedThingRegistry things = mock(OwnedThingRegistry.class);
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
    void allManualContactConfigurationDriftInvalidatesReviewWithoutExposingValues() {
        var adapter = adapter();
        Thing contact = contact("contact");
        ItemChannelLink contactLink = new ItemChannelLink("ContactValue", new ChannelUID(contact.getUID(), "contact"),
                new Configuration(Map.of("profile", "custom:contact", "ordinary", "SENTINEL-profile")));
        when(things.getAll()).thenReturn(List.of(contact, endpoint, poller));
        when(things.getProvider(contact)).thenReturn(owner);
        when(links.getAll()).thenReturn(List.of(contactLink));
        when(links.getProvider(contactLink)).thenReturn(linkOwner);
        var before = adapter.preview();
        assertTrue(before.fileChanges().isEmpty());
        contact.getConfiguration().put("ordinary", "SENTINEL-data");
        var after = adapter.preview();
        assertNotEquals(before.revisionDigest(), after.revisionDigest());
        assertFalse(after.toString().contains("SENTINEL"));
    }

    @Test
    void allManualContactEndpointCredentialsDriftInvalidatesReview() {
        var adapter = adapter();
        Thing contact = contact("contact");
        when(things.getAll()).thenReturn(List.of(contact, endpoint, poller));
        when(things.getProvider(contact)).thenReturn(owner);
        ItemChannelLink contactLink = new ItemChannelLink("ContactValue", new ChannelUID(contact.getUID(), "contact"));
        when(links.getAll()).thenReturn(List.of(contactLink));
        when(links.getProvider(contactLink)).thenReturn(linkOwner);
        var before = adapter.preview();
        assertTrue(before.fileChanges().isEmpty());
        endpoint.getConfiguration().put("password", "SENTINEL-new-credential");
        var after = adapter.preview();
        assertNotEquals(before.revisionDigest(), after.revisionDigest());
        assertFalse(after.toString().contains("SENTINEL"));
    }

    @Test
    void allManualContactLinkConfigurationDriftInvalidatesReview() {
        var adapter = adapter();
        Thing contact = contact("contact");
        ItemChannelLink contactLink = new ItemChannelLink("ContactValue", new ChannelUID(contact.getUID(), "contact"),
                new Configuration(Map.of("profile", "custom:contact", "ordinary", "SENTINEL-old")));
        when(things.getAll()).thenReturn(List.of(contact, endpoint, poller));
        when(things.getProvider(contact)).thenReturn(owner);
        when(links.getAll()).thenReturn(List.of(contactLink));
        when(links.getProvider(contactLink)).thenReturn(linkOwner);
        var before = adapter.preview();
        assertTrue(before.fileChanges().isEmpty());
        contactLink.getConfiguration().put("ordinary", "SENTINEL-new");
        var after = adapter.preview();
        assertNotEquals(before.revisionDigest(), after.revisionDigest());
        assertFalse(after.toString().contains("SENTINEL"));
    }

    @Test
    void allManualContactProviderDriftInvalidatesReview() {
        var adapter = adapter();
        Thing contact = contact("contact");
        ItemChannelLink contactLink = new ItemChannelLink("ContactValue", new ChannelUID(contact.getUID(), "contact"));
        when(things.getAll()).thenReturn(List.of(contact, endpoint, poller));
        when(things.getProvider(contact)).thenReturn(owner);
        when(links.getAll()).thenReturn(List.of(contactLink));
        when(links.getProvider(contactLink)).thenReturn(linkOwner);
        var before = adapter.preview();
        assertTrue(before.fileChanges().isEmpty());
        when(things.getProvider(contact)).thenReturn(mock(ThingProvider.class));
        assertNotEquals(before.revisionDigest(), adapter.preview().revisionDigest());
    }

    @Test
    void allManualContactPollerConfigurationDriftInvalidatesReview() {
        var adapter = adapter();
        Thing contact = contact("contact");
        ItemChannelLink contactLink = new ItemChannelLink("ContactValue", new ChannelUID(contact.getUID(), "contact"));
        when(things.getAll()).thenReturn(List.of(contact, endpoint, poller));
        when(things.getProvider(contact)).thenReturn(owner);
        when(links.getAll()).thenReturn(List.of(contactLink));
        when(links.getProvider(contactLink)).thenReturn(linkOwner);
        var before = adapter.preview();
        assertTrue(before.fileChanges().isEmpty());
        poller.getConfiguration().put("refresh", 2000);
        assertNotEquals(before.revisionDigest(), adapter.preview().revisionDigest());
    }

    @Test
    void unknownPollerOwnerStillBindsRefusedLinkConfigurationToRevision() {
        var adapter = adapter();
        when(things.getProvider(poller)).thenReturn(null);
        var before = adapter.preview();
        assertTrue(before.fileChanges().isEmpty());
        link.getConfiguration().put("ordinary", "SENTINEL-refused-link");
        var after = adapter.preview();
        assertNotEquals(before.revisionDigest(), after.revisionDigest());
        assertFalse(after.toString().contains("SENTINEL"));
    }

    @Test
    void existingTargetRefusalStillBindsLinkConfigurationToRevision() {
        var adapter = adapter();
        Thing target = BridgeBuilder
                .create(new ThingTypeUID("modbus", "poller2"), new ThingUID("modbus:poller2:plant:holding"))
                .withBridge(endpoint.getUID()).build();
        when(things.get(target.getUID())).thenReturn(target);
        var before = adapter.preview();
        assertTrue(before.fileChanges().isEmpty());
        link.getConfiguration().put("ordinary", "SENTINEL-refused-link");
        var after = adapter.preview();
        assertNotEquals(before.revisionDigest(), after.revisionDigest());
        assertFalse(after.toString().contains("SENTINEL"));
        target.getConfiguration().put("ordinary", "SENTINEL-existing-target");
        var targetDrift = adapter.preview();
        assertNotEquals(after.revisionDigest(), targetDrift.revisionDigest());
        assertFalse(targetDrift.toString().contains("SENTINEL"));
    }

    @Test
    void unknownEndpointOwnerDoesNotGenerateAFileCandidate() {
        var adapter = adapter();
        when(things.getProvider(endpoint)).thenReturn(null);
        var preview = adapter.preview();
        assertTrue(preview.fileChanges().isEmpty());
        assertFalse(preview.manualWork().isEmpty());
        verifyNoInteractions(owner, linkOwner);
    }

    @Test
    void managedLinksAreManualNotProviderFilePatchesAndStillBindRevision() {
        var adapter = adapter();
        when(links.getProvider(link))
                .thenReturn(mock(org.openhab.core.thing.link.ManagedItemChannelLinkProvider.class));
        var before = adapter.preview();
        assertEquals(1, before.fileChanges().size());
        assertTrue(before.fileChanges().getFirst().linkPatches().isEmpty());
        assertTrue(before.manualWork().stream().anyMatch(
                work -> work.sourceUid().equals(link.getLinkedUID().toString()) && work.reason().contains("managed")));
        link.getConfiguration().put("ordinary", "SENTINEL-managed-profile");
        var after = adapter.preview();
        assertNotEquals(before.revisionDigest(), after.revisionDigest());
        assertFalse(after.toString().contains("SENTINEL"));
    }

    @Test
    void partialCandidateKeepsLegacyPollingForContactTransformedAndManagedConsumers() {
        var adapter = adapter();
        Thing contact = contact("contact");
        Thing transformed = number("transformed", poller.getUID());
        transformed.getConfiguration().put("readTransform", "JS(SENTINEL-transform)");
        Thing managed = number("managed", poller.getUID());
        when(things.getAll()).thenReturn(List.of(managed, data, contact, transformed, endpoint, poller));
        when(things.getProvider(contact)).thenReturn(owner);
        when(things.getProvider(transformed)).thenReturn(owner);
        when(things.getProvider(managed)).thenReturn(mock(org.openhab.core.thing.ManagedThingProvider.class));
        ItemChannelLink contactLink = new ItemChannelLink("ContactValue", new ChannelUID(contact.getUID(), "contact"));
        when(links.getAll()).thenReturn(List.of(link, contactLink));
        when(links.getProvider(contactLink)).thenReturn(linkOwner);
        var preview = adapter.preview();
        assertEquals(1, preview.fileChanges().size());
        var change = preview.fileChanges().getFirst();
        assertEquals(1, change.linkPatches().size());
        for (Thing residual : List.of(contact, transformed, managed)) {
            assertTrue(
                    preview.manualWork().stream()
                            .anyMatch(work -> work.sourceUid().equals(residual.getUID().toString())),
                    residual.getUID().toString());
            assertTrue(change.instructions().contains(residual.getUID().toString()), change.instructions());
        }
        assertTrue(change.instructions().contains("Keep legacy polling online"), change.instructions());
        assertTrue(change.instructions().contains("ALL remaining legacy consumers"), change.instructions());
        assertFalse(change.instructions().contains("Disable legacy polling only after successful cutover"));
        assertFalse(preview.toString().contains("SENTINEL"));
    }

    @Test
    void fileOwnedDataOutsideEligiblePollersIsInventoriedAndBindsConfigurationAndProfiles() {
        var adapter = adapter();
        Thing managedPoller = BridgeBuilder
                .create(poller.getThingTypeUID(), new ThingUID("modbus:poller:plant:managed"))
                .withBridge(endpoint.getUID()).withConfiguration(poller.getConfiguration()).build();
        Thing managedChild = number("managedParent", managedPoller.getUID());
        Thing missingChild = number("missingParent", new ThingUID("modbus:poller:plant:missing"));
        Thing nonPollerChild = number("nonPollerParent", endpoint.getUID());
        when(things.getAll()).thenReturn(List.of(endpoint, managedPoller, managedChild, missingChild, nonPollerChild));
        when(things.getProvider(managedPoller)).thenReturn(mock(org.openhab.core.thing.ManagedThingProvider.class));
        for (Thing child : List.of(managedChild, missingChild, nonPollerChild)) {
            when(things.getProvider(child)).thenReturn(owner);
        }
        ItemChannelLink orphanLink = new ItemChannelLink("OrphanValue", new ChannelUID(missingChild.getUID(), "number"),
                new Configuration(Map.of("profile", "custom:orphan", "ordinary", "SENTINEL-profile")));
        when(links.getAll()).thenReturn(List.of(orphanLink));
        when(links.getProvider(orphanLink)).thenReturn(linkOwner);
        var before = adapter.preview();
        assertTrue(before.fileChanges().isEmpty());
        for (Thing child : List.of(managedChild, missingChild, nonPollerChild)) {
            assertTrue(
                    before.manualWork().stream().anyMatch(work -> work.sourceUid().equals(child.getUID().toString())),
                    child.getUID().toString());
        }
        assertEquals(before, adapter.preview());
        missingChild.getConfiguration().put("ordinary", "SENTINEL-data");
        var configurationDrift = adapter.preview();
        assertNotEquals(before.revisionDigest(), configurationDrift.revisionDigest());
        orphanLink.getConfiguration().put("ordinary", "SENTINEL-profile-drift");
        var profileDrift = adapter.preview();
        assertNotEquals(configurationDrift.revisionDigest(), profileDrift.revisionDigest());
        assertFalse(profileDrift.toString().contains("SENTINEL"));
        when(things.getAll()).thenReturn(List.of(nonPollerChild, missingChild, managedChild, managedPoller, endpoint));
        assertEquals(profileDrift, adapter.preview());
    }

    private Thing contact(String id) {
        return ThingBuilder.create(data.getThingTypeUID(), new ThingUID("modbus:data:plant:holding:" + id))
                .withBridge(poller.getUID())
                .withConfiguration(new Configuration(Map.of("readStart", "100.0", "readValueType", "bit")))
                .withChannel(ChannelBuilder.create(new ChannelUID("modbus:data:plant:holding:" + id + ":contact"))
                        .withAcceptedItemType("Contact").build())
                .build();
    }

    private Thing number(String id, ThingUID parent) {
        return ThingBuilder.create(data.getThingTypeUID(), new ThingUID("modbus:data:plant:holding:" + id))
                .withBridge(parent)
                .withConfiguration(new Configuration(Map.of("readStart", "100", "readValueType", "int16")))
                .withChannel(ChannelBuilder.create(new ChannelUID("modbus:data:plant:holding:" + id + ":number"))
                        .withAcceptedItemType("Number").build())
                .build();
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
