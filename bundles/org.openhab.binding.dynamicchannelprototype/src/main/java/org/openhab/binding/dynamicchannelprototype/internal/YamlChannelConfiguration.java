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

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Loads the prototype's version-controlled dynamic Channel declaration.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public class YamlChannelConfiguration {
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final Map<String, ChannelDefinition> channels;

    private YamlChannelConfiguration(Map<String, ChannelDefinition> channels) {
        this.channels = channels;
    }

    public static YamlChannelConfiguration parse(InputStream yaml) throws IOException {
        Map<String, Map<String, ChannelDefinition>> document = YAML_MAPPER.readValue(yaml, new TypeReference<>() {
        });
        Map<String, ChannelDefinition> channels = document.get("channels");
        if (channels == null) {
            throw new IllegalArgumentException("YAML configuration must contain channels");
        }
        return new YamlChannelConfiguration(channels);
    }

    public Channel createChannel(ChannelUID channelUID) {
        ChannelDefinition definition = channels.get(channelUID.getId());
        if (definition == null) {
            throw new IllegalArgumentException("No YAML configuration for channel " + channelUID.getId());
        }
        return ChannelBuilder.create(channelUID, definition.acceptedItemType()).build();
    }

    /**
     * YAML declaration of one dynamically configured Channel.
     *
     * @author Sami Salonen - Initial contribution
     */
    public static class ChannelDefinition {
        public String itemType = "";
        public String itemDimension = "";

        public String acceptedItemType() {
            return itemDimension.isBlank() ? itemType : itemType + ":" + itemDimension;
        }
    }
}
