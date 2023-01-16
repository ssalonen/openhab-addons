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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openhab.binding.modbus.config.WriteChannelConfiguration;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.io.transport.modbus.ModbusWriteCoilRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteFunctionCode;
import org.openhab.core.io.transport.modbus.ModbusWriteRegisterRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprintVisitor;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.types.Command;

/**
 * @author Sami Salonen - Initial contribution
 */
public class WriteRegisterChannelHandlerWithBitOfRegisterTest {
    private static int SLAVE_ID = 6;
    private static int REGISTER_INDEX = 9;

    private static Stream<Arguments> provideArgsForUpdateThenCommandFromItem() {
        return Stream.of(//
//              @formatter:off
                // ON/OFF commands
                Arguments.of(WriteRegisterFromOnOff.class, (short) 0b1011_0100_0000_1111, "1", (short) 0b1011_0100_0000_1101, OnOffType.OFF),
                Arguments.of(WriteRegisterFromOnOff.class, (short) 0b1011_0100_0000_1111, "1", (short) 0b1011_0100_0000_1111, OnOffType.ON), // no-change
                Arguments.of(WriteRegisterFromOnOff.class, (short) 0b1011_0100_0000_1111, "4", (short) 0b1011_0100_0001_1111, OnOffType.ON),
                Arguments.of(WriteRegisterFromOnOff.class, (short) 0b1011_0100_0000_1111, "4", (short) 0b1011_0100_0000_1111, OnOffType.OFF), // no-change
                // OPEN/CLOSED commands
                Arguments.of(WriteRegisterFromOpenClosed.class, (short) 0b1011_0100_0000_1111, "1", (short) 0b1011_0100_0000_1101, OpenClosedType.CLOSED),
                Arguments.of(WriteRegisterFromOpenClosed.class, (short) 0b1011_0100_0000_1111, "1", (short) 0b1011_0100_0000_1111, OpenClosedType.OPEN), // no change
                Arguments.of(WriteRegisterFromOpenClosed.class, (short) 0b1011_0100_0000_1111, "4", (short) 0b1011_0100_0001_1111, OpenClosedType.OPEN),
                Arguments.of(WriteRegisterFromOpenClosed.class, (short) 0b1011_0100_0000_1111, "4", (short) 0b1011_0100_0000_1111, OpenClosedType.CLOSED), // no change
                // DecimalType commands
                Arguments.of(WriteRegisterFromNumber.class, (short) 0b1011_0100_0000_1111, "1", (short) 0b1011_0100_0000_1101, new DecimalType(0)),
                Arguments.of(WriteRegisterFromNumber.class, (short) 0b1011_0100_0000_1111, "1", (short) 0b1011_0100_0000_1111, new DecimalType(5)), // no change
                Arguments.of(WriteRegisterFromNumber.class, (short) 0b1011_0100_0010_1111, "5", (short) 0b1011_0100_0000_1111, new DecimalType(0)),
                Arguments.of(WriteRegisterFromNumber.class, (short) 0b1011_0100_0010_1111, "5", (short) 0b1011_0100_0010_1111, new DecimalType(5)), // no change
                Arguments.of(WriteRegisterFromNumber.class, (short) 0b1011_0100_0000_1111, "4", (short) 0b1011_0100_0001_1111, new DecimalType(5)),
                Arguments.of(WriteRegisterFromNumber.class, (short) 0b1011_0100_0000_1111, "15", (short) 0b0011_0100_0000_1111, new DecimalType(0))
//              @formatter:on
        );
    }

    @ParameterizedTest
    @MethodSource("provideArgsForUpdateThenCommandFromItem")
    public void testUpdateFromHandlerThenCommandFromItem(Class<? extends WriteRegisterChannelHandler> handlerClass,
            short stateUpdateFromHandler, String bitIndex, short expectedWriteDataToSlave, Command commandFromItem) {

        WriteChannelConfiguration config = new WriteChannelConfiguration();
        config.address = REGISTER_INDEX + "." + bitIndex;
        config.writeMaxTries = 3;
        config.valueType = ValueType.BIT.getConfigValue();
        ModbusRegisterArray cachedRegister = new ModbusRegisterArray(stateUpdateFromHandler);

        final ModbusWriteRegisterRequestBlueprint[] actualRequest = new ModbusWriteRegisterRequestBlueprint[1];
        final WriteRegisterChannelHandler handler;
        try {
            Constructor<? extends WriteRegisterChannelHandler> handlerConstructor = handlerClass
                    .getConstructor(Integer.TYPE, WriteChannelConfiguration.class, RegisterCache.class, Consumer.class);

            RegisterCache registerCache = (start, length) -> {
                assertEquals(REGISTER_INDEX, start);
                assertEquals(1, length);
                return Optional.of(cachedRegister);
            };

            Consumer<ModbusWriteRequestBlueprint> writer = writeRequest -> {
                writeRequest.accept(new ModbusWriteRequestBlueprintVisitor() {

                    @Override
                    public void visit(@NonNull ModbusWriteRegisterRequestBlueprint request) {
                        actualRequest[0] = request;
                    }

                    @Override
                    public void visit(@NonNull ModbusWriteCoilRequestBlueprint request) {
                        fail("Expected holding register write, not coil write");
                    }
                });
            };

            handler = handlerConstructor.newInstance(SLAVE_ID, config, registerCache, writer);
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException
                | NoSuchMethodException | SecurityException e) {
            fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            throw new AssertionError();
        }
        handler.processCommand(commandFromItem);
        assertNotNull(actualRequest[0]);
        assertEquals(SLAVE_ID, actualRequest[0].getUnitID());
        assertEquals(config.writeMaxTries, actualRequest[0].getMaxTries());
        assertEquals(ModbusWriteFunctionCode.WRITE_SINGLE_REGISTER, actualRequest[0].getFunctionCode());

        ModbusRegisterArray expectedRegisters = new ModbusRegisterArray(expectedWriteDataToSlave & 0xffff);
        ModbusRegisterArray actualRegisters = actualRequest[0].getRegisters();
        assertEquals(expectedRegisters, actualRegisters);
    }
}
