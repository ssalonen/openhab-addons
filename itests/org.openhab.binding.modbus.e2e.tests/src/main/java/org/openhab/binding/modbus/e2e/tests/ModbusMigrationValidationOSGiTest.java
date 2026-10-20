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
    private static final class ReplacementThingProvider
            extends org.openhab.core.common.registry.AbstractProvider<org.openhab.core.thing.Thing>
            implements org.openhab.core.thing.ThingProvider {
        private final org.openhab.core.thing.Thing thing;

        ReplacementThingProvider(org.openhab.core.thing.Thing thing) {
            this.thing = thing;
        }

        @Override
        public java.util.Collection<org.openhab.core.thing.Thing> getAll() {
            return java.util.List.of(thing);
        }
    }

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
        var production = new org.openhab.core.thing.link.ItemChannelLink("MigrationValue",
                new ChannelUID(data, "number"));
        ((org.openhab.core.common.registry.AbstractRegistry<?, ?, ?>) registry).waitForCompletedAsyncActivationTasks();
        links.waitForCompletedAsyncActivationTasks();
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
                // Keep Core's real injected defaults: removing them concealed runtime classification failures.
                var readOnlyConfiguration = java.util.Objects.requireNonNull(registry.get(data)).getConfiguration();
                waitForAssert(() -> {
                    assertEquals(java.util.List.of("default"), readOnlyConfiguration.get("readTransform"));
                    assertEquals(java.util.List.of("default"), readOnlyConfiguration.get("writeTransform"));
                    assertEquals(Boolean.FALSE, readOnlyConfiguration.get("writeMultipleEvenWithSingleRegisterOrCoil"));
                    assertEquals(new java.math.BigDecimal("3"), readOnlyConfiguration.get("writeMaxTries"));
                    assertEquals(new java.math.BigDecimal("1000"),
                            readOnlyConfiguration.get("updateUnchangedValuesEveryMillis"));
                });
                var capturedConfiguration = Map.copyOf(readOnlyConfiguration.getProperties());
                links.add(production);
                var plan = service.preview().plan();
                assertEquals(1, plan.groups().size(), plan.yaml());
                assertTrue(plan.manualWork().isEmpty(), plan.manualWork().toString());
                assertEquals(capturedConfiguration, readOnlyConfiguration.getProperties());
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
                var reviewed = service.report(identity);
                var endpointConfiguration = java.util.Objects.requireNonNull(registry.get(endpoint)).getConfiguration();
                Object host = endpointConfiguration.get("host");
                endpointConfiguration.put("host", "192.0.2.123");
                assertThrows(IllegalArgumentException.class, () -> service.validate(identity, poller.toString()));
                assertThrows(IllegalArgumentException.class, () -> service.cutover(identity, poller.toString()));
                assertEquals(reviewed, service.report(identity));
                endpointConfiguration.put("host", host);
                Object transform = readOnlyConfiguration.get("readTransform");
                readOnlyConfiguration.put("readTransform", java.util.List.of("JS:private-transform"));
                assertThrows(IllegalArgumentException.class, () -> service.validate(identity, poller.toString()));
                assertThrows(IllegalArgumentException.class, () -> service.cutover(identity, poller.toString()));
                assertEquals(reviewed, service.report(identity));
                if (transform == null) {
                    readOnlyConfiguration.remove("readTransform");
                } else {
                    readOnlyConfiguration.put("readTransform", transform);
                }
                waitForAssert(() -> assertEquals(MigrationValidation.Outcome.MATCH,
                        service.validate(identity, poller.toString()).outcome()));
                service.cutover(identity, poller.toString());
                service.revert(identity, "MigrationValue", new ChannelUID(data, "number").toString());
                assertNotNull(links.get(production.getUID()));

                var staged = java.util.Objects.requireNonNull(registry.get(target));
                var replacement = BridgeBuilder.create(staged.getThingTypeUID(), target).withBridge(endpoint)
                        .withConfiguration(staged.getConfiguration()).withChannels(staged.getChannels())
                        .withProperties(staged.getProperties()).build();
                provider.remove(target);
                waitForAssert(() -> assertNull(registry.get(target)));
                var replacementProvider = new ReplacementThingProvider(replacement);
                var registration = registerService(replacementProvider,
                        org.openhab.core.thing.ThingProvider.class.getName());
                try {
                    waitForAssert(() -> assertNotNull(registry.get(target).getHandler()));
                    var rawValidation = new org.openhab.binding.modbus.migration.LiveModbusMigrationValidation(
                            registry);
                    waitForAssert(() -> assertEquals(MigrationValidation.Outcome.MATCH, rawValidation
                            .observe(plan, plan.groups().getFirst(), service.report(identity).stagedAt()).outcome()));
                    assertThrows(IllegalArgumentException.class, () -> service.validate(identity, poller.toString()));
                    assertThrows(IllegalArgumentException.class, () -> service.cutover(identity, poller.toString()));
                    assertThrows(IllegalStateException.class, () -> service.rollback(identity));
                    assertNotNull(registry.get(target));
                    assertNotNull(links.get(production.getUID()));
                    assertTrue(links.getLinks(new ChannelUID(target, "value")).isEmpty());
                } finally {
                    registration.unregister();
                    waitForAssert(() -> assertNull(registry.get(target)));
                }
            } finally {
                links.remove(production.getUID());
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
