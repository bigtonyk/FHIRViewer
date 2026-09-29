package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.ServerOperation;
import com.example.fhirviewer.server.ServerOperationInvocation;
import com.example.fhirviewer.server.ServerOperationParameter;
import com.example.fhirviewer.server.rest.RestMethod;

/**
 * The claim Phase 6 ends on, tested: a plugin can add an operation and the generic form
 * handles it correctly without the UI knowing anything about it.
 *
 * <p>The operation declared here is deliberately a stranger — a path parameter, two query
 * parameters, a header parameter and a body, none of which the application has ever seen.
 * If the generated form could not describe it, the mechanism would only work for the
 * operations its author happened to imagine, which is the failure mode the plan warns
 * about.</p>
 *
 * <p>These assertions are about the seam between a descriptor and a request, not about
 * JavaFX. {@link ServerOperationForm} is deliberately free of it, which is what makes the
 * central claim of this phase testable without a display.</p>
 */
class ServerOperationFormTest {

    /** A vendor endpoint with one input in each of the three locations, plus a body. */
    private static ServerOperation vendorOperation() {
        return ServerOperation.builder("export-jobs", RestMethod.POST, "admin/export/{jobId}")
                .displayName("Export Job")
                .pathParameter("jobId", "The job to report on.")
                .queryParameter("format", "ndjson, csv or bundle.", false)
                .queryParameter("since", "Only resources changed after this instant.", false)
                .headerParameter("X-Report-Mode", "summary or full.", false)
                .acceptsBody("application/json")
                .returns(ServerOperation.ResultKind.JSON)
                .build();
    }

    @Test
    @DisplayName("The form describes every parameter the plugin declared, in order")
    void exposesDeclaredParameters() {
        ServerOperationForm form = new ServerOperationForm(vendorOperation());
        assertEquals(List.of("jobId", "format", "since", "X-Report-Mode"),
                form.parameters().stream().map(ServerOperationParameter::name).toList());
    }

    /** Each location becomes its own field, which is what a form has to render. */
    @Test
    @DisplayName("Parameters are grouped by the part of the request they travel in")
    void distinguishesParameterLocations() {
        ServerOperation operation = vendorOperation();
        assertEquals(List.of("jobId"),
                operation.parametersAt(ServerOperationParameter.Location.PATH).stream()
                        .map(ServerOperationParameter::name).toList());
        assertEquals(List.of("format", "since"),
                operation.parametersAt(ServerOperationParameter.Location.QUERY).stream()
                        .map(ServerOperationParameter::name).toList());
        assertEquals(List.of("X-Report-Mode"),
                operation.parametersAt(ServerOperationParameter.Location.HEADER).stream()
                        .map(ServerOperationParameter::name).toList());
    }

    /** Values must reach the right part of the request, not merely be collected. */
    @Test
    @DisplayName("Entered values are placed in the path, query and header respectively")
    void routesValuesToTheirLocation() {
        ServerOperationForm form = new ServerOperationForm(vendorOperation())
                .set("jobId", "job-42")
                .set("format", "ndjson")
                .set("X-Report-Mode", "summary");
        ServerOperationInvocation invocation = form.toInvocation("{\"limit\":10}");

        assertEquals("job-42", invocation.pathParameter("jobId").orElseThrow());
        assertEquals(List.of("ndjson"), invocation.queryParameter("format"));
        assertEquals("summary", invocation.headerParameter("X-Report-Mode").orElseThrow());
        assertEquals("{\"limit\":10}", invocation.body());
    }

    /** A field the user never touched must not become an empty query parameter. */
    @Test
    @DisplayName("A blank optional value is dropped rather than sent empty")
    void dropsBlankOptionalValues() {
        ServerOperationForm form = new ServerOperationForm(vendorOperation()).set("jobId", "job-1");
        ServerOperationInvocation invocation = form.toInvocation(null);

        assertTrue(invocation.queryParameter("format").isEmpty(),
                "An untouched field was sent as an empty parameter");
        assertTrue(invocation.queryParameter("since").isEmpty());
        assertEquals("job-1", invocation.pathParameter("jobId").orElseThrow());
    }

    /** A required input with no value is refused before any request is attempted. */
    @Test
    @DisplayName("A missing required parameter is refused, naming the field")
    void refusesMissingRequiredParameter() {
        ServerOperation operation = ServerOperation.builder("read-export", RestMethod.GET,
                "admin/export/{jobId}")
                .displayName("Read Export")
                .pathParameter("jobId", "The job to report on.")
                .build();
        ServerOperationForm form = new ServerOperationForm(operation);

        assertTrue(form.missing().isPresent(), "The missing job id was not detected");
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> form.toInvocation(null));
        assertTrue(refusal.getMessage().contains("jobId"),
                "The message does not name the field: " + refusal.getMessage());
    }

    /** The same rule for a body, which is the other thing an operation can require. */
    @Test
    @DisplayName("A missing required body is refused, and a present one is accepted")
    void refusesMissingRequiredBody() {
        ServerOperationForm form = new ServerOperationForm(ServerOperation.builder("validate",
                RestMethod.POST, "Patient/$validate")
                .displayName("Validate")
                .requiresBody("application/fhir+json")
                .build());

        assertEquals("a request body", form.missing().orElseThrow());
        assertEquals("{\"resourceType\":\"Patient\"}",
                form.toInvocation("{\"resourceType\":\"Patient\"}").body());
    }

    /** An operation that needs nothing runs from an empty form. */
    @Test
    @DisplayName("An operation with no inputs needs no input from the user")
    void allowsAnEmptyForm() {
        ServerOperationForm form = new ServerOperationForm(ServerOperation.builder("server-log",
                RestMethod.GET, "admin/log")
                .displayName("Server Log")
                .build());

        assertTrue(form.missing().isEmpty());
        assertFalse(form.acceptsBody());
        assertEquals("", form.bodyHint());
        assertEquals("server-log", form.toInvocation(null).operationId());
    }

    /**
     * A typo in a field name would otherwise silently drop a value, so it is refused while
     * the form is being built rather than at request time.
     */
    @Test
    @DisplayName("A name the operation never declared is refused")
    void refusesUndeclaredParameterNames() {
        ServerOperationForm form = new ServerOperationForm(vendorOperation());
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> form.set("notARealParameter", "x"));
        assertTrue(refusal.getMessage().contains("notARealParameter"));
    }

    /** The body hint states the media type, because the format is not inferred. */
    @Test
    @DisplayName("The body hint names the media type the body is sent as")
    void statesTheBodyContentType() {
        assertTrue(new ServerOperationForm(vendorOperation()).bodyHint().contains("application/json"));
    }

    /** Two locations may share a name; the label has to keep them apart. */
    @Test
    @DisplayName("A field label says which part of the request it fills in")
    void labelsDisambiguateLocations() {
        assertEquals("Path: jobId *",
                ServerOperationForm.label(ServerOperationParameter.path("jobId", "", true)));
        assertEquals("Header: X-Report-Mode",
                ServerOperationForm.label(ServerOperationParameter.header("X-Report-Mode", "", false)));
    }

    /** The form must not echo entered values: a header value can be a token. */
    @Test
    @DisplayName("The form's own description never contains an entered value")
    void doesNotLogValues() {
        ServerOperationForm form = new ServerOperationForm(vendorOperation())
                .set("X-Report-Mode", "Bearer super-secret-token");
        assertFalse(form.toString().contains("super-secret-token"),
                "A value leaked into: " + form);
    }

    /** Query parameters keep the order the plugin declared them in. */
    @Test
    @DisplayName("Query parameters reach the request in declaration order")
    void keepsQueryParameterOrder() {
        ServerOperationForm form = new ServerOperationForm(vendorOperation())
                .set("jobId", "j")
                .set("since", "2024-01-01")
                .set("format", "csv");
        Map<String, List<String>> query = form.toInvocation(null).queryParameters();
        assertEquals(List.of("format", "since"), List.copyOf(query.keySet()));
    }
}
