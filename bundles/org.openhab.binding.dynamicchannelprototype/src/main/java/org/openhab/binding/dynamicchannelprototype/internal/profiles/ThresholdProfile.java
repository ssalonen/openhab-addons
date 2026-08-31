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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.profiles.ProfileCallback;
import org.openhab.core.thing.profiles.ProfileContext;
import org.openhab.core.thing.profiles.ProfileTypeUID;
import org.openhab.core.thing.profiles.StateProfile;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;

/**
 * Converts a raw numeric Channel to a Switch using a threshold and explicit raw values for commands.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ThresholdProfile implements StateProfile {
    private final ProfileCallback callback;
    private final BigDecimal threshold;
    private final DecimalType onValue;
    private final DecimalType offValue;

    public ThresholdProfile(ProfileCallback callback, ProfileContext context) {
        this.callback = callback;
        threshold = new BigDecimal(String.valueOf(context.getConfiguration().get("threshold")));
        onValue = new DecimalType(String.valueOf(context.getConfiguration().get("onValue")));
        offValue = new DecimalType(String.valueOf(context.getConfiguration().get("offValue")));
    }

    @Override
    public ProfileTypeUID getProfileTypeUID() {
        return DynamicChannelPrototypeProfileFactory.THRESHOLD_PROFILE_TYPE;
    }

    @Override
    public void onStateUpdateFromItem(State state) {
        // No state updates are sent from the Item to the raw Channel.
    }

    @Override
    public void onCommandFromItem(Command command) {
        if (OnOffType.ON.equals(command)) {
            callback.handleCommand(onValue);
        } else if (OnOffType.OFF.equals(command)) {
            callback.handleCommand(offValue);
        }
    }

    @Override
    public void onCommandFromHandler(Command command) {
        // The prototype only demonstrates state updates from the binding.
    }

    @Override
    public void onStateUpdateFromHandler(State state) {
        if (state instanceof DecimalType decimal) {
            callback.sendUpdate(decimal.toBigDecimal().compareTo(threshold) > 0 ? OnOffType.ON : OnOffType.OFF);
        }
    }
}
