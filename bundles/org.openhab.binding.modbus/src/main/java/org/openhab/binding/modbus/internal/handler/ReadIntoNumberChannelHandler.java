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
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.modbus.config.ReadChannelConfiguration;
import org.openhab.core.io.transport.modbus.BitArray;
import org.openhab.core.io.transport.modbus.ModbusBitUtilities;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
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

    protected final ValueType valueType;

    public ReadIntoNumberChannelHandler(int pollStart, ReadChannelConfiguration config, Consumer<State> stateUpdater) {
        super(pollStart, config, stateUpdater);

        String valueTypeString = config.valueType;
        Objects.requireNonNull(valueTypeString);
        this.valueType = ValueType.fromConfigValue(valueTypeString);
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

        int elementIndex = extractIndexFromAbsolute(valueType, parsedAddress.channelStartElement,
                parsedAddress.channelStartElementSub, pollStart);
        numericState = ModbusBitUtilities.extractStateFromRegisters(registers, elementIndex, valueType)
                .map(state -> (State) state).orElse(UnDefType.UNDEF);
        // numericState is UNDEF with infinite or NaN float values

        processUpdatedValue(numericState);
    }

    /**
     * Calculate (relative) extract index for element (of type valueType) to start decoding
     *
     *
     * - With <16 bit types (bit, uint8, int8), this is the index of the N'th 1-bit/8-bit item. Each register has 16/2
     * items, respectively.
     * - With >=16 bit types, this is index of first register to start decoding from
     *
     *
     * Examples
     * - with BIT valueType, element index=0 would refer to first bit (=LSB) of first register. Index=15 would
     * refer to
     * MSB of first register (0.15). Index=16 refers to LSB of second register and so forth.
     * - with INT8 valueType, element index=0 refers to low byte of first register, index=1 high byte of first
     * register (0.1), and index=2 low byte of second register
     *
     * @param valueType value type of the element that is decoded
     * @param decodeStartIndex absolute address of register (e.g. 1001)
     * @param decodeStartSubIndex index to specify element within the register
     * @param pollStart absolute address of polled data (e.g. 1000)
     * @return index of element
     */
    public static int extractIndexFromAbsolute(ValueType valueType, int decodeStartIndex,
            OptionalInt decodeStartSubIndex, int pollStart) {
        return extractIndexFromRelative(valueType, decodeStartIndex - pollStart, decodeStartSubIndex);
    }

    /**
     * Calculate (relative) extract index for element (of type valueType) to start decoding
     *
     *
     * - With <16 bit types (bit, uint8, int8), this is the index of the N'th 1-bit/8-bit item. Each register has 16/2
     * items, respectively.
     * - With >=16 bit types, this is index of first register to start decoding from
     *
     *
     * Examples
     * - with BIT valueType, element index=0 would refer to first bit (=LSB) of first register (0.0). Index=15 would
     * refer to
     * MSB of first register (0.15). Index=16 refers to LSB of second register and so forth (1.0).
     * - with INT8 valueType, element index=0 refers to low byte of first register (0.0), index=1 high byte of first
     * register (0.1), and index=2 low byte of second register (1.0)
     *
     * @param valueType value type of the element that is decoded
     * @param decodeStartIndex relative address of register (e.g. 1001)
     * @param decodeStartSubIndex index to specify element within the register
     * @return index of element
     */
    public static int extractIndexFromRelative(ValueType valueType, int decodeStartIndexRelative,
            OptionalInt decodeStartSubIndex) {
        int extractIndex;
        if (valueType.getBits() >= 16) {
            extractIndex = decodeStartIndexRelative;
        } else {
            int subIndex = decodeStartSubIndex.orElse(0);
            int itemsPerRegister = 16 / valueType.getBits();
            extractIndex = decodeStartIndexRelative * itemsPerRegister + subIndex;
        }
        return extractIndex;
    }

    @Override
    public void process(Exception readError) {
        if (config.updateUndefOnErrors) {
            updateExpiredChannel(System.currentTimeMillis(), UnDefType.UNDEF);
        }
    }

    /**
     *
     * @param numericState DecimalType or UNDEF. numericState is UNDEF with inf or NaN float values
     */
    private void processUpdatedValue(State state) {
        // TODO: handle gain and offset
        State postProcessedState = postProcessDecodedNumberState(state);
        updateExpiredChannel(System.currentTimeMillis(), postProcessedState);
    }

    /**
     * Post-process decoded state.
     *
     * @param numericState DecimalType or UNDEF
     */
    protected State postProcessDecodedNumberState(State state) {
        return state;
    }

}
