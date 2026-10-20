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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

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
    private @Nullable ManagedItemChannelLinkProvider expectedLinkProvider;
    private final Map<String, ItemChannelLink> activeTargetLinks = new HashMap<>();
    private final Set<String> knownNotAdded = new HashSet<>();

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
        this(thingProvider, linkRegistry, journal, thingRegistry, null);
    }

    ManagedModbusMigrationApplier(ManagedThingProvider thingProvider, ItemChannelLinkRegistry linkRegistry,
            MigrationJournal journal, @Nullable ThingRegistry thingRegistry,
            @Nullable ManagedItemChannelLinkProvider linkProvider) {
        this.expectedLinkProvider = linkProvider;
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
        cutover(preview, group, () -> true);
    }

    @Override
    public void cutover(MigrationPreview preview, MigrationGroup group, BooleanSupplier authorization) {
        cutover(preview, group, authorization, () -> {
        });
    }

    @Override
    public void cutover(MigrationPreview preview, MigrationGroup group, BooleanSupplier authorization,
            Runnable completion) {
        requireReadAuthorization(authorization);
        requireOwnedTarget(preview, group);
        String key = preview.identity() + ":" + group.sourcePollerUid();
        if (!journal.links(key).isEmpty()) {
            throw new IllegalStateException("Existing cutover journal; rollback before retry");
        }
        Map<String, ItemChannelLink> reviewedSources = new HashMap<>();
        for (MigrationLink link : group.links()) {
            if (!link.markedDefaultTarget()) {
                throw new IllegalStateException("Unmarked target plan requires a new review; no automatic cutover");
            }
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
            // Runtime-local constructors learn the owner from the reviewed source. Durable recovery injects it.
            if (expectedLinkProvider == null) {
                expectedLinkProvider = (ManagedItemChannelLinkProvider) linkRegistry.getProvider(source);
            }
            if (!expectedManagedOwner(source)) {
                throw new IllegalStateException("Legacy link managed owner changed since review");
            }
            reviewedSources.put(source.getUID(), source);
        }
        List<MigrationJournal.LinkIntent> journal = new ArrayList<>();
        this.journal.manifest(preview);
        try {
            for (MigrationLink link : group.links()) {
                requireReadAuthorization(authorization);
                requireOwnedTarget(preview, group);
                ItemChannelLink source = Objects.requireNonNull(reviewedSources.get(sourceLink(link).getUID()));
                requireCurrentSource(link, source);
                // Install before any write-ahead callback can introduce a target we never added.
                knownNotAdded.add(targetLink(link).getUID());
                journal.add(new MigrationJournal.LinkIntent(link, MigrationJournal.LinkPhase.REMOVE_INTENDED));
                this.journal.intents(key, journal);
                requireReadAuthorization(authorization);
                requireOwnedTarget(preview, group);
                requireCurrentSource(link, source);
                linkRegistry.remove(sourceLink(link).getUID());
                // Provider callbacks can replace a Thing during either link mutation.
                requireReadAuthorization(authorization);
                requireOwnedTarget(preview, group);
                if (linkRegistry.get(source.getUID()) != null || linkRegistry.get(targetLink(link).getUID()) != null) {
                    throw new IllegalStateException("Link changed during cutover removal");
                }
                ItemChannelLink replacement = targetLink(link);
                advance(key, journal, link, MigrationJournal.LinkPhase.SOURCE_REMOVED);
                requireReadAuthorization(authorization);
                requireOwnedTarget(preview, group);
                requireRestoredState(link, null);
                advance(key, journal, link, MigrationJournal.LinkPhase.ADD_ATTEMPTED);
                requireReadAuthorization(authorization);
                requireOwnedTarget(preview, group);
                requireRestoredState(link, null);
                activeTargetLinks.put(replacement.getUID(), replacement);
                knownNotAdded.remove(replacement.getUID());
                linkRegistry.add(replacement);
                requireReadAuthorization(authorization);
                requireOwnedTarget(preview, group);
                for (MigrationJournal.LinkIntent intent : journal) {
                    MigrationLink switched = intent.link();
                    requireSwitchedLink(switched,
                            Objects.requireNonNull(activeTargetLinks.get(targetLink(switched).getUID())));
                }
                advance(key, journal, link, MigrationJournal.LinkPhase.ADD_CONFIRMED);
                requireReadAuthorization(authorization);
                requireOwnedTarget(preview, group);
                for (MigrationJournal.LinkIntent intent : journal) {
                    requireSwitchedLink(intent.link(),
                            Objects.requireNonNull(activeTargetLinks.get(targetLink(intent.link()).getUID())));
                }
            }
            // Workflow persistence can synchronously invoke provider/storage callbacks too.
            completion.run();
            requireReadAuthorization(authorization);
            requireOwnedTarget(preview, group);
            for (MigrationLink link : group.links()) {
                requireSwitchedLink(link, Objects.requireNonNull(activeTargetLinks.get(targetLink(link).getUID())));
            }
            // After the whole switch completes, an unchanged UI save retains the reviewed marker claim.
            // Keep exact instances only while mutation/compensation is in progress.
            for (MigrationLink link : group.links()) {
                activeTargetLinks.remove(targetLink(link).getUID());
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

    private void requireReadAuthorization(BooleanSupplier authorization) {
        if (!authorization.getAsBoolean()) {
            throw new IllegalStateException("Migration read authorization expired");
        }
    }

    private void advance(String key, List<MigrationJournal.LinkIntent> intents, MigrationLink link,
            MigrationJournal.LinkPhase phase) {
        intents.set(intents.size() - 1, new MigrationJournal.LinkIntent(link, phase));
        journal.intents(key, intents);
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals") // A listener replacement is not the captured source instance.
    private void requireCurrentSource(MigrationLink link, ItemChannelLink expected) {
        ItemChannelLink source = linkRegistry.get(expected.getUID());
        if (source != expected || !expected.getConfiguration().getProperties().equals(link.configuration())
                || !expectedManagedOwner(expected) || linkRegistry.get(targetLink(link).getUID()) != null) {
            throw new IllegalStateException("Source or target link changed before cutover mutation");
        }
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals") // Read back the exact link just added, not a listener
                                                      // replacement.
    private void requireSwitchedLink(MigrationLink link, ItemChannelLink expected) {
        if (linkRegistry.get(sourceLink(link).getUID()) != null || linkRegistry.get(expected.getUID()) != expected
                || !expected.getConfiguration().getProperties().equals(link.targetConfiguration())
                || !expectedManagedOwner(expected)) {
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
        String key = preview.identity() + ":" + group.sourcePollerUid();
        if (journal.links(key).stream().anyMatch(recorded -> recorded.itemName().equals(link.itemName())
                && recorded.sourceChannelUid().equals(link.sourceChannelUid()) && !recorded.equals(link))) {
            throw new IllegalStateException(
                    "Selected recovery link differs from its recorded review; manual review required");
        }
        restore(key, List.of(link));
    }

    private ItemChannelLink sourceLink(MigrationLink link) {
        return new ItemChannelLink(link.itemName(), new ChannelUID(link.sourceChannelUid()),
                new Configuration(link.configuration()));
    }

    private ItemChannelLink targetLink(MigrationLink link) {
        return new ItemChannelLink(link.itemName(), new ChannelUID(link.targetChannelUid()),
                new Configuration(link.targetConfiguration()));
    }

    private void restore(String key) {
        restore(key, this.journal.links(key));
    }

    private void restore(String key, List<MigrationLink> selected) {
        List<MigrationJournal.LinkIntent> journal = new ArrayList<>(this.journal.intents(key));
        if (journal.isEmpty()) {
            return;
        }
        List<MigrationJournal.LinkIntent> original = List.copyOf(journal);
        Map<MigrationLink, ItemChannelLink> restoredLinks = new HashMap<>();
        Map<MigrationLink, ItemChannelLink> addedSources = new HashMap<>();
        boolean completionAttempted = false;
        try {
            for (MigrationJournal.LinkIntent intent : new ArrayList<>(journal).reversed()) {
                MigrationLink link = intent.link();
                if (!selected.contains(link)) {
                    continue;
                }
                ItemChannelLink target = linkRegistry.get(targetLink(link).getUID());
                ItemChannelLink source = linkRegistry.get(sourceLink(link).getUID());
                if (source == null && expectedLinkProvider == null) {
                    throw new IllegalStateException(
                            "Recovery requires the expected authoritative managed link provider");
                }
                if ((source == null || target != null) && !this.journal.reviewedLink(key, link)) {
                    throw new IllegalStateException(
                            "Recovery link differs from immutable review; manual review required");
                }
                if (source == null && (intent.version() != 1 || intent.phase() == null
                        || intent.phase() == MigrationJournal.LinkPhase.HISTORICAL)) {
                    throw new IllegalStateException("Ambiguous recovery intent; manual review required");
                }
                ItemChannelLink expectedTarget = activeTargetLinks.get(targetLink(link).getUID());
                boolean liveAdd = intent.version() == 1 && intent.phase() == MigrationJournal.LinkPhase.ADD_ATTEMPTED
                        && expectedTarget != null && sameTarget(link, expectedTarget);
                if (target != null
                        && ((!intent.permitsTargetRemoval() && !liveAdd) || knownNotAdded.contains(target.getUID()))) {
                    throw new IllegalStateException(
                            "Recovery intent does not authorize target removal; manual review required");
                }
                if (target != null) {
                    String identity = key.substring(0, key.indexOf(':'));
                    ThingRegistry registry = thingRegistry;
                    ThingUID candidate = new ChannelUID(link.targetChannelUid()).getThingUID();
                    if (!link.markedDefaultTarget() || registry == null
                            || !this.journal.targets(identity).contains(candidate.toString())
                            || !ManagedModbusMigrationAdapter.ownsTarget(registry, thingProvider, candidate,
                                    identity)) {
                        throw new IllegalStateException(
                                "Target ownership is uncertain; retain recovery record for manual review");
                    }
                }
                if ((target != null && !target.getConfiguration().getProperties().equals(link.targetConfiguration()))
                        || (source != null
                                && !source.getConfiguration().getProperties().equals(link.configuration()))) {
                    throw new IllegalStateException("Refusing to overwrite a link changed since migration");
                }
                if (target != null && !expectedManagedOwner(target)) {
                    throw new IllegalStateException("Refusing removal of unmanaged replacement link");
                }
                if (target != null && expectedTarget != null && !sameTarget(link, expectedTarget)) {
                    throw new IllegalStateException("Refusing replacement of the active cutover target link");
                }
                requireManagedSource(link, source);
                // Removal callbacks can replace either link; a successful provider return is not proof of absence.
                if (target != null) {
                    linkRegistry.remove(targetLink(link).getUID());
                }
                requireRestoredState(link, source);
                requireRestoredLinks(restoredLinks);
                ItemChannelLink restored = source == null ? sourceLink(link) : source;
                restoredLinks.put(link, restored);
                if (source == null) {
                    addedSources.put(link, restored);
                    linkRegistry.add(restored);
                }
                requireRestoredLinks(restoredLinks);
                journal.remove(intent);
            }
            // Keep the entire cohort recoverable until every provider callback has completed.
            completionAttempted = true;
            this.journal.intents(key, journal);
            requireRestoredLinks(restoredLinks);
            for (MigrationLink link : restoredLinks.keySet()) {
                activeTargetLinks.remove(targetLink(link).getUID());
                knownNotAdded.remove(targetLink(link).getUID());
            }
        } catch (RuntimeException failure) {
            if (completionAttempted) {
                try {
                    this.journal.intents(key, original);
                } catch (RuntimeException journalFailure) {
                    failure.addSuppressed(journalFailure);
                }
            }
            // Never remove a pre-existing source or a listener replacement, even with identical metadata.
            addedSources.forEach((link, restored) -> {
                if (sameSource(link, restored)
                        && restored.getConfiguration().getProperties().equals(link.configuration())
                        && expectedManagedOwner(restored)) {
                    try {
                        linkRegistry.remove(restored.getUID());
                    } catch (RuntimeException compensationFailure) {
                        failure.addSuppressed(compensationFailure);
                    }
                }
            });
            throw failure;
        }
    }

    private void requireRestoredLinks(Map<MigrationLink, ItemChannelLink> restoredLinks) {
        restoredLinks.forEach(this::requireRestoredState);
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

    @SuppressWarnings("PMD.CompareObjectsWithEquals") // Authoritative ownership is provider instance identity.
    private boolean expectedManagedOwner(ItemChannelLink link) {
        return expectedLinkProvider != null && linkRegistry.getProvider(link) == expectedLinkProvider;
    }

    private void requireManagedSource(MigrationLink link, @Nullable ItemChannelLink source) {
        if (source != null && (!source.getConfiguration().getProperties().equals(link.configuration())
                || !(linkRegistry.getProvider(source) instanceof ManagedItemChannelLinkProvider)
                || (expectedLinkProvider != null && !expectedManagedOwner(source)))) {
            throw new IllegalStateException("Refusing changed or unmanaged legacy link");
        }
    }

    @Override
    public void removeLegacy(MigrationPreview preview) {
        throw new UnsupportedOperationException("legacy Things are never removed by this migration console");
    }
}
