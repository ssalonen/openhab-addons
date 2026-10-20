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

import static org.openhab.binding.modbus.internal.ModbusBindingConstantsInternal.THING_TYPE_MODBUS_DATA;
import static org.openhab.binding.modbus.internal.ModbusBindingConstantsInternal.THING_TYPE_MODBUS_POLLER;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.common.registry.AbstractRegistry;
import org.openhab.core.common.registry.ManagedProvider;
import org.openhab.core.common.registry.Provider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;

/**
 * Reads provider-owned legacy topology only. Never invokes provider mutations or reads source files.
 * Non-managed providers are not necessarily file providers: the user must confirm their authoritative source.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public final class ProviderOwnedMigrationAdapter {
    private static final String UNKNOWN_LOCATION = "UNKNOWN_MANUAL_LOCATION";
    private final ThingRegistry things;
    private final ItemChannelLinkRegistry links;

    public ProviderOwnedMigrationAdapter(ThingRegistry things, ItemChannelLinkRegistry links) {
        this.things = things;
        this.links = links;
    }

    public ProviderOwnedMigrationPreview preview() {
        var allThings = things.getAll().stream().sorted(Comparator.comparing(thing -> thing.getUID().toString()))
                .toList();
        var allLinks = links.getAll().stream().sorted(Comparator.comparing(ItemChannelLink::getUID)).toList();
        List<ProviderOwnedMigrationPreview.FileChange> changes = new ArrayList<>();
        List<ManualMigrationWork> manual = new ArrayList<>();
        // Capture the complete inventory before eligibility checks: refused work is still reviewed state.
        var inventory = new java.util.TreeMap<String, ProviderOwnedMigrationPreview.SourceRevision>();
        for (Thing thing : allThings) {
            if (THING_TYPE_MODBUS_POLLER.equals(thing.getThingTypeUID())
                    || THING_TYPE_MODBUS_DATA.equals(thing.getThingTypeUID())) {
                inventory.put(thing.getUID().toString(), source(thing));
                if (THING_TYPE_MODBUS_POLLER.equals(thing.getThingTypeUID())) {
                    Thing target = things.get(new ThingUID(thing.getUID().toString().replace(":poller:", ":poller2:")));
                    if (target != null) {
                        inventory.put(target.getUID().toString(), source(target));
                    }
                }
                ThingUID parentUid = thing.getBridgeUID();
                Thing parent = null;
                if (parentUid != null) {
                    for (Thing candidate : allThings) {
                        if (candidate.getUID().equals(parentUid)) {
                            parent = candidate;
                            break;
                        }
                    }
                    if (parent == null) {
                        parent = things.get(parentUid);
                    }
                }
                if (parent != null) {
                    inventory.put(parent.getUID().toString(), source(parent));
                }
                if (THING_TYPE_MODBUS_DATA.equals(thing.getThingTypeUID())
                        && !(provider(thing) instanceof ManagedProvider)
                        && (parent == null || !THING_TYPE_MODBUS_POLLER.equals(parent.getThingTypeUID())
                                || provider(parent) instanceof ManagedProvider)) {
                    manual.add(new ManualMigrationWork(thing.getUID().toString(),
                            "file-owned or unknown data parent is managed, missing or not a legacy poller; manual migration required",
                            source(thing).revisionDigest()));
                }
            }
        }
        var inventoryLinks = allLinks.stream()
                .filter(link -> inventory.containsKey(link.getLinkedUID().getThingUID().toString()))
                .map(link -> List.of(link.getUID(), identity(links.getProvider(link)), linkDigest(link))).toList();
        String inventoryDigest = ModbusMigrationPlanner.configurationIdentity(List.of(inventory.values().stream()
                .map(source -> List.of(source.sourceUid(), source.providerIdentity(), source.revisionDigest()))
                .toList(), inventoryLinks));
        for (Thing poller : allThings) {
            if (!THING_TYPE_MODBUS_POLLER.equals(poller.getThingTypeUID())) {
                continue;
            }
            Provider<Thing> owner = provider(poller);
            if (owner instanceof ManagedProvider) {
                continue;
            }
            ThingUID endpointUid = poller.getBridgeUID();
            Thing endpoint = endpointUid == null ? null : things.get(endpointUid);
            List<ProviderOwnedMigrationPreview.SourceRevision> sources = new ArrayList<>();
            sources.add(source(poller));
            if (endpoint != null) {
                sources.add(source(endpoint));
            }
            List<LegacyData> children = new ArrayList<>();
            for (Thing data : allThings) {
                if (!THING_TYPE_MODBUS_DATA.equals(data.getThingTypeUID())
                        || !poller.getUID().equals(data.getBridgeUID())) {
                    continue;
                }
                sources.add(source(data));
                if (provider(data) == null || provider(data) instanceof ManagedProvider) {
                    manual.add(new ManualMigrationWork(data.getUID().toString(),
                            "child provider is unknown or managed; review ownership separately",
                            source(data).revisionDigest()));
                    continue;
                }
                List<LegacyLink> sourceLinks = new ArrayList<>();
                for (ItemChannelLink link : allLinks) {
                    if (data.getUID().equals(link.getLinkedUID().getThingUID())) {
                        var channel = data.getChannel(link.getLinkedUID().getId());
                        String itemType = channel == null ? null : channel.getAcceptedItemType();
                        sourceLinks.add(new LegacyLink(link.getItemName(), link.getLinkedUID().toString(),
                                itemType == null ? "" : itemType, link.getConfiguration().getProperties()));
                    }
                }
                children.add(new LegacyData(data.getUID().toString(), poller.getUID().toString(),
                        data.getConfiguration().getProperties(), sourceLinks));
            }
            sources.sort(Comparator.comparing(ProviderOwnedMigrationPreview.SourceRevision::sourceUid));
            String sourceDigest = ModbusMigrationPlanner.configurationIdentity(sources.stream()
                    .map(source -> List.of(source.sourceUid(), source.providerIdentity(), source.revisionDigest()))
                    .toList());
            String targetUid = poller.getUID().toString().replace(":poller:", ":poller2:");
            if (owner == null || endpoint == null || provider(endpoint) == null
                    || things.get(new ThingUID(targetUid)) != null) {
                manual.add(new ManualMigrationWork(poller.getUID().toString(),
                        "provider or endpoint unknown, or target exists; no file candidate generated", sourceDigest));
                continue;
            }
            var plan = new ModbusMigrationPlanner()
                    .preview(
                            List.of(new LegacyPoller(poller.getUID().toString(), endpoint.getUID().toString(),
                                    poller.getConfiguration().getProperties(), source(endpoint).revisionDigest())),
                            children);
            manual.addAll(plan.manualWork());
            for (MigrationGroup group : plan.groups()) {
                List<ProviderOwnedMigrationPreview.LinkPatch> patches = new ArrayList<>();
                for (MigrationLink mapping : group.links()) {
                    ItemChannelLink original = allLinks.stream()
                            .filter(link -> mapping.itemName().equals(link.getItemName())
                                    && mapping.sourceChannelUid().equals(link.getLinkedUID().toString()))
                            .findFirst().orElseThrow();
                    Provider<ItemChannelLink> linkProvider = links.getProvider(original);
                    if (linkProvider == null || linkProvider instanceof ManagedProvider) {
                        manual.add(new ManualMigrationWork(mapping.sourceChannelUid(),
                                "link provider unknown or managed; independent manual link work required; no source-link patch generated",
                                linkDigest(original)));
                    } else {
                        patches.add(new ProviderOwnedMigrationPreview.LinkPatch(mapping.itemName(),
                                mapping.sourceChannelUid(), mapping.targetChannelUid(), identity(linkProvider),
                                UNKNOWN_LOCATION, linkDigest(original), mapping.configuration()));
                    }
                }
                // Include refused/nondefault links and provider ownership in revision identity, without exposing
                // values.
                var linkRevisions = allLinks.stream()
                        .filter(link -> sources.stream().anyMatch(
                                data -> data.sourceUid().equals(link.getLinkedUID().getThingUID().toString())))
                        .map(link -> List.of(link.getUID(), identity(links.getProvider(link)), linkDigest(link)))
                        .toList();
                String revision = ModbusMigrationPlanner.configurationIdentity(List.of("provider-owned-review-v2",
                        inventoryDigest, sourceDigest, plan.identity(), linkRevisions));
                var residual = new java.util.TreeSet<String>();
                for (Thing data : allThings) {
                    if (THING_TYPE_MODBUS_DATA.equals(data.getThingTypeUID())
                            && poller.getUID().equals(data.getBridgeUID())) {
                        if (group.channels().stream()
                                .noneMatch(channel -> channel.sourceDataUid().equals(data.getUID().toString()))) {
                            residual.add(data.getUID().toString());
                        }
                        allLinks.stream().filter(link -> data.getUID().equals(link.getLinkedUID().getThingUID()))
                                .filter(link -> patches.stream()
                                        .noneMatch(patch -> patch.itemName().equals(link.getItemName())
                                                && patch.sourceChannelUid().equals(link.getLinkedUID().toString())))
                                .forEach(link -> residual.add(link.getItemName() + " -> " + link.getLinkedUID()));
                    }
                }
                changes.add(new ProviderOwnedMigrationPreview.FileChange(group.sourcePollerUid(),
                        group.targetPollerUid(), revision, sources, plan.yaml(), patches, instructions(residual)));
            }
        }
        manual.sort(Comparator.comparing(ManualMigrationWork::sourceUid).thenComparing(ManualMigrationWork::reason));
        String revision = ModbusMigrationPlanner.configurationIdentity(List.of("provider-owned-review-v2",
                inventoryDigest,
                changes.stream().map(change -> List.of(change.sourcePollerUid(), change.revisionDigest())).toList(),
                manual.stream().map(work -> List.of(work.sourceUid(), work.reason(), work.configurationIdentity()))
                        .toList()));
        return new ProviderOwnedMigrationPreview(revision, changes, manual);
    }

    // ThingRegistry does not declare ownership. Core's runtime registry inherits this public AbstractRegistry API.
    // Other implementations fail closed; no reflection or dependency on Core's internal implementation is needed.
    @SuppressWarnings("unchecked")
    private @Nullable Provider<Thing> provider(Thing thing) {
        if (things instanceof AbstractRegistry<?, ?, ?> registry) {
            return ((AbstractRegistry<Thing, ThingUID, ?>) registry).getProvider(thing);
        }
        return null;
    }

    private ProviderOwnedMigrationPreview.SourceRevision source(Thing thing) {
        var bridge = thing.getBridgeUID();
        var channels = thing.getChannels().stream().sorted(Comparator.comparing(channel -> channel.getUID().toString()))
                .map(channel -> List.of(channel.getUID().toString(),
                        java.util.Objects.requireNonNullElse(channel.getAcceptedItemType(), ""),
                        channel.getConfiguration().getProperties()))
                .toList();
        String digest = ModbusMigrationPlanner.configurationIdentity(List.of(thing.getThingTypeUID().toString(),
                bridge == null ? "" : bridge.toString(), thing.getConfiguration().getProperties(), channels));
        return new ProviderOwnedMigrationPreview.SourceRevision(thing.getUID().toString(), identity(provider(thing)),
                UNKNOWN_LOCATION, digest);
    }

    private static String identity(@Nullable Provider<?> provider) {
        return provider == null ? "UNKNOWN_PROVIDER" : provider.getClass().getName();
    }

    private static String linkDigest(ItemChannelLink link) {
        return ModbusMigrationPlanner.configurationIdentity(
                List.of(link.getItemName(), link.getLinkedUID().toString(), link.getConfiguration().getProperties()));
    }

    private static String instructions(java.util.Set<String> residual) {
        return """
                MANUAL PROVIDER-OWNED REVIEW ONLY; never pass this artifact to managed apply/cutover/revert or REST writes.
                Confirm the provider is file-backed and locate/back up its authoritative Thing and Item link sources manually.
                UNKNOWN_MANUAL_LOCATION means the public registry API does not expose a source filepath; no filename is inferred.
                Re-capture and compare revision digests before editing. Digests describe registry-visible configuration, not file bytes.
                Stage: merge the native YAML candidate into an operator-selected YAML file, keeping the existing endpoint unchanged.
                The candidate has no Item links. Keep legacy Things/links live and verify the candidate after provider reload.
                Compare fresh device readings, window edges, indexes and multiregister boundaries before a manual cutover.
                Cutover: use each linkPatches entry to remove the old source channel link and add the target channel link
                in the authoritative provider source, retaining the exact listed default profile configuration.
                Reload and verify one link per Item, no simultaneous old/new delivery. Do not use managed-provider or REST mutations.
                Revert: remove the target link and restore the source channel link and listed profile from linkPatches;
                reload and verify source delivery before removing an unlinked candidate. Retain legacy Things for rollback.
                Keep legacy polling online until ALL remaining legacy consumers are independently resolved and verified.
                Cutover of the eligible subset does not authorize disabling the whole legacy poller.
                Deletion is a separate backed-up cleanup action.
                Review Item/source/target identifiers in rules, scripts, widgets, groups, tags, dynamic and external references.
                No scripts, widgets, rules or nondefault profiles are rewritten. Refused work requires independent review.
                """
                + "Residual legacy consumers requiring independent resolution: "
                + (residual.isEmpty() ? "none identified in the registry; verify external consumers manually"
                        : String.join(", ", residual))
                + "\n";
    }
}
