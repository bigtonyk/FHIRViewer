package com.example.fhirviewer.server;

import java.util.Objects;

/**
 * One input an operation takes, described as data.
 *
 * <p>The three locations are modelled explicitly rather than as a name with a prefix
 * because they behave differently downstream: a path parameter is substituted into the URL
 * and percent-encoded, a query parameter is appended to the query string, and a header
 * parameter has to clear the safety rules in {@link PluginOperationClient} before it is
 * allowed on the wire at all.</p>
 *
 * <p>Header parameters are the reason this type exists rather than a bare string map. A
 * caller that could set an arbitrary header could set {@code Authorization}, and would
 * then be a second, unlogged, unencrypted credential path around the session's
 * {@link ServerAuthentication}. A declared header is a name the plugin chose, validated
 * once, with the value supplied per invocation.</p>
 *
 * <p>Built with {@link #path}, {@link #query} or {@link #header}, or the all-arguments
 * constructor when a plugin needs the location as a variable.</p>
 */
public record ServerOperationParameter(
        String name,
        Location location,
        String description,
        boolean required,
        boolean secret) {

    /** Which part of the request a parameter travels in. */
    public enum Location {
        /** Substituted into a {@code {name}} hole in the path, then percent-encoded. */
        PATH,
        /** Appended to the query string. */
        QUERY,
        /** Sent as a request header, subject to the transport's safety rules. */
        HEADER
    }

    public ServerOperationParameter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A parameter name is required.");
        }
        name = name.trim();
        location = Objects.requireNonNull(location, "location");
        description = description == null ? "" : description.trim();
        if (location == Location.HEADER && name.indexOf(':') >= 0) {
            // A colon here would be a legal HTTP header name but a confusing parameter name
            // in a form, and the header path validates separately anyway.
            throw new IllegalArgumentException("A header parameter cannot be named '" + name + "'.");
        }
    }

    /** A required path parameter, which the path template must also name. */
    public static ServerOperationParameter path(String name, String description, boolean required) {
        return new ServerOperationParameter(name, Location.PATH, description, required, false);
    }

    /** A query parameter. */
    public static ServerOperationParameter query(String name, String description, boolean required) {
        return new ServerOperationParameter(name, Location.QUERY, description, required, false);
    }

    /**
     * A header parameter whose value is not a credential, so it may be described in a log
     * line.
     */
    public static ServerOperationParameter header(String name, String description, boolean required) {
        return new ServerOperationParameter(name, Location.HEADER, description, required, false);
    }

    /** True when the caller cannot run the operation without a value for this parameter. */
    public boolean isRequired() {
        return required;
    }

    /** Never prints a value — this type has none — and never invents one. */
    @Override
    public String toString() {
        return name + " (" + location + (required ? ", required" : "") + ")";
    }
}
