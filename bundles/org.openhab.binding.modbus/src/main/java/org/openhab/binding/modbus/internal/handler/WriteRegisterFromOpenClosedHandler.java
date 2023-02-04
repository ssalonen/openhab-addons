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

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.config.WriteChannelConfiguration;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.types.Command;

/**
 * Handler for write channels, transforming openHAB commands into raw binary data and modbus write requests
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public class WriteRegisterFromOpenClosedHandler extends WriteRegisterFromNumberHandler {

    public WriteRegisterFromOpenClosedHandler(int slaveId, WriteChannelConfiguration config, @Nullable RegisterCache cache,
            Consumer<ModbusWriteRequestBlueprint> writer) {
        super(slaveId, config, cache, writer);
    }

    /**
     * Pre-process OPEN/CLOSED command into number that will be encoded over Modbus.
     *
     * @param command received by channel
     */
    @Override
    protected Optional<BigDecimal> preProcessCommand(Command command) {
        return preProcessOnlyIf(OpenClosedType.class, command, openClosedCommand -> {
            return openClosedCommand == OpenClosedType.CLOSED ? config.closedValue : config.openValue;
        });
    }
}
