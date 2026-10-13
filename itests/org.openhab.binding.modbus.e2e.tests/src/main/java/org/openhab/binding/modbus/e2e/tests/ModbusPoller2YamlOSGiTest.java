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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.modbus.internal.ModbusHandlerFactory;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.io.transport.modbus.ModbusManager;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.model.yaml.YamlModelRepository;
import org.openhab.core.service.WatchService;
import org.openhab.core.test.java.JavaOSGiTest;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.link.ItemChannelLink;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.transform.TransformationService;
import org.openhab.core.types.RefreshType;

/**
 * End-to-end coverage for poller2 Things supplied by the native YAML model provider.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ModbusPoller2YamlOSGiTest extends JavaOSGiTest {
    private static final ThingUID ENDPOINT_UID = new ThingUID("modbus:tcp:yaml-endpoint");
    private static final ThingUID POLLER_UID = new ThingUID("modbus:poller2:yaml-endpoint:holding");

    private @NonNullByDefault({}) ManagedThingProvider thingProvider;
    private @NonNullByDefault({}) ThingRegistry thingRegistry;
    private @NonNullByDefault({}) WatchService.WatchEventListener yamlRepository;
    private @NonNullByDefault({}) Path yamlFile;

    @BeforeEach
    public void setUp() throws IOException {
        registerVolatileStorageService();
        thingProvider = getService(ManagedThingProvider.class);
        thingRegistry = getService(ThingRegistry.class);
        yamlRepository = (WatchService.WatchEventListener) getService(YamlModelRepository.class);
        assertNotNull(thingProvider);
        assertNotNull(thingRegistry);
        assertNotNull(yamlRepository);

        ModbusHandlerFactory factory = getService(ThingHandlerFactory.class, ModbusHandlerFactory.class);
        ModbusManager manager = getService(ModbusManager.class);
        assertNotNull(factory);
        assertNotNull(manager);

        Bridge endpoint = BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), ENDPOINT_UID)
                .withConfiguration(new Configuration(Map.of("host", "127.0.0.1", "port", 502))).build();
        thingProvider.add(endpoint);
        waitForAssert(() -> assertNotNull(thingRegistry.get(ENDPOINT_UID)));

        WatchService watchService = getService(WatchService.class);
        assertNotNull(watchService);
        yamlFile = watchService.getWatchPath().resolve("things/poller2-e2e.yaml");
        Files.createDirectories(yamlFile.getParent());
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (Files.exists(yamlFile)) {
            Files.delete(yamlFile);
            yamlRepository.processWatchEvent(WatchService.Kind.DELETE, yamlFile);
        }
        thingProvider.remove(ENDPOINT_UID);
    }

    @Test
    public void nativeYamlCreatesDimensionedPoller2ChannelAndInitializesThing() throws IOException {
        loadYaml("""
                version: 1
                things:
                  modbus:poller2:yaml-endpoint:holding:
                    bridge: modbus:tcp:yaml-endpoint
                    config:
                      start: 100
                      length: 2
                      type: holding
                      refresh: 0
                    channels:
                      temperature:
                        itemType: Number
                        itemDimension: Temperature
                        config:
                          address: "100"
                          valueType: int16
                """);

        waitForAssert(() -> {
            Thing poller = requireNonNull(thingRegistry.get(POLLER_UID));
            assertEquals(ThingStatus.ONLINE, poller.getStatus());
            Channel temperature = poller.getChannel("temperature");
            assertNotNull(temperature);
            assertEquals("Number:Temperature", temperature.getAcceptedItemType());
        });
    }

    @Test
    public void nativeYamlProviderRefreshReinitializesPoller2Thing() throws IOException {
        loadYaml("""
                version: 1
                things:
                  modbus:poller2:yaml-endpoint:holding:
                    bridge: modbus:tcp:yaml-endpoint
                    config:
                      start: 100
                      length: 2
                      type: holding
                      refresh: 0
                    channels:
                      temperature:
                        itemType: Number
                        itemDimension: Temperature
                        config:
                          address: "100"
                          valueType: int16
                """);

        waitForAssert(() -> {
            Thing poller = requireNonNull(thingRegistry.get(POLLER_UID));
            assertNotNull(poller.getChannel("temperature"));
        });

        Files.writeString(yamlFile, """
                version: 1
                things:
                  modbus:poller2:yaml-endpoint:holding:
                    bridge: modbus:tcp:yaml-endpoint
                    config:
                      start: 100
                      length: 2
                      type: holding
                      refresh: 0
                    channels:
                      humidity:
                        itemType: Number
                        itemDimension: Dimensionless
                        config:
                          address: "101"
                          valueType: int16
                """);
        yamlRepository.processWatchEvent(WatchService.Kind.MODIFY, yamlFile);

        waitForAssert(() -> {
            Thing poller = requireNonNull(thingRegistry.get(POLLER_UID));
            assertEquals(ThingStatus.ONLINE, poller.getStatus());
            assertNull(poller.getChannel("temperature"));
            Channel humidity = poller.getChannel("humidity");
            assertNotNull(humidity);
            assertEquals("Number:Dimensionless", humidity.getAcceptedItemType());
        });
    }

    @Test
    public void nativeYamlReportsInvalidPoller2ChannelConfiguration() throws IOException {
        loadYaml("""
                version: 1
                things:
                  modbus:poller2:yaml-endpoint:holding:
                    bridge: modbus:tcp:yaml-endpoint
                    config:
                      start: 100
                      length: 1
                      type: holding
                      refresh: 0
                    channels:
                      outside-window:
                        itemType: Number
                        config:
                          address: "101"
                          valueType: int16
                """);

        waitForAssert(() -> {
            Thing poller = thingRegistry.get(POLLER_UID);
            assertNotNull(poller);
            assertEquals(ThingStatus.OFFLINE, poller.getStatus());
            assertEquals(ThingStatusDetail.CONFIGURATION_ERROR, poller.getStatusInfo().getStatusDetail());
            assertEquals("Channel 'outside-window': Address 101 is outside poll window 100..100",
                    poller.getStatusInfo().getDescription());
        });
    }

    @Test
    public void nativeYamlLinkedItemReceivesTransformedRegisterStates() throws Exception {
        ItemRegistry itemRegistry = getService(ItemRegistry.class);
        ItemChannelLinkRegistry linkRegistry = getService(ItemChannelLinkRegistry.class);
        assertNotNull(itemRegistry);
        assertNotNull(linkRegistry);
        EventPublisher eventPublisher = getService(EventPublisher.class);
        assertNotNull(eventPublisher);
        linkRegistry.waitForCompletedAsyncActivationTasks();

        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put("openhab.transform", "YAMLSCALE");
        registerService((TransformationService) (function, value) -> new BigDecimal(value)
                .multiply(new BigDecimal("0.1")).add(BigDecimal.ONE).toPlainString(), properties);

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(10000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            AtomicReference<@Nullable Socket> connection = new AtomicReference<>();
            try {
                Future<?> replies = executor.submit(() -> {
                    for (int raw : new int[] { 49, 79 }) {
                        try (Socket socket = server.accept()) {
                            connection.set(socket);
                            socket.setSoTimeout(10000);
                            byte[] request = socket.getInputStream().readNBytes(12);
                            assertEquals(12, request.length);
                            assertEquals(3, request[7]);
                            assertEquals(100, request[9]);
                            assertEquals(1, request[11]);
                            byte[] response = { request[0], request[1], 0, 0, 0, 5, request[6], 3, 2, 0, (byte) raw };
                            socket.getOutputStream().write(response);
                            socket.getOutputStream().flush();
                        }
                    }
                    return null;
                });
                thingProvider
                        .update(BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), ENDPOINT_UID)
                                .withConfiguration(
                                        new Configuration(Map.of("host", "127.0.0.1", "port", server.getLocalPort())))
                                .build());
                loadYaml("""
                        version: 1
                        things:
                          modbus:poller2:yaml-endpoint:holding:
                            bridge: modbus:tcp:yaml-endpoint
                            config:
                              start: 100
                              length: 1
                              type: holding
                              refresh: 0
                            channels:
                              scaled:
                                itemType: Number
                                config:
                                  address: "100"
                                  valueType: int16
                                  readTransform: "YAMLSCALE(scale)"
                        items:
                          YamlScaled:
                            type: Number
                            channel: modbus:poller2:yaml-endpoint:holding:scaled
                        """);
                ChannelUID channelUID = new ChannelUID(POLLER_UID, "scaled");
                waitForAssert(() -> {
                    Thing poller = requireNonNull(thingRegistry.get(POLLER_UID));
                    assertEquals(ThingStatus.ONLINE, poller.getStatus());
                    assertNotNull(poller.getHandler());
                    assertNotNull(linkRegistry.get(new ItemChannelLink("YamlScaled", channelUID).getUID()));
                    assertNotNull(itemRegistry.get("YamlScaled"));
                });
                eventPublisher.post(ItemEventFactory.createCommandEvent("YamlScaled", RefreshType.REFRESH));
                waitForAssert(() -> assertEquals(new DecimalType("5.9"),
                        requireNonNull(itemRegistry.get("YamlScaled")).getState()));
                eventPublisher.post(ItemEventFactory.createCommandEvent("YamlScaled", RefreshType.REFRESH));
                waitForAssert(() -> assertEquals(new DecimalType("8.9"),
                        requireNonNull(itemRegistry.get("YamlScaled")).getState()));
                replies.get(10, TimeUnit.SECONDS);
            } finally {
                Socket socket = connection.get();
                if (socket != null) {
                    socket.close();
                }
                server.close();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void nativeYamlLinkedItemCommandsWriteTransformedHoldingRegisters() throws Exception {
        ItemRegistry itemRegistry = getService(ItemRegistry.class);
        ItemChannelLinkRegistry linkRegistry = getService(ItemChannelLinkRegistry.class);
        EventPublisher eventPublisher = getService(EventPublisher.class);
        assertNotNull(itemRegistry);
        assertNotNull(linkRegistry);
        assertNotNull(eventPublisher);
        linkRegistry.waitForCompletedAsyncActivationTasks();

        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put("openhab.transform", "YAMLWRITE");
        registerService((TransformationService) (function, value) -> new BigDecimal(value)
                .multiply(new BigDecimal("100")).add(new BigDecimal("7")).toPlainString(), properties);

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(10000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            AtomicReference<@Nullable Socket> connection = new AtomicReference<>();
            try {
                Future<?> replies = executor.submit(() -> {
                    int writes = 0;
                    int[] expectedValues = { 1237, 4567 };
                    while (writes < expectedValues.length) {
                        try (Socket socket = server.accept()) {
                            connection.set(socket);
                            socket.setSoTimeout(10000);
                            byte[] request = socket.getInputStream().readNBytes(12);
                            assertEquals(12, request.length);
                            // Core can request an initial state when the native Item link becomes active.
                            if (request[7] == 3) {
                                assertEquals(100,
                                        (Byte.toUnsignedInt(request[8]) << 8) | Byte.toUnsignedInt(request[9]));
                                assertEquals(1,
                                        (Byte.toUnsignedInt(request[10]) << 8) | Byte.toUnsignedInt(request[11]));
                                byte[] response = { request[0], request[1], 0, 0, 0, 5, request[6], 3, 2, 0, 0 };
                                socket.getOutputStream().write(response);
                                socket.getOutputStream().flush();
                                continue;
                            }
                            // FC6 acknowledges a write by echoing the complete request.
                            socket.getOutputStream().write(request);
                            socket.getOutputStream().flush();
                            assertEquals(0, request[2]);
                            assertEquals(0, request[3]);
                            assertEquals(0, request[4]);
                            assertEquals(6, request[5]);
                            assertEquals(1, request[6]);
                            assertEquals(6, request[7]);
                            assertEquals(321, (Byte.toUnsignedInt(request[8]) << 8) | Byte.toUnsignedInt(request[9]));
                            assertEquals(expectedValues[writes++],
                                    (Byte.toUnsignedInt(request[10]) << 8) | Byte.toUnsignedInt(request[11]),
                                    "FC6 wire value must contain the transformed Item command");
                        }
                    }
                    return null;
                });
                thingProvider
                        .update(BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), ENDPOINT_UID)
                                .withConfiguration(
                                        new Configuration(Map.of("host", "127.0.0.1", "port", server.getLocalPort())))
                                .build());
                loadYaml("""
                        version: 1
                        things:
                          modbus:poller2:yaml-endpoint:holding:
                            bridge: modbus:tcp:yaml-endpoint
                            config:
                              start: 100
                              length: 1
                              type: holding
                              refresh: 0
                            channels:
                              writable:
                                itemType: Number
                                config:
                                  address: "100"
                                  valueType: int16
                                  writeStart: "321"
                                  writeValueType: int16
                                  writeTransform: "YAMLWRITE(scale)"
                                  writeMultipleEvenWithSingleRegisterOrCoil: false
                                  writeMaxTries: 1
                        items:
                          YamlWritable:
                            type: Number
                            channel: modbus:poller2:yaml-endpoint:holding:writable
                        """);
                ChannelUID channelUID = new ChannelUID(POLLER_UID, "writable");
                waitForAssert(() -> {
                    Thing poller = requireNonNull(thingRegistry.get(POLLER_UID));
                    assertEquals(ThingStatus.ONLINE, poller.getStatus());
                    assertNotNull(poller.getHandler());
                    assertNotNull(linkRegistry.get(new ItemChannelLink("YamlWritable", channelUID).getUID()));
                    assertNotNull(itemRegistry.get("YamlWritable"));
                });
                eventPublisher.post(ItemEventFactory.createCommandEvent("YamlWritable", new DecimalType("12.3")));
                eventPublisher.post(ItemEventFactory.createCommandEvent("YamlWritable", new DecimalType("45.6")));
                replies.get(10, TimeUnit.SECONDS);
            } finally {
                Socket socket = connection.get();
                if (socket != null) {
                    socket.close();
                }
                server.close();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void nativeYamlModifyReplacesLinkedChannelStateRoute() throws Exception {
        ItemRegistry items = getService(ItemRegistry.class);
        ItemChannelLinkRegistry links = getService(ItemChannelLinkRegistry.class);
        EventPublisher events = getService(EventPublisher.class);
        assertNotNull(items);
        assertNotNull(links);
        assertNotNull(events);
        links.waitForCompletedAsyncActivationTasks();

        try (WireFixture wire = new WireFixture()) {
            useEndpoint(wire.server.getLocalPort());
            wire.registers.set(new int[] { 41, 73 });
            loadYaml(routingYaml("original", 100));
            awaitLink(items, links, "YamlLive", "original");
            events.post(ItemEventFactory.createCommandEvent("YamlLive", RefreshType.REFRESH));
            waitForAssert(() -> assertEquals(new DecimalType(41), requireNonNull(items.get("YamlLive")).getState()));

            Files.writeString(yamlFile, routingYaml("replacement", 101));
            yamlRepository.processWatchEvent(WatchService.Kind.MODIFY, yamlFile);
            awaitLink(items, links, "YamlLive", "replacement");
            waitForAssert(() -> {
                assertNull(requireNonNull(thingRegistry.get(POLLER_UID)).getChannel("original"));
                assertNull(links.get(new ItemChannelLink("YamlLive", new ChannelUID(POLLER_UID, "original")).getUID()));
            });
            // Distinct values after MODIFY rule out the pre-modification state or cached read.
            wire.registers.set(new int[] { 52, 89 });
            wire.reads.clear();
            events.post(ItemEventFactory.createCommandEvent("YamlLive", RefreshType.REFRESH));
            assertNotNull(wire.reads.poll(10, TimeUnit.SECONDS), "MODIFY must retain an actual FC3 wire read route");
            waitForAssert(() -> assertEquals(new DecimalType(89), requireNonNull(items.get("YamlLive")).getState(),
                    "MODIFY must route the replacement register state to the linked Item"));
        }
    }

    private void useEndpoint(int port) {
        thingProvider.update(BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), ENDPOINT_UID)
                .withConfiguration(new Configuration(Map.of("host", "127.0.0.1", "port", port))).build());
    }

    private void awaitLink(ItemRegistry items, ItemChannelLinkRegistry links, String item, String channel) {
        waitForAssert(() -> {
            Thing poller = requireNonNull(thingRegistry.get(POLLER_UID));
            assertEquals(ThingStatus.ONLINE, poller.getStatus());
            assertNotNull(poller.getHandler());
            assertNotNull(poller.getChannel(channel));
            assertNotNull(items.get(item));
            assertNotNull(links.get(new ItemChannelLink(item, new ChannelUID(POLLER_UID, channel)).getUID()));
        });
    }

    private String routingYaml(String channel, int address) {
        return """
                version: 1
                things:
                  modbus:poller2:yaml-endpoint:holding:
                    bridge: modbus:tcp:yaml-endpoint
                    config:
                      start: 100
                      length: 2
                      type: holding
                      refresh: 0
                      cacheMillis: 0
                      maxTries: 1
                    channels:
                      %s:
                        itemType: Number
                        config:
                          address: "%d"
                          valueType: int16
                items:
                  YamlLive:
                    type: Number
                    channel: modbus:poller2:yaml-endpoint:holding:%s
                """.formatted(channel, address, channel);
    }

    private static class WireFixture implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final AtomicReference<@Nullable Socket> connection = new AtomicReference<>();
        private final AtomicReference<int[]> registers = new AtomicReference<>(new int[] { 0, 0 });
        private final java.util.concurrent.BlockingQueue<Integer> reads = new java.util.concurrent.LinkedBlockingQueue<>();
        private final Future<?> replies;
        private volatile boolean closed;

        private WireFixture() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            server.setSoTimeout(10000);
            replies = executor.submit(() -> {
                while (!closed) {
                    try (Socket socket = server.accept()) {
                        connection.set(socket);
                        socket.setSoTimeout(10000);
                        byte[] request = socket.getInputStream().readNBytes(12);
                        assertEquals(12, request.length);
                        assertEquals(0, request[2]);
                        assertEquals(0, request[3]);
                        assertEquals(0, request[4]);
                        assertEquals(6, request[5]);
                        assertEquals(1, request[6]);
                        int address = (Byte.toUnsignedInt(request[8]) << 8) | Byte.toUnsignedInt(request[9]);
                        int value = (Byte.toUnsignedInt(request[10]) << 8) | Byte.toUnsignedInt(request[11]);
                        assertEquals(3, request[7]);
                        assertEquals(100, address);
                        assertEquals(2, value);
                        int[] data = registers.get();
                        byte[] response = { request[0], request[1], 0, 0, 0, 7, request[6], 3, 4, (byte) (data[0] >> 8),
                                (byte) data[0], (byte) (data[1] >> 8), (byte) data[1] };
                        socket.getOutputStream().write(response);
                        reads.add(data[1]);
                        socket.getOutputStream().flush();
                    } catch (IOException e) {
                        if (!closed) {
                            throw e;
                        }
                    }
                }
                return null;
            });
        }

        @Override
        public void close() throws Exception {
            closed = true;
            try {
                server.close();
                Socket socket = connection.get();
                if (socket != null) {
                    socket.close();
                }
                replies.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    private void loadYaml(String yaml) throws IOException {
        Files.writeString(yamlFile, yaml);
        yamlRepository.processWatchEvent(WatchService.Kind.CREATE, yamlFile);
    }
}
