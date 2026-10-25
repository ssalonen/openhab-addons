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
package org.openhab.binding.modbus.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.modbus.internal.ModbusBindingConstantsInternal;
import org.openhab.binding.modbus.internal.ModbusChannelTypeProvider;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.RefreshType;

/**
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class ModbusPoller2ThingHandlerTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "Switch", "Contact" })
    public void registerChannelsPreserveLegacyBooleanConversionAndRawEvidence(String itemType)
            throws EndpointNotInitializedException {
        LifecycleFixture fixture = new LifecycleFixture("holding", itemType,
                java.util.Map.of("address", "0", "valueType", "int16", "errorPolicy", "keepLast"));
        fixture.handler.initialize();
        for (int value : new int[] { 0, 1, 2, 32767, 32768, 65535 }) {
            org.mockito.Mockito.clearInvocations(fixture.callback);
            fixture.pollRegisters(value);
            org.openhab.core.types.State expected = "Switch".equals(itemType)
                    ? org.openhab.core.library.types.OnOffType.from(value != 0)
                    : value == 0 ? org.openhab.core.library.types.OpenClosedType.CLOSED
                            : org.openhab.core.library.types.OpenClosedType.OPEN;
            verify(fixture.callback).stateUpdated(fixture.channelUID, expected);
            var evidence = fixture.handler.getObservation().orElseThrow().evidence().get(fixture.channelUID);
            assertEquals(expected, evidence.value());
            assertEquals(new org.openhab.core.library.types.DecimalType((short) value), evidence.decoded());
        }
        fixture.handler.dispose();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "coil,Switch", "discrete,Contact", "coil,Number",
            "discrete,Number" })
    public void migrationBitReadsAreDetachedAndInformationBearing(String pollType, String itemType) throws Exception {
        LifecycleFixture fixture = new LifecycleFixture(pollType, itemType,
                java.util.Map.of("address", "0", "valueType", "bit", "errorPolicy", "keepLast"));
        fixture.handler.initialize();
        fixture.handler.getThing().setHandler(fixture.handler);
        org.mockito.Mockito.clearInvocations(fixture.callback, fixture.communication);
        var read = fixture.handler.readForMigration(new org.openhab.binding.modbus.migration.MigrationReadSelection(
                java.util.Map.of(fixture.handler.getThing().getUID(),
                        new org.openhab.binding.modbus.migration.MigrationReadSelection.Binding(
                                fixture.handler.getThing()))));
        var requests = ArgumentCaptor.forClass(org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint.class);
        var reads = ArgumentCaptor.forClass(org.openhab.core.io.transport.modbus.ModbusReadCallback.class);
        verify(fixture.communication).submitOneTimePoll(requests.capture(), reads.capture(), any());
        reads.getValue().handle(new org.openhab.core.io.transport.modbus.AsyncModbusReadResult(requests.getValue(),
                new org.openhab.core.io.transport.modbus.BitArray(true)));
        var observation = read.completion().get().get(fixture.handler.getThing().getUID());
        org.junit.jupiter.api.Assertions.assertNotNull(observation);
        org.junit.jupiter.api.Assertions.assertTrue(observation.evidence().containsKey(fixture.channelUID));
        var evidence = observation.evidence().get(fixture.channelUID);
        org.junit.jupiter.api.Assertions.assertTrue(evidence.informationBearing());
        assertEquals(new org.openhab.core.library.types.DecimalType(1), evidence.decoded());
        assertEquals("Number".equals(itemType) ? "1" : "Contact".equals(itemType) ? "OPEN" : "ON",
                evidence.value().toString());
        org.mockito.Mockito.verifyNoInteractions(fixture.callback);
        fixture.handler.dispose();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "lastReadSuccess", "lastReadError" })
    public void readDiagnosticChannelsPublishOnlyTheirOwnReadEvent(String role) throws Exception {
        LifecycleFixture fixture = new LifecycleFixture("holding", "DateTime", java.util.Map.of("address", "0",
                "valueType", "int16", "errorPolicy", "keepLast", "readDiagnostic", role));
        fixture.handler.initialize();
        org.mockito.Mockito.clearInvocations(fixture.callback);
        fixture.pollRegisters(19);
        var values = ArgumentCaptor.forClass(org.openhab.core.types.State.class);
        if ("lastReadSuccess".equals(role)) {
            verify(fixture.callback).stateUpdated(org.mockito.ArgumentMatchers.eq(fixture.channelUID),
                    values.capture());
            org.junit.jupiter.api.Assertions.assertInstanceOf(org.openhab.core.library.types.DateTimeType.class,
                    values.getValue());
        } else {
            verify(fixture.callback, org.mockito.Mockito.never()).stateUpdated(any(), any());
        }
        org.mockito.Mockito.clearInvocations(fixture.callback);
        var failures = ArgumentCaptor.forClass(org.openhab.core.io.transport.modbus.ModbusFailureCallback.class);
        verify(fixture.communication).registerRegularPoll(any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any(), failures.capture());
        var failure = org.mockito.Mockito.mock(org.openhab.core.io.transport.modbus.AsyncModbusFailure.class);
        org.mockito.Mockito.when(failure.getCause()).thenReturn(new IllegalStateException("fixture read failure"));
        failures.getValue().handle(failure);
        if ("lastReadError".equals(role)) {
            verify(fixture.callback).stateUpdated(org.mockito.ArgumentMatchers.eq(fixture.channelUID),
                    values.capture());
            org.junit.jupiter.api.Assertions.assertInstanceOf(org.openhab.core.library.types.DateTimeType.class,
                    values.getValue());
        } else {
            verify(fixture.callback, org.mockito.Mockito.never()).stateUpdated(any(), any());
        }
        org.junit.jupiter.api.Assertions.assertTrue(fixture.handler.getObservation().isEmpty());
        fixture.handler.dispose();
    }

    @Test
    public void migrationChannelsPreserveLegacyUnchangedValueSuppression() throws Exception {
        LifecycleFixture fixture = new LifecycleFixture("holding", "Number", java.util.Map.of("address", "0",
                "valueType", "int16", "errorPolicy", "keepLast", "updateUnchangedValuesEveryMillis", 1000));
        fixture.handler.initialize();
        org.mockito.Mockito.clearInvocations(fixture.callback);
        fixture.pollRegisters(19);
        fixture.pollRegisters(19);
        verify(fixture.callback, times(1)).stateUpdated(fixture.channelUID,
                new org.openhab.core.library.types.DecimalType(19));
        fixture.pollRegisters(20);
        verify(fixture.callback).stateUpdated(fixture.channelUID, new org.openhab.core.library.types.DecimalType(20));
        fixture.handler.dispose();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    public void explicitRefreshRepublishesUnchangedValueWithoutDisablingPollSuppression(boolean cached)
            throws Exception {
        LifecycleFixture fixture = new LifecycleFixture("holding", "Number", java.util.Map.of("address", "0",
                "valueType", "int16", "errorPolicy", "keepLast", "updateUnchangedValuesEveryMillis", 1000));
        if (!cached) {
            var thing = org.openhab.core.thing.binding.builder.BridgeBuilder
                    .create((Bridge) fixture.handler.getThing()).withConfiguration(new Configuration(java.util.Map
                            .of("type", "holding", "start", 0, "length", 1, "refresh", 1000, "cacheMillis", 0)))
                    .build();
            fixture.handler.thingUpdated(thing);
        } else {
            fixture.handler.initialize();
        }
        fixture.pollRegisters(41);
        var observation = fixture.handler.getObservation().orElseThrow();
        org.mockito.Mockito.clearInvocations(fixture.callback);
        fixture.pollRegisters(41);
        verify(fixture.callback, org.mockito.Mockito.never()).stateUpdated(any(), any());
        observation = fixture.handler.getObservation().orElseThrow();

        fixture.handler.handleCommand(fixture.channelUID, RefreshType.REFRESH);
        if (!cached) {
            var reads = ArgumentCaptor.forClass(org.openhab.core.io.transport.modbus.ModbusReadCallback.class);
            verify(fixture.communication).submitOneTimePoll(any(), reads.capture(), any());
            var refreshRead = reads.getValue();
            fixture.handler.getThing().setHandler(fixture.handler);
            var migration = fixture.handler
                    .readForMigration(new org.openhab.binding.modbus.migration.MigrationReadSelection(
                            java.util.Map.of(fixture.handler.getThing().getUID(),
                                    new org.openhab.binding.modbus.migration.MigrationReadSelection.Binding(
                                            fixture.handler.getThing()))));
            verify(fixture.communication, times(2)).submitOneTimePoll(any(), reads.capture(), any());
            reads.getValue()
                    .handle(new org.openhab.core.io.transport.modbus.AsyncModbusReadResult(
                            java.util.Objects.requireNonNull(fixture.handler.getRequest()),
                            new org.openhab.core.io.transport.modbus.ModbusRegisterArray(99)));
            assertEquals(new org.openhab.core.library.types.DecimalType(99),
                    java.util.Objects
                            .requireNonNull(migration.completion().get().get(fixture.handler.getThing().getUID()))
                            .states().get(fixture.channelUID));
            assertEquals(observation, fixture.handler.getObservation().orElseThrow());
            verify(fixture.callback, org.mockito.Mockito.never()).stateUpdated(any(), any());
            refreshRead.handle(new org.openhab.core.io.transport.modbus.AsyncModbusReadResult(
                    java.util.Objects.requireNonNull(fixture.handler.getRequest()),
                    new org.openhab.core.io.transport.modbus.ModbusRegisterArray(41)));
        } else {
            assertEquals(observation.observedAt(), fixture.handler.getObservation().orElseThrow().observedAt());
        }
        verify(fixture.callback).stateUpdated(fixture.channelUID, new org.openhab.core.library.types.DecimalType(41));
        org.mockito.Mockito.clearInvocations(fixture.callback);
        fixture.pollRegisters(41);
        verify(fixture.callback, org.mockito.Mockito.never()).stateUpdated(any(), any());
        fixture.handler.dispose();
    }

    @Test
    public void reusesTheGeneratedChannelTypeWhenTheBridgeIsInitializedAgain() {
        Bridge bridge = BridgeBuilder.create(ModbusBindingConstantsInternal.THING_TYPE_MODBUS_POLLER2, "poller2")
                .withConfiguration(new Configuration()).withChannel(ChannelBuilder
                        .create(new ChannelUID("modbus:poller2:poller2:value")).withAcceptedItemType("Number").build())
                .build();
        ModbusChannelTypeProvider provider = new ModbusChannelTypeProvider();
        ModbusPoller2ThingHandler handler = new ModbusPoller2ThingHandler(bridge, provider);
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        handler.setCallback(callback);

        handler.initialize();

        ArgumentCaptor<Thing> updatedThing = ArgumentCaptor.forClass(Thing.class);
        verify(callback).thingUpdated(updatedThing.capture());
        Channel generatedChannel = updatedThing.getValue().getChannels().getFirst();
        assertEquals(new ChannelTypeUID("modbus", "poller2-generated-number"), generatedChannel.getChannelTypeUID());
        assertEquals(1, provider.getChannelTypes(null).size());

        handler.thingUpdated(updatedThing.getValue());
        handler.initialize();

        verify(callback, times(1)).thingUpdated(any(Thing.class));
        assertEquals(1, provider.getChannelTypes(null).size());
    }

    @Test
    public void ignoresResultsFromSupersededPollRegistrations() throws EndpointNotInitializedException {
        LifecycleFixture fixture = new LifecycleFixture();
        fixture.handler.initialize();
        ArgumentCaptor<org.openhab.core.io.transport.modbus.ModbusReadCallback> reads = ArgumentCaptor
                .forClass(org.openhab.core.io.transport.modbus.ModbusReadCallback.class);
        ArgumentCaptor<org.openhab.core.io.transport.modbus.ModbusFailureCallback> failures = ArgumentCaptor
                .forClass(org.openhab.core.io.transport.modbus.ModbusFailureCallback.class);
        verify(fixture.communication).registerRegularPoll(any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), reads.capture(), failures.capture());
        var oldRead = reads.getValue();
        var oldFailure = failures.getValue();
        fixture.handler.dispose();
        fixture.handler.initialize();
        org.mockito.Mockito.clearInvocations(fixture.callback);

        var result = mock(org.openhab.core.io.transport.modbus.AsyncModbusReadResult.class);
        org.mockito.Mockito.when(result.getRegisters())
                .thenReturn(java.util.Optional.of(new org.openhab.core.io.transport.modbus.ModbusRegisterArray(42)));
        org.mockito.Mockito.when(result.getBits()).thenReturn(java.util.Optional.empty());
        oldRead.handle(result);
        var failure = mock(org.openhab.core.io.transport.modbus.AsyncModbusFailure.class);
        org.mockito.Mockito.when(failure.getCause()).thenReturn(new IllegalStateException("old read"));
        oldFailure.handle(failure);

        org.mockito.Mockito.verifyNoInteractions(fixture.callback);
        org.junit.jupiter.api.Assertions.assertNull(fixture.handler.getLastPolledDataCache().get());
        verify(fixture.communication, times(2)).registerRegularPoll(any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), reads.capture(), failures.capture());
        reads.getValue().handle(result);
        verify(fixture.callback).stateUpdated(fixture.channelUID, new org.openhab.core.library.types.DecimalType(42));
    }

    @Test
    public void submitsWritesOutsideTheLifecycleMonitor() throws EndpointNotInitializedException {
        LifecycleFixture fixture = new LifecycleFixture();
        fixture.handler.initialize();
        org.mockito.Mockito.doAnswer(invocation -> {
            org.junit.jupiter.api.Assertions.assertFalse(Thread.holdsLock(fixture.handler));
            return null;
        }).when(fixture.communication).submitOneTimeWrite(any(), any(), any());
        fixture.handler.handleCommand(fixture.channelUID, new org.openhab.core.library.types.DecimalType(7));
        assertEquals(org.openhab.binding.modbus.internal.handler.Poller2WriteResult.ACCEPTED,
                fixture.handler.submitActionWrite(org.openhab.binding.modbus.internal.handler.Poller2WriteRequest
                        .holding("value", new org.openhab.core.library.types.DecimalType(8))));
        verify(fixture.communication, times(2)).submitOneTimeWrite(any(), any(), any());
        fixture.handler.dispose();
        org.mockito.Mockito.clearInvocations(fixture.communication);
        fixture.handler.handleCommand(fixture.channelUID, new org.openhab.core.library.types.DecimalType(9));
        org.mockito.Mockito.verifyNoInteractions(fixture.communication);
    }

    @Test
    public void reconcilesWriteResponsesOutsideTheLifecycleMonitor() throws EndpointNotInitializedException {
        LifecycleFixture fixture = new LifecycleFixture();
        fixture.handler.initialize();
        fixture.handler.handleCommand(fixture.channelUID, new org.openhab.core.library.types.DecimalType(7));
        var writes = ArgumentCaptor.forClass(org.openhab.core.io.transport.modbus.ModbusWriteCallback.class);
        verify(fixture.communication).submitOneTimeWrite(any(), writes.capture(), any());
        org.mockito.Mockito.doAnswer(invocation -> {
            org.junit.jupiter.api.Assertions.assertFalse(Thread.holdsLock(fixture.handler),
                    "write reconciliation must not submit transport work under the lifecycle monitor");
            return null;
        }).when(fixture.communication).submitOneTimePoll(any(), any(), any());
        writes.getValue().handle(mock(org.openhab.core.io.transport.modbus.AsyncModbusWriteResult.class));
        verify(fixture.communication).submitOneTimePoll(any(), any(), any());
        fixture.handler.dispose();
    }

    @Test
    public void ignoresWriteCallbacksAfterReinitialization() throws EndpointNotInitializedException {
        LifecycleFixture fixture = new LifecycleFixture();
        fixture.handler.initialize();
        fixture.handler.handleCommand(fixture.channelUID, new org.openhab.core.library.types.DecimalType(7));
        ArgumentCaptor<org.openhab.core.io.transport.modbus.ModbusWriteCallback> responses = ArgumentCaptor
                .forClass(org.openhab.core.io.transport.modbus.ModbusWriteCallback.class);
        ArgumentCaptor<org.openhab.core.io.transport.modbus.ModbusFailureCallback> failures = ArgumentCaptor
                .forClass(org.openhab.core.io.transport.modbus.ModbusFailureCallback.class);
        verify(fixture.communication).submitOneTimeWrite(any(), responses.capture(), failures.capture());
        fixture.handler.dispose();
        fixture.handler.initialize();
        org.mockito.Mockito.clearInvocations(fixture.callback, fixture.communication);

        responses.getValue().handle(mock(org.openhab.core.io.transport.modbus.AsyncModbusWriteResult.class));
        var failure = mock(org.openhab.core.io.transport.modbus.AsyncModbusFailure.class);
        org.mockito.Mockito.when(failure.getCause()).thenReturn(new IllegalStateException("old write"));
        failures.getValue().handle(failure);

        org.mockito.Mockito.verifyNoInteractions(fixture.callback, fixture.communication);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    public void requiresAFreshPollForBitWritesAfterReinitialization(boolean disposeFirst)
            throws EndpointNotInitializedException {
        LifecycleFixture fixture = new LifecycleFixture("holding", "Number",
                java.util.Map.of("address", "0", "valueType", "uint16", "writeStart", "0.0", "writeValueType", "bit"));
        fixture.handler.initialize();
        fixture.pollRegisters(2);
        if (disposeFirst) {
            fixture.handler.dispose();
        }
        fixture.handler.initialize();

        assertEquals(org.openhab.binding.modbus.internal.handler.Poller2WriteResult.INVALID_COMMAND,
                fixture.handler.submitActionWrite(org.openhab.binding.modbus.internal.handler.Poller2WriteRequest
                        .holding("value", org.openhab.core.library.types.OnOffType.ON)));
        fixture.pollRegisters(4);
        assertEquals(org.openhab.binding.modbus.internal.handler.Poller2WriteResult.ACCEPTED,
                fixture.handler.submitActionWrite(org.openhab.binding.modbus.internal.handler.Poller2WriteRequest
                        .holding("value", org.openhab.core.library.types.OnOffType.ON)));
        fixture.handler.dispose();
    }

    @Test
    public void cachedRefreshPublishesStateWithoutReauthorizingConsumedBitWrites()
            throws EndpointNotInitializedException {
        LifecycleFixture fixture = new LifecycleFixture("holding", "Number",
                java.util.Map.of("address", "0", "valueType", "uint16", "writeStart", "0.0", "writeValueType", "bit",
                        "updateUnchangedValuesEveryMillis", 1000));
        fixture.handler.initialize();
        fixture.pollRegisters(2);
        var observed = fixture.handler.getObservation().orElseThrow();
        var command = org.openhab.binding.modbus.internal.handler.Poller2WriteRequest.holding("value",
                org.openhab.core.library.types.OnOffType.ON);
        assertEquals(org.openhab.binding.modbus.internal.handler.Poller2WriteResult.ACCEPTED,
                fixture.handler.submitActionWrite(command));
        org.mockito.Mockito.clearInvocations(fixture.callback);

        fixture.handler.handleCommand(fixture.channelUID, RefreshType.REFRESH);

        verify(fixture.callback).stateUpdated(fixture.channelUID, new org.openhab.core.library.types.DecimalType(2));
        verify(fixture.communication, org.mockito.Mockito.never()).submitOneTimePoll(any(), any(), any());
        assertEquals(observed, fixture.handler.getObservation().orElseThrow());
        assertEquals(org.openhab.binding.modbus.internal.handler.Poller2WriteResult.INVALID_COMMAND,
                fixture.handler.submitActionWrite(command));
        fixture.pollRegisters(4);
        assertEquals(org.openhab.binding.modbus.internal.handler.Poller2WriteResult.ACCEPTED,
                fixture.handler.submitActionWrite(command));
        var writes = ArgumentCaptor.forClass(org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprint.class);
        verify(fixture.communication, times(2)).submitOneTimeWrite(writes.capture(), any(), any());
        assertEquals(5, ((org.openhab.core.io.transport.modbus.ModbusWriteRegisterRequestBlueprint) writes.getValue())
                .getRegisters().getRegister(0));
        fixture.handler.dispose();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "coil", "discrete" })
    public void publishesContactStatesForAcceptedContactChannels(String type) throws EndpointNotInitializedException {
        LifecycleFixture fixture = new LifecycleFixture(type, "Contact", java.util.Map.of("address", "0"));
        fixture.handler.initialize();
        var reads = ArgumentCaptor.forClass(org.openhab.core.io.transport.modbus.ModbusReadCallback.class);
        verify(fixture.communication).registerRegularPoll(any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), reads.capture(), any());
        var result = mock(org.openhab.core.io.transport.modbus.AsyncModbusReadResult.class);
        org.mockito.Mockito.when(result.getRegisters()).thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(result.getBits())
                .thenReturn(java.util.Optional.of(new org.openhab.core.io.transport.modbus.BitArray(true)));
        reads.getValue().handle(result);
        verify(fixture.callback).stateUpdated(fixture.channelUID, org.openhab.core.library.types.OpenClosedType.OPEN);

        org.mockito.Mockito.when(result.getBits())
                .thenReturn(java.util.Optional.of(new org.openhab.core.io.transport.modbus.BitArray(false)));
        reads.getValue().handle(result);
        verify(fixture.callback).stateUpdated(fixture.channelUID, org.openhab.core.library.types.OpenClosedType.CLOSED);
        fixture.handler.dispose();
    }

    @Test
    public void observesOnlyFreshDecodedUnlinkedCandidatePolls() throws EndpointNotInitializedException {
        LifecycleFixture fixture = new LifecycleFixture();
        fixture.handler.initialize();
        org.junit.jupiter.api.Assertions.assertTrue(fixture.handler.getObservation().isEmpty());
        java.time.Instant stagedAt = java.time.Instant.now();
        fixture.pollRegisters(42);
        var observed = fixture.handler.getObservation().orElseThrow();
        assertEquals(fixture.handler.getThing().getUID(), observed.thingUID());
        assertEquals(new org.openhab.core.library.types.DecimalType(42), observed.states().get(fixture.channelUID));
        org.junit.jupiter.api.Assertions.assertFalse(observed.observedAt().isBefore(stagedAt));
        org.junit.jupiter.api.Assertions.assertFalse(observed.configurationSignature().isBlank());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> observed.states().clear());
        org.mockito.Mockito.clearInvocations(fixture.callback);
        fixture.handler.handleCommand(fixture.channelUID, RefreshType.REFRESH);
        verify(fixture.callback).stateUpdated(fixture.channelUID, new org.openhab.core.library.types.DecimalType(42));
        assertEquals(observed, fixture.handler.getObservation().orElseThrow());
        fixture.handler.dispose();
    }

    @Test
    public void clearsCandidateObservationAcrossLifecycles() throws EndpointNotInitializedException {
        LifecycleFixture fixture = new LifecycleFixture();
        fixture.handler.initialize();
        fixture.pollRegisters(42);
        var old = fixture.handler.getObservation().orElseThrow();
        fixture.handler.initialize();
        org.junit.jupiter.api.Assertions.assertTrue(fixture.handler.getObservation().isEmpty());
        fixture.pollRegisters(43);
        var fresh = fixture.handler.getObservation().orElseThrow();
        org.junit.jupiter.api.Assertions.assertNotEquals(old.generation(), fresh.generation());
        assertEquals(new org.openhab.core.library.types.DecimalType(43), fresh.states().get(fixture.channelUID));
        fixture.handler.dispose();
        org.junit.jupiter.api.Assertions.assertTrue(fixture.handler.getObservation().isEmpty());
    }

    @Test
    public void clearsCandidateObservationOnReadFailure() throws EndpointNotInitializedException {
        LifecycleFixture fixture = new LifecycleFixture();
        fixture.handler.initialize();
        fixture.pollRegisters(42);
        var failures = ArgumentCaptor.forClass(org.openhab.core.io.transport.modbus.ModbusFailureCallback.class);
        verify(fixture.communication).registerRegularPoll(any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any(), failures.capture());
        var failure = mock(org.openhab.core.io.transport.modbus.AsyncModbusFailure.class);
        org.mockito.Mockito.when(failure.getCause()).thenReturn(new IllegalStateException("read failure"));
        failures.getValue().handle(failure);
        org.junit.jupiter.api.Assertions.assertTrue(fixture.handler.getObservation().isEmpty());
        fixture.pollRegisters(43);
        assertEquals(new org.openhab.core.library.types.DecimalType(43),
                fixture.handler.getObservation().orElseThrow().states().get(fixture.channelUID));
        fixture.handler.dispose();
    }

    private static class LifecycleFixture {
        final ChannelUID channelUID = new ChannelUID("modbus:poller2:poller2:value");
        final org.openhab.core.io.transport.modbus.ModbusCommunicationInterface communication = mock(
                org.openhab.core.io.transport.modbus.ModbusCommunicationInterface.class);
        final ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        final TestHandler handler;

        private static class TestHandler extends ModbusPoller2ThingHandler {
            private final Bridge endpoint;

            TestHandler(Bridge bridge, Bridge endpoint) {
                super(bridge, new ModbusChannelTypeProvider());
                this.endpoint = endpoint;
            }

            @Override
            protected @org.eclipse.jdt.annotation.Nullable Bridge getBridge() {
                return endpoint;
            }
        }

        LifecycleFixture() throws EndpointNotInitializedException {
            this("holding", "Number", java.util.Map.of("address", "0", "valueType", "uint16", "writeStart", "0",
                    "writeValueType", "uint16"));
        }

        void pollRegisters(int value) {
            var reads = ArgumentCaptor.forClass(org.openhab.core.io.transport.modbus.ModbusReadCallback.class);
            verify(communication, org.mockito.Mockito.atLeastOnce()).registerRegularPoll(any(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), reads.capture(),
                    any());
            var result = mock(org.openhab.core.io.transport.modbus.AsyncModbusReadResult.class);
            org.mockito.Mockito.when(result.getRegisters()).thenReturn(
                    java.util.Optional.of(new org.openhab.core.io.transport.modbus.ModbusRegisterArray(value)));
            org.mockito.Mockito.when(result.getBits()).thenReturn(java.util.Optional.empty());
            reads.getValue().handle(result);
        }

        LifecycleFixture(String type, String itemType, java.util.Map<String, Object> channelConfiguration)
                throws EndpointNotInitializedException {
            Bridge bridge = BridgeBuilder.create(ModbusBindingConstantsInternal.THING_TYPE_MODBUS_POLLER2, "poller2")
                    .withConfiguration(new Configuration(java.util.Map.of("type", type, "start", 0, "length", 1,
                            "refresh", 1000, "cacheMillis", Integer.MAX_VALUE)))
                    .withChannel(ChannelBuilder.create(channelUID).withAcceptedItemType(itemType)
                            .withType(new ChannelTypeUID("modbus", "number"))
                            .withConfiguration(new Configuration(channelConfiguration)).build())
                    .build();
            Bridge endpoint = mock(Bridge.class);
            org.openhab.binding.modbus.internal.handler.ModbusTcpThingHandler endpointHandler = mock(
                    org.openhab.binding.modbus.internal.handler.ModbusTcpThingHandler.class);
            org.mockito.Mockito.when(endpoint.getStatus()).thenReturn(org.openhab.core.thing.ThingStatus.ONLINE);
            org.mockito.Mockito.when(endpoint.getHandler()).thenReturn(endpointHandler);
            org.mockito.Mockito.when(endpointHandler.getCommunicationInterface()).thenReturn(communication);
            org.mockito.Mockito.when(endpointHandler.getSlaveId()).thenReturn(1);
            org.mockito.Mockito
                    .when(communication.registerRegularPoll(any(), org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyLong(), any(), any()))
                    .thenReturn(mock(org.openhab.core.io.transport.modbus.PollTask.class));
            handler = new TestHandler(bridge, endpoint);
            handler.setCallback(callback);
        }
    }

    @Test
    public void routesRefreshCommandsThroughThePollerRefreshLifecycle() {
        ModbusPoller2ThingHandler handler = spy(
                new ModbusPoller2ThingHandler(mock(Bridge.class), new ModbusChannelTypeProvider()));

        handler.handleCommand(mock(ChannelUID.class), RefreshType.REFRESH);

        verify(handler).refresh();
    }
}
