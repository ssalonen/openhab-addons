/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
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

import java.math.BigDecimal;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.modbus.config.ReadChannelConfiguration;
import org.openhab.core.io.transport.modbus.BitArray;
import org.openhab.core.io.transport.modbus.ModbusBitUtilities;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Handler for readIntoNumber channels, decoding raw binary data from modbus according to channel configuration.
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public class ReadIntoNumberChannelHandler extends ReadIntoChannelHandler {

    public ReadIntoNumberChannelHandler(int pollStart, ReadChannelConfiguration config, Consumer<State> stateUpdater) {
        super(pollStart, config, stateUpdater);
    }

    @Override
    public void process(BitArray bits) {
        boolean boolValue = bits.getBit(parsedAddress.channelStartElement - pollStart);
        DecimalType numericState = boolValue ? new DecimalType(BigDecimal.ONE) : DecimalType.ZERO;
        processUpdatedValue(numericState);
    }

    @Override
    public void process(ModbusRegisterArray registers) {
        State numericState;

        // extractIndex:
        // e.g. with bit, extractIndex=4 means 5th bit (from right) ("10.4" -> 5th bit of register 10, "10.4" -> 5th bit
        // of register 10)
        // bit of second register)
        // e.g. with 8bit integer, extractIndex=3 means high byte of second register
        //
        // with <16 bit types, this is the index of the N'th 1-bit/8-bit item. Each register has 16/2 items,
        // respectively.
        // with >=16 bit types, this is index of first register
        int extractIndex;
        if (valueType.getBits() >= 16) {
            extractIndex = parsedAddress.channelStartElement - pollStart;
        } else {
            int subIndex = parsedAddress.channelStartElementSub.orElse(0);
            int itemsPerRegister = 16 / valueType.getBits();
            extractIndex = (parsedAddress.channelStartElement - pollStart) * itemsPerRegister + subIndex;
        }
        numericState = ModbusBitUtilities.extractStateFromRegisters(registers, extractIndex, valueType)
                .map(state -> (State) state).orElse(UnDefType.UNDEF);
        // numericState is UNDEF with infinite or NaN float values

        processUpdatedValue(numericState);
    }

    @Override
    public void process(Exception readError) {
        processUpdatedValue(UnDefType.UNDEF);
    }

    /**
     *
     * @param numericState DecimalType or UNDEF
     * @param boolValue
     */
    protected void processUpdatedValue(State state) {
        // TODO: handle gain and offset
        // handle UNDEF
        updateExpiredChannel(System.currentTimeMillis(), state);
    }

}
