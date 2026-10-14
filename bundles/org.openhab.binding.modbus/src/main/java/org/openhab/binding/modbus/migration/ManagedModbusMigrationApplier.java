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

import static org.openhab.binding.modbus.internal.ModbusBindingConstantsInternal.THING_TYPE_MODBUS_POLLER2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.link.ManagedItemChannelLinkProvider;

/**
 * Stages unlinked managed candidates and journals explicit link switches in Core storage for recovery.
 * 
 * @author Sami Salonen - Unlinked staging and explicit link cutover
 * @author Sami Salonen - Durable ownership and precise link recovery
 * @author Sami Salonen - Authoritative compensation and registry-aware construction
 * @author Sami Salonen - Per-link cutover checks and exact active-link recovery
 */
@NonNullByDefault
public final class ManagedModbusMigrationApplier implements MigrationApplier {
    /**
     * Signals that staging left owned targets which must be removed before another attempt.
     * 
     * @author Sami Salonen - Retryable staging recovery
     */
    static final class IncompleteStageException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        IncompleteStageException(RuntimeException cause) {
            super("Stage compensation incomplete; retry rollback", cause);
        }
    }

    private final MigrationJournal journal;
    private final @Nullable ThingRegistry thingRegistry;
    private static final String OWNER = "modbusMigrationIdentity";
    private final ManagedThingProvider thingProvider;
    private final ItemChannelLinkRegistry linkRegistry;
    private final Map<String, ItemChannelLink> activeTargetLinks = new HashMap<>();

    /**
     * Creates a runtime-local applier without authoritative Thing ownership checks.
     * Staging is refused before any journal or provider mutation.
     *
     * @deprecated Use the registry-aware constructor, or {@link ModbusMigrationService} for durable production
     *             recovery.
     */
    @Deprecated
    public ManagedModbusMigrationApplier(ManagedThingProvider thingProvider, ItemChannelLinkRegistry linkRegistry) {
        this(thingProvider, linkRegistry, new MigrationJournal());
    }

    /**
     * Creates an applier with authoritative Thing ownership checks and a runtime-local journal.
     * Keep this instance for cutover and rollback; its journal does not survive reconstruction or restart.
     * Prefer {@link ModbusMigrationService} as the shared, storage-backed production boundary.
     */
    public ManagedModbusMigrationApplier(ManagedThingProvider thingProvider, ItemChannelLinkRegistry linkRegistry,
            ThingRegistry thingRegistry) {
        this(thingProvider, linkRegistry, new MigrationJournal(), thingRegistry);
    }

    ManagedModbusMigrationApplier(ManagedThingProvider thingProvider, ItemChannelLinkRegistry linkRegistry,
            MigrationJournal journal) {
        this(thingProvider, linkRegistry, journal, null);
    }

    ManagedModbusMigrationApplier(ManagedThingProvider thingProvider, ItemChannelLinkRegistry linkRegistry,
            MigrationJournal journal, @Nullable ThingRegistry thingRegistry) {
        this.journal = journal;
        this.thingRegistry = thingRegistry;
        this.thingProvider = thingProvider;
        this.linkRegistry = linkRegistry;
    }

    @Override
    public void createTargets(MigrationPreview preview) {
        if (thingRegistry == null) {
            throw new IllegalStateException("Staging requires an authoritative ThingRegistry");
        }
        journal.manifest(preview);
        List<String> createdThings = new ArrayList<>(journal.targets(preview.identity()));
        if (!createdThings.isEmpty()) {
            throw new IncompleteStageException(new IllegalStateException("Existing target journal"));
        }
        try {
            for (MigrationGroup group : preview.groups()) {
                ThingUID targetUid = new ThingUID(group.targetPollerUid());
                ThingRegistry registry = thingRegistry;
                if (thingProvider.get(targetUid) != null || (registry != null && registry.get(targetUid) != null)) {
                    throw new IllegalStateException("refusing to overwrite existing target " + targetUid);
                }
                BridgeBuilder builder = BridgeBuilder.create(THING_TYPE_MODBUS_POLLER2, targetUid)
                        .withBridge(new ThingUID(group.targetBridgeUid()))
                        .withConfiguration(new Configuration(group.configuration()))
                        .withProperties(Map.of(OWNER, preview.identity()));
                group.channels().forEach(channel -> builder.withChannel(ChannelBuilder
                        .create(new ChannelUID(targetUid, channel.id())).withAcceptedItemType(channel.itemType())
                        .withConfiguration(new Configuration(channel.configuration())).build()));
                // Provider listeners may throw after the add has already changed managed state.
                createdThings.add(targetUid.toString());
                journal.targets(preview.identity(), createdThings);
                thingProvider.add(builder.build());

            }
        } catch (RuntimeException e) {
            try {
                remove(preview.identity(), createdThings);
            } catch (RuntimeException compensationFailure) {
                e.addSuppressed(compensationFailure);
                throw new IncompleteStageException(e);
            }
            throw e;
        }
    }

    @Override
    public void removeTargets(MigrationPreview preview) {
        for (MigrationGroup group : preview.groups()) {
            restore(preview.identity() + ":" + group.sourcePollerUid());
        }
        remove(preview.identity(), new ArrayList<>(journal.targets(preview.identity())));
    }

    private void remove(String identity, List<String> things) {
        List<RuntimeException> failures = new ArrayList<>();
        for (String thing : new ArrayList<>(things).reversed()) {
            try {
                ThingUID uid = new ThingUID(thing);
                var current = thingProvider.get(uid);
                ThingRegistry registry = thingRegistry;
                if ((current != null || (registry != null && registry.get(uid) != null)) && (registry == null
                        || !ManagedModbusMigrationAdapter.ownsTarget(registry, thingProvider, uid, identity))) {
                    throw new IllegalStateException("Refusing removal of target with unknown ownership " + uid);
                }
                thingProvider.remove(uid);
                things.remove(thing);
                journal.targets(identity, things);
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            IllegalStateException failure = new IllegalStateException(
                    "Migration target removal failed; retry rollback");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    @Override
    public void cutover(MigrationPreview preview, MigrationGroup group) {
        requireOwnedTarget(preview, group);
        String key = preview.identity() + ":" + group.sourcePollerUid();
        if (!journal.links(key).isEmpty()) {
            throw new IllegalStateException("Existing cutover journal; rollback before retry");
        }
        Map<String, ItemChannelLink> reviewedSources = new HashMap<>();
        for (MigrationLink link : group.links()) {
            if (!(link.configuration().isEmpty() || link.configuration().equals(Map.of("profile", "system:default")))) {
                throw new IllegalStateException("Non-default profiles require manual migration");
            }
            ItemChannelLink source = linkRegistry.get(sourceLink(link).getUID());
            if (source == null || !source.getConfiguration().getProperties().equals(link.configuration())) {
                throw new IllegalStateException("Legacy link changed since review");
            }
            if (!(linkRegistry.getProvider(source) instanceof ManagedItemChannelLinkProvider)) {
                throw new IllegalStateException(
                        "Legacy link is not managed; provider-owned changes require manual work");
            }
            if (linkRegistry.get(targetLink(link).getUID()) != null) {
                throw new IllegalStateException("Refusing existing target link");
            }
            reviewedSources.put(source.getUID(), source);
        }
        List<MigrationLink> journal = new ArrayList<>();
        this.journal.manifest(preview);
        try {
            for (MigrationLink link : group.links()) {
                requireOwnedTarget(preview, group);
                ItemChannelLink source = Objects.requireNonNull(reviewedSources.get(sourceLink(link).getUID()));
                requireCurrentSource(link, source);
                journal.add(link);
                this.journal.links(key, journal);
                requireOwnedTarget(preview, group);
                requireCurrentSource(link, source);
                linkRegistry.remove(sourceLink(link).getUID());
                // Provider callbacks can replace a Thing during either link mutation.
                requireOwnedTarget(preview, group);
                if (linkRegistry.get(source.getUID()) != null || linkRegistry.get(targetLink(link).getUID()) != null) {
                    throw new IllegalStateException("Link changed during cutover removal");
                }
                ItemChannelLink replacement = targetLink(link);
                activeTargetLinks.put(replacement.getUID(), replacement);
                linkRegistry.add(replacement);
                requireOwnedTarget(preview, group);
                for (MigrationLink switched : journal) {
                    requireSwitchedLink(switched,
                            Objects.requireNonNull(activeTargetLinks.get(targetLink(switched).getUID())));
                }
            }
        } catch (RuntimeException failure) {
            try {
                restore(key);
            } catch (RuntimeException compensationFailure) {
                failure.addSuppressed(compensationFailure);
            }
            throw failure;
        }
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals") // A listener replacement is not the captured source instance.
    private void requireCurrentSource(MigrationLink link, ItemChannelLink expected) {
        ItemChannelLink source = linkRegistry.get(expected.getUID());
        if (source != expected || !expected.getConfiguration().getProperties().equals(link.configuration())
                || !(linkRegistry.getProvider(expected) instanceof ManagedItemChannelLinkProvider)
                || linkRegistry.get(targetLink(link).getUID()) != null) {
            throw new IllegalStateException("Source or target link changed before cutover mutation");
        }
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals") // Read back the exact link just added, not a listener
                                                      // replacement.
    private void requireSwitchedLink(MigrationLink link, ItemChannelLink expected) {
        if (linkRegistry.get(sourceLink(link).getUID()) != null || linkRegistry.get(expected.getUID()) != expected
                || !expected.getConfiguration().getProperties().equals(link.configuration())
                || !(linkRegistry.getProvider(expected) instanceof ManagedItemChannelLinkProvider)) {
            throw new IllegalStateException("Link changed during cutover publication");
        }
    }

    private void requireOwnedTarget(MigrationPreview preview, MigrationGroup group) {
        ThingRegistry registry = thingRegistry;
        if (!journal.targets(preview.identity()).contains(group.targetPollerUid()) || registry == null
                || !ManagedModbusMigrationAdapter.ownsTarget(registry, thingProvider,
                        new ThingUID(group.targetPollerUid()), preview.identity())) {
            throw new IllegalStateException("Staged target ownership changed since review");
        }
    }

    @Override
    public void revertLink(MigrationPreview preview, MigrationLink link) {
        MigrationGroup group = preview.groups().stream().filter(candidate -> candidate.links().contains(link))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown reviewed link"));
        restore(preview.identity() + ":" + group.sourcePollerUid(), List.of(link));
    }

    private ItemChannelLink sourceLink(MigrationLink link) {
        return new ItemChannelLink(link.itemName(), new ChannelUID(link.sourceChannelUid()),
                new Configuration(link.configuration()));
    }

    private ItemChannelLink targetLink(MigrationLink link) {
        return new ItemChannelLink(link.itemName(), new ChannelUID(link.targetChannelUid()),
                new Configuration(link.configuration()));
    }

    private void restore(String key) {
        restore(key, this.journal.links(key));
    }

    private void restore(String key, List<MigrationLink> selected) {
        List<MigrationLink> journal = new ArrayList<>(this.journal.links(key));
        if (journal.isEmpty()) {
            return;
        }
        for (MigrationLink link : new ArrayList<>(journal).reversed()) {
            if (!selected.contains(link)) {
                continue;
            }
            ItemChannelLink target = linkRegistry.get(targetLink(link).getUID());
            ItemChannelLink source = linkRegistry.get(sourceLink(link).getUID());
            if ((target != null && !target.getConfiguration().getProperties().equals(link.configuration()))
                    || (source != null && !source.getConfiguration().getProperties().equals(link.configuration()))) {
                throw new IllegalStateException("Refusing to overwrite a link changed since migration");
            }
            if (target != null && !(linkRegistry.getProvider(target) instanceof ManagedItemChannelLinkProvider)) {
                throw new IllegalStateException("Refusing removal of unmanaged replacement link");
            }
            ItemChannelLink expectedTarget = activeTargetLinks.get(targetLink(link).getUID());
            if (target != null && expectedTarget != null && !sameTarget(link, expectedTarget)) {
                throw new IllegalStateException("Refusing replacement of the active cutover target link");
            }
            requireManagedSource(link, source);
            // Removal callbacks can replace either link; a successful provider return is not proof of absence.
            linkRegistry.remove(targetLink(link).getUID());
            requireRestoredState(link, source);
            if (source == null) {
                ItemChannelLink restored = sourceLink(link);
                try {
                    linkRegistry.add(restored);
                    requireRestoredState(link, restored);
                } catch (RuntimeException failure) {
                    // Compensate only our own unchanged source, never a listener's replacement.
                    if (sameSource(link, restored)
                            && restored.getConfiguration().getProperties().equals(link.configuration())
                            && linkRegistry.getProvider(restored) instanceof ManagedItemChannelLinkProvider) {
                        try {
                            linkRegistry.remove(restored.getUID());
                        } catch (RuntimeException compensationFailure) {
                            failure.addSuppressed(compensationFailure);
                        }
                    }
                    throw failure;
                }
            }
            journal.remove(link);
            this.journal.links(key, journal);
            activeTargetLinks.remove(targetLink(link).getUID());
        }
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals") // Active ownership is tied to the link actually added.
    private boolean sameTarget(MigrationLink link, ItemChannelLink expectedTarget) {
        return linkRegistry.get(targetLink(link).getUID()) == expectedTarget;
    }

    private void requireRestoredState(MigrationLink link, @Nullable ItemChannelLink expectedSource) {
        ItemChannelLink source = linkRegistry.get(sourceLink(link).getUID());
        if (linkRegistry.get(targetLink(link).getUID()) != null || !sameSource(link, expectedSource)) {
            throw new IllegalStateException("Link changed during migration recovery; retry rollback");
        }
        requireManagedSource(link, source);
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals") // Equal link contents do not authorize a replacement instance.
    private boolean sameSource(MigrationLink link, @Nullable ItemChannelLink expectedSource) {
        return linkRegistry.get(sourceLink(link).getUID()) == expectedSource;
    }

    private void requireManagedSource(MigrationLink link, @Nullable ItemChannelLink source) {
        if (source != null && (!source.getConfiguration().getProperties().equals(link.configuration())
                || !(linkRegistry.getProvider(source) instanceof ManagedItemChannelLinkProvider))) {
            throw new IllegalStateException("Refusing changed or unmanaged legacy link");
        }
    }

    @Override
    public void removeLegacy(MigrationPreview preview) {
        throw new UnsupportedOperationException("legacy Things are never removed by this migration console");
    }
}
