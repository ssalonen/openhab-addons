/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
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
package org.openhab.binding.modbus;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link ModbusBindingConstants} class defines some constants
 * public that might be used from other bundles as well.
 *
 * @author Sami Salonen - Initial contribution
 * @author Nagy Attila Gabor - Split the original ModbusBindingConstants in two
 */
@NonNullByDefault
public class ModbusBindingConstants {

    public static final String BINDING_ID = "modbus";

    public static final String CHANNEL_READ_INTO_NUMBER = "readIntoNumber";
    public static final String CHANNEL_READ_INTO_PERCENT_COLOR = "readIntoPercentColor";
    public static final String CHANNEL_READ_INTO_PERCENT_DIMMER = "readIntoPercentDimmer";
    public static final String CHANNEL_READ_INTO_PERCENT_ROLLERSHUTTER = "readIntoPercentRollershutter";
    public static final String CHANNEL_READ_INTO_ON_OFF_COLOR = "readIntoOnOffColor";
    public static final String CHANNEL_READ_INTO_ON_OFF_DIMMER = "readIntoOnOffDimmer";
    public static final String CHANNEL_READ_INTO_ON_OFF_SWITCH = "readIntoOnOffSwitch";
    public static final String CHANNEL_READ_INTO_OPEN_CLOSED_CONTACT = "readIntoOpenClosedContact";
    public static final String CHANNEL_READ_INTO_HEX_STRNG = "readIntoHexString";

    public static final String CHANNEL_WRITE_REGISTER_FROM_NUMBER = "writeRegisterFromNumber";
    public static final String CHANNEL_WRITE_REGISTER_FROM_ON_OFF_COLOR = "writeRegisterFromOnOffColor";
    public static final String CHANNEL_WRITE_REGISTER_FROM_ON_OFF_DIMMER = "writeRegisterFromOnOffDimmer";
    public static final String CHANNEL_WRITE_REGISTER_FROM_ON_OFF_SWITCH = "writeRegisterFromOnOffSwitch";
    public static final String CHANNEL_WRITE_REGISTER_FROM_OPEN_CLOSED_CONTACT = "writeRegisterFromOpenClosedContact";
    public static final String CHANNEL_WRITE_REGISTER_FROM_PERCENT_COLOR = "writeRegisterFromPercentColor";
    public static final String CHANNEL_WRITE_REGISTER_FROM_PERCENT_DIMMER = "writeRegisterFromPercentDimmer";
    public static final String CHANNEL_WRITE_REGISTER_FROM_PERCENT_ROLLERSHUTTER = "writeRegisterFromPercentRollershutter";

    public static final String CHANNEL_WRITE_COIL_FROM_NUMBER = "writeCoilFromNumber";
    public static final String CHANNEL_WRITE_COIL_FROM_ON_OFF_COLOR = "writeCoilFromOnOffColor";
    public static final String CHANNEL_WRITE_COIL_FROM_ON_OFF_DIMMER = "writeCoilFromOnOffDimmer";
    public static final String CHANNEL_WRITE_COIL_FROM_ON_OFF_SWITCH = "writeCoilFromOnOffSwitch";
}
