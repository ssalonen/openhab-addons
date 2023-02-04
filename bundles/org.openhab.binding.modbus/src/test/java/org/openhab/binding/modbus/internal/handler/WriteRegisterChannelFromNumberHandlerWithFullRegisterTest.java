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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.function.Consumer;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openhab.binding.modbus.config.WriteChannelConfiguration;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.io.transport.modbus.ModbusWriteCoilRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteFunctionCode;
import org.openhab.core.io.transport.modbus.ModbusWriteRegisterRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprintVisitor;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;

/**
 * @author Sami Salonen - Initial contribution
 */
public class WriteRegisterChannelFromNumberHandlerWithFullRegisterTest {
    private static int SLAVE_ID = 6;
    private static int REGISTER_INDEX = 9;

    @ParameterizedTest
    @MethodSource("org.openhab.binding.modbus.internal.handler.ReadIntoNumberChannelHandlerTest#provideArgsForReadIntoNumberFromRegistersTest")
    public void testReadIntoNumberFromRegistersWithWriteMultiple(State state, ValueType valueType,
            ModbusRegisterArray expectedRegisters, int index) {
        assumeTrue(valueType.getBits() >= 16); // only full register writes
        assumeTrue(state instanceof Command); // Filter out tests not suitable for write testing

        ModbusRegisterArray relevantExpectedRegisters = relevantRegisters(valueType, expectedRegisters, index);
        doTestReadIntoNumberFromRegisters(relevantExpectedRegisters, (Command) state, valueType, true);
    }

    @ParameterizedTest
    @MethodSource("org.openhab.binding.modbus.internal.handler.ReadIntoNumberChannelHandlerTest#provideArgsForReadIntoNumberFromRegistersTest")
    public void testReadIntoNumberFromRegistersWithoutWriteMultiple(State state, ValueType valueType,
            ModbusRegisterArray expectedRegisters, int readIndex) {
        assumeTrue(valueType.getBits() >= 16); // only full register writes
        assumeTrue(state instanceof Command); // Filter out tests not suitable for write testing

        ModbusRegisterArray relevantExpectedRegisters = relevantRegisters(valueType, expectedRegisters, readIndex);
        doTestReadIntoNumberFromRegisters(relevantExpectedRegisters, (Command) state, valueType, false);
    }

    /**
     * In the read test, there are some extra registers, for writing assertion we can extract the relevant registers
     */
    public ModbusRegisterArray relevantRegisters(ValueType valueType, ModbusRegisterArray expectedRegisters,
            int index) {
        if (valueType.getBits() == 64) {
            // Take 4 x 16bit registers
            return new ModbusRegisterArray(expectedRegisters.getRegister(index),
                    expectedRegisters.getRegister(index + 1), expectedRegisters.getRegister(index + 2),
                    expectedRegisters.getRegister(index + 3));
        } else if (valueType.getBits() == 32) {
            // Take 2 x 16bit registers
            return new ModbusRegisterArray(expectedRegisters.getRegister(index),
                    expectedRegisters.getRegister(index + 1));
        } else if (valueType.getBits() == 16) {
            return new ModbusRegisterArray(expectedRegisters.getRegister(index));
        } else {
            fail("Bug in test: " + valueType.getBits());
            throw new AssertionError();
        }
    }

    public void doTestReadIntoNumberFromRegisters(ModbusRegisterArray expectedRegisters, Command commandFromItem,
            ValueType valueType, boolean writeMultiple) {
        WriteChannelConfiguration config = new WriteChannelConfiguration();
        config.address = String.valueOf(REGISTER_INDEX);
        config.writeMultiple = writeMultiple;
        config.writeMaxTries = 3;
        config.valueType = valueType.getConfigValue();

        final ModbusWriteRegisterRequestBlueprint[] actualRequest = new ModbusWriteRegisterRequestBlueprint[1];
        final WriteRegisterChannelHandler handler;

        Consumer<ModbusWriteRequestBlueprint> writer = writeRequest -> {
            writeRequest.accept(new ModbusWriteRequestBlueprintVisitor() {

                @Override
                public void visit(ModbusWriteRegisterRequestBlueprint request) {
                    actualRequest[0] = request;
                }

                @Override
                public void visit(ModbusWriteCoilRequestBlueprint request) {
                    fail("Expected holding register write, not coil write");
                }
            });
        };

        handler = new WriteRegisterFromNumberHandler(SLAVE_ID, config,
                null /* No need to provide register cache with full register writes */, writer);
        handler.processCommand(commandFromItem);
        assertNotNull(actualRequest[0]);
        assertEquals(SLAVE_ID, actualRequest[0].getUnitID());
        assertEquals(config.writeMaxTries, actualRequest[0].getMaxTries());
        if (!writeMultiple && expectedRegisters.size() == 1) {
            assertEquals(ModbusWriteFunctionCode.WRITE_SINGLE_REGISTER, actualRequest[0].getFunctionCode());
        } else {
            assertEquals(ModbusWriteFunctionCode.WRITE_MULTIPLE_REGISTERS, actualRequest[0].getFunctionCode());
        }

        ModbusRegisterArray actualRegisters = actualRequest[0].getRegisters();
        assertEquals(expectedRegisters, actualRegisters);
    }
}
