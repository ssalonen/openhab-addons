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

import java.util.Collection;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.profiles.Profile;
import org.openhab.core.thing.profiles.ProfileCallback;
import org.openhab.core.thing.profiles.ProfileContext;
import org.openhab.core.thing.profiles.ProfileFactory;
import org.openhab.core.thing.profiles.ProfileTypeUID;
import org.osgi.service.component.annotations.Component;

/**
 * Provides the prototype's profiles for converting raw numeric values at item links.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
@Component(service = ProfileFactory.class)
public class DynamicChannelPrototypeProfileFactory implements ProfileFactory {
    public static final ProfileTypeUID POWER_PROFILE_TYPE = new ProfileTypeUID("dynamicchannelprototype", "power");
    public static final ProfileTypeUID THRESHOLD_PROFILE_TYPE = new ProfileTypeUID("dynamicchannelprototype",
            "threshold");

    @Override
    public @Nullable Profile createProfile(ProfileTypeUID profileTypeUID, ProfileCallback callback,
            ProfileContext context) {
        if (POWER_PROFILE_TYPE.equals(profileTypeUID)) {
            return new PowerProfile(callback, context);
        } else if (THRESHOLD_PROFILE_TYPE.equals(profileTypeUID)) {
            return new ThresholdProfile(callback, context);
        }
        return null;
    }

    @Override
    public Collection<ProfileTypeUID> getSupportedProfileTypeUIDs() {
        return Set.of(POWER_PROFILE_TYPE, THRESHOLD_PROFILE_TYPE);
    }
}
