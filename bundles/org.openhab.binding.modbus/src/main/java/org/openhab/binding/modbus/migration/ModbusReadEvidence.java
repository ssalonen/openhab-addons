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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Typed decode provenance; retained operational state is never successful validation evidence.
 * 
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public record ModbusReadEvidence(State value, State decoded, Provenance provenance) {
    public enum Provenance {
        DECODED,
        INTENTIONAL_UNDEF,
        DECODE_FAILURE,
        TRANSFORM_FAILURE,
        TRANSPORT_FAILURE
    }

    public boolean informationBearing() {
        return (provenance == Provenance.DECODED || provenance == Provenance.INTENTIONAL_UNDEF)
                && !(decoded instanceof UnDefType);
    }

    /** Reuses legacy conversion order; callers must authorize any configured transformation before invoking. */
    public static ModbusReadEvidence decode(State raw, String itemType,
            org.openhab.binding.modbus.internal.ModbusTransformation transformation) {
        State value;
        if (transformation.isIdentityTransform()) {
            value = switch (itemType) {
                case "Switch" -> org.openhab.core.library.types.OnOffType
                        .from(!raw.equals(org.openhab.core.library.types.DecimalType.ZERO));
                case "Contact" -> raw.equals(org.openhab.core.library.types.DecimalType.ZERO)
                        ? org.openhab.core.library.types.OpenClosedType.CLOSED
                        : org.openhab.core.library.types.OpenClosedType.OPEN;
                default -> raw;
            };
        } else {
            String text = transformation.transform(raw.toString());
            if ("UNDEF".equals(text)) {
                return new ModbusReadEvidence(UnDefType.UNDEF, raw,
                        raw instanceof UnDefType ? Provenance.DECODE_FAILURE : Provenance.INTENTIONAL_UNDEF);
            }
            java.util.List<Class<? extends State>> types = switch (itemType) {
                case "Switch" -> new org.openhab.core.library.items.SwitchItem("").getAcceptedDataTypes();
                case "Contact" -> new org.openhab.core.library.items.ContactItem("").getAcceptedDataTypes();
                default -> new org.openhab.core.library.items.NumberItem("").getAcceptedDataTypes();
            };
            State parsed = org.openhab.core.types.TypeParser.parseState(types, text);
            if (parsed == null) {
                return new ModbusReadEvidence(UnDefType.UNDEF, raw, Provenance.TRANSFORM_FAILURE);
            }
            value = parsed;
        }
        return new ModbusReadEvidence(value, raw,
                raw instanceof UnDefType ? Provenance.DECODE_FAILURE : Provenance.DECODED);
    }
}
