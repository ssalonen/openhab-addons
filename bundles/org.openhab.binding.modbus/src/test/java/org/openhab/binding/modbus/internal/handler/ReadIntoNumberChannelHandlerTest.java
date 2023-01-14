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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collection;
import java.util.Collections;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.openhab.binding.modbus.config.ReadChannelConfiguration;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;;

/**
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ReadIntoNumberChannelHandlerTest {

    /**
     * Inverse of ReadIntoNumberChannelHandler.extractIndexFromRelative
     *
     * For reusing tests from org.openhab.core.io.transport.modbus.test.BitUtilitiesExtractStateFromRegistersTest
     */
    private static String calculateRelativeAdress(int extractIndex, ValueType valueType) {
        if (valueType.getBits() < 16) {
            int itemsPerRegister = 16 / valueType.getBits();
            int registerRelative = extractIndex / itemsPerRegister;
            int subIndex = extractIndex - registerRelative * itemsPerRegister;
            return registerRelative + "." + subIndex;
        } else {
            return String.valueOf(extractIndex);
        }
    }

    private static ModbusRegisterArray shortArrayToRegisterArray(int... arr) {
        return new ModbusRegisterArray(arr);
    }

    // Adapted from org.openhab.core.io.transport.modbus.test.BitUtilitiesExtractStateFromRegistersTest
    public static Collection<Object[]> provideArgsForReadIntoNumberFromRegistersTest() {
        return Collections.unmodifiableList(Stream.of(
                //
                // BIT
                //
                new Object[] { new DecimalType("1.0"), ValueType.BIT,
                        shortArrayToRegisterArray(1 << 5 | 1 << 4 | 1 << 15), 4 },
                new Object[] { new DecimalType("1.0"), ValueType.BIT,
                        shortArrayToRegisterArray(1 << 5 | 1 << 4 | 1 << 15), 15 },
                new Object[] { new DecimalType("0.0"), ValueType.BIT, shortArrayToRegisterArray(1 << 5), 7 },
                new Object[] { new DecimalType("1.0"), ValueType.BIT, shortArrayToRegisterArray(1 << 5), 5 },
                new Object[] { new DecimalType("0.0"), ValueType.BIT, shortArrayToRegisterArray(1 << 5), 4 },
                new Object[] { new DecimalType("0.0"), ValueType.BIT, shortArrayToRegisterArray(1 << 5), 0 },
                new Object[] { new DecimalType("0.0"), ValueType.BIT, shortArrayToRegisterArray(0, 0), 15 },
                new Object[] { new DecimalType("1.0"), ValueType.BIT, shortArrayToRegisterArray(1 << 5, 1 << 4), 5 },
                new Object[] { new DecimalType("1.0"), ValueType.BIT, shortArrayToRegisterArray(1 << 5, 1 << 4), 20 },
                //
                // INT8
                //
                new Object[] { new DecimalType("5.0"), ValueType.INT8, shortArrayToRegisterArray(5), 0 },
                new Object[] { new DecimalType("-5.0"), ValueType.INT8, shortArrayToRegisterArray(-5), 0 },
                new Object[] { new DecimalType("3.0"), ValueType.INT8,
                        shortArrayToRegisterArray(((byte) 6 << 8) | (byte) 3), 0 },
                new Object[] { new DecimalType("6.0"), ValueType.INT8,
                        shortArrayToRegisterArray(((byte) 6 << 8) | (byte) 3), 1 },
                new Object[] { new DecimalType("4.0"), ValueType.INT8,
                        shortArrayToRegisterArray(((byte) 6 << 8) | (byte) 3, 4), 2 },
                new Object[] { new DecimalType("6.0"), ValueType.INT8,
                        shortArrayToRegisterArray(55, ((byte) 6 << 8) | (byte) 3), 3 },
                //
                // UINT8
                //
                new Object[] { new DecimalType("5.0"), ValueType.UINT8, shortArrayToRegisterArray(5), 0 },
                new Object[] { new DecimalType("251.0"), ValueType.UINT8, shortArrayToRegisterArray(-5), 0 },
                new Object[] { new DecimalType("3.0"), ValueType.UINT8,
                        shortArrayToRegisterArray(((byte) 6 << 8) | (byte) 3), 0 },
                new Object[] { new DecimalType("6.0"), ValueType.UINT8,
                        shortArrayToRegisterArray(((byte) 6 << 8) | (byte) 3), 1 },
                new Object[] { new DecimalType("4.0"), ValueType.UINT8,
                        shortArrayToRegisterArray(((byte) 6 << 8) | (byte) 3, 4), 2 },
                new Object[] { new DecimalType("6.0"), ValueType.UINT8,
                        shortArrayToRegisterArray(55, ((byte) 6 << 8) | (byte) 3), 3 },

                //
                // INT16
                //
                new Object[] { new DecimalType("1.0"), ValueType.INT16, shortArrayToRegisterArray(1), 0 },
                new Object[] { new DecimalType("2.0"), ValueType.INT16, shortArrayToRegisterArray(2), 0 },
                new Object[] { new DecimalType("-1004"), ValueType.INT16, shortArrayToRegisterArray(-1004), 0 },
                new Object[] { new DecimalType("-1536"), ValueType.INT16, shortArrayToRegisterArray(64000), 0 },
                new Object[] { new DecimalType("-1004"), ValueType.INT16, shortArrayToRegisterArray(4, -1004), 1 },
                new Object[] { new DecimalType("-1004"), ValueType.INT16, shortArrayToRegisterArray(-1004, 4), 0 },
                //
                // UINT16
                //
                new Object[] { new DecimalType("1.0"), ValueType.UINT16, shortArrayToRegisterArray(1), 0 },
                new Object[] { new DecimalType("2.0"), ValueType.UINT16, shortArrayToRegisterArray(2), 0 },
                new Object[] { new DecimalType("64532"), ValueType.UINT16, shortArrayToRegisterArray(-1004), 0 },
                new Object[] { new DecimalType("64000"), ValueType.UINT16, shortArrayToRegisterArray(64000), 0 },
                new Object[] { new DecimalType("64532"), ValueType.UINT16, shortArrayToRegisterArray(4, -1004), 1 },
                new Object[] { new DecimalType("64532"), ValueType.UINT16, shortArrayToRegisterArray(-1004, 4), 0 },
                //
                // INT32
                //
                new Object[] { new DecimalType("1.0"), ValueType.INT32, shortArrayToRegisterArray(0, 1), 0 },
                new Object[] { new DecimalType("2.0"), ValueType.INT32, shortArrayToRegisterArray(0, 2), 0 },
                new Object[] { new DecimalType("-1004"), ValueType.INT32,
                        // -1004 = 0xFFFFFC14 (32bit) =
                        shortArrayToRegisterArray(0xFFFF, 0xFC14), 0 },
                new Object[] { new DecimalType("64000"), ValueType.INT32, shortArrayToRegisterArray(0, 64000), 0 },
                new Object[] { new DecimalType("-1004"), ValueType.INT32,
                        // -1004 = 0xFFFFFC14 (32bit) =
                        shortArrayToRegisterArray(0x4, 0xFFFF, 0xFC14), 1 },
                new Object[] { new DecimalType("-1004"), ValueType.INT32,
                        // -1004 = 0xFFFFFC14 (32bit) =
                        shortArrayToRegisterArray(0xFFFF, 0xFC14, 0x4), 0 },
                //
                // UINT32
                //
                new Object[] { new DecimalType("1.0"), ValueType.UINT32, shortArrayToRegisterArray(0, 1), 0 },
                new Object[] { new DecimalType("2.0"), ValueType.UINT32, shortArrayToRegisterArray(0, 2), 0 },
                new Object[] { new DecimalType("4294966292"), ValueType.UINT32,
                        // 4294966292 = 0xFFFFFC14 (32bit) =
                        shortArrayToRegisterArray(0xFFFF, 0xFC14), 0 },
                new Object[] { new DecimalType("64000"), ValueType.UINT32, shortArrayToRegisterArray(0, 64000), 0 },
                new Object[] {
                        // out of bounds of unsigned 16bit (0 to 65,535)
                        new DecimalType("70004"),
                        // 70004 -> 0x00011174 (32bit) -> 0x1174 (16bit)
                        ValueType.UINT32, shortArrayToRegisterArray(1, 4468), 0 },
                new Object[] { new DecimalType("4294966292"), ValueType.UINT32,
                        // 4294966292 = 0xFFFFFC14 (32bit) =
                        shortArrayToRegisterArray(0xFFFF, 0xFC14, 0x5), 0 },
                new Object[] { new DecimalType("4294966292"), ValueType.UINT32,
                        // 4294966292 = 0xFFFFFC14 (32bit) =
                        shortArrayToRegisterArray(0x5, 0xFFFF, 0xFC14), 1 },
                //
                // INT32_SWAP
                //
                new Object[] { new DecimalType("1.0"), ValueType.INT32_SWAP, shortArrayToRegisterArray(1, 0), 0 },
                new Object[] { new DecimalType("2.0"), ValueType.INT32_SWAP, shortArrayToRegisterArray(2, 0), 0 },
                new Object[] { new DecimalType("-1004"), ValueType.INT32_SWAP,
                        // -1004 = 0xFFFFFC14 (32bit) =
                        shortArrayToRegisterArray(0xFC14, 0xFFFF), 0 },
                new Object[] { new DecimalType("64000"), ValueType.INT32_SWAP, shortArrayToRegisterArray(64000, 0), 0 },
                new Object[] { new DecimalType("-1004"), ValueType.INT32_SWAP,
                        // -1004 = 0xFFFFFC14 (32bit) =
                        shortArrayToRegisterArray(0x4, 0xFC14, 0xFFFF), 1 },
                new Object[] { new DecimalType("-1004"), ValueType.INT32_SWAP,
                        // -1004 = 0xFFFFFC14 (32bit) =
                        shortArrayToRegisterArray(0xFC14, 0xFFFF, 0x4), 0 },
                //
                // UINT32_SWAP
                //
                new Object[] { new DecimalType("1.0"), ValueType.UINT32_SWAP, shortArrayToRegisterArray(1, 0), 0 },
                new Object[] { new DecimalType("2.0"), ValueType.UINT32_SWAP, shortArrayToRegisterArray(2, 0), 0 },
                new Object[] { new DecimalType("4294966292"), ValueType.UINT32_SWAP,
                        // 4294966292 = 0xFFFFFC14 (32bit) =
                        shortArrayToRegisterArray(0xFC14, 0xFFFF), 0 },
                new Object[] { new DecimalType("64000"), ValueType.UINT32_SWAP, shortArrayToRegisterArray(64000, 0),
                        0 },
                new Object[] {
                        // out of bounds of unsigned 16bit (0 to 65,535)
                        new DecimalType("70004"),
                        // 70004 -> 0x00011174 (32bit) -> 0x1174 (16bit)
                        ValueType.UINT32_SWAP, shortArrayToRegisterArray(4468, 1), 0 },
                new Object[] { new DecimalType("4294966292"), ValueType.UINT32_SWAP,
                        // 4294966292 = 0xFFFFFC14 (32bit) =
                        shortArrayToRegisterArray(0xFC14, 0xFFFF, 0x5), 0 },
                new Object[] { new DecimalType("4294966292"), ValueType.UINT32_SWAP,
                        // 4294966292 = 0xFFFFFC14 (32bit) =
                        shortArrayToRegisterArray(0x5, 0xFC14, 0xFFFF), 1 },
                //
                // FLOAT32
                //
                new Object[] { new DecimalType("1.0"), ValueType.FLOAT32, shortArrayToRegisterArray(0x3F80, 0x0000),
                        0 },
                new Object[] { new DecimalType(1.6f), ValueType.FLOAT32, shortArrayToRegisterArray(0x3FCC, 0xCCCD), 0 },
                new Object[] { new DecimalType(2.6f), ValueType.FLOAT32, shortArrayToRegisterArray(0x4026, 0x6666), 0 },
                new Object[] { new DecimalType(-1004.4f), ValueType.FLOAT32, shortArrayToRegisterArray(0xC47B, 0x199A),
                        0 },
                new Object[] { new DecimalType("64000"), ValueType.FLOAT32, shortArrayToRegisterArray(0x477A, 0x0000),
                        0 },
                new Object[] {
                        // out of bounds of unsigned 16bit (0 to 65,535)
                        new DecimalType(70004.4f), ValueType.FLOAT32, shortArrayToRegisterArray(0x4788, 0xBA33), 0 },
                new Object[] {
                        // out of bounds of unsigned 32bit (0 to 4,294,967,295)
                        new DecimalType("5000000000"), ValueType.FLOAT32, shortArrayToRegisterArray(0x4F95, 0x02F9),
                        0 },
                new Object[] { new DecimalType(-1004.4f), ValueType.FLOAT32,
                        shortArrayToRegisterArray(0x4, 0xC47B, 0x199A), 1 },
                new Object[] { new DecimalType(-1004.4f), ValueType.FLOAT32,
                        shortArrayToRegisterArray(0xC47B, 0x199A, 0x4), 0 },
                new Object[] { // equivalent of NaN
                        UnDefType.UNDEF, ValueType.FLOAT32, shortArrayToRegisterArray(0x7fc0, 0x0000), 0 },
                new Object[] { new DecimalType(-1004.4f), ValueType.FLOAT32,
                        shortArrayToRegisterArray(0x4, 0x0, 0x0, 0x0, 0xC47B, 0x199A), 4 },
                //
                // FLOAT32_SWAP
                //
                new Object[] { new DecimalType("1.0"), ValueType.FLOAT32_SWAP,
                        shortArrayToRegisterArray(0x0000, 0x3F80), 0 },
                new Object[] { new DecimalType(1.6f), ValueType.FLOAT32_SWAP, shortArrayToRegisterArray(0xCCCD, 0x3FCC),
                        0 },
                new Object[] { new DecimalType(2.6f), ValueType.FLOAT32_SWAP, shortArrayToRegisterArray(0x6666, 0x4026),
                        0 },
                new Object[] { new DecimalType(-1004.4f), ValueType.FLOAT32_SWAP,
                        shortArrayToRegisterArray(0x199A, 0xC47B), 0 },
                new Object[] { new DecimalType("64000"), ValueType.FLOAT32_SWAP,
                        shortArrayToRegisterArray(0x0000, 0x477A), 0 },
                new Object[] { // equivalent of NaN
                        UnDefType.UNDEF, ValueType.FLOAT32_SWAP, shortArrayToRegisterArray(0x0000, 0x7fc0), 0 },
                new Object[] {
                        // out of bounds of unsigned 16bit (0 to 65,535)
                        new DecimalType(70004.4f), ValueType.FLOAT32_SWAP, shortArrayToRegisterArray(0xBA33, 0x4788),
                        0 },
                new Object[] {
                        // out of bounds of unsigned 32bit (0 to 4,294,967,295)
                        new DecimalType("5000000000"), ValueType.FLOAT32_SWAP,
                        shortArrayToRegisterArray(0x02F9, 0x4F95), 0 },
                new Object[] { new DecimalType(-1004.4f), ValueType.FLOAT32_SWAP,
                        shortArrayToRegisterArray(0x4, 0x199A, 0xC47B), 1 },
                new Object[] { new DecimalType(-1004.4f), ValueType.FLOAT32_SWAP,
                        shortArrayToRegisterArray(0x199A, 0xC47B, 0x4), 0 },

                //
                // INT64
                //
                new Object[] { new DecimalType("1.0"), ValueType.INT64, shortArrayToRegisterArray(0, 0, 0, 1), 0 },
                new Object[] { new DecimalType("2.0"), ValueType.INT64, shortArrayToRegisterArray(0, 0, 0, 2), 0 },
                new Object[] { new DecimalType("-1004"), ValueType.INT64,
                        shortArrayToRegisterArray(0xFFFF, 0xFFFF, 0xFFFF, 0xFC14), 0 },
                new Object[] { new DecimalType("64000"), ValueType.INT64, shortArrayToRegisterArray(0, 0, 0, 64000),
                        0 },
                new Object[] {
                        // out of bounds of unsigned 32bit
                        new DecimalType("34359738368"), ValueType.INT64, shortArrayToRegisterArray(0x0, 0x8, 0x0, 0x0),
                        0 },
                new Object[] { new DecimalType("-2322243636186679031"), ValueType.INT64,
                        shortArrayToRegisterArray(0xDFC5, 0xBBB7, 0x772E, 0x7909), 0 },
                // would read over the registers

                //
                // UINT64
                //
                new Object[] { new DecimalType("1.0"), ValueType.UINT64, shortArrayToRegisterArray(0, 0, 0, 1), 0 },
                new Object[] { new DecimalType("2.0"), ValueType.UINT64, shortArrayToRegisterArray(0, 0, 0, 2), 0 },
                new Object[] { new DecimalType("18446744073709550612"), ValueType.UINT64,
                        shortArrayToRegisterArray(0xFFFF, 0xFFFF, 0xFFFF, 0xFC14), 0 },
                new Object[] { new DecimalType("64000"), ValueType.UINT64, shortArrayToRegisterArray(0, 0, 0, 64000),
                        0 },
                new Object[] {
                        // out of bounds of unsigned 32bit
                        new DecimalType("34359738368"), ValueType.UINT64, shortArrayToRegisterArray(0x0, 0x8, 0x0, 0x0),
                        0 },
                new Object[] { new DecimalType("16124500437522872585"), ValueType.UINT64,
                        shortArrayToRegisterArray(0xDFC5, 0xBBB7, 0x772E, 0x7909), 0 },

                //
                // INT64_SWAP
                //
                new Object[] { new DecimalType("1.0"), ValueType.INT64_SWAP, shortArrayToRegisterArray(1, 0, 0, 0), 0 },
                new Object[] { new DecimalType("2.0"), ValueType.INT64_SWAP, shortArrayToRegisterArray(2, 0, 0, 0), 0 },
                new Object[] { new DecimalType("-1004"), ValueType.INT64_SWAP,
                        shortArrayToRegisterArray(0xFC14, 0xFFFF, 0xFFFF, 0xFFFF), 0 },
                new Object[] { new DecimalType("64000"), ValueType.INT64_SWAP,
                        shortArrayToRegisterArray(64000, 0, 0, 0), 0 },
                new Object[] {
                        // out of bounds of unsigned 32bit
                        new DecimalType("34359738368"),
                        // 70004 -> 0x00011174 (32bit) -> 0x1174 (16bit)
                        ValueType.INT64_SWAP, shortArrayToRegisterArray(0x0, 0x0, 0x8, 0x0), 0 },
                new Object[] { new DecimalType("-2322243636186679031"), ValueType.INT64_SWAP,

                        shortArrayToRegisterArray(0x7909, 0x772E, 0xBBB7, 0xDFC5), 0 },

                //
                // UINT64_SWAP
                //
                new Object[] { new DecimalType("1.0"), ValueType.UINT64_SWAP, shortArrayToRegisterArray(1, 0, 0, 0),
                        0 },
                new Object[] { new DecimalType("2.0"), ValueType.UINT64_SWAP, shortArrayToRegisterArray(2, 0, 0, 0),
                        0 },
                new Object[] { new DecimalType("18446744073709550612"), ValueType.UINT64_SWAP,
                        shortArrayToRegisterArray(0xFC14, 0xFFFF, 0xFFFF, 0xFFFF), 0 },
                new Object[] { new DecimalType("64000"), ValueType.UINT64_SWAP,
                        shortArrayToRegisterArray(64000, 0, 0, 0), 0 },
                new Object[] {
                        // out of bounds of unsigned 32bit
                        new DecimalType("34359738368"), ValueType.UINT64_SWAP,
                        shortArrayToRegisterArray(0x0, 0x0, 0x8, 0x0), 0 },
                new Object[] {
                        // out of bounds of unsigned 64bit
                        new DecimalType("16124500437522872585"), ValueType.UINT64_SWAP,
                        shortArrayToRegisterArray(0x7909, 0x772E, 0xBBB7, 0xDFC5), 0 })
                .collect(Collectors.toList()));
    }

    @ParameterizedTest
    @MethodSource("provideArgsForReadIntoNumberFromRegistersTest")
    public void testReadIntoNumberFromRegisters(State expectedState, ValueType type, ModbusRegisterArray registers,
            int index) {

        String address = calculateRelativeAdress(index, type);
        doTestReadIntoNumberFromRegisters(expectedState, type, registers, address, 0);
    }

    public void doTestReadIntoNumberFromRegisters(State expectedState, ValueType valueType,
            ModbusRegisterArray registers, String address, int pollerStart) {
        ModbusReadRequestBlueprint requestMock = Mockito.mock(ModbusReadRequestBlueprint.class);

        ReadChannelConfiguration config = new ReadChannelConfiguration();
        config.address = address;
        config.valueType = valueType.getConfigValue();
        AtomicReference<State> updatedState = new AtomicReference<>();
        Consumer<State> stateUpdater = state -> updatedState.set(state);
        ReadIntoNumberChannelHandler handler = new ReadIntoNumberChannelHandler(pollerStart, config, stateUpdater);

        handler.handle(new AsyncModbusReadResult(requestMock, registers));
        assertEquals(expectedState, updatedState.get());
    }

    // TODO: testReadIntoNumberFromBits
    // TODO: testReadIntoNumberFromError

    public static Collection<Object[]> provideArgsForExtractIndexFromRelativeTest() {
        return Collections.unmodifiableList(Stream.of(//
                // Args: expected, valueType, decodeStartIndexRelative, decodeStartSubIndex
                //@formatter:off

                new Object[] { 0, ValueType.BIT, 0, OptionalInt.of(0) }, //
                new Object[] { 1, ValueType.BIT, 0, OptionalInt.of(1) },//
                new Object[] { 15, ValueType.BIT, 0, OptionalInt.of(15) },//
                new Object[] { 16, ValueType.BIT, 1, OptionalInt.of(0) },//

                new Object[] { 0, ValueType.INT8, 0, OptionalInt.of(0) }, //
                new Object[] { 1, ValueType.INT8, 0, OptionalInt.of(1) },//
                new Object[] { 2, ValueType.INT8, 1, OptionalInt.of(0) },//
                new Object[] { 3, ValueType.INT8, 1, OptionalInt.of(1) },//
                new Object[] { 0, ValueType.UINT8, 0, OptionalInt.of(0) }, //
                new Object[] { 1, ValueType.UINT8, 0, OptionalInt.of(1) },//
                new Object[] { 2, ValueType.UINT8, 1, OptionalInt.of(0) },//
                new Object[] { 3, ValueType.UINT8, 1, OptionalInt.of(1) },//

                //
                // Trivial cases follow:
                //
                new Object[] { 0, ValueType.INT16, 0, OptionalInt.empty() }, //
                new Object[] { 1, ValueType.INT16, 1, OptionalInt.empty() },//
                new Object[] { 2, ValueType.INT16, 2,  OptionalInt.empty() },//
                new Object[] { 0, ValueType.UINT16, 0, OptionalInt.empty() }, //
                new Object[] { 1, ValueType.UINT16, 1, OptionalInt.empty() },//
                new Object[] { 2, ValueType.UINT16, 2,  OptionalInt.empty() },//

                new Object[] { 0, ValueType.INT32, 0, OptionalInt.empty() }, //
                new Object[] { 1, ValueType.INT32, 1, OptionalInt.empty() },//
                new Object[] { 2, ValueType.INT32, 2,  OptionalInt.empty() },//
                new Object[] { 0, ValueType.UINT32, 0, OptionalInt.empty() }, //
                new Object[] { 1, ValueType.UINT32, 1, OptionalInt.empty() },//
                new Object[] { 2, ValueType.UINT32, 2,  OptionalInt.empty() },//
                new Object[] { 0, ValueType.INT32_SWAP, 0, OptionalInt.empty() }, //
                new Object[] { 1, ValueType.INT32_SWAP, 1, OptionalInt.empty() },//
                new Object[] { 2, ValueType.INT32_SWAP, 2,  OptionalInt.empty() },//
                new Object[] { 0, ValueType.UINT32_SWAP, 0, OptionalInt.empty() }, //
                new Object[] { 1, ValueType.UINT32_SWAP, 1, OptionalInt.empty() },//
                new Object[] { 2, ValueType.UINT32_SWAP, 2,  OptionalInt.empty() },//

                new Object[] { 0, ValueType.FLOAT32, 0, OptionalInt.empty() }, //
                new Object[] { 1, ValueType.FLOAT32, 1, OptionalInt.empty() },//
                new Object[] { 2, ValueType.FLOAT32, 2,  OptionalInt.empty() },//
                new Object[] { 0, ValueType.FLOAT32_SWAP, 0, OptionalInt.empty() }, //
                new Object[] { 1, ValueType.FLOAT32_SWAP, 1, OptionalInt.empty() },//
                new Object[] { 2, ValueType.FLOAT32_SWAP, 2,  OptionalInt.empty() },//

                new Object[] { 0, ValueType.INT64, 0, OptionalInt.empty() }, //
                new Object[] { 1, ValueType.INT64, 1, OptionalInt.empty() },//
                new Object[] { 2, ValueType.INT64, 2,  OptionalInt.empty() },//
                new Object[] { 0, ValueType.UINT64, 0, OptionalInt.empty() }, //
                new Object[] { 1, ValueType.UINT64, 1, OptionalInt.empty() },//
                new Object[] { 2, ValueType.UINT64, 2,  OptionalInt.empty() },//
                new Object[] { 0, ValueType.INT64_SWAP, 0, OptionalInt.empty() }, //
                new Object[] { 1, ValueType.INT64_SWAP, 1, OptionalInt.empty() },//
                new Object[] { 2, ValueType.INT64_SWAP, 2,  OptionalInt.empty() },//
                new Object[] { 0, ValueType.UINT64_SWAP, 0, OptionalInt.empty() }, //
                new Object[] { 1, ValueType.UINT64_SWAP, 1, OptionalInt.empty() },//
                new Object[] { 2, ValueType.UINT64_SWAP, 2,  OptionalInt.empty() }//
              //@formatter:on
        ).toList());

    }

    @ParameterizedTest
    @MethodSource("provideArgsForExtractIndexFromRelativeTest")
    public void testExtractIndexFromRelative(int expected, ValueType valueType, int decodeStartIndexRelative,
            OptionalInt decodeStartSubIndex) {
        int actualElementIndex = ReadIntoNumberChannelHandler.extractIndexFromRelative(valueType,
                decodeStartIndexRelative, decodeStartSubIndex);
        assertEquals(expected, actualElementIndex);
    }

    /**
     * Test the utility function used in tests
     */
    @ParameterizedTest
    @MethodSource("provideArgsForExtractIndexFromRelativeTest")
    public void testCalculateRelativeAddress(int extractIndex, ValueType valueType,
            int expectedDecodeStartIndexRelative, OptionalInt expectedDecodeStartSubIndex) {
        String address = calculateRelativeAdress(extractIndex, valueType);
        @NonNull
        String[] parts = address.split("\\.");

        assertEquals(expectedDecodeStartSubIndex.isPresent() ? 2 : 1, parts.length);
        int actualDecodeStartIndexRelative = Integer.parseInt(parts[0]);
        assertEquals(expectedDecodeStartIndexRelative, actualDecodeStartIndexRelative);
        if (expectedDecodeStartSubIndex.isPresent()) {
            int actualDecodeStartSubIndex = Integer.parseInt(parts[1]);
            assertEquals(expectedDecodeStartSubIndex.getAsInt(), actualDecodeStartSubIndex);
        }
    }

}
