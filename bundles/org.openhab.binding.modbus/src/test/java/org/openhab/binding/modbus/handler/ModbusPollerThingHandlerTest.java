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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.binding.modbus.internal.ModbusBindingConstantsInternal;
import org.openhab.binding.modbus.internal.handler.ModbusTcpThingHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.ModbusCommunicationInterface;
import org.openhab.core.io.transport.modbus.ModbusReadCallback;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.io.transport.modbus.PollTask;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;

/**
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
class ModbusPollerThingHandlerTest {
    @Test
    void refreshKeepsRequestTransportAndCallbacksFromTheSameLifecycle() throws Exception {
        Fixture fixture = new Fixture(0);
        fixture.handler.initialize();
        var oldRead = fixture.readCallback();
        var oldRequest = fixture.handler.getRequest();
        var result = fixture.result();
        oldRead.handle(result);
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(result.getRegisters()).thenAnswer(invocation -> {
            captured.countDown();
            assertTrue(resume.await(5, TimeUnit.SECONDS));
            return Optional.of(fixture.registers);
        });
        var executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "modbus-refresh-lifecycle-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var refresh = executor.submit(fixture.handler::refresh);
            assertTrue(captured.await(5, TimeUnit.SECONDS));
            var lifecycleThread = new java.util.concurrent.atomic.AtomicReference<@Nullable Thread>();
            var reinitialize = executor.submit(() -> {
                lifecycleThread.set(Thread.currentThread());
                fixture.handler.initialize();
            });
            awaitBlockedOrCompleted(reinitialize, lifecycleThread);
            resume.countDown();
            refresh.get(5, TimeUnit.SECONDS);
            reinitialize.get(5, TimeUnit.SECONDS);
            // Even a refresh captured before reinitialization must never combine the old request with the new endpoint.
            verify(fixture.newCommunication, never()).submitOneTimePoll(any(), any(), any());
            var callbacks = ArgumentCaptor.forClass(ModbusReadCallback.class);
            var failures = ArgumentCaptor.forClass(org.openhab.core.io.transport.modbus.ModbusFailureCallback.class);
            verify(fixture.oldCommunication).submitOneTimePoll(org.mockito.ArgumentMatchers.same(oldRequest),
                    callbacks.capture(), failures.capture());
            org.junit.jupiter.api.Assertions.assertNotSame(oldRead, callbacks.getValue(),
                    "one-time reads capture their own child authorization");
            assertSame(callbacks.getValue(), failures.getValue());
            clearInvocations(fixture.callback);
            callbacks.getValue().handle(fixture.result());
            verifyNoInteractions(fixture.callback);
            assertNull(fixture.handler.getLastPolledDataCache().get());
        } finally {
            resume.countDown();
            executor.shutdownNow();
            fixture.handler.dispose();
        }
    }

    @Test
    void cachedPublicationIsSerializedWithLifecycleChanges() throws Exception {
        Fixture fixture = new Fixture(Integer.MAX_VALUE);
        fixture.handler.initialize();
        fixture.readCallback().handle(fixture.result());
        fixture.handler.refresh();
        assertTrue(fixture.handler.cachedPublicationUnderMonitor);
        verify(fixture.oldCommunication, never()).submitOneTimePoll(any(), any(), any());
        fixture.handler.dispose();
    }

    @Test
    void cachedPublicationCannotDecodeUsingAReinitializedLifecycle() throws Exception {
        Fixture fixture = new Fixture(Integer.MAX_VALUE);
        fixture.handler.initialize();
        fixture.readCallback().handle(fixture.result());
        int oldDecoder = fixture.handler.decoderGeneration;
        CountDownLatch publication = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        fixture.handler.beforeCachedDecode = () -> {
            publication.countDown();
            try {
                assertTrue(resume.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        };
        var executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "modbus-refresh-lifecycle-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var refresh = executor.submit(fixture.handler::refresh);
            assertTrue(publication.await(5, TimeUnit.SECONDS));
            var lifecycleThread = new java.util.concurrent.atomic.AtomicReference<@Nullable Thread>();
            var reinitialize = executor.submit(() -> {
                lifecycleThread.set(Thread.currentThread());
                fixture.handler.initialize();
            });
            awaitBlockedOrCompleted(reinitialize, lifecycleThread);
            resume.countDown();
            refresh.get(5, TimeUnit.SECONDS);
            reinitialize.get(5, TimeUnit.SECONDS);
            assertEquals(oldDecoder, fixture.handler.cachedDecoderGeneration);
            assertEquals(oldDecoder + 1, fixture.handler.decoderGeneration);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            fixture.handler.dispose();
        }
    }

    private static void awaitBlockedOrCompleted(java.util.concurrent.Future<?> transition,
            java.util.concurrent.atomic.AtomicReference<@Nullable Thread> lifecycleThread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!transition.isDone()) {
            Thread thread = lifecycleThread.get();
            if (thread != null && thread.getState() == Thread.State.BLOCKED) {
                return;
            }
            assertTrue(System.nanoTime() < deadline);
            Thread.yield();
        }
    }

    private static class TestHandler extends ModbusPollerThingHandler {
        private final Bridge endpoint;
        boolean cachedPublicationUnderMonitor;
        int decoderGeneration;
        int cachedDecoderGeneration;
        Runnable beforeCachedDecode = () -> {
        };

        TestHandler(Bridge bridge, Bridge endpoint) {
            super(bridge);
            this.endpoint = endpoint;
        }

        @Override
        protected @Nullable Bridge getBridge() {
            return endpoint;
        }

        @Override
        public synchronized void initialize() {
            decoderGeneration++;
            super.initialize();
        }

        @Override
        protected void onPollResult(AsyncModbusReadResult result, boolean cached) {
            if (cached) {
                beforeCachedDecode.run();
                cachedDecoderGeneration = decoderGeneration;
                cachedPublicationUnderMonitor = Thread.holdsLock(this);
            }
        }
    }

    private static class Fixture {
        final ModbusCommunicationInterface oldCommunication = mock(ModbusCommunicationInterface.class);
        final ModbusCommunicationInterface newCommunication = mock(ModbusCommunicationInterface.class);
        final ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        final ModbusRegisterArray registers = new ModbusRegisterArray(42);
        final TestHandler handler;

        Fixture(int cacheMillis) throws EndpointNotInitializedException {
            Bridge bridge = BridgeBuilder.create(ModbusBindingConstantsInternal.THING_TYPE_MODBUS_POLLER, "poller")
                    .withConfiguration(new Configuration(Map.of("type", "holding", "start", 0, "length", 1, "refresh",
                            1000, "cacheMillis", cacheMillis)))
                    .build();
            Bridge endpoint = mock(Bridge.class);
            ModbusTcpThingHandler endpointHandler = mock(ModbusTcpThingHandler.class);
            when(endpoint.getStatus()).thenReturn(ThingStatus.ONLINE);
            when(endpoint.getHandler()).thenReturn(endpointHandler);
            when(endpointHandler.getCommunicationInterface()).thenReturn(oldCommunication, newCommunication);
            when(endpointHandler.getSlaveId()).thenReturn(1, 2);
            handler = new TestHandler(bridge, endpoint);
            handler.setCallback(callback);
            for (var communication : new ModbusCommunicationInterface[] { oldCommunication, newCommunication }) {
                when(communication.registerRegularPoll(any(), anyLong(), anyLong(), any(), any()))
                        .thenReturn(mock(PollTask.class));
                doAnswer(invocation -> {
                    assertFalse(Thread.holdsLock(handler));
                    return null;
                }).when(communication).submitOneTimePoll(any(), any(), any());
            }
        }

        ModbusReadCallback readCallback() {
            var reads = ArgumentCaptor.forClass(ModbusReadCallback.class);
            verify(oldCommunication).registerRegularPoll(any(), anyLong(), anyLong(), reads.capture(), any());
            return reads.getValue();
        }

        AsyncModbusReadResult result() {
            var result = mock(AsyncModbusReadResult.class);
            when(result.getRegisters()).thenReturn(Optional.of(registers));
            when(result.getBits()).thenReturn(Optional.empty());
            return result;
        }
    }
}
