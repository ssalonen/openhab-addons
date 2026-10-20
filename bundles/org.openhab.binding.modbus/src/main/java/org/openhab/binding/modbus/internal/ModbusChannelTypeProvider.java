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
package org.openhab.binding.modbus.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.ModbusBindingConstants;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeBuilder;
import org.openhab.core.thing.type.ChannelTypeProvider;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Generates ChannelTypes for the accepted item types of live and persisted poller2 channels.
 *
 * @author Sami Salonen - Initial contribution
 */
@Component(service = { ChannelTypeProvider.class, ModbusChannelTypeProvider.class })
@NonNullByDefault
public class ModbusChannelTypeProvider implements ChannelTypeProvider {
    private static final String GENERATED_PREFIX = "poller2-generated-";

    private final Map<ChannelTypeUID, ChannelType> channelTypes = new HashMap<>();
    private final Set<ChannelTypeUID> ambiguousUIDs = new HashSet<>();
    private @Nullable ThingRegistry thingRegistry;

    @Reference
    public synchronized void setThingRegistry(ThingRegistry thingRegistry) {
        this.thingRegistry = thingRegistry;
    }

    @Override
    public synchronized Collection<ChannelType> getChannelTypes(@Nullable Locale locale) {
        restorePersistedChannelTypes();
        return List.copyOf(channelTypes.values());
    }

    @Override
    public synchronized @Nullable ChannelType getChannelType(ChannelTypeUID channelTypeUID, @Nullable Locale locale) {
        restorePersistedChannelTypes();
        return channelTypes.get(channelTypeUID);
    }

    private void restorePersistedChannelTypes() {
        ThingRegistry registry = thingRegistry;
        if (registry == null) {
            return;
        }
        // Core resolves persisted channel types before creating handlers. Read the public registry lazily:
        // the registry service may be activated before its managed Things have been restored.
        for (var thing : registry.getAll()) {
            if (ModbusBindingConstantsInternal.THING_TYPE_MODBUS_POLLER2.equals(thing.getThingTypeUID())) {
                for (var channel : thing.getChannels()) {
                    String itemType = channel.getAcceptedItemType();
                    if (itemType != null && !itemType.isBlank()
                            && generatedUID(itemType).equals(channel.getChannelTypeUID())) {
                        registerChannelType(itemType);
                    }
                }
            }
        }
    }

    public synchronized @Nullable ChannelTypeUID getGeneratedChannelTypeUID(@Nullable String itemType) {
        if (itemType == null || itemType.isBlank()) {
            return null;
        }
        restorePersistedChannelTypes();
        return registerChannelType(itemType);
    }

    private ChannelTypeUID generatedUID(String itemType) {
        String id = GENERATED_PREFIX
                + itemType.replace(':', '-').replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        return new ChannelTypeUID(ModbusBindingConstants.BINDING_ID, id);
    }

    private @Nullable ChannelTypeUID registerChannelType(String itemType) {
        ChannelTypeUID uid = generatedUID(itemType);
        if (ambiguousUIDs.contains(uid)) {
            return null;
        }
        ChannelType existing = channelTypes.get(uid);
        if (existing != null && !itemType.equals(existing.getItemType())) {
            // The historical ID encoding is not injective. Never expose another accepted Item type under this ID.
            channelTypes.remove(uid);
            ambiguousUIDs.add(uid);
            return null;
        }
        if (existing == null) {
            channelTypes.put(uid, ChannelTypeBuilder.state(uid, itemType, itemType).build());
        }
        return uid;
    }
}
