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

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.modbus.migration.MigrationValidation;
import org.openhab.binding.modbus.migration.ModbusMigrationService;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.test.java.JavaOSGiTest;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;

/**
 * Live transport validation without candidate Item links.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ModbusMigrationValidationOSGiTest extends JavaOSGiTest {
    @Test
    public void comparesFreshLegacyAndUnlinkedCandidateDeviceReads() throws Exception {
        registerVolatileStorageService();
        ManagedThingProvider provider = getService(ManagedThingProvider.class);
        ThingRegistry registry = getService(ThingRegistry.class);
        ItemChannelLinkRegistry links = getService(ItemChannelLinkRegistry.class);
        assertNotNull(provider);
        assertNotNull(registry);
        assertNotNull(links);
        ThingUID endpoint = new ThingUID("modbus:tcp:validation");
        ThingUID poller = new ThingUID("modbus:poller:validation:holding");
        ThingUID data = new ThingUID("modbus:data:validation:holding:value");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var changingValues = new java.util.concurrent.atomic.AtomicBoolean();
        var failReads = new java.util.concurrent.atomic.AtomicBoolean();
        var nextValue = new java.util.concurrent.atomic.AtomicInteger(42);
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(15000);
            Future<?> wire = executor.submit(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(15000);
                        var input = socket.getInputStream();
                        var output = socket.getOutputStream();
                        while (!Thread.currentThread().isInterrupted()) {
                            byte[] request = input.readNBytes(12);
                            if (request.length == 0) {
                                break;
                            }
                            assertEquals(12, request.length);
                            assertEquals(3, request[7]);
                            assertEquals(0, request[8]);
                            assertEquals(100, request[9]);
                            assertEquals(0, request[10]);
                            assertEquals(1, request[11]);
                            int value = changingValues.get() ? nextValue.incrementAndGet() : 42;
                            byte[] response = failReads.get()
                                    ? new byte[] { request[0], request[1], 0, 0, 0, 3, request[6], (byte) 0x83, 2 }
                                    : new byte[] { request[0], request[1], 0, 0, 0, 5, request[6], 3, 2,
                                            (byte) (value >> 8), (byte) value };
                            output.write(response);
                            output.flush();
                        }
                    } catch (java.io.IOException e) {
                        if (!server.isClosed()) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
            });
            try {
                provider.add(BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), endpoint)
                        .withConfiguration(new Configuration(Map.of("host", "127.0.0.1", "port", server.getLocalPort(),
                                "id", 1, "reconnectAfterMillis", 0)))
                        .build());
                provider.add(BridgeBuilder.create(new ThingTypeUID("modbus", "poller"), poller).withBridge(endpoint)
                        .withConfiguration(new Configuration(Map.of("start", 100, "length", 1, "type", "holding",
                                "refresh", 100, "cacheMillis", 1000)))
                        .build());
                provider.add(ThingBuilder.create(new ThingTypeUID("modbus", "data"), data).withBridge(poller)
                        .withConfiguration(new Configuration(Map.of("readStart", "100", "readValueType", "uint16")))
                        .withChannel(ChannelBuilder.create(new ChannelUID(data, "number"))
                                .withAcceptedItemType("Number").build())
                        .build());
                waitForAssert(() -> assertNotNull(registry.get(data).getHandler()));
                ModbusMigrationService service = getService(ModbusMigrationService.class);
                assertNotNull(service);
                // Core supplies inactive write defaults; this fixture explicitly models a read-only source.
                var readOnlyConfiguration = java.util.Objects.requireNonNull(registry.get(data)).getConfiguration();
                readOnlyConfiguration.getProperties().keySet().stream()
                        .filter(key -> !java.util.Set.of("readStart", "readValueType", "readTransform").contains(key))
                        .toList().forEach(readOnlyConfiguration::remove);
                var plan = service.preview().plan();
                assertEquals(1, plan.groups().size(), plan.yaml());
                String identity = plan.identity();
                service.apply(identity);
                ThingUID target = new ThingUID("modbus:poller2:validation:holding");
                waitForAssert(() -> {
                    assertNotNull(registry.get(target));
                    assertNotNull(java.util.Objects.requireNonNull(registry.get(target)).getHandler());
                });
                assertTrue(links.getLinks(new ChannelUID(target, "value")).isEmpty());
                waitForAssert(() -> {
                    var observation = service.validate(identity, poller.toString());
                    assertEquals(MigrationValidation.Outcome.MATCH, observation.outcome(), observation.toString());
                    assertEquals("42", observation.comparisons().getFirst().sourceValue());
                    assertEquals("42", observation.comparisons().getFirst().targetValue());
                });
                changingValues.set(true);
                waitForAssert(() -> assertEquals(MigrationValidation.Outcome.MISMATCH,
                        service.validate(identity, poller.toString()).outcome()));
                failReads.set(true);
                waitForAssert(() -> {
                    assertEquals(MigrationValidation.Outcome.UNAVAILABLE,
                            service.validate(identity, poller.toString()).outcome());
                    assertTrue(((org.openhab.binding.modbus.migration.ModbusObservationSource) java.util.Objects
                            .requireNonNull(registry.get(target)).getHandler()).getObservation().isEmpty());
                    assertTrue(((org.openhab.binding.modbus.migration.ModbusObservationSource) java.util.Objects
                            .requireNonNull(registry.get(data)).getHandler()).getObservation().isEmpty());
                });
                changingValues.set(false);
                failReads.set(false);
                waitForAssert(() -> assertEquals(MigrationValidation.Outcome.MATCH,
                        service.validate(identity, poller.toString()).outcome()));
            } finally {
                provider.remove(new ThingUID("modbus:poller2:validation:holding"));
                provider.remove(data);
                provider.remove(poller);
                provider.remove(endpoint);
                server.close();
            }
            wire.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
