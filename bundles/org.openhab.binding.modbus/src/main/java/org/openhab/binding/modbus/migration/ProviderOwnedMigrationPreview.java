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
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Detached provider-owned review artifacts, deliberately not executable managed migration groups.
 * Provider identities are implementation class names, not inferred source filenames or service IDs.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
public record ProviderOwnedMigrationPreview(String revisionDigest, List<FileChange> fileChanges,
        List<ManualMigrationWork> manualWork) {
    public ProviderOwnedMigrationPreview {
        fileChanges = List.copyOf(fileChanges);
        manualWork = List.copyOf(manualWork);
    }

    /** A reviewable candidate plus a semantic patch manifest; no file bytes are read or rewritten. */
    public record FileChange(String sourcePollerUid, String targetPollerUid, String revisionDigest,
            List<SourceRevision> sources, String candidateYaml, List<LinkPatch> linkPatches, String instructions) {
        public FileChange {
            sources = List.copyOf(sources);
            linkPatches = List.copyOf(linkPatches);
        }
    }

    /** Digest of registry-visible configuration, not a checksum of unknown source file bytes. */
    public record SourceRevision(String sourceUid, String providerIdentity, String sourceLocation,
            String revisionDigest) {
    }

    /** Manual remove-old/add-new intent; revert restores this exact safe source link configuration. */
    public record LinkPatch(String itemName, String sourceChannelUid, String targetChannelUid, String providerIdentity,
            String sourceLocation, String revisionDigest, Map<String, Object> profileConfiguration) {
        public LinkPatch {
            profileConfiguration = Map.copyOf(profileConfiguration);
        }
    }
}
