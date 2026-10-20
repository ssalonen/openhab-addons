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
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.link.ManagedItemChannelLinkProvider;
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
    private final ProviderOwnedMigrationAdapter providerAdapter;
    private final ModbusMigrationWorkflow workflow;

    public ModbusMigrationService(ThingRegistry thingRegistry, ItemChannelLinkRegistry linkRegistry,
            ManagedThingProvider thingProvider, StorageService storageService) {
        this(thingRegistry, linkRegistry, thingProvider, storageService,
                new LiveModbusMigrationValidation(thingRegistry));
    }

    public ModbusMigrationService(ThingRegistry thingRegistry, ItemChannelLinkRegistry linkRegistry,
            ManagedThingProvider thingProvider, StorageService storageService, MigrationValidation validation) {
        this(thingRegistry, linkRegistry, thingProvider, storageService, validation, null);
    }

    @Activate
    public ModbusMigrationService(@Reference ThingRegistry thingRegistry,
            @Reference ItemChannelLinkRegistry linkRegistry, @Reference ManagedThingProvider thingProvider,
            @Reference StorageService storageService, @Reference MigrationValidation validation,
            @Reference @Nullable ManagedItemChannelLinkProvider linkProvider) {
        adapter = new ManagedModbusMigrationAdapter(thingRegistry, linkRegistry, thingProvider);
        providerAdapter = new ProviderOwnedMigrationAdapter(thingRegistry, linkRegistry);
        MigrationJournal journal = new MigrationJournal(storageService.getStorage("modbus-migration-journal"));
        workflow = new ModbusMigrationWorkflow(new ModbusMigrationPlanner(),
                new ManagedModbusMigrationApplier(thingProvider, linkRegistry, journal, thingRegistry, linkProvider),
                (preview, group, stagedAt) -> {
                    requireCurrentSources(preview.identity());
                    var observation = validation.observe(preview, group, stagedAt);
                    requireCurrentSources(preview.identity());
                    return new MigrationValidation.Observation(observation.previewIdentity(),
                            observation.sourcePollerUid(), observation.observedAt(), observation.outcome(),
                            observation.comparisons(), () -> {
                                if (!observation.authorization().getAsBoolean()) {
                                    return false;
                                }
                                try {
                                    requireCurrentSources(preview.identity());
                                    return true;
                                } catch (IllegalArgumentException | IllegalStateException e) {
                                    return false;
                                }
                            });
                }, journal);
    }

    /**
     * Captures and journals a review, without modifying Things or links. A future HTTP capture must use POST, not GET.
     */
    public synchronized Preview preview() {
        var snapshot = adapter.snapshot();
        return new Preview(workflow.preview(snapshot.pollers(), snapshot.data(), snapshot.manualWork()), List.of());
    }

    /** Returns a detached provider-owned file proposal without journal, Thing or link writes. */
    public synchronized ProviderOwnedMigrationPreview providerPreview() {
        return providerAdapter.preview();
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

    private void requireCurrentSources(String identity) {
        var snapshot = adapter.snapshot(workflow.stagedTargets(identity));
        workflow.requireCurrentSources(identity, snapshot.pollers(), snapshot.data(), snapshot.manualWork());
    }

    public synchronized void cutover(String identity, String sourcePollerUid) {
        var snapshot = adapter.snapshot(workflow.stagedTargets(identity));
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
