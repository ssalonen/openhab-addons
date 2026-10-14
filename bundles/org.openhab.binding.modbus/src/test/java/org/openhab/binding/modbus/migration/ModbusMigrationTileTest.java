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

import org.junit.jupiter.api.Test;

/** @author Sami Salonen - Initial contribution */
class ModbusMigrationTileTest {
    @Test
    void linksToTheSameOriginHostedView() {
        var tile = new ModbusMigrationTile();
        assertEquals("Modbus Migration", tile.getName());
        assertEquals("../modbus/migration/", tile.getUrl());
        assertEquals("../modbus/migration/tile.svg", tile.getImageUrl());
        assertEquals("html5", tile.getOverlay());
    }
}
