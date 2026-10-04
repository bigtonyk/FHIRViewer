package com.example.fhirviewer.ui;

import java.util.List;
import java.util.Objects;

import com.example.fhirviewer.server.RequestHeaders;
import com.example.fhirviewer.server.rest.RestHeaders;

/**
 * Request headers a user typed, minus the ones that would be a credential in a text box.
 *
 * <p>A REST console without a headers table is not much of a console: {@code If-Match} for
 * a conditional update, {@code Prefer} for a return-representation, {@code _format}, and a
 * vendor's own header are all ordinary things to need and none of them is expressible
 * through the parameter grid. So this exists, and it is a thin wrapper over
 * {@link RestParameterList} because an ordered list of name/value pairs is exactly the
 * same problem twice.
 *
 * <p><b>{@code Authorization} is refused, and that is the point of this class.</b> The
 * console already has a first-class way to authenticate — the auth selector, which turns a
 * form into a {@code BearerServerAuthentication} or a
 * {@link com.example.fhirviewer.server.BasicServerAuthentication} and keeps the secret out
 * of any string this application can print. A free-text {@code Authorization} header undoes
 * all of it: the value sits in a table cell that is rendered in a screenshot, in a screen
 * share and in a support ticket. {@link #add(String, String)} therefore refuses rather than
 * hiding the control, and says why.
 *
 * <p>Everything else is allowed, including cookies — {@link RequestHeaders#isSecret(String)}
 * decides what counts, so this rule and the redaction rule cannot drift apart. The two
 * agree by construction, and a header refused here would have been redacted there anyway.
 */
public final class RestHeaderList {

    private final RestParameterList entries;

    private RestHeaderList(RestParameterList entries) {
        this.entries = entries;
    }

    /** No headers. */
    public static RestHeaderList empty() {
        return new RestHeaderList(RestParameterList.empty());
    }

    /**
     * Headers read from {@code Name: value} lines.
     *
     * <p>A line without a colon is skipped rather than guessed at, because sending
     * {@code Authorization} with no value is not a request anyone means to make.
     */
    public static RestHeaderList parse(String headerBlock) {
        if (headerBlock == null || headerBlock.isBlank()) {
            return empty();
        }
        RestParameterList parsed = RestParameterList.empty();
        for (String line : headerBlock.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                parsed = parsed.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return new RestHeaderList(parsed);
    }

    /**
     * These headers plus one more.
     *
     * @throws IllegalArgumentException when the name carries a credential; use the console's
     *         auth selector instead, which is not shown in this text
     */
    public RestHeaderList add(String name, String value) {
        refuseIfSecret(name);
        return new RestHeaderList(entries.add(name, value));
    }

    /** This list with the entry at {@code index} replaced. A blank name is kept: it is an edit. */
    public RestHeaderList replace(int index, RestParameterList.Parameter parameter) {
        Objects.requireNonNull(parameter, "parameter");
        refuseIfSecret(parameter.name());
        return new RestHeaderList(entries.replace(index, parameter));
    }

    /** This list with the entry at {@code index} removed. */
    public RestHeaderList remove(int index) {
        return new RestHeaderList(entries.remove(index));
    }

    /** The entries, in insertion order. */
    public List<RestParameterList.Parameter> entries() {
        return entries.entries();
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The first value for a name, or the empty string. */
    public String value(String name) {
        return entries.value(name);
    }

    /** The names, one per line, for showing above the grid. */
    public String toHeaderBlock() {
        StringBuilder text = new StringBuilder();
        for (RestParameterList.Parameter parameter : entries.entries()) {
            if (parameter.name().isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(parameter.name()).append(": ").append(parameter.value());
        }
        return text.toString();
    }

    /** The message a refused header produces, exposed so a form can show it as a tooltip. */
    public static String refusalMessage(String name) {
        return "The " + name + " header is set by the Authentication selector above. "
                + "Use that, so the value is not kept in a text field on screen.";
    }

    private static void refuseIfSecret(String name) {
        if (RestHeaders.isSecret(name)) {
            throw new IllegalArgumentException(refusalMessage(name));
        }
    }

    /** Never contains a value, for the same reason {@link RestParameterList} does not. */
    @Override
    public String toString() {
        return "RestHeaderList[" + entries.size() + " headers]";
    }
}