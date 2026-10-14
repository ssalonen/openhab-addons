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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * Single binding-owned migration operation boundary for console and future REST consumers.
 * All operations, including source capture, are serialized on this singleton; consumers must not construct workflows.
 *
 * @author Sami Salonen - Initial contribution
 */
@Component(service = ModbusMigrationService.class, scope = ServiceScope.SINGLETON)
@NonNullByDefault
public final class ModbusMigrationService {
    private final ManagedModbusMigrationAdapter adapter;
    private final ModbusMigrationWorkflow workflow;

    @Activate
    public ModbusMigrationService(@Reference ThingRegistry thingRegistry,
            @Reference ItemChannelLinkRegistry linkRegistry, @Reference ManagedThingProvider thingProvider,
            @Reference StorageService storageService) {
        adapter = new ManagedModbusMigrationAdapter(thingRegistry, linkRegistry, thingProvider);
        MigrationJournal journal = new MigrationJournal(storageService.getStorage("modbus-migration-journal"));
        workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(thingProvider, linkRegistry, journal, thingRegistry), journal);
    }

    /**
     * Captures and journals a review, without modifying Things or links. A future HTTP capture must use POST, not GET.
     */
    public synchronized Preview preview() {
        var snapshot = adapter.snapshot();
        return new Preview(workflow.preview(snapshot.pollers(), snapshot.data(), snapshot.manualWork()), List.of());
    }

    /** Reads an existing review and workflow status only; never captures, stages, validates or writes the journal. */
    public synchronized MigrationWorkflowReport report(String identity) {
        return workflow.report(identity);
    }

    public synchronized void apply(String identity) {
        var snapshot = adapter.snapshot();
        workflow.apply(identity, snapshot.pollers(), snapshot.data(), snapshot.manualWork());
    }

    public synchronized MigrationValidation.Observation validate(String identity, String sourcePollerUid) {
        return workflow.validate(identity, sourcePollerUid);
    }

    public synchronized void cutover(String identity, String sourcePollerUid) {
        var snapshot = adapter.snapshot();
        workflow.cutover(identity, sourcePollerUid, snapshot.pollers(), snapshot.data(), snapshot.manualWork());
    }

    public synchronized void revert(String identity, String itemName, String sourceChannelUid) {
        workflow.revert(identity, itemName, sourceChannelUid);
    }

    public synchronized void rollback(String identity) {
        workflow.rollback(identity);
    }

    /** Safe plan with all manual work. The legacy outer manualWork field stays empty to avoid duplicate rendering. */
    public record Preview(MigrationPreview plan, List<ManualMigrationWork> manualWork) {
        public Preview {
            manualWork = List.copyOf(manualWork);
        }
    }
}
