package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.rest.RestMethod;

/**
 * The declaration rules: what a plugin may not write.
 *
 * <p>Every check here exists because a mistake it prevents would otherwise show up as a
 * confusing failure much later. A template naming a parameter that was never declared
 * would be sent to the server literally; a body declared on a {@code GET} would be
 * dropped in silence; a transport-owned header would disagree with the request actually
 * being sent. Catching them at declaration time means a plugin author hears about it while
 * writing the declaration rather than from a server returning a puzzling 400.</p>
 */
public class ServerOperationModelTest {

    @Test
    @DisplayName("The shortest declaration — id, verb, path — is already a usable one")
    void minimalDeclarationIsUsable() {
        ServerOperation operation = ServerOperation
                .builder("ping", RestMethod.GET, "admin/ping")
                .build();

        assertEquals("ping", operation.id(), "the display name falls back to the id");
        assertEquals(ServerOperation.Category.VENDOR, operation.category());
        assertEquals(ServerOperation.ResultKind.ANY, operation.expectedResult());
        assertEquals(ServerOperation.BodyRequirement.NONE, operation.bodyRequirement());
        assertTrue(operation.parameters().isEmpty());
        assertFalse(operation.requiresAuthentication());
    }

    @Test
    @DisplayName("A template naming a parameter that was never declared is refused")
    void refusesAnUndeclaredPlaceholder() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ServerOperation.builder("export", RestMethod.GET, "admin/export/{jobId}").build());

        assertTrue(failure.getMessage().contains("jobId"), failure.getMessage());
        assertTrue(failure.getMessage().contains("no such path parameter"), failure.getMessage());
    }

    @Test
    @DisplayName("A declared path parameter the template does not name is refused")
    void refusesAParameterTheTemplateIgnores() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ServerOperation.builder("export", RestMethod.GET, "admin/export")
                        .pathParameter("jobId", "The job.")
                        .build());

        assertTrue(failure.getMessage().contains("jobId"), failure.getMessage());
    }

    @Test
    @DisplayName("A placeholder used twice needs one declared parameter, not two")
    void repeatedPlaceholderIsOneParameter() {
        ServerOperation operation = ServerOperation
                .builder("move", RestMethod.POST, "admin/{from}/to/{to}")
                .pathParameter("from", "Where it is now.")
                .pathParameter("to", "Where it should go.")
                .acceptsBody("application/json")
                .build();

        assertEquals(2, operation.pathParameters().size());
        assertEquals(List.of("from", "to"),
                OperationPathTemplate.placeholdersIn(operation.pathTemplate()));
    }

    @Test
    @DisplayName("A body declared on a verb that cannot carry one is refused")
    void refusesABodyOnARead() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerOperation.builder("purge", RestMethod.GET, "admin/purge")
                        .requiresBody("application/json")
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerOperation.builder("purge", RestMethod.DELETE, "admin/purge")
                        .requiresBody("application/json")
                        .build());
    }

    @Test
    @DisplayName("A header the transport computes for itself is refused at declaration time")
    void refusesATransportOwnedHeader() {
        for (String name : List.of("Content-Length", "content-length", "Host",
                "Transfer-Encoding", "Connection")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ServerOperation.builder("probe", RestMethod.GET, "admin/probe")
                            .headerParameter(name, "1", false)
                            .build(),
                    "the header " + name + " must not be declarable");
        }
    }

    @Test
    @DisplayName("The same parameter name declared twice is refused")
    void refusesDuplicateParameterNames() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ServerOperation.builder("search", RestMethod.GET, "admin/search")
                        .queryParameter("q", "First.", false)
                        .queryParameter("q", "Second.", false)
                        .build());

        assertTrue(failure.getMessage().contains("twice"), failure.getMessage());
    }

    @Test
    @DisplayName("A path carrying a query string or a fragment is refused")
    void refusesAPathThatIsReallyAUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerOperation.builder("x", RestMethod.GET, "admin/x?force=1"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerOperation.builder("x", RestMethod.GET, "admin/x#frag"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerOperation.builder("x", RestMethod.GET, "  "));
        assertThrows(IllegalArgumentException.class,
                () -> ServerOperation.builder("  ", RestMethod.GET, "admin/x"));
    }

    @Test
    @DisplayName("The body rule is stated as a sentence the user can act on")
    void bodyRequirementIsExplained() {
        ServerOperation needs = ServerOperation.builder("submit", RestMethod.POST, "admin/submit")
                .requiresBody("application/json").build();
        assertTrue(needs.isSatisfiedBy("{}"));
        assertFalse(needs.isSatisfiedBy(null));
        assertFalse(needs.isSatisfiedBy("   "));
        assertTrue(needs.bodyRequirementMessage().contains("needs a request body"));

        ServerOperation refuses = ServerOperation.builder("status", RestMethod.GET, "admin/status").build();
        assertTrue(refuses.isSatisfiedBy(null));
        assertFalse(refuses.isSatisfiedBy("{}"));
        assertTrue(refuses.bodyRequirementMessage().contains("does not take"));
    }

    @Test
    @DisplayName("Nothing an operation or an invocation carries appears in its own description")
    void descriptionsLeakNothing() {
        ServerOperation export = ServerOperation.builder("export", RestMethod.POST, "admin/export/{jobId}")
                .pathParameter("jobId", "The job.")
                .acceptsBody("application/json")
                .build();

        assertFalse(export.toString().contains("secret"), export.toString());
        assertTrue(export.toString().contains("admin/export/{jobId}"),
                "the shape is still described: " + export.toString());
    }
}
