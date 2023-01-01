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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.config.ReadChannelConfiguration;
import org.openhab.binding.modbus.internal.ChannelConfigValidationMessage;
import org.openhab.core.io.transport.modbus.AsyncModbusFailure;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.BitArray;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.io.transport.modbus.ModbusFailureCallback;
import org.openhab.core.io.transport.modbus.ModbusReadCallback;
import org.openhab.core.io.transport.modbus.ModbusReadFunctionCode;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.types.State;

/**
 * Handler for read channels, decoding raw binary data from modbus according to channel configuration.
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public abstract class ReadIntoChannelHandler
        implements ModbusReadCallback, ModbusFailureCallback<ModbusReadRequestBlueprint> {

    private @Nullable State lastState;
    private @Nullable Long lastUpdatedMillis;
    private final Consumer<State> stateUpdater;

    protected final ReadChannelConfiguration config;
    protected final int pollStart;
    protected final Address parsedAddress;
    protected final ValueType valueType;

    public ReadIntoChannelHandler(int pollStart, ReadChannelConfiguration config, Consumer<State> stateUpdater) {
        this.pollStart = pollStart;
        this.config = config;
        this.stateUpdater = stateUpdater;
        String address = config.address;
        Objects.requireNonNull(address);
        String valueTypeString = config.valueType;
        Objects.requireNonNull(valueTypeString);
        this.valueType = ValueType.fromConfigValue(valueTypeString);
        // throws on parse error. XML config description validates format however
        parsedAddress = ReadIntoChannelHandler.parseAddress(address);
    }

    public static class Address {

        public final int channelStartElement;
        public final OptionalInt channelStartElementSub;

        public Address(int channelStartElement, OptionalInt channelStartElementSub) {
            this.channelStartElement = channelStartElement;
            this.channelStartElementSub = channelStartElementSub;
        }

    }

    // defaults
    // 1. assume bit value type if poller is readingDiscreteOrCoil

    // validation input:
    // CASE 2 INPUT: poller data type, poll start, poll length, channel start, channel length (hex string)
    // [CASE 1&2] 6. check that readStart is within limits of polled data
    // [CASE 1&2] 7. check that last byte/bit expected is within limits of polled data

    static Address parseAddress(String channelStart) throws IllegalArgumentException {
        final int channelStartElement;
        final OptionalInt channelStartElementSub;
        {
            String[] readParts = channelStart.split("\\.", 2);
            channelStartElement = Integer.parseInt(readParts[0]);
            if (readParts.length == 2) {
                channelStartElementSub = OptionalInt.of(Integer.parseInt(readParts[1]));
            } else {
                channelStartElementSub = OptionalInt.empty();
            }
        }
        Address parsedAddress = new Address(channelStartElement, channelStartElementSub);
        return parsedAddress;
    }

    /**
     * Validate read parameters used to specify decoding of binary data polled from modbus
     *
     * Checks
     * 0. validate format of channelStart. Should be X or X.Y
     * 1. if poller is READ_COILS or READ_INPUT_DISCRETES, check that we have BIT channelValueType
     * 2. if poller is READ_COILS or READ_INPUT_DISCRETES, check that channelStart=X not channelStart=X.Y
     * 3. if poller is reading registers, and channelValueType < 16 bit, check that channelStart=X.Y
     * 4. if having channelStart=X.Y, check that Y is within limits (not referring to data outside register)
     * 5. if poller is reading registers, and channelValueType >= 16 bit, check that channelStart=X
     * 6. Check that decoding starts within the limits of polled data
     * 7. Check that decoding ends within the limits of polled data
     *
     * @param pollerFunctionCode function code used for polled data
     * @param pollerStart start address for polled data
     * @param pollerLength length of elements for polled data
     * @param channelStart start address to start decoding
     * @param channelValueType value type for decoding
     * @return Empty list when validation passes without errors. Otherwise list of validation errors is returned.
     */
    public static List<ChannelConfigValidationMessage> validateReadParameters(ModbusReadFunctionCode pollerFunctionCode,
            int pollerStart, int pollerLength, String channelStart, ValueType channelValueType) {

        List<ChannelConfigValidationMessage> validationIssues = new ArrayList<>();

        // CHECK 0
        final int channelStartElement;
        final OptionalInt channelStartElementSub;
        try {
            Address parsedAddress = parseAddress(channelStart);
            channelStartElement = parsedAddress.channelStartElement;
            channelStartElementSub = parsedAddress.channelStartElementSub;
        } catch (IllegalArgumentException e) {
            String errmsg = String.format("Invalid address=%s, expecting X or X.Y", channelStart);
            validationIssues.add(new ChannelConfigValidationMessage(errmsg));
            // Critical validation issue, stop validating other things
            return validationIssues;
        }

        if (pollerFunctionCode == ModbusReadFunctionCode.READ_COILS
                || pollerFunctionCode == ModbusReadFunctionCode.READ_INPUT_DISCRETES) {
            // Reading bit-type data

            // CHECK 1: BIT value type supported only with READ_COILS and READ_INPUT_DISCRETES
            if (channelValueType != ValueType.BIT) {
                validationIssues.add(new ChannelConfigValidationMessage(String.format(
                        "Invalid valueType=%s, only \"bit\" is supported when reading coils or discrete inputs",
                        channelValueType)));
            }
            // CHECK 2: channelStart=X.Y not supported with READ_COILS and READ_INPUT_DISCRETES, only channelStart=X
            if (channelStartElementSub.isPresent()) {
                String errmsg = String
                        .format("Invalid address=X.Y, only address=X allowed when reading coils or discrete inputs");
                validationIssues.add(new ChannelConfigValidationMessage(errmsg));
            }
        } else {
            // Reading register data

            if (channelValueType.getBits() < 16) {
                // CHECK 3
                if (channelStartElementSub.isEmpty()) {
                    validationIssues.add(new ChannelConfigValidationMessage(String.format(
                            "Invalid address=X, must use address=X.Y with valueType=%s to ambiguously refer to data inside the register",
                            channelValueType)));
                }
                // CHECK 4 (check only if channelStartElementSub is present -- i.e. above CHECK 3 passes)
                else {
                    int subAddress = channelStartElementSub.getAsInt();
                    if (subAddress > 16 / channelValueType.getBits() - 1) {
                        validationIssues.add(new ChannelConfigValidationMessage(String.format(
                                "Invalid address=X.Y=%s, value Y is referring to data outside register. Maximum value for Y with valueType=%s is %d",
                                channelStart, channelValueType, 16 / channelValueType.getBits() - 1)));
                    }
                }
            } else {
                // CHECK 5
                if (channelStartElementSub.isPresent()) {
                    validationIssues.add(new ChannelConfigValidationMessage(String.format(
                            "Invalid address=X.Y, only address=X allowed with valueType=%s, denoting first register address to start decoding the valueType %s",
                            channelValueType, channelValueType)));
                }
            }
        }

        // CHECK 6 & 7
        {
            // Determine bit positions polled, both start and end inclusive
            int dataElementBits = ((pollerFunctionCode == ModbusReadFunctionCode.READ_COILS
                    || pollerFunctionCode == ModbusReadFunctionCode.READ_INPUT_DISCRETES) ? 1 : 16);
            int polledFirstBitIndex = pollerStart * dataElementBits;
            int polledLastBitIndex = polledFirstBitIndex + pollerLength * dataElementBits - 1;

            // Determine bit positions read, both start and end inclusive
            int decodeStartBitIndex = channelStartElement * dataElementBits
                    + channelStartElementSub.orElse(0) * channelValueType.getBits();
            int decodeEndBitIndex = decodeStartBitIndex + channelValueType.getBits() - 1;

            if (decodeStartBitIndex < polledFirstBitIndex || decodeEndBitIndex > polledLastBitIndex) {
                String errmsg = String.format(
                        "Invalid address=X=%d, decoding %s would need data outside polled data. Poller is reading elements from address %d to %d (inclusive) but decoding of valueType=%s starting from address %d would exceeds polled data bounds",
                        channelStartElement, channelValueType, polledFirstBitIndex / dataElementBits,
                        polledLastBitIndex / dataElementBits, channelValueType, channelStartElement);
                validationIssues.add(new ChannelConfigValidationMessage(errmsg));
            }
        }

        return validationIssues;
    }

    protected void updateExpiredChannel(long now, State state) {
        @Nullable
        State localLastState = lastState;
        long localLastUpdatedMillis = Optional.ofNullable(this.lastUpdatedMillis).orElse(0L);
        long millisSinceLastUpdate = now - localLastUpdatedMillis;
        if (localLastUpdatedMillis <= 0L || localLastState == null || config.updateUnchangedValuesEveryMillis <= 0L
                || millisSinceLastUpdate > config.updateUnchangedValuesEveryMillis || !state.equals(localLastState)) {
            stateUpdater.accept(state);
            localLastUpdatedMillis = now;
        }
    }

    @Override
    public void handle(AsyncModbusFailure<ModbusReadRequestBlueprint> failure) {
        process(failure.getCause());
    }

    @Override
    public void handle(AsyncModbusReadResult result) {
        result.getRegisters().ifPresent(registers -> process(registers));
        result.getBits().ifPresent(bits -> process(bits));
    }

    public abstract void process(BitArray bits);

    public abstract void process(ModbusRegisterArray registers);

    public abstract void process(Exception readError);

}
