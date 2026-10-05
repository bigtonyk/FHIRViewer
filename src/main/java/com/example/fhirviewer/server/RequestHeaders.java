package com.example.fhirviewer.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The HTTP header fields one authentication mechanism contributes to a request.
 *
 * <p>This is the extension point that lets a new mechanism be added without touching
 * anything that sends a request. {@link ServerAuthentication#requestHeaders()} returns
 * one of these, and both the HAPI client in {@code StandardFhirRestPlugin} and the
 * generic transport in {@code JdkHttpRestClient} simply apply whatever it contains.
 * Basic, bearer and a SMART or vendor key all travel the same road from then on.
 *
 * <p><b>These are secrets.</b> An {@code Authorization} value is a base64 password or a
 * token, and every value here should be treated the same way, because a mechanism
 * cannot be assumed to be well behaved. {@link #toString()} therefore prints the names
 * and replaces every value with {@code <redacted>}, so a header set that reaches a log
 * line or an exception message cannot leak. That is the same rule
 * {@code RestHeaders} applies on the transport side, and this class owns the list so
 * it is written down once.
 *
 * <p>Immutable, and safe to share between threads: an authentication belongs to a
 * session, and a session may be used for several requests.
 */
public final class RequestHeaders {

    /** The header every bearer and basic mechanism uses. */
    public static final String AUTHORIZATION = "Authorization";

    /**
     * Header names whose value must never appear in a log line or a message.
     *
     * <p>{@code Set-Cookie} and {@code Cookie} are here because a session cookie is a
     * bearer credential in all but name. The API-key names cover the header conventions
     * Firely and several gateways use.
     */
    private static final Set<String> SECRET_NAMES = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "x-fhir-api-key");

    private static final String REDACTED = "<redacted>";

    private final Map<String, List<String>> values;

    private RequestHeaders(Map<String, List<String>> values) {
        this.values = values;
    }

    /** No headers at all, which is what anonymous access contributes. Never {@code null}. */
    public static RequestHeaders none() {
        return new RequestHeaders(Map.of());
    }

    /** One header, the common case: {@code Authorization} and nothing else. */
    public static RequestHeaders of(String name, String value) {
        return builder().set(name, value).build();
    }


    /** Wraps a copy of the given headers; the argument is not retained. */
    public static RequestHeaders of(Map<String, ? extends Collection<String>> source) {
        Objects.requireNonNull(source, "source");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((name, entries) -> {
            if (name != null && !name.isBlank() && entries != null && !entries.isEmpty()) {
                List<String> kept = new ArrayList<>();
                for (String value : entries) {
                    if (value != null) {
                        kept.add(value);
                    }
                }
                if (!kept.isEmpty()) {
                    copy.put(name, List.copyOf(kept));
                }
            }
        });
        return new RequestHeaders(Map.copyOf(copy));
    }

    /** A builder, for a mechanism that contributes more than one header. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The first value for a name, ignoring case, or {@code null} when absent.
     *
     * <p>Credential headers are single-valued, so this is what almost every caller
     * wants; {@link #all(String)} exists for the general case.
     */
    public String first(String name) {
        List<String> found = all(name);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Every value for a name, ignoring case. Never {@code null}; may be empty. */
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

    /** True when the name is present with at least one value, ignoring case. */
    public boolean contains(String name) {
        return !all(name).isEmpty();
    }

    /** True when nothing is set. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** The number of distinct header names. */
    public int size() {
        return values.size();
    }

    /** An unmodifiable view, keeping the casing they were supplied with. */
    public Map<String, List<String>> asMap() {
        return values;
    }

    /**
     * True when a header of this name carries a credential.
     *
     * <p>Case-insensitive, because HTTP header names are. The transport asks the same
     * question through this method, so a header cannot be redacted on one side of the
     * application and printed on the other.
     */
    public static boolean isSecret(String name) {
        return name != null && SECRET_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * The names, with every value replaced.
     *
     * <p>A credential set ends up in log lines and in the text of an exception, and a
     * printed {@code Authorization} is a leaked password. The names are still shown, so
     * a reader can tell that authentication was attempted and by which mechanism.
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("RequestHeaders{");
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            text.append(entry.getKey()).append('=');
            text.append(isSecret(entry.getKey()) ? REDACTED : entry.getValue());
        }
        return text.append('}').toString();
    }

    /** Assembles a {@link RequestHeaders} one name at a time. */
    public static final class Builder {

        private final Map<String, List<String>> values = new LinkedHashMap<>();

        private Builder() {
        }

        /** Sets a name to exactly one value, replacing any already present. */
        public Builder set(String name, String value) {
            if (name == null || name.isBlank()) {
                return this;
            }
            values.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            if (value != null && !value.isBlank()) {
                values.put(name, List.of(value));
            }
            return this;
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
            values.put(name, List.of(value));
            return this;
        }

        public RequestHeaders build() {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            values.forEach((name, list) -> copy.put(name, List.copyOf(list)));
            return new RequestHeaders(Map.copyOf(copy));
        }
    }
}
