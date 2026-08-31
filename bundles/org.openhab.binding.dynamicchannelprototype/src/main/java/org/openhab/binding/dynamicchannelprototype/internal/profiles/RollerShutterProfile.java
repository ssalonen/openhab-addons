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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.thing.profiles.ProfileCallback;
import org.openhab.core.thing.profiles.ProfileTypeUID;
import org.openhab.core.thing.profiles.StateProfile;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;

/**
 * Arbitrary example mapping raw numeric positions to a RollerShutter's percentage state and command.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class RollerShutterProfile implements StateProfile {
    private final ProfileCallback callback;

    public RollerShutterProfile(ProfileCallback callback) {
        this.callback = callback;
    }

    @Override
    public ProfileTypeUID getProfileTypeUID() {
        return DynamicChannelPrototypeProfileFactory.ROLLER_PROFILE_TYPE;
    }

    @Override
    public void onStateUpdateFromItem(State state) {
        // No state updates are sent from the Item to the raw Channel.
    }

    @Override
    public void onCommandFromItem(Command command) {
        if (command instanceof PercentType percent) {
            callback.handleCommand(new DecimalType(percent.toBigDecimal()));
        }
    }

    @Override
    public void onCommandFromHandler(Command command) {
        // The prototype only demonstrates state updates from the binding.
    }

    @Override
    public void onStateUpdateFromHandler(State state) {
        if (state instanceof DecimalType decimal) {
            callback.sendUpdate(new PercentType(decimal.toBigDecimal()));
        }
    }
}
