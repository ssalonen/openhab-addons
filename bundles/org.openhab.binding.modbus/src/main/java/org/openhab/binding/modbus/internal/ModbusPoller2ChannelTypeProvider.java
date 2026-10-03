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
package org.openhab.binding.modbus.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.ModbusBindingConstants;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeBuilder;
import org.openhab.core.thing.type.ChannelTypeProvider;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.osgi.service.component.annotations.Component;

/**
 * Provides generated ChannelTypes for user-declared poller2 channels.
 *
 * The native YAML model remains the source of a channel's accepted Item type. These generated ChannelTypes only supply
 * the runtime type reference required by current Main UI channel and profile screens.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
@Component(service = { ChannelTypeProvider.class, ModbusPoller2ChannelTypeProvider.class })
public class ModbusPoller2ChannelTypeProvider implements ChannelTypeProvider {
    private static final String SHIM_PREFIX = "poller2-shim-";
    private static final String[] NUMBER_DIMENSIONS = { "Angle", "Area", "CatalyticActivity", "DataAmount",
            "DataTransferRate", "Density", "ElectricCapacitance", "ElectricCharge", "ElectricConductance",
            "ElectricConductivity", "ElectricCurrent", "ElectricInductance", "ElectricPotential", "ElectricResistance",
            "Energy", "Force", "Frequency", "Illuminance", "Intensity", "Length", "LuminousFlux", "LuminousIntensity",
            "MagneticFlux", "MagneticFluxDensity", "Mass", "Power", "Pressure", "RadiationDoseAbsorbed",
            "RadiationDoseEffective", "Radioactivity", "SolidAngle", "Speed", "Temperature", "Time", "Volume",
            "VolumetricFlowRate" };

    private final Map<ChannelTypeUID, ChannelType> channelTypes = createChannelTypes();
    private final Map<String, ChannelTypeUID> channelTypeUIDsByItemType = createItemTypeIndex(channelTypes.values());

    @Override
    public Collection<ChannelType> getChannelTypes(@Nullable Locale locale) {
        return channelTypes.values();
    }

    @Override
    public @Nullable ChannelType getChannelType(ChannelTypeUID channelTypeUID, @Nullable Locale locale) {
        return channelTypes.get(channelTypeUID);
    }

    public @Nullable ChannelTypeUID getGeneratedChannelTypeUID(@Nullable String itemType) {
        return channelTypeUIDsByItemType.get(itemType);
    }

    private static Map<ChannelTypeUID, ChannelType> createChannelTypes() {
        Map<ChannelTypeUID, ChannelType> result = new LinkedHashMap<>();
        add(result, "string", "String", "String");
        add(result, "switch", "Switch", "Switch");
        add(result, "number", "Number", "Number");
        add(result, "number-dimensionless", "Dimensionless Number", "Number:Dimensionless");
        for (String dimension : NUMBER_DIMENSIONS) {
            add(result, "number-" + dimension.toLowerCase(Locale.ROOT), dimension, "Number:" + dimension);
        }
        add(result, "rollershutter", "Rollershutter", "Rollershutter");
        return result;
    }

    private static void add(Map<ChannelTypeUID, ChannelType> channelTypes, String id, String label, String itemType) {
        ChannelTypeUID uid = new ChannelTypeUID(ModbusBindingConstants.BINDING_ID, SHIM_PREFIX + id);
        channelTypes.put(uid, ChannelTypeBuilder.state(uid, label, itemType).build());
    }

    private static Map<String, ChannelTypeUID> createItemTypeIndex(Collection<ChannelType> channelTypes) {
        Map<String, ChannelTypeUID> result = new LinkedHashMap<>();
        channelTypes.forEach(channelType -> {
            String itemType = channelType.getItemType();
            if (itemType != null) {
                result.put(itemType, channelType.getUID());
            }
        });
        return result;
    }
}
