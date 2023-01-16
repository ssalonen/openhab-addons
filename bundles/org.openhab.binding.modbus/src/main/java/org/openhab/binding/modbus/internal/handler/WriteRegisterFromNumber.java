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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.config.WriteChannelConfiguration;
import org.openhab.core.io.transport.modbus.ModbusBitUtilities;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.io.transport.modbus.ModbusWriteRegisterRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for write channels, transforming openHAB commands into raw binary data and modbus write requests
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public class WriteRegisterFromNumber extends WriteRegisterChannelHandler {
    private final Logger logger = LoggerFactory.getLogger(WriteRegisterFromNumber.class);
    protected int slaveId;

    public WriteRegisterFromNumber(int slaveId, WriteChannelConfiguration config, @Nullable RegisterCache cache,
            Consumer<ModbusWriteRequestBlueprint> writer) {
        super(config, cache, writer);
        this.slaveId = slaveId;
    }

    /**
     * Pre-process only commands of expected type
     *
     * @param <T> class of expected command
     * @param clz class of expected command
     * @param command incoming command
     * @param postProcessor post processor to process commands of type T
     * @return posprocessed value, or empty when type is not as expected
     */
    protected <T extends Command> Optional<BigDecimal> preProcessOnlyIf(Class<? extends T> clz, Command command,
            Function<T, BigDecimal> postProcessor) {
        if (clz.isAssignableFrom(command.getClass())) {
            @SuppressWarnings("unchecked")
            T typeSafeCommand = (T) command;
            return Optional.of(postProcessor.apply(typeSafeCommand));
        } else {
            logger.debug("Unexpected command {}={} received, only accepting DecimalType",
                    command.getClass().getSimpleName(), command);
            return Optional.empty();
        }
    }

    /**
     * Pre-process command into number that will eventually be encoded over Modbus using given value type
     *
     * @param command
     * @return command to write over Modbus. Empty optional can be used to skip write.
     */
    protected Optional<BigDecimal> preProcessCommand(Command command) {
        return preProcessOnlyIf(DecimalType.class, command, dec -> dec.toBigDecimal());
    }

    @Override
    public void processCommand(Command command) {
        if (valueType == ValueType.BIT) {
            processWriteToBitOfRegister(command);
        } else if (valueType.getBits() < 16) {
            throw new IllegalStateException("Bug. int8/uint8 not expected as this is validated when parsing config");
        } else {
            processWriteFullRegisters(command);
        }
    }

    private void processWriteToBitOfRegister(Command command) {
        Optional<BigDecimal> decimal = preProcessCommand(command);
        if (decimal.isEmpty()) {
            return;
        }

        boolean setBitOn = !BigDecimal.ZERO.equals(decimal.get());
        // getAsInt() never fails, as otherwise config is invalid:
        // - sub index needs to be specified BIT value type
        int bitIndex = address.channelStartElementSub.getAsInt();
        RegisterCache localCache = cache;
        Objects.requireNonNull(localCache); // Invariant, as this is asserted in parent class constructor
        Optional<ModbusRegisterArray> cached = localCache.getCache(address.channelStartElement, 1);
        if (cached.isEmpty()) {
            logger.debug("Cannot write command {} {}: cache not populated", command.getClass().getSimpleName(),
                    command);
            return;
        }
        ModbusRegisterArray cachedRegister = cached.get();
        int register = cachedRegister.getRegister(0);
        if (setBitOn) {
            register |= 1 << bitIndex;
        } else {
            register &= ~(1 << bitIndex);
        }
        writer.accept(new ModbusWriteRegisterRequestBlueprint(slaveId, address.channelStartElement,
                new ModbusRegisterArray(register), config.writeMultiple, config.writeMaxTries));
    }

    private void processWriteFullRegisters(Command command) {
        ModbusRegisterArray registers = ModbusBitUtilities.commandToRegisters(command, valueType);
        writer.accept(new ModbusWriteRegisterRequestBlueprint(slaveId, address.channelStartElement, registers,
                (registers.size() > 1) || config.writeMultiple, config.writeMaxTries));
    }

}
