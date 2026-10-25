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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.modbus.e2e.tests.ModbusReadOnlyMigrationOSGiTest.ReadWire;
import org.openhab.binding.modbus.migration.MigrationPreview;
import org.openhab.binding.modbus.migration.MigrationValidation;
import org.openhab.binding.modbus.migration.ModbusMigrationService;
import org.openhab.core.common.registry.AbstractProvider;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.ManagedItemProvider;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.model.yaml.YamlModelRepository;
import org.openhab.core.service.WatchService;
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
import org.openhab.core.transform.Transformation;
import org.openhab.core.transform.TransformationProvider;
import org.openhab.core.transform.TransformationRegistry;

/**
 * Real JS engine and MAP add-on profiles, multi-channel diagnostics and unmodified emitted YAML.
 * No transformation service is supplied by this fixture: Core routes all Item updates through native profiles.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ModbusReadOnlyProfilesOSGiTest extends JavaOSGiTest {
    private static final ThingUID ENDPOINT = new ThingUID("modbus:tcp:profiles");
    private static final ThingUID POLLER = new ThingUID("modbus:poller:profiles:window");
    private static final ThingUID DATA = new ThingUID("modbus:data:profiles:window:value");
    private static final ThingUID TARGET = new ThingUID("modbus:poller2:profiles:window");
    private static final String VALUE = "ReadProfileValue";
    private static final String AVAILABLE = "ReadProfileAvailable";
    private static final String YAML_VALUE = "ReadProfileYamlValue";
    private static final Map<String, Object> AVAILABILITY = Map.of("profile", "transform:MAP", "function",
            "fixture-availability.map");

    @Test
    public void numberJsToItemAndTimestampMapSurviveCutoverFailureRecoveryAndExactUndo() throws Exception {
        profilesRoundTrip(false, false);
    }

    @Test
    public void numberJsWithCommandConfigurationAndTimestampMapSurviveExactUndo() throws Exception {
        profilesRoundTrip(false, true);
    }

    @Test
    public void coilSwitchAndTimestampMapSurviveCutoverFailureRecoveryAndExactUndo() throws Exception {
        profilesRoundTrip(true, false);
    }

    private void profilesRoundTrip(boolean coil, boolean commandScript) throws Exception {
        registerVolatileStorageService();
        registerTransformations();
        var things = requireNonNull(getService(ManagedThingProvider.class));
        var registry = requireNonNull(getService(ThingRegistry.class));
        var links = requireNonNull(getService(ItemChannelLinkRegistry.class));
        var provider = requireNonNull(getService(ManagedItemProvider.class));
        var items = requireNonNull(getService(ItemRegistry.class));
        var migration = requireNonNull(getService(ModbusMigrationService.class));
        var validation = requireNonNull(getService(MigrationValidation.class));
        links.waitForCompletedAsyncActivationTasks();
        Map<String, Object> js = commandScript
                ? Map.of("profile", "transform:JS", "toItemScript", "config:js:fixtureToItem", "commandFromItemScript",
                        "config:js:fixtureFromItem")
                : Map.of("profile", "transform:JS", "toItemScript", "config:js:fixtureToItem");
        var valueLink = new ItemChannelLink(VALUE, new ChannelUID(DATA, coil ? "switch" : "number"),
                new Configuration(coil ? Map.of() : js));
        var availabilityLink = new ItemChannelLink(AVAILABLE, new ChannelUID(DATA, "lastReadSuccess"),
                new Configuration(AVAILABILITY));
        List<ItemChannelLink> originals = List.of(valueLink, availabilityLink);
        try (ReadWire wire = new ReadWire(coil ? 1 : 3, coil ? 1 : 49)) {
            try {
                if (coil) {
                    provider.add(new SwitchItem(VALUE));
                } else {
                    provider.add(new NumberItem(VALUE));
                }
                provider.add(new SwitchItem(AVAILABLE));
                createLegacy(things, registry, wire.port(), coil, false);
                originals.forEach(links::add);
                awaitState(items, VALUE, coil ? "ON" : "5.9");
                awaitState(items, AVAILABLE, "ON");
                var plan = migration.preview().plan();
                assertPlan(plan, originals);
                String identity = plan.identity();
                migration.apply(identity);
                waitForAssert(() -> assertNotNull(requireNonNull(registry.get(TARGET)).getHandler()));
                for (var link : plan.groups().getFirst().links()) {
                    assertTrue(links.getLinks(new ChannelUID(link.targetChannelUid())).isEmpty());
                    assertNotNull(links.get(
                            new ItemChannelLink(link.itemName(), new ChannelUID(link.sourceChannelUid())).getUID()));
                }
                awaitMatch(migration, plan);
                migration.cutover(identity, POLLER.toString());
                for (var link : plan.groups().getFirst().links()) {
                    assertNull(links.get(
                            new ItemChannelLink(link.itemName(), new ChannelUID(link.sourceChannelUid())).getUID()));
                    var moved = requireNonNull(links.get(
                            new ItemChannelLink(link.itemName(), new ChannelUID(link.targetChannelUid())).getUID()));
                    assertEquals(link.targetConfiguration(), moved.getConfiguration().getProperties());
                    var expected = new HashMap<>(link.configuration());
                    expected.putIfAbsent("profile", "system:default");
                    var marker = moved.getConfiguration().get("modbusMigrationOwner");
                    assertInstanceOf(String.class, marker);
                    assertFalse(((String) marker).isBlank());
                    expected.put("modbusMigrationOwner", marker);
                    assertEquals(expected, moved.getConfiguration().getProperties(),
                            "Only the ownership marker may be added");
                    assertEquals(Set.of(new ChannelUID(link.targetChannelUid())), itemChannels(links, link.itemName()));
                }
                wire.value.set(coil ? 0 : 79);
                awaitState(items, VALUE, coil ? "OFF" : "8.9");
                awaitState(items, AVAILABLE, "ON");
                int failures = wire.failures.get();
                wire.fail.set(true);
                waitForAssert(() -> {
                    assertTrue(wire.failures.get() > failures);
                    assertEquals(ThingStatus.OFFLINE, requireNonNull(registry.get(TARGET)).getStatus());
                    var unavailable = migration.validate(identity, POLLER.toString());
                    assertEquals(MigrationValidation.Outcome.UNAVAILABLE, unavailable.outcome());
                    // Workflow freshness rejection may legitimately return aggregate-only UNAVAILABLE.
                    // Independently retain the detailed per-channel failure contract at the observation boundary.
                    var details = validation.observe(plan, plan.groups().getFirst(),
                            requireNonNull(migration.report(identity).stagedAt()));
                    assertEquals(MigrationValidation.Outcome.UNAVAILABLE, details.outcome());
                    assertEquals(2, details.comparisons().size());
                    details.comparisons().forEach(
                            comparison -> assertEquals(MigrationValidation.Outcome.UNAVAILABLE, comparison.outcome()));
                    assertEquals(coil ? "OFF" : "8.9", requireNonNull(items.get(VALUE)).getState().toString());
                    assertEquals("ON", requireNonNull(items.get(AVAILABLE)).getState().toString(),
                            "keepLast retains the last successful timestamp, and MAP retains its availability state");
                });
                wire.value.set(coil ? 1 : 49);
                wire.fail.set(false);
                awaitState(items, VALUE, coil ? "ON" : "5.9");
                awaitMatch(migration, plan);
                for (var original : originals) {
                    migration.revert(identity, original.getItemName(), original.getLinkedUID().toString());
                    assertEquals(original.getConfiguration().getProperties(),
                            requireNonNull(links.get(original.getUID())).getConfiguration().getProperties());
                    assertEquals(Set.of(original.getLinkedUID()), itemChannels(links, original.getItemName()));
                }
                for (var link : plan.groups().getFirst().links()) {
                    assertTrue(links.getLinks(new ChannelUID(link.targetChannelUid())).isEmpty());
                }
                wire.value.set(coil ? 0 : 79);
                awaitState(items, VALUE, coil ? "OFF" : "8.9");
                awaitState(items, AVAILABLE, "ON");
                assertEquals(0, wire.writes.get());
            } finally {
                cleanup(things, links, provider);
            }
        }
    }

    @Test
    public void emittedMapSequenceLoadsThroughNativeYamlAndTransformsBeforeSwitchConversion() throws Exception {
        registerVolatileStorageService();
        registerTransformations();
        var things = requireNonNull(getService(ManagedThingProvider.class));
        var registry = requireNonNull(getService(ThingRegistry.class));
        var links = requireNonNull(getService(ItemChannelLinkRegistry.class));
        var provider = requireNonNull(getService(ManagedItemProvider.class));
        var items = requireNonNull(getService(ItemRegistry.class));
        var migration = requireNonNull(getService(ModbusMigrationService.class));
        var validation = requireNonNull(getService(MigrationValidation.class));
        var repository = (WatchService.WatchEventListener) requireNonNull(getService(YamlModelRepository.class));
        var watch = requireNonNull(getService(WatchService.class));
        Path file = watch.getWatchPath().resolve("things/readonly-emitted-map.yaml");
        assertFalse(Files.exists(file), "Never replace an existing model");
        Files.createDirectories(file.getParent());
        links.waitForCompletedAsyncActivationTasks();
        try (ReadWire wire = new ReadWire(3, 1)) {
            try {
                provider.add(new SwitchItem(VALUE));
                provider.add(new SwitchItem(YAML_VALUE));
                createLegacy(things, registry, wire.port(), false, true);
                var source = new ItemChannelLink(VALUE, new ChannelUID(DATA, "switch"));
                links.add(source);
                awaitState(items, VALUE, "OFF");
                var plan = migration.preview().plan();
                assertEquals(1, plan.groups().size(), plan.manualWork().toString());
                assertTrue(plan.manualWork().isEmpty(), plan.manualWork().toString());
                var group = plan.groups().getFirst();
                var mapping = group.channels().getFirst();
                var channel = new ChannelUID(group.targetPollerUid() + ":" + mapping.id());
                Instant stagedAt = Instant.now();
                // Exactly the public renderer output, not authored YAML or a scalar replacement of its sequence.
                Files.writeString(file, plan.yaml());
                repository.processWatchEvent(WatchService.Kind.CREATE, file);
                waitForAssert(() -> {
                    var target = requireNonNull(registry.get(TARGET));
                    assertNotNull(target.getHandler());
                    assertEquals(mapping.configuration().get("readTransform"),
                            requireNonNull(target.getChannel(channel.getId())).getConfiguration().get("readTransform"));
                });
                assertTrue(links.getLinks(channel).isEmpty(), "Emitted candidates must stay unlinked");
                assertNotNull(links.get(source.getUID()));
                waitForAssert(() -> assertEquals(MigrationValidation.Outcome.MATCH,
                        validation.observe(plan, group, stagedAt).outcome()));
                // Dedicated disposable Item only; provider-owned production cutover is not authorized.
                links.add(new ItemChannelLink(YAML_VALUE, channel));
                awaitState(items, YAML_VALUE, "OFF");
                wire.value.set(0);
                awaitState(items, VALUE, "ON");
                awaitState(items, YAML_VALUE, "ON");
                waitForAssert(() -> assertEquals(MigrationValidation.Outcome.MATCH,
                        validation.observe(plan, group, stagedAt).outcome()));
                assertEquals(Set.of(source.getLinkedUID()), itemChannels(links, VALUE));
                assertEquals(0, wire.writes.get());
                links.remove(new ItemChannelLink(YAML_VALUE, channel).getUID());
                Files.delete(file);
                repository.processWatchEvent(WatchService.Kind.DELETE, file);
                waitForAssert(() -> assertNull(registry.get(TARGET)));
                assertNotNull(links.get(source.getUID()));
            } finally {
                if (Files.deleteIfExists(file)) {
                    repository.processWatchEvent(WatchService.Kind.DELETE, file);
                }
                cleanup(things, links, provider);
            }
        }
    }

    private void registerTransformations() {
        registerService((TransformationProvider) new FixtureTransformationProvider());
        var registry = requireNonNull(getService(TransformationRegistry.class));
        waitForAssert(() -> {
            assertNotNull(registry.get("config:js:fixtureToItem"));
            assertNotNull(registry.get("fixture-invert.map", null));
            assertNotNull(registry.get("fixture-availability.map", null));
        });
    }

    private void createLegacy(ManagedThingProvider things, ThingRegistry registry, int port, boolean coil,
            boolean invert) {
        things.add(
                BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), ENDPOINT)
                        .withConfiguration(new Configuration(
                                Map.of("host", "127.0.0.1", "port", port, "id", 1, "reconnectAfterMillis", 0)))
                        .build());
        things.add(BridgeBuilder.create(new ThingTypeUID("modbus", "poller"), POLLER).withBridge(ENDPOINT)
                .withConfiguration(new Configuration(Map.of("start", 100, "length", 1, "type",
                        coil ? "coil" : "holding", "refresh", 100, "cacheMillis", 0, "maxTries", 1)))
                .build());
        Map<String, Object> config = new HashMap<>(
                Map.of("readStart", "100", "readValueType", coil ? "bit" : "uint16"));
        if (invert) {
            config.put("readTransform", List.of("MAP(fixture-invert.map)"));
        }
        String role = coil || invert ? "switch" : "number";
        things.add(ThingBuilder.create(new ThingTypeUID("modbus", "data"), DATA).withBridge(POLLER)
                .withConfiguration(new Configuration(config))
                .withChannel(ChannelBuilder.create(new ChannelUID(DATA, role))
                        .withAcceptedItemType(coil || invert ? "Switch" : "Number")
                        .withType(new ChannelTypeUID("modbus", role + "-type")).build())
                .withChannel(
                        ChannelBuilder.create(new ChannelUID(DATA, "lastReadSuccess")).withAcceptedItemType("DateTime")
                                .withType(new ChannelTypeUID("modbus", "last-successful-read-type")).build())
                .build());
        waitForAssert(() -> assertNotNull(requireNonNull(registry.get(DATA)).getHandler()));
    }

    private void assertPlan(MigrationPreview plan, List<ItemChannelLink> originals) {
        assertTrue(plan.manualWork().isEmpty(), plan.manualWork().toString());
        assertEquals(1, plan.groups().size());
        var group = plan.groups().getFirst();
        assertEquals(2, group.channels().size(), "Value and read diagnostic must be independently planned");
        assertEquals(2, group.links().size());
        assertEquals(originals.stream().map(link -> link.getLinkedUID().toString()).collect(Collectors.toSet()),
                group.links().stream().map(link -> link.sourceChannelUid()).collect(Collectors.toSet()));
        for (var original : originals) {
            var planned = group.links().stream().filter(link -> link.itemName().equals(original.getItemName()))
                    .findFirst().orElseThrow();
            assertEquals(original.getConfiguration().getProperties(), planned.configuration());
        }
        assertEquals(1, group.channels().stream()
                .filter(channel -> "lastReadSuccess".equals(channel.configuration().get("readDiagnostic"))).count());
    }

    private void awaitMatch(ModbusMigrationService migration, MigrationPreview plan) {
        Instant requestedAt = Instant.now();
        waitForAssert(() -> {
            var observation = migration.validate(plan.identity(), POLLER.toString());
            assertEquals(MigrationValidation.Outcome.MATCH, observation.outcome(), observation.toString());
            assertEquals(2, observation.comparisons().size());
            assertEquals(
                    plan.groups().getFirst().channels().stream().map(channel -> channel.sourceChannelUid())
                            .collect(Collectors.toSet()),
                    observation.comparisons().stream().map(comparison -> comparison.sourceChannelUid())
                            .collect(Collectors.toSet()));
            for (var comparison : observation.comparisons()) {
                assertEquals(MigrationValidation.Outcome.MATCH, comparison.outcome(), comparison.toString());
                assertFalse(comparison.sourceObservedAt().isBefore(requestedAt));
                assertFalse(comparison.targetObservedAt().isBefore(requestedAt));
                if (comparison.sourceChannelUid().endsWith(":lastReadSuccess")) {
                    assertNotEquals(comparison.sourceObservedAt(), comparison.targetObservedAt(),
                            "Separate successful reads must agree semantically, not require equal event timestamps");
                    assertTrue(comparison.reason().contains("independent event timestamps are not compared"));
                }
            }
        });
    }

    private void awaitState(ItemRegistry items, String name, String expected) {
        waitForAssert(() -> assertEquals(expected, requireNonNull(items.get(name)).getState().toString()));
    }

    private Set<ChannelUID> itemChannels(ItemChannelLinkRegistry links, String item) {
        return links.getAll().stream().filter(link -> item.equals(link.getItemName()))
                .map(ItemChannelLink::getLinkedUID).collect(Collectors.toSet());
    }

    private void cleanup(ManagedThingProvider things, ItemChannelLinkRegistry links, ManagedItemProvider items) {
        links.getAll().stream().filter(link -> Set.of(VALUE, AVAILABLE, YAML_VALUE).contains(link.getItemName()))
                .toList().forEach(link -> links.remove(link.getUID()));
        things.remove(TARGET);
        things.remove(DATA);
        things.remove(POLLER);
        things.remove(ENDPOINT);
        items.remove(VALUE);
        items.remove(AVAILABLE);
        items.remove(YAML_VALUE);
    }

    /**
     * Public provider API deliberately supports legacy filename UIDs, unlike the managed provider's config-only UIDs.
     */
    private static final class FixtureTransformationProvider extends AbstractProvider<Transformation>
            implements TransformationProvider {
        @Override
        public Collection<Transformation> getAll() {
            return List.of(
                    new Transformation("config:js:fixtureToItem", "Fixture to Item", "js",
                            Map.of(Transformation.FUNCTION,
                                    "(function(i) { return (Number(i) / 10 + 1).toFixed(1); })(input)")),
                    new Transformation("config:js:fixtureFromItem", "Fixture from Item", "js",
                            Map.of(Transformation.FUNCTION,
                                    "(function(i) { return String((Number(i) - 1) * 10); })(input)")),
                    new Transformation("fixture-invert.map", "Fixture inverted switch", "map",
                            Map.of(Transformation.FUNCTION, "1=OFF\n0=ON\n")),
                    new Transformation("fixture-availability.map", "Fixture availability", "map",
                            Map.of(Transformation.FUNCTION, "UNDEF=OFF\nNULL=OFF\n=ON\n")));
        }
    }
}
