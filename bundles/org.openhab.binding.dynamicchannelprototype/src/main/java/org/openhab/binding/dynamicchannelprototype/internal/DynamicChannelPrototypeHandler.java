/*
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.dynamicchannelprototype.internal;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;

/**
 * Minimal handler for a configured Channel named {@code value}.
 *
 * The Channel's accepted Item type is deliberately supplied by configuration, not by this binding.
 */
public class DynamicChannelPrototypeHandler extends BaseThingHandler {
    private final AtomicReference<@Nullable Command> lastCommand = new AtomicReference<>();

    public DynamicChannelPrototypeHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        updateStatus(org.openhab.core.thing.ThingStatus.ONLINE);
    }

    public void emitPower(QuantityType<?> state) {
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
