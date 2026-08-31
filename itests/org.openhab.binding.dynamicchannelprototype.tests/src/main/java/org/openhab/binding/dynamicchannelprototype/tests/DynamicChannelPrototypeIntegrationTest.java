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
package org.openhab.binding.dynamicchannelprototype.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.dynamicchannelprototype.internal.DynamicChannelPrototypeHandler;
import org.openhab.binding.dynamicchannelprototype.internal.DynamicChannelPrototypeHandlerFactory;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemProvider;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.ManagedItemProvider;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.model.yaml.YamlModelRepository;
import org.openhab.core.model.yaml.internal.items.YamlChannelLinkProvider;
import org.openhab.core.model.yaml.internal.items.YamlItemProvider;
import org.openhab.core.model.yaml.internal.things.YamlThingProvider;
import org.openhab.core.test.java.JavaOSGiTest;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingProvider;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkProvider;
import org.openhab.core.thing.link.ManagedItemChannelLinkProvider;

/**
 * Proves direct typed channels and raw numeric channels with link profiles using native openHAB YAML configuration.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault({})
public class DynamicChannelPrototypeIntegrationTest extends JavaOSGiTest {
    private static final ThingUID DIRECT_THING_UID = new ThingUID("dynamicchannelprototype", "value", "direct");
    private static final ThingUID PROFILED_POWER_THING_UID = new ThingUID("dynamicchannelprototype", "value",
            "profiled-power");
    private static final ThingUID PROFILED_SWITCH_THING_UID = new ThingUID("dynamicchannelprototype", "value",
            "profiled-switch");
    private static final ChannelUID DIRECT_CHANNEL_UID = new ChannelUID(DIRECT_THING_UID, "value");
    private static final String DIRECT_ITEM_NAME = "DirectPower";
    private static final String PROFILED_POWER_ITEM_NAME = "ProfiledPower";
    private static final String THRESHOLD_SWITCH_ITEM_NAME = "ThresholdSwitch";

    private ManagedThingProvider thingProvider;
    private ManagedItemProvider itemProvider;
    private ItemRegistry itemRegistry;
    private ManagedItemChannelLinkProvider linkProvider;
    private ThingRegistry thingRegistry;
    private YamlModelRepository yamlModelRepository;
    private YamlThingProvider yamlThingProvider;
    private YamlItemProvider yamlItemProvider;
    private YamlChannelLinkProvider yamlChannelLinkProvider;
    private EventPublisher eventPublisher;
    private List<Thing> things;
    private List<Item> items;
    private List<ItemChannelLink> links;
    private String yamlModelName;

    @BeforeEach
    public void setUp() throws Exception {
        registerVolatileStorageService();
        thingProvider = getService(ThingProvider.class, ManagedThingProvider.class);
        itemProvider = getService(ItemProvider.class, ManagedItemProvider.class);
        itemRegistry = getService(ItemRegistry.class);
        linkProvider = getService(ItemChannelLinkProvider.class, ManagedItemChannelLinkProvider.class);
        thingRegistry = getService(ThingRegistry.class);
        yamlModelRepository = getService(YamlModelRepository.class);
        yamlThingProvider = getService(ThingProvider.class, YamlThingProvider.class);
        yamlItemProvider = getService(ItemProvider.class, YamlItemProvider.class);
        yamlChannelLinkProvider = getService(ItemChannelLinkProvider.class, YamlChannelLinkProvider.class);
        eventPublisher = getService(EventPublisher.class);
        assertNotNull(getService(ThingHandlerFactory.class, DynamicChannelPrototypeHandlerFactory.class));

        InputStream yaml = Objects.requireNonNull(getClass().getResourceAsStream("/power-channel.yaml"));
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        yamlModelName = yamlModelRepository.createIsolatedModel(yaml, errors, warnings);
        assertNotNull(yamlModelName);
        assertEquals(List.of(), errors);
        assertEquals(List.of(), warnings);

        things = List.copyOf(yamlThingProvider.getAllFromModel(yamlModelName));
        items = List.copyOf(yamlItemProvider.getAllFromModel(yamlModelName));
        links = List.copyOf(yamlChannelLinkProvider.getAllFromModel(yamlModelName));
        assertEquals(3, things.size());
        assertEquals(3, items.size());
        assertEquals(3, links.size());

        things.forEach(thingProvider::add);
        waitForAssert(() -> things.forEach(thing -> assertNotNull(thingRegistry.get(thing.getUID()).getHandler())));
        items.forEach(itemProvider::add);
        links.forEach(linkProvider::add);
    }

    @AfterEach
    public void tearDown() {
        links.forEach(link -> linkProvider.remove(link.getUID()));
        items.forEach(item -> itemProvider.remove(item.getName()));
        things.forEach(thing -> thingProvider.remove(thing.getUID()));
        yamlModelRepository.removeIsolatedModel(yamlModelName);
    }

    @Test
    public void typedDynamicChannelPreservesQuantityOnStateAndCommand() {
        assertEquals("Number:Power",
                thingRegistry.get(DIRECT_THING_UID).getChannel(DIRECT_CHANNEL_UID).getAcceptedItemType());

        DynamicChannelPrototypeHandler handler = handler(DIRECT_THING_UID);
        handler.emitPower(new QuantityType<>("5 kW"));
        waitForAssert(() -> assertEquals(new QuantityType<>("5 kW"), itemRegistry.get(DIRECT_ITEM_NAME).getState()));

        eventPublisher.post(ItemEventFactory.createCommandEvent(DIRECT_ITEM_NAME, new QuantityType<>("5 kW")));
        waitForAssert(() -> assertEquals(new QuantityType<>("5 kW"), handler.getLastCommand()));
    }

    @Test
    public void dynamicallyConfiguredProfiledChannelsExposeTheirDeclaredItemTypes() {
        Thing profiledPowerThing = thingRegistry.get(PROFILED_POWER_THING_UID);
        Thing profiledSwitchThing = thingRegistry.get(PROFILED_SWITCH_THING_UID);
        assertNull(profiledPowerThing.getChannel("value").getChannelTypeUID());
        assertEquals("Number:Power", profiledPowerThing.getChannel("value").getAcceptedItemType());
        assertNull(profiledSwitchThing.getChannel("value").getChannelTypeUID());
        assertEquals("Number", profiledSwitchThing.getChannel("value").getAcceptedItemType());
    }

    @Test
    public void powerProfileConvertsRawStateAndCommandAtANumberChannel() {
        DynamicChannelPrototypeHandler handler = handler(PROFILED_POWER_THING_UID);
        handler.emitRaw(new DecimalType(49));
        waitForAssert(
                () -> assertEquals(new QuantityType<>("5 kW"), itemRegistry.get(PROFILED_POWER_ITEM_NAME).getState()));

        eventPublisher.post(ItemEventFactory.createCommandEvent(PROFILED_POWER_ITEM_NAME, new QuantityType<>("5 kW")));
        waitForAssert(() -> assertEquals(new DecimalType(49), handler.getLastCommand()));
    }

    @Test
    public void thresholdProfileConvertsRawStateAndSwitchCommandAtANumberChannel() {
        DynamicChannelPrototypeHandler handler = handler(PROFILED_SWITCH_THING_UID);
        handler.emitRaw(new DecimalType(51));
        waitForAssert(() -> assertEquals(OnOffType.ON, itemRegistry.get(THRESHOLD_SWITCH_ITEM_NAME).getState()));

        eventPublisher.post(ItemEventFactory.createCommandEvent(THRESHOLD_SWITCH_ITEM_NAME, OnOffType.ON));
        waitForAssert(() -> assertEquals(new DecimalType(100), handler.getLastCommand()));

        eventPublisher.post(ItemEventFactory.createCommandEvent(THRESHOLD_SWITCH_ITEM_NAME, OnOffType.OFF));
        waitForAssert(() -> assertEquals(new DecimalType(0), handler.getLastCommand()));
    }

    private DynamicChannelPrototypeHandler handler(ThingUID thingUID) {
        return (DynamicChannelPrototypeHandler) thingRegistry.get(thingUID).getHandler();
    }
}
