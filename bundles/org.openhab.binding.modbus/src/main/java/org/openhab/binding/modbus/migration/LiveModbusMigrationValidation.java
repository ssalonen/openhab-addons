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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.handler.ModbusPollerThingHandler;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;
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
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // Replaced handlers/Things cannot retain read authorization.
    public Observation observe(MigrationPreview preview, MigrationGroup group, Instant stagedAt) {
        List<Comparison> details = new ArrayList<>();
        Thing target = registry.get(new ThingUID(group.targetPollerUid()));
        Thing poller = registry.get(new ThingUID(group.sourcePollerUid()));
        var pollerHandler = poller == null ? null : poller.getHandler();
        var targetHandler = target == null ? null : target.getHandler();
        Map<ThingUID, Optional<ThingHandler>> sourceHandlers = new HashMap<>();
        Map<ThingUID, Thing> sources = new HashMap<>();
        Map<ThingUID, String> sourceSignatures = new HashMap<>();
        for (MigrationChannel mapping : group.channels()) {
            ThingUID uid = new ThingUID(mapping.sourceDataUid());
            Thing source = registry.get(uid);
            if (source != null) {
                sources.put(uid, source);
                sourceHandlers.put(uid, Optional.ofNullable(source.getHandler()));
                sourceSignatures.put(uid, Poller2ObservationCollector.signature(source));
            }
        }
        MigrationRead legacyRead = start(poller, new MigrationReadSelection(sourceSignatures));
        MigrationRead candidateRead = start(target, new MigrationReadSelection(
                target == null ? Map.of() : Map.of(target.getUID(), Poller2ObservationCollector.signature(target))));
        Map<ThingUID, Poller2Observation> legacyValues = await(legacyRead);
        Map<ThingUID, Poller2Observation> candidateValues = await(candidateRead);
        Optional<Poller2Observation> candidate = read(target, stagedAt, candidateRead, candidateValues);
        for (MigrationChannel mapping : group.channels()) {
            Thing source = registry.get(new ThingUID(mapping.sourceDataUid()));
            Optional<Poller2Observation> legacy = read(source, stagedAt, legacyRead, legacyValues);
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
                .flatMap(detail -> Stream.of(detail.sourceObservedAt(), detail.targetObservedAt()))
                .min(Instant::compareTo).orElse(Instant.EPOCH);
        return new Observation(preview.identity(), group.sourcePollerUid(), Objects.requireNonNull(timestamp), outcome,
                details,
                () -> legacyRead.current().getAsBoolean() && candidateRead.current().getAsBoolean()
                        && registry.get(new ThingUID(group.targetPollerUid())) == target
                        && registry.get(new ThingUID(group.sourcePollerUid())) == poller && poller != null
                        && poller.getHandler() == pollerHandler && target != null
                        && target.getHandler() == targetHandler
                        && sources
                                .size() == group.channels().stream().map(MigrationChannel::sourceDataUid).distinct()
                                        .count()
                        && sources.entrySet().stream()
                                .allMatch(entry -> registry.get(entry.getKey()) == entry.getValue()
                                        && entry.getValue().getHandler() == Objects
                                                .requireNonNull(sourceHandlers.get(entry.getKey())).orElse(null)
                                        && Objects.equals(sourceSignatures.get(entry.getKey()),
                                                Poller2ObservationCollector.signature(entry.getValue()))));
    }

    private boolean matches(Thing thing, MigrationGroup group) {
        return group.configuration().entrySet().stream().allMatch(entry -> Objects.toString(entry.getValue())
                .equals(Objects.toString(thing.getConfiguration().get(entry.getKey()))));
    }

    private MigrationRead start(@Nullable Thing thing, MigrationReadSelection selection) {
        if (thing != null && thing.getHandler() instanceof ModbusPollerThingHandler handler) {
            return handler.readForMigration(selection);
        }
        return new MigrationRead(CompletableFuture.completedFuture(Map.of()), () -> false);
    }

    private Map<ThingUID, Poller2Observation> await(MigrationRead read) {
        try {
            return read.completion().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            read.completion().cancel(false);
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (ExecutionException | TimeoutException e) {
            read.completion().cancel(false);
            return Map.of();
        }
    }

    private Optional<Poller2Observation> read(@Nullable Thing thing, Instant stagedAt, MigrationRead read,
            Map<ThingUID, Poller2Observation> values) {
        if (thing == null || !read.current().getAsBoolean()) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.get(thing.getUID()))
                .filter(value -> value.configurationSignature().equals(Poller2ObservationCollector.signature(thing)))
                .filter(value -> value.observedAt().isAfter(stagedAt) && !value.observedAt().isAfter(Instant.now()));
    }
}
