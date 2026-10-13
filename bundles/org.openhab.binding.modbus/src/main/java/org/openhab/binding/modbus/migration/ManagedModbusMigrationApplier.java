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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;

/**
 * Stages unlinked managed candidates and journals explicit link switches in memory for compensation.
 * 
 * @author Sami Salonen - Unlinked staging and explicit link cutover
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

    private final Map<String, List<MigrationLink>> switchedLinks = new HashMap<>();
    private final Map<String, List<ThingUID>> ownedTargets = new HashMap<>();
    private final ManagedThingProvider thingProvider;
    private final ItemChannelLinkRegistry linkRegistry;

    public ManagedModbusMigrationApplier(ManagedThingProvider thingProvider, ItemChannelLinkRegistry linkRegistry) {
        this.thingProvider = thingProvider;
        this.linkRegistry = linkRegistry;
    }

    @Override
    public void createTargets(MigrationPreview preview) {
        List<ThingUID> createdThings = new ArrayList<>();
        ownedTargets.put(preview.identity(), createdThings);
        try {
            for (MigrationGroup group : preview.groups()) {
                ThingUID targetUid = new ThingUID(group.targetPollerUid());
                if (thingProvider.get(targetUid) != null) {
                    throw new IllegalStateException("refusing to overwrite existing target " + targetUid);
                }
                BridgeBuilder builder = BridgeBuilder.create(THING_TYPE_MODBUS_POLLER2, targetUid)
                        .withBridge(new ThingUID(group.targetBridgeUid()))
                        .withConfiguration(new Configuration(group.configuration()));
                group.channels().forEach(channel -> builder.withChannel(ChannelBuilder
                        .create(new ChannelUID(targetUid, channel.id())).withAcceptedItemType(channel.itemType())
                        .withConfiguration(new Configuration(channel.configuration())).build()));
                // Provider listeners may throw after the add has already changed managed state.
                createdThings.add(targetUid);
                thingProvider.add(builder.build());

            }
        } catch (RuntimeException e) {
            try {
                remove(createdThings);
                ownedTargets.remove(preview.identity());
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
        List<ThingUID> things = ownedTargets.get(preview.identity());
        if (things != null) {
            remove(things);
            ownedTargets.remove(preview.identity());
        }
    }

    private void remove(List<ThingUID> things) {
        List<RuntimeException> failures = new ArrayList<>();
        for (ThingUID thing : new ArrayList<>(things).reversed()) {
            try {
                thingProvider.remove(thing);
                things.remove(thing);
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
        if (switchedLinks.containsKey(key)) {
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
            if (linkRegistry.get(targetLink(link).getUID()) != null) {
                throw new IllegalStateException("Refusing existing target link");
            }
        }
        List<MigrationLink> journal = new ArrayList<>();
        switchedLinks.put(key, journal);
        try {
            for (MigrationLink link : group.links()) {
                journal.add(link);
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

    private ItemChannelLink sourceLink(MigrationLink link) {
        return new ItemChannelLink(link.itemName(), new ChannelUID(link.sourceChannelUid()),
                new Configuration(link.configuration()));
    }

    private ItemChannelLink targetLink(MigrationLink link) {
        return new ItemChannelLink(link.itemName(), new ChannelUID(link.targetChannelUid()),
                new Configuration(link.configuration()));
    }

    private void restore(String key) {
        List<MigrationLink> journal = switchedLinks.get(key);
        if (journal == null) {
            return;
        }
        for (MigrationLink link : new ArrayList<>(journal).reversed()) {
            // Never restore production legacy delivery while target removal is still failing.
            linkRegistry.remove(targetLink(link).getUID());
            linkRegistry.add(sourceLink(link));
            journal.remove(link);
        }
        switchedLinks.remove(key);
    }

    @Override
    public void removeLegacy(MigrationPreview preview) {
        throw new UnsupportedOperationException("legacy Things are never removed by this migration console");
    }
}
