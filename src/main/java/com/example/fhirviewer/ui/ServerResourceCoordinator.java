package com.example.fhirviewer.ui;

import java.util.Objects;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.example.fhirviewer.model.ValidationIssue;
import com.example.fhirviewer.model.ValidationReport;
import com.example.fhirviewer.server.FhirServerConfiguration;
import com.example.fhirviewer.server.FhirServerService;
import com.example.fhirviewer.server.ServerOperationException;
import com.example.fhirviewer.server.ServerOrigin;
import com.example.fhirviewer.server.ServerWriteResult;

/**
 * Decides what a write to a FHIR server will do, and performs it.
 *
 * <p>Every judgement a push needs lives here rather than in a dialog: whether the plugin
 * can write at all, whether the resource is valid enough to send, whether this is a
 * create or an update, and what a failure means for the origin the editor is holding. A
 * dialog is then left with layout and handler wiring, which is both the reason the
 * plan asks for this class and the reason these rules can be tested: a JavaFX dialog
 * cannot be exercised by a headless build, and the rules are the part that must not be
 * wrong.</p>
 *
 * <p>Nothing here touches a {@code Stage}, a {@code Node} or {@code Platform.runLater}.
 * The caller owns the thread, so this runs happily on a background thread, and the
 * result is a value rather than a callback.</p>
 *
 * <p><b>Three refusals, each before any request is made.</b> Nothing is sent when the
 * plugin cannot write, when the resource has a blocking validation error, or when the
 * resource is about to be written to a server it did not come from. The first two are
 * decided here; the third is enforced by {@link FhirServerService}, which is the only
 * place that knows the rule.</p>
 *
 * <p><b>A conflict is never resolved silently.</b> A {@code 412} comes back as its own
 * outcome carrying the reason, so the caller can offer reload-or-force. Overwriting by
 * default would be a data-loss risk in a tool pointed at clinical data.</p>
 */
public final class ServerResourceCoordinator {

    /** What happened to a push, in terms a dialog can act on. */
    public enum Outcome {
        /** The server created the resource; the origin now carries the new id. */
        CREATED,
        /** The server updated the resource; the origin now carries the new version. */
        UPDATED,
        /** Nothing was sent: the resource has validation errors. */
        BLOCKED,
        /** Nothing was sent: this server or plugin cannot write. */
        UNSUPPORTED,
        /** Nothing was written: the server copy changed since it was read. */
        CONFLICT,
        /** The write failed for some other reason; the message says which. */
        FAILED
    }

    /**
     * The outcome of one push.
     *
     * @param outcome what happened
     * @param origin  the origin the editor should now hold, or the one it should keep
     *                when nothing was written
     * @param write   what the server reported, or {@code null} when nothing was written
     * @param message one line a user can act on, never {@code null}
     * @param report  the validation that gated the push, or {@code null} when the
     *                resource never got as far as being validated
     */
    public record PushResult(Outcome outcome, ServerOrigin origin, ServerWriteResult write,
            String message, ValidationReport report) {

        public PushResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(message, "message");
        }

        /** True when the resource reached the server. */
        public boolean written() {
            return outcome == Outcome.CREATED || outcome == Outcome.UPDATED;
        }

        /** True when the user has to choose between reloading and overwriting. */
        public boolean isConflict() {
            return outcome == Outcome.CONFLICT;
        }
    }

    /** Runs validation for a resource; kept as a port so tests need no FHIR validator. */
    @FunctionalInterface
    public interface Validator {
        /**
         * @return the issues found, never {@code null}
         */
        ValidationReport validate(IBaseResource resource);
    }

    private final FhirServerService serverService;
    private final Validator validator;

    /**
     * @param serverService the service every call goes through, so the UI still never
     *                      talks to a plugin directly
     * @param validator     runs before every write
     */
    public ServerResourceCoordinator(FhirServerService serverService, Validator validator) {
        this.serverService = Objects.requireNonNull(serverService, "serverService");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * Validates the resource and, only if it is fit to send, writes it.
     *
     * <p>The order matters and is the point of this method. A viewer that pushes a
     * resource it knows to be invalid onto a live server is worse than one that refuses,
     * so {@link FhirServerService} is never reached with a blocking error. Warnings are
     * reported and the push continues: a warning is advice, and refusing on advice would
     * make the viewer unusable against a real server with its own opinions.</p>
     *
     * @param target   the server to write to
     * @param resource the edited resource
     * @param origin   where it came from, or {@code null} when it came from a file, which
     *                 makes this a create
     */
    public PushResult push(FhirServerConfiguration target, IBaseResource resource, ServerOrigin origin) {
        if (target == null) {
            return failure(Outcome.FAILED, origin, "No server was chosen.");
        }
        if (resource == null) {
            return failure(Outcome.FAILED, origin, "There is no resource to save.");
        }
        if (!serverService.supportsWrite(target)) {
            // Checked before validation: a plugin that cannot write refuses whatever it
            // is sent, so reporting a validation error here would be advice about a
            // request that was never going to be made.
            return failure(Outcome.UNSUPPORTED, origin,
                    "The " + target.pluginId() + " plugin cannot write to " + target.name()
                            + ". Save the resource to a file instead.");
        }
        ValidationReport report = validator.validate(resource);
        if (report == null) {
            report = ValidationReport.successful("");
        }
        if (!report.isValid()) {
            return blocked(origin, report);
        }
        boolean update = origin != null && origin.isSaved();
        try {
            ServerWriteResult written = update
                    ? serverService.update(target, resource, origin)
                    : serverService.create(target, resource);
            return writeSucceeded(target, origin, resource, written, report);
        } catch (ServerOperationException failure) {
            return writeFailed(origin, failure);
        } catch (RuntimeException failure) {
            return failure(Outcome.FAILED, origin, ServerErrors.describe(failure));
        }
    }

    /**
     * Sends the same write again without the version, which is what overwriting a
     * conflict means.
     *
     * <p>Validation deliberately does not run again: it ran immediately before the write
     * that produced the conflict and the user has changed nothing since. A second full
     * FHIR validation on one decision would make the retry noticeably slower for no
     * additional safety.</p>
     *
     * @param target   the server to write to
     * @param resource the resource being written
     * @param origin   the origin the conflict was raised against; its version is dropped
     *                 here so the retry goes without {@code If-Match}
     */
    public PushResult forceWrite(FhirServerConfiguration target, IBaseResource resource,
            ServerOrigin origin) {
        if (target == null || resource == null || origin == null || !origin.isSaved()) {
            return failure(Outcome.FAILED, origin,
                    "Only a resource that already exists on a server can be overwritten.");
        }
        try {
            ServerWriteResult written = serverService.update(target, resource,
                    origin.withoutVersion());
            return writeSucceeded(target, origin, resource, written, null);
        } catch (ServerOperationException failure) {
            return writeFailed(origin, failure);
        } catch (RuntimeException failure) {
            return failure(Outcome.FAILED, origin, ServerErrors.describe(failure));
        }
    }

    /**
     * Reports a completed write and the origin the editor should now hold.
     *
     * <p>A create with no origin gets one built from what the server returned, so the
     * next save updates rather than creating a second copy. The returned origin carries
     * whatever version the server reported - and, crucially, carries none when the server
     * reported none, so the next save does not claim a conflict check it could not
     * perform.</p>
     */
    private PushResult writeSucceeded(FhirServerConfiguration target, ServerOrigin origin,
            IBaseResource resource, ServerWriteResult written, ValidationReport report) {
        if (written == null) {
            return failure(Outcome.FAILED, origin,
                    "The server reported success but did not say what it stored.");
        }
        ServerOrigin base = origin != null ? origin
                : ServerOrigin.unsaved(target.pluginId(), target.baseUrl(), resource.fhirType());
        ServerOrigin rebased = base.rebased(written);
        StringBuilder message = new StringBuilder(written.created() ? "Created " : "Updated ")
                .append(rebased.resourceType()).append('/').append(rebased.resourceId())
                .append(" on ").append(target.name()).append('.');
        if (!rebased.hasVersion()) {
            // Said out loud rather than left implied: a viewer that cannot detect a
            // conflicting change must not look like one that can.
            message.append(" The server reported no version, so changes made by others cannot")
                    .append(" be detected before the next save.");
        }
        long warnings = report == null ? 0 : report.count(ValidationIssue.Severity.WARNING);
        if (warnings > 0) {
            message.append(" Sent with ").append(warnings).append(" warning(s).");
        }
        return new PushResult(written.created() ? Outcome.CREATED : Outcome.UPDATED,
                rebased, written, message.toString(), report);
    }

    /**
     * Turns a plugin failure into an outcome the UI can branch on.
     *
     * <p>The conflict gets its own outcome because it is the only failure where the
     * useful next step is a choice by the user rather than a fix to something.</p>
     */
    private PushResult writeFailed(ServerOrigin origin, ServerOperationException failure) {
        if (failure.kind() == ServerOperationException.Kind.CONFLICT) {
            return new PushResult(Outcome.CONFLICT, origin, null,
                    "The server copy changed since it was read. Reload it, or overwrite it "
                            + "deliberately.", null);
        }
        Outcome outcome = failure.kind() == ServerOperationException.Kind.UNSUPPORTED
                ? Outcome.UNSUPPORTED
                : Outcome.FAILED;
        return failure(outcome, origin, ServerErrors.describe(failure));
    }

    /**
     * Reports a write that validation stopped.
     *
     * <p>The counts are split by severity because they call for different actions: an
     * error has to be fixed before anything can be saved, a warning does not.</p>
     */
    private PushResult blocked(ServerOrigin origin, ValidationReport report) {
        long errors = report.count(ValidationIssue.Severity.ERROR)
                + report.count(ValidationIssue.Severity.FATAL);
        long warnings = report.count(ValidationIssue.Severity.WARNING);
        StringBuilder message = new StringBuilder("Not saved: the resource has ")
                .append(errors).append(errors == 1 ? " validation error" : " validation errors")
                .append(". Fix them, or save to a file instead.");
        if (warnings > 0) {
            message.append(" It also has ").append(warnings)
                    .append(warnings == 1 ? " warning" : " warnings").append('.');
        }
        return new PushResult(Outcome.BLOCKED, origin, null, message.toString(), report);
    }

    /**
     * Every message this class hands to the UI goes past {@link ServerErrors#redact},
     * because a message is the one part of a failed request a user will paste into a
     * support ticket, and a screenshot of that ticket should never be a screenshot of a
     * token.
     */
    private PushResult failure(Outcome outcome, ServerOrigin origin, String message) {
        return new PushResult(outcome, origin, null, ServerErrors.redact(message), null);
    }

    /** Never includes anything secret: this object holds a service and a validator. */
    @Override
    public String toString() {
        return "ServerResourceCoordinator[write-capable only]";
    }
}

