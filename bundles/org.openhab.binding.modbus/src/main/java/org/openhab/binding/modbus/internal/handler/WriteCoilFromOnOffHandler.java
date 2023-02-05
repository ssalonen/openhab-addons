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
import org.openhab.binding.modbus.config.WriteChannelConfiguration;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.types.Command;

/**
 * Handler for write channels, transforming openHAB commands into raw binary data and modbus write requests
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public class WriteCoilFromOnOffHandler extends WriteCoilFromNumberHandler {
    public WriteCoilFromOnOffHandler(int slaveId, WriteChannelConfiguration config,
            Consumer<ModbusWriteRequestBlueprint> writer) {
        super(slaveId, config, writer);
    }

    /**
     * Pre-process command into number that will eventually be encoded over Modbus using given value type
     *
     * @param command
     * @return command to write over Modbus. Empty optional can be used to skip write.
     */
    @Override
    protected Optional<BigDecimal> preProcessCommand(Command command) {
        return preProcessOnlyIf(OnOffType.class, command, onOffCommand -> {
            return onOffCommand == OnOffType.OFF ? BigDecimal.ZERO : BigDecimal.ONE;
        });
    }

}
