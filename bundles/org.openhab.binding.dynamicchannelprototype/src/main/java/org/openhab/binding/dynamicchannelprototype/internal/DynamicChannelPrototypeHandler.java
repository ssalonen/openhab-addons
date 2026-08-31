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
package org.openhab.binding.dynamicchannelprototype.internal;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;

/**
 * Minimal handler for a configured Channel named {@code value}.
 *
 * The Channel's accepted Item type is deliberately supplied by configuration, not by this binding.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class DynamicChannelPrototypeHandler extends BaseThingHandler {
    private final AtomicReference<@Nullable Command> lastCommand = new AtomicReference<>();

    public DynamicChannelPrototypeHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.ONLINE);
    }

    public void emitPower(QuantityType<?> state) {
        updateState(new ChannelUID(getThing().getUID(), "value"), state);
    }

    public void emitRaw(DecimalType state) {
        updateState(new ChannelUID(getThing().getUID(), "value"), state);
    }

    public @Nullable Command getLastCommand() {
        return lastCommand.get();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if ("value".equals(channelUID.getId())) {
            lastCommand.set(command);
        }
    }
}
