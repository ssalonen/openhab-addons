/*
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.dynamicchannelprototype.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.dynamicchannelprototype.internal.DynamicChannelPrototypeHandler;
import org.openhab.binding.dynamicchannelprototype.internal.DynamicChannelPrototypeHandlerFactory;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.i18n.UnitProvider;
import org.openhab.core.items.ItemProvider;
import org.openhab.core.items.ManagedItemProvider;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.test.java.JavaOSGiTest;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingProvider;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkProvider;
import org.openhab.core.thing.link.ManagedItemChannelLinkProvider;

/** Proves that a binding can use a user-defined, dimensioned Channel without a static Number:Power channel type. */
public class DynamicChannelPrototypeIntegrationTest extends JavaOSGiTest {
    private static final ThingUID THING_UID = new ThingUID("dynamicchannelprototype", "value", "power");
    private static final ChannelUID CHANNEL_UID = new ChannelUID(THING_UID, "value");
    private static final String ITEM_NAME = "PrototypePower";

    private ManagedThingProvider thingProvider;
    private ManagedItemProvider itemProvider;
    private ManagedItemChannelLinkProvider linkProvider;
    private EventPublisher eventPublisher;
    private Thing thing;

    @BeforeEach
    public void setUp() {
        registerVolatileStorageService();
        thingProvider = getService(ThingProvider.class, ManagedThingProvider.class);
        itemProvider = getService(ItemProvider.class, ManagedItemProvider.class);
        linkProvider = getService(ItemChannelLinkProvider.class, ManagedItemChannelLinkProvider.class);
        eventPublisher = getService(EventPublisher.class);
        assertNotNull(getService(ThingHandlerFactory.class, DynamicChannelPrototypeHandlerFactory.class));

        thing = ThingBuilder.create(DynamicChannelPrototypeHandlerFactory.THING_TYPE, THING_UID)
                .withChannel(ChannelBuilder.create(CHANNEL_UID, "Number:Power").build()).build();
        thingProvider.add(thing);
        waitForAssert(() -> assertNotNull(thing.getHandler()));

        UnitProvider units = getService(UnitProvider.class);
        NumberItem powerItem = new NumberItem("Number:Power", ITEM_NAME, units);
        itemProvider.add(powerItem);
        linkProvider.add(new ItemChannelLink(ITEM_NAME, CHANNEL_UID));
    }

    @AfterEach
    public void tearDown() {
        linkProvider.remove(new ItemChannelLink(ITEM_NAME, CHANNEL_UID).getUID());
        itemProvider.remove(ITEM_NAME);
        thingProvider.remove(THING_UID);
    }

    @Test
    public void bindingStateReachesLinkedDimensionedItem() {
        DynamicChannelPrototypeHandler handler = (DynamicChannelPrototypeHandler) thing.getHandler();
        handler.emitPower(new QuantityType<>("5 kW"));

        waitForAssert(() -> assertEquals(new QuantityType<>("5 kW"), itemProvider.get(ITEM_NAME).getState()));
    }

    @Test
    public void dimensionedItemCommandReachesBindingWithItsUnit() {
        DynamicChannelPrototypeHandler handler = (DynamicChannelPrototypeHandler) thing.getHandler();
        eventPublisher.post(ItemEventFactory.createCommandEvent(ITEM_NAME, new QuantityType<>("5 kW")));

        waitForAssert(() -> assertEquals(new QuantityType<>("5 kW"), handler.getLastCommand()));
    }
}
