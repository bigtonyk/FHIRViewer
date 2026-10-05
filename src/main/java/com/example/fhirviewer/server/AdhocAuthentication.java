package com.example.fhirviewer.server;

import java.util.Objects;

/**
 * Builds a {@link ServerAuthentication} from what a user typed into the REST console.
 *
 * <p>The console offers the same three kinds the server manager already offers, and each
 * of them already exists as a class — {@link AnonymousServerAuthentication},
 * {@link BasicServerAuthentication} and {@link BearerServerAuthentication}. What was
 * missing is the one step between "the form has three fields" and "the transport has
 * headers". This is that step, and it lives here rather than in the dialog for the reason
 * {@link ServerAuthKind} lives outside the UI package: which mechanism a choice means is
 * not presentation.
 *
 * <p><b>Refusing is a feature here.</b> A blank user name with the Basic kind selected, or
 * a blank token with the bearer kind, throws with a sentence naming the empty field. That
 * is deliberate rather than falling back to anonymous: silently sending an unauthenticated
 * request when the user believes they are authenticated produces a {@code 401} that reads
 * as a permissions problem and sends them looking in the wrong place. Failing before the
 * socket opens names the thing that is actually wrong.
 *
 * <p>Nothing here logs, and nothing here holds a secret beyond the call: the returned
 * authentication carries it for the lifetime of a session, exactly as it does everywhere
 * else, and its own {@code toString()} never prints it.
 */
public final class AdhocAuthentication {

    private AdhocAuthentication() {
    }

    /**
     * The authentication the user's choices describe.
     *
     * @param kind     which mechanism to use; {@code null} means anonymous
     * @param userName the user name, for {@link ServerAuthKind#BASIC}; may be {@code null}
     * @param password the password, for {@link ServerAuthKind#BASIC}; may be {@code null}
     * @param token    the access token, for {@link ServerAuthKind#BEARER}; may be {@code null}
     * @return the authentication to put on the session, never {@code null}
     * @throws IllegalArgumentException when the chosen kind is missing a required field,
     *         with a message naming that field
     */
    public static ServerAuthentication of(ServerAuthKind kind, String userName,
            String password, String token) {
        Objects.requireNonNull(AdhocAuthentication.class);
        ServerAuthKind chosen = kind == null ? ServerAuthKind.ANONYMOUS : kind;
        return switch (chosen) {
            case ANONYMOUS -> AnonymousServerAuthentication.INSTANCE;
            case BASIC -> new BasicServerAuthentication(
                    requireText(userName, "user name"),
                    password == null ? "" : password);
            case BEARER -> new BearerServerAuthentication(requireText(token, "access token"));
        };
    }

    /** True when this kind needs something the user has not supplied. */
    public static boolean isMissingField(ServerAuthKind kind, String userName, String token) {
        ServerAuthKind chosen = kind == null ? ServerAuthKind.ANONYMOUS : kind;
        return switch (chosen) {
            case ANONYMOUS -> false;
            case BASIC -> userName == null || userName.isBlank();
            case BEARER -> token == null || token.isBlank();
        };
    }

    /**
     * The value a missing field should be reported as, for a message rather than a throw.
     *
     * <p>Kept next to {@link #isMissingField} so the rule about which kind needs what lives
     * in exactly one place, and a caller can ask the question without catching anything.
     */
    public static String missingFieldLabel(ServerAuthKind kind) {
        ServerAuthKind chosen = kind == null ? ServerAuthKind.ANONYMOUS : kind;
        return switch (chosen) {
            case ANONYMOUS -> null;
            case BASIC -> "user name";
            case BEARER -> "access token";
        };
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Enter a " + field + " first.");
        }
        return value;
    }

    /** Never returns anything containing a credential. */
    @Override
    public String toString() {
        return "AdhocAuthentication";
    }
}