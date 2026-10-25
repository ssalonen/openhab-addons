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

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;

/** Source Item-channel link and the compatible replacement link eligible for explicit cutover. */
@NonNullByDefault
public record MigrationLink(String itemName, String sourceChannelUid, String targetChannelUid,
        Map<String, Object> configuration, Map<String, Object> targetConfiguration) {

    static final String OWNER = "modbusMigrationOwner";

    public MigrationLink(String itemName, String sourceChannelUid, String targetChannelUid,
            Map<String, Object> configuration) {
        this(itemName, sourceChannelUid, targetChannelUid, configuration, Map.of());
    }

    public MigrationLink {
        configuration = Map.copyOf(configuration);
        // Old persisted records have no target claim. Never synthesize one during deserialization.
        targetConfiguration = targetConfiguration == null ? Map.of() : Map.copyOf(targetConfiguration);
    }

    boolean markedDefaultTarget() {
        Object marker = targetConfiguration.get(OWNER);
        if (!(marker instanceof String value) || value.isBlank()
                || !MigrationReviewConfiguration.compatibleProfile(configuration)) {
            return false;
        }
        Map<String, Object> expected = new java.util.HashMap<>(configuration);
        expected.putIfAbsent("profile", "system:default");
        expected.put(OWNER, marker);
        return expected.equals(targetConfiguration);
    }
}
