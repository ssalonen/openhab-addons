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
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.modbus.config.ReadChannelConfiguration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Handler for readIntoPercent channels, decoding raw binary data from modbus according to channel configuration.
 *
 * @author Sami Salonen - Initial contribution
 *
 */
@NonNullByDefault
public class ReadIntoPercentChannelHandler extends ReadIntoNumberChannelHandler {

    private static BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private BigDecimal range;
    private BigDecimal min;
    private BigDecimal max;

    public ReadIntoPercentChannelHandler(int pollStart, ReadChannelConfiguration config,
            Consumer<@NonNull State> stateUpdater) {
        super(pollStart, config, stateUpdater);
        range = config.maxValue.subtract(config.minValue);
        // It is perfectly valid to have min can be greater than max:
        // In this case, the larger decoded number we have, the smaller
        // resulting PercentType will be
        min = config.minValue.min(config.maxValue);
        max = config.maxValue.max(config.minValue);
    }

    @Override
    protected State postProcessNumberState(State state) {
        if (state instanceof UnDefType) {
            // UNDEF means we have either infinite or NaN floating point number
            return state;
        } else {
            DecimalType decimalState = (DecimalType) state; // cast always succeeds
            BigDecimal value = decimalState.toBigDecimal();
            value = value.max(min).min(max);
            return new PercentType(value.subtract(config.minValue).divide(range).multiply(HUNDRED));
        }
    }
}
