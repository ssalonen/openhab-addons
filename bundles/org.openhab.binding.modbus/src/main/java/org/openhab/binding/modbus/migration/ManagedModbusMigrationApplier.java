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
import java.util.List;
import java.util.Map;

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

    public ManagedModbusMigrationApplier(ManagedThingProvider thingProvider, ItemChannelLinkRegistry linkRegistry) {
        this(thingProvider, linkRegistry, new MigrationJournal());
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
                if (current != null && !identity.equals(current.getProperties().get(OWNER))) {
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
        String key = preview.identity() + ":" + group.sourcePollerUid();
        if (!journal.links(key).isEmpty()) {
            throw new IllegalStateException("Existing cutover journal; rollback before retry");
        }
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
        }
        List<MigrationLink> journal = new ArrayList<>();
        this.journal.manifest(preview);
        try {
            for (MigrationLink link : group.links()) {
                journal.add(link);
                this.journal.links(key, journal);
                linkRegistry.remove(sourceLink(link).getUID());
                linkRegistry.add(targetLink(link));
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
            // Never restore production legacy delivery while target removal is still failing.
            linkRegistry.remove(targetLink(link).getUID());
            if (source == null) {
                linkRegistry.add(sourceLink(link));
            }
            journal.remove(link);
            this.journal.links(key, journal);
        }
    }

    @Override
    public void removeLegacy(MigrationPreview preview) {
        throw new UnsupportedOperationException("legacy Things are never removed by this migration console");
    }
}
