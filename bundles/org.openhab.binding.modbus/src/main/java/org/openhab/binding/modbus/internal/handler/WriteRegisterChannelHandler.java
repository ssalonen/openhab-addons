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

import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.config.WriteChannelConfiguration;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprint;

/**
 * Handler for register write channels, transforming openHAB commands into raw binary data and modbus write
 * requests
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public abstract class WriteRegisterChannelHandler extends WriteChannelHandler {

    protected @Nullable RegisterCache cache;

    /**
     * Create new write channel handler
     *
     * cache parameter can be omitted with full-register writes (i.e. not having address=X)
     *
     * @param config channel config
     * @param cache cache to registers. Used with "partial" register updates
     * @param writer consumer to accept write commands
     */
    public WriteRegisterChannelHandler(WriteChannelConfiguration config, @Nullable RegisterCache cache,
            Consumer<ModbusWriteRequestBlueprint> writer) {
        super(config, writer);
        if (valueType.getBits() < 16) {
            Objects.requireNonNull(cache, "Cache must be provided with channels having partial register writes, "
                    + "i.e. when writing values less than register size (less than 16bit)");
            this.cache = cache;
        }
    }

}
