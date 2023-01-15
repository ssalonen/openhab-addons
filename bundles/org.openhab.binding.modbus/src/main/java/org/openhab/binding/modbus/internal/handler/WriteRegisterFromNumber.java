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
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.config.WriteChannelConfiguration;
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
public class WriteRegisterFromNumber extends WriteChannelHandler {

    private final Logger logger = LoggerFactory.getLogger(WriteRegisterFromNumber.class);

    public WriteRegisterFromNumber(WriteChannelConfiguration config, @Nullable RegisterCache registerCache) {
        super(config, registerCache);
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
            logger.debug("Unexpected command {} received, only accepting DecimalType",
                    command.getClass().getSimpleName());
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
}
