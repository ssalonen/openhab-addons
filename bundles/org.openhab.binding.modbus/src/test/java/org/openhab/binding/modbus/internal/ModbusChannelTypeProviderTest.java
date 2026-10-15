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

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeUID;

/**
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ModbusChannelTypeProviderTest {

    @Test
    public void extensibleChannelTypesHaveEagerConfigurationSchemas() throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        var builder = factory.newDocumentBuilder();
        try (var bridgeStream = requireNonNull(getClass().getResourceAsStream("/OH-INF/thing/bridge-poller2.xml"));
                var channelsStream = requireNonNull(
                        getClass().getResourceAsStream("/OH-INF/thing/poller2-channels.xml"))) {
            var bridge = builder.parse(bridgeStream);
            var channels = builder.parse(channelsStream);
            var definitions = channels.getElementsByTagName("channel-type");
            var ids = new java.util.HashSet<String>();
            for (int index = 0; index < definitions.getLength(); index++) {
                var type = (org.w3c.dom.Element) definitions.item(index);
                ids.add(type.getAttribute("id"));
                assertEquals(1, type.getElementsByTagName("config-description-ref").getLength());
            }
            String extensible = ((org.w3c.dom.Element) bridge.getElementsByTagName("bridge-type").item(0))
                    .getAttribute("extensible");
            assertEquals(java.util.Set.of(extensible.split(",")), ids);
            try (var configStream = requireNonNull(
                    getClass().getResourceAsStream("/OH-INF/config/poller2-channel.xml"))) {
                var config = builder.parse(configStream);
                assertEquals("channel-type:modbus:poller2-value",
                        ((org.w3c.dom.Element) config.getElementsByTagName("config-description").item(0))
                                .getAttribute("uri"));
                var parameters = config.getElementsByTagName("parameter");
                var required = new java.util.HashSet<String>();
                for (int index = 0; index < parameters.getLength(); index++) {
                    var parameter = (org.w3c.dom.Element) parameters.item(index);
                    if ("true".equals(parameter.getAttribute("required"))) {
                        required.add(parameter.getAttribute("name"));
                    }
                }
                assertEquals(java.util.Set.of("address", "valueType"), required);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "Number", "Number:Temperature", "Number:CustomDimension", "Switch", "Contact", "Dimmer",
            "Rollershutter", "String", "DateTime" })
    public void reconstructsPersistedManagedChannelsBeforeAnyHandlerExists(String itemType) {
        var original = new ModbusChannelTypeProvider();
        var uid = requireNonNull(original.getGeneratedChannelTypeUID(itemType));
        var thing = persistedPoller("restored", itemType, uid);
        var channels = List.copyOf(thing.getChannels());
        var registry = mock(ThingRegistry.class);
        when(registry.getAll()).thenReturn(List.of(thing));
        var restarted = new ModbusChannelTypeProvider();
        restarted.setThingRegistry(registry);

        assertEquals(itemType, requireNonNull(restarted.getChannelType(uid, null)).getItemType());
        assertEquals(channels, thing.getChannels());
        assertNull(thing.getHandler());
        assertEquals(1, restarted.getChannelTypes(null).size());
        verify(registry, atLeastOnce()).getAll();
        verifyNoMoreInteractions(registry);
    }

    @Test
    public void discoversThingsRestoredAfterRegistryServiceActivation() {
        var registry = mock(ThingRegistry.class);
        var uid = new ChannelTypeUID("modbus:poller2-generated-number-temperature");
        when(registry.getAll()).thenReturn(List.of());
        var provider = new ModbusChannelTypeProvider();
        provider.setThingRegistry(registry);
        assertNull(provider.getChannelType(uid, null));
        when(registry.getAll()).thenReturn(List.of(persistedPoller("late", "Number:Temperature", uid)));
        assertEquals("Number:Temperature", provider.getChannelTypes(null).iterator().next().getItemType());
        assertEquals("Number:Temperature", requireNonNull(provider.getChannelType(uid, null)).getItemType());
    }

    @Test
    public void ignoresForeignThingsAndMismatchedOrStaticTypeIds() {
        var registry = mock(ThingRegistry.class);
        var uid = new ChannelTypeUID("modbus:poller2-generated-number-temperature");
        var foreign = BridgeBuilder.create(new ThingTypeUID("other", "poller2"), "foreign").withChannels(ChannelBuilder
                .create(new ChannelUID("other:poller2:foreign:value"), "Number:Temperature").withType(uid).build())
                .build();
        when(registry.getAll()).thenReturn(List.of(foreign, persistedPoller("mismatch", "Switch", uid),
                persistedPoller("static", "Number:Temperature", new ChannelTypeUID("modbus:poller2-number"))));
        var provider = new ModbusChannelTypeProvider();
        provider.setThingRegistry(registry);
        assertNull(provider.getChannelType(uid, null));
        assertEquals(0, provider.getChannelTypes(null).size());
    }

    @Test
    public void refusesPersistedCollisionsInEitherRegistryOrder() {
        var uid = new ChannelTypeUID("modbus:poller2-generated-number-custom-dimension");
        var first = persistedPoller("first", "Number:CustomDimension", uid);
        var second = persistedPoller("second", "Number:Custom-Dimension", uid);
        for (var things : List.of(List.of(first, second), List.of(second, first))) {
            var registry = mock(ThingRegistry.class);
            when(registry.getAll()).thenReturn(List.copyOf(things));
            var provider = new ModbusChannelTypeProvider();
            provider.setThingRegistry(registry);
            assertNull(provider.getChannelType(uid, null));
            assertEquals(0, provider.getChannelTypes(null).size());
            assertNull(provider.getGeneratedChannelTypeUID("Number:CustomDimension"));
        }
    }

    @Test
    public void refusesAmbiguousGeneratedIdentities() {
        var provider = new ModbusChannelTypeProvider();
        var uid = requireNonNull(provider.getGeneratedChannelTypeUID("Number:CustomDimension"));
        assertNull(provider.getGeneratedChannelTypeUID("Number:Custom-Dimension"));
        assertNull(provider.getChannelType(uid, null));
        assertNull(provider.getGeneratedChannelTypeUID("Number:CustomDimension"));
        assertEquals(0, provider.getChannelTypes(null).size());
    }

    private Bridge persistedPoller(String id, String itemType, ChannelTypeUID typeUID) {
        var thingUID = new ThingUID("modbus:poller2:recovery-acceptance:" + id);
        var channel = ChannelBuilder.create(new ChannelUID(thingUID, "value"), itemType).withType(typeUID).build();
        return BridgeBuilder.create(ModbusBindingConstantsInternal.THING_TYPE_MODBUS_POLLER2, thingUID)
                .withChannels(channel).build();
    }

    @Test
    public void reusesOneStableGeneratedChannelTypeForEachAcceptedItemType() {
        ModbusChannelTypeProvider provider = new ModbusChannelTypeProvider();

        ChannelTypeUID numberUID = requireNonNull(provider.getGeneratedChannelTypeUID("Number"));
        ChannelTypeUID temperatureUID = requireNonNull(provider.getGeneratedChannelTypeUID("Number:Temperature"));
        ChannelTypeUID reusedNumberUID = requireNonNull(provider.getGeneratedChannelTypeUID("Number"));

        assertEquals(new ChannelTypeUID("modbus", "poller2-generated-number"), numberUID);
        assertEquals(new ChannelTypeUID("modbus", "poller2-generated-number-temperature"), temperatureUID);
        assertEquals(numberUID, reusedNumberUID);
        assertEquals(2, provider.getChannelTypes(null).size());

        ChannelType numberType = requireNonNull(provider.getChannelType(numberUID, null));
        assertSame(numberType, provider.getChannelType(reusedNumberUID, null));
        assertEquals("Number", numberType.getItemType());
    }
}
