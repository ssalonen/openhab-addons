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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Compares every planned mapping using binding-owned fresh device responses.
 * 
 * @author Sami Salonen - Initial contribution
 */
@Component(service = MigrationValidation.class)
@NonNullByDefault
public final class LiveModbusMigrationValidation implements MigrationValidation {
    private final ThingRegistry registry;

    @Activate
    public LiveModbusMigrationValidation(@Reference ThingRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Observation observe(MigrationPreview preview, MigrationGroup group, Instant stagedAt) {
        List<Comparison> details = new ArrayList<>();
        Thing target = registry.get(new ThingUID(group.targetPollerUid()));
        Thing poller = registry.get(new ThingUID(group.sourcePollerUid()));
        Optional<Poller2Observation> candidate = read(target, stagedAt);
        for (MigrationChannel mapping : group.channels()) {
            Thing source = registry.get(new ThingUID(mapping.sourceDataUid()));
            Optional<Poller2Observation> legacy = read(source, stagedAt);
            ChannelUID sourceUid = new ChannelUID(mapping.sourceDataUid() + ":number");
            ChannelUID targetUid = new ChannelUID(group.targetPollerUid() + ":" + mapping.id());
            String address = Objects.requireNonNull(Objects.toString(mapping.configuration().get("address"), ""));
            ModbusReadEvidence left = legacy.map(value -> value.evidence().get(sourceUid)).orElse(null);
            ModbusReadEvidence right = candidate.map(value -> value.evidence().get(targetUid)).orElse(null);
            boolean config = target != null && poller != null && source != null
                    && group.targetBridgeUid().equals(Objects.toString(target.getBridgeUID()))
                    && group.targetBridgeUid().equals(Objects.toString(poller.getBridgeUID())) && matches(poller, group)
                    && matches(target, group) && Objects.toString(source.getBridgeUID()).equals(group.sourcePollerUid())
                    && address.equals(Objects.toString(source.getConfiguration().get("readStart")))
                    && Objects.toString(mapping.configuration().get("valueType"))
                            .equals(Objects.toString(source.getConfiguration().get("readValueType")))
                    && target.getChannel(mapping.id()) != null
                    && ModbusMigrationPlanner.configurationIdentity(mapping.configuration())
                            .equals(ModbusMigrationPlanner
                                    .configurationIdentity(Objects.requireNonNull(target.getChannel(mapping.id()))
                                            .getConfiguration().getProperties()));
            Outcome result = Outcome.UNAVAILABLE;
            String reason = "Fresh information-bearing evidence is missing";
            if (!config) {
                reason = "Current mapping configuration differs from the staged plan";
            } else if (left != null && right != null && left.informationBearing() && right.informationBearing()) {
                result = left.value().equals(right.value()) && left.decoded().equals(right.decoded()) ? Outcome.MATCH
                        : Outcome.MISMATCH;
                reason = result == Outcome.MATCH ? "Fresh decoded values agree" : "Fresh decoded values differ";
            }
            details.add(new Comparison(sourceUid.toString(), targetUid.toString(), address,
                    Objects.requireNonNull(Objects.toString(mapping.configuration().get("valueType"))),
                    left == null ? "UNAVAILABLE" : left.value().toString(),
                    right == null ? "UNAVAILABLE" : right.value().toString(),
                    Objects.requireNonNull(legacy.map(Poller2Observation::observedAt).orElse(Instant.EPOCH)),
                    Objects.requireNonNull(candidate.map(Poller2Observation::observedAt).orElse(Instant.EPOCH)), result,
                    reason));
        }
        Outcome outcome = details.isEmpty()
                || details.stream().anyMatch(detail -> detail.outcome() == Outcome.UNAVAILABLE) ? Outcome.UNAVAILABLE
                        : details.stream().anyMatch(detail -> detail.outcome() == Outcome.MISMATCH) ? Outcome.MISMATCH
                                : Outcome.MATCH;
        Instant timestamp = details.stream()
                .flatMap(detail -> java.util.stream.Stream.of(detail.sourceObservedAt(), detail.targetObservedAt()))
                .min(Instant::compareTo).orElse(Instant.EPOCH);
        return new Observation(preview.identity(), group.sourcePollerUid(), Objects.requireNonNull(timestamp), outcome,
                details);
    }

    private boolean matches(Thing thing, MigrationGroup group) {
        return group.configuration().entrySet().stream().allMatch(entry -> Objects.toString(entry.getValue())
                .equals(Objects.toString(thing.getConfiguration().get(entry.getKey()))));
    }

    private Optional<Poller2Observation> read(@org.eclipse.jdt.annotation.Nullable Thing thing, Instant stagedAt) {
        if (thing == null || !(thing.getHandler() instanceof ModbusObservationSource source)) {
            return Optional.empty();
        }
        return source.getObservation()
                .filter(value -> value.configurationSignature().equals(Poller2ObservationCollector.signature(thing)))
                .filter(value -> value.observedAt().isAfter(stagedAt) && !value.observedAt().isAfter(Instant.now()));
    }
}
