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

import static org.openhab.binding.modbus.internal.ModbusBindingConstantsInternal.WRITE_TYPE_COIL;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.modbus.internal.ChannelConfigValidationMessage;
import org.openhab.binding.modbus.internal.handler.ReadIntoChannelHandler.Address;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.io.transport.modbus.ModbusReadFunctionCode;

/**
 * Handler for write channels, transforming openHAB commands into raw binary data and modbus write requests
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public class WriteChannelHandler {

    //
    // defaults:
    // - assume BIT if writing coil

    /**
     * Validate write parameters used to specify transformation of openHAB command to modbus request
     *
     * Checks
     * 0. validate format of channelStart. Should be X or X.Y
     *
     * 1. if writing coils, check that we have BIT channelValueType
     * 2. if writing coils, check that channelStart=X
     * 3. if writing registers, and channelValueType < 16 bit, check that a) channelStart=X.Y and b) poller is
     * READ_MULTIPLE_REGISTERS
     * 4. if writing registers, and channelStart=X.Y, check that Y is within limits (not referring to data outside
     * register)
     *
     * 5. if writing registers, and channelValueType >= 16 bit, check that channelStart=X
     *
     * Since writing to register with channelStart=X.Y actually uses polled data to construct register to write:
     * 6. if writing registers, and channelStart=X.Y, check that we are within limits of polled data
     * 7. if writing registers, and channelStart=X.Y, check that we are within limits of polled data
     *
     * 8. if writing registers, and channelValueType < 16 bit, support for channelValueType=bit only implemented now,
     * not for (u)int8.
     *
     * @param pollerFunctionCode function code used for polled data
     * @param pollerStart start address for polled data
     * @param pollerLength length of elements for polled data
     * @param writeType written element type, either "coil" or "holding"
     * @param channelStart start address to start encoding
     * @param channelValueType value type for encoding
     * @return Empty list when validation passes without errors. Otherwise list of validation errors is returned.
     */
    public static List<ChannelConfigValidationMessage> validateWriteParameters(
            ModbusReadFunctionCode pollerFunctionCode, int pollerStart, int pollerLength, String writeType,
            String channelStart, ValueType channelValueType) {

        List<ChannelConfigValidationMessage> validationIssues = new ArrayList<>();

        // CHECK 0
        final int channelStartElement;
        final OptionalInt channelStartElementSub;
        try {
            Address parsedAddress = Address.parse(channelStart);
            channelStartElement = parsedAddress.channelStartElement;
            channelStartElementSub = parsedAddress.channelStartElementSub;
        } catch (IllegalArgumentException e) {
            String errmsg = String.format("Invalid address=%s, expecting X or X.Y", channelStart);
            validationIssues.add(new ChannelConfigValidationMessage(errmsg));
            // Critical validation issue, stop validating other things
            return validationIssues;
        }

        if (writeType == WRITE_TYPE_COIL) {
            // Writing bit-type data

            // CHECK 1
            if (channelValueType != ValueType.BIT) {
                validationIssues.add(new ChannelConfigValidationMessage(String.format(
                        "Invalid valueType=%s, only \"bit\" is supported when writing coils", channelValueType)));
            }
            // CHECK 2: channelStart=X.Y not supported with coils
            if (channelStartElementSub.isPresent()) {
                String errmsg = String.format("Invalid address=X.Y, only address=X allowed when writing coils");
                validationIssues.add(new ChannelConfigValidationMessage(errmsg));
            }
        } else {
            // Writing register data

            if (channelValueType.getBits() < 16) {
                // Writing only part of a register, polled data will be utilized to construct the whole register for
                // write request

                // CHECK 3a
                if (channelStartElementSub.isEmpty()) {
                    validationIssues.add(new ChannelConfigValidationMessage(String.format(
                            "Invalid address=X, must use address=X.Y with valueType=%s to ambiguously refer to data inside the register",
                            channelValueType)));
                }
                // CHECK 3b & 4 (check only if channelStartElementSub is present -- i.e. above CHECK 3a passes)
                else {
                    // CHECK 8
                    if (channelValueType != ValueType.BIT) {
                        validationIssues.add(new ChannelConfigValidationMessage(String.format(
                                "Invalid valueType=%s. With address=X.Y, only valueType=bit is supported (bit of register)",
                                channelValueType)));
                    }

                    // CHECK 3b
                    if (pollerFunctionCode != ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS) {
                        validationIssues.add(new ChannelConfigValidationMessage(
                                "Expecting poller to read holding registers when write channel has address=X.Y"));
                    }

                    // CHECK 4
                    int subAddress = channelStartElementSub.getAsInt();
                    if (subAddress > 16 / channelValueType.getBits() - 1) {
                        validationIssues.add(new ChannelConfigValidationMessage(String.format(
                                "Invalid address=X.Y=%s, value Y is referring to data outside register. Maximum value for Y with valueType=%s is %d",
                                channelStart, channelValueType, 16 / channelValueType.getBits() - 1)));
                    }

                    // CHECK 6 & 7
                    {
                        // Determine bit positions polled, both start and end inclusive
                        int dataElementBits = 16; // always writing 16 bit holding registers
                        int pollFirstBitIndex = pollerStart * dataElementBits;
                        int pollLastBitIndex = pollFirstBitIndex + pollerLength * dataElementBits - 1;

                        // Determine bit positions read, both start and end inclusive
                        int encodeStartBitIndex = channelStartElement * dataElementBits
                                + subAddress * channelValueType.getBits();
                        int encodeEndBitIndex = encodeStartBitIndex + channelValueType.getBits() - 1;

                        if (encodeStartBitIndex < pollFirstBitIndex || encodeEndBitIndex > pollLastBitIndex) {
                            String errmsg = String.format(
                                    "Invalid address=X=%d is out-of-bounds. Poller is reading from index %d to %d (inclusive) but this thing configured to write starting from element %d. Must write within polled limits",
                                    channelStartElement, pollFirstBitIndex / dataElementBits,
                                    pollLastBitIndex / dataElementBits, channelStartElement);
                            validationIssues.add(new ChannelConfigValidationMessage(errmsg));
                        }
                    }
                }
            } else {
                // CHECK 5
                if (channelStartElementSub.isPresent()) {
                    validationIssues.add(new ChannelConfigValidationMessage(String.format(
                            "Invalid address=X.Y, only address=X allowed with valueType=%s, denoting first register address to start encoding the valueType %s",
                            channelValueType, channelValueType)));
                }
            }
        }

        return validationIssues;
    }

}
