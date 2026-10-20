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
import org.openhab.binding.modbus.handler.ModbusPollerThingHandler;
import org.openhab.binding.modbus.internal.handler.ModbusDataThingHandler;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;

/**
 * Exact reviewed Things eligible for a migration-owned read, never an implicit sibling wildcard.
 *
 * @author Sami Salonen - Scoped migration read selection
 */
@NonNullByDefault
public record MigrationReadSelection(Map<ThingUID, Binding> bindings) {
    public MigrationReadSelection {
        bindings = Map.copyOf(bindings);
    }

    /** Exact authoritative decoder pair and the complete configuration captured for this read. */
    public record Binding(Thing thing, @Nullable ThingHandler handler, String signature) {
        public Binding(Thing thing) {
            this(thing, thing.getHandler(), Poller2ObservationCollector.signature(thing));
        }

        @SuppressWarnings("PMD.CompareObjectsWithEquals") // Decoder authority requires exact live instances.
        public boolean matches(Thing selectedThing, ThingHandler selectedHandler) {
            return thing == selectedThing && handler == selectedHandler && current();
        }

        @SuppressWarnings("PMD.CompareObjectsWithEquals") // Replacements cannot inherit decoder authority.
        public boolean current() {
            return (handler instanceof ModbusDataThingHandler || handler instanceof ModbusPollerThingHandler)
                    && thing.getHandler() == handler && signature.equals(Poller2ObservationCollector.signature(thing));
        }
    }

    public boolean matches(Thing thing, ThingHandler handler) {
        Binding binding = bindings.get(thing.getUID());
        return binding != null && binding.matches(thing, handler);
    }

    public boolean current() {
        return !bindings.isEmpty() && bindings.values().stream().allMatch(Binding::current);
    }

    public static boolean identityTransform(@Nullable Object value) {
        return value == null || value instanceof String text && "default".equalsIgnoreCase(text.trim())
                || value instanceof List<?> list && list.size() == 1 && list.getFirst() instanceof String text
                        && "default".equalsIgnoreCase(text.trim());
    }
}
