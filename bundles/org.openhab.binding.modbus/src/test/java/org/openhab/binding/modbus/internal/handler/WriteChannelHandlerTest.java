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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openhab.core.types.Command;

/**
 * @author Sami Salonen - Initial contribution
 */
public class WriteChannelHandlerTest {
    // private static Stream<Arguments> provideArgsForUpdateThenCommandFromItem()
    //
    // {
    // return Stream.of(//
    // // ON/OFF commands
    // Arguments.of((short) 0b1011_0100_0000_1111, "1", (short) 0b1011_0100_0000_1101, OnOffType.OFF),
    // Arguments.of((short) 0b1011_0100_0000_1111, "4", (short) 0b1011_0100_0001_1111, OnOffType.ON),
    // // OPEN/CLOSED commands
    // Arguments.of((short) 0b1011_0100_0000_1111, "1", (short) 0b1011_0100_0000_1101, OpenClosedType.CLOSED),
    // Arguments.of((short) 0b1011_0100_0000_1111, "4", (short) 0b1011_0100_0001_1111, OpenClosedType.OPEN),
    // // DecimalType commands
    // Arguments.of((short) 0b1011_0100_0000_1111, "1", (short) 0b1011_0100_0000_1101, new DecimalType(0)),
    // Arguments.of((short) 0b1011_0100_0010_1111, "5", (short) 0b1011_0100_0000_1111, new DecimalType(0)),
    // Arguments.of((short) 0b1011_0100_0000_1111, "4", (short) 0b1011_0100_0001_1111, new DecimalType(5)),
    // Arguments.of((short) 0b1011_0100_0000_1111, "15", (short) 0b0011_0100_0000_1111, new DecimalType(0))
    //
    // ).flatMap(a -> {
    // // parametrize by channel (yes, it does not matter what channel is used, commands are interpreted all the
    // // same)
    // Stream<String> channels = Stream.of("switch", "number", "contact");
    // return channels.map(channel -> appendArg(a, channel));
    // });
    // }

    @ParameterizedTest
    @MethodSource("provideArgsForUpdateThenCommandFromItem")
    public void testUpdateFromHandlerThenCommandFromItem(short stateUpdateFromHandler, String bitIndex,
            short expectedWriteDataToSlave, Command commandFromItem, String channel) {

    }
}
