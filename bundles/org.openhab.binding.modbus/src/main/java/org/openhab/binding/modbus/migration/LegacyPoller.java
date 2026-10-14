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

/** A legacy Modbus poller with a digest of its endpoint configuration, never plaintext endpoint credentials. */
@NonNullByDefault
public record LegacyPoller(String uid, String bridgeUid, Map<String, Object> configuration,
        String endpointConfigurationIdentity, String configurationIdentity, boolean configurationOmitted) {
    public LegacyPoller(String uid, String bridgeUid, Map<String, Object> configuration,
            String endpointConfigurationIdentity) {
        this(uid, bridgeUid, MigrationReviewConfiguration.poller(configuration), endpointConfigurationIdentity,
                ModbusMigrationPlanner.configurationIdentity(configuration),
                !MigrationReviewConfiguration.poller(configuration).equals(configuration));
    }

    public LegacyPoller(String uid, String bridgeUid, Map<String, Object> configuration) {
        this(uid, bridgeUid, configuration, "");
    }

    public LegacyPoller {
        configuration = Map.copyOf(configuration);
    }
}
