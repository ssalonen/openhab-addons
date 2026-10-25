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
package org.openhab.binding.modbus.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.io.transport.modbus.BitArray;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.types.State;

/**
 * Decodes one coil or discrete-input poller-owned Modbus channel.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public final class Poller2BitChannel {

    private final int address;
    private final boolean contact;
    private final boolean number;
    private final PollerChannelState state;
    private org.openhab.binding.modbus.migration.ModbusReadEvidence evidence = unavailable();

    public synchronized org.openhab.binding.modbus.migration.ModbusReadEvidence getReadEvidence() {
        return evidence;
    }

    private static org.openhab.binding.modbus.migration.ModbusReadEvidence unavailable() {
        return new org.openhab.binding.modbus.migration.ModbusReadEvidence(org.openhab.core.types.UnDefType.UNDEF,
                org.openhab.core.types.UnDefType.UNDEF,
                org.openhab.binding.modbus.migration.ModbusReadEvidence.Provenance.DECODE_FAILURE);
    }

    public Poller2BitChannel(int address, PollerReadFailurePolicy failurePolicy) {
        this(address, failurePolicy, "Switch");
    }

    public Poller2BitChannel(int address, PollerReadFailurePolicy failurePolicy, String itemType) {
        this.address = address;
        this.contact = "Contact".equals(itemType);
        this.number = "Number".equals(itemType);
        this.state = new PollerChannelState(failurePolicy);
    }

    public synchronized State acceptBits(BitArray bits, int pollStart) {
        evidence = unavailable();
        long index = (long) address - pollStart;
        if (index < 0 || index > Integer.MAX_VALUE) {
            state.acceptReadFailure();
            return state.currentState();
        }
        try {
            boolean value = bits.getBit((int) index);
            State numeric = new org.openhab.core.library.types.DecimalType(value ? 1 : 0);
            State converted = number ? numeric
                    : contact ? (value ? OpenClosedType.OPEN : OpenClosedType.CLOSED) : OnOffType.from(value);
            state.acceptSuccessfulState(converted);
            evidence = new org.openhab.binding.modbus.migration.ModbusReadEvidence(converted, numeric,
                    org.openhab.binding.modbus.migration.ModbusReadEvidence.Provenance.DECODED);
        } catch (IndexOutOfBoundsException e) {
            state.acceptReadFailure();
        }
        return state.currentState();
    }

    public synchronized State acceptReadFailure() {
        evidence = unavailable();
        state.acceptReadFailure();
        return state.currentState();
    }
}
