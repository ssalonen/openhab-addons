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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openhab.binding.modbus.config.WriteChannelConfiguration;
import org.openhab.core.io.transport.modbus.ModbusWriteCoilRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteFunctionCode;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.types.Command;;

/**
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class WriteCoilFromOnOffChannelHandlerTest {

    private static int SLAVE_ID = 6;

    public static Collection<Object[]> provideArgsForPreProcessTest() {
        return Collections.unmodifiableList(Stream.of(
//        @formatter:off
                                new Object[] { BigDecimal.ZERO,          false, OnOffType.OFF},
                                new Object[] { BigDecimal.valueOf(1),    true, OnOffType.OFF},
                                new Object[] { BigDecimal.valueOf(1),    false, OnOffType.ON},
                                new Object[] { BigDecimal.ZERO,          true, OnOffType.ON},

                                // DecimalType command is not processed, only OnOff comamnds
                                new Object[] { null,  false, DecimalType.ZERO, BigDecimal.valueOf(0),   BigDecimal.valueOf(1) }

                        //@formatter:on
        ).collect(Collectors.toList()));
    }

    /**
     * Unit test OnfOff channel
     *
     * Here we test pre-processing step of the channel, starting from command ending up number.
     * The actual handler will then turnn the number to on/off coil bit status
     */
    @ParameterizedTest
    @MethodSource("provideArgsForPreProcessTest")
    public void testWriteCoilFromOnOffPreProcess(@Nullable BigDecimal expectedPreprocessedNumber, boolean inverted,
            Command command) {
        WriteChannelConfiguration config = new WriteChannelConfiguration();
        config.address = "0"; // not used in test
        config.inverted = inverted;
        WriteCoilFromOnOffHandler handler = new WriteCoilFromOnOffHandler(SLAVE_ID, config, r -> {
        });

        assertEquals(Optional.ofNullable(expectedPreprocessedNumber), handler.preProcessCommand(command));
    }

    @Test
    public void testEndToEnd() {
        WriteChannelConfiguration config = new WriteChannelConfiguration();
        config.address = "12"; // not used in test

        List<ModbusWriteRequestBlueprint> requests = new ArrayList<>();
        WriteCoilFromOnOffHandler handler = new WriteCoilFromOnOffHandler(SLAVE_ID, config, r -> {
            requests.add(r);
        });

        assertTrue(requests.isEmpty());
        handler.processCommand(OnOffType.ON);
        assertEquals(1, requests.size());
        {
            ModbusWriteRequestBlueprint request = requests.get(0);
            assertEquals(ModbusWriteFunctionCode.WRITE_COIL, request.getFunctionCode());
            assertEquals(config.writeMaxTries, request.getMaxTries());
            assertEquals(SLAVE_ID, request.getUnitID());
            assertEquals(12, request.getReference());
            assertInstanceOf(ModbusWriteCoilRequestBlueprint.class, request);
            ModbusWriteCoilRequestBlueprint coilRequest = (ModbusWriteCoilRequestBlueprint) request;
            assertEquals(1, coilRequest.getCoils().size());
            assertEquals(true, coilRequest.getCoils().getBit(0));
        }

        handler.processCommand(OnOffType.OFF);
        assertEquals(2, requests.size());
        {
            ModbusWriteRequestBlueprint request = requests.get(1);
            assertEquals(ModbusWriteFunctionCode.WRITE_COIL, request.getFunctionCode());
            assertEquals(config.writeMaxTries, request.getMaxTries());
            assertEquals(SLAVE_ID, request.getUnitID());
            assertEquals(12, request.getReference());
            assertInstanceOf(ModbusWriteCoilRequestBlueprint.class, request);
            ModbusWriteCoilRequestBlueprint coilRequest = (ModbusWriteCoilRequestBlueprint) request;
            assertEquals(1, coilRequest.getCoils().size());
            assertEquals(false, coilRequest.getCoils().getBit(0));
        }

        handler.processCommand(OpenClosedType.CLOSED);
        // command was ignored, no new write requests
        assertEquals(2, requests.size());

    }

}
