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
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;

/**
 * Reads managed legacy topology and current link profiles from the registries.
 *
 * @author Sami Salonen - Capture endpoint configuration identity
 */
@NonNullByDefault
public final class ManagedModbusMigrationAdapter {
    private final ThingRegistry thingRegistry;
    private final ItemChannelLinkRegistry linkRegistry;
    private final ManagedThingProvider managedThingProvider;

    public ManagedModbusMigrationAdapter(ThingRegistry thingRegistry, ItemChannelLinkRegistry linkRegistry,
            ManagedThingProvider managedThingProvider) {
        this.thingRegistry = thingRegistry;
        this.linkRegistry = linkRegistry;
        this.managedThingProvider = managedThingProvider;
    }

    public Snapshot snapshot() {
        List<LegacyPoller> pollers = new ArrayList<>();
        List<LegacyData> data = new ArrayList<>();
        List<ManualMigrationWork> manual = new ArrayList<>();
        for (Thing thing : thingRegistry.getAll()) {
            ThingUID bridgeUid = thing.getBridgeUID();
            if (THING_TYPE_MODBUS_POLLER.equals(thing.getThingTypeUID())) {
                if (!managed(thing)) {
                    manual.add(manualWork(thing, "legacy Thing is not managed"));
                } else if (targetExists(thing.getUID())) {
                    manual.add(manualWork(thing, "target poller2 Thing already exists"));
                } else if (bridgeUid == null) {
                    manual.add(manualWork(thing, "legacy poller has no bridge"));
                } else {
                    Thing endpoint = thingRegistry.get(bridgeUid);
                    String endpointIdentity = ModbusMigrationPlanner.configurationIdentity(endpoint == null ? Map.of()
                            : Map.of("type", endpoint.getThingTypeUID().toString(), "configuration",
                                    endpoint.getConfiguration().getProperties()));
                    pollers.add(new LegacyPoller(thing.getUID().toString(), bridgeUid.toString(),
                            thing.getConfiguration().getProperties(), endpointIdentity));
                }
            } else if (THING_TYPE_MODBUS_DATA.equals(thing.getThingTypeUID())) {
                if (!managed(thing)) {
                    manual.add(manualWork(thing, "legacy Thing is not managed"));
                } else if (bridgeUid == null) {
                    manual.add(manualWork(thing, "legacy data Thing has no poller"));
                } else if (!bridgeUid.toString().startsWith("modbus:poller:")) {
                    manual.add(
                            manualWork(thing, "legacy data parent is not a poller; writes require manual migration"));
                } else {
                    List<LegacyLink> links = links(thing);
                    data.add(new LegacyData(thing.getUID().toString(), bridgeUid.toString(),
                            thing.getConfiguration().getProperties(), links));
                }
            }
        }
        return new Snapshot(pollers, data, manual);
    }

    private ManualMigrationWork manualWork(Thing thing, String reason) {
        ThingUID bridgeUid = thing.getBridgeUID();
        Thing parent = bridgeUid == null ? null : thingRegistry.get(bridgeUid);
        var linkIdentities = links(thing).stream()
                .map(link -> ModbusMigrationPlanner.configurationIdentity(
                        List.of(link.itemName(), link.channelUid(), link.itemType(), link.configurationIdentity())))
                .sorted().toList();
        String identity = ModbusMigrationPlanner.configurationIdentity(List.of(thing.getThingTypeUID().toString(),
                thing.getConfiguration().getProperties(), bridgeUid == null ? "" : bridgeUid.toString(),
                parent == null ? Map.of() : parent.getConfiguration().getProperties(), linkIdentities));
        return new ManualMigrationWork(thing.getUID().toString(), reason, identity);
    }

    private boolean managed(Thing thing) {
        return managedThingProvider.get(thing.getUID()) != null;
    }

    private boolean targetExists(ThingUID sourceUid) {
        String target = sourceUid.toString().replace(":poller:", ":poller2:");
        return thingRegistry.get(new ThingUID(target)) != null;
    }

    private List<LegacyLink> links(Thing thing) {
        List<LegacyLink> result = new ArrayList<>();
        for (ItemChannelLink link : linkRegistry.getAll()) {
            if (thing.getUID().equals(link.getLinkedUID().getThingUID())) {
                org.openhab.core.thing.Channel channel = thing.getChannel(link.getLinkedUID().getId());
                String itemType = channel == null ? null : channel.getAcceptedItemType();
                result.add(new LegacyLink(link.getItemName(), link.getLinkedUID().toString(),
                        itemType == null ? "" : itemType, link.getConfiguration().getProperties()));
            }
        }
        return result;
    }

    /** Immutable safe registry input plus explicitly refused work. */
    public record Snapshot(List<LegacyPoller> pollers, List<LegacyData> data, List<ManualMigrationWork> manualWork) {
        public Snapshot {
            pollers = List.copyOf(pollers);
            data = List.copyOf(data);
            manualWork = List.copyOf(manualWork);
        }
    }
}
