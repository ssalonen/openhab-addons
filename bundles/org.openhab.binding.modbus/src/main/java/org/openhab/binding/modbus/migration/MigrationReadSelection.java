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

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.ThingUID;

/**
 * Exact reviewed Things eligible for a migration-owned read, never an implicit sibling wildcard.
 *
 * @author Sami Salonen - Scoped migration read selection
 */
@NonNullByDefault
public record MigrationReadSelection(Map<ThingUID, String> signatures) {
    public MigrationReadSelection {
        signatures = Map.copyOf(signatures);
    }

    public static boolean identityTransform(@Nullable Object value) {
        return value == null || value instanceof String text && "default".equalsIgnoreCase(text.trim())
                || value instanceof List<?> list && list.size() == 1 && list.getFirst() instanceof String text
                        && "default".equalsIgnoreCase(text.trim());
    }
}
