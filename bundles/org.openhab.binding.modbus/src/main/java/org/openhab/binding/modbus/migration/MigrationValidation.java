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

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Binding-owned observation seam. Implementations must compare every planned channel using fresh device polls
 * after candidate creation, not Item state caches or cached REFRESH delivery. Missing coverage is UNAVAILABLE.
 * 
 * @author Sami Salonen - Fresh candidate validation contract
 */
@NonNullByDefault
public interface MigrationValidation {
    enum Outcome {
        MATCH,
        MISMATCH,
        UNAVAILABLE
    }

    record Observation(String previewIdentity, String sourcePollerUid, Instant observedAt, Outcome outcome) {
    }

    Observation observe(MigrationPreview preview, MigrationGroup group, Instant stagedAt);
}
