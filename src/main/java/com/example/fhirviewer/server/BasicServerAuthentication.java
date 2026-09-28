package com.example.fhirviewer.server;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * HTTP Basic authentication: a user name and password sent on every request.
 *
 * <p>The credentials live on the session for the lifetime of one operation and are never
 * written to disk by this class — persistence is {@link PluginSettingsStore}'s job, and it
 * stores them encrypted via {@link SecretBox}.</p>
 *
 * <p>{@link #toString()} deliberately never reveals the password, because authentication
 * objects end up in log lines and error messages.</p>
 */
public final class BasicServerAuthentication implements ServerAuthentication {

    private final String userName;
    private final String password;

    /**
     * @param userName the user name; must not be blank
     * @param password the password; must not be {@code null}
     */
    public BasicServerAuthentication(String userName, String password) {
        if (userName == null || userName.isBlank()) {
            throw new IllegalArgumentException("A user name is required for basic authentication.");
        }
        this.userName = userName.trim();
        this.password = Objects.requireNonNull(password, "password");
    }

    /**
     * Builds the credentials from saved settings, falling back to anonymous access when
     * the settings carry no user name.
     */
    public static ServerAuthentication from(PluginSettings settings) {
        Objects.requireNonNull(settings, "settings");
        if (!settings.hasCredentials()) {
            return AnonymousServerAuthentication.INSTANCE;
        }
        return new BasicServerAuthentication(
                settings.userName() == null ? "" : settings.userName(),
                settings.password() == null ? "" : settings.password());
    }

    @Override
    public String type() {
        return "basic";
    }

    @Override
    public String displayName() {
        return "Basic (user name and password)";
    }

    @Override
    public boolean isAnonymous() {
        return false;
    }

    /** The user name sent to the server. */
    public String userName() {
        return userName;
    }

    /**
     * The value for the {@code Authorization} header, or {@code null} when there is
     * nothing to send. Kept package-private so only the client wiring uses it.
     */
    String authorizationHeaderValue() {
        String pair = userName + ':' + password;
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "Basic as " + userName;
    }
}
