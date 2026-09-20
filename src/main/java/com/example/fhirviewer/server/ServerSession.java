package com.example.fhirviewer.server;

import java.util.Objects;

/**
 * A single operation against a FHIR server.
 *
 * <p>A session pairs a server configuration with the authentication for one request or
 * one batch of requests. Because the secrets live on the session and not on
 * {@link FhirServerConfiguration}, persisting a server can never persist a credential.</p>
 */
public final class ServerSession {

    private final FhirServerConfiguration server;
    private final ServerAuthentication authentication;

    public ServerSession(FhirServerConfiguration server, ServerAuthentication authentication) {
        this.server = Objects.requireNonNull(server, "server");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
    }

    /** The server this session talks to. */
    public FhirServerConfiguration server() {
        return server;
    }

    /** How this session authenticates. */
    public ServerAuthentication authentication() {
        return authentication;
    }

    @Override
    public String toString() {
        return server.name() + " (" + authentication.displayName() + ")";
    }
}
