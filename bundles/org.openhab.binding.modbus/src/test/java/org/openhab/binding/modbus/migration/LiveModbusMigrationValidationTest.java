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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.openhab.binding.modbus.handler.ModbusPollerThingHandler;
import org.openhab.binding.modbus.internal.handler.ModbusDataThingHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/** @author Sami Salonen - Initial contribution */
class LiveModbusMigrationValidationTest {
    private final ThingRegistry registry = mock(ThingRegistry.class);
    private final ThingUID endpoint = new ThingUID("modbus:tcp:validation");
    private final Thing poller = BridgeBuilder
            .create(new ThingTypeUID("modbus", "poller"), new ThingUID("modbus:poller:validation:holding"))
            .withBridge(endpoint)
            .withConfiguration(new Configuration(Map.of("start", 100, "length", 2, "type", "holding"))).build();
    private final ThingUID targetUid = new ThingUID("modbus:poller2:validation:holding");
    private final List<MigrationChannel> mappings = List.of(
            new MigrationChannel("modbus:data:validation:holding:first", "first",
                    Map.of("address", "100", "valueType", "uint16", "updateUnchangedValuesEveryMillis",
                            BigDecimal.valueOf(1000)),
                    "Number"),
            new MigrationChannel("modbus:data:validation:holding:second", "second",
                    Map.of("address", "101", "valueType", "uint16"), "Number"));
    private final MigrationGroup group = new MigrationGroup(poller.getUID().toString(), targetUid.toString(),
            endpoint.toString(), poller.getConfiguration().getProperties(), mappings, List.of());
    private final MigrationPreview preview = new MigrationPreview("review", List.of(group), List.of(), List.of(), "",
            "", "");
    private final Instant stagedAt = Instant.now().minusSeconds(10);
    private final Poller2ObservationCollector candidate = new Poller2ObservationCollector();
    private final List<Poller2ObservationCollector> legacy = List.of(new Poller2ObservationCollector(),
            new Poller2ObservationCollector());
    private final Thing target;
    private final List<Thing> sources;
    private final LiveModbusMigrationValidation validation = new LiveModbusMigrationValidation(registry);

    LiveModbusMigrationValidationTest() {
        target = BridgeBuilder.create(new ThingTypeUID("modbus", "poller2"), targetUid).withBridge(endpoint)
                .withConfiguration(new Configuration(group.configuration()))
                .withChannels(mappings.stream()
                        .map(mapping -> ChannelBuilder.create(new ChannelUID(targetUid, mapping.id()))
                                .withAcceptedItemType("Number")
                                .withConfiguration(new Configuration(mapping.configuration())).build())
                        .toList())
                .build();
        sources = mappings.stream().map(mapping -> ThingBuilder
                .create(new ThingTypeUID("modbus", "data"), new ThingUID(mapping.sourceDataUid()))
                .withBridge(poller.getUID())
                .withConfiguration(new Configuration(Map.of("readStart",
                        Objects.requireNonNull(mapping.configuration().get("address")), "readValueType", "uint16")))
                .withChannel(ChannelBuilder.create(new ChannelUID(mapping.sourceDataUid() + ":number"))
                        .withAcceptedItemType("Number").build())
                .build()).toList();
        when(registry.get(poller.getUID())).thenReturn(poller);
        attach(target, candidate);
        for (int i = 0; i < sources.size(); i++) {
            attach(sources.get(i), legacy.get(i));
            record(legacy.get(i), sources.get(i), new ChannelUID(sources.get(i).getUID(), "number"), 42, Instant.now());
        }
        recordCandidate(42, Instant.now());
        // Typed response fixtures: each request detaches only its supplied completion payload.
        var legacyHandler = mock(ModbusPollerThingHandler.class);
        poller.setHandler(legacyHandler);
        when(legacyHandler.readForMigration(any(MigrationReadSelection.class))).thenAnswer(call -> {
            Map<ThingUID, Poller2Observation> values = new HashMap<>();
            legacy.forEach(
                    collector -> collector.getObservation().ifPresent(value -> values.put(value.thingUID(), value)));
            return new MigrationRead(CompletableFuture.completedFuture(Map.copyOf(values)),
                    () -> values.values().stream().allMatch(value -> legacy.stream()
                            .anyMatch(collector -> collector.getObservation().filter(value::equals).isPresent())));
        });
        var candidateHandler = mock(ModbusPollerThingHandler.class);
        target.setHandler(candidateHandler);
        when(candidateHandler.readForMigration(any(MigrationReadSelection.class))).thenAnswer(call -> {
            var value = candidate.getObservation();
            return new MigrationRead(
                    CompletableFuture
                            .completedFuture(value.map(observation -> Map.of(targetUid, observation)).orElse(Map.of())),
                    () -> value.equals(candidate.getObservation()));
        });
    }

    private void attach(Thing thing, Poller2ObservationCollector collector) {
        ThingHandler handler = mock(ModbusDataThingHandler.class,
                withSettings().extraInterfaces(ModbusObservationSource.class, BridgeHandler.class));
        when(((ModbusObservationSource) handler).getObservation()).thenAnswer(invocation -> collector.getObservation());
        thing.setHandler(handler);
        when(registry.get(thing.getUID())).thenReturn(thing);
    }

    private void record(Poller2ObservationCollector collector, Thing thing, ChannelUID uid, int value, Instant time) {
        var state = new DecimalType(value);
        collector.record(collector.beginLifecycle(thing), time, Map.of(uid, state),
                Map.of(uid, new ModbusReadEvidence(state, state, ModbusReadEvidence.Provenance.DECODED)));
    }

    private void recordCandidate(int value, Instant time) {
        var state = new DecimalType(value);
        Map<ChannelUID, State> states = Map.of(new ChannelUID(targetUid, "first"), state,
                new ChannelUID(targetUid, "second"), state);
        var evidence = new ModbusReadEvidence(state, state, ModbusReadEvidence.Provenance.DECODED);
        candidate.record(candidate.beginLifecycle(target), time, states,
                Map.of(new ChannelUID(targetUid, "first"), evidence, new ChannelUID(targetUid, "second"), evidence));
    }

    private MigrationValidation.Observation observe() {
        return validation.observe(preview, group, stagedAt);
    }

    @Test
    void candidateReloadAfterRetrievalCannotAuthorizeComparison() {
        when(registry.get(sources.getFirst().getUID())).thenAnswer(call -> {
            candidate.beginLifecycle(target);
            return sources.getFirst();
        });
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().outcome());
    }

    @Test
    void candidateReloadAfterComparisonRevokesAuthorization() {
        var observation = observe();
        assertEquals(MigrationValidation.Outcome.MATCH, observation.outcome());
        candidate.beginLifecycle(target);
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observation.outcome());
    }

    @Test
    void interruptionAbandonsBothRequests() {
        var left = new MigrationRead(new CompletableFuture<>(), () -> true);
        var right = new MigrationRead(new CompletableFuture<>(), () -> true);
        when(((ModbusPollerThingHandler) poller.getHandler()).readForMigration(any(MigrationReadSelection.class)))
                .thenReturn(left);
        when(((ModbusPollerThingHandler) target.getHandler()).readForMigration(any(MigrationReadSelection.class)))
                .thenReturn(right);
        Thread.currentThread().interrupt();
        try {
            assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().outcome());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(left.completion().isCancelled());
            assertTrue(right.completion().isCancelled());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void timeoutAbandonsUncompletedRequest() {
        var pending = new MigrationRead(new CompletableFuture<>(), () -> true);
        when(((ModbusPollerThingHandler) poller.getHandler()).readForMigration(any(MigrationReadSelection.class)))
                .thenReturn(pending);
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().outcome());
        assertTrue(pending.completion().isCancelled());
        assertFalse(pending.current().getAsBoolean());
    }

    @Test
    void requiresEveryPlannedChannel() {
        assertEquals(MigrationValidation.Outcome.MATCH, observe().outcome(), observe().toString());
        assertEquals(2, observe().comparisons().size());
        record(candidate, target, new ChannelUID(targetUid, "first"), 42, Instant.now());
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().outcome());
        assertEquals(MigrationValidation.Outcome.MATCH, observe().comparisons().getFirst().outcome());
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().comparisons().getLast().outcome());
    }

    @Test
    void reportsCapturedMismatchInsteadOfRetainingMatch() {
        assertEquals(MigrationValidation.Outcome.MATCH, observe().outcome(), observe().toString());
        recordCandidate(43, Instant.now());
        assertEquals(MigrationValidation.Outcome.MISMATCH, observe().outcome());
        assertEquals("42", observe().comparisons().getFirst().sourceValue());
        assertEquals("43", observe().comparisons().getFirst().targetValue());
    }

    @Test
    void invalidatedLegacyOrCandidateCannotAuthorize() {
        candidate.invalidate();
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().outcome());
        recordCandidate(42, Instant.now());
        legacy.getLast().invalidate();
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().outcome());
    }

    @Test
    void refusesPreStageCachedAndFutureEvidence() {
        recordCandidate(42, stagedAt);
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().outcome());
        recordCandidate(42, Instant.now().plusSeconds(60));
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().outcome());
        recordCandidate(42, Instant.now());
        assertEquals(MigrationValidation.Outcome.MATCH, observe().outcome(), observe().toString());
    }

    @Test
    void coreNumericRoundTripMatchesReconstructedJournalWithoutChangingReviewIdentity() {
        var gson = new com.google.gson.Gson();
        Map<String, Object> corePersisted = gson.fromJson(gson.toJson(mappings.getFirst().configuration()),
                new com.google.gson.reflect.TypeToken<Map<String, Object>>() {
                }.getType());
        assertInstanceOf(Double.class, corePersisted.get("updateUnchangedValuesEveryMillis"));
        target.getChannel("first").getConfiguration().setProperties(corePersisted);
        recordCandidate(42, Instant.now());
        Map<String, String> persisted = new HashMap<>();
        var journal = new MigrationJournal(ManagedModbusMigrationStagingTest.storage(persisted));
        journal.workflow(new MigrationJournal.Workflow(preview, List.of(), List.of(), "STAGED", stagedAt.toString(),
                List.of(), List.of()));
        var reconstructed = new MigrationJournal(ManagedModbusMigrationStagingTest.storage(persisted)).workflows()
                .getFirst().preview();
        assertEquals(gson.toJsonTree(preview), gson.toJsonTree(reconstructed));
        assertEquals(BigDecimal.valueOf(1000), reconstructed.groups().getFirst().channels().getFirst().configuration()
                .get("updateUnchangedValuesEveryMillis"));
        assertNotEquals(ModbusMigrationPlanner.configurationIdentity(mappings.getFirst().configuration()),
                ModbusMigrationPlanner.configurationIdentity(corePersisted),
                "Review digest remains representation-exact");
        var observation = validation.observe(reconstructed, reconstructed.groups().getFirst(), stagedAt);
        assertEquals("42", observation.comparisons().getFirst().sourceValue());
        assertEquals("42", observation.comparisons().getFirst().targetValue());
        assertEquals(MigrationValidation.Outcome.MATCH, observation.outcome(), observation.comparisons().toString());
    }

    @Test
    void numericRestartToleranceStillRefusesValueTypeAndOtherConfigurationDrift() {
        for (Object changed : List.of(1001, 1000.5, new BigDecimal("1000.00000000000000000001"), "1000", true,
                Double.NaN, Double.POSITIVE_INFINITY)) {
            target.getChannel("first").getConfiguration().put("updateUnchangedValuesEveryMillis", changed);
            recordCandidate(42, Instant.now());
            assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().comparisons().getFirst().outcome(),
                    changed.toString());
            assertEquals("Current mapping configuration differs from the staged plan",
                    observe().comparisons().getFirst().reason());
        }
        target.getChannel("first").getConfiguration().put("updateUnchangedValuesEveryMillis", 1000.0);
        for (String key : List.of("address", "valueType", "errorPolicy", "profile", "unknown")) {
            var original = mappings.getFirst().configuration();
            var changed = new HashMap<>(original);
            changed.put("updateUnchangedValuesEveryMillis", 1000.0);
            changed.put(key, "changed");
            target.getChannel("first").getConfiguration().setProperties(changed);
            recordCandidate(42, Instant.now());
            assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().comparisons().getFirst().outcome(), key);
        }
    }

    @Test
    void sourceAddressStringsRemainFormatExact() {
        sources.getFirst().getConfiguration().put("readStart", "0100");
        record(legacy.getFirst(), sources.getFirst(), new ChannelUID(sources.getFirst().getUID(), "number"), 42,
                Instant.now());
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().comparisons().getFirst().outcome());
        assertEquals("Current mapping configuration differs from the staged plan",
                observe().comparisons().getFirst().reason());
    }

    @Test
    void refusesChangedConfigurationEvenWithFreshEqualStates() {
        target.getChannel("first").getConfiguration().put("address", "101");
        recordCandidate(42, Instant.now());
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().outcome());
        assertEquals("Current mapping configuration differs from the staged plan",
                observe().comparisons().getFirst().reason());
    }

    @Test
    void refusesConfigurationSignatureFromBeforeChange() {
        sources.getFirst().getConfiguration().put("readTransform", "default");
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, observe().outcome());
    }

    @Test
    void failedEvidenceIsReportedUnavailableRatherThanIntentionalUndef() {
        var uid = new ChannelUID(targetUid, "first");
        candidate.record(candidate.beginLifecycle(target), Instant.now(), Map.of(uid, UnDefType.UNDEF),
                Map.of(uid, new ModbusReadEvidence(UnDefType.UNDEF, new DecimalType(42),
                        ModbusReadEvidence.Provenance.TRANSFORM_FAILURE)));
        var comparison = observe().comparisons().getFirst();
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, comparison.outcome());
        assertEquals("UNAVAILABLE", comparison.targetValue());
    }

    @Test
    void intentionalUndefRequiresEqualInformationBearingInputs() {
        var undef = new ModbusReadEvidence(UnDefType.UNDEF, new DecimalType(42),
                ModbusReadEvidence.Provenance.INTENTIONAL_UNDEF);
        var uid = new ChannelUID(targetUid, "first");
        candidate.record(candidate.beginLifecycle(target), Instant.now(), Map.of(uid, UnDefType.UNDEF),
                Map.of(uid, undef));
        var source = sources.getFirst();
        var sourceUid = new ChannelUID(source.getUID(), "number");
        legacy.getFirst().record(legacy.getFirst().beginLifecycle(source), Instant.now(),
                Map.of(sourceUid, UnDefType.UNDEF), Map.of(sourceUid, undef));
        assertEquals(MigrationValidation.Outcome.MATCH, observe().comparisons().getFirst().outcome());
        assertTrue(undef.informationBearing());
        legacy.getFirst().record(legacy.getFirst().beginLifecycle(source), Instant.now(),
                Map.of(sourceUid, UnDefType.UNDEF), Map.of(sourceUid, new ModbusReadEvidence(UnDefType.UNDEF,
                        new DecimalType(43), ModbusReadEvidence.Provenance.INTENTIONAL_UNDEF)));
        assertEquals(MigrationValidation.Outcome.MISMATCH, observe().comparisons().getFirst().outcome());
    }
}
