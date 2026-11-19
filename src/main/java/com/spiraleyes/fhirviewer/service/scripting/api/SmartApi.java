package com.spiraleyes.fhirviewer.service.scripting.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;

import com.spiraleyes.fhirviewer.server.PatchFormat;
import com.spiraleyes.fhirviewer.server.ServerOperationException;
import com.spiraleyes.fhirviewer.server.ServerWriteResult;
import com.spiraleyes.fhirviewer.server.smart.SmartAuthorizationSession;
import com.spiraleyes.fhirviewer.server.smart.SmartFhirAccess;

/**
 * Script-facing facade over an existing authorized SMART session. Never exposes tokens.
 *
 * <p>Reads use the session's bearer. Writes use it too, and are refused unless the console's
 * write opt-in is on: they are journalled and confirmed like any other write, while the
 * scopes the server granted decide what the token may actually do. Nothing here ever sends a
 * token anywhere but the URL the session was authorized for.</p>
 */
public class SmartApi {

    private volatile SmartAuthorizationSession session;
    private volatile SmartFhirAccess access;
    private volatile WritePolicy writePolicy = WritePolicy.READ_ONLY;

    public SmartApi(SmartAuthorizationSession session, SmartFhirAccess access) {
        this.session = session;
        this.access = access;
    }

    public SmartApi() {
        this(null, null);
    }

    /**
     * Replaces the backing SMART session and accessor, for example when a launch completes
     * and an authorized session becomes available. Safe to call from any thread; the next
     * script evaluation sees the new session.
     *
     * @param session the active SMART session, or {@code null} to clear it
     * @param access the authorized FHIR accessor for the session, or {@code null}
     */
    public void updateSession(SmartAuthorizationSession session, SmartFhirAccess access) {
        this.session = session;
        this.access = access;
    }

    /**
     * Applies the console's write grant, or clears it when writes are turned off. Reads are
     * unaffected.
     */
    public void useWritePolicy(WritePolicy policy) {
        this.writePolicy = policy == null ? WritePolicy.READ_ONLY : policy;
    }

    public boolean isSessionActive() {
        return session != null && !session.isLoggedOut() && !session.isExpired();
    }

    public List<String> grantedScopes() {
        if (session == null) return List.of();
        String scope = session.grantedScope();
        if (scope == null || scope.isBlank()) return List.of();
        return List.of(scope.split("\\s+"));
    }

    public String launchPatientId() {
        return session == null ? null : session.patient();
    }

    public String launchEncounterId() {
        return session == null ? null : session.encounter();
    }

    public IBaseResource read(String resourceType, String id) throws ScriptApiException {
        if (!isSessionActive()) throw new ScriptApiException("No active SMART session");
        try {
            Optional<IBaseResource> r = access.read(session, resourceType, id);
            return r.orElse(null);
        } catch (ServerOperationException e) {
            throw new ScriptApiException("SMART read failed: " + e.getMessage(), e);
        }
    }

    /** True when the console's write opt-in currently allows writes through this session. */
    public boolean isWriteEnabled() {
        return writePolicy.isEnabled();
    }

    /**
     * The FHIR base URL this session's token may be sent to, or {@code null} when no usable
     * session is active. Named so the console's write warning can say where SMART writes
     * would go; the URL carries no token.
     */
    public String sessionBaseUrl() {
        return isSessionActive() ? session.fhirBaseUrl() : null;
    }

    /** Creates a resource through the session. Refused unless the console's writes are on. */
    public ServerWriteResult create(IBaseResource resource) {
        Objects.requireNonNull(resource, "resource");
        String type = resource.fhirType();
        SmartAuthorizationSession current = requireWrite("create", type);
        long started = System.nanoTime();
        try {
            ServerWriteResult result = access.create(current, resource);
            record("create", type, result.resourceId(), result.versionId(),
                    WriteJournal.Status.SUCCEEDED, "", started);
            return result;
        } catch (ServerOperationException e) {
            throw failedWrite("create", type, null, null, e, started);
        }
    }

    /** Replaces a resource through the session. Refused unless the console's writes are on. */
    public ServerWriteResult update(IBaseResource resource) {
        Objects.requireNonNull(resource, "resource");
        String type = resource.fhirType();
        IIdType id = resource.getIdElement();
        if (id == null || !id.hasIdPart()) {
            throw new ScriptApiException("Cannot update " + type + ": it has no server id.");
        }
        String resourceId = id.getIdPart();
        String versionId = resource.getMeta() == null ? null : resource.getMeta().getVersionId();
        SmartAuthorizationSession current = requireWrite("update", type);
        long started = System.nanoTime();
        try {
            ServerWriteResult result = access.update(current, resource, versionId);
            record("update", type, resourceId, result.versionId(),
                    WriteJournal.Status.SUCCEEDED, "", started);
            return result;
        } catch (ServerOperationException e) {
            throw failedWrite("update", type, resourceId, versionId, e, started);
        }
    }

    /** Applies a partial update through the session. Refused unless writes are on. */
    public ServerWriteResult patch(String resourceType, String resourceId, String body,
            PatchFormat format) {
        return patch(resourceType, resourceId, body, format, null);
    }

    /**
     * Applies a partial update with a known version. Passing the version sends
     * {@code If-Match}, so the server can refuse a patch against a stale copy.
     */
    public ServerWriteResult patch(String resourceType, String resourceId, String body,
            PatchFormat format, String versionId) {
        if (body == null || body.isBlank()) {
            throw new ScriptApiException("A patch body is required and must not be blank.");
        }
        Objects.requireNonNull(format, "format");
        SmartAuthorizationSession current = requireWrite("patch", resourceType);
        long started = System.nanoTime();
        try {
            ServerWriteResult result = access.patch(current, resourceType, resourceId, body,
                    format, versionId);
            record("patch", resourceType, resourceId, result.versionId(),
                    WriteJournal.Status.SUCCEEDED, "", started);
            return result;
        } catch (ServerOperationException e) {
            throw failedWrite("patch", resourceType, resourceId, versionId, e, started);
        }
    }

    /** Deletes a resource through the session. Refused unless writes are on. */
    public void delete(String resourceType, String resourceId) {
        delete(resourceType, resourceId, null);
    }

    /**
     * Deletes a resource with a known version. Passing the version sends {@code If-Match},
     * so the server can refuse to delete a resource that changed after it was read.
     */
    public void delete(String resourceType, String resourceId, String versionId) {
        SmartAuthorizationSession current = requireWrite("delete", resourceType);
        String summary = "Delete " + resourceType + "/" + resourceId
                + " through the SMART session (" + current.fhirBaseUrl() + ")"
                + (versionId == null ? "" : " version " + versionId);
        long started = System.nanoTime();
        if (!writePolicy.confirmDestructive(summary)) {
            record("delete", resourceType, resourceId, versionId,
                    WriteJournal.Status.CANCELLED, "cancelled", started);
            throw new WriteDeniedException("Delete cancelled.");
        }
        try {
            access.delete(current, resourceType, resourceId, versionId);
            record("delete", resourceType, resourceId, versionId,
                    WriteJournal.Status.SUCCEEDED, "", started);
        } catch (ServerOperationException e) {
            throw failedWrite("delete", resourceType, resourceId, versionId, e, started);
        }
    }

    /**
     * Checks the grant and returns the session a write may use.
     *
     * <p>The grant is checked first, so a script with writes off is told that rather than
     * being sent looking for a session; the session is checked before any request, so an
     * expired or logged-out session never produces a confusing 401.</p>
     */
    private SmartAuthorizationSession requireWrite(String operation, String resourceType) {
        writePolicy.requireWriteWithItsOwnAuthorization(operation, resourceType,
                "the SMART session");
        if (!isSessionActive() || access == null) {
            throw new ScriptApiException("No active SMART session");
        }
        return session;
    }

    /**
     * Records one attempt against the session's server.
     *
     * <p>No before-image is kept: undoing a SMART write is the server's own history or a
     * deliberate script's job, and the console's Undo resolves configured servers. The
     * entry exists so the attempt is visible and a timeout is not silently retried.</p>
     */
    private void record(String operation, String resourceType, String resourceId,
            String versionId, WriteJournal.Status status, String detail, long started) {
        WriteJournal journal = writePolicy.journal();
        if (journal == null) {
            return;
        }
        SmartAuthorizationSession current = session;
        journal.record("SMART session", current == null ? "" : current.fhirBaseUrl(), operation,
                resourceType, resourceId, versionId, status, detail,
                Duration.ofNanos(Math.max(0L, System.nanoTime() - started)));
    }

    /**
     * Reports a failed write, as unknown when the transport timed out after the request may
     * have been sent. A script must not retry that blindly: the change may already exist.
     */
    private ScriptApiException failedWrite(String operation, String resourceType,
            String resourceId, String versionId, ServerOperationException failure, long started) {
        boolean unknown = failure.kind() == ServerOperationException.Kind.TIMEOUT;
        record(operation, resourceType, resourceId, versionId,
                unknown ? WriteJournal.Status.UNKNOWN : WriteJournal.Status.FAILED,
                failure.kind().name(), started);
        if (unknown) {
            SmartAuthorizationSession current = session;
            return new WriteOutcomeUnknownException(operation, "SMART session",
                    current == null ? "" : current.fhirBaseUrl(), failure);
        }
        String target = resourceType + (resourceId == null ? "" : "/" + resourceId);
        return new ScriptApiException("Cannot " + operation + " " + target
                + " through the SMART session: " + failure.getMessage(), failure);
    }
}
