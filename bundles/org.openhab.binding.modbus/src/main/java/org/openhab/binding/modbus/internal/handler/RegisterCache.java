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

import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;

/**
 * Cache of polled data
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public interface RegisterCache {

    /**
     * Get cached registers
     *
     * @param start first register index
     * @param length length of registers
     * @return cached registers, or empty when there is no cache available
     */
    public Optional<ModbusRegisterArray> getCache(int start, int length);

}
