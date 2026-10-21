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

    private final SmartAuthorizationSession session;
    private final SmartFhirAccess access;

    public SmartApi(SmartAuthorizationSession session, SmartFhirAccess access) {
        this.session = session;
        this.access = access;
    }

    public SmartApi() {
        this(null, null);
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
