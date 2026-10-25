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

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

/** @author Sami Salonen - Initial contribution */
class MigrationReadonlyConformanceTest {
    @Test
    void allReadonlyLinksArePlannedAndEveryWritableSourceStaysManual() throws Exception {
        var gson = new com.google.gson.GsonBuilder()
                .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.BIG_DECIMAL).create();
        JsonObject fixture;
        try (var input = getClass().getResourceAsStream("/migration/readonly-conformance.json")) {
            assertNotNull(input);
            fixture = gson.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class);
        }
        List<LegacyPoller> pollers = new ArrayList<>();
        List<LegacyData> data = new ArrayList<>();
        var mapType = new TypeToken<Map<String, Object>>() {
        }.getType();
        for (var element : fixture.getAsJsonArray("things")) {
            var thing = element.getAsJsonObject();
            String uid = thing.get("UID").getAsString();
            Map<String, Object> config = java.util.Objects
                    .requireNonNull(gson.fromJson(thing.get("configuration"), mapType));
            if ("modbus:poller".equals(thing.get("thingTypeUID").getAsString())) {
                pollers.add(new LegacyPoller(uid, thing.get("bridgeUID").getAsString(), config));
            } else if ("modbus:data".equals(thing.get("thingTypeUID").getAsString())) {
                List<LegacyLink> links = new ArrayList<>();
                for (var linkElement : fixture.getAsJsonArray("links")) {
                    var link = linkElement.getAsJsonObject();
                    String channel = link.get("channelUID").getAsString();
                    if (new org.openhab.core.thing.ChannelUID(channel).getThingUID().toString().equals(uid)) {
                        links.add(new LegacyLink(link.get("itemName").getAsString(), channel,
                                link.get("sourceAcceptedType").getAsString(),
                                java.util.Objects.requireNonNull(gson.fromJson(link.get("configuration"), mapType))));
                    }
                }
                data.add(new LegacyData(uid, thing.get("bridgeUID").getAsString(), config, links));
            }
        }
        assertEquals(34, data.size());
        assertEquals(40, data.stream().mapToInt(source -> source.links().size()).sum());
        var preview = new ModbusMigrationPlanner().preview(pollers, data);
        Set<String> plannedItems = preview.groups().stream().flatMap(group -> group.links().stream())
                .map(MigrationLink::itemName).collect(Collectors.toSet());
        Set<String> expectedItems = gson.fromJson(
                fixture.getAsJsonObject("expectedPartitions").getAsJsonObject("readOnly").get("items"),
                new TypeToken<Set<String>>() {
                }.getType());
        assertEquals(expectedItems, plannedItems);
        assertEquals(18, plannedItems.size());
        Set<String> represented = preview.groups().stream().flatMap(group -> group.channels().stream())
                .map(MigrationChannel::sourceDataUid).collect(Collectors.toSet());
        assertEquals(16, represented.size());
        assertEquals(18, preview.groups().stream().mapToInt(group -> group.links().size()).sum());
        assertTrue(data.stream().filter(source -> !represented.contains(source.uid()))
                .allMatch(source -> source.omissionReason().contains("write")));
        for (var group : preview.groups()) {
            for (var channel : group.channels()) {
                assertEquals("keepLast", channel.configuration().get("errorPolicy"));
                assertFalse(channel.configuration().keySet().stream().anyMatch(key -> key.startsWith("write")));
            }
        }
    }
}
