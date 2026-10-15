package com.spiraleyes.fhirviewer.server.smart;

import java.util.Objects;
import java.util.Optional;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.spiraleyes.fhirviewer.server.FhirServerPlugin;
import com.spiraleyes.fhirviewer.server.FhirServerPluginRegistry;
import com.spiraleyes.fhirviewer.server.ServerDefinition;
import com.spiraleyes.fhirviewer.server.ServerOperationException;
import com.spiraleyes.fhirviewer.server.ServerSession;

/**
 * The minimal authorized FHIR reads a SMART session enables, and only ever against the
 * server that granted the session.
 *
 * <p>This is Phase 04's operation surface. It sends no token of its own: every call
 * builds a fresh {@link ServerSession} whose base URL is derived from the
 * {@link SmartAuthorizationSession}'s own {@code fhirBaseUrl} — never from user input and
 * never from any other configured server — and whose authentication is a
 * {@link SmartBearerAuthorization} carved out of that same session. The session, the URL
 * and the token therefore travel together as one unit, which is what makes "the token
 * only reaches the intended server" a structural property rather than a rule someone has
 * to remember.</p>
 *
 * <p>The session is never stored here: this object is stateless and reusable, two
 * sessions never share state, and nothing survives a logout because the session itself
 * holds the token. A logged-out or expired session is refused before a socket opens, so a
 * stale token is never sent and a failure is reported as "authorize again" rather than
 * as a confusing 401. HTTP 401 and 403 pass through as their own
 * {@link ServerOperationException.Kind} values with the server's explanation; there is
 * no retry loop — one attempt, then a message the user can act on.</p>
 *
 * <p>Reads go through the application's existing {@link FhirServerPlugin} registry, so
 * an authorized read and an unauthenticated one differ in exactly one respect: which
 * authentication the session carries. No second FHIR client stack exists.</p>
 */
public final class SmartFhirAccess {

    private final FhirServerPluginRegistry registry;

    /**
     * @param registry the plugin registry that serves the session's server; never
     *                 {@code null}. The application's real registry is used in
     *                 production, so a vendor plugin still answers its own servers.
     */
    public SmartFhirAccess(FhirServerPluginRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Reads the patient context the server put in the token, when there is one.
     *
     * <p>This is the Phase 04 minimal operation: a launch that granted a patient context
     * can fetch that patient directly. A launch with no patient context is not a failure
     * and not an error — standalone launches and launches with only identity scopes
     * legitimately have none — so that case returns empty without opening a socket, and
     * the caller is expected to say so rather than imply something is broken.</p>
     *
     * @param session the authorized session; never {@code null}
     * @return the granted patient, or empty when the launch carried no patient context
     * @throws ServerOperationException when the read fails: an expired or logged-out
     *         session, a {@code 401} meaning the token is no longer accepted, a
     *         {@code 403} meaning the scopes do not cover this read, or a transport
     *         failure
     */
    public Optional<IBaseResource> patient(SmartAuthorizationSession session)
            throws ServerOperationException {
        Objects.requireNonNull(session, "session");
        requireUsable(session);
        String patientId = session.patient();
        if (patientId == null || patientId.isBlank()) {
            return Optional.empty();
        }
        return readGranted(session, "Patient", patientId);
    }

    /**
     * Reads one resource by type and id through the authorized session.
     *
     * <p>The general form of {@link #patient(SmartAuthorizationSession)}, offered so an
     * identity-scoped launch can fetch its {@code sub} or any other resource the granted
     * scopes cover. The scopes decide, not this class: a {@code 403} is reported exactly
     * as the server gave it, never retried and never worked around.</p>
     *
     * @param session the authorized session; never {@code null}
     * @param resourceType the FHIR resource type, for example {@code Patient}
     * @param resourceId the server-assigned id
     * @return the resource, or empty when the server says it does not exist
     * @throws ServerOperationException when the read fails; a {@code 404} is empty
     *         rather than thrown because "no such resource" is an answer, not a fault
     */
    public Optional<IBaseResource> read(SmartAuthorizationSession session,
            String resourceType, String resourceId) throws ServerOperationException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        if (resourceType.isBlank()) {
            throw new IllegalArgumentException("A resource type is required for a read.");
        }
        if (resourceId.isBlank()) {
            throw new IllegalArgumentException("A resource id is required for a read.");
        }
        requireUsable(session);
        return readGranted(session, resourceType.strip(), resourceId.strip());
    }

    /**
     * Refuses a session that must not send its token, before any socket opens.
     *
     * <p>Two cases, each with its own message, because they call for different actions:
     * a logged-out session has no token at all and must be re-authorized, and an expired
     * one still holds a token the server will reject — sending it would produce a 401
     * that looks like a permissions problem when the truth is "authorize again". Saying
     * it here is also what keeps the failure off any wire: nothing is transmitted.</p>
     */
    private static void requireUsable(SmartAuthorizationSession session)
            throws ServerOperationException {
        if (session.isLoggedOut()) {
            throw new ServerOperationException(ServerOperationException.Kind.UNAUTHORIZED,
                    "This session was logged out, so it has no access token to send. "
                            + "Authorize again before reading.");
        }
        if (session.isExpired()) {
            throw new ServerOperationException(ServerOperationException.Kind.UNAUTHORIZED,
                    "The access token has expired. Authorize again to get a new session.");
        }
    }

    /**
     * Performs one granted read through the existing plugin registry.
     *
     * <p>The server configuration handed to the plugin is built from the session's own
     * base URL and nothing else — this is the single point where the token's destination
     * is decided, and it cannot name a different server. A plugin that does not support
     * the configuration is a clear message rather than a null pointer: it means this
     * build cannot talk to that server, not that the session is bad.</p>
     *
     * <p>A {@code 404} becomes empty because a missing resource is an answer; every
     * other failure — {@code 401}, {@code 403}, transport — is thrown untouched so its
     * kind and the server's own diagnostics reach the caller.</p>
     */
    private Optional<IBaseResource> readGranted(SmartAuthorizationSession session,
            String resourceType, String resourceId) throws ServerOperationException {
        ServerSession serverSession = sessionFor(session);
        FhirServerPlugin plugin = registry.pluginFor(serverSession.server());
        if (plugin == null) {
            throw new ServerOperationException(ServerOperationException.Kind.UNSUPPORTED,
                    "No plugin in this build can talk to " + session.fhirBaseUrl()
                            + ", so the authorized read cannot be sent.");
        }
        try {
            return Optional.ofNullable(plugin.read(serverSession, resourceType, resourceId));
        } catch (ServerOperationException failure) {
            if (failure.kind() == ServerOperationException.Kind.NOT_FOUND) {
                return Optional.empty();
            }
            throw failure;
        }
    }

    /**
     * Builds the per-call session: the session's server plus the session's bearer.
     *
     * <p>A new {@link ServerSession} per call rather than a shared one, so concurrent
     * reads through two different SMART sessions can never pick up each other's
     * authentication — isolation is structural, not remembered. The base URL comes from
     * {@link SmartAuthorizationSession#fhirBaseUrl()}, which Phase 03 recorded as "the
     * URL this session's token may be sent to — and no other", and the plugin id and
     * FHIR version follow this build so the registry resolves the same plugin an
     * unauthenticated connection would get.</p>
     */
    private static ServerSession sessionFor(SmartAuthorizationSession session)
            throws ServerOperationException {
        String baseUrl;
        try {
            // The URL rule the whole SMART package shares: one scheme, path intact,
            // never a downgrade — a scheme-less issuer gets https, not a silent http.
            baseUrl = SmartUrls.normalizeFhirBase(session.fhirBaseUrl());
        } catch (IllegalArgumentException refused) {
            throw new ServerOperationException(ServerOperationException.Kind.BAD_REQUEST,
                    "This session's FHIR base URL cannot be used: " + refused.getMessage());
        }
        ServerDefinition server = ServerDefinition
                .named("SMART session", baseUrl)
                .fhirVersion("R4")
                .pluginId("standard-rest")
                .build();
        return new ServerSession(server, new SmartBearerAuthorization(session));
    }
}
