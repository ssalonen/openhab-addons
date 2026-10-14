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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.rest.core.internal.MediaTypeExtension;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.BridgeBuilder;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

/**
 * Exercises JAX-RS routing and the actual Core JSON provider over loopback HTTP.
 * This standalone server deliberately does not test Core runtime role enforcement.
 *
 * @author Sami Salonen - Initial contribution
 */
class ModbusMigrationResourceTest {
    private final ProviderOwnedMigrationPreviewTest.OwnedThingRegistry things = mock(
            ProviderOwnedMigrationPreviewTest.OwnedThingRegistry.class);
    private final ItemChannelLinkRegistry links = mock(ItemChannelLinkRegistry.class);
    private final ManagedThingProvider provider = mock(ManagedThingProvider.class);
    private final StorageService storage = mock(StorageService.class);
    private final Map<String, String> persisted = new HashMap<>();
    private HttpServer server;
    private HttpClient client;
    private ModbusMigrationService service;
    private URI base;

    @BeforeEach
    void start() {
        var poller = BridgeBuilder.create(new ThingTypeUID("modbus", "poller"), new ThingUID("modbus:poller:tcp:plant"))
                .withBridge(new ThingUID("modbus:tcp:plant"))
                .withConfiguration(new Configuration(Map.of("start", 100, "length", 2, "type", "holding"))).build();
        var data = org.openhab.core.thing.binding.builder.ThingBuilder
                .create(new ThingTypeUID("modbus", "data"), new ThingUID("modbus:data:tcp:plant:value"))
                .withBridge(poller.getUID())
                .withConfiguration(new Configuration(Map.of("readStart", "100", "readValueType", "int16")))
                .withChannel(org.openhab.core.thing.binding.builder.ChannelBuilder
                        .create(new org.openhab.core.thing.ChannelUID("modbus:data:tcp:plant:value:number"), "Number")
                        .build())
                .build();
        when(things.getAll()).thenReturn(List.of(poller, data));
        when(provider.get(data.getUID())).thenReturn(data);
        when(provider.get(poller.getUID())).thenReturn(poller);
        when(links.getAll()).thenReturn(List.of());
        when(storage.<String> getStorage("modbus-migration-journal"))
                .thenReturn(ManagedModbusMigrationStagingTest.storage(persisted));
        service = new ModbusMigrationService(things, links, provider, storage);
        var resource = new ModbusMigrationResource(service);
        var config = new ResourceConfig().register(ModbusMigrationResource.class)
                .register(new org.glassfish.jersey.internal.inject.AbstractBinder() {
                    @Override
                    protected void configure() {
                        bind(resource).to(ModbusMigrationResource.class);
                    }
                }).register(new MediaTypeExtension<>());
        server = JdkHttpServerFactory.createHttpServer(URI.create("http://127.0.0.1:0/rest/"), config);
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rest/modbus/migration/");
        client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    }

    @AfterEach
    void stop() {
        client.close();
        server.stop(0);
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(base.resolve(path)).timeout(java.time.Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject json(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private String capture() throws Exception {
        return json(request("POST", "previews", "")).getAsJsonObject("plan").get("identity").getAsString();
    }

    @Test
    void providerOwnedPreviewIsReadOnlyAndDoesNotJournal() throws Exception {
        var before = Map.copyOf(persisted);
        var response = request("GET", "provider-previews", "");
        assertEquals(200, response.statusCode());
        var result = json(response);
        assertTrue(result.get("revisionDigest").getAsString().matches("[0-9a-f]{64}"));
        assertNotNull(result.getAsJsonArray("fileChanges"));
        assertFalse(response.body().contains("SENTINEL"));
        assertEquals(before, persisted);
        verify(provider, never()).add(any());
        verify(links, never()).add(any());
    }

    @Test
    void coreJsonSerializesNonemptyProviderFileChangeAndLinkPatchWithoutMutation() throws Exception {
        var sourceThings = things.getAll();
        var poller = sourceThings.stream().filter(thing -> thing.getThingTypeUID().getId().equals("poller")).findFirst()
                .orElseThrow();
        var data = sourceThings.stream().filter(thing -> thing.getThingTypeUID().getId().equals("data")).findFirst()
                .orElseThrow();
        var endpoint = BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), new ThingUID("modbus:tcp:plant"))
                .withConfiguration(
                        new Configuration(Map.of("host", "SENTINEL-endpoint", "password", "SENTINEL-secret")))
                .build();
        var owner = mock(org.openhab.core.thing.ThingProvider.class);
        var linkOwner = mock(org.openhab.core.common.registry.Provider.class);
        var link = new org.openhab.core.thing.link.ItemChannelLink("Value",
                new org.openhab.core.thing.ChannelUID(data.getUID(), "number"),
                new Configuration(Map.of("profile", "system:default")));
        when(things.getAll()).thenReturn(List.of(data, endpoint, poller));
        when(things.get(endpoint.getUID())).thenReturn(endpoint);
        when(things.getProvider(endpoint)).thenReturn(owner);
        when(things.getProvider(poller)).thenReturn(owner);
        when(things.getProvider(data)).thenReturn(owner);
        when(links.getAll()).thenReturn(List.of(link));
        when(links.getProvider(link)).thenReturn(linkOwner);
        var before = Map.copyOf(persisted);
        var response = request("GET", "provider-previews", "");
        var preview = json(response);
        var changes = preview.getAsJsonArray("fileChanges");
        assertEquals(1, changes.size());
        var change = changes.get(0).getAsJsonObject();
        assertEquals(poller.getUID().toString(), change.get("sourcePollerUid").getAsString());
        assertEquals("modbus:poller2:tcp:plant", change.get("targetPollerUid").getAsString());
        assertTrue(change.get("revisionDigest").getAsString().matches("[0-9a-f]{64}"));
        assertTrue(change.get("candidateYaml").getAsString().contains("itemType: Number"));
        assertFalse(change.get("candidateYaml").getAsString().contains("items:"));
        assertTrue(change.get("instructions").getAsString().contains("Revert"));
        assertEquals(3, change.getAsJsonArray("sources").size());
        for (var entry : change.getAsJsonArray("sources")) {
            var source = entry.getAsJsonObject();
            assertEquals("UNKNOWN_MANUAL_LOCATION", source.get("sourceLocation").getAsString());
            assertEquals(owner.getClass().getName(), source.get("providerIdentity").getAsString());
            assertTrue(source.get("revisionDigest").getAsString().matches("[0-9a-f]{64}"));
        }
        var patches = change.getAsJsonArray("linkPatches");
        assertEquals(1, patches.size());
        var patch = patches.get(0).getAsJsonObject();
        assertEquals("Value", patch.get("itemName").getAsString());
        assertEquals(link.getLinkedUID().toString(), patch.get("sourceChannelUid").getAsString());
        assertEquals("modbus:poller2:tcp:plant:value", patch.get("targetChannelUid").getAsString());
        assertEquals("UNKNOWN_MANUAL_LOCATION", patch.get("sourceLocation").getAsString());
        assertEquals(linkOwner.getClass().getName(), patch.get("providerIdentity").getAsString());
        assertTrue(patch.get("revisionDigest").getAsString().matches("[0-9a-f]{64}"));
        assertEquals("system:default", patch.getAsJsonObject("profileConfiguration").get("profile").getAsString());
        assertFalse(response.body().contains("SENTINEL"));
        assertEquals(response.body(), request("GET", "provider-previews", "").body());
        assertEquals(before, persisted);
        verify(provider, never()).add(any());
        verify(provider, never()).update(any());
        verify(provider, never()).remove(any());
        verify(links, never()).add(any());
        verify(links, never()).remove(anyString());
        verifyNoInteractions(owner, linkOwner);
    }

    @Test
    void historicalUnversionedReviewIsQuarantinedOverCoreHttpWithoutRewritingStorage() throws Exception {
        String identity = capture();
        String key = "v1:workflow:" + identity;
        var historical = JsonParser.parseString(java.util.Objects.requireNonNull(persisted.get(key))).getAsJsonObject();
        historical.remove("reviewSafetyVersion");
        var preview = historical.getAsJsonObject("preview");
        for (String artifact : List.of("yaml", "manifestJson", "impactReport")) {
            preview.addProperty(artifact, "SENTINEL-historical-credential");
        }
        preview.getAsJsonArray("groups").get(0).getAsJsonObject().getAsJsonObject("configuration")
                .addProperty("innocuous", "SENTINEL-historical-credential");
        persisted.put(key, historical.toString());
        persisted.put("v1:review:" + identity, historical.toString());
        var before = Map.copyOf(persisted);
        stop();
        start(); // Reconstruct the actual service and HTTP resource from the pre-fix serialized record.
        clearInvocations(things, links, provider, storage);
        var response = request("GET", "workflows/" + identity, "");
        assertFalse(response.body().contains("SENTINEL"), response.body());
        var report = json(response);
        assertEquals("QUARANTINED", report.get("status").getAsString());
        assertTrue(report.getAsJsonObject("preview").getAsJsonArray("groups").isEmpty());
        assertFalse(report.get("cutoverReady").getAsBoolean());
        assertFalse(service.report(identity).toString().contains("SENTINEL"));
        assertEquals(before, persisted);
        verifyNoInteractions(things, links, provider, storage);
        for (String suffix : List.of("apply", "validate", "cutover")) {
            response = request("POST", "workflows/" + identity + "/" + suffix,
                    suffix.equals("apply") ? "" : "{\"sourcePollerUid\":\"modbus:poller:tcp:plant\"}");
            assertEquals(409, response.statusCode(), response.body());
            assertFalse(response.body().contains("SENTINEL"));
        }
        verify(provider, never()).add(any());
        verify(links, never()).add(any());
    }

    @Test
    void coreJsonAndSavedReviewsOmitUnknownAndCustomConfigurationValues() throws Exception {
        var sourceThings = things.getAll();
        var data = sourceThings.stream().filter(thing -> thing.getThingTypeUID().getId().equals("data")).findFirst()
                .orElseThrow();
        var link = new org.openhab.core.thing.link.ItemChannelLink("Value",
                new org.openhab.core.thing.ChannelUID("modbus:data:tcp:plant:value:number"),
                new Configuration(Map.of("profile", "system:default", "innocuous", "SENTINEL-profile")));
        when(links.getAll()).thenReturn(List.of(link));
        var response = request("POST", "previews", "");
        var plan = json(response).getAsJsonObject("plan");
        String identity = plan.get("identity").getAsString();
        assertFalse(response.body().contains("SENTINEL"));
        assertTrue(plan.get("impactReport").getAsString().contains("omitted"));
        var saved = json(request("GET", "workflows/" + identity, ""));
        assertEquals(plan, saved.getAsJsonObject("preview"));
        var restarted = new ModbusMigrationService(things, links, provider, storage);
        assertEquals(plan, new com.google.gson.Gson().toJsonTree(restarted.report(identity).preview()));
        var poller = sourceThings.stream().filter(thing -> thing.getThingTypeUID().getId().equals("poller")).findFirst()
                .orElseThrow();
        poller.getConfiguration().put("innocuous", "SENTINEL-poller");
        data.getConfiguration().put("readTransform", "JS(https://user:SENTINEL-transform@example.test)");
        response = request("POST", "previews", "");
        plan = json(response).getAsJsonObject("plan");
        assertTrue(plan.getAsJsonArray("groups").isEmpty());
        assertFalse(plan.getAsJsonArray("manualWork").isEmpty());
        assertFalse(response.body().contains("SENTINEL"));
        assertFalse(persisted.toString().contains("SENTINEL"));
        assertFalse(plan.get("yaml").getAsString().contains("SENTINEL"));
        assertFalse(plan.get("manifestJson").getAsString().contains("SENTINEL"));
        assertFalse(plan.get("impactReport").getAsString().contains("SENTINEL"));
    }

    @Test
    void previewUsesPostAndCoreSerializesTheServiceRecord() throws Exception {
        var response = request("POST", "previews", "");
        var json = json(response);
        assertEquals(service.preview().plan().identity(), json.getAsJsonObject("plan").get("identity").getAsString());
        assertTrue(json.getAsJsonArray("manualWork").isEmpty());
        assertEquals(405, request("GET", "previews", "").statusCode());
        verify(provider, never()).add(any());
    }

    @Test
    void applyStagesTheExactReviewAndReturnsItsReport() throws Exception {
        String identity = capture();
        var report = json(request("POST", "workflows/" + identity + "/apply", ""));
        assertEquals("STAGED", report.get("status").getAsString());
        assertEquals(identity, report.getAsJsonObject("preview").get("identity").getAsString());
        assertFalse(report.get("cutoverReady").getAsBoolean());
        assertEquals(report, json(request("GET", "workflows/" + identity, "")));
        verify(provider).add(any());
        assertEquals(405, request("GET", "workflows/" + identity + "/apply", "").statusCode());
    }

    @Test
    void applyRefusesMalformedUnknownAndDriftedIdentitiesWithSanitizedErrors() throws Exception {
        String identity = capture();
        for (String invalid : List.of("not-reviewed", "A".repeat(64), "a".repeat(65))) {
            var response = request("POST", "workflows/" + invalid + "/apply", "");
            assertEquals(400, response.statusCode(), response.body());
            assertEquals("INVALID_INPUT",
                    JsonParser.parseString(response.body()).getAsJsonObject().get("code").getAsString());
        }
        assertEquals(404, request("POST", "workflows/" + "0".repeat(64) + "/apply", "").statusCode());
        when(things.getAll()).thenReturn(List.of());
        var response = request("POST", "workflows/" + identity + "/apply", "");
        assertEquals(409, response.statusCode(), response.body());
        assertEquals("CONFLICT", JsonParser.parseString(response.body()).getAsJsonObject().get("code").getAsString());
        assertFalse(response.body().contains("exact, current"));
        verify(provider, never()).add(any());
    }

    @Test
    void validateReturnsReadbackAndUnavailableObservationWithoutAuthorizingCutover() throws Exception {
        String identity = capture();
        json(request("POST", "workflows/" + identity + "/apply", ""));
        var response = request("POST", "workflows/" + identity + "/validate",
                "{\"sourcePollerUid\":\"modbus:poller:tcp:plant\"}");
        var report = json(response);
        assertEquals("STAGED", report.get("status").getAsString());
        assertFalse(report.get("cutoverReady").getAsBoolean());
        var observation = report.getAsJsonObject("observation");
        assertEquals(identity, observation.get("previewIdentity").getAsString());
        assertEquals("modbus:poller:tcp:plant", observation.get("sourcePollerUid").getAsString());
        assertEquals("UNAVAILABLE", observation.get("outcome").getAsString());
        assertEquals("1970-01-01T00:00:00Z", observation.get("observedAt").getAsString());
        assertEquals(405, request("GET", "workflows/" + identity + "/validate", "").statusCode());
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/migration-rest-validation-example.json"),
                response.body());
    }

    @Test
    void validateRequiresBoundedExactReviewedPollerAndAppliedWorkflow() throws Exception {
        String identity = capture();
        String path = "workflows/" + identity + "/validate";
        for (String body : List.of("", "null", "{}", "{\"sourcePollerUid\":null}", "{\"sourcePollerUid\":\" \"}",
                "{\"sourcePollerUid\":\"" + "a".repeat(513) + "\"}")) {
            var response = request("POST", path, body);
            assertEquals(400, response.statusCode(), response.body());
            assertEquals("INVALID_INPUT",
                    JsonParser.parseString(response.body()).getAsJsonObject().get("code").getAsString());
        }
        assertEquals(404, request("POST", path, "{\"sourcePollerUid\":\"modbus:poller:tcp:unknown\"}").statusCode());
        assertEquals(404, request("POST", path, "{\"sourcePollerUid\":\" modbus:poller:tcp:plant\"}").statusCode());
        assertEquals(409, request("POST", path, "{\"sourcePollerUid\":\"modbus:poller:tcp:plant\"}").statusCode());
        verify(provider, never()).add(any());
    }

    @Test
    void cutoverRemainsFailClosedAndNeverSwitchesProductionLinks() throws Exception {
        String identity = capture();
        json(request("POST", "workflows/" + identity + "/apply", ""));
        String path = "workflows/" + identity + "/cutover";
        assertEquals(400, request("POST", path, "{}").statusCode());
        assertEquals(404, request("POST", path, "{\"sourcePollerUid\":\"modbus:poller:tcp:wrong\"}").statusCode());
        var response = request("POST", path, "{\"sourcePollerUid\":\"modbus:poller:tcp:plant\"}");
        assertEquals(409, response.statusCode(), response.body());
        assertEquals("CONFLICT", JsonParser.parseString(response.body()).getAsJsonObject().get("code").getAsString());
        assertEquals(405, request("GET", path, "").statusCode());
        verify(links, never()).add(any());
        verify(links, never()).remove(anyString());
        assertFalse(json(request("GET", "workflows/" + identity, "")).get("cutoverReady").getAsBoolean());
    }

    @Test
    void revertRequiresAnExactReviewedItemChannelPairAndReturnsReadback() throws Exception {
        var link = new org.openhab.core.thing.link.ItemChannelLink("Value",
                new org.openhab.core.thing.ChannelUID("modbus:data:tcp:plant:value:number"));
        when(links.getAll()).thenReturn(List.of(link));
        String identity = capture();
        json(request("POST", "workflows/" + identity + "/apply", ""));
        String path = "workflows/" + identity + "/revert";
        for (String body : List.of("{}", "{\"itemName\":\"Value\"}", "{\"sourceChannelUid\":\"channel\"}",
                "{\"itemName\":\"" + "a".repeat(256) + "\",\"sourceChannelUid\":\"channel\"}")) {
            assertEquals(400, request("POST", path, body).statusCode());
        }
        assertEquals(404,
                request("POST", path,
                        "{\"itemName\":\"Wrong\",\"sourceChannelUid\":\"modbus:data:tcp:plant:value:number\"}")
                        .statusCode());
        var report = json(request("POST", path,
                "{\"itemName\":\"Value\",\"sourceChannelUid\":\"modbus:data:tcp:plant:value:number\"}"));
        assertEquals("STAGED", report.get("status").getAsString());
        assertEquals(report, json(request("GET", "workflows/" + identity, "")));
        assertEquals(405, request("GET", path, "").statusCode());
        // There was no cutover: reverting the reviewed source link is an idempotent no-op.
        verify(links, never()).add(any());
        verify(links, never()).remove(anyString());
    }

    @Test
    void rollbackRemovesOnlyStagedTargetsAndReturnsReadback() throws Exception {
        String identity = capture();
        String path = "workflows/" + identity + "/rollback";
        assertEquals(409, request("POST", path, "").statusCode());
        json(request("POST", "workflows/" + identity + "/apply", ""));
        var report = json(request("POST", path, ""));
        assertEquals("ROLLED_BACK", report.get("status").getAsString());
        assertEquals(report, json(request("GET", "workflows/" + identity, "")));
        verify(provider).remove(new ThingUID("modbus:poller2:tcp:plant"));
        verify(links, never()).remove(anyString());
        assertEquals(405, request("GET", path, "").statusCode());
    }

    @Test
    void malformedWrongTypeOversizedAndExtraRequestFieldsReturnTypedSanitizedInputErrors() throws Exception {
        String identity = capture();
        for (String suffix : List.of("validate", "cutover", "revert")) {
            for (String body : List.of("[1]", "{", "{\"password\":\"private-input\"}", "{\"sourcePollerUid\":123}",
                    "{\"sourcePollerUid\":\"modbus:poller:tcp:plant\",\"override\":true}",
                    "{\"sourcePollerUid\":\"" + "a".repeat(8193) + "\"}")) {
                var response = request("POST", "workflows/" + identity + "/" + suffix, body);
                assertEquals(400, response.statusCode(), response.body());
                assertEquals("INVALID_INPUT",
                        JsonParser.parseString(response.body()).getAsJsonObject().get("code").getAsString());
                assertFalse(response.body().contains("private-input"));
            }
        }
        assertEquals(400, new ModbusMigrationResource(service).report("").getStatus());
    }

    @Test
    void credentialsAndProviderFailureMessagesNeverReachJsonResponses() throws Exception {
        var endpoint = BridgeBuilder.create(new ThingTypeUID("modbus", "tcp"), new ThingUID("modbus:tcp:plant"))
                .withConfiguration(new Configuration(Map.of("password", "private-endpoint"))).build();
        when(things.get(endpoint.getUID())).thenReturn(endpoint);
        var capture = request("POST", "previews", "");
        assertFalse(capture.body().contains("private-endpoint"));
        String identity = json(capture).getAsJsonObject("plan").get("identity").getAsString();
        assertFalse(request("GET", "workflows/" + identity, "").body().contains("private-endpoint"));
        doThrow(new RuntimeException("private-provider-message")).when(provider).add(any());
        var response = request("POST", "workflows/" + identity + "/apply", "");
        assertEquals(500, response.statusCode(), response.body());
        assertEquals("INTERNAL_ERROR",
                JsonParser.parseString(response.body()).getAsJsonObject().get("code").getAsString());
        assertFalse(response.body().contains("private-provider-message"));
    }

    @Test
    void generatedWhiteboardRegistrationSelectsCoreJsonAndTheSharedConsoleService() throws Exception {
        assertArrayEquals(new String[] { org.openhab.core.auth.Role.ADMIN },
                java.util.Objects.requireNonNull(
                        ModbusMigrationResource.class.getAnnotation(javax.annotation.security.RolesAllowed.class))
                        .value());
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var builder = factory.newDocumentBuilder();
        String prefix = "/OSGI-INF/org.openhab.binding.modbus.migration.";
        var document = builder
                .parse(ModbusMigrationResource.class.getResourceAsStream(prefix + "ModbusMigrationResource.xml"));
        var properties = document.getElementsByTagName("property");
        var values = new HashMap<String, String>();
        for (int i = 0; i < properties.getLength(); i++) {
            var element = (org.w3c.dom.Element) properties.item(i);
            values.put(element.getAttribute("name"), element.getAttribute("value"));
        }
        assertEquals("true", values.get("osgi.jaxrs.resource"));
        assertEquals("(osgi.jaxrs.name=openhab)", values.get("osgi.jaxrs.application.select"));
        assertEquals("(osgi.jaxrs.media.type=application/json)", values.get("osgi.jaxrs.extension.select"));
        assertEquals("org.openhab.core.io.rest.RESTResource",
                ((org.w3c.dom.Element) document.getElementsByTagName("provide").item(0)).getAttribute("interface"));
        var console = builder.parse(ModbusMigrationResource.class
                .getResourceAsStream(prefix + "ModbusMigrationConsoleCommandExtension.xml"));
        String shared = ModbusMigrationService.class.getName();
        assertEquals(shared,
                ((org.w3c.dom.Element) document.getElementsByTagName("reference").item(0)).getAttribute("interface"));
        assertEquals(shared,
                ((org.w3c.dom.Element) console.getElementsByTagName("reference").item(0)).getAttribute("interface"));
        var singleton = builder
                .parse(ModbusMigrationResource.class.getResourceAsStream(prefix + "ModbusMigrationService.xml"));
        assertEquals("singleton",
                ((org.w3c.dom.Element) singleton.getElementsByTagName("service").item(0)).getAttribute("scope"));
    }

    @Test
    void unknownWorkflowsCannotCreateReviewsOrMutateConfiguration() throws Exception {
        String prefix = "workflows/" + "0".repeat(64);
        clearInvocations(things, links, provider, storage);
        assertEquals(404, request("GET", prefix, "").statusCode());
        for (String suffix : List.of("apply", "rollback")) {
            assertEquals(404, request("POST", prefix + "/" + suffix, "").statusCode());
        }
        for (String suffix : List.of("validate", "cutover")) {
            assertEquals(404,
                    request("POST", prefix + "/" + suffix, "{\"sourcePollerUid\":\"modbus:poller:tcp:plant\"}")
                            .statusCode());
        }
        assertEquals(404,
                request("POST", prefix + "/revert",
                        "{\"itemName\":\"Value\",\"sourceChannelUid\":\"modbus:data:tcp:plant:value:number\"}")
                        .statusCode());
        assertTrue(persisted.isEmpty());
        verifyNoInteractions(things, links, provider, storage);
    }

    @Test
    void reportIsReadOnlyAndCoreSerializesTimesWithoutJavaModuleAccess() throws Exception {
        String identity = capture();
        var before = Map.copyOf(persisted);
        clearInvocations(things, links, provider, storage);
        var response = request("GET", "workflows/" + identity, "");
        var report = json(response);
        assertEquals(identity, report.getAsJsonObject("preview").get("identity").getAsString());
        assertEquals("PREVIEW", report.get("status").getAsString());
        assertEquals("1970-01-01T00:00:00Z", report.get("stagedAt").getAsString());
        assertEquals("UNAVAILABLE", report.get("validation").getAsString());
        assertFalse(report.get("cutoverReady").getAsBoolean());
        assertEquals(response.body(), request("GET", "workflows/" + identity, "").body());
        assertEquals(before, persisted);
        verifyNoInteractions(things, links, provider, storage);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/migration-rest-report-example.json"),
                response.body());
    }
}
