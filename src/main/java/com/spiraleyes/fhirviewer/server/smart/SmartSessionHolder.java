package com.spiraleyes.fhirviewer.server.smart;

import com.spiraleyes.fhirviewer.service.scripting.api.SmartApi;

/**
 * The one authorized SMART session the application currently holds, shared by the
 * Workbench and the Scripting Console.
 *
 * <p>The Workbench is modal, so a console cannot be open at the same time as it. Keeping
 * the session here is what lets a script run after the Workbench closes: the token stays in
 * memory for the life of the application, is never written to disk, and is replaced or
 * cleared as the Workbench authorizes or logs out.</p>
 */
public final class SmartSessionHolder {

    private final SmartApi api = new SmartApi();
    private SmartAuthorizationSession session;
    private SmartFhirAccess access;

    /** Makes {@code session} the current one and exposes it to scripts. */
    public synchronized void set(SmartAuthorizationSession session, SmartFhirAccess access) {
        this.session = session;
        this.access = access;
        api.updateSession(session, access);
    }

    /** Drops the current session, so scripts see no active SMART session. */
    public synchronized void clear() {
        set(null, null);
    }

    /** The current session, or {@code null}. */
    public synchronized SmartAuthorizationSession session() {
        return session;
    }

    /** The accessor that may read through {@link #session()}, or {@code null}. */
    public synchronized SmartFhirAccess access() {
        return access;
    }

    /** The script-facing facade, updated whenever the session changes. */
    public SmartApi api() {
        return api;
    }
}
