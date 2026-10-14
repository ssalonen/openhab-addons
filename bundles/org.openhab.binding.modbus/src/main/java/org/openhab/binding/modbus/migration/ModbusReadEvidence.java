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
        return provenance == Provenance.DECODED && !(decoded instanceof UnDefType) && !(value instanceof UnDefType);
    }
}
