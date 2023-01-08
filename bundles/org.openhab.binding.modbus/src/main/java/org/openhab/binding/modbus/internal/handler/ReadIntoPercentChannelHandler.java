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

    public ReadIntoPercentChannelHandler(int pollStart, ReadChannelConfiguration config,
            Consumer<@NonNull State> stateUpdater) {
        super(pollStart, config, stateUpdater);
    }

    @Override
    protected void processUpdatedValue(@NonNull State state) {
        if (state instanceof UnDefType) {
            // UNDEF means we have either infinite or NaN floating point number
            super.processUpdatedValue(state);
            return;
        } else {
            DecimalType decimalState = (DecimalType) state; // cast always succeeds

            BigDecimal value = decimalState.toBigDecimal();

            // TODO: clip to minValue and maxValue
            // TODO: then scale linearly
            // if (value.compareTo(this.config.minValue) > 0) {
            //
            // }
            // TODO: scale
            super.processUpdatedValue(state);
            return;
        }
    }
}
