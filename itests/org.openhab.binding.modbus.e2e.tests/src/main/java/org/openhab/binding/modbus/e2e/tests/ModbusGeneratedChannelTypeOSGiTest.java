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
package org.openhab.binding.modbus.e2e.tests;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.test.java.JavaOSGiTest;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelTypeRegistry;
import org.openhab.core.thing.type.ChannelTypeUID;

/**
 * Verifies Core readiness for managed pollers restored with already-generated channel type IDs.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ModbusGeneratedChannelTypeOSGiTest extends JavaOSGiTest {
    @Test
    public void persistedGeneratedTypeDoesNotWaitForHandlerInitialization() {
        registerVolatileStorageService();
        var provider = requireNonNull(getService(ManagedThingProvider.class));
        var registry = requireNonNull(getService(ThingRegistry.class));
        var types = requireNonNull(getService(ChannelTypeRegistry.class));
        var endpointUID = new ThingUID("modbus:tcp:generated-restart");
        var pollerUID = new ThingUID("modbus:poller2:generated-restart:holding");
        var typeUID = new ChannelTypeUID("modbus:poller2-generated-number-electric-charge");
        var channel = ChannelBuilder.create(new ChannelUID(pollerUID, "charge"), "Number:ElectricCharge")
                .withType(typeUID).withConfiguration(new Configuration(Map.of("address", "100", "valueType", "int16")))
                .build();
        var endpoint = BridgeBuilder.create(new ThingTypeUID("modbus:tcp"), endpointUID)
                .withConfiguration(new Configuration(Map.of("host", "127.0.0.1", "port", 502))).build();
        var poller = BridgeBuilder.create(new ThingTypeUID("modbus:poller2"), pollerUID).withBridge(endpointUID)
                .withConfiguration(
                        new Configuration(Map.of("start", 100, "length", 1, "type", "holding", "refresh", 0)))
                .withChannels(channel).build();
        try {
            // Deliberately no getGeneratedChannelTypeUID call: this is the managed restart shape, not native YAML.
            provider.add(endpoint);
            provider.add(poller);
            waitForAssert(() -> {
                assertNotNull(types.getChannelType(typeUID));
                assertEquals("Number:ElectricCharge", requireNonNull(types.getChannelType(typeUID)).getItemType());
                assertEquals(ThingStatus.ONLINE, requireNonNull(registry.get(pollerUID)).getStatus());
                assertEquals(typeUID, requireNonNull(requireNonNull(registry.get(pollerUID)).getChannel("charge"))
                        .getChannelTypeUID());
            });
        } finally {
            provider.remove(pollerUID);
            provider.remove(endpointUID);
        }
    }
}
