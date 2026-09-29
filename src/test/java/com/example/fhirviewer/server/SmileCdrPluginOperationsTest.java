package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.rest.RestMethod;

/**
 * Tests for the operations {@link SmileCdrPlugin} declares.
 *
 * <p>Exists separately from the disabled {@code SmileCdrPluginTest.java.hold}, which is the
 * connection and detection half of this plugin and is still parked. These tests are about
 * the operation declarations, which arrived later and are exercised here so that they
 * cannot silently regress into nothing — the exact failure that left the operation screen
 * empty for every user before any plugin declared anything.</p>
 *
 * <p>The declared shapes come from Smile's own "Search Parameter Reindexing"
 * documentation.</p>
 */
class SmileCdrPluginOperationsTest {

    private SmileCdrPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new SmileCdrPlugin();
    }

    @Test
    @DisplayName("Smile declares its reindexing operations")
    void declaresReindexingOperations() {
        List<ServerOperation> operations = plugin.availableOperations();

        assertFalse(operations.isEmpty(),
                "Smile exposes a reindex family on the FHIR endpoint; declaring nothing leaves"
                        + " the operation screen empty for every Smile user");

        // Scoped to Smile's own declarations. The inherited bulk export and import are
        // correctly categorised as STANDARD, not administration.
        for (ServerOperation operation : operations) {
            if (operation.category() == ServerOperation.Category.STANDARD) {
                continue;
            }
            assertEquals(ServerOperation.Category.ADMINISTRATION, operation.category(),
                    operation.id() + " is an administration operation");
            assertTrue(operation.requiresAuthentication(),
                    operation.id() + " re-indexes a server and needs credentials");
        }

        // At least one Smile-specific operation, or the assertions above could pass on the
        // inherited bulk list alone.
        assertTrue(operations.stream()
                        .anyMatch(o -> o.category() == ServerOperation.Category.ADMINISTRATION),
                "Smile's own reindex operations must be on the list");
    }

    @Test
    @DisplayName("Smile's operations run on the FHIR endpoint, not the Admin JSON API")
    void operationsAreOnTheFhirEndpoint() {
        // This is the load-bearing assertion. Smile's JSON Admin API is on a separate port
        // (typically 9000) from the FHIR endpoint (typically 8000), and this application
        // resolves operation paths against the single base URL the user configured. A path
        // under admin-json would therefore 404 on every real server while looking entirely
        // plausible — see docs/plans/08_SECOND_BASE_URL_FOR_ADMIN_APIS.md.
        for (ServerOperation operation : plugin.availableOperations()) {
            String path = operation.pathTemplate();
            assertFalse(path.contains("admin-json"),
                    operation.id() + " must not use the Admin JSON API branch: " + path);
            assertFalse(path.startsWith("admin/"),
                    operation.id() + " must not use an admin branch: " + path);
        }
    }

    @Test
    @DisplayName("The system reindex is a POST at the endpoint root")
    void systemReindexIsAPostAtTheRoot() {
        ServerOperation operation = plugin.operation("$reindex").orElseThrow(
                () -> new AssertionError("no $reindex declared"));

        assertEquals(RestMethod.POST, operation.method(),
                "$reindex is invoked with POST; Smile documents it as a job initiation");
        assertEquals("$reindex", operation.pathTemplate(),
                "$reindex is invoked at the FHIR endpoint root");
        assertTrue(operation.parameter("url").isPresent(),
                "the url parameter selects what to re-index");
        assertTrue(operation.parameter("partitionId").isPresent(),
                "Smile's documented example passes partitionId to scope the job");
    }

    @Test
    @DisplayName("The dry run is a safe read that takes a resource type and id")
    void dryRunIsAGetOnOneResource() {
        // The distinction that matters: $reindex changes the server's index, while
        // $reindex-dryrun only reports what would change. Declaring the dry run as a write,
        // or with the wrong path, would either lose a genuinely useful safe operation or
        // put a write behind a name that promises none.
        ServerOperation operation = plugin.operation("reindex-dryrun").orElseThrow(
                () -> new AssertionError("no reindex-dryrun declared"));

        assertEquals(RestMethod.GET, operation.method(),
                "$reindex-dryrun is invoked with GET; Smile documents that it can be, because"
                        + " it does not affect resource state");
        assertEquals("{resourceType}/{id}/$reindex-dryrun", operation.pathTemplate(),
                "the dry run is an instance-level operation");
        assertEquals(List.of("resourceType", "id"),
                operation.pathParameters().stream().map(ServerOperationParameter::name).toList(),
                "both path segments are declared as path parameters");
    }

    @Test
    @DisplayName("The instance reindex names both path segments")
    void instanceReindexDeclaresItsPath() {
        ServerOperation operation = plugin.operation("reindex-instance").orElseThrow(
                () -> new AssertionError("no instance reindex declared"));

        assertEquals("{resourceType}/{id}/$reindex", operation.pathTemplate());
        assertEquals(List.of("resourceType", "id"),
                operation.pathParameters().stream().map(ServerOperationParameter::name).toList());
    }

    @Test
    @DisplayName("Smile keeps the inherited bulk operations")
    void keepsTheInheritedBulkOperations() {
        // SmileCdrPlugin extends StandardFhirRestPlugin and overrides availableOperations().
        // Returning only its own list would quietly drop bulk export and import, which a
        // Smile server does support.
        for (String id : List.of("$export", "$import")) {
            assertTrue(plugin.operation(id).isPresent(),
                    id + " is inherited from StandardFhirRestPlugin and must survive the override");
        }
    }

    @Test
    @DisplayName("Smile declares no vendor screen, because its operations are all callable")
    void declaresNoVendorScreen() {
        assertTrue(plugin.vendorActions().isEmpty(),
                "Smile's extras are callable endpoints rather than a whole administrative UI,"
                        + " so there is nothing for a vendor screen to open");
    }

    @Test
    @DisplayName("An anonymous Smile session still sees the operations")
    void anonymousSessionStillSeesTheOperations() {
        ServerSession anonymous = new ServerSession(
                ServerDefinition.forSmileCdr("Public", "https://example.org/fhir").build(),
                AnonymousServerAuthentication.INSTANCE);

        List<ServerOperation> shown = plugin.supportedOperations(anonymous);

        assertEquals(plugin.availableOperations().size(), shown.size(),
                "an anonymous session must see the same list; an empty one here is"
                        + " indistinguishable from the plugin declaring nothing");
    }
}
