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

    @Test
    public void providerPreviewBytesLoadReloadAndRecoverLinkedItem() throws Exception {
        ItemRegistry items = getService(ItemRegistry.class);
        ItemChannelLinkRegistry links = getService(ItemChannelLinkRegistry.class);
        EventPublisher events = getService(EventPublisher.class);
        var migration = getService(org.openhab.binding.modbus.migration.ModbusMigrationService.class);
        var storage = getService(org.openhab.core.storage.StorageService.class).getStorage("modbus-migration-journal");
        assertNotNull(migration);
        assertNotNull(items);
        assertNotNull(links);
        assertNotNull(events);
        links.waitForCompletedAsyncActivationTasks();
        Path source = yamlFile.resolveSibling("provider-source.yaml");
        Path itemFile = yamlFile.resolveSibling("provider-items.yaml");
        try (WireFixture wire = new WireFixture()) {
            useEndpoint(wire.server.getLocalPort());
            wire.registers.set(new int[] { 41, 73 });
            writeModel(source, legacyYaml("value", 100), WatchService.Kind.CREATE);
            writeModel(itemFile, itemYaml("modbus:data:yaml-endpoint:legacy:value:number"), WatchService.Kind.CREATE);
            ThingUID data = new ThingUID("modbus:data:yaml-endpoint:legacy:value");
            waitForAssert(() -> {
                Thing legacy = requireNonNull(thingRegistry.get(data));
                assertNotNull(legacy.getHandler());
                assertEquals(java.util.List.of("default"), legacy.getConfiguration().get("writeTransform"));
                assertEquals(Boolean.FALSE, legacy.getConfiguration().get("writeMultipleEvenWithSingleRegisterOrCoil"));
                assertNotNull(links.get(new ItemChannelLink("YamlLive", new ChannelUID(data, "number")).getUID()));
            });
            var thingIds = thingRegistry.getAll().stream().map(Thing::getUID)
                    .collect(java.util.stream.Collectors.toSet());
            var linkIds = links.getAll().stream().map(ItemChannelLink::getUID)
                    .collect(java.util.stream.Collectors.toSet());
            var journalKeys = java.util.Set.copyOf(storage.getKeys());
            var preview = migration.providerPreview();
            assertEquals(1, preview.fileChanges().size(), preview.toString());
            var first = preview.fileChanges().getFirst();
            assertEquals(thingIds,
                    thingRegistry.getAll().stream().map(Thing::getUID).collect(java.util.stream.Collectors.toSet()));
            assertEquals(linkIds,
                    links.getAll().stream().map(ItemChannelLink::getUID).collect(java.util.stream.Collectors.toSet()));
            assertEquals(journalKeys, java.util.Set.copyOf(storage.getKeys()));
            assertEquals(1, first.linkPatches().size());
            assertEquals("modbus:data:yaml-endpoint:legacy:value:number",
                    first.linkPatches().getFirst().sourceChannelUid());
            assertTrue(!first.candidateYaml().contains("items:"), "Generated candidates must remain unlinked");
            ThingUID target = new ThingUID(first.targetPollerUid());
            ChannelUID original = new ChannelUID(first.linkPatches().getFirst().targetChannelUid());
            writeModel(yamlFile, first.candidateYaml(), WatchService.Kind.CREATE);
            waitForAssert(() -> {
                Thing candidate = requireNonNull(thingRegistry.get(target));
                assertEquals(ThingStatus.ONLINE, candidate.getStatus());
                assertNotNull(candidate.getHandler());
                assertEquals("Number", requireNonNull(candidate.getChannel(original.getId())).getAcceptedItemType());
                assertNotNull(candidate.getChannel(original.getId()).getChannelTypeUID());
                assertTrue(links.getLinks(original).isEmpty());
                assertNotNull(links.get(new ItemChannelLink("YamlLive", new ChannelUID(data, "number")).getUID()));
            });
            // Public provider provenance, not a managed registry insertion or an inferred filename.
            @SuppressWarnings("unchecked")
            var registry = (org.openhab.core.common.registry.AbstractRegistry<Thing, ThingUID, org.openhab.core.thing.ThingProvider>) thingRegistry;
            assertTrue(!(registry.getProvider(requireNonNull(
                    thingRegistry.get(target))) instanceof org.openhab.core.common.registry.ManagedProvider));
            assertNotNull(registry.getProvider(requireNonNull(thingRegistry.get(target))));
            assertEquals(registry.getProvider(requireNonNull(thingRegistry.get(data))),
                    registry.getProvider(requireNonNull(thingRegistry.get(target))));
            var candidateHandler = (org.openhab.binding.modbus.handler.ModbusPoller2ThingHandler) requireNonNull(
                    thingRegistry.get(target)).getHandler();
            assertNotNull(candidateHandler);
            candidateHandler.handleCommand(original, RefreshType.REFRESH);
            waitForAssert(() -> {
                var observation = candidateHandler.getObservation();
                assertTrue(observation.isPresent(), "The unlinked candidate must decode a fresh wire response");
                assertEquals(new DecimalType(41), observation.orElseThrow().states().get(original));
            });
            assertTrue(links.getLinks(original).isEmpty(), "Fresh decoded candidate data must not create an Item link");
            assertTrue(migration.providerPreview().fileChanges().isEmpty(),
                    "An existing candidate must refuse a second proposal");
            writeModel(itemFile, itemYaml(original.toString()), WatchService.Kind.MODIFY);
            awaitProviderLink(items, links, target, original);
            refreshState(events, items, wire, 41);

            // Remove only the disposable candidate to obtain a genuine new proposal, never rewrite emitted YAML.
            writeModel(itemFile, itemYaml("modbus:data:yaml-endpoint:legacy:value:number"), WatchService.Kind.MODIFY);
            Files.delete(yamlFile);
            yamlRepository.processWatchEvent(WatchService.Kind.DELETE, yamlFile);
            waitForAssert(() -> assertNull(thingRegistry.get(target)));
            writeModel(source, legacyYaml("replacement", 101), WatchService.Kind.MODIFY);
            writeModel(itemFile, itemYaml("modbus:data:yaml-endpoint:legacy:replacement:number"),
                    WatchService.Kind.MODIFY);
            waitForAssert(() -> {
                assertNull(thingRegistry.get(data));
                assertNotNull(
                        requireNonNull(thingRegistry.get(new ThingUID("modbus:data:yaml-endpoint:legacy:replacement")))
                                .getHandler());
                assertNotNull(links.get(new ItemChannelLink("YamlLive",
                        new ChannelUID("modbus:data:yaml-endpoint:legacy:replacement:number")).getUID()));
            });
            var secondPreview = migration.providerPreview();
            assertEquals(1, secondPreview.fileChanges().size(), secondPreview.toString());
            assertTrue(!preview.revisionDigest().equals(secondPreview.revisionDigest()));
            var second = secondPreview.fileChanges().getFirst();
            ChannelUID replacement = new ChannelUID(second.linkPatches().getFirst().targetChannelUid());
            assertTrue(!original.equals(replacement));
            // Recreate the old candidate, then MODIFY that actual provider model with the exact new proposal.
            writeModel(yamlFile, first.candidateYaml(), WatchService.Kind.CREATE);
            waitForAssert(() -> assertNotNull(requireNonNull(thingRegistry.get(target)).getChannel(original.getId())));
            writeModel(yamlFile, second.candidateYaml(), WatchService.Kind.MODIFY);
            writeModel(itemFile, itemYaml(replacement.toString()), WatchService.Kind.MODIFY);
            awaitProviderLink(items, links, target, replacement);
            waitForAssert(() -> {
                assertNull(requireNonNull(thingRegistry.get(target)).getChannel(original.getId()));
                assertTrue(links.getLinks(original).isEmpty());
            });
            wire.registers.set(new int[] { 52, 89 });
            refreshState(events, items, wire, 89);
            for (String failure : new String[] { "exception", "malformed" }) {
                wire.failure.set(failure);
                events.post(ItemEventFactory.createCommandEvent("YamlLive", RefreshType.REFRESH));
                waitForAssert(() -> {
                    assertEquals(ThingStatus.OFFLINE, requireNonNull(thingRegistry.get(target)).getStatus());
                    assertEquals(org.openhab.core.types.UnDefType.UNDEF,
                            requireNonNull(items.get("YamlLive")).getState());
                });
                wire.failure.set("");
                wire.registers.set(new int[] { 63, "exception".equals(failure) ? 97 : 109 });
                refreshState(events, items, wire, "exception".equals(failure) ? 97 : 109);
                waitForAssert(
                        () -> assertEquals(ThingStatus.ONLINE, requireNonNull(thingRegistry.get(target)).getStatus()));
            }
            assertEquals(new DecimalType(109), requireNonNull(items.get("YamlLive")).getState(),
                    "Generated provider reload must publish register 101 after malformed-response recovery");
        } finally {
            for (Path path : new Path[] { itemFile, source }) {
                Files.deleteIfExists(path);
                yamlRepository.processWatchEvent(WatchService.Kind.DELETE, path);
            }
        }
    }

    @Test
    public void nativeYamlModifyRetainsWireCommandsAfterFailureRecovery() throws Exception {
        ItemRegistry items = getService(ItemRegistry.class);
        ItemChannelLinkRegistry links = getService(ItemChannelLinkRegistry.class);
        EventPublisher events = getService(EventPublisher.class);
        assertNotNull(items);
        assertNotNull(links);
        assertNotNull(events);
        links.waitForCompletedAsyncActivationTasks();
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put("openhab.transform", "YAMLRELOAD");
        registerService((TransformationService) (function, value) -> new BigDecimal(value)
                .multiply(new BigDecimal("100")).add(new BigDecimal("7")).toPlainString(), properties);
        try (WireFixture wire = new WireFixture()) {
            useEndpoint(wire.server.getLocalPort());
            loadYaml(writableRoutingYaml("original", 100, 321));
            awaitLink(items, links, "YamlLive", "original");
            writeCommand(events, wire, "12.3", 321, 1237);
            writeModel(yamlFile, writableRoutingYaml("replacement", 101, 322), WatchService.Kind.MODIFY);
            awaitLink(items, links, "YamlLive", "replacement");
            waitForAssert(() -> {
                assertNull(requireNonNull(thingRegistry.get(POLLER_UID)).getChannel("original"));
                assertTrue(links.getLinks(new ChannelUID(POLLER_UID, "original")).isEmpty());
            });
            writeCommand(events, wire, "45.6", 322, 4567);
            wire.failure.set("writeException");
            writeCommand(events, wire, "23.4", 322, 2347);
            waitForAssert(() -> {
                Thing poller = requireNonNull(thingRegistry.get(POLLER_UID));
                assertEquals(ThingStatus.OFFLINE, poller.getStatus());
                assertEquals(ThingStatusDetail.COMMUNICATION_ERROR, poller.getStatusInfo().getStatusDetail());
            });
            wire.failure.set("");
            writeCommand(events, wire, "34.5", 322, 3457);
            waitForAssert(
                    () -> assertEquals(ThingStatus.ONLINE, requireNonNull(thingRegistry.get(POLLER_UID)).getStatus()));
            wire.failure.set("exception");
            events.post(ItemEventFactory.createCommandEvent("YamlLive", RefreshType.REFRESH));
            waitForAssert(() -> {
                assertEquals(ThingStatus.OFFLINE, requireNonNull(thingRegistry.get(POLLER_UID)).getStatus());
                assertEquals(org.openhab.core.types.UnDefType.UNDEF, requireNonNull(items.get("YamlLive")).getState());
            });
            wire.failure.set("");
            wire.registers.set(new int[] { 52, 89 });
            refreshState(events, items, wire, 89);
            waitForAssert(
                    () -> assertEquals(ThingStatus.ONLINE, requireNonNull(thingRegistry.get(POLLER_UID)).getStatus()));
            writeCommand(events, wire, "67.8", 322, 6787);
        }
    }

    private void writeCommand(EventPublisher events, WireFixture wire, String value, int address, int expected)
            throws Exception {
        wire.writes.clear();
        events.post(ItemEventFactory.createCommandEvent("YamlLive", new DecimalType(value)));
        int[] actual = wire.writes.poll(10, TimeUnit.SECONDS);
        assertNotNull(actual, "Public Item command must produce an FC6 frame");
        assertEquals(address, actual[0]);
        assertEquals(expected, actual[1], "The post-reload command must use the new address and nonidentity transform");
    }

    private String writableRoutingYaml(String channel, int address, int writeAddress) {
        // Independent writable contract: never add write settings to a migration-generated proposal.
        return routingYaml(channel, address).replace("valueType: int16", """
                valueType: int16
                          writeStart: "%d"
                          writeValueType: int16
                          writeTransform: "YAMLRELOAD(scale)"
                          writeMaxTries: 1
                """.formatted(writeAddress).stripTrailing());
    }

    private void writeModel(Path path, String yaml, WatchService.Kind kind) throws IOException {
        Files.writeString(path, yaml);
        yamlRepository.processWatchEvent(kind, path);
    }

    private String itemYaml(String channel) {
        return """
                version: 1
                items:
                  YamlLive:
                    type: Number
                    channel: %s
                """.formatted(channel);
    }

    private String legacyYaml(String name, int address) {
        return """
                version: 1
                things:
                  modbus:poller:yaml-endpoint:legacy:
                    bridge: modbus:tcp:yaml-endpoint
                    config:
                      start: 100
                      length: 2
                      type: holding
                      refresh: 0
                      cacheMillis: 0
                      maxTries: 1
                  modbus:data:yaml-endpoint:legacy:%s:
                    bridge: modbus:poller:yaml-endpoint:legacy
                    config:
                      readStart: "%d"
                      readValueType: int16
                """.formatted(name, address);
    }

    private void awaitProviderLink(ItemRegistry items, ItemChannelLinkRegistry links, ThingUID target,
            ChannelUID channel) {
        waitForAssert(() -> {
            Thing candidate = requireNonNull(thingRegistry.get(target));
            assertEquals(ThingStatus.ONLINE, candidate.getStatus());
            assertNotNull(candidate.getHandler());
            assertNotNull(candidate.getChannel(channel.getId()));
            assertNotNull(items.get("YamlLive"));
            assertEquals(java.util.Set.of(channel),
                    links.getAll().stream().filter(link -> link.getItemName().equals("YamlLive"))
                            .map(ItemChannelLink::getLinkedUID).collect(java.util.stream.Collectors.toSet()));
        });
    }

    private void refreshState(EventPublisher events, ItemRegistry items, WireFixture wire, int expected)
            throws Exception {
        wire.reads.clear();
        events.post(ItemEventFactory.createCommandEvent("YamlLive", RefreshType.REFRESH));
        assertNotNull(wire.reads.poll(10, TimeUnit.SECONDS), "A fresh FC3 wire read is required");
        waitForAssert(() -> assertEquals(new DecimalType(expected), requireNonNull(items.get("YamlLive")).getState()));
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
        private final AtomicReference<String> failure = new AtomicReference<>("");
        private final java.util.concurrent.BlockingQueue<int[]> writes = new java.util.concurrent.LinkedBlockingQueue<>();
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
                        if (request[7] == 6) {
                            byte[] response = failure.get().equals("writeException")
                                    ? new byte[] { request[0], request[1], 0, 0, 0, 3, request[6], (byte) 0x86, 2 }
                                    : request;
                            socket.getOutputStream().write(response);
                            socket.getOutputStream().flush();
                            writes.add(new int[] { address, value });
                            continue;
                        }
                        assertEquals(3, request[7]);
                        assertEquals(100, address);
                        assertEquals(2, value);
                        int[] data = registers.get();
                        byte[] response = switch (failure.get()) {
                            case "exception" ->
                                new byte[] { request[0], request[1], 0, 0, 0, 3, request[6], (byte) 0x83, 2 };
                            case "malformed" ->
                                new byte[] { request[0], request[1], 0, 0, 0, 5, request[6], 3, 2, 0, 1 };
                            default -> new byte[] { request[0], request[1], 0, 0, 0, 7, request[6], 3, 4,
                                    (byte) (data[0] >> 8), (byte) data[0], (byte) (data[1] >> 8), (byte) data[1] };
                        };
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
