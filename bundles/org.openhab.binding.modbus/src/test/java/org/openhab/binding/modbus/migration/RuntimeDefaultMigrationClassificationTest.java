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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;

/**
 * Runtime-shaped read-only configuration must not be mistaken for configured writes.
 *
 * @author Sami Salonen - Runtime default classification regression coverage
 */
class RuntimeDefaultMigrationClassificationTest {
    private static final String POLLER = "modbus:poller:recovery-acceptance:holding";
    private static final String DATA = "modbus:data:recovery-acceptance:holding:value";
    private static final Map<String, Object> READ_CONFIGURATION = Map.of("readStart", "100", "readValueType", "uint16",
            "readTransform", List.of("default"));

    // Exact property shape returned by Core REST for the read-only runtime fixture. Core Configuration stores
    // numerical properties as BigDecimal; do not strip defaults before constructing LegacyData or taking a snapshot.
    private static Map<String, Object> runtimeConfiguration() {
        return Map.of("readValueType", "uint16", "readTransform", List.of("default"), "writeTransform",
                List.of("default"), "readStart", "100", "updateUnchangedValuesEveryMillis", new BigDecimal("1000"),
                "writeMultipleEvenWithSingleRegisterOrCoil", false, "writeMaxTries", new BigDecimal("3"));
    }

    private static LegacyPoller poller() {
        return new LegacyPoller(POLLER, "modbus:tcp:recovery-acceptance",
                Map.of("start", 100, "length", 1, "type", "holding"));
    }

    private static LegacyData data(Map<String, Object> configuration) {
        return new LegacyData(DATA, POLLER, configuration, List
                .of(new LegacyLink("RuntimeValue", DATA + ":number", "Number", Map.of("profile", "system:default"))));
    }

    @Test
    void plannerAcceptsReadOnlyCoreRestDefaultsWithoutDiscardingSourceIdentity() {
        Map<String, Object> source = runtimeConfiguration();
        LegacyData captured = data(source);
        MigrationPreview plan = new ModbusMigrationPlanner().preview(List.of(poller()), List.of(captured));

        assertEquals(1, plan.groups().size(), plan.manualWork().toString());
        assertTrue(plan.manualWork().isEmpty());
        assertEquals(
                Map.of("address", "100", "valueType", "uint16", "errorPolicy", "keepLast",
                        "updateUnchangedValuesEveryMillis", java.math.BigDecimal.valueOf(1000)),
                plan.groups().getFirst().channels().getFirst().configuration());
        assertEquals(1, plan.groups().getFirst().links().size());
        assertEquals("", captured.omissionReason());
        assertEquals(READ_CONFIGURATION, captured.configuration());
        assertEquals(ModbusMigrationPlanner.configurationIdentity(source), captured.configurationIdentity());
        assertNotEquals(ModbusMigrationPlanner.configurationIdentity(READ_CONFIGURATION),
                captured.configurationIdentity());
        assertEquals(runtimeConfiguration(), source);
    }

    @Test
    void managedAdapterClassifiesCapturedRuntimeDefaultsWithoutMutatingTheThing() {
        var endpointUid = new ThingUID("modbus:tcp:recovery-acceptance");
        var endpoint = BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), endpointUid).build();
        var legacyPoller = BridgeBuilder.create(new ThingTypeUID("modbus", "poller"), new ThingUID(POLLER))
                .withBridge(endpointUid).withConfiguration(new Configuration(poller().configuration())).build();
        var legacyData = ThingBuilder.create(new ThingTypeUID("modbus", "data"), new ThingUID(DATA))
                .withBridge(legacyPoller.getUID()).withConfiguration(new Configuration(runtimeConfiguration())).build();
        var registry = mock(ThingRegistry.class);
        when(registry.getAll()).thenReturn(List.of(endpoint, legacyPoller, legacyData));
        when(registry.get(endpointUid)).thenReturn(endpoint);
        var provider = mock(ManagedThingProvider.class);
        when(provider.get(legacyPoller.getUID())).thenReturn(legacyPoller);
        when(provider.get(legacyData.getUID())).thenReturn(legacyData);
        var links = mock(ItemChannelLinkRegistry.class);
        when(links.getAll()).thenReturn(List.of());
        Map<String, Object> original = Map.copyOf(legacyData.getConfiguration().getProperties());

        var snapshot = new ManagedModbusMigrationAdapter(registry, links, provider).snapshot();
        var plan = new ModbusMigrationPlanner().preview(snapshot.pollers(), snapshot.data(), snapshot.manualWork());

        assertEquals(1, plan.groups().size(), plan.manualWork().toString());
        assertTrue(plan.manualWork().isEmpty());
        assertEquals("", snapshot.data().getFirst().omissionReason());
        assertEquals(ModbusMigrationPlanner.configurationIdentity(original),
                snapshot.data().getFirst().configurationIdentity());
        assertEquals(original, legacyData.getConfiguration().getProperties());
    }

    @ParameterizedTest
    @ValueSource(strings = { "writeTransform", "writeMaxTries", "writeMultipleEvenWithSingleRegisterOrCoil",
            "updateUnchangedValuesEveryMillis" })
    void omittedDefaultStillChangesReviewIdentityDespiteIdenticalReadMapping(String key) {
        var source = new HashMap<>(runtimeConfiguration());
        var planner = new ModbusMigrationPlanner();
        var complete = planner.preview(List.of(poller()), List.of(data(source)));
        source.remove(key);
        var omitted = planner.preview(List.of(poller()), List.of(data(source)));

        assertEquals(1, complete.groups().size(), complete.manualWork().toString());
        assertEquals(1, omitted.groups().size(), omitted.manualWork().toString());
        assertEquals(complete.groups().getFirst().channels(), omitted.groups().getFirst().channels());
        assertNotEquals(complete.identity(), omitted.identity());
        assertNotEquals(data(runtimeConfiguration()).configurationIdentity(), data(source).configurationIdentity());
    }

    @Test
    void completeGenuineWriteConfigurationRemainsManualAlongsideReadDefaults() {
        var source = new HashMap<>(runtimeConfiguration());
        source.putAll(Map.of("writeStart", "100", "writeType", "holding", "writeValueType", "uint16"));
        var plan = new ModbusMigrationPlanner().preview(List.of(poller()), List.of(data(source)));

        assertTrue(plan.groups().isEmpty());
        assertTrue(plan.impacts().isEmpty());
        assertEquals("writes require manual migration; configuration omitted", plan.manualWork().getFirst().reason());
    }

    @ParameterizedTest
    @MethodSource("configuredWritesOrUnsupportedDefaults")
    void runtimeDefaultsNeverHideConfiguredWritesOrUnsupportedSemantics(String key, Object value) {
        var source = new HashMap<>(runtimeConfiguration());
        source.put(key, value);
        LegacyData captured = data(source);
        var plan = new ModbusMigrationPlanner().preview(List.of(poller()), List.of(captured));

        assertTrue(plan.groups().isEmpty());
        assertFalse(plan.manualWork().isEmpty());
        assertFalse(captured.omissionReason().isEmpty());
        assertEquals(ModbusMigrationPlanner.configurationIdentity(source), captured.configurationIdentity());
        assertFalse(captured.configuration().containsKey("writeTransform"));
        assertFalse(plan.toString().contains("private-transform"));
    }

    private static Stream<Arguments> configuredWritesOrUnsupportedDefaults() {
        return Stream.of(Arguments.of("writeStart", "100"), Arguments.of("writeType", "holding"),
                Arguments.of("writeValueType", "uint16"),
                Arguments.of("writeTransform", List.of("JS:private-transform")),
                Arguments.of("writeTransform", "JSONPATH(private-transform)"),
                Arguments.of("writeTransform", List.of("default", "JS:private-transform")),
                Arguments.of("writeTransform", List.of("default", "default")),
                Arguments.of("writeTransform", List.of()), Arguments.of("writeTransform", List.of("")),
                Arguments.of("writeMaxTries", new BigDecimal("4")), Arguments.of("writeMaxTries", "3"),
                Arguments.of("writeMultipleEvenWithSingleRegisterOrCoil", true),
                Arguments.of("writeMultipleEvenWithSingleRegisterOrCoil", "false"),
                Arguments.of("updateUnchangedValuesEveryMillis", new BigDecimal("2000")),
                Arguments.of("updateUnchangedValuesEveryMillis", "1000"),
                Arguments.of("readTransform", List.of("JS:private-transform")),
                Arguments.of("writeUnknownOption", false));
    }
}
