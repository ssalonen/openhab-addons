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

import java.util.function.BooleanSupplier;

import org.eclipse.jdt.annotation.NonNullByDefault;

/** Executes only the distinct state changes selected by an explicit migration workflow action. */
@NonNullByDefault
public interface MigrationApplier {
    void createTargets(MigrationPreview preview);

    void removeTargets(MigrationPreview preview);

    default void cutover(MigrationPreview preview, MigrationGroup group) {
        throw new UnsupportedOperationException("cutover unavailable");
    }

    default void cutover(MigrationPreview preview, MigrationGroup group, BooleanSupplier authorization) {
        if (!authorization.getAsBoolean()) {
            throw new IllegalStateException("Migration read authorization expired");
        }
        cutover(preview, group);
    }

    default void cutover(MigrationPreview preview, MigrationGroup group, BooleanSupplier authorization,
            Runnable completion) {
        cutover(preview, group, authorization);
        completion.run();
        if (!authorization.getAsBoolean()) {
            throw new IllegalStateException("Migration read authorization expired");
        }
    }

    default void revertLink(MigrationPreview preview, MigrationLink link) {
        throw new UnsupportedOperationException("link revert unavailable");
    }

    void removeLegacy(MigrationPreview preview);
}
