package com.example.fhirviewer.server.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.server.ServerOperationException;

/**
 * Tests the one place the REST layer's failures are translated into the exception type the
 * UI already understands. Getting the mapping wrong makes the status bar lie to the user.
 */
public class RestFailuresTest {

    private static final String INVALID_PARAM_JSON = "{ \"resourceType\": \"OperationOutcome\", "
            + "\"issue\": [ { \"severity\": \"error\", \"code\": \"invalid\", "
            + "\"diagnostics\": \"Unknown search parameter 'foo'\" } ] }";

    @Test
    @DisplayName("HTTP statuses map onto the plugin error kinds the UI already distinguishes")
    void statusMapping() {
        assertEquals(ServerOperationException.Kind.BAD_REQUEST, RestFailures.kindOfStatus(400));
        assertEquals(ServerOperationException.Kind.UNAUTHORIZED, RestFailures.kindOfStatus(401));
        assertEquals(ServerOperationException.Kind.FORBIDDEN, RestFailures.kindOfStatus(403));
        assertEquals(ServerOperationException.Kind.NOT_FOUND, RestFailures.kindOfStatus(404));
        assertEquals(ServerOperationException.Kind.CONFLICT, RestFailures.kindOfStatus(409));
        assertEquals(ServerOperationException.Kind.CONFLICT, RestFailures.kindOfStatus(412));
        assertEquals(ServerOperationException.Kind.UNSUPPORTED, RestFailures.kindOfStatus(501));
        assertEquals(ServerOperationException.Kind.SERVER_ERROR, RestFailures.kindOfStatus(500));
        assertEquals(ServerOperationException.Kind.SERVER_ERROR, RestFailures.kindOfStatus(503));
    }

    @Test
    @DisplayName("The exception for a failed response keeps the status and the server's reason")
    void httpFailureCarriesBoth() {
        RestResponse response = RestResponse.of(400, RestHeaders.empty(), INVALID_PARAM_JSON,
                new RestOutcomeParser().parse(INVALID_PARAM_JSON));

        ServerOperationException failure = RestFailures.httpFailure("search Patient", response);

        assertEquals(ServerOperationException.Kind.BAD_REQUEST, failure.kind());
        assertEquals(400, failure.httpStatus());
        assertTrue(failure.displayMessage().contains("Unknown search parameter"),
                "the server's own words must reach the user: " + failure.displayMessage());
    }

    @Test
    @DisplayName("A failure message never repeats the raw body, which can be patient data")
    void failureMessageDoesNotLeakTheBody() {
        RestResponse response = RestResponse.of(500, RestHeaders.empty(),
                "{\"resourceType\":\"OperationOutcome\",\"contained\":[{\"family\":\"Smith\"}]}",
                List.of());

        String message = RestFailures.httpFailure("save Patient", response).displayMessage();

        assertFalse(message.contains("Smith"), "the body must not be copied into a message");
    }

    @Test
    @DisplayName("Transport problems are told apart, because the advice differs")
    void transportClassification() {
        assertEquals(RestFailures.RestFailure.TIMEOUT,
                RestFailures.failureOf(new SocketTimeoutException("read timed out")));
        assertEquals(RestFailures.RestFailure.TIMEOUT,
                RestFailures.failureOf(new InterruptedIOException("timeout")));
        assertEquals(RestFailures.RestFailure.TLS,
                RestFailures.failureOf(new SSLHandshakeException("certificate not trusted")));
        assertEquals(RestFailures.RestFailure.NETWORK,
                RestFailures.failureOf(new UnknownHostException("no such host")));
        assertEquals(RestFailures.RestFailure.NETWORK,
                RestFailures.failureOf(new IOException("Connection refused")));
    }

    @Test
    @DisplayName("A wrapped cause is still classified, however deep the chain is")
    void classificationFollowsTheCauseChain() {
        IOException wrapped = new IOException("request failed",
                new SocketTimeoutException("connect timed out"));

        assertEquals(RestFailures.RestFailure.TIMEOUT, RestFailures.failureOf(wrapped));
    }

    @Test
    @DisplayName("A timeout is not reported as an unreachable server, which would be wrong advice")
    void timeoutIsNotUnreachable() {
        assertEquals(ServerOperationException.Kind.TIMEOUT,
                RestFailures.kindOf(RestFailures.RestFailure.TIMEOUT));
        assertEquals(ServerOperationException.Kind.UNREACHABLE,
                RestFailures.kindOf(RestFailures.RestFailure.NETWORK));
        assertEquals(ServerOperationException.Kind.UNREACHABLE,
                RestFailures.kindOf(RestFailures.RestFailure.TLS));
    }

    @Test
    @DisplayName("Each transport failure produces a message that says what to do next")
    void transportMessages() {
        assertTrue(RestFailures.transportFailure("read Patient", RestFailures.RestFailure.TIMEOUT,
                "https://x/fhir", null).getMessage().contains("did not answer in time"));
        assertTrue(RestFailures.transportFailure("read Patient", RestFailures.RestFailure.TLS,
                "https://x/fhir", null).getMessage().contains("secure connection"));
        assertTrue(RestFailures.transportFailure("read Patient", RestFailures.RestFailure.NETWORK,
                "https://x/fhir", null).getMessage().contains("Check the base URL"));
        assertTrue(RestFailures.transportFailure("read Patient", RestFailures.RestFailure.NONE,
                "https://x/fhir", null).getMessage().contains("could not be reached"));
    }

    @Test
    @DisplayName("A transport failure keeps its cause for the log and names no status")
    void transportFailureKeepsTheCause() {
        IOException cause = new IOException("Connection refused");

        ServerOperationException failure = RestFailures.transportFailure("read Patient",
                RestFailures.RestFailure.NETWORK, "https://x/fhir", cause);

        assertEquals(cause, failure.getCause());
        assertNull(failure.httpStatus(), "no response means no status to report");
    }

    @Test
    @DisplayName("NONE means the exchange completed, and never reaches a transport message")
    void noneIsNotATransportProblem() {
        assertEquals(ServerOperationException.Kind.UNREACHABLE,
                RestFailures.kindOf(RestFailures.RestFailure.NONE));
        assertEquals(RestFailures.RestFailure.NETWORK,
                RestFailures.failureOf(new RuntimeException("something odd")),
                "an unrecognised problem falls back to a network failure, never to NONE");
    }
}
