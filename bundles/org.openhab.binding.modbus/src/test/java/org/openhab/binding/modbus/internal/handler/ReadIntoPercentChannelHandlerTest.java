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
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openhab.binding.modbus.config.ReadChannelConfiguration;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;;

/**
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ReadIntoPercentChannelHandlerTest {

    public static Collection<Object[]> provideArgsForPostProcessTest() {
        return Collections.unmodifiableList(Stream.of(
        //@formatter:off
                                new Object[] { new PercentType(50), DecimalType.valueOf("500"), BigDecimal.valueOf(0), BigDecimal.valueOf(1000)},
                                new Object[] { new PercentType(50), DecimalType.valueOf("0"), BigDecimal.valueOf(-100), BigDecimal.valueOf(100)},
                                new Object[] { new PercentType(75), DecimalType.valueOf("50"), BigDecimal.valueOf(-100), BigDecimal.valueOf(100)},
                                new Object[] { new PercentType("5.05"), DecimalType.valueOf("50.5"), BigDecimal.valueOf(0), BigDecimal.valueOf(1000)},
                                // at upper limit
                                new Object[] { new PercentType(100), DecimalType.valueOf("100"), BigDecimal.valueOf(-100), BigDecimal.valueOf(100)},
                                // out-of-bounds, clipped to max
                                new Object[] { new PercentType(100), DecimalType.valueOf("150"), BigDecimal.valueOf(-100), BigDecimal.valueOf(100)},
                                // out-of-bounds, clipped to min
                                new Object[] { new PercentType(0), DecimalType.valueOf("-110"), BigDecimal.valueOf(-100), BigDecimal.valueOf(100)},
                                // at lower limit
                                new Object[] { new PercentType(0), DecimalType.valueOf("-100"), BigDecimal.valueOf(-100), BigDecimal.valueOf(100)},

                                // We decode into UNDEF number when we have float NaN or float inf. We return UNDEF.
                                new Object[] { UnDefType.UNDEF, UnDefType.UNDEF, BigDecimal.valueOf(0), BigDecimal.valueOf(1000)},
                                new Object[] { UnDefType.UNDEF, UnDefType.UNDEF, BigDecimal.valueOf(0), BigDecimal.valueOf(1000)}
                        //@formatter:on
        ).collect(Collectors.toList()));
    }

    /**
     * Unit test OpenClosed channel
     *
     * Here we test post-processing step of the channel, starting from 'decoded number'
     */
    @ParameterizedTest
    @MethodSource("provideArgsForPostProcessTest")
    public void testReadIntoPercentPostProcessWithDecodedNumber(State expectedState, State decodedNumberState,
            BigDecimal minValue, BigDecimal maxValue) {
        ReadChannelConfiguration config = new ReadChannelConfiguration();
        config.address = "0"; // not used in test
        config.valueType = ValueType.BIT.getConfigValue(); // not used in test, we unit test with decoded numbers
        config.minValue = minValue;
        config.maxValue = maxValue;
        Consumer<State> stateUpdater = state -> {
        };
        ReadIntoPercentChannelHandler handler = new ReadIntoPercentChannelHandler(0 /* not used in test */, config,
                stateUpdater);

        assertEquals(expectedState, handler.postProcessNumberState(decodedNumberState));
    }

}
