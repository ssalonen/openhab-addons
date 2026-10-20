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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Schema-bound review values. Arbitrary source values are retained only as typed digests.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
final class MigrationReviewConfiguration {
    private static final Set<String> POLLER_NUMBERS = Set.of("start", "length", "refresh", "maxTries", "cacheMillis");
    private static final Set<String> VALUE_TYPES = Set.of("bit", "int8", "uint8", "int16", "uint16", "int32", "uint32",
            "int32_swap", "uint32_swap", "int64", "uint64", "int64_swap", "uint64_swap", "float32", "float32_swap");

    private MigrationReviewConfiguration() {
    }

    static Map<String, Object> poller(Map<String, Object> source) {
        Map<String, Object> safe = new HashMap<>();
        source.forEach((key, value) -> {
            if (POLLER_NUMBERS.contains(key) && integer(value)
                    || "type".equals(key) && Set.of("coil", "discrete", "holding", "input").contains(value)) {
                safe.put(key, value);
            }
        });
        return Map.copyOf(safe);
    }

    private static boolean integer(Object value) {
        return (value instanceof Number || value instanceof String) && value.toString().matches("[0-9]+");
    }

    static boolean defaultProfile(Map<String, Object> source) {
        return source.isEmpty() || Map.of("profile", "system:default").equals(source);
    }

    static Map<String, Object> data(Map<String, Object> source) {
        Map<String, Object> safe = new HashMap<>();
        source.forEach((key, value) -> {
            if ("readStart".equals(key) && (value instanceof Number || value instanceof String)
                    && value.toString().matches("[0-9]+(\\.[0-9]{1,2})?")
                    || "readValueType".equals(key) && VALUE_TYPES.contains(value)
                    || "readTransform".equals(key) && identityTransform(value)) {
                safe.put(key, value instanceof List<?> list ? List.copyOf(list) : value);
            }
        });
        return Map.copyOf(safe);
    }

    private static boolean identityTransform(@Nullable Object value) {
        return value instanceof String text && (text.isBlank() || "default".equals(text))
                || value instanceof List<?> values && values.size() == 1 && values.getFirst() instanceof String entry
                        && "default".equalsIgnoreCase(entry.trim());
    }

    // Core injects these defaults even without a write destination. Validate them separately from the
    // read-only display DTO; the original full source map must still determine review identity.
    private static boolean inertDataDefault(String key, @Nullable Object value) {
        return switch (key) {
            case "writeTransform" -> value instanceof List<?> && identityTransform(value);
            case "writeMultipleEvenWithSingleRegisterOrCoil" -> Boolean.FALSE.equals(value);
            case "writeMaxTries" -> numericDefault(value, 3);
            case "updateUnchangedValuesEveryMillis" -> numericDefault(value, 1000);
            default -> false;
        };
    }

    private static boolean numericDefault(@Nullable Object value, int expected) {
        if (!(value instanceof Number)) {
            return false;
        }
        try {
            return new BigDecimal(value.toString()).compareTo(BigDecimal.valueOf(expected)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static String dataReason(Map<String, Object> source) {
        if (source.entrySet().stream().anyMatch(
                entry -> entry.getKey().startsWith("write") && !inertDataDefault(entry.getKey(), entry.getValue()))) {
            return source.get("writeTransform") instanceof String text && text.contains("JSON")
                    ? "JSON write transform requires manual migration; configuration omitted"
                    : "writes require manual migration; configuration omitted";
        }
        if (source.containsKey("readTransform") && !identityTransform(source.get("readTransform"))) {
            return "custom readTransform requires manual migration; configuration omitted";
        }
        Map<String, Object> readable = data(source);
        if (source.entrySet().stream().anyMatch(entry -> !readable.containsKey(entry.getKey())
                && !inertDataDefault(entry.getKey(), entry.getValue()))) {
            return "unknown or invalid data configuration requires manual migration; configuration omitted";
        }
        return "";
    }
}
