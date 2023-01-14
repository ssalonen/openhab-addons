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
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.types.State;;

/**
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ReadIntoOnOffChannelHandlerTest {

    public static Collection<Object[]> provideArgsForPostProcessTest() {
        return Collections.unmodifiableList(Stream.of(
        //@formatter:off
                                new Object[] { OnOffType.OFF, DecimalType.ZERO, BigDecimal.valueOf(0), false},
                                new Object[] { OnOffType.ON, DecimalType.ZERO, BigDecimal.valueOf(0), true},
                                new Object[] { OnOffType.ON, DecimalType.valueOf("3.14"), BigDecimal.valueOf(0), false},
                                new Object[] { OnOffType.OFF, DecimalType.valueOf("3.14"), BigDecimal.valueOf(0), true},
                                new Object[] { OnOffType.OFF, DecimalType.valueOf("3.14"), new BigDecimal("3.14"), false},
                                new Object[] { OnOffType.ON, DecimalType.valueOf("3.14"),  new BigDecimal("3.14"), true},
                                new Object[] { OnOffType.OFF, DecimalType.valueOf("-3.14"), new BigDecimal("-3.14"), false},
                                new Object[] { OnOffType.ON, DecimalType.valueOf("-3.14"),  new BigDecimal("-3.14"), true},

                                new Object[] { OnOffType.OFF, DecimalType.valueOf("255"), BigDecimal.valueOf(255), false},
                                new Object[] { OnOffType.ON, DecimalType.valueOf("255"), BigDecimal.valueOf(255), true},
                                new Object[] { OnOffType.ON, DecimalType.valueOf("0"), BigDecimal.valueOf(255), false},
                                new Object[] { OnOffType.OFF, DecimalType.valueOf("0"), BigDecimal.valueOf(255), true}

                        //@formatter:on
        ).collect(Collectors.toList()));
    }

    /**
     * Unit test OnfOff channel
     *
     * Here we test post-processing step of the channel, starting from 'decoded number'
     */
    @ParameterizedTest
    @MethodSource("provideArgsForPostProcessTest")
    public void testReadIntoOnOffPostProcessWithDecodedNumber(State expectedState, State decodedNumberState,
            BigDecimal offValue, boolean inverted) {
        ReadChannelConfiguration config = new ReadChannelConfiguration();
        config.address = "0"; // not used in test
        config.valueType = ValueType.BIT.getConfigValue(); // not used in test, we unit test with decoded numbers
        config.offValue = offValue;
        config.inverted = inverted;
        Consumer<State> stateUpdater = state -> {
        };
        ReadIntoOnOffChannelHandler handler = new ReadIntoOnOffChannelHandler(0 /* not used in test */, config,
                stateUpdater);

        assertEquals(expectedState, handler.postProcessNumberState(decodedNumberState));
    }

}
