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
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Read-only reviewed plan and workflow status, not live validation evidence.
 * Comparison wiring is not available yet: readiness remains blocked even for staged candidates.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public record MigrationWorkflowReport(MigrationPreview preview, Status status, Instant stagedAt,
        List<String> switchedLinks, MigrationValidation.Outcome validation, boolean cutoverReady,
        String readinessReason) {
    public enum Status {
        PREVIEW,
        STAGED,
        RECOVERY,
        ROLLED_BACK
    }

    public MigrationWorkflowReport {
        switchedLinks = List.copyOf(switchedLinks);
    }
}
