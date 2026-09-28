package com.example.fhirviewer.server.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One issue from a FHIR {@code OperationOutcome}, reduced to what a user can act on.
 *
 * <p>Servers report failures as an {@code OperationOutcome} whose {@code issue} array
 * carries the real reason a write was refused. Parsing it into HAPI model objects and
 * passing those around would drag the FHIR version into the UI, so it is reduced here to
 * plain strings that any layer can read, log or display.
 *
 * <p>The diagnostics are the server's own words and are deliberately kept verbatim: they
 * are the only thing that distinguishes "unknown search parameter" from "patient not
 * found" once the HTTP status has been reduced to a single number.
 *
 * @param severity    {@code fatal}, {@code error}, {@code warning} or {@code information},
 *                    or {@code null} when the server omitted it
 * @param code        the issue type code such as {@code not-found}, or {@code null}
 * @param diagnostics the server's human readable explanation, or {@code null}
 * @param expression   the FHIRPath the issue applies to, or {@code null}
 * @param location     where the issue applies, for example {@code Patient.name[0]};
 *                    never {@code null}, possibly empty
 */
public record RestOperationOutcome(String severity, String code, String diagnostics,
        String expression, List<String> location) {

    public RestOperationOutcome {
        location = List.copyOf(location == null ? List.of() : location);
    }

    /** A single issue with nothing but a message, used when a server sends a bare error. */
    public static RestOperationOutcome ofMessage(String severity, String diagnostics) {
        return new RestOperationOutcome(severity, null, diagnostics, null, List.of());
    }

    /**
     * True when this issue should stop the user continuing.
     *
     * <p>A {@code warning} or {@code information} still travels back to the caller — the
     * data is valid — but the UI shows it differently from a failure.
     */
    public boolean isError() {
        return !"warning".equalsIgnoreCase(severity) && !"information".equalsIgnoreCase(severity);
    }

    /**
     * One line suitable for a status bar or a dialog.
     *
     * <p>Never includes the location list when it is empty, and never throws when the
     * server sent an issue with nothing in it, which some vendor endpoints do.
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        if (severity != null && !severity.isBlank()) {
            text.append(severity).append(": ");
        }
        if (code != null && !code.isBlank()) {
            text.append('[').append(code).append("] ");
        }
        if (diagnostics != null && !diagnostics.isBlank()) {
            text.append(diagnostics.trim());
        } else if (code == null || code.isBlank()) {
            text.append("The server reported a problem but gave no detail.");
        }
        if (expression != null && !expression.isBlank()) {
            text.append(" (at ").append(expression.trim()).append(')');
        } else if (!location.isEmpty()) {
            text.append(" (at ").append(String.join(", ", location)).append(')');
        }
        return text.toString();
    }

    /**
     * A stable, secret-free rendering for logs and tests.
     *
     * <p>Diagnostics can name a patient, so this is the same text the user already sees
     * rather than anything extra; callers that must not log patient data should log the
     * severity and code from {@link #severity()} and {@link #code()} instead.
     */
    @Override
    public String toString() {
        return "RestOperationOutcome[severity=" + severity + ", code=" + code
                + ", issues=" + location.size() + "]";
    }

    /** Builds an unmodifiable copy, for callers assembling a list. */
    static List<RestOperationOutcome> copyOf(List<RestOperationOutcome> issues) {
        Objects.requireNonNull(issues, "issues");
        return List.copyOf(new ArrayList<>(issues));
    }
}
