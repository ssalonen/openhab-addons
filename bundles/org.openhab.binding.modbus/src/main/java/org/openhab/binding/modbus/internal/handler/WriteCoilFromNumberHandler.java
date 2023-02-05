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
import org.openhab.core.io.transport.modbus.ModbusWriteCoilRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.types.Command;

/**
 * Handler for write channels, transforming openHAB commands into raw binary data and modbus write requests
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public class WriteCoilFromNumberHandler extends WriteChannelHandler {
    protected int slaveId;

    public WriteCoilFromNumberHandler(int slaveId, WriteChannelConfiguration config,
            Consumer<ModbusWriteRequestBlueprint> writer) {
        super(config, writer);
        this.slaveId = slaveId;
    }

    /**
     * Pre-process command into number that will eventually be encoded over Modbus to true/false bit
     *
     * @param command
     * @return command to write over Modbus. Empty optional can be used to skip write.
     */
    protected Optional<BigDecimal> preProcessCommand(Command command) {
        Optional<BigDecimal> maybeDec = preProcessOnlyIf(DecimalType.class, command, dec -> dec.toBigDecimal());
        if (maybeDec.isPresent()) {
            return maybeDec;
        }
        return preProcessOnlyIf(QuantityType.class, command, dec -> dec.toBigDecimal());
    }

    @Override
    public void processCommand(Command command) {
        Optional<BigDecimal> optionalDecimalValue = preProcessCommand(command);
        optionalDecimalValue.ifPresent(decimalValue -> {
            boolean bitStatus = !decimalValue.equals(BigDecimal.ZERO);
            writer.accept(new ModbusWriteCoilRequestBlueprint(slaveId, address.channelStartElement, bitStatus,
                    config.writeMultiple, config.writeMaxTries));
        });
    }

}
