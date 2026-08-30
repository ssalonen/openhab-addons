/*
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.dynamicchannelprototype.internal;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Component;

/** Creates handlers for a Thing whose Channels are entirely configured by the user. */
@Component(service = ThingHandlerFactory.class)
public class DynamicChannelPrototypeHandlerFactory extends BaseThingHandlerFactory {
    public static final ThingTypeUID THING_TYPE = new ThingTypeUID("dynamicchannelprototype", "value");

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return THING_TYPE.equals(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        return new DynamicChannelPrototypeHandler(thing);
    }
}
