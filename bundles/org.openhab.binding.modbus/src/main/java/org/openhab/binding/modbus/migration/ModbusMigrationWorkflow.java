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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Public, opt-in migration workflow. Preview is the only operation that can inspect configuration without a supplied
 * preview identity; staging, validation, cutover, rollback, and cleanup are separate actions. Recovery state is
 * retained in Core storage when supplied.
 * 
 * @author Sami Salonen - Staged migration and fail-closed validation
 * @author Sami Salonen - Durable workflow restart recovery and independent revert
 * @author Sami Salonen - Read-only shared service workflow reports
 */
@NonNullByDefault
public final class ModbusMigrationWorkflow {
    private record Sources(List<LegacyPoller> pollers, List<LegacyData> data, List<ManualMigrationWork> providerWork) {
    }

    private final MigrationJournal journal;
    private final Map<String, Sources> sources = new HashMap<>();
    private final MigrationValidation validation;
    private final Map<String, Instant> stagedAt = new HashMap<>();
    private final Set<String> switched = new HashSet<>();
    private final ModbusMigrationPlanner planner;
    private final MigrationApplier applier;
    private final Map<String, MigrationPreview> previews = new HashMap<>();
    private final Set<String> recovery = new HashSet<>();
    private final Set<String> quarantined = new HashSet<>();
    private final Set<String> applied = new HashSet<>();

    public ModbusMigrationWorkflow(ModbusMigrationPlanner planner, MigrationApplier applier) {
        this(planner, applier, (preview, group, createdAt) -> new MigrationValidation.Observation(preview.identity(),
                group.sourcePollerUid(), Instant.EPOCH, MigrationValidation.Outcome.UNAVAILABLE));
    }

    public ModbusMigrationWorkflow(ModbusMigrationPlanner planner, MigrationApplier applier,
            MigrationValidation validation) {
        this(planner, applier, validation, new MigrationJournal());
    }

    ModbusMigrationWorkflow(ModbusMigrationPlanner planner, MigrationApplier applier, MigrationJournal journal) {
        this(planner, applier, (preview, group, createdAt) -> new MigrationValidation.Observation(preview.identity(),
                group.sourcePollerUid(), Instant.EPOCH, MigrationValidation.Outcome.UNAVAILABLE), journal);
    }

    ModbusMigrationWorkflow(ModbusMigrationPlanner planner, MigrationApplier applier, MigrationValidation validation,
            MigrationJournal journal) {
        this.journal = journal;
        for (MigrationJournal.Workflow entry : journal.workflows()) {
            String identity = entry.preview().identity();
            previews.put(identity, entry.preview());
            if (entry.reviewSafetyVersion() != 1) {
                quarantined.add(identity);
            }
            sources.put(identity, new Sources(entry.pollers(), entry.data(), entry.providerWork()));
            if (entry.outcome().equals("STAGED")) {
                applied.add(identity);
                stagedAt.put(identity, Instant.parse(entry.stagedAt()));
            } else if (entry.outcome().equals("STAGING") || entry.outcome().equals("RECOVERY")) {
                recovery.add(identity);
            }
            switched.addAll(entry.switched());
        }
        this.validation = validation;
        this.planner = planner;
        this.applier = applier;
    }

    public synchronized MigrationPreview preview(List<LegacyPoller> pollers, List<LegacyData> dataThings) {
        return preview(pollers, dataThings, List.of());
    }

    synchronized MigrationPreview preview(List<LegacyPoller> pollers, List<LegacyData> dataThings,
            List<ManualMigrationWork> providerWork) {
        MigrationPreview preview = planner.preview(pollers, dataThings, providerWork);
        MigrationJournal.Workflow snapshot = journal.snapshot(new MigrationJournal.Workflow(preview, pollers,
                dataThings, "PREVIEW", Instant.EPOCH.toString(), List.of(), providerWork));
        previews.putIfAbsent(preview.identity(), snapshot.preview());
        sources.putIfAbsent(preview.identity(),
                new Sources(snapshot.pollers(), snapshot.data(), snapshot.providerWork()));
        if (!applied.contains(preview.identity()) && !recovery.contains(preview.identity())) {
            save(preview.identity(), "PREVIEW");
        }
        return preview;
    }

    /** Returns a detached journal view without capturing sources or changing workflow ownership. */
    public synchronized MigrationWorkflowReport report(String identity) {
        MigrationJournal.Workflow entry = journal.workflows().stream()
                .filter(candidate -> candidate.preview().identity().equals(identity)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown preview identity"));
        if (quarantined.contains(identity)) {
            MigrationPreview safe = new MigrationPreview(identity, List.of(), List.of(), List.of(), "", "", "");
            return new MigrationWorkflowReport(safe, MigrationWorkflowReport.Status.QUARANTINED, Instant.EPOCH,
                    List.of(), MigrationValidation.Outcome.UNAVAILABLE, false,
                    "Historical review quarantined: configuration artifacts are withheld. Recovery records are retained.");
        }
        MigrationWorkflowReport.Status status = switch (entry.outcome()) {
            case "STAGED" -> MigrationWorkflowReport.Status.STAGED;
            case "STAGING", "RECOVERY" -> MigrationWorkflowReport.Status.RECOVERY;
            case "ROLLED_BACK" -> MigrationWorkflowReport.Status.ROLLED_BACK;
            default -> MigrationWorkflowReport.Status.PREVIEW;
        };
        return new MigrationWorkflowReport(entry.preview(), status, Instant.parse(entry.stagedAt()), entry.switched(),
                MigrationValidation.Outcome.UNAVAILABLE, false,
                "No fresh binding-owned comparison is available; cutover is blocked (no override).");
    }

    private void save(String identity, String outcome) {
        Sources source = Objects.requireNonNull(sources.get(identity));
        journal.workflow(new MigrationJournal.Workflow(Objects.requireNonNull(previews.get(identity)), source.pollers(),
                source.data(), outcome, stagedAt.getOrDefault(identity, Instant.EPOCH).toString(),
                switched.stream().filter(key -> key.startsWith(identity + ":")).sorted().toList(),
                source.providerWork()));
    }

    public synchronized void apply(String previewIdentity, List<LegacyPoller> pollers, List<LegacyData> dataThings) {
        apply(previewIdentity, pollers, dataThings, List.of());
    }

    synchronized void apply(String previewIdentity, List<LegacyPoller> pollers, List<LegacyData> dataThings,
            List<ManualMigrationWork> providerWork) {
        requireSafeReview(previewIdentity);
        MigrationPreview currentPreview = planner.preview(pollers, dataThings, providerWork);
        if (!previewIdentity.equals(currentPreview.identity()) || !previews.containsKey(previewIdentity)) {
            throw new IllegalArgumentException("apply requires the exact, current preview identity");
        }
        if (!applied.contains(previewIdentity)) {
            if (recovery.contains(previewIdentity)) {
                throw new IllegalStateException("Stage recovery required; retry rollback");
            }
            save(previewIdentity, "STAGING");
            try {
                applier.createTargets(Objects.requireNonNull(previews.get(previewIdentity)));
            } catch (ManagedModbusMigrationApplier.IncompleteStageException e) {
                recovery.add(previewIdentity);
                save(previewIdentity, "RECOVERY");
                throw e;
            } catch (RuntimeException e) {
                save(previewIdentity, "PREVIEW");
                throw e;
            }
            stagedAt.put(previewIdentity, Instant.now());
            applied.add(previewIdentity);
            save(previewIdentity, "STAGED");
        }
    }

    public synchronized MigrationValidation.Observation validate(String previewIdentity, String sourcePollerUid) {
        requireSafeReview(previewIdentity);
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
        cutover(previewIdentity, sourcePollerUid, pollers, dataThings, List.of());
    }

    synchronized void cutover(String previewIdentity, String sourcePollerUid, List<LegacyPoller> pollers,
            List<LegacyData> dataThings, List<ManualMigrationWork> providerWork) {
        requireSafeReview(previewIdentity);
        MigrationPreview preview = appliedPreview(previewIdentity);
        requireCurrentSources(previewIdentity, pollers, dataThings, providerWork);
        MigrationGroup selected = group(preview, sourcePollerUid);
        if (!selected.links().isEmpty()
                && selected.links().stream().allMatch(link -> switched.contains(linkKey(previewIdentity, link)))) {
            return;
        }
        var observation = validate(previewIdentity, sourcePollerUid);
        if (observation.outcome() != MigrationValidation.Outcome.MATCH) {
            throw new IllegalStateException("Fresh candidate validation is not MATCH; cutover unavailable");
        }
        try {
            applier.cutover(preview, selected, observation.authorization(), () -> {
                selected.links().forEach(link -> switched.add(linkKey(previewIdentity, link)));
                save(previewIdentity, "STAGED");
            });
        } catch (RuntimeException failure) {
            selected.links().forEach(link -> switched.remove(linkKey(previewIdentity, link)));
            recovery.add(previewIdentity);
            try {
                save(previewIdentity, "RECOVERY");
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
            throw failure;
        }
    }

    synchronized Map<String, String> stagedTargets(String identity) {
        requireSafeReview(identity);
        var targets = appliedPreview(identity).groups().stream().map(MigrationGroup::targetPollerUid).toList();
        if (!journal.targets(identity).containsAll(targets)) {
            throw new IllegalArgumentException("Staged target journal ownership is incomplete");
        }
        return targets.stream().collect(Collectors.toUnmodifiableMap(uid -> uid, uid -> identity));
    }

    synchronized void requireCurrentSources(String previewIdentity, List<LegacyPoller> pollers,
            List<LegacyData> dataThings, List<ManualMigrationWork> providerWork) {
        requireSafeReview(previewIdentity);
        MigrationPreview preview = appliedPreview(previewIdentity);
        Sources reviewedSources = Objects.requireNonNull(sources.get(previewIdentity));
        Set<String> removedLinks = new HashSet<>();
        preview.groups().stream().flatMap(candidate -> candidate.links().stream())
                .filter(link -> switched.contains(linkKey(previewIdentity, link)))
                .forEach(link -> removedLinks.add(link.itemName() + " -> " + link.sourceChannelUid()));
        // Only journaled mutation intents may account for a source link removed by this live cutover.
        preview.groups()
                .forEach(candidate -> journal.links(previewIdentity + ":" + candidate.sourcePollerUid()).stream()
                        .filter(link -> dataThings.stream().flatMap(data -> data.links().stream())
                                .noneMatch(current -> current.itemName().equals(link.itemName())
                                        && current.channelUid().equals(link.sourceChannelUid())))
                        .forEach(link -> removedLinks.add(link.itemName() + " -> " + link.sourceChannelUid())));
        List<LegacyData> expectedData = reviewedSources.data().stream()
                .map(data -> new LegacyData(data.uid(), data.pollerUid(), data.configuration(), data.links().stream()
                        .filter(link -> !removedLinks.contains(link.itemName() + " -> " + link.channelUid())).toList(),
                        data.configurationIdentity(), data.omissionReason()))
                .toList();
        String expectedIdentity = planner
                .preview(reviewedSources.pollers(), expectedData, reviewedSources.providerWork()).identity();
        if (!expectedIdentity.equals(planner.preview(pollers, dataThings, providerWork).identity())) {
            throw new IllegalArgumentException("operation requires the exact, current reviewed source configuration");
        }
    }

    private MigrationGroup group(MigrationPreview preview, String sourcePollerUid) {
        return preview.groups().stream().filter(group -> group.sourcePollerUid().equals(sourcePollerUid)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown reviewed poller group"));
    }

    private static String linkKey(String identity, MigrationLink link) {
        return identity + ":" + link.itemName() + " -> " + link.sourceChannelUid();
    }

    public synchronized void revert(String previewIdentity, String itemName, String sourceChannelUid) {
        MigrationPreview preview = appliedPreview(previewIdentity);
        MigrationLink link = preview.groups().stream().flatMap(group -> group.links().stream())
                .filter(candidate -> candidate.itemName().equals(itemName)
                        && candidate.sourceChannelUid().equals(sourceChannelUid))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown reviewed Item-channel link"));
        applier.revertLink(preview, link);
        switched.remove(linkKey(previewIdentity, link));
        save(previewIdentity, applied.contains(previewIdentity) ? "STAGED" : "RECOVERY");
    }

    public synchronized void rollback(String previewIdentity) {
        MigrationPreview preview = appliedPreview(previewIdentity);
        applier.removeTargets(preview);
        applied.remove(previewIdentity);
        recovery.remove(previewIdentity);
        stagedAt.remove(previewIdentity);
        switched.removeIf(key -> key.startsWith(previewIdentity + ":"));
        save(previewIdentity, "ROLLED_BACK");
    }

    public synchronized void cleanupLegacy(String previewIdentity) {
        requireSafeReview(previewIdentity);
        MigrationPreview preview = appliedPreview(previewIdentity);
        applier.removeLegacy(preview);
    }

    private void requireSafeReview(String identity) {
        if (quarantined.contains(identity)) {
            throw new IllegalStateException("Historical review quarantined; only recovery operations are permitted");
        }
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
