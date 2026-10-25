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

import java.math.BigDecimal;
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
        Map<ThingUID, MigrationReadSelection.Binding> sourceBindings = new HashMap<>();
        for (MigrationChannel mapping : group.channels()) {
            ThingUID uid = new ThingUID(mapping.sourceDataUid());
            Thing source = registry.get(uid);
            if (source != null) {
                sourceBindings.put(uid,
                        new MigrationReadSelection.Binding(source, group.channels().stream()
                                .filter(channel -> channel.sourceDataUid().equals(uid.toString()))
                                .map(MigrationChannel::sourceChannelUid).collect(java.util.stream.Collectors.toSet())));
            }
        }
        var sourceSelection = new MigrationReadSelection(sourceBindings);
        MigrationRead legacyRead = start(poller, sourceSelection);
        MigrationRead candidateRead = start(target, new MigrationReadSelection(
                target == null ? Map.of() : Map.of(target.getUID(), new MigrationReadSelection.Binding(target))));
        Map<ThingUID, Poller2Observation> legacyValues = await(legacyRead);
        Map<ThingUID, Poller2Observation> candidateValues = await(candidateRead);
        Optional<Poller2Observation> candidate = read(target, stagedAt, candidateRead, candidateValues);
        for (MigrationChannel mapping : group.channels()) {
            Thing source = registry.get(new ThingUID(mapping.sourceDataUid()));
            Optional<Poller2Observation> legacy = read(source, stagedAt, legacyRead, legacyValues);
            ChannelUID sourceUid = new ChannelUID(mapping.sourceChannelUid());
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
                    && target.getChannel(mapping.id()) != null && matchesChannelConfiguration(mapping.configuration(),
                            Objects.requireNonNull(target.getChannel(mapping.id())).getConfiguration().getProperties());
            Outcome result = Outcome.UNAVAILABLE;
            String reason = "Fresh information-bearing evidence is missing";
            if (!config) {
                reason = "Current mapping configuration differs from the staged plan";
            } else if (left != null && right != null && left.informationBearing() && right.informationBearing()) {
                result = left.value().equals(right.value()) && left.decoded().equals(right.decoded()) ? Outcome.MATCH
                        : Outcome.MISMATCH;
                reason = result == Outcome.MATCH ? "Fresh decoded values agree" : "Fresh decoded values differ";
                if (result == Outcome.MATCH && mapping.configuration().containsKey("readDiagnostic")) {
                    reason = "Fresh read-event semantics and decoded inputs agree; independent event timestamps are not compared";
                } else if (result == Outcome.MATCH
                        && left.provenance() == ModbusReadEvidence.Provenance.INTENTIONAL_UNDEF) {
                    reason = "Fresh intentional UNDEF results agree with equal decoded inputs";
                }
            }
            details.add(new Comparison(sourceUid.toString(), targetUid.toString(), address,
                    Objects.requireNonNull(Objects.toString(mapping.configuration().get("valueType"))),
                    left == null || !left.informationBearing() ? "UNAVAILABLE" : left.value().toString(),
                    right == null || !right.informationBearing() ? "UNAVAILABLE" : right.value().toString(),
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
                        && target.getHandler() == targetHandler && sourceSelection.current()
                        && sourceBindings.size() == group.channels().stream().map(MigrationChannel::sourceDataUid)
                                .distinct().count()
                        && sourceBindings.entrySet().stream()
                                .allMatch(entry -> registry.get(entry.getKey()) == entry.getValue().thing()));
    }

    private boolean matchesChannelConfiguration(Map<String, Object> planned, Map<String, Object> actual) {
        // Core Thing JSONDB can restore the generated interval as Double (1000.0), while the immutable
        // review restores BigDecimal (1000). Tolerate representation only for this numeric schema field,
        // not source address strings, profiles, arbitrary configuration or the versioned review digest.
        String interval = "updateUnchangedValuesEveryMillis";
        Map<String, Object> comparable = new HashMap<>(actual);
        if (planned.get(interval) instanceof Number expected && actual.get(interval) instanceof Number current) {
            try {
                if (new BigDecimal(expected.toString()).compareTo(new BigDecimal(current.toString())) == 0) {
                    comparable.put(interval, expected);
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return ModbusMigrationPlanner.configurationIdentity(planned)
                .equals(ModbusMigrationPlanner.configurationIdentity(comparable));
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
