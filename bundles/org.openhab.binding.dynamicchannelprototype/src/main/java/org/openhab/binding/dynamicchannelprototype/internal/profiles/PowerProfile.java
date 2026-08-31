/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
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
package org.openhab.binding.dynamicchannelprototype.internal.profiles;

import java.math.BigDecimal;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.profiles.ProfileCallback;
import org.openhab.core.thing.profiles.ProfileContext;
import org.openhab.core.thing.profiles.ProfileTypeUID;
import org.openhab.core.thing.profiles.StateProfile;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;

/**
 * Applies a reversible gain and offset between a raw numeric Channel and a power Item in kilowatts.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class PowerProfile implements StateProfile {
    private final ProfileCallback callback;
    private final BigDecimal gain;
    private final BigDecimal offset;

    public PowerProfile(ProfileCallback callback, ProfileContext context) {
        this.callback = callback;
        gain = new BigDecimal(String.valueOf(context.getConfiguration().get("gain")));
        offset = new BigDecimal(String.valueOf(context.getConfiguration().get("offset")));
    }

    @Override
    public ProfileTypeUID getProfileTypeUID() {
        return DynamicChannelPrototypeProfileFactory.POWER_PROFILE_TYPE;
    }

    @Override
    public void onStateUpdateFromItem(State state) {
        // No state updates are sent from the Item to the raw Channel.
    }

    @Override
    public void onCommandFromItem(Command command) {
        BigDecimal value;
        if (command instanceof DecimalType decimal) {
            value = decimal.toBigDecimal();
        } else if (command instanceof QuantityType<?> quantity) {
            value = quantity.toBigDecimal();
        } else {
            return;
        }
        callback.handleCommand(new DecimalType(value.divide(gain).subtract(offset)));
    }

    @Override
    public void onCommandFromHandler(Command command) {
        // The prototype only demonstrates state updates from the binding.
    }

    @Override
    public void onStateUpdateFromHandler(State state) {
        if (state instanceof DecimalType decimal) {
            BigDecimal value = decimal.toBigDecimal().add(offset).multiply(gain);
            QuantityType<Power> power = new QuantityType<>(value.toPlainString() + " kW");
            callback.sendUpdate(power);
        }
    }
}
