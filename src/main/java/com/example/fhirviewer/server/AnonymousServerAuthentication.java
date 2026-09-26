package com.example.fhirviewer.server;

/**
 * Anonymous access: no credentials are sent.
 *
 * <p>This is the only authentication provider needed for the initial server feature.
 * Basic and bearer-token providers can be added later behind {@link ServerAuthentication}
 * without changing anything else.</p>
 */
public final class AnonymousServerAuthentication implements ServerAuthentication {

    /** Shared instance: anonymous access carries no state. */
    public static final AnonymousServerAuthentication INSTANCE = new AnonymousServerAuthentication();

    private AnonymousServerAuthentication() {
    }

    @Override
    public String type() {
        return "anonymous";
    }

    @Override
    public String displayName() {
        return "None (anonymous)";
    }

    @Override
    public boolean isAnonymous() {
        return true;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
