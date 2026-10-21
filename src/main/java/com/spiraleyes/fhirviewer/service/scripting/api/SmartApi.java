package com.spiraleyes.fhirviewer.service.scripting.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.spiraleyes.fhirviewer.server.ServerOperationException;
import com.spiraleyes.fhirviewer.server.smart.SmartAuthorizationSession;
import com.spiraleyes.fhirviewer.server.smart.SmartFhirAccess;

/** Script-facing facade over an existing authorized SMART session. Never exposes tokens. */
public class SmartApi {

    private SmartAuthorizationSession session;
    private SmartFhirAccess access;

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
}
