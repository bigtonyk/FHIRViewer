package com.spiraleyes.fhirviewer.server.smart;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extra parameters a launch adds to its authorization request, one {@code name=value} per
 * line.
 *
 * <p>Some authorization servers need parameters the SMART flow itself does not define, such
 * as an identity-provider hint ({@code kc_idp_hint} for Keycloak, {@code idp} elsewhere), a
 * {@code login_hint}, {@code acr_values}, or, for Smile CDR's federated OAuth2 provider
 * selection, a {@code launch} value an interceptor reads in a standalone launch. Rather than
 * a field per server, a launch carries a short list of them.</p>
 *
 * <p>The list cannot replace what makes the request safe. {@link #RESERVED} names the
 * parameters the flow sets itself: the client, redirect, scope, state, nonce and PKCE values,
 * the audience, which has its own field, and three that would change where or how the code
 * is delivered ({@code response_mode}) or replace the request wholesale ({@code request},
 * {@code request_uri}). {@code launch} is reserved in an EHR launch, which has its own field
 * for it, and {@code prompt} when Force re-login sets it.</p>
 *
 * <p>Values may be personal (a {@code login_hint} is often an email address), so only the
 * names are ever recorded in diagnostics.</p>
 */
public final class AuthorizationParameters {

    /** Parameters only the flow itself may set. Compared case-insensitively. */
    public static final Set<String> RESERVED = Set.of(
            "response_type", "client_id", "redirect_uri", "scope", "state", "nonce",
            "code_challenge", "code_challenge_method", "aud",
            "response_mode", "request", "request_uri");

    private AuthorizationParameters() {
    }

    /**
     * Reads the parameters in order, skipping blank lines and {@code #} comments.
     *
     * <p>Lenient: a line that is not {@code name=value} is skipped here and reported by
     * {@link #problems}, which every launch is checked against before it is sent.</p>
     *
     * @param text the parameters, one per line; may be {@code null}
     * @return the name and value of each usable line, in order
     */
    public static List<Map.Entry<String, String>> parse(String text) {
        List<Map.Entry<String, String>> parameters = new ArrayList<>();
        for (String line : lines(text)) {
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = line.substring(0, equals).strip();
            String value = line.substring(equals + 1).strip();
            if (!name.isEmpty() && !value.isEmpty()) {
                parameters.add(Map.entry(name, value));
            }
        }
        return List.copyOf(parameters);
    }

    /**
     * What is wrong with the parameters, for a launch in {@code mode}.
     *
     * @param text the parameters, one per line; may be {@code null}
     * @param mode the launch mode, which decides whether {@code launch} is free to set
     * @param forceLogin whether Force re-login already sends {@code prompt}
     * @return one line per problem; empty when every line can be sent
     */
    public static List<String> problems(String text, SmartLaunchMode mode, boolean forceLogin) {
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : lines(text)) {
            int equals = line.indexOf('=');
            String name = equals < 0 ? line.strip() : line.substring(0, equals).strip();
            String value = equals < 0 ? "" : line.substring(equals + 1).strip();
            if (equals <= 0 || name.isEmpty()) {
                found.add("Authorization parameter \"" + shorten(line)
                        + "\" is not written as name=value.");
                continue;
            }
            if (!isName(name)) {
                found.add("Authorization parameter name \"" + shorten(name)
                        + "\" may only use letters, digits and - . _ ~");
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (RESERVED.contains(key)) {
                found.add("Authorization parameter \"" + name + "\" is set by the launch "
                        + "itself and cannot be overridden.");
            } else if (key.equals("launch") && mode != null && mode.needsLaunchParameter()) {
                found.add("An EHR launch sends launch from the Launch parameter field; remove "
                        + "it from the authorization parameters.");
            } else if (key.equals("prompt") && forceLogin) {
                found.add("Force re-login already sends prompt=login; remove prompt from the "
                        + "authorization parameters or clear Force re-login.");
            }
            if (value.isEmpty()) {
                found.add("Authorization parameter \"" + name + "\" has no value.");
            }
            if (!seen.add(key)) {
                found.add("Authorization parameter \"" + name + "\" is given more than once.");
            }
        }
        return found;
    }

    /**
     * The text in the form it is stored and compared in: trimmed lines, blank lines dropped.
     *
     * @return the normalized text, or {@code null} when nothing is left
     */
    public static String normalize(String text) {
        List<String> kept = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    kept.add(line.strip());
                }
            }
        }
        return kept.isEmpty() ? null : String.join("\n", kept);
    }

    /** The parameter names alone, for diagnostics: values may identify the user. */
    public static String namesOf(String text) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> parameter : parse(text)) {
            names.add(parameter.getKey());
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    /** The lines that carry a parameter: not blank and not a {@code #} comment. */
    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                lines.add(stripped);
            }
        }
        return lines;
    }

    private static boolean isName(String name) {
        return name.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~');
    }

    private static String shorten(String text) {
        return text.length() > 40 ? text.substring(0, 40) + "..." : text;
    }
}
