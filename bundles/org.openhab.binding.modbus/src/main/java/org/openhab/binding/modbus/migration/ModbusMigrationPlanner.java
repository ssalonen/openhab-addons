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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Builds a deterministic, non-mutating migration plan for legacy poller/data topology.
 *
 * @author Sami Salonen - Bind preview identity to source and target configuration
 */
@NonNullByDefault
public final class ModbusMigrationPlanner {

    public MigrationPreview preview(List<LegacyPoller> pollers, List<LegacyData> dataThings) {
        return preview(pollers, dataThings, List.of());
    }

    MigrationPreview preview(List<LegacyPoller> pollers, List<LegacyData> dataThings,
            List<ManualMigrationWork> providerWork) {
        List<LegacyPoller> sortedPollers = pollers.stream().sorted(Comparator.comparing(LegacyPoller::uid)).toList();
        List<ManualMigrationWork> manualWork = new ArrayList<>(providerWork);
        List<MigrationGroup> groups = new ArrayList<>();
        List<MigrationImpact> impacts = new ArrayList<>();
        String ownershipSeed = previewIdentity(sortedPollers, dataThings, List.of(),
                providerWork.stream()
                        .sorted(Comparator.comparing(ManualMigrationWork::sourceUid)
                                .thenComparing(ManualMigrationWork::reason)
                                .thenComparing(ManualMigrationWork::configurationIdentity))
                        .toList());
        for (LegacyPoller poller : sortedPollers) {
            if (poller.configurationOmitted()) {
                manualWork.add(new ManualMigrationWork(poller.uid(),
                        "unknown or invalid poller configuration requires manual migration; configuration omitted"));
                continue;
            }
            List<LegacyData> children = dataThings.stream().filter(data -> poller.uid().equals(data.pollerUid()))
                    .sorted(Comparator.comparing(LegacyData::uid)).toList();
            List<MigrationChannel> channels = new ArrayList<>();
            List<MigrationLink> links = new ArrayList<>();
            for (LegacyData data : children) {
                Optional<String> unsupportedReason = unsupportedReason(data);
                if (unsupportedReason.isEmpty()
                        && ("coil".equals(poller.configuration().get("type"))
                                || "discrete".equals(poller.configuration().get("type")))
                        && !MigrationReadSelection.identityTransform(data.configuration().get("readTransform"))) {
                    unsupportedReason = Optional.of("custom bit-window readTransform requires manual migration");
                }

                if (unsupportedReason.isPresent()) {
                    manualWork.add(new ManualMigrationWork(data.uid(), unsupportedReason.get()));
                    if (!data.omissionReason().isEmpty() && !data.omissionReason().equals(unsupportedReason.get())) {
                        manualWork.add(new ManualMigrationWork(data.uid(), data.omissionReason()));
                    }
                    data.links().stream().filter(link -> !link.compatible())
                            .forEach(link -> manualWork.add(new ManualMigrationWork(link.channelUid(),
                                    "Item link profile configuration is not proven compatible; target link was not planned")));
                    continue;
                }
                List<String> sourceChannels = data.links().isEmpty() ? List.of(data.uid() + ":number")
                        : data.links().stream().map(LegacyLink::channelUid).distinct().sorted().toList();
                for (String sourceChannel : sourceChannels) {
                    String baseId = data.uid().substring(data.uid().lastIndexOf(':') + 1);
                    String role = new org.openhab.core.thing.ChannelUID(sourceChannel).getId();
                    String channelId = "number".equals(role) ? baseId : baseId + "-" + role;
                    String itemType = Objects.requireNonNull(
                            data.links().stream().filter(link -> sourceChannel.equals(link.channelUid()))
                                    .map(LegacyLink::itemType).findFirst().orElse("Number"));
                    Object readStart = Objects.requireNonNull(data.configuration().get("readStart"));
                    Object readValueType = Objects.requireNonNull(data.configuration().get("readValueType"));
                    Map<String, Object> channelConfiguration = new java.util.HashMap<>(
                            Map.of("address", readStart, "valueType", readValueType, "errorPolicy", "keepLast",
                                    "updateUnchangedValuesEveryMillis", java.math.BigDecimal.valueOf(1000)));
                    if ("lastReadSuccess".equals(role) || "lastReadError".equals(role)) {
                        channelConfiguration.put("readDiagnostic", role);
                    } else if (data.configuration().containsKey("readTransform")
                            && !MigrationReadSelection.identityTransform(data.configuration().get("readTransform"))) {
                        channelConfiguration.put("readTransform",
                                Objects.requireNonNull(data.configuration().get("readTransform")));
                    }
                    channels.add(
                            new MigrationChannel(data.uid(), channelId, channelConfiguration, itemType, sourceChannel));
                    String targetChannelUid = targetPollerUid(poller.uid()) + ":" + channelId;
                    data.links().stream().filter(link -> sourceChannel.equals(link.channelUid()))
                            .sorted(Comparator.comparing(LegacyLink::channelUid).thenComparing(LegacyLink::itemName))
                            .forEach(link -> {
                                boolean compatible = link.compatible();
                                impacts.add(new MigrationImpact(link.itemName(), link.channelUid(), targetChannelUid,
                                        link.configuration(), compatible));
                                if (compatible) {
                                    Map<String, Object> targetConfiguration = new java.util.HashMap<>(
                                            link.configuration());
                                    targetConfiguration.putIfAbsent("profile", "system:default");
                                    targetConfiguration.put(MigrationLink.OWNER,
                                            configurationIdentity(List.of("modbus-link-owner-v1", ownershipSeed,
                                                    link.itemName(), link.channelUid(), targetChannelUid)));
                                    links.add(new MigrationLink(link.itemName(), link.channelUid(), targetChannelUid,
                                            link.configuration(), targetConfiguration));
                                } else {
                                    manualWork.add(new ManualMigrationWork(link.channelUid(),
                                            "Item link profile configuration is not proven compatible; target link was not planned"));
                                }
                            });
                }
            }
            if (channels.stream().map(MigrationChannel::id).distinct().count() != channels.size()) {
                channels.stream().map(MigrationChannel::sourceDataUid).distinct()
                        .forEach(uid -> manualWork.add(new ManualMigrationWork(uid,
                                "target channel identifiers collide; group requires manual migration")));
                impacts.removeIf(impact -> impact.targetChannelUid().startsWith(targetPollerUid(poller.uid()) + ":"));
            } else if (!channels.isEmpty()) {
                groups.add(new MigrationGroup(poller.uid(), targetPollerUid(poller.uid()), poller.bridgeUid(),
                        poller.configuration(), channels, links));
            }
        }
        manualWork.sort(Comparator.comparing(ManualMigrationWork::sourceUid).thenComparing(ManualMigrationWork::reason)
                .thenComparing(ManualMigrationWork::configurationIdentity));
        impacts.sort(Comparator.comparing(MigrationImpact::sourceChannelUid).thenComparing(MigrationImpact::itemName));
        String yaml = yaml(groups, manualWork);
        String manifestJson = manifest(groups, manualWork, impacts);
        return new MigrationPreview(previewIdentity(sortedPollers, dataThings, groups, manualWork), groups, manualWork,
                impacts, yaml, manifestJson, impactReport(impacts));
    }

    private static Optional<String> unsupportedReason(LegacyData data) {
        if (data.links().stream().anyMatch(link -> !supportedContract(data.uid(), link))) {
            return Optional.of(
                    "source channel type is unknown or its conversion is not proven compatible; requires manual migration");
        }
        if (!data.omissionReason().isEmpty()) {
            return Optional.of(data.omissionReason());
        }
        Map<String, Object> configuration = data.configuration();
        if (!configuration.containsKey("readStart") || !configuration.containsKey("readValueType")) {
            return Optional.of("readStart and readValueType are required for migration");
        }
        return Optional.empty();
    }

    private static boolean supportedContract(String uid, LegacyLink link) {
        return (uid + ":number").equals(link.channelUid()) && "Number".equals(link.itemType())
                || (uid + ":switch").equals(link.channelUid()) && "Switch".equals(link.itemType())
                || (uid + ":contact").equals(link.channelUid()) && "Contact".equals(link.itemType())
                || ((uid + ":lastReadSuccess").equals(link.channelUid())
                        || (uid + ":lastReadError").equals(link.channelUid())) && "DateTime".equals(link.itemType());
    }

    private static String targetPollerUid(String sourceUid) {
        return sourceUid.replace(":poller:", ":poller2:");
    }

    private static String yaml(List<MigrationGroup> groups, List<ManualMigrationWork> manualWork) {
        StringBuilder result = new StringBuilder("version: 1\nthings:\n");
        for (MigrationGroup group : groups) {
            result.append("  ").append(group.targetPollerUid()).append(":\n    bridge: ")
                    .append(group.targetBridgeUid()).append("\n    config:\n");
            group.configuration().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.append("      ").append(entry.getKey()).append(": ")
                            .append(yamlValue(entry.getValue())).append('\n'));
            result.append("    channels:\n");
            for (MigrationChannel channel : group.channels()) {
                result.append("      ").append(channel.id()).append(":\n        itemType: ").append(channel.itemType())
                        .append("\n        config:\n");
                channel.configuration().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> result.append("          ").append(entry.getKey()).append(": ")
                                .append(yamlValue(entry.getValue())).append('\n'));
            }
        }
        if (!manualWork.isEmpty()) {
            result.append("# Manual work (not included above):\n");
            manualWork.forEach(work -> result.append("# - ").append(work.sourceUid()).append(": ").append(work.reason())
                    .append('\n'));
        }
        return result.toString();
    }

    private static String impactReport(List<MigrationImpact> impacts) {
        StringBuilder result = new StringBuilder(
                "IMPACT ASSESSMENT (review only; no rules, scripts, widgets, or profiles are rewritten)\n");
        for (MigrationImpact impact : impacts) {
            result.append("- Item: ").append(impact.itemName()).append("\n  Source channel: ")
                    .append(impact.sourceChannelUid()).append("\n  Target channel: ").append(impact.targetChannelUid())
                    .append("\n  Profile configuration: ").append(jsonObject(impact.profileConfiguration()))
                    .append("\n  Target link action: ").append(impact.targetLinkPlanned() ? "CREATE" : "MANUAL")
                    .append("\n  Developer Sidebar search queries (literal terms):\n    - ").append(impact.itemName())
                    .append("\n    - ").append(thingUid(impact.sourceChannelUid())).append("\n    - ")
                    .append(thingUid(impact.targetChannelUid())).append('\n');
        }
        return result.append(
                "NOTE: Developer Sidebar search is a user review aid, not authoritative dependency detection. Review dynamic, group, tag, external references, and scripts, widgets, and profiles manually.\n")
                .toString();
    }

    private static String thingUid(String channelUid) {
        return channelUid.substring(0, channelUid.lastIndexOf(':'));
    }

    private static String manifest(List<MigrationGroup> groups, List<ManualMigrationWork> manualWork,
            List<MigrationImpact> impacts) {
        List<String> mappings = new ArrayList<>();
        for (MigrationGroup group : groups) {
            for (MigrationChannel channel : group.channels()) {
                mappings.add("{\"sourceThing\":\"" + json(channel.sourceDataUid()) + "\",\"sourceChannel\":\""
                        + json(channel.sourceChannelUid()) + "\",\"targetPoller\":\"" + json(group.targetPollerUid())
                        + "\",\"targetChannel\":\"" + json(group.targetPollerUid() + ":" + channel.id()) + "\"}");
            }
            for (MigrationLink link : group.links()) {
                mappings.add("{\"item\":\"" + json(link.itemName()) + "\",\"sourceLink\":\""
                        + json(link.sourceChannelUid()) + "\",\"targetLink\":\"" + json(link.targetChannelUid())
                        + "\",\"profileConfiguration\":" + jsonObject(link.configuration())
                        + ",\"targetConfiguration\":" + jsonObject(link.targetConfiguration()) + "}");
            }
        }
        List<String> manual = manualWork.stream()
                .map(work -> "{\"sourceThing\":\"" + json(work.sourceUid()) + "\",\"reason\":\"" + json(work.reason())
                        + "\",\"configurationIdentity\":\"" + json(work.configurationIdentity()) + "\"}")
                .toList();
        List<String> impactMappings = impacts.stream()
                .map(impact -> "{\"item\":\"" + json(impact.itemName()) + "\",\"sourceChannel\":\""
                        + json(impact.sourceChannelUid()) + "\",\"targetChannel\":\"" + json(impact.targetChannelUid())
                        + "\",\"profileConfiguration\":" + jsonObject(impact.profileConfiguration())
                        + ",\"targetLinkPlanned\":" + impact.targetLinkPlanned() + "}")
                .toList();
        return "{\"version\":1,\"mappings\":[" + String.join(",", mappings) + "],\"manualWork\":["
                + String.join(",", manual) + "],\"impacts\":[" + String.join(",", impactMappings) + "]}";
    }

    private static String yamlValue(Object value) {
        if (value instanceof List<?> values) {
            return values.stream().map(ModbusMigrationPlanner::yamlValue)
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        }
        return value instanceof Number || value instanceof Boolean ? value.toString()
                : "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonObject(Map<String, Object> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> "\"" + json(entry.getKey()) + "\":\"" + json(entry.getValue().toString()) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static String previewIdentity(List<LegacyPoller> pollers, List<LegacyData> dataThings,
            List<MigrationGroup> groups, List<ManualMigrationWork> manualWork) {
        List<?> sources = pollers.stream().map(poller -> List.of(poller.uid(), poller.bridgeUid(),
                poller.configurationIdentity(), poller.endpointConfigurationIdentity())).toList();
        List<?> data = dataThings
                .stream().sorted(Comparator.comparing(LegacyData::uid)).map(
                        thing -> List.of(thing.uid(), thing.pollerUid(), thing.configurationIdentity(),
                                thing.links().stream()
                                        .map(link -> canonical(List.of(link.itemName(), link.channelUid(),
                                                link.itemType(), link.configurationIdentity())))
                                        .sorted().toList()))
                .toList();
        List<?> targets = groups.stream().map(group -> {
            List<?> channels = group.channels().stream().map(channel -> List.of(channel.sourceDataUid(),
                    channel.sourceChannelUid(), channel.id(), channel.itemType(), channel.configuration())).toList();
            List<?> links = group.links().stream().map(link -> List.of(link.itemName(), link.sourceChannelUid(),
                    link.targetChannelUid(), link.configuration(), link.targetConfiguration())).toList();
            return List.of(group.targetPollerUid(), group.targetBridgeUid(), group.configuration(), channels, links);
        }).toList();
        List<?> manual = manualWork.stream()
                .map(work -> List.of(work.sourceUid(), work.reason(), work.configurationIdentity())).toList();
        return configurationIdentity(List.of("modbus-migration-preview-v5", sources, data, targets, manual));
    }

    static String configurationIdentity(Object value) {
        return identity(canonical(value));
    }

    // Type tags and length prefixes avoid collisions between strings, scalars, and nested containers.
    // Only the digest is exposed; endpoint credentials never enter the review artifacts or DTOs.
    private static String canonical(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            return "map" + canonical(map.entrySet().stream()
                    .map(entry -> List.of(canonical(entry.getKey()), canonical(entry.getValue())))
                    .sorted(Comparator.comparing(entry -> entry.getFirst())).toList());
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ModbusMigrationPlanner::canonical)
                    .collect(java.util.stream.Collectors.joining("", "list" + list.size() + ":", ";"));
        }
        String type;
        if (value instanceof String) {
            type = "string";
        } else if (value instanceof Number) {
            type = "number";
        } else if (value instanceof Boolean) {
            type = "boolean";
        } else {
            throw new IllegalArgumentException("unsupported migration configuration value type");
        }
        String text = value.toString();
        return type + text.length() + ":" + text;
    }

    private static String identity(String manifest) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }
}
