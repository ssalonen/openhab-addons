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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * @author Sami Salonen - Preview identity regression coverage
 */
class ModbusMigrationPlannerTest {

    private final ModbusMigrationPlanner planner = new ModbusMigrationPlanner();

    @Test
    void safeReviewsDoNotReuseHistoricalPreviewIdentities() {
        String historical = ModbusMigrationPlanner.configurationIdentity(
                List.of("modbus-migration-preview-v3", List.of(), List.of(), List.of(), List.of()));
        org.junit.jupiter.api.Assertions.assertNotEquals(historical, planner.preview(List.of(), List.of()).identity());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("customReadTransforms")
    void previewRefusesCustomListReadTransforms(List<?> transforms) {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant", Map.of("start", 100));
        LegacyData data = new LegacyData("modbus:data:tcp:plant:value", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16", "readTransform", transforms),
                List.of(new LegacyLink("Value", "modbus:data:tcp:plant:value:number", "Number")));

        MigrationPreview preview = planner.preview(List.of(poller), List.of(data));

        assertTrue(preview.groups().isEmpty());
        assertTrue(preview.impacts().isEmpty());
        assertEquals(
                List.of(new ManualMigrationWork(data.uid(),
                        "custom readTransform requires manual migration; configuration omitted")),
                preview.manualWork());
        assertFalse(data.configuration().containsKey("readTransform"));
    }

    private static java.util.stream.Stream<List<?>> customReadTransforms() {
        return java.util.stream.Stream.of(List.of("JS(scale.js)"), List.of("default", "JS(scale.js)"),
                List.of("JS(scale.js)", "default"), List.of("17"), List.of(17), List.of(), List.of("", " "),
                List.of("default", "default"), List.of("", " ", "default"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("defaultReadTransforms")
    void previewAcceptsNoOpListReadTransforms(List<String> transforms) {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant", Map.of("start", 100));
        LegacyData data = new LegacyData("modbus:data:tcp:plant:value", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16", "readTransform", transforms), List.of());

        MigrationPreview preview = planner.preview(List.of(poller), List.of(data));

        assertEquals(1, preview.groups().size());
        assertEquals(Map.of("address", "100", "valueType", "int16"),
                preview.groups().getFirst().channels().getFirst().configuration());
        assertTrue(preview.manualWork().isEmpty());
        assertEquals(transforms, data.configuration().get("readTransform"));
    }

    private static java.util.stream.Stream<List<String>> defaultReadTransforms() {
        return java.util.stream.Stream.of(List.of("default"), List.of("DEFAULT"), List.of(" default "));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "readStart", "readValueType", "readTransform",
            "unknown" })
    void arbitraryStringsCannotBypassTheDataReviewSchema(String key) {
        var poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant", Map.of("start", 100));
        var values = new java.util.HashMap<String, Object>(Map.of("readStart", "100", "readValueType", "int16"));
        values.put(key, "JS(https://user:SENTINEL@example.test)");
        var data = new LegacyData("modbus:data:tcp:plant:value", poller.uid(), values, List.of());
        var preview = planner.preview(List.of(poller), List.of(data));
        assertTrue(preview.groups().isEmpty());
        assertFalse(preview.manualWork().isEmpty());
        assertFalse(preview.toString().contains("SENTINEL"));
        assertFalse(new com.google.gson.Gson().toJson(data).contains("SENTINEL"));
    }

    @Test
    void schemaPreservesDocumentedPollerValuesAndIndexedAddresses() {
        var values = Map.<String, Object> of("start", 100, "length", 2, "type", "holding", "refresh", 500, "maxTries",
                3, "cacheMillis", 50);
        var poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant", values);
        var data = new LegacyData("modbus:data:tcp:plant:value", poller.uid(),
                Map.of("readStart", "100.1", "readValueType", "bit", "readTransform", List.of(" default ")), List.of());
        var preview = planner.preview(List.of(poller), List.of(data));
        assertTrue(preview.manualWork().isEmpty());
        assertEquals(values, preview.groups().getFirst().configuration());
        assertEquals(Map.of("address", "100.1", "valueType", "bit"),
                preview.groups().getFirst().channels().getFirst().configuration());
        assertEquals(List.of(" default "), data.configuration().get("readTransform"));
    }

    @Test
    void identityBindsSourceAndTargetConfiguration() {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant", Map.of("start", 100));
        LegacyData first = new LegacyData("modbus:data:tcp:plant:value", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16"), List.of());
        LegacyData changed = new LegacyData(first.uid(), poller.uid(),
                Map.of("readStart", "101", "readValueType", "int16"), List.of());
        assertFalse(planner.preview(List.of(poller), List.of(first)).identity()
                .equals(planner.preview(List.of(poller), List.of(changed)).identity()));
    }

    @Test
    void identityBindsPollerConfigurationEvenWithoutTargets() {
        LegacyPoller first = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant", Map.of("start", 100));
        LegacyPoller changed = new LegacyPoller(first.uid(), first.bridgeUid(), Map.of("start", 101));
        assertFalse(planner.preview(List.of(first), List.of()).identity()
                .equals(planner.preview(List.of(changed), List.of()).identity()));
    }

    @Test
    void identityBindsIgnoredSourceConfigurationAndValueTypes() {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant", Map.of("start", 100));
        LegacyData first = new LegacyData("modbus:data:tcp:plant:value", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16", "extra", List.of("a", "b")), List.of());
        for (Object value : List.of("[a, b]", List.of("b", "a"), List.of("a, b"), 100, "100")) {
            LegacyData changed = new LegacyData(first.uid(), first.pollerUid(),
                    Map.of("readStart", "100", "readValueType", "int16", "extra", value), List.of());
            assertFalse(planner.preview(List.of(poller), List.of(first)).identity()
                    .equals(planner.preview(List.of(poller), List.of(changed)).identity()));
        }
        LegacyData numeric = new LegacyData(first.uid(), first.pollerUid(), Map.of("extra", 100), List.of());
        LegacyData text = new LegacyData(first.uid(), first.pollerUid(), Map.of("extra", "100"), List.of());
        assertFalse(planner.preview(List.of(poller), List.of(numeric)).identity()
                .equals(planner.preview(List.of(poller), List.of(text)).identity()));
    }

    @Test
    void identityBindsProfileValueTypesForManualLinks() {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant", Map.of("start", 100));
        LegacyData numeric = new LegacyData("modbus:data:tcp:plant:value", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16"),
                List.of(new LegacyLink("Value", "modbus:data:tcp:plant:value:number", "Number",
                        Map.of("profile", "modbus:gain-offset", "gain", 2))));
        LegacyData text = new LegacyData(numeric.uid(), numeric.pollerUid(), numeric.configuration(),
                List.of(new LegacyLink("Value", "modbus:data:tcp:plant:value:number", "Number",
                        Map.of("profile", "modbus:gain-offset", "gain", "2"))));
        assertFalse(planner.preview(List.of(poller), List.of(numeric)).identity()
                .equals(planner.preview(List.of(poller), List.of(text)).identity()));
    }

    @Test
    void identityIsIndependentOfNestedProfileAndLinkOrder() {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant", Map.of("start", 100));
        java.util.LinkedHashMap<String, Object> firstOptions = new java.util.LinkedHashMap<>();
        firstOptions.put("a", 1);
        firstOptions.put("b", List.of(true, "x"));
        java.util.LinkedHashMap<String, Object> secondOptions = new java.util.LinkedHashMap<>();
        secondOptions.put("b", List.of(true, "x"));
        secondOptions.put("a", 1);
        LegacyLink first = new LegacyLink("Value", "modbus:data:tcp:plant:value:number", "Number",
                Map.of("profile", "custom:profile", "options", firstOptions));
        LegacyLink reordered = new LegacyLink(first.itemName(), first.channelUid(), first.itemType(),
                Map.of("profile", "custom:profile", "options", secondOptions));
        LegacyLink second = new LegacyLink("Other", first.channelUid(), "Number");
        LegacyData data = new LegacyData("modbus:data:tcp:plant:value", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16"), List.of(first, second));
        LegacyData changed = new LegacyData(data.uid(), data.pollerUid(), data.configuration(),
                List.of(second, reordered));
        assertEquals(planner.preview(List.of(poller), List.of(data)).identity(),
                planner.preview(List.of(poller), List.of(changed)).identity());
    }

    @Test
    void identityIsIndependentOfSourceAndConfigurationOrder() {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant", Map.of("start", 100));
        LegacyData first = new LegacyData("modbus:data:tcp:plant:a", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16", "extra", Map.of("a", 1, "b", 2)), List.of());
        LegacyData second = new LegacyData("modbus:data:tcp:plant:b", poller.uid(),
                Map.of("readStart", "101", "readValueType", "int16"), List.of());
        java.util.LinkedHashMap<String, Object> configuration = new java.util.LinkedHashMap<>();
        configuration.put("extra", Map.of("b", 2, "a", 1));
        configuration.put("readValueType", "int16");
        configuration.put("readStart", "100");
        LegacyData reordered = new LegacyData(first.uid(), first.pollerUid(), configuration, List.of());
        assertEquals(planner.preview(List.of(poller), List.of(first, second)).identity(),
                planner.preview(List.of(poller), List.of(second, reordered)).identity());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "", "Switch", "Contact", "DateTime", "Dimmer", "String",
            "Rollershutter" })
    void previewRefusesUnprovenTypedChannelConversions(String itemType) {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding"));
        LegacyData data = new LegacyData("modbus:data:tcp:plant:value", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16"),
                List.of(new LegacyLink("Value", "modbus:data:tcp:plant:value:typed", itemType)));

        MigrationPreview preview = planner.preview(List.of(poller), List.of(data));

        assertTrue(preview.groups().isEmpty());
        assertEquals(data.uid(), preview.manualWork().getFirst().sourceUid());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "Number", "Switch" })
    void previewDoesNotCollapseDistinctSourceChannels(String secondType) {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding"));
        LegacyData data = new LegacyData("modbus:data:tcp:plant:value", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16"),
                List.of(new LegacyLink("Value", "modbus:data:tcp:plant:value:number", "Number"),
                        new LegacyLink("OtherValue", "modbus:data:tcp:plant:value:other", secondType)));

        MigrationPreview preview = planner.preview(List.of(poller), List.of(data));

        assertTrue(preview.groups().isEmpty());
        assertEquals(data.uid(), preview.manualWork().getFirst().sourceUid());
    }

    @Test
    void previewEmitsNativeYamlThingAndChannelConfiguration() {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding"));
        LegacyData data = new LegacyData("modbus:data:tcp:plant:temperature", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16"), List.of());

        assertEquals("""
                version: 1
                things:
                  modbus:poller2:tcp:plant:
                    bridge: modbus:tcp:plant
                    config:
                      length: 2
                      start: 100
                      type: "holding"
                    channels:
                      temperature:
                        itemType: Number
                        config:
                          address: "100"
                          valueType: "int16"
                """, planner.preview(List.of(poller), List.of(data)).yaml());
    }

    @Test
    void previewGroupsEligibleDataThingsAndEmitsReviewArtifactsWithoutMutatingInput() {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding", "refresh", 500));
        LegacyData data = new LegacyData("modbus:data:tcp:plant:temperature", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16"),
                List.of(new LegacyLink("Temperature", "modbus:data:tcp:plant:temperature:number", "Number")));

        MigrationPreview preview = planner.preview(List.of(poller), List.of(data));

        assertEquals(1, preview.groups().size());
        assertEquals("modbus:poller2:tcp:plant", preview.groups().getFirst().targetPollerUid());
        assertTrue(preview.yaml().contains("modbus:poller2:tcp:plant"));
        assertTrue(preview.yaml().contains("temperature:"));
        assertTrue(preview.manifestJson().contains("modbus:data:tcp:plant:temperature:number"));
        assertTrue(preview.manifestJson().contains("modbus:poller2:tcp:plant:temperature"));
        assertFalse(preview.identity().isBlank());
        assertEquals(Map.of("readStart", "100", "readValueType", "int16"), data.configuration());
    }

    @Test
    void previewEmitsLiteralDeveloperSidebarReviewTermsForEachMigratedItem() {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding"));
        LegacyData data = new LegacyData("modbus:data:tcp:plant:temperature", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16"),
                List.of(new LegacyLink("Temperature", "modbus:data:tcp:plant:temperature:number", "Number")));

        MigrationPreview preview = planner.preview(List.of(poller), List.of(data));

        assertEquals(
                """
                        IMPACT ASSESSMENT (review only; no rules, scripts, widgets, or profiles are rewritten)
                        - Item: Temperature
                          Source channel: modbus:data:tcp:plant:temperature:number
                          Target channel: modbus:poller2:tcp:plant:temperature
                          Profile configuration: {}
                          Target link action: CREATE
                          Developer Sidebar search queries (literal terms):
                            - Temperature
                            - modbus:data:tcp:plant:temperature
                            - modbus:poller2:tcp:plant
                        NOTE: Developer Sidebar search is a user review aid, not authoritative dependency detection. Review dynamic, group, tag, external references, and scripts, widgets, and profiles manually.
                        """,
                preview.impactReport());
    }

    @Test
    void previewPreservesTheProvenSystemDefaultProfileAndRefusesUnknownProfileConfiguration() {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding"));
        LegacyData data = new LegacyData("modbus:data:tcp:plant:temperature", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16"),
                List.of(new LegacyLink("Temperature", "modbus:data:tcp:plant:temperature:number", "Number",
                        Map.of("profile", "system:default")),
                        new LegacyLink("AdjustedTemperature", "modbus:data:tcp:plant:temperature:number", "Number",
                                Map.of("profile", "modbus:gain-offset", "gain", "2"))));

        MigrationPreview preview = planner.preview(List.of(poller), List.of(data));

        assertEquals(Map.of("profile", "system:default"),
                preview.groups().getFirst().links().getFirst().configuration());
        assertEquals(1, preview.groups().getFirst().links().size());
        assertEquals(
                List.of(new ManualMigrationWork("modbus:data:tcp:plant:temperature:number",
                        "Item link profile configuration is not proven compatible; target link was not planned")),
                preview.manualWork());
        assertTrue(preview.impactReport().contains("configuration omitted; manual restoration required"));
        assertFalse(preview.impactReport().contains("modbus:gain-offset"));
        assertTrue(preview.impactReport().contains("Target link action: MANUAL"));
    }

    @Test
    void previewReportsCustomTransformsAndJsonWritesAsManualWork() {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding"));
        LegacyData transformed = new LegacyData("modbus:data:tcp:plant:scaled", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16", "readTransform", "JS(scale.js)"), List.of());
        LegacyData jsonWrite = new LegacyData("modbus:data:tcp:plant:writer", poller.uid(),
                Map.of("writeStart", "100", "writeType", "holding", "writeTransform", "JSONPATH($.value)"), List.of());

        MigrationPreview preview = planner.preview(List.of(poller), List.of(transformed, jsonWrite));

        assertTrue(preview.groups().isEmpty());
        assertEquals(2, preview.manualWork().size());
        assertTrue(preview.manualWork().stream().anyMatch(work -> work.reason().contains("custom readTransform")));
        assertTrue(preview.manualWork().stream().anyMatch(work -> work.reason().contains("JSON write")));
    }

    @Test
    void previewIdentityChangesWhenSourceTopologyChanges() {
        LegacyPoller poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 2, "type", "holding"));
        LegacyData first = new LegacyData("modbus:data:tcp:plant:temperature", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16"), List.of());
        LegacyData second = new LegacyData("modbus:data:tcp:plant:pressure", poller.uid(),
                Map.of("readStart", "101", "readValueType", "uint16"), List.of());

        assertFalse(planner.preview(List.of(poller), List.of(first)).identity()
                .equals(planner.preview(List.of(poller), List.of(first, second)).identity()));
    }
}
