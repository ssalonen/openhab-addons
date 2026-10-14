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

import java.util.Arrays;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.io.console.Console;
import org.openhab.core.io.console.extensions.AbstractConsoleCommandExtension;
import org.openhab.core.io.console.extensions.ConsoleCommandExtension;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * User-facing, explicit Modbus poller migration commands.
 * 
 * @author Sami Salonen - Unlinked staging and fail-closed cutover commands
 * @author Sami Salonen - Core storage journal and explicit link revert
 * @author Sami Salonen - Shared migration service consumer
 */
@Component(service = ConsoleCommandExtension.class)
@NonNullByDefault
public final class ModbusMigrationConsoleCommandExtension extends AbstractConsoleCommandExtension {
    private final ModbusMigrationService service;

    @Activate
    public ModbusMigrationConsoleCommandExtension(@Reference ModbusMigrationService service) {
        super("modbus", "Safe Modbus migration commands.");
        this.service = service;
    }

    @Override
    public void execute(String[] args, Console console) {
        if (args.length < 2 || !"migrate".equals(args[0])) {
            printUsage(console);
            return;
        }
        try {
            switch (args[1]) {
                case "preview" -> preview(console);
                case "apply" -> apply(args, console);
                case "validate" -> validate(args, console);
                case "cutover" -> cutover(args, console);
                case "revert" -> revert(args, console);
                case "rollback" -> rollback(args, console);
                default -> printUsage(console);
            }
        } catch (RuntimeException e) {
            console.println("Migration refused: " + e.getMessage());
        }
    }

    private void preview(Console console) {
        ModbusMigrationService.Preview capture = service.preview();
        MigrationPreview preview = capture.plan();
        console.println("Preview identity: " + preview.identity());
        console.println(preview.yaml());
        console.println(preview.impactReport());
        capture.manualWork().forEach(work -> console.println("MANUAL: " + work.sourceUid() + " — " + work.reason()));
        preview.manualWork().forEach(work -> console.println("MANUAL: " + work.sourceUid() + " — " + work.reason()));
    }

    private void apply(String[] args, Console console) {
        if (args.length != 3) {
            printUsage(console);
            return;
        }
        service.apply(args[2]);
        console.println("Candidates staged UNLINKED; production links and legacy Things remain unchanged.");
    }

    private void validate(String[] args, Console console) {
        if (args.length != 4) {
            printUsage(console);
            return;
        }
        MigrationValidation.Observation observation = service.validate(args[2], args[3]);
        console.println("Candidate validation: " + observation.outcome() + "; source poller: "
                + observation.sourcePollerUid() + "; observed at: " + observation.observedAt());
        if (observation.outcome() == MigrationValidation.Outcome.UNAVAILABLE) {
            console.println("No fresh binding-owned comparison is available; cutover is blocked (no override).");
        }
    }

    private void cutover(String[] args, Console console) {
        if (args.length != 4) {
            printUsage(console);
            return;
        }
        service.cutover(args[2], args[3]);
        console.println("Eligible reviewed links switched for " + args[3] + "; legacy Things retained.");
    }

    private void revert(String[] args, Console console) {
        if (args.length != 5) {
            printUsage(console);
            return;
        }
        service.revert(args[2], args[3], args[4]);
        console.println("Original links and profiles restored for " + args[3] + " -> " + args[4]
                + "; legacy Things and staged candidates retained.");
    }

    private void rollback(String[] args, Console console) {
        if (args.length != 3) {
            printUsage(console);
            return;
        }
        service.rollback(args[2]);
        console.println("Migration-owned targets removed and cutover links restored; legacy Things remain untouched.");
    }

    @Override
    public List<String> getUsages() {
        return Arrays.asList(buildCommandUsage("migrate preview", "Show safe targets, manual work, and identity"),
                buildCommandUsage("migrate apply <identity>", "Stage reviewed managed targets UNLINKED"),
                buildCommandUsage("migrate validate <identity> <source-poller-uid>",
                        "Report fresh validation; unavailable blocks cutover"),
                buildCommandUsage("migrate cutover <identity> <source-poller-uid>",
                        "Explicitly replace eligible links after fresh MATCH"),
                buildCommandUsage("migrate revert <identity> <item-name> <source-channel-uid>",
                        "Restore original links and profiles; retain candidates and legacy Things"),
                buildCommandUsage("migrate rollback <identity>",
                        "Restore switched legacy links and remove owned candidates"));
    }
}
