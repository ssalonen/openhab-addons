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
package org.openhab.binding.modbus.internal;

import java.util.OptionalInt;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.io.transport.modbus.ModbusReadFunctionCode;

/**
 * Handler for read channels, responsible of necessary transformations
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public class ReadChannelHandler {

    // defaults
    // 1. assume bit value type if poller is readingDiscreteOrCoil

    // validation input:
    // CASE 1 INPUT: poller data type, poll start, poll length, channel start, channel value type (other than hex
    // string)
    // CASE 2 INPUT: poller data type, poll start, poll length, channel start, channel length (hex string)
    // [CASE 1] 1. if poller is readingDiscreteOrCoil check that bit value type
    // [CASE 1] 2. if poller is readingDiscreteOrCoil, check that readStart=X not readStart=X.Y
    // [CASE 1] 3. if poller is reading registers, and valueType < 16 bit, check that readStart=X.Y
    // [CASE 1] 4. if having readStart=X.Y, check that Y is within limits (0...1, 0...15)
    // [CASE 1] 5. if poller is reading registers, and valueType >= 16 bit, check that readStart=X
    // [CASE 1&2] 6. check that readStart is within limits of polled data
    // [CASE 1&2] 7. check that last byte/bit expected is within limits of polled data

    /**
     * Validate read parameters used to specify decoding of binary data polled from modbus
     *
     * Exceptions are thrown for user display
     *
     *
     * Checks
     * 0. validate format of channelStart. Should be X or X.Y
     * 1. if poller is READ_COILS or READ_INPUT_DISCRETES, check that we have BIT channelValueType
     * 2. if poller is READ_COILS or READ_INPUT_DISCRETES, check that channelStart=X not channelStart=X.Y
     * 3. if poller is reading registers, and channelValueType < 16 bit, check that channelStart=X.Y
     * 4. if having channelStart=X.Y, check that Y is within limits (not referring to data outside register)
     * 5. if poller is reading registers, and channelValueType >= 16 bit, check that channelStart=X
     * 6. Check that decoding starts within the limits of polled data
     * 7. Check that decoding ends within the limits of polled data
     *
     * @param pollerFunctionCode function code used for polled data
     * @param pollerStart start address for polled data
     * @param pollerLength length of elements for polled data
     * @param channelStart start address to start decoding
     * @param channelValueType value type for decoding
     * @return true when channelStart and channelValueType are within limits of polled data and are of correct
     *         syntax/format
     * @throws ModbusConfigurationException with attempt to decode out-of-bounds data or when syntax is incorrect
     */
    public static boolean validateConfigCase1(ModbusReadFunctionCode pollerFunctionCode, int pollerStart,
            int pollerLength, String channelStart, ValueType channelValueType) throws ModbusConfigurationException {

        // CHECK 0
        final int channelStartElement;
        final OptionalInt channelStartElementSub;
        {
            String[] readParts = channelStart.split("\\.", 2);
            try {
                channelStartElement = Integer.parseInt(readParts[0]);
                if (readParts.length == 2) {
                    channelStartElementSub = OptionalInt.of(Integer.parseInt(readParts[1]));
                } else {
                    channelStartElementSub = OptionalInt.empty();
                }
            } catch (IllegalArgumentException e) {
                String errmsg = String.format("Invalid address=%s, expecting X or X.Y!", channelStart);
                throw new ModbusConfigurationException(errmsg);
            }
        }

        if (pollerFunctionCode == ModbusReadFunctionCode.READ_COILS
                || pollerFunctionCode == ModbusReadFunctionCode.READ_INPUT_DISCRETES) {
            // Reading bit-type data

            // CHECK 1: BIT value type supported only with READ_COILS and READ_INPUT_DISCRETES
            if (channelValueType != ValueType.BIT) {
                return false;
            }
            // CHECK 2: channelStart=X.Y not supported with READ_COILS and READ_INPUT_DISCRETES, only channelStart=X
            if (channelStartElementSub.isPresent()) {
                String errmsg = String
                        .format("Invalid address format X.Y, only address=X allowed with coils or discrete inputs!");
                throw new ModbusConfigurationException(errmsg);
            }

        } else {
            // Reading register data

            if (channelValueType.getBits() < 16) {
                // CHECK 3
                if (channelStartElementSub.isEmpty()) {
                    throw new ModbusConfigurationException(
                            "Invalid address format X, only address=X.Y allowed with valueType less than 16bit!");
                }
                // CHECK 4
                {
                    int subAddress = channelStartElementSub.getAsInt();
                    if (subAddress > 16 / channelValueType.getBits() - 1) {
                        throw new ModbusConfigurationException(String.format(
                                "Invalid address=X.Y=%s, value Y is referring to data outside register. Maximum value for Y is %d",
                                channelStart, 16 / channelValueType.getBits() - 1));
                    }
                }
            } else {
                // CHECK 5
                if (channelStartElementSub.isPresent()) {
                    throw new ModbusConfigurationException(
                            "address=X must be used with valueType greater than or equal 16bit!");
                }
            }
        }

        // CHECK 6 & 7
        {
            // Determine bit positions polled, both start and end inclusive
            int dataElementBits = ((pollerFunctionCode == ModbusReadFunctionCode.READ_COILS
                    || pollerFunctionCode == ModbusReadFunctionCode.READ_INPUT_DISCRETES) ? 1 : 16);
            int polledFirstBitIndex = pollerStart * dataElementBits;
            int polledLastBitIndex = polledFirstBitIndex + pollerLength * dataElementBits - 1;

            // Determine bit positions read, both start and end inclusive
            int decodeStartBitIndex = channelStartElement * dataElementBits
                    + channelStartElementSub.orElse(0) * channelValueType.getBits();
            int decodeEndBitIndex = decodeStartBitIndex + channelValueType.getBits() - 1;

            if (decodeStartBitIndex < polledFirstBitIndex || decodeEndBitIndex > polledLastBitIndex) {
                String errmsg = String.format(
                        "Out-of-bounds: Poller is reading from index %d to %d (inclusive) but this thing configured to read '%s' starting from element %d. Exceeds polled data bounds.",
                        polledFirstBitIndex / dataElementBits, polledLastBitIndex / dataElementBits, channelValueType,
                        channelStartElement);
                throw new ModbusConfigurationException(errmsg);
            }
        }

        // All checks passed, OK
        return true;
    }

}
