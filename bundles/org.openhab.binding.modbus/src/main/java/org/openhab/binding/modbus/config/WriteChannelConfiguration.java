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

/**
 * Base configuration for read channels
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public class WriteChannelConfiguration {

    // Address and valueType are always specified with valid values
    // (core checks against xml definition)
    // setting to empty string here to avoid @Nullable
    public String address = "";
    public String valueType = "";
    public boolean writeMultiple = false;
    public int writeMaxTries; // core fills default from XML

    public BigDecimal closedValue = BigDecimal.ZERO; // OPEN/CLOSED
    public BigDecimal openValue = BigDecimal.ONE; // OPEN/CLOSED

    public BigDecimal offValue = BigDecimal.ZERO; // ON/OFF
    public BigDecimal onValue = BigDecimal.ONE; // ON/OFF

    public BigDecimal minValue = BigDecimal.ZERO; // Percent
    public BigDecimal maxValue = BigDecimal.valueOf(100L); // Percent
}
