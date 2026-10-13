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
package org.openhab.binding.modbus.migration;

import java.time.Instant;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.types.State;

/**
 * Decoded states from one fresh device response, independent of Item-channel links.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public record Poller2Observation(ThingUID thingUID, long generation, String configurationSignature, Instant observedAt,
        Map<ChannelUID, State> states) {
    public Poller2Observation {
        states = Map.copyOf(states);
    }
}
