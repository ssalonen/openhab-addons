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
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.modbus.migration.MigrationValidation;
import org.openhab.binding.modbus.migration.ModbusMigrationService;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.ManagedItemProvider;
import org.openhab.core.library.items.ContactItem;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.test.java.JavaOSGiTest;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.type.ChannelTypeUID;

/**
 * Real Core Item/profile routing through read-only migration, including failure retention and exact undo.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ModbusReadOnlyMigrationOSGiTest extends JavaOSGiTest {
    private record ReadCase(String name, String channel, String acceptedType, int function, int initialWire,
            int changedWire, String initialState, String changedState, Map<String, Object> profile) {
        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    public void preservesHoldingNumberThroughCutoverFailureAndUndo() throws Exception {
        assertReadOnlyRoundTrip(new ReadCase("holding-number", "number", "Number", 3, 41, 52, "41", "52", Map.of()));
    }

    @Test
    public void preservesActualGainOffsetProfileThroughCutoverFailureAndUndo() throws Exception {
        assertReadOnlyRoundTrip(new ReadCase("holding-gain-offset", "number", "Number", 3, 41, 52, "84", "106",
                Map.of("profile", "modbus:gainOffset", "gain", "2", "pre-gain-offset", "1")));
    }

    @Test
    public void preservesHoldingSwitchThroughCutoverFailureAndUndo() throws Exception {
        assertReadOnlyRoundTrip(new ReadCase("holding-switch", "switch", "Switch", 3, 41, 0, "ON", "OFF", Map.of()));
    }

    @Test
    public void preservesCoilSwitchThroughCutoverFailureAndUndo() throws Exception {
        assertReadOnlyRoundTrip(new ReadCase("coil-switch", "switch", "Switch", 1, 1, 0, "ON", "OFF", Map.of()));
    }

    @Test
    public void preservesCoilContactThroughCutoverFailureAndUndo() throws Exception {
        assertReadOnlyRoundTrip(
                new ReadCase("coil-contact", "contact", "Contact", 1, 1, 0, "OPEN", "CLOSED", Map.of()));
    }

    @Test
    public void preservesCoilNumberThroughCutoverFailureAndUndo() throws Exception {
        assertReadOnlyRoundTrip(new ReadCase("coil-number", "number", "Number", 1, 1, 0, "1", "0", Map.of()));
    }

    private void assertReadOnlyRoundTrip(ReadCase test) throws Exception {
        registerVolatileStorageService();
        ManagedThingProvider things = requireNonNull(getService(ManagedThingProvider.class));
        ThingRegistry registry = requireNonNull(getService(ThingRegistry.class));
        ItemChannelLinkRegistry links = requireNonNull(getService(ItemChannelLinkRegistry.class));
        ManagedItemProvider itemProvider = requireNonNull(getService(ManagedItemProvider.class));
        ItemRegistry items = requireNonNull(getService(ItemRegistry.class));
        ModbusMigrationService migration = requireNonNull(getService(ModbusMigrationService.class));
        ThingUID endpoint = new ThingUID("modbus:tcp:readonly");
        ThingUID poller = new ThingUID("modbus:poller:readonly:window");
        ThingUID data = new ThingUID("modbus:data:readonly:window:value");
        ThingUID target = new ThingUID("modbus:poller2:readonly:window");
        String itemName = "ReadOnlyMigrationValue";
        ChannelUID sourceChannel = new ChannelUID(data, test.channel());
        ItemChannelLink original = new ItemChannelLink(itemName, sourceChannel, new Configuration(test.profile()));
        ((org.openhab.core.common.registry.AbstractRegistry<?, ?, ?>) registry).waitForCompletedAsyncActivationTasks();
        links.waitForCompletedAsyncActivationTasks();
        try (ReadWire wire = new ReadWire(test.function(), test.initialWire())) {
            try {
                switch (test.acceptedType()) {
                    case "Number" -> itemProvider.add(new NumberItem(itemName));
                    case "Switch" -> itemProvider.add(new SwitchItem(itemName));
                    case "Contact" -> itemProvider.add(new ContactItem(itemName));
                    default -> fail("Unsupported fixture Item type");
                }
                things.add(BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), endpoint)
                        .withConfiguration(new Configuration(
                                Map.of("host", "127.0.0.1", "port", wire.port(), "id", 1, "reconnectAfterMillis", 0)))
                        .build());
                things.add(
                        BridgeBuilder.create(new ThingTypeUID("modbus", "poller"), poller).withBridge(endpoint)
                                .withConfiguration(new Configuration(Map.of("start", 100, "length", 1, "type",
                                        test.function() == 1 ? "coil" : "holding", "refresh", 100, "cacheMillis", 0,
                                        "maxTries", 1)))
                                .build());
                things.add(ThingBuilder.create(new ThingTypeUID("modbus", "data"), data).withBridge(poller)
                        .withConfiguration(new Configuration(
                                Map.of("readStart", "100", "readValueType", test.function() == 1 ? "bit" : "uint16")))
                        .withChannel(ChannelBuilder.create(sourceChannel).withAcceptedItemType(test.acceptedType())
                                .withType(new ChannelTypeUID("modbus", test.channel() + "-type")).build())
                        .build());
                waitForAssert(() -> assertNotNull(requireNonNull(registry.get(data)).getHandler()));
                links.add(original);
                waitForAssert(() -> assertEquals(test.initialState(),
                        requireNonNull(items.get(itemName)).getState().toString()));

                var plan = migration.preview().plan();
                assertEquals(1, plan.groups().size(), plan.manualWork().toString());
                assertTrue(plan.manualWork().isEmpty(), plan.manualWork().toString());
                var impact = plan.impacts().stream().filter(entry -> entry.itemName().equals(itemName)).findFirst()
                        .orElseThrow();
                assertTrue(impact.targetLinkPlanned(), "The exact source profile must be transferable");
                assertEquals(sourceChannel.toString(), impact.sourceChannelUid());
                String identity = plan.identity();
                ChannelUID targetChannel = new ChannelUID(impact.targetChannelUid());
                migration.apply(identity);
                waitForAssert(() -> assertNotNull(requireNonNull(registry.get(target)).getHandler()));
                assertTrue(links.getLinks(targetChannel).isEmpty(), "Staging must not dual-link the Item");
                assertNotNull(links.get(original.getUID()));
                assertEquals("keepLast", requireNonNull(registry.get(target)).getChannel(targetChannel.getId())
                        .getConfiguration().get("errorPolicy"));
                waitForAssert(() -> {
                    var observation = migration.validate(identity, poller.toString());
                    assertEquals(MigrationValidation.Outcome.MATCH, observation.outcome(), observation.toString());
                    assertEquals(sourceChannel.toString(), observation.comparisons().getFirst().sourceChannelUid());
                });
                migration.cutover(identity, poller.toString());
                assertNull(links.get(original.getUID()));
                assertEquals(1, links.getLinks(targetChannel).size());
                var transferred = links.getLinks(targetChannel).iterator().next();
                assertEquals(itemName, transferred.getItemName());
                assertTrue(transferred.getConfiguration().getProperties().entrySet()
                        .containsAll(test.profile().entrySet()));
                wire.value.set(test.changedWire());
                waitForAssert(() -> assertEquals(test.changedState(),
                        requireNonNull(items.get(itemName)).getState().toString()));

                int precedingFailures = wire.failures.get();
                wire.fail.set(true);
                waitForAssert(() -> {
                    assertTrue(wire.failures.get() > precedingFailures);
                    assertEquals(ThingStatus.OFFLINE, requireNonNull(registry.get(target)).getStatus());
                    assertEquals(MigrationValidation.Outcome.UNAVAILABLE,
                            migration.validate(identity, poller.toString()).outcome());
                    assertEquals(test.changedState(), requireNonNull(items.get(itemName)).getState().toString(),
                            "Read failure must invalidate evidence without erasing the last Item value");
                });
                wire.value.set(test.initialWire());
                wire.fail.set(false);
                waitForAssert(() -> assertEquals(test.initialState(),
                        requireNonNull(items.get(itemName)).getState().toString()));
                waitForAssert(() -> assertEquals(MigrationValidation.Outcome.MATCH,
                        migration.validate(identity, poller.toString()).outcome()));
                migration.revert(identity, itemName, sourceChannel.toString());
                assertTrue(links.getLinks(targetChannel).isEmpty());
                var restored = requireNonNull(links.get(original.getUID()));
                assertEquals(test.profile(), restored.getConfiguration().getProperties());
                wire.value.set(test.changedWire());
                waitForAssert(() -> assertEquals(test.changedState(),
                        requireNonNull(items.get(itemName)).getState().toString()));
                assertEquals(0, wire.writes.get(), "All fixture traffic must remain read-only");
            } finally {
                links.getAll().stream().filter(link -> itemName.equals(link.getItemName())).toList()
                        .forEach(link -> links.remove(link.getUID()));
                things.remove(target);
                things.remove(data);
                things.remove(poller);
                things.remove(endpoint);
                itemProvider.remove(itemName);
            }
        }
    }

    /** Accept reconnecting clients and propagate every server assertion to the JUnit thread. */
    static final class ReadWire implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<?> result;
        final AtomicInteger value;
        final AtomicBoolean fail = new AtomicBoolean();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();

        ReadWire(int function, int initialValue) throws IOException {
            value = new AtomicInteger(initialValue);
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            result = executor.submit(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(15000);
                        var input = socket.getInputStream();
                        var output = socket.getOutputStream();
                        byte[] request;
                        while ((request = input.readNBytes(12)).length != 0) {
                            assertEquals(12, request.length);
                            int receivedFunction = Byte.toUnsignedInt(request[7]);
                            if (receivedFunction != 1 && receivedFunction != 2 && receivedFunction != 3
                                    && receivedFunction != 4) {
                                writes.incrementAndGet();
                            }
                            assertEquals(function, receivedFunction, "Unexpected Modbus function");
                            assertEquals(100, (Byte.toUnsignedInt(request[8]) << 8) | Byte.toUnsignedInt(request[9]));
                            assertEquals(1, (Byte.toUnsignedInt(request[10]) << 8) | Byte.toUnsignedInt(request[11]));
                            byte[] response;
                            if (fail.get()) {
                                failures.incrementAndGet();
                                response = new byte[] { request[0], request[1], 0, 0, 0, 3, request[6],
                                        (byte) (function | 0x80), 2 };
                            } else if (function == 1 || function == 2) {
                                response = new byte[] { request[0], request[1], 0, 0, 0, 4, request[6], (byte) function,
                                        1, (byte) (value.get() == 0 ? 0 : 1) };
                            } else {
                                int word = value.get();
                                response = new byte[] { request[0], request[1], 0, 0, 0, 5, request[6], (byte) function,
                                        2, (byte) (word >> 8), (byte) word };
                            }
                            output.write(response);
                            output.flush();
                        }
                    } catch (IOException e) {
                        if (!server.isClosed()) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            server.close();
            try {
                result.get(20, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
