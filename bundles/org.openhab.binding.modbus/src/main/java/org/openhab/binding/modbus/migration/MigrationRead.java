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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingUID;

/**
 * A single migration-owned transport execution. Evidence never comes from periodic or cached responses.
 * The future fails on transport/decode failure; callers must also recheck lifecycle validity before use.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public record MigrationRead(CompletableFuture<Map<ThingUID, Poller2Observation>> completion, BooleanSupplier current) {
    public MigrationRead {
        BooleanSupplier lifecycle = current;
        current = () -> !completion.isCancelled() && !completion.isCompletedExceptionally() && lifecycle.getAsBoolean();
    }
}
