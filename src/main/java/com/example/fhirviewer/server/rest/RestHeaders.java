package com.example.fhirviewer.server.rest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable, case-insensitive, multi-valued set of HTTP header fields.
 *
 * <p>HTTP header names are case-insensitive, so a lookup for {@code Content-Type} has to
 * find {@code content-type}. Header values are not: a name may legally repeat, which is
 * how {@code Set-Cookie} and {@code Link} carry several entries, so every name maps to a
 * list and the first value is offered as a convenience.
 *
 * <p>{@link #toString()} redacts the fields that carry credentials. Header maps end up in
 * log lines and exception messages, and a leaked {@code Authorization} value is a
 * credential leak; the names are still shown so a reader can tell that auth was attempted.
 */
public final class RestHeaders {

    /**
     * True when the name is one whose value must never be printed.
     *
     * <p>Delegated to {@link com.example.fhirviewer.server.RequestHeaders} so the list of
     * credential-bearing names is written down once. The two types are used on opposite
     * sides of a request — one builds what an authentication mechanism contributes, the
     * other carries whatever a request or response ended up with — and a header that was
     * redacted on one side must not be printed on the other.
     */
    public static boolean isSecret(String name) {
        return com.example.fhirviewer.server.RequestHeaders.isSecret(name);
    }

    private static final String REDACTED = "<redacted>";

    private final Map<String, List<String>> values;

    private RestHeaders(Map<String, List<String>> values) {
        this.values = values;
    }

    /** Headers with nothing in them. Never {@code null}. */
    public static RestHeaders empty() {
        return new RestHeaders(Map.of());
    }

    /** Wraps a copy of the given headers; the argument is not retained. */
    public static RestHeaders of(Map<String, ? extends Collection<String>> source) {
        Objects.requireNonNull(source, "source");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Collection<String>> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            List<String> entries = new ArrayList<>();
            for (String value : entry.getValue()) {
                if (value != null) {
                    entries.add(value);
                }
            }
            if (!entries.isEmpty()) {
                copy.put(entry.getKey(), List.copyOf(entries));
            }
        }
        return new RestHeaders(Map.copyOf(copy));
    }

    /** A builder, for assembling headers one name at a time. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The first value for a header name, ignoring case, or {@code null} when absent.
     *
     * <p>This is the accessor the transport uses for {@code Content-Type} and friends,
     * where repeating the name would be a server bug rather than something to honour.
     */
    public String first(String name) {
        List<String> found = all(name);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Every value for a header name, ignoring case. Never {@code null}; may be empty. */
    public List<String> all(String name) {
        if (name == null) {
            return List.of();
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(wanted)) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    /** True when the header is present with at least one value, ignoring case. */
    public boolean contains(String name) {
        return !all(name).isEmpty();
    }

    /** The header names, in the casing they were supplied with. */
    public Set<String> names() {
        return values.keySet();
    }

    /** True when nothing was set. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** The number of distinct header names. */
    public int size() {
        return values.size();
    }

    /** An unmodifiable view of the headers, keeping the original casing. */
    public Map<String, List<String>> asMap() {
        return values;
    }

    /** A copy with one more value added under an existing or new name. */
    public RestHeaders with(String name, String value) {
        if (name == null || name.isBlank() || value == null) {
            return this;
        }
        Map<String, List<String>> copy = new LinkedHashMap<>(values);
        for (Map.Entry<String, List<String>> entry : copy.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                List<String> merged = new ArrayList<>(entry.getValue());
                merged.add(value);
                copy.put(entry.getKey(), List.copyOf(merged));
                return new RestHeaders(Map.copyOf(copy));
            }
        }
        copy.put(name, List.of(value));
        return new RestHeaders(Map.copyOf(copy));
    }

    /** A copy with {@code name} set to exactly one value, replacing any existing ones. */
    public RestHeaders withReplacing(String name, String value) {
        Objects.requireNonNull(name, "name");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase(name)) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        if (value != null && !value.isBlank()) {
            copy.put(name, List.of(value));
        }
        return new RestHeaders(Map.copyOf(copy));
    }

    /**
     * Every header with its values, with credential-bearing values replaced.
     *
     * <p>Used by logging and by the transport's own diagnostics, so that a failed request
     * can be described without ever printing a password or a token.
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("RestHeaders{");
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            text.append(entry.getKey()).append('=');
            if (isSecret(entry.getKey())) {
                text.append(REDACTED);
            } else {
                text.append(entry.getValue());
            }
        }
        return text.append('}').toString();
    }


    /** Assembles {@link RestHeaders} one name at a time. */
    public static final class Builder {

        private final Map<String, List<String>> values = new LinkedHashMap<>();

        private Builder() {
        }

        /** Adds a value, keeping any already present under the same name. */
        public Builder add(String name, String value) {
            if (name == null || name.isBlank() || value == null) {
                return this;
            }
            for (Map.Entry<String, List<String>> entry : values.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    List<String> merged = new ArrayList<>(entry.getValue());
                    merged.add(value);
                    values.put(entry.getKey(), List.copyOf(merged));
                    return this;
                }
            }
            values.put(name, new ArrayList<>(List.of(value)));
            return this;
        }

        /** Sets a name to exactly one value, replacing any already present. */
        public Builder set(String name, String value) {
            if (name == null || name.isBlank()) {
                return this;
            }
            values.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            if (value != null && !value.isBlank()) {
                values.put(name, new ArrayList<>(List.of(value)));
            }
            return this;
        }

        /** Adds a {@code Content-Type} header. */
        public Builder contentType(String contentType) {
            return set("Content-Type", contentType);
        }

        /** Adds an {@code Accept} header. */
        public Builder accept(String accept) {
            return set("Accept", accept);
        }

        /** Adds the FHIR JSON content type, the default this project negotiates. */
        public Builder fhirJson() {
            return contentType("application/fhir+json").accept("application/fhir+json");
        }

        public RestHeaders build() {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            values.forEach((name, list) -> copy.put(name, List.copyOf(list)));
            return new RestHeaders(Map.copyOf(copy));
        }
    }

}
