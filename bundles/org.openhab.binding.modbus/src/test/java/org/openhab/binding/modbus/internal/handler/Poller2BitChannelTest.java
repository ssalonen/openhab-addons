/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
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

import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openhab.core.io.transport.modbus.BitArray;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.types.UnDefType;

/**
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class Poller2BitChannelTest {

    static Stream<Arguments> absoluteBitAddresses() {
        return Stream.of(Arguments.of(100, new BitArray(true, false, false, true), OnOffType.ON),
                Arguments.of(103, new BitArray(true, false, false, true), OnOffType.ON),
                Arguments.of(101, new BitArray(true, false, false, true), OnOffType.OFF));
    }

    @ParameterizedTest
    @MethodSource("absoluteBitAddresses")
    public void decodesAbsoluteCoilAndDiscreteAddresses(int address, BitArray bits, Object expectedState) {
        Poller2BitChannel channel = new Poller2BitChannel(address, PollerReadFailurePolicy.UNDEF);

        assertEquals(expectedState, channel.acceptBits(bits, 100));
    }

    static Stream<Arguments> failedResponsePolicies() {
        return Stream.of(PollerReadFailurePolicy.values())
                .flatMap(policy -> Stream.of("Switch", "Contact").flatMap(itemType -> Stream
                        .of(101, 99, Integer.MIN_VALUE).map(pollStart -> Arguments.of(policy, itemType, pollStart))));
    }

    @ParameterizedTest
    @MethodSource("failedResponsePolicies")
    public void appliesFailurePolicyToShortOrOutOfWindowResponses(PollerReadFailurePolicy policy, String itemType,
            int pollStart) {
        Poller2BitChannel channel = new Poller2BitChannel(100, policy, itemType);
        Object successfulState = "Contact".equals(itemType) ? org.openhab.core.library.types.OpenClosedType.OPEN
                : OnOffType.ON;
        Object failedState = policy == PollerReadFailurePolicy.KEEP_LAST ? successfulState : UnDefType.UNDEF;

        assertEquals(UnDefType.UNDEF, channel.acceptBits(new BitArray(false), pollStart));
        assertEquals(successfulState, channel.acceptBits(new BitArray(true), 100));
        assertEquals(failedState, channel.acceptBits(new BitArray(false), pollStart));
        assertEquals(failedState, channel.acceptReadFailure());
        assertEquals("Contact".equals(itemType) ? org.openhab.core.library.types.OpenClosedType.CLOSED : OnOffType.OFF,
                channel.acceptBits(new BitArray(false), 100));
    }

    static Stream<Arguments> malformedResponses() {
        return Stream.of(Arguments.of(99, new BitArray(true), UnDefType.UNDEF),
                Arguments.of(101, new BitArray(true), UnDefType.UNDEF));
    }

    @ParameterizedTest
    @MethodSource("malformedResponses")
    public void mapsOutOfWindowOrShortBitResponsesToUndef(int address, BitArray bits, Object expectedState) {
        Poller2BitChannel channel = new Poller2BitChannel(address, PollerReadFailurePolicy.UNDEF);

        assertEquals(expectedState, channel.acceptBits(bits, 100));
    }
}
