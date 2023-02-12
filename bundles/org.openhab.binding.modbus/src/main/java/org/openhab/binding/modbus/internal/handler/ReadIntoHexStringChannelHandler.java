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

import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.modbus.config.ReadChannelConfiguration;
import org.openhab.core.io.transport.modbus.BitArray;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.openhab.core.util.HexUtils;

/**
 * Handler for readIntoNumber channels, decoding raw binary data from modbus according to channel configuration.
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public class ReadIntoHexStringChannelHandler extends ReadIntoChannelHandler {

    private int relativeStartIndex;

    public ReadIntoHexStringChannelHandler(int pollStart, ReadChannelConfiguration config,
            Consumer<State> stateUpdater) {
        super(pollStart, config, stateUpdater);
        relativeStartIndex = parsedAddress.channelStartElement - pollStart;
    }

    @Override
    public void process(BitArray bits) {
        processUpdatedValue(HexUtils
                .bytesToHex(bits.copyOfRange(relativeStartIndex, relativeStartIndex + config.length).getBytes()));
    }

    @Override
    public void process(ModbusRegisterArray registers) {
        processUpdatedValue(
                registers.copyOfRange(relativeStartIndex, relativeStartIndex + config.length).toHexString());
    }

    @Override
    public void process(Exception readError) {
        updateExpiredChannel(System.currentTimeMillis(), UnDefType.UNDEF);
    }

    private void processUpdatedValue(String hexString) {
        updateExpiredChannel(System.currentTimeMillis(), new StringType(hexString));
    }

}
