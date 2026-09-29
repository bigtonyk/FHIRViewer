package com.example.fhirviewer.server;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One of the REST interactions a FHIR server can advertise for a resource type.
 *
 * <p>These are exactly the {@code CapabilityStatement.rest.resource.interaction.code}
 * values from R4, named in Java. Modelling them as an enum rather than as free strings
 * is what lets the rest of the application ask "can this server patch?" instead of
 * assuming it can: the plan is explicit that PATCH, history, batch, transactions and
 * conditional operations are all optional, and a server that omits an interaction is
 * saying "do not send me that" rather than "I forgot to mention it".
 *
 * <p>A code the running server invents is simply not mapped, rather than being guessed
 * at: an unknown interaction is one this build cannot offer, and refusing it is the
 * honest answer.
 */
public enum ServerInteraction {

    /** {@code read} — {@code GET [type]/[id]}. */
    READ("read"),

    /** {@code vread} — {@code GET [type]/[id]/_history/[vid]}. */
    VREAD("vread"),

    /** {@code update} — {@code PUT [type]/[id]}. */
    UPDATE("update"),

    /** {@code create} — {@code POST [type]}. */
    CREATE("create"),

    /** {@code delete} — {@code DELETE [type]/[id]}. */
    DELETE("delete"),

    /** {@code history} — the {@code [type]} and {@code [type]/[id]} history reads. */
    HISTORY("history"),

    /** {@code patch} — {@code PATCH [type]/[id]}, in a server-agreed patch format. */
    PATCH("patch"),

    /**
     * {@code search} — {@code GET [type]?params}, which is what returns a Bundle.
     *
     * <p>See {@link #ALIASES}: R4 declares this as {@code search-type}, and several
     * servers write {@code search}. Both mean the same thing here.</p>
     */
    SEARCH("search"),

    /** {@code operation} — {@code $}-prefixed operations at this level. */
    OPERATION("operation");

    /**
     * The codes some servers and some FHIR model versions use for the same thing.
     *
     * <p>Worth writing down rather than leaving to chance: R4's
     * {@code CapabilityStatementResourceInteraction} has no {@code search} code — search
     * is declared through {@code search-type} — yet plenty of servers and documentation
     * write {@code search} there anyway, and HAPI's own R4 enum only accepts
     * {@code search-type} and the two split history codes. A viewer that refused a
     * statement over that spelling would report a perfectly good server as unreadable,
     * so the spellings are folded together here, once.</p>
     */
    private static final Map<String, ServerInteraction> ALIASES = Map.of(
            "search", SEARCH,
            "search-type", SEARCH,
            "vsearch", SEARCH,
            "history-instance", HISTORY,
            "history-type", HISTORY);

    private final String code;

    ServerInteraction(String code) {
        this.code = code;
    }

    /** The code as it appears in a {@code CapabilityStatement}. */
    public String code() {
        return code;
    }

    /**
     * The interaction a server advertised, or empty when the code is absent or unknown.
     *
     * <p>Matched case-insensitively and tolerant of the alternative spellings in
     * {@link #ALIASES}, because servers differ on all three counts and none of the
     * differences change what the server can do.</p>
     */
    public static Optional<ServerInteraction> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String wanted = code.trim().toLowerCase(Locale.ROOT);
        ServerInteraction alias = ALIASES.get(wanted);
        if (alias != null) {
            return Optional.of(alias);
        }
        for (ServerInteraction candidate : values()) {
            if (candidate.code.equals(wanted)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
