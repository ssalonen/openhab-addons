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
package org.openhab.binding.modbus.config;

import java.math.BigDecimal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Base configuration for read channels
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public class ReadChannelConfiguration {

    // Address is always specified with valid value (core checks against xml definition)
    public String address = "";
    public long updateUnchangedValuesEveryMillis = 1000L;
    public boolean updateUndefOnErrors;

    public @Nullable String valueType; // Number, Percent, OPEN/CLOSED, ON/OFF
    public BigDecimal closedValue = BigDecimal.ZERO; // OPEN/CLOSED
    public BigDecimal offValue = BigDecimal.ZERO; // ON/OFF
    public BigDecimal p0Value = BigDecimal.ZERO; // Percent
    public BigDecimal p100Value = BigDecimal.valueOf(100L); // Percent
    public boolean inverted; // OPEN/CLOSED, ON/OFF
    public int length; // HexString

}
