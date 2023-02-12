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

import java.util.List;
import java.util.stream.Stream;
import java.util.stream.Stream.Builder;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openhab.binding.modbus.internal.handler.ReadIntoChannelHandler;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.io.transport.modbus.ModbusReadFunctionCode;

/**
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ReadChannelHandlerParametersValidationTest {
    private static Stream<Arguments> provideArgsForTestValidateConfig() {
        // Arguments:
        // 1. expected validity
        // 2. poll function
        // 3. poll start
        // 4. poll length
        // 5. channel start
        // 6. address
        // 7. value type
        return Stream.of(
        //@formatter:off
                //
                // reading coils 4, 5, 6. Coil 7 & 8 are out of bounds. Coil 6 within bounds
                // (same test for discrete inputs)
                //
                Arguments.of(false, ModbusReadFunctionCode.READ_COILS, 4, 3, "8", ValueType.BIT),
                Arguments.of(false, ModbusReadFunctionCode.READ_INPUT_DISCRETES, 4, 3, "8", ValueType.BIT),
                Arguments.of(false, ModbusReadFunctionCode.READ_COILS, 4, 3, "7", ValueType.BIT),
                Arguments.of(false, ModbusReadFunctionCode.READ_INPUT_DISCRETES, 4, 3, "7", ValueType.BIT),
                Arguments.of(true, ModbusReadFunctionCode.READ_COILS, 4, 3, "6", ValueType.BIT),
                Arguments.of(true, ModbusReadFunctionCode.READ_INPUT_DISCRETES, 4, 3, "6", ValueType.BIT),

                // not allowed to have sub-index with discrete inputs or coils
                Arguments.of(false, ModbusReadFunctionCode.READ_INPUT_DISCRETES, 4, 3, "6.0", ValueType.BIT),
                Arguments.of(false, ModbusReadFunctionCode.READ_COILS, 4, 3, "6.0", ValueType.BIT),
                //
                // reading registers 4, 5, 6, and decoding individual BIT from registers
                // - Register 8 bit 0: out of bounds
                // - 7.16 out of bounds: bit 16 over the register (bit index 15 is max)
                // - 6.16 out of bounds: similarly invalid
                // - 6.15 OK: most significant bit of register 6
                // - 6.0 OK: least significant bit of register 6
                // - 6.1 OK: 2nd least significant bit of register 6
                //
                // (same test for discrete inputs)
                //
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 4, 3, "8.0", ValueType.BIT),
                Arguments.of(false, ModbusReadFunctionCode.READ_INPUT_REGISTERS, 4, 3, "8.0", ValueType.BIT),
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 4, 3, "7.16", ValueType.BIT),
                Arguments.of(false, ModbusReadFunctionCode.READ_INPUT_REGISTERS, 4, 3, "7.16", ValueType.BIT),
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 4, 3, "6.16", ValueType.BIT),
                Arguments.of(false, ModbusReadFunctionCode.READ_INPUT_REGISTERS, 4, 3, "6.16", ValueType.BIT),
                Arguments.of(true, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 4, 3, "6.15", ValueType.BIT),
                Arguments.of(true, ModbusReadFunctionCode.READ_INPUT_REGISTERS, 4, 3, "6.15", ValueType.BIT),
                Arguments.of(true, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 4, 3, "6.0", ValueType.BIT),
                Arguments.of(true, ModbusReadFunctionCode.READ_INPUT_REGISTERS, 4, 3, "6.0", ValueType.BIT),
                //
                // reading registers 4, 5, 6, and decoding with INT16
                // - register 6 OK
                // - register 7 & 8 out of bounds
                // - address 6.0 invalid (not expecting sub-index)
                //
                // (same test for discrete inputs)
                //
                Arguments.of(true, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 4, 3, "6", ValueType.INT16),
                Arguments.of(true, ModbusReadFunctionCode.READ_INPUT_REGISTERS, 4, 3, "6", ValueType.INT16),
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 4, 3, "7", ValueType.INT16),
                Arguments.of(false, ModbusReadFunctionCode.READ_INPUT_REGISTERS, 4, 3, "7", ValueType.INT16),
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 4, 3, "8", ValueType.INT16),
                Arguments.of(false, ModbusReadFunctionCode.READ_INPUT_REGISTERS, 4, 3, "8", ValueType.INT16),
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 4, 3, "6.0", ValueType.INT16),
                Arguments.of(false, ModbusReadFunctionCode.READ_INPUT_REGISTERS, 4, 3, "6.0", ValueType.INT16),
                //
                // reading registers 4, 5, 6, and decoding with INT32
                // - address 5 OK (decodes registers 5 & 6 as INT32)
                // - address 6 out-of-bounds (would need register 7 for decoding INT32)
                //
                // (same test for discrete inputs)
                //
                Arguments.of(true, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 4, 3, "5", ValueType.INT32),
                Arguments.of(true, ModbusReadFunctionCode.READ_INPUT_REGISTERS, 4, 3, "5", ValueType.INT32),
                Arguments.of(false, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS, 4, 3, "6", ValueType.INT32),
                Arguments.of(false, ModbusReadFunctionCode.READ_INPUT_REGISTERS, 4, 3, "6", ValueType.INT32),

                // Decoding coils into FLOAT32 not supported
                Arguments.of(false, ModbusReadFunctionCode.READ_COILS, 0, 3, "0", ValueType.FLOAT32),
                Arguments.of(false, ModbusReadFunctionCode.READ_INPUT_DISCRETES, 0, 3, "0", ValueType.FLOAT32)
                //@formatter:on
        );

    }

    @ParameterizedTest
    @MethodSource("provideArgsForTestValidateConfig")
    public void testValidateConfig(boolean expectedValidity, ModbusReadFunctionCode pollerFunctionCode, int pollerStart,
            int pollerLength, String channelStart, ValueType channelValueType) {

        List<ChannelConfigValidationMessage> validationErrors = ReadIntoChannelHandler
                .validateReadParameters(pollerFunctionCode, pollerStart, pollerLength, channelStart, channelValueType);
        assertEquals(expectedValidity, validationErrors.isEmpty(), validationErrors.toString());
    }

    private static Stream<Arguments> provideArgsForTestValidateConfigRaw() {
        return Stream.of(
        //@formatter:off
                Arguments.of(true, 0, 3, "0", 3),
                Arguments.of(true, 0, 3, "0", 2),
                Arguments.of(true, 0, 3, "2", 1),
                Arguments.of(false, 0, 3, "0", 4),
                Arguments.of(false, 0, 3, "3", 1),
                Arguments.of(false, 0, 3, "-3", 1)
                //@formatter:on
        )
                // generate same test with all function codes
                .flatMap(args -> crossProduct(args, new ModbusReadFunctionCode[] { ModbusReadFunctionCode.READ_COILS,
                        ModbusReadFunctionCode.READ_INPUT_DISCRETES, ModbusReadFunctionCode.READ_INPUT_REGISTERS,
                        ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS }));

    }

    @ParameterizedTest
    @MethodSource("provideArgsForTestValidateConfigRaw")
    public void testValidateConfigRaw(boolean expectedValidity, int pollerStart, int pollerLength,
            String channelAddress, int channelLength, ModbusReadFunctionCode fc) {
        assertEquals(expectedValidity, ReadIntoChannelHandler
                .validateReadParametersRaw(fc, pollerStart, pollerLength, channelAddress, channelLength).isEmpty(),
                fc.toString() + " fail");
    }

    // Generate variation of args, each appended with one of additionalParam
    private static Stream<Arguments> crossProduct(Arguments args, Object[] additionalParam) {
        Object[] objs1 = args.get();
        Builder<Arguments> streamBuilder = Stream.builder();

        for (Object add : additionalParam) {
            Object[] result = new Object[objs1.length + 1];
            System.arraycopy(objs1, 0, result, 0, objs1.length);
            result[objs1.length] = add;
            streamBuilder.add(Arguments.of(result));
        }

        return streamBuilder.build();
    }

}
