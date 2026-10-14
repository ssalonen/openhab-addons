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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.ui.tiles.Tile;
import org.osgi.service.component.annotations.Component;

/**
 * Supported Core launcher entry for the add-on-hosted view, not a native Main UI settings component.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
@Component(service = Tile.class)
public class ModbusMigrationTile implements Tile {
    @Override
    public String getName() {
        return "Modbus Migration";
    }

    @Override
    public String getUrl() {
        return "../modbus/migration/";
    }

    @Override
    public String getImageUrl() {
        return "../modbus/migration/tile.svg";
    }

    @Override
    public @Nullable String getOverlay() {
        return "html5";
    }
}
