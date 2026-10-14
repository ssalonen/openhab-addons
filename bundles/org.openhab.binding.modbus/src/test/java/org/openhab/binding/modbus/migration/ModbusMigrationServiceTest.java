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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.console.Console;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;

/** @author Sami Salonen - Initial contribution */
class ModbusMigrationServiceTest {
    private final ProviderOwnedMigrationPreviewTest.OwnedThingRegistry things = mock(
            ProviderOwnedMigrationPreviewTest.OwnedThingRegistry.class);
    private final ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
    private final ManagedThingProvider provider = mock(ManagedThingProvider.class);
    private final StorageService storage = mock(StorageService.class);
    private final Map<String, String> persisted = new HashMap<>();

    @org.junit.jupiter.api.BeforeEach
    void ownership() {
        ManagedModbusMigrationStagingTest.trackManagedTargets(things, provider);
    }

    private ModbusMigrationService service() {
        var poller = BridgeBuilder.create(new ThingTypeUID("modbus", "poller"), new ThingUID("modbus:poller:tcp:plant"))
                .withBridge(new ThingUID("modbus:tcp:plant"))
                .withConfiguration(new Configuration(Map.of("start", 100, "length", 2, "type", "holding"))).build();
        var data = org.openhab.core.thing.binding.builder.ThingBuilder
                .create(new ThingTypeUID("modbus", "data"), new ThingUID("modbus:data:tcp:plant:value"))
                .withBridge(poller.getUID())
                .withConfiguration(new Configuration(Map.of("readStart", "100", "readValueType", "int16"))).build();
        when(things.getAll()).thenReturn(List.of(poller, data));
        when(provider.get(data.getUID())).thenReturn(data);
        when(links.getAll()).thenReturn(List.of());
        when(provider.get(poller.getUID())).thenReturn(poller);
        when(storage.<String> getStorage("modbus-migration-journal"))
                .thenReturn(ManagedModbusMigrationStagingTest.storage(persisted));
        return new ModbusMigrationService(things, links, provider, storage);
    }

    @Test
    void providerRefusedWorkSurvivesReadOnlyReportAndRestart() {
        var first = service();
        var unmanaged = BridgeBuilder
                .create(new ThingTypeUID("modbus", "poller"), new ThingUID("modbus:poller:tcp:unmanaged")).build();
        var original = things.getAll();
        var all = new java.util.ArrayList<>(original);
        all.add(unmanaged);
        when(things.getAll()).thenReturn(all);
        var capture = first.preview();
        assertTrue(capture.manualWork().isEmpty());
        assertEquals(1, capture.plan().manualWork().size());
        assertEquals(capture.plan().manualWork(), first.report(capture.plan().identity()).preview().manualWork());
        var before = Map.copyOf(persisted);
        var restarted = service();
        clearInvocations(things, links, provider, storage);
        assertEquals(capture.plan(), restarted.report(capture.plan().identity()).preview());
        assertEquals(before, persisted);
        verifyNoInteractions(things, links, provider, storage);
    }

    @Test
    void refusedProviderConfigurationDriftChangesReviewWithoutExposingValues() {
        var service = service();
        var refused = BridgeBuilder
                .create(new ThingTypeUID("modbus", "poller"), new ThingUID("modbus:poller:tcp:unmanaged"))
                .withConfiguration(new Configuration(Map.of("innocuous", "SENTINEL-before"))).build();
        when(things.getAll()).thenReturn(List.of(refused));
        var before = service.preview().plan();
        refused.getConfiguration().put("innocuous", "SENTINEL-after");
        var after = service.preview().plan();
        assertNotEquals(before.identity(), after.identity());
        assertFalse(persisted.toString().contains("SENTINEL"));
        assertFalse(after.toString().contains("SENTINEL"));
        assertEquals(before.manualWork(), service.report(before.identity()).preview().manualWork());
    }

    @Test
    void serviceReactivationRetainsReviewAndStagingStatus() {
        var first = service();
        String identity = first.preview().plan().identity();
        first.apply(identity);
        var restarted = service();
        assertEquals(MigrationWorkflowReport.Status.STAGED, restarted.report(identity).status());
        assertEquals(identity, restarted.report(identity).preview().identity());
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE,
                restarted.validate(identity, "modbus:poller:tcp:plant").outcome());
        restarted.rollback(identity);
        assertEquals(MigrationWorkflowReport.Status.ROLLED_BACK, restarted.report(identity).status());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void staleSourceCannotValidateOrCutoverAndValidationDoesNotJournal(boolean endpointDrift) {
        service();
        var endpoint = BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), new ThingUID("modbus:tcp:plant"))
                .withConfiguration(new Configuration(Map.of("host", "before"))).build();
        when(things.get(endpoint.getUID())).thenReturn(endpoint);
        MigrationValidation validation = (preview, group, stagedAt) -> new MigrationValidation.Observation(
                preview.identity(), group.sourcePollerUid(), java.time.Instant.now(),
                MigrationValidation.Outcome.MATCH);
        var service = new ModbusMigrationService(things, links, provider, storage, validation);
        String identity = service.preview().plan().identity();
        service.apply(identity);
        assertEquals(MigrationValidation.Outcome.MATCH,
                service.validate(identity, "modbus:poller:tcp:plant").outcome());
        var before = Map.copyOf(persisted);
        if (endpointDrift) {
            endpoint.getConfiguration().put("host", "after");
        } else {
            things.getAll().stream().filter(thing -> thing.getThingTypeUID().getId().equals("data")).findFirst()
                    .orElseThrow().getConfiguration().put("readTransform", List.of("JS:private-source"));
        }
        assertThrows(IllegalArgumentException.class, () -> service.validate(identity, "modbus:poller:tcp:plant"));
        assertThrows(IllegalArgumentException.class, () -> service.cutover(identity, "modbus:poller:tcp:plant"));
        assertEquals(before, persisted);
        assertFalse(persisted.toString().contains("private-source"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void sourceDriftDuringTypedObservationRefusesValidationAndCutover(boolean cutover) {
        service();
        var endpoint = BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), new ThingUID("modbus:tcp:plant"))
                .withConfiguration(new Configuration(Map.of("host", "before"))).build();
        when(things.get(endpoint.getUID())).thenReturn(endpoint);
        MigrationValidation validation = (preview, group, stagedAt) -> {
            endpoint.getConfiguration().put("host", "after");
            return new MigrationValidation.Observation(preview.identity(), group.sourcePollerUid(),
                    java.time.Instant.now(), MigrationValidation.Outcome.MATCH);
        };
        var service = new ModbusMigrationService(things, links, provider, storage, validation);
        String identity = service.preview().plan().identity();
        service.apply(identity);
        var before = Map.copyOf(persisted);
        assertThrows(IllegalArgumentException.class, () -> {
            if (cutover) {
                service.cutover(identity, "modbus:poller:tcp:plant");
            } else {
                service.validate(identity, "modbus:poller:tcp:plant");
            }
        });
        assertEquals(before, persisted);
        verify(links, never()).add(any());
        verify(links, never()).remove(anyString());
    }

    @Test
    void endpointCredentialsAreNotReportedAndDriftStillRefusesApply() {
        var service = service();
        var endpoint = BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), new ThingUID("modbus:tcp:plant"))
                .withConfiguration(new Configuration(Map.of("password", "private-before"))).build();
        when(things.get(endpoint.getUID())).thenReturn(endpoint);
        var capture = service.preview();
        String identity = capture.plan().identity();
        assertFalse(capture.toString().contains("private-before"));
        assertFalse(service.report(identity).toString().contains("private-before"));
        endpoint.getConfiguration().put("password", "private-after");
        assertThrows(IllegalArgumentException.class, () -> service.apply(identity));
        verify(provider, never()).add(any());
        assertEquals(identity, service.report(identity).preview().identity());
    }

    @Test
    void readingReportsNeverCapturesSourcesOrWritesJournal() {
        var service = service();
        String identity = service.preview().plan().identity();
        var before = Map.copyOf(persisted);
        clearInvocations(things, links, provider, storage);
        var first = service.report(identity);
        assertEquals(first, service.report(identity));
        assertEquals(before, persisted);
        assertEquals(identity, first.preview().identity());
        assertEquals(MigrationValidation.Outcome.UNAVAILABLE, first.validation());
        assertFalse(first.cutoverReady());
        verifyNoInteractions(things, links, provider, storage);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "", "unknown" })
    void missingReviewIdentityCannotMutateOrCreateAReview(String identity) {
        var service = service();
        assertThrows(IllegalArgumentException.class, () -> service.report(identity));
        assertThrows(IllegalArgumentException.class, () -> service.apply(identity));
        assertThrows(IllegalStateException.class, () -> service.validate(identity, "modbus:poller:tcp:plant"));
        assertThrows(IllegalStateException.class, () -> service.cutover(identity, "modbus:poller:tcp:plant"));
        assertThrows(IllegalStateException.class,
                () -> service.revert(identity, "Value", "modbus:data:tcp:plant:value:number"));
        assertThrows(IllegalStateException.class, () -> service.rollback(identity));
        assertTrue(persisted.isEmpty());
        verify(provider, never()).add(any());
        verify(provider, never()).remove(any());
        verify(links, never()).add(any());
        verify(links, never()).remove(anyString());
    }

    @Test
    void consoleAndServiceSerializeConcurrentApplyOwnership() throws Exception {
        var service = service();
        String identity = service.preview().plan().identity();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var secondStarted = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return null;
        }).when(provider).add(any());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> new ModbusMigrationConsoleCommandExtension(service)
                    .execute(new String[] { "migrate", "apply", identity }, mock(Console.class)));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                secondStarted.countDown();
                service.apply(identity);
            });
            assertTrue(secondStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> second.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            verify(provider, times(1)).add(any());
            assertEquals(MigrationWorkflowReport.Status.STAGED, service.report(identity).status());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(MigrationValidation.Outcome.class)
    void consoleValidatePrintsEveryComparisonFieldAndDomainThingId(MigrationValidation.Outcome outcome) {
        var service = mock(ModbusMigrationService.class);
        String identity = "a".repeat(64);
        String poller = "modbus:poller:tcp:plant";
        var sourceTime = java.time.Instant.parse("2026-10-14T09:00:00.123456789Z");
        var targetTime = java.time.Instant.parse("2026-10-14T09:00:01.987654321Z");
        var comparison = new MigrationValidation.Comparison("modbus:data:tcp:plant:value:number",
                "modbus:poller2:tcp:plant:value", "100.1", "int16", "42",
                outcome == MigrationValidation.Outcome.UNAVAILABLE ? "UNAVAILABLE"
                        : outcome == MigrationValidation.Outcome.MATCH ? "42" : "43",
                sourceTime, targetTime, outcome, "Detailed reason for " + outcome);
        var second = new MigrationValidation.Comparison("modbus:data:tcp:plant:other:number",
                "modbus:poller2:tcp:plant:other", "101", "uint16", "7", "7", sourceTime, targetTime,
                MigrationValidation.Outcome.MATCH, "Second channel agrees");
        when(service.validate(identity, poller)).thenReturn(new MigrationValidation.Observation(identity, poller,
                targetTime, outcome, List.of(comparison, second)));
        var console = mock(Console.class);
        new ModbusMigrationConsoleCommandExtension(service)
                .execute(new String[] { "migrate", "validate", identity, poller }, console);
        var lines = ArgumentCaptor.forClass(String.class);
        verify(console, atLeastOnce()).println(lines.capture());
        String output = String.join("\n", lines.getAllValues());
        assertTrue(output.contains("Thing ID: modbus:poller2:tcp:plant"), output);
        for (var entry : List.of(comparison, second)) {
            for (String detail : List.of(entry.sourceChannelUid(), entry.targetChannelUid(), entry.address(),
                    entry.valueType(), entry.sourceValue(), entry.targetValue(), entry.sourceObservedAt().toString(),
                    entry.targetObservedAt().toString(), entry.outcome().name(), entry.reason())) {
                assertTrue(output.contains(detail), "Missing console detail: " + detail + "\n" + output);
            }
        }
        assertFalse(output.toLowerCase(java.util.Locale.ROOT).contains("generation"), output);
        verify(service).validate(identity, poller);
        verify(service, never()).cutover(anyString(), anyString());
    }

    @Test
    void consoleCaptureAndServiceOperationsShareOneWorkflow() {
        var service = service();
        var console = mock(Console.class);
        var command = new ModbusMigrationConsoleCommandExtension(service);
        command.execute(new String[] { "migrate", "preview" }, console);
        var lines = ArgumentCaptor.forClass(String.class);
        verify(console, atLeastOnce()).println(lines.capture());
        String identity = lines.getAllValues().stream().filter(line -> line.startsWith("Preview identity: "))
                .findFirst().orElseThrow().substring("Preview identity: ".length());
        assertEquals(MigrationWorkflowReport.Status.PREVIEW, service.report(identity).status());
        service.apply(identity);
        assertEquals(MigrationWorkflowReport.Status.STAGED, service.report(identity).status());
        command.execute(new String[] { "migrate", "validate", identity, "modbus:poller:tcp:plant" }, console);
        verify(console).println(contains("Candidate validation: UNAVAILABLE"));
        verify(console).println("Thing ID: modbus:poller2:tcp:plant");
        verify(console).println(contains("Source channel: modbus:data:tcp:plant:value:number"));
        verify(console).println(contains("Source value: UNAVAILABLE"));
        verify(console).println(contains("Outcome: UNAVAILABLE; reason:"));
        assertFalse(service.report(identity).cutoverReady());
        assertThrows(IllegalStateException.class, () -> service.cutover(identity, "modbus:poller:tcp:plant"));
        command.execute(new String[] { "migrate", "rollback", identity }, console);
        assertEquals(MigrationWorkflowReport.Status.ROLLED_BACK, service.report(identity).status());
        verify(provider).add(any());
        verify(provider).remove(new ThingUID("modbus:poller2:tcp:plant"));
        verify(links, never()).add(any());
        verify(links, never()).remove(anyString());
    }
}
