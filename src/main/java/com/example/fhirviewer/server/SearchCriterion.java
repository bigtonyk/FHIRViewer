package com.example.fhirviewer.server;

import java.util.Objects;

/**
 * One search criterion: either a name/value pair such as <code>name = Smith</code>, or a
 * raw query string passed through to the server untouched.
 *
 * <p>Two kinds, because the pair form and the raw form are genuinely different requests.
 * A pair is built by the plugin, so it can be translated for a vendor server and a
 * malformed name caught before anything is sent. A raw string is the user's own search,
 * copied from a browser address bar or a specification example, and the point of it is
 * that the viewer does not second-guess it — prefixes, modifiers, chains, {@code _sort},
 * {@code _count} and anything a vendor has added all work, because none of it is parsed.</p>
 *
 * <p>Modifying one of these means understanding what the raw form is for: anything sent
 * raw is sent to whatever host the server definition names, with whatever that host
 * makes of it. It is not a security boundary, only a convenience.</p>
 */
public final class SearchCriterion {

    /** What kind of criterion this is. */
    public enum Kind {
        /** A name and a value, built by the plugin. The default and the common case. */
        EXACT,
        /** A query string appended to the search URL verbatim. */
        RAW
    }

    private final Kind kind;
    private final String name;
    private final String value;

    public SearchCriterion(String name, String value) {
        this(Kind.EXACT, name, value);
    }

    private SearchCriterion(Kind kind, String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A search parameter name is required.");
        }
        this.kind = kind == null ? Kind.EXACT : kind;
        this.name = name;
        this.value = Objects.requireNonNull(value, "value");
    }

    /**
     * A query string sent to the server as written.
     *
     * <p>The text may or may not include the resource type and a leading {@code ?}. Both
     * are stripped, because the type is chosen separately and a leading {@code ?} is
     * punctuation the user typed rather than part of the query. Everything after the first
     * {@code ?} or {@code /} is kept exactly as given, including prefixes, modifiers,
     * chains, ampersands and {@code _sort}.</p>
     *
     * @throws IllegalArgumentException when nothing usable is left after stripping
     */
    public static SearchCriterion raw(String query) {
        String text = query == null ? "" : query.trim();
        // Accept what a user is likely to paste: "Patient?name=Smith", "?name=Smith",
        // "Patient?..." or just "name=Smith".
        int question = text.indexOf('?');
        if (question >= 0) {
            text = text.substring(question + 1).trim();
        } else {
            int slash = text.indexOf('/');
            if (slash >= 0) {
                text = text.substring(slash + 1).trim();
            }
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException(
                    "Enter a search string, for example name=Smith or birthdate=1990-01-01.");
        }
        return new SearchCriterion(Kind.RAW, "raw", text);
    }

    /** What kind of criterion this is. Never {@code null}. */
    public Kind kind() {
        return kind;
    }

    /** True when this is a raw query string rather than a name/value pair. */
    public boolean isRaw() {
        return kind == Kind.RAW;
    }

    /** The search parameter name, for example <code>name</code>. */
    public String name() {
        return name;
    }

    /** The value to look for, for example <code>Smith</code>. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return isRaw() ? "?" + value : name + "=" + value;
    }
}
