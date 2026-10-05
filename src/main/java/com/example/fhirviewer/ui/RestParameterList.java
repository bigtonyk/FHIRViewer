package com.example.fhirviewer.ui;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Query parameters a user typed, in the order they typed them.
 *
 * <p>A list rather than a map, and that is the whole design. {@code _include} may legally
 * repeat, and a repeatable parameter is order-sensitive on some servers;
 * {@link com.example.fhirviewer.server.rest.RestRequest} goes out of its way to preserve
 * insertion order, and handing it a {@code HashMap} would quietly undo that work. The same
 * applies to a query copied out of a browser address bar and pasted in expecting it to
 * arrive as written.
 *
 * <p>Immutable: every {@code add}, {@code remove} and {@code replace} returns a new list.
 * The console's grid edits a copy at a time and swaps it in, so a half-typed row cannot be
 * lost by an edit a later keystroke invalidates.
 *
 * <p><b>JavaFX-free on purpose.</b> This is the half of the parameter editor that has to be
 * right — what a flag means, how a repeated name survives, how a value is encoded — and it
 * is testable without a display.
 */
public final class RestParameterList {

    /**
     * One name and one value.
     *
     * <p>An empty value is meaningful, not missing: {@code _pretty} and {@code _include=}
     * both send an empty value, and a bare {@code _pretty} is how a FHIR server is asked
     * for indented output.
     */
    public record Parameter(String name, String value) {

        public Parameter {
            name = name == null ? "" : name.trim();
            value = value == null ? "" : value;
        }
    }

    private static final RestParameterList EMPTY = new RestParameterList(List.of());

    private final List<Parameter> parameters;

    private RestParameterList(List<Parameter> parameters) {
        this.parameters = List.copyOf(parameters);
    }

    /** No parameters. */
    public static RestParameterList empty() {
        return EMPTY;
    }

    /** A list holding one parameter. A blank name is dropped rather than sent as {@code =value}. */
    public static RestParameterList of(String name, String value) {
        return empty().add(name, value);
    }

    /**
     * A list read from a query string.
     *
     * <p>A leading {@code ?} is optional and an empty pair is skipped, so pasting
     * {@code "name=Smith&_count=5"} and {@code "?name=Smith&_count=5"} both work. Values are
     * percent-decoded, so a copied URL does not arrive with {@code %20} in a name.
     */
    public static RestParameterList parse(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return EMPTY;
        }
        String text = queryString.strip();
        int start = text.indexOf('?');
        if (start >= 0) {
            text = text.substring(start + 1);
        }
        List<Parameter> parsed = new ArrayList<>();
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            if (equals < 0) {
                // A bare name is a flag: "_pretty" means "_pretty=".
                parsed.add(new Parameter(decode(pair), ""));
            } else {
                parsed.add(new Parameter(decode(pair.substring(0, equals)),
                        decode(pair.substring(equals + 1))));
            }
        }
        return new RestParameterList(parsed);
    }

/**
     * This list plus one more parameter.
     *
     * <p>A blank name is dropped. {@link #replace(int, Parameter)} deliberately keeps a
     * blank one, because that is an in-progress edit in a grid rather than a value to send.
     */
    public RestParameterList add(String name, String value) {
        if (name == null || name.isBlank()) {
            return this;
        }
        List<Parameter> next = new ArrayList<>(parameters);
        next.add(new Parameter(name, value));
        return new RestParameterList(next);
    }

    /** This list with the entry at {@code index} replaced. */
    public RestParameterList replace(int index, Parameter parameter) {
        if (index < 0 || index >= parameters.size()) {
            throw new IndexOutOfBoundsException("No parameter at " + index);
        }
        Objects.requireNonNull(parameter, "parameter");
        List<Parameter> next = new ArrayList<>(parameters);
        next.set(index, parameter);
        return new RestParameterList(next);
    }

    /** This list with the entry at {@code index} removed. */
    public RestParameterList remove(int index) {
        if (index < 0 || index >= parameters.size()) {
            throw new IndexOutOfBoundsException("No parameter at " + index);
        }
        List<Parameter> next = new ArrayList<>(parameters);
        next.remove(index);
        return new RestParameterList(next);
    }

    /** The entries, in insertion order. */
    public List<Parameter> entries() {
        return parameters;
    }

    public int size() {
        return parameters.size();
    }

    public boolean isEmpty() {
        return parameters.isEmpty();
    }

    /** The first value for a name, or the empty string. */
    public String value(String name) {
        for (Parameter parameter : parameters) {
            if (parameter.name().equalsIgnoreCase(name)) {
                return parameter.value();
            }
        }
        return "";
    }

    /**
     * The shape {@link com.example.fhirviewer.server.rest.RestRequest} expects.
     *
     * <p>A {@code LinkedHashMap} of lists, so both the order and a repeated name survive.
     */
    public Map<String, List<String>> toQueryMap() {
        Map<String, List<String>> query = new LinkedHashMap<>();
        for (Parameter parameter : parameters) {
            if (parameter.name().isBlank()) {
                continue;
            }
            query.computeIfAbsent(parameter.name(), key -> new ArrayList<>())
                    .add(parameter.value());
        }
        query.replaceAll((name, values) -> List.copyOf(values));
        return query;
    }

    /** The query string these parameters describe, without a leading {@code ?}. */
    public String toQueryString() {
        StringBuilder text = new StringBuilder();
        for (Parameter parameter : parameters) {
            if (parameter.name().isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('&');
            }
            text.append(encode(parameter.name())).append('=').append(encode(parameter.value()));
        }
        return text.toString();
    }

    /** Never contains a value: a query parameter can carry a patient name. */
    @Override
    public String toString() {
        return "RestParameterList[" + parameters.size() + " parameters]";
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notEncoded) {
            // A stray % that is not an escape is data, not a reason to refuse the request.
            return value;
        }
    }

    /** Percent-encodes everything outside the unreserved set, so a value cannot add structure. */
    private static String encode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format("%02X", c));
            }
        }
        return encoded.toString();
    }
}