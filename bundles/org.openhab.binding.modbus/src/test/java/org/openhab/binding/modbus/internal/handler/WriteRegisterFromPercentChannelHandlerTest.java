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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openhab.binding.modbus.config.WriteChannelConfiguration;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.types.Command;;

/**
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class WriteRegisterFromPercentChannelHandlerTest {

    private static int SLAVE_ID = 6;

    public static Collection<Object[]> provideArgsForPostProcessTest() {
        return Collections.unmodifiableList(Stream.of(
        //@formatter:off
                                new Object[] { new BigDecimal("500"),  new PercentType(50),      BigDecimal.valueOf(0),    BigDecimal.valueOf(1000) },
                                new Object[] { new BigDecimal("0"),    new PercentType(50),      BigDecimal.valueOf(-100), BigDecimal.valueOf(100) },
                                new Object[] { new BigDecimal("50"),    new PercentType(75),      BigDecimal.valueOf(-100), BigDecimal.valueOf(100) },
                                new Object[] { new BigDecimal("50.50"), new PercentType("5.05"),  BigDecimal.valueOf(0),    BigDecimal.valueOf(1000) },
                                // min/max flipped (the larger the decoded number, the smaller PercentType)
                                new Object[] { new BigDecimal("50.50"), new PercentType("94.95"), BigDecimal.valueOf(1000), BigDecimal.valueOf(0) },
                                // at upper limit
                                new Object[] { new BigDecimal("100"),  new PercentType(100),     BigDecimal.valueOf(-100), BigDecimal.valueOf(100) },
                                // at lower limit
                                new Object[] { new BigDecimal("-100"), new PercentType(0),       BigDecimal.valueOf(-100), BigDecimal.valueOf(100) },

                                // DecimalType command is not processed, only PercentType comamnds
                                new Object[] { null,                        DecimalType.ZERO,         BigDecimal.valueOf(-100), BigDecimal.valueOf(100) },
                                // Same goes for OFF
                                new Object[] { null,                        OnOffType.OFF,            BigDecimal.valueOf(-100), BigDecimal.valueOf(100) }
                        //@formatter:on
        ).collect(Collectors.toList()));
    }

    /**
     * Unit test Percent write channel
     *
     * Here we test post-processing step of the channel, starting from 'decoded number'
     */
    @ParameterizedTest
    @MethodSource("provideArgsForPostProcessTest")
    public void testWriteWholeRegisterFromPercentPreProcess(BigDecimal expectedPreprocessedNumber, Command command,
            BigDecimal p0Value, BigDecimal p100Value) {
        WriteChannelConfiguration config = new WriteChannelConfiguration();
        config.address = "0"; // not used in test
        // value type relevant in this test, we test the number that will be encoded
        config.valueType = ValueType.INT16.getConfigValue();
        config.p0Value = p0Value;
        config.p100Value = p100Value;
        WriteRegisterFromPercentHandler handler = new WriteRegisterFromPercentHandler(SLAVE_ID, config, null, r -> {
        });

        assertEquals(Optional.ofNullable(expectedPreprocessedNumber), handler.preProcessCommand(command));
    }

}
