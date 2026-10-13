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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.types.State;

/**
 * Handler-owned, lifecycle-bound observation storage. It retains only the latest fresh poll, not Item state.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class Poller2ObservationCollector {
    private long generation;
    private @Nullable ThingUID thingUID;
    private String configurationSignature = "";
    private @Nullable Poller2Observation observation;

    /** Starts an empty lifecycle and returns its handler-local generation token. */
    public synchronized long beginLifecycle(Bridge thing) {
        invalidate();
        thingUID = thing.getUID();
        configurationSignature = ModbusMigrationPlanner.configurationIdentity(List.of(
                Objects.requireNonNullElse(Objects.toString(thing.getBridgeUID()), ""),
                thing.getConfiguration().getProperties(),
                thing.getChannels().stream().sorted(Comparator.comparing(channel -> channel.getUID().toString()))
                        .map(channel -> List.of(channel.getUID().toString(),
                                Objects.requireNonNullElse(channel.getAcceptedItemType(), ""),
                                Objects.requireNonNullElse(Objects.toString(channel.getChannelTypeUID()), ""),
                                channel.getConfiguration().getProperties()))
                        .toList()));
        return generation;
    }

    /** Clears observations and prevents a previously captured generation from recording new data. */
    public synchronized void invalidate() {
        generation++;
        thingUID = null;
        observation = null;
    }

    /**
     * Records a decoded fresh response only if its lifecycle is still current. Empty responses are not observations.
     */
    public synchronized void record(long expectedGeneration, Instant observedAt, Map<ChannelUID, State> states) {
        ThingUID uid = thingUID;
        if (expectedGeneration == generation && uid != null && !states.isEmpty()) {
            observation = new Poller2Observation(uid, generation, configurationSignature, observedAt, states);
        }
    }

    /** Returns the latest fresh response, or empty until a response arrives in the current lifecycle. */
    public synchronized Optional<Poller2Observation> getObservation() {
        return Optional.ofNullable(observation);
    }
}
