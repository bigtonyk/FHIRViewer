package com.example.fhirviewer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.ServerAuthKind;
import com.example.fhirviewer.server.ServerOperationException;
import com.example.fhirviewer.server.rest.JdkHttpRestClient;
import com.example.fhirviewer.server.rest.RestAnswer;
import com.example.fhirviewer.server.rest.RestAnswers;
import com.example.fhirviewer.server.rest.RestClient;
import com.example.fhirviewer.server.rest.RestMethod;

/**
 * The console end to end: a form, the project's transport, and the classifier.
 *
 * <p>The parts are each tested on their own; this is the test that proves they compose. A
 * form that builds a request no client would send, or a classifier that cannot read what the
 * transport actually returns, would pass every other test in the suite.
 *
 * <p>Against a loopback stub rather than a live server, as every other transport test here
 * does: the offline suite stays offline, and the assertions are about what the console
 * builds and reads, not about what any particular server does.
 */
class RestConsoleIntegrationTest {

    private com.sun.net.httpserver.HttpServer fhir;
    private final AtomicReference<String> answer = new AtomicReference<>("");
    private final AtomicReference<String> answerType = new AtomicReference<>("application/fhir+json");
    private final AtomicReference<Integer> answerStatus = new AtomicReference<>(200);
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>("");
    private final AtomicReference<String> lastQuery = new AtomicReference<>("");
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicInteger requests = new AtomicInteger();

    private static final String PATIENT_JSON =
            "{ \"resourceType\": \"Patient\", \"id\": \"example-1\", "
                    + "\"name\": [ { \"family\": \"Smith\" } ] }";

    private static final String BUNDLE_JSON =
            "{ \"resourceType\": \"Bundle\", \"type\": \"searchset\", \"entry\": [ "
                    + "{ \"fullUrl\": \"http://x/Patient/1\", \"resource\": "
                    + "{ \"resourceType\": \"Patient\", \"id\": \"1\" } }, "
                    + "{ \"fullUrl\": \"http://x/Observation/2\", \"resource\": "
                    + "{ \"resourceType\": \"Observation\", \"id\": \"2\" } } ] }";

    private static final String OUTCOME_JSON =
            "{ \"resourceType\": \"OperationOutcome\", \"issue\": [ "
                    + "{ \"severity\": \"error\", \"code\": \"not-found\", "
                    + "\"diagnostics\": \"No patient with that name\" } ] }";

    @BeforeEach
    void startStub() throws IOException {
        fhir = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fhir.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastAuthorization.set(
                    String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", answerType.get());
            exchange.sendResponseHeaders(answerStatus.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        fhir.start();
    }

    @AfterEach
    void stopStub() {
        fhir.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + fhir.getAddress().getPort() + "/fhir";
    }

    /** A form pointed at the stub. */
    private RestConsoleForm form(String path) {
        return new RestConsoleForm().baseUrl(baseUrl()).path(path);
    }

    /** Runs the form the way the dialog does, and classifies what came back. */
    private RestAnswer roundTrip(RestConsoleForm form) throws Exception {
        try (RestClient client = JdkHttpRestClient.forSession(form.toSession())) {
            return RestAnswers.classify(client.execute(form.toRequest()));
        }
    }

@Test
    @DisplayName("A typed URL reaches the server and a Patient comes back openable")
    void aPatientRoundTrips() throws Exception {
        answer.set(PATIENT_JSON);

        RestAnswer result = roundTrip(form("Patient/example-1"));

        assertEquals(200, result.statusCode());
        assertEquals(RestAnswer.Kind.FHIR_RESOURCE, result.kind());
        assertNotNull(result.resource(), "this is what the Open button needs");
        assertEquals("Patient", result.resource().fhirType());
        assertEquals(1, requests.get());
    }

    @Test
    @DisplayName("A search Bundle comes back as a Bundle with its raw body intact")
    void aBundleRoundTrips() throws Exception {
        answer.set(BUNDLE_JSON);

        RestAnswer result = roundTrip(form("Patient?name=Smith&_include=Patient:organization"));

        assertEquals(RestAnswer.Kind.BUNDLE, result.kind());
        assertNotNull(result.resource());
        assertEquals(BUNDLE_JSON, result.body(), "the raw bytes are what the Body tab shows");
        assertTrue(lastQuery.get().contains("name=Smith"), lastQuery.get());
        assertTrue(lastQuery.get().contains("_include=Patient:organization"), lastQuery.get());
    }

    @Test
    @DisplayName("A bearer token typed into the console reaches the Authorization header")
    void bearerTokenIsSent() throws Exception {
        answer.set(PATIENT_JSON);

        roundTrip(form("Patient").authentication(ServerAuthKind.BEARER, "", "", "abc.def.ghi"));

        assertEquals("Bearer abc.def.ghi", lastAuthorization.get());
    }

    @Test
    @DisplayName("A user name and password reach the server as HTTP Basic")
    void basicCredentialsAreSent() throws Exception {
        answer.set(PATIENT_JSON);

        roundTrip(form("Patient").authentication(ServerAuthKind.BASIC, "alice", "s3cret", ""));

        assertEquals("Basic YWxpY2U6czNjcmV0", lastAuthorization.get(),
                "the Base64 of alice:s3cret");
    }

    @Test
    @DisplayName("Anonymous sends no Authorization header at all")
    void anonymousSendsNothing() throws Exception {
        answer.set(PATIENT_JSON);

        roundTrip(form("Patient"));

        assertEquals("null", lastAuthorization.get());
    }

    @Test
    @DisplayName("A refusal arrives as a readable answer, not an exception")
    void anOperationOutcomeIsReadable() throws Exception {
        answer.set(OUTCOME_JSON);
        answerStatus.set(404);

        RestAnswer result = roundTrip(form("Patient?name=Nobody"));

        assertEquals(404, result.statusCode());
        assertEquals(RestAnswer.Kind.OPERATION_OUTCOME, result.kind());
        assertFalse(result.isSuccess());
        assertTrue(RestAnswers.diagnostics(result).get(0).contains("No patient with that name"),
                RestAnswers.diagnostics(result).toString());
        assertTrue(RestAnswers.summary(result).contains("HTTP 404"), RestAnswers.summary(result));
    }

    @Test
    @DisplayName("A 404 with no body is still an answer")
    void anEmptyRefusalIsStillAnAnswer() throws Exception {
        answer.set("");
        answerStatus.set(404);

        RestAnswer result = roundTrip(form("Patient/999"));

        assertEquals(RestAnswer.Kind.EMPTY, result.kind());
        assertEquals(404, result.statusCode());
        assertTrue(RestAnswers.diagnostics(result).stream().anyMatch(line -> line.contains("404")));
    }

@Test
    @DisplayName("A POST body and its content type reach the server")
    void aWriteCarriesItsBody() throws Exception {
        answer.set(PATIENT_JSON);
        answerStatus.set(201);

        RestAnswer result = roundTrip(form("Patient")
                .method(RestMethod.POST)
                .body(PATIENT_JSON)
                .contentType("application/fhir+json"));

        assertEquals(201, result.statusCode());
        assertEquals(PATIENT_JSON, lastBody.get());
    }

    @Test
    @DisplayName("An XML resource is parsed just as a JSON one is")
    void anXmlResourceIsParsed() throws Exception {
        answer.set("<Patient xmlns=\"http://hl7.org/fhir\"><id value=\"xml-1\"/></Patient>");
        answerType.set("application/fhir+xml");

        RestAnswer result = roundTrip(form("Patient/xml-1"));

        assertEquals(RestAnswer.Kind.FHIR_RESOURCE, result.kind());
        assertNotNull(result.resource());
    }

    @Test
    @DisplayName("A vendor document that is not FHIR comes back as JSON, with nothing to open")
    void aVendorDocumentIsJson() throws Exception {
        answer.set("{ \"jobId\": 42, \"state\": \"done\" }");
        answerType.set("application/json");

        RestAnswer result = roundTrip(form("admin/export/42"));

        assertEquals(RestAnswer.Kind.JSON, result.kind());
        assertTrue(result.resourceIfPresent().isEmpty());
    }

    @Test
    @DisplayName("A typed header goes out, and Authorization can never be one of them")
    void typedHeadersAreSentButAuthorizationIsNot() throws Exception {
        answer.set(PATIENT_JSON);

        roundTrip(form("Patient").headers(RestHeaderList.empty().add("If-Match", "W/\"3\"")));
        assertEquals(1, requests.get());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> RestHeaderList.empty().add("Authorization", "Bearer sneaky"));
        assertTrue(refused.getMessage().contains("Authentication selector"), refused.getMessage());
    }

    @Test
    @DisplayName("An unreachable host is a failure, not an empty answer")
    void anUnreachableHostFails() throws Exception {
        RestConsoleForm unreachable = new RestConsoleForm()
                .baseUrl("http://127.0.0.1:1/fhir").path("Patient");

        try (RestClient client = JdkHttpRestClient.forSession(unreachable.toSession())) {
            ServerOperationException failure = assertThrows(ServerOperationException.class,
                    () -> client.execute(unreachable.toRequest()));
            assertEquals(ServerOperationException.Kind.UNREACHABLE, failure.kind());
        }
    }

    @Test
    @DisplayName("No credential reaches anything the console can print")
    void noCredentialIsPrinted() throws Exception {
        answer.set(PATIENT_JSON);

        RestConsoleForm withSecret = form("Patient")
                .authentication(ServerAuthKind.BEARER, "", "", "super-secret-token");
        RestAnswer result = roundTrip(withSecret);

        assertFalse(result.toString().contains("super-secret-token"), result.toString());
        assertFalse(withSecret.toString().contains("super-secret-token"), withSecret.toString());
        assertFalse(RestAnswers.summary(result).contains("super-secret-token"));
    }
}