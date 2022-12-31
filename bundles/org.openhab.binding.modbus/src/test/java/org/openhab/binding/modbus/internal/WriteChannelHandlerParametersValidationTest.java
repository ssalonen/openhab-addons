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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.openhab.binding.modbus.internal.ModbusBindingConstantsInternal.*;

import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.io.transport.modbus.ModbusReadFunctionCode;

/**
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class WriteChannelHandlerParametersValidationTest {
    private static Stream<Arguments> provideArgsForTestValidateConfig()

    {
        // Arguments:
        // 1. expected validity
        // 2. poll function
        // 3. poll start
        // 4. poll length
        // 5. write type
        // 6. channel start
        // 7. address
        // 8. channel value type
        return Stream.of(
        //@formatter:off
                // (Happy flow) Writing of coil, poller does not matter
                Arguments.of(true, ModbusReadFunctionCode.READ_COILS, 4, 3, WRITE_TYPE_COIL, "8", ValueType.BIT),
                // (Happy flow) Writing of holding register, poller does not matter
                Arguments.of(true, ModbusReadFunctionCode.READ_COILS, 4, 3, WRITE_TYPE_HOLDING, "8", ValueType.INT16),

                //
                // writing byte-of-register
                //
                // Invalid write address, expecting to specify byte-within-register index with address=X.Y
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "8", ValueType.INT8),
                // Same test now with address=X.Y -> OK
                Arguments.of(true, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "8.0", ValueType.INT8),
                Arguments.of(true, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "8.1", ValueType.INT8),
                // out-of-bounds Y (there is no 3rd byte in register)
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "8.2", ValueType.INT8),
                // out-of-bounds X (register 7 not polled)
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "7.0", ValueType.INT8),
                // out-of-bounds X (register 11 not polled)
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "11.0", ValueType.INT8),

                // spot check, polling input register 8, trying to write byte of holding register 8
                // -> invalid as input register and holding register are a different thing
                Arguments.of(false, ModbusReadFunctionCode.READ_INPUT_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "8.0", ValueType.INT8),


                //
                // writing bit-of-register
                //
                // Invalid write address, expecting to specify bit-within-register index with address=X.Y
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "8", ValueType.BIT),
                // Same test now with address=X.Y -> OK
                Arguments.of(true, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "8.0", ValueType.BIT),
                Arguments.of(true, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "8.15", ValueType.BIT),
                // out-of-bounds Y (there is no 17th bit in register)
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "8.16", ValueType.BIT),
                // out-of-bounds X (register 7 not polled)
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "7.0", ValueType.BIT),
                // out-of-bounds X (register 11 not polled)
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "11.0", ValueType.BIT),

                // spot check, polling input register 8, trying to write bit of holding register 8
                // -> invalid as input register and holding register are a different thing
                Arguments.of(false, ModbusReadFunctionCode.READ_INPUT_REGISTERS, 8, 3, WRITE_TYPE_HOLDING, "8.0", ValueType.BIT),


                // address=X.Y not ok for COIL writes
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 4, 3, WRITE_TYPE_COIL, "8.0", ValueType.BIT)


                //@formatter:on
        );

    }

    @ParameterizedTest
    @MethodSource("provideArgsForTestValidateConfig")
    public void testValidateConfig(boolean expectedValidity, ModbusReadFunctionCode pollerFunctionCode, int pollerStart,
            int pollerLength, String writeType, String channelStart, ValueType channelValueType) {

        List<ChannelConfigValidationMessage> validationErrors = WriteChannelHandler.validateConfigCase1(
                pollerFunctionCode, pollerStart, pollerLength, writeType, channelStart, channelValueType);
        assertEquals(expectedValidity, validationErrors.isEmpty(), validationErrors.toString());
    }

}
