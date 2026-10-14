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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.auth.Role;
import org.openhab.core.io.rest.RESTConstants;
import org.openhab.core.io.rest.RESTResource;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.jaxrs.whiteboard.JaxrsWhiteboardConstants;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JSONRequired;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsApplicationSelect;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsName;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsResource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

/**
 * Administrator-only migration operations on the authenticated Core REST application.
 * POST previews captures a review; GET workflows/{identity} only reads it. All other operations use POST.
 * Validate and cutover accept only {sourcePollerUid}; revert accepts only {itemName, sourceChannelUid}.
 * JSON bodies are bounded to 8192 bytes, UIDs to 512 characters and Item names to 255 characters.
 * Identity is the exact lowercase 64-character review digest; selections are compared without normalization.
 * No endpoint provides a validation override or deletes legacy Things.
 *
 * @author Sami Salonen - Initial contribution
 */
@Component
@JaxrsResource
@JaxrsName("modbus.migration")
@JaxrsApplicationSelect("(" + JaxrsWhiteboardConstants.JAX_RS_NAME + "=" + RESTConstants.JAX_RS_NAME + ")")
@JSONRequired
@Path("modbus/migration")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Role.ADMIN)
@NonNullByDefault
public class ModbusMigrationResource implements RESTResource {
    private static final Gson REQUEST_JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final int MAX_BODY_BYTES = 8192;
    private final ModbusMigrationService service;

    @Activate
    public ModbusMigrationResource(@Reference ModbusMigrationService service) {
        this.service = service;
    }

    @POST
    @Path("previews")
    public Response preview() {
        return execute(service::preview);
    }

    @GET
    @Path("workflows/{identity}")
    public Response report(@PathParam("identity") String identity) {
        return execute(() -> Report.from(review(identity)));
    }

    @POST
    @Path("workflows/{identity}/apply")
    public Response apply(@PathParam("identity") String identity) {
        return execute(() -> {
            review(identity);
            service.apply(identity);
            return Report.from(service.report(identity));
        });
    }

    @POST
    @Path("workflows/{identity}/validate")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response validate(@PathParam("identity") String identity, @Context ContainerRequestContext request) {
        return withBody(request, List.of("sourcePollerUid"), body -> execute(() -> {
            var report = review(identity);
            String poller = poller(report, body);
            var observation = service.validate(identity, poller);
            return Report.from(service.report(identity), Observation.from(observation));
        }));
    }

    @POST
    @Path("workflows/{identity}/cutover")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response cutover(@PathParam("identity") String identity, @Context ContainerRequestContext request) {
        return withBody(request, List.of("sourcePollerUid"), body -> execute(() -> {
            var report = review(identity);
            String poller = poller(report, body);
            service.cutover(identity, poller);
            return Report.from(service.report(identity));
        }));
    }

    @POST
    @Path("workflows/{identity}/revert")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response revert(@PathParam("identity") String identity, @Context ContainerRequestContext request) {
        return withBody(request, List.of("itemName", "sourceChannelUid"), body -> execute(() -> {
            var report = review(identity);
            String item = field(body, "itemName", 255);
            String channel = field(body, "sourceChannelUid", 512);
            if (report.status() != MigrationWorkflowReport.Status.QUARANTINED
                    && report.preview().groups().stream().flatMap(group -> group.links().stream()).noneMatch(
                            link -> link.itemName().equals(item) && link.sourceChannelUid().equals(channel))) {
                throw new Failure(404, ErrorCode.NOT_FOUND);
            }
            service.revert(identity, item, channel);
            return Report.from(service.report(identity));
        }));
    }

    @POST
    @Path("workflows/{identity}/rollback")
    public Response rollback(@PathParam("identity") String identity) {
        return execute(() -> {
            review(identity);
            service.rollback(identity);
            return Report.from(service.report(identity));
        });
    }

    private String poller(MigrationWorkflowReport report, JsonObject body) {
        if (report.status() == MigrationWorkflowReport.Status.QUARANTINED) {
            throw new Failure(409, ErrorCode.CONFLICT);
        }
        String poller = field(body, "sourcePollerUid", 512);
        if (report.preview().groups().stream().noneMatch(group -> group.sourcePollerUid().equals(poller))) {
            throw new Failure(404, ErrorCode.NOT_FOUND);
        }
        return poller;
    }

    private Response withBody(ContainerRequestContext request, List<String> fields,
            Function<JsonObject, Response> operation) {
        try {
            // Do not hold the shared workflow monitor while receiving an HTTP request body.
            var body = body(request);
            if (body.size() != fields.size() || !body.keySet().containsAll(fields)) {
                throw new Failure(400, ErrorCode.INVALID_INPUT);
            }
            return operation.apply(body);
        } catch (Failure e) {
            return error(e.status, e.code);
        }
    }

    private JsonObject body(ContainerRequestContext request) {
        // Parsing here bounds input and sanitizes syntax/type failures before they reach generic REST error handling.
        try {
            byte[] bytes = request.getEntityStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new Failure(400, ErrorCode.INVALID_INPUT);
            }
            JsonObject body = REQUEST_JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            if (body == null) {
                throw new Failure(400, ErrorCode.INVALID_INPUT);
            }
            return body;
        } catch (IOException | JsonParseException e) {
            throw new Failure(400, ErrorCode.INVALID_INPUT);
        }
    }

    private String field(JsonObject body, String name, int maxLength) {
        var value = body.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new Failure(400, ErrorCode.INVALID_INPUT);
        }
        String text = value.getAsString();
        if (text.isBlank() || text.length() > maxLength || text.chars().anyMatch(Character::isISOControl)) {
            throw new Failure(400, ErrorCode.INVALID_INPUT);
        }
        return text;
    }

    private MigrationWorkflowReport review(String identity) {
        if (!identity.matches("[0-9a-f]{64}")) {
            throw new Failure(400, ErrorCode.INVALID_INPUT);
        }
        try {
            return service.report(identity);
        } catch (IllegalArgumentException e) {
            throw new Failure(404, ErrorCode.NOT_FOUND);
        }
    }

    private Response execute(Supplier<?> operation) {
        // Keep preflight, mutation and readback atomic with console operations on the shared singleton monitor.
        synchronized (service) {
            try {
                return Response.ok(operation.get()).build();
            } catch (Failure e) {
                return error(e.status, e.code);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return error(409, ErrorCode.CONFLICT);
            } catch (RuntimeException e) {
                return error(500, ErrorCode.INTERNAL_ERROR);
            }
        }
    }

    private Response error(int status, ErrorCode code) {
        String message = switch (code) {
            case INVALID_INPUT -> "Invalid migration request.";
            case NOT_FOUND -> "Reviewed migration identity or selection not found.";
            case CONFLICT ->
                "Migration safety check failed; read the workflow report and review current configuration.";
            case INTERNAL_ERROR -> "Migration operation failed; read the workflow report before retrying.";
        };
        return Response.status(status).entity(new Error(code, message)).build();
    }

    public enum ErrorCode {
        INVALID_INPUT,
        NOT_FOUND,
        CONFLICT,
        INTERNAL_ERROR
    }

    public record Error(ErrorCode code, String message) {
    }

    private static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final ErrorCode code;

        private Failure(int status, ErrorCode code) {
            this.status = status;
            this.code = code;
        }
    }

    /**
     * Core's Gson provider has no java.time adapter; timestamps are ISO-8601 strings at this boundary.
     * Only POST validate includes observation; GET reports never collect or persist validation evidence.
     */
    public record Report(MigrationPreview preview, MigrationWorkflowReport.Status status, String stagedAt,
            List<String> switchedLinks, MigrationValidation.Outcome validation, boolean cutoverReady,
            String readinessReason, @Nullable Observation observation) {
        public Report {
            switchedLinks = List.copyOf(switchedLinks);
        }

        private static Report from(MigrationWorkflowReport report) {
            return from(report, null);
        }

        private static Report from(MigrationWorkflowReport report, @Nullable Observation observation) {
            return new Report(report.preview(), report.status(), report.stagedAt().toString(), report.switchedLinks(),
                    report.validation(), report.cutoverReady(), report.readinessReason(), observation);
        }
    }

    public record Observation(String previewIdentity, String sourcePollerUid, String observedAt,
            MigrationValidation.Outcome outcome) {
        private static Observation from(MigrationValidation.Observation observation) {
            return new Observation(observation.previewIdentity(), observation.sourcePollerUid(),
                    observation.observedAt().toString(), observation.outcome());
        }
    }
}
