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

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Public, opt-in migration workflow. Preview is the only operation that can inspect configuration without a supplied
 * preview identity; staging, validation, cutover, rollback, and cleanup are separate actions. State is runtime-local.
 * 
 * @author Sami Salonen - Staged migration and fail-closed validation
 */
@NonNullByDefault
public final class ModbusMigrationWorkflow {
    private record Sources(List<LegacyPoller> pollers, List<LegacyData> data) {
    }

    private final Map<String, Sources> sources = new HashMap<>();
    private final MigrationValidation validation;
    private final Map<String, Instant> stagedAt = new HashMap<>();
    private final Set<String> switched = new HashSet<>();
    private final ModbusMigrationPlanner planner;
    private final MigrationApplier applier;
    private final Map<String, MigrationPreview> previews = new HashMap<>();
    private final Set<String> recovery = new HashSet<>();
    private final Set<String> applied = new HashSet<>();

    public ModbusMigrationWorkflow(ModbusMigrationPlanner planner, MigrationApplier applier) {
        this(planner, applier, (preview, group, createdAt) -> new MigrationValidation.Observation(preview.identity(),
                group.sourcePollerUid(), Instant.EPOCH, MigrationValidation.Outcome.UNAVAILABLE));
    }

    public ModbusMigrationWorkflow(ModbusMigrationPlanner planner, MigrationApplier applier,
            MigrationValidation validation) {
        this.validation = validation;
        this.planner = planner;
        this.applier = applier;
    }

    public synchronized MigrationPreview preview(List<LegacyPoller> pollers, List<LegacyData> dataThings) {
        MigrationPreview preview = planner.preview(pollers, dataThings);
        previews.put(preview.identity(), preview);
        sources.putIfAbsent(preview.identity(), new Sources(List.copyOf(pollers), List.copyOf(dataThings)));
        return preview;
    }

    public synchronized void apply(String previewIdentity, List<LegacyPoller> pollers, List<LegacyData> dataThings) {
        MigrationPreview currentPreview = planner.preview(pollers, dataThings);
        if (!previewIdentity.equals(currentPreview.identity()) || !previews.containsKey(previewIdentity)) {
            throw new IllegalArgumentException("apply requires the exact, current preview identity");
        }
        if (!applied.contains(previewIdentity)) {
            if (recovery.contains(previewIdentity)) {
                throw new IllegalStateException("Stage recovery required; retry rollback");
            }
            try {
                applier.createTargets(java.util.Objects.requireNonNull(previews.get(previewIdentity)));
            } catch (ManagedModbusMigrationApplier.IncompleteStageException e) {
                recovery.add(previewIdentity);
                throw e;
            }
            stagedAt.put(previewIdentity, Instant.now());
            applied.add(previewIdentity);
        }
    }

    public synchronized MigrationValidation.Observation validate(String previewIdentity, String sourcePollerUid) {
        MigrationPreview preview = appliedPreview(previewIdentity);
        MigrationGroup group = group(preview, sourcePollerUid);
        Instant createdAt = stagedAt.get(previewIdentity);
        if (createdAt == null || recovery.contains(previewIdentity)) {
            throw new IllegalStateException("Candidate staging incomplete; retry rollback");
        }
        MigrationValidation.Observation observation = validation.observe(preview, group, createdAt);
        if (!previewIdentity.equals(observation.previewIdentity())
                || !sourcePollerUid.equals(observation.sourcePollerUid())
                || !observation.observedAt().isAfter(createdAt) || observation.observedAt().isAfter(Instant.now())) {
            return new MigrationValidation.Observation(previewIdentity, sourcePollerUid, Instant.EPOCH,
                    MigrationValidation.Outcome.UNAVAILABLE);
        }
        return observation;
    }

    public synchronized void cutover(String previewIdentity, String sourcePollerUid, List<LegacyPoller> pollers,
            List<LegacyData> dataThings) {
        MigrationPreview preview = appliedPreview(previewIdentity);
        Sources reviewedSources = java.util.Objects.requireNonNull(sources.get(previewIdentity));
        Set<String> removedLinks = new HashSet<>();
        preview.groups().stream()
                .filter(candidate -> switched.contains(previewIdentity + ":" + candidate.sourcePollerUid()))
                .flatMap(candidate -> candidate.links().stream())
                .forEach(link -> removedLinks.add(link.itemName() + " -> " + link.sourceChannelUid()));
        List<LegacyData> expectedData = reviewedSources.data().stream()
                .map(data -> new LegacyData(data.uid(), data.pollerUid(), data.configuration(), data.links().stream()
                        .filter(link -> !removedLinks.contains(link.itemName() + " -> " + link.channelUid())).toList()))
                .toList();
        String expectedIdentity = planner.preview(reviewedSources.pollers(), expectedData).identity();
        if (!expectedIdentity.equals(planner.preview(pollers, dataThings).identity())) {
            throw new IllegalArgumentException("cutover requires the exact, current reviewed source configuration");
        }
        String key = previewIdentity + ":" + sourcePollerUid;
        if (switched.contains(key)) {
            return;
        }
        if (validate(previewIdentity, sourcePollerUid).outcome() != MigrationValidation.Outcome.MATCH) {
            throw new IllegalStateException("Fresh candidate validation is not MATCH; cutover unavailable");
        }
        applier.cutover(preview, group(preview, sourcePollerUid));
        switched.add(key);
    }

    private MigrationGroup group(MigrationPreview preview, String sourcePollerUid) {
        return preview.groups().stream().filter(group -> group.sourcePollerUid().equals(sourcePollerUid)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown reviewed poller group"));
    }

    public synchronized void rollback(String previewIdentity) {
        MigrationPreview preview = appliedPreview(previewIdentity);
        applier.removeTargets(preview);
        applied.remove(previewIdentity);
        recovery.remove(previewIdentity);
        stagedAt.remove(previewIdentity);
        switched.removeIf(key -> key.startsWith(previewIdentity + ":"));
    }

    public synchronized void cleanupLegacy(String previewIdentity) {
        MigrationPreview preview = appliedPreview(previewIdentity);
        applier.removeLegacy(preview);
    }

    private MigrationPreview appliedPreview(String previewIdentity) {
        if (!applied.contains(previewIdentity) && !recovery.contains(previewIdentity)) {
            throw new IllegalStateException("the preview has not been applied");
        }
        MigrationPreview preview = previews.get(previewIdentity);
        if (preview == null) {
            throw new IllegalArgumentException("unknown preview identity");
        }
        return preview;
    }
}
