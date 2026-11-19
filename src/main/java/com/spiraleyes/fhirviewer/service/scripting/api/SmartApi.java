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
import com.spiraleyes.fhirviewer.server.smart.SmartUrls;

/**
 * Script-facing facade over an existing authorized SMART session. Never exposes tokens.
 *
 * <p>Reads use the session's bearer. Writes use it too, and are refused unless the console's
 * write opt-in is on: they are journalled and confirmed like any other write, while the
 * scopes the server granted decide what the token may actually do. Nothing here ever sends a
 * token anywhere but the URL the session was authorized for.</p>
 */
public class SmartApi {

    /** The journal name for a write through this session: no configured server owns it. */
    public static final String JOURNAL_SERVER_NAME = "SMART session";

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
        IBaseResource before = readBefore(current, type, resourceId);
        long started = System.nanoTime();
        try {
            ServerWriteResult result = access.update(current, resource, versionId);
            record("update", type, resourceId, result.versionId(),
                    WriteJournal.Status.SUCCEEDED, "", started,
                    new WriteJournal.Undo("update", type, resourceId, result.versionId(), before));
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
        IBaseResource before = readBefore(current, resourceType, resourceId);
        long started = System.nanoTime();
        try {
            ServerWriteResult result = access.patch(current, resourceType, resourceId, body,
                    format, versionId);
            record("patch", resourceType, resourceId, result.versionId(),
                    WriteJournal.Status.SUCCEEDED, "", started,
                    new WriteJournal.Undo("patch", resourceType, resourceId,
                            result.versionId(), before));
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
        // Read only after the confirmation, so the before-image is as close as possible to
        // the delete and a cancelled prompt costs no extra request.
        IBaseResource before = readBefore(current, resourceType, resourceId);
        try {
            access.delete(current, resourceType, resourceId, versionId);
            record("delete", resourceType, resourceId, versionId,
                    WriteJournal.Status.SUCCEEDED, "", started,
                    new WriteJournal.Undo("delete", resourceType, resourceId, versionId, before));
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
     * Reads the resource immediately before a write so the console can offer a best-effort
     * undo, exactly as a configured-server write does. A failed read must never block the
     * write the user approved: the undo is optional and this extra request is best effort.
     */
    private IBaseResource readBefore(SmartAuthorizationSession current, String resourceType,
            String resourceId) {
        if (writePolicy.journal() == null || current == null || access == null
                || resourceType == null || resourceId == null) {
            return null;
        }
        try {
            return access.read(current, resourceType, resourceId).orElse(null);
        } catch (ServerOperationException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Puts a captured before-image back, as the console's Undo Last Write does.
     *
     * <p>Local recovery only: this is not a script operation, and it deliberately ignores the
     * grant's operation and resource scope so narrowing the scope cannot strand a reversal. It
     * still needs an active session -- the bearer is the only credential -- and only ever
     * writes to the URL that session was granted for, which must be the URL the entry names:
     * a later session for another server must not receive this before-image. The two URLs are
     * compared after normalization, so the same server written with a trailing slash is not
     * mistaken for a different one.</p>
     *
     * <p>An update or patch is reverted with {@code If-Match} naming the version that write
     * produced, because the before-image's own version predates it. A delete is reverted by a
     * restore carrying {@code If-None-Match: *} after trying to check the id is still free, so a
     * resource recreated later is never overwritten; a session that may write but not read
     * still sends that conditional restore, and the server's refusal is what stops it.</p>
     *
     * @param serverBaseUrl the URL the journalled write was made against
     * @param plan the recorded plan, which must carry a before-image
     * @throws ScriptApiException when the session is gone or different, the plan has no
     *         before-image, or the server refuses the recovery write
     */
    public void restoreBeforeImage(String serverBaseUrl, WriteJournal.Undo plan) {
        Objects.requireNonNull(plan, "plan");
        IBaseResource before = plan.before();
        if (before == null) {
            throw new ScriptApiException("No before-image was captured for this write.");
        }
        if (plan.resourceType() == null || plan.resourceId() == null) {
            throw new ScriptApiException("The write has no resource id to restore.");
        }
        String currentUrl = sessionBaseUrl();
        if (currentUrl == null || access == null) {
            throw new ScriptApiException(
                    "No active SMART session, so its write cannot be undone here.");
        }
        if (!sameServer(currentUrl, serverBaseUrl)) {
            throw new ScriptApiException("The active SMART session is not the one that made this"
                    + " write (it is authorized for " + currentUrl + "), so the before-image"
                    + " cannot be restored here.");
        }
        String type = plan.resourceType();
        String id = plan.resourceId();
        boolean restoring = "delete".equals(plan.operation());
        if (restoring) {
            requireStillAbsent(type, id);
        }
        try {
            if (restoring) {
                access.restore(session, before);
            } else {
                access.update(session, before, plan.versionId());
            }
        } catch (ServerOperationException refused) {
            if (restoring && refused.kind() == ServerOperationException.Kind.CONFLICT) {
                throw new ScriptApiException(type + "/" + id + " was taken by a new resource"
                        + " before the restore arrived (" + refused.getMessage() + "), so the"
                        + " undo wrote nothing and will not overwrite it.", refused);
            }
            throw new ScriptApiException("Undo failed: " + refused.getMessage(), refused);
        }
    }

    /**
     * Cancels restoring a deleted resource when a readable id is already occupied again.
     *
     * <p>The boundary is the conditional write, not this read: the restore carries
     * {@code If-None-Match: *}, so the server refuses an id that something took between the
     * read and the write -- the one gap a read alone can only narrow. The read therefore only
     * makes the usual answer readable instead of a bare {@code 412}; it must never be able to
     * block the restore. A session may hold write scope without read scope, and a delete such
     * a session could have made must stay revertible, so a refused or failed read falls
     * through to the conditional write and the server decides.</p>
     */
    private void requireStillAbsent(String type, String id) {
        boolean present;
        try {
            present = access.read(session, type, id).isPresent();
        } catch (ServerOperationException unreadable) {
            // Not permitted, or not answered: the token may still write, so the conditional
            // restore is sent. Diagnostics already recorded the failed read.
            return;
        }
        if (present) {
            throw new ScriptApiException(type + "/" + id + " exists again, so restoring the"
                    + " before-image would overwrite a resource created after the delete."
                    + " Nothing was sent.");
        }
    }

    /**
     * True when both base URLs name the same server, compared after the normalization the
     * bearer's destination goes through. A URL that cannot be normalized matches nothing.
     */
    private static boolean sameServer(String one, String other) {
        try {
            return SmartUrls.normalizeFhirBase(one).equals(SmartUrls.normalizeFhirBase(other));
        } catch (IllegalArgumentException unusable) {
            return false;
        }
    }

    /** Records one attempt against the session's server, with no undo information. */
    private void record(String operation, String resourceType, String resourceId,
            String versionId, WriteJournal.Status status, String detail, long started) {
        record(operation, resourceType, resourceId, versionId, status, detail, started, null);
    }

    /**
     * Records one attempt against the session's server.
     *
     * <p>The plan travels with the entry, so a listener never sees a successful write before
     * its before-image is available and the console can enable Undo as soon as it appears. An
     * entry exists either way so the attempt is visible and a timeout is not silently retried.</p>
     */
    private void record(String operation, String resourceType, String resourceId,
            String versionId, WriteJournal.Status status, String detail, long started,
            WriteJournal.Undo undo) {
        WriteJournal journal = writePolicy.journal();
        if (journal == null) {
            return;
        }
        SmartAuthorizationSession current = session;
        journal.record(JOURNAL_SERVER_NAME, current == null ? "" : current.fhirBaseUrl(),
                operation, resourceType, resourceId, versionId, status, detail,
                Duration.ofNanos(Math.max(0L, System.nanoTime() - started)), undo);
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
            return new WriteOutcomeUnknownException(operation, JOURNAL_SERVER_NAME,
                    current == null ? "" : current.fhirBaseUrl(), failure);
        }
        String target = resourceType + (resourceId == null ? "" : "/" + resourceId);
        return new ScriptApiException("Cannot " + operation + " " + target
                + " through the SMART session: " + failure.getMessage(), failure);
    }
}
