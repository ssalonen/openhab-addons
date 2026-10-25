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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;

/** @author Sami Salonen - Preserve native YAML transformation configuration types */
class MigrationYamlSequenceTest {
    private final ObjectMapper yaml = new ObjectMapper(
            YAMLFactory.builder().enable(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS).build());

    @Test
    void mapSequenceRoundTripsWithoutBecomingAStringAndKeepsDistinctReviewIdentity() throws Exception {
        String transform = "MAP(fixture.map)";
        var sequence = preview(List.of(transform));
        var scalar = preview(transform);
        JsonNode sequenceConfig = channelConfiguration(sequence);
        JsonNode scalarConfig = channelConfiguration(scalar);
        assertTrue(sequenceConfig.get("readTransform").isArray(),
                "Core YAML must receive a sequence, not its string form");
        assertEquals(yaml.valueToTree(List.of(transform)), sequenceConfig.get("readTransform"));
        assertTrue(scalarConfig.get("readTransform").isTextual());
        assertEquals(transform, scalarConfig.get("readTransform").textValue());
        assertNotEquals(scalar.identity(), sequence.identity());
        assertEquals(List.of(transform),
                sequence.groups().getFirst().channels().getFirst().configuration().get("readTransform"));
        assertEquals("100", sequenceConfig.get("address").textValue());
        assertTrue(sequenceConfig.get("updateUnchangedValuesEveryMillis").isNumber());
        assertEquals(1000, sequenceConfig.get("updateUnchangedValuesEveryMillis").intValue());
    }

    @Test
    void unsafeTransformationValuesStayManualAndNeverEnterYaml() {
        for (var transform : List.of("MAP(fixture.map)\nsecret: fixture-secret", "JS:fixture-secret.js",
                "MAP(fixture-secret.map)\\\"\nthings: {}")) {
            var preview = preview(List.of(transform));
            assertTrue(preview.groups().isEmpty());
            assertFalse(preview.manualWork().isEmpty());
            assertFalse(preview.yaml().contains("fixture-secret"));
            assertFalse(preview.manifestJson().contains("fixture-secret"));
        }
    }

    private JsonNode channelConfiguration(MigrationPreview preview) throws Exception {
        return yaml.readTree(preview.yaml()).get("things").get("modbus:poller2:tcp:plant").get("channels").get("value")
                .get("config");
    }

    private MigrationPreview preview(Object transform) {
        var poller = new LegacyPoller("modbus:poller:tcp:plant", "modbus:tcp:plant",
                Map.of("start", 100, "length", 1, "type", "holding"));
        var data = new LegacyData("modbus:data:tcp:plant:value", poller.uid(),
                Map.of("readStart", "100", "readValueType", "int16", "readTransform", transform), List.of());
        return new ModbusMigrationPlanner().preview(List.of(poller), List.of(data));
    }
}
