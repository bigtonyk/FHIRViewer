package com.example.fhirviewer.server;

/**
 * How a server is authenticated to, as a value the UI can offer and the server layer can
 * act on.
 *
 * <p>JavaFX-free on purpose. A dialog needs to show these as choices, but a choice list is
 * presentation; what a request does with it is not. Keeping the three options here means the
 * dialog does not have to know that "basic" means an {@code Authorization} header built from
 * a user name and password, and the server layer does not have to know how it was chosen.</p>
 *
 * <p>The secret itself never travels through here. A {@code ServerAuthentication} carries it
 * on the session, and this enum says only which kind is in use — so a list of these can be
 * logged, persisted, or shown in a form without leaking anything.</p>
 */
public enum ServerAuthKind {

    /** No credentials are sent. The default, and the only kind that needs nothing stored. */
    ANONYMOUS("None (anonymous)"),

    /** An HTTP Basic {@code Authorization} header built from a user name and password. */
    BASIC("User name and password"),

    /** A bearer token, sent as {@code Authorization: Bearer <token>}. */
    BEARER("Bearer token");

    private final String label;

    ServerAuthKind(String label) {
        this.label = label;
    }

    /** The text shown to the user for this option. */
    public String label() {
        return label;
    }

    /** True when this kind sends no credentials at all. */
    public boolean isAnonymous() {
        return this == ANONYMOUS;
    }

    /**
     * True when this kind needs a user name as well as a secret.
     *
     * <p>Basic pairs a name with a password; a bearer token stands alone. A form uses this
     * to decide whether to show the user name field, rather than hard-coding the rule.</p>
     */
    public boolean needsUserName() {
        return this == BASIC;
    }

    /**
     * The kind named by {@code name}, or {@link #ANONYMOUS} when it is absent or unknown.
     *
     * <p>Falls back rather than throwing because this is reached from a persisted settings
     * file, which a user or a hand edit may have changed. An unrecognised value means "send
     * no credentials", which is the safe direction to fail.</p>
     */
    public static ServerAuthKind fromName(String name) {
        if (name == null || name.isBlank()) {
            return ANONYMOUS;
        }
        for (ServerAuthKind kind : values()) {
            if (kind.name().equalsIgnoreCase(name.trim())) {
                return kind;
            }
        }
        return ANONYMOUS;
    }

    @Override
    public String toString() {
        return label;
    }
}
