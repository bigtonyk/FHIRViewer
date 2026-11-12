package com.spiraleyes.fhirviewer.service.scripting.testing;

import java.util.Objects;

import com.spiraleyes.fhirviewer.service.scripting.ScriptRunResult;

/**
 * One case's verdict and the line that explains a failure.
 *
 * <p>A case passes only when its run completed with no errors. A rejected
 * snippet, a thrown exception (assertions included), a stop or a session-level
 * failure all fail the case, and {@link #detail()} carries the first useful
 * message so the console can name the failure without replaying the output.</p>
 *
 * @param name   the case name
 * @param status pass or fail
 * @param detail the first diagnostic, exception or error line; empty on pass
 */
public record SuiteCaseResult(String name, Status status, String detail) {

    /** What a case did. */
    public enum Status {
        PASSED, FAILED
    }

    public SuiteCaseResult {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
    }

    /** Classifies one executed case. */
    public static SuiteCaseResult from(String name, ScriptRunResult result) {
        Objects.requireNonNull(result, "result");
        if (result.status() == ScriptRunResult.Status.COMPLETED && !result.hasErrors()) {
            return new SuiteCaseResult(name, Status.PASSED, "");
        }
        ScriptRunResult.Entry reason = result.first(ScriptRunResult.Kind.EXCEPTION);
        if (reason == null) {
            reason = result.first(ScriptRunResult.Kind.DIAGNOSTIC);
        }
        if (reason == null) {
            reason = result.first(ScriptRunResult.Kind.ERROR);
        }
        String detail = reason == null ? result.status().name() : reason.text();
        return new SuiteCaseResult(name, Status.FAILED, detail);
    }

    public boolean passed() {
        return status == Status.PASSED;
    }
}
