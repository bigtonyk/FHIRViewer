package com.spiraleyes.fhirviewer.server.smart;

import java.time.Instant;

/**
 * One structured diagnostic event from a SMART launch attempt.
 *
 * <p>Events describe what happened and when, but never secrets: callers must
 * not pass access tokens, refresh tokens, authorization codes, PKCE verifiers,
 * client secrets, raw authorization headers, or patient identifiers. The
 * recorder redacts anything that looks like one as a belt-and-braces measure.
 */
public record SmartDiagnosticsEvent(
        Instant instant,
        Phase phase,
        Kind kind,
        String message,
        String detail
) {

    /** The part of the launch the event belongs to. */
    public enum Phase {
        DISCOVERY, AUTHORIZATION, CALLBACK, TOKEN_EXCHANGE, SESSION,
        FHIR_ACCESS, VALIDATION
    }

    /** What happened within that phase. */
    public enum Kind {
        STARTED, COMPLETED, FAILED,
        REQUEST_INITIATED, BROWSER_LAUNCHED, BROWSER_UNAVAILABLE,
        RECEIVED,
        SUCCESS, FAILURE,
        CREATED, LOGGED_OUT, EXPIRED,
        REQUEST_STARTED, REQUEST_COMPLETED,
        CONTEXT_GRANTED, CONTEXT_ABSENT,
        ERROR
    }
}
