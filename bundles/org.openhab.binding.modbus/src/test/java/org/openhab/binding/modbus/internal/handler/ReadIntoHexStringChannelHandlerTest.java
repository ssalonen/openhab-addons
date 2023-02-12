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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.openhab.binding.modbus.config.ReadChannelConfiguration;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.BitArray;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;;

/**
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ReadIntoHexStringChannelHandlerTest {

    private final static int POLL_START = 10;
    private ModbusReadRequestBlueprint request = Mockito.mock(ModbusReadRequestBlueprint.class);

    private final static ModbusRegisterArray REGISTERS = new ModbusRegisterArray(0xAE41, 0x5652, 0x4340);
    private final static BitArray BITS = new BitArray(//
            // byte pattern from https://simplymodbus.ca/FC02.htm
            false, false, true, true, false, true, false, true, // 00110101
            true, true, false, true, true, false, true, true // 11011011
    );

    @BeforeAll
    public static void setUp() {
        // Invariant
        assertEquals(3, REGISTERS.size());
    }

    public static Collection<Object[]> provideArgsForRegisterTest() {
        return Collections.unmodifiableList(Stream.of(
        //@formatter:off
                                new Object[] { new StringType("AE41"), "10", 1},
                                new Object[] { new StringType("AE415652"), "10", 2},
                                new Object[] { new StringType("5652"), "11", 1},
                                new Object[] { new StringType("56524340"), "11", 2},
                                new Object[] { new StringType("AE4156524340"), "10", 3}

                        //@formatter:on
        ).collect(Collectors.toList()));
    }

    public static Collection<Object[]> provideArgsForBitsTest() {
        return Collections.unmodifiableList(Stream.of(
        //@formatter:off
                                new Object[] { new StringType("00"), "10", 1}, // one false bit
                                new Object[] { new StringType("00"), "10", 2}, // two false bits
                                new Object[] { new StringType("04"), "10", 3}, // 001 bits -->     encoded as 0b00000100 = 0x04
                                new Object[] { new StringType("0C"), "10", 4}, // 0011 bits -->    encoded as 0b00001100 = 0x0C
                                new Object[] { new StringType("AC"), "10", 8}, // 00110101 bits -> encoded as 0b10101100 = 0xAC

                                new Object[] { new StringType("AC01"), "10", 9}, // 00110101 1
                                new Object[] { new StringType("D6"), "11", 8}, //    0110101 1 encoded as 0b11010110 = 0xD6

                                new Object[] { new StringType("AC03"), "10", 10}, // 00110101 11
                                new Object[] { new StringType("01D6"), "11", 9}, //   0110101 11 encoded as 111010110 = 0x01D6

                                new Object[] { new StringType("AC03"), "10", 11}, // 00110101 110 -> encoded same as "00110101 11"
                                new Object[] { new StringType("AC0B"), "10", 12}, // 00110101 1101. 1101 would be encoded as 0b1011 = 0x0B
                                new Object[] { new StringType("ACDB"), "10", 16}
                        //@formatter:on
        ).collect(Collectors.toList()));
    }

    @ParameterizedTest
    @MethodSource("provideArgsForRegisterTest")
    public void testReadIntoHexStringFromRegisters(State expectedState, String address, int length) {
        ReadChannelConfiguration config = new ReadChannelConfiguration();
        config.address = address;
        config.length = length;

        List<State> stateUpdates = new ArrayList<>();
        ReadIntoHexStringChannelHandler handler = new ReadIntoHexStringChannelHandler(POLL_START, config,
                state -> stateUpdates.add(state));
        handler.handle(new AsyncModbusReadResult(request, REGISTERS));

        assertEquals(1, stateUpdates.size());
        assertEquals(expectedState, stateUpdates.get(0));
    }

    @ParameterizedTest
    @MethodSource("provideArgsForBitsTest")
    public void testReadIntoHexStringFromBits(State expectedState, String address, int length) {
        ReadChannelConfiguration config = new ReadChannelConfiguration();
        config.address = address;
        config.length = length;

        List<State> stateUpdates = new ArrayList<>();
        ReadIntoHexStringChannelHandler handler = new ReadIntoHexStringChannelHandler(POLL_START, config,
                state -> stateUpdates.add(state));
        handler.handle(new AsyncModbusReadResult(request, BITS));

        assertEquals(1, stateUpdates.size());
        assertEquals(expectedState, stateUpdates.get(0));
    }

}
