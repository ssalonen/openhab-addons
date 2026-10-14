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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.storage.Storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

/**
 * Versioned write-ahead records in Core storage. The backend controls physical commit guarantees.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
final class MigrationJournal {
    record Workflow(MigrationPreview preview, List<LegacyPoller> pollers, List<LegacyData> data, String outcome,
            String stagedAt, List<String> switched, List<ManualMigrationWork> providerWork, int reviewSafetyVersion) {
        Workflow(MigrationPreview preview, List<LegacyPoller> pollers, List<LegacyData> data, String outcome,
                String stagedAt, List<String> switched, List<ManualMigrationWork> providerWork) {
            this(preview, pollers, data, outcome, stagedAt, switched, providerWork, 1);
        }
    }

    private final Gson gson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private final Map<String, String> local = new HashMap<>();
    private final @Nullable Storage<String> storage;

    MigrationJournal() {
        storage = null;
    }

    MigrationJournal(Storage<String> storage) {
        this.storage = storage;
        for (String key : storage.getKeys()) {
            local.put(key, Objects.requireNonNull(storage.get(key), "Unreadable migration journal"));
        }
    }

    List<Workflow> workflows() {
        return local.entrySet().stream().filter(entry -> entry.getKey().startsWith("v1:workflow:"))
                .map(entry -> Objects.requireNonNull(gson.fromJson(entry.getValue(), Workflow.class))).toList();
    }

    Workflow snapshot(Workflow workflow) {
        return Objects.requireNonNull(gson.fromJson(gson.toJson(workflow), Workflow.class));
    }

    void workflow(Workflow workflow) {
        String reviewKey = "v1:review:" + workflow.preview().identity();
        if (!local.containsKey(reviewKey)) {
            put(reviewKey, gson.toJson(workflow));
        }
        Workflow review = Objects.requireNonNull(gson.fromJson(local.get(reviewKey), Workflow.class));
        Workflow outcome = new Workflow(review.preview(), review.pollers(), review.data(), workflow.outcome(),
                workflow.stagedAt(), workflow.switched(), review.providerWork(), review.reviewSafetyVersion());
        put("v1:workflow:" + workflow.preview().identity(), gson.toJson(outcome));
    }

    void manifest(MigrationPreview preview) {
        String key = "v1:manifest:" + preview.identity();
        if (!local.containsKey(key)) {
            put(key, gson.toJson(preview));
        }
    }

    List<String> targets(String identity) {
        return read("v1:targets:" + identity, new TypeToken<List<String>>() {
        });
    }

    void targets(String identity, List<String> targets) {
        put("v1:targets:" + identity, gson.toJson(targets));
    }

    List<MigrationLink> links(String key) {
        return read("v1:links:" + key, new TypeToken<List<MigrationLink>>() {
        });
    }

    void links(String key, List<MigrationLink> links) {
        put("v1:links:" + key, gson.toJson(links));
    }

    private <T> List<T> read(String key, TypeToken<List<T>> type) {
        String value = local.get(key);
        return value == null ? List.of() : Objects.requireNonNull(gson.fromJson(value, type.getType()));
    }

    private void put(String key, String value) {
        Storage<String> backend = storage;
        if (backend != null) {
            backend.put(key, value);
            if (!value.equals(backend.get(key))) {
                throw new IllegalStateException("Migration journal write could not be read back");
            }
        }
        local.put(key, value);
    }
}
