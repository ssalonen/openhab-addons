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

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openhab.binding.modbus.config.WriteChannelConfiguration;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.types.Command;;

/**
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class WriteRegisterFromOnOffChannelHandlerTest {

    private static int SLAVE_ID = 6;

    public static Collection<Object[]> provideArgsForPreProcessTest() {
        return Collections.unmodifiableList(Stream.of(
//        @formatter:off
                                new Object[] { BigDecimal.ZERO,          OnOffType.OFF, BigDecimal.valueOf(0),   BigDecimal.valueOf(1) },
                                new Object[] { BigDecimal.valueOf(1),    OnOffType.ON,  BigDecimal.valueOf(0),   BigDecimal.valueOf(1) },
                                new Object[] { new BigDecimal("3.14"),   OnOffType.ON,  new BigDecimal("-3.14"),   new BigDecimal("3.14") },
                                new Object[] { new BigDecimal("-3.14"),  OnOffType.OFF, new BigDecimal("-3.14"),   new BigDecimal("3.14") },

                                new Object[] { BigDecimal.valueOf(1337), OnOffType.OFF, new BigDecimal("1337"),   new BigDecimal("1337") },
                                new Object[] { BigDecimal.valueOf(1337), OnOffType.OFF, new BigDecimal("1337"),   new BigDecimal("1337") },

                                // DecimalType command is not processed, only OnOff comamnds
                                new Object[] { null,  DecimalType.ZERO, BigDecimal.valueOf(0),   BigDecimal.valueOf(1) }

                        //@formatter:on
        ).collect(Collectors.toList()));
    }

    /**
     * Unit test OnfOff channel
     *
     * Here we test pre-processing step of the channel, starting from command ending up number.
     * The actual handler will encode the number using value type.
     */
    @ParameterizedTest
    @MethodSource("provideArgsForPreProcessTest")
    public void testWriteWholeRegisterFromOnOffPreProcess(@Nullable BigDecimal expectedPreprocessedNumber,
            Command command, BigDecimal offValue, BigDecimal onValue) {
        WriteChannelConfiguration config = new WriteChannelConfiguration();
        config.address = "0"; // not used in test
        // value type relevant in this test, we test the number that will be encoded
        config.valueType = ValueType.INT16.getConfigValue();
        config.offValue = offValue;
        config.onValue = onValue;
        WriteRegisterFromOnOffHandler handler = new WriteRegisterFromOnOffHandler(SLAVE_ID, config, null, r -> {
        });

        assertEquals(Optional.ofNullable(expectedPreprocessedNumber), handler.preProcessCommand(command));
    }

}
