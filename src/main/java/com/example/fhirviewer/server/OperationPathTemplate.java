package com.example.fhirviewer.server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills the {@code {name}} holes in an operation's path template.
 *
 * <p>An operation declares a path such as {@code admin/export/{jobId}}; this turns that
 * into {@code admin/export/abc-123} once the caller has supplied a value. The
 * substitution is the one place in the application where caller-supplied text becomes part
 * of a URL path, so it is also the one place that has to be careful about it.</p>
 *
 * <p><b>Every value is percent-encoded.</b> Not merely the ones that look dangerous: a
 * value is data, and a value of {@code ../admin} or {@code Patient/1} must arrive at the
 * server as one opaque segment rather than as extra structure. Encoding unconditionally
 * means a plugin author cannot forget to do it, and a caller cannot escape the path the
 * plugin declared.</p>
 *
 * <p><b>A missing required value is an error, not an empty segment.</b> Substituting
 * nothing would produce {@code admin/export/} — a different, valid-looking URL that the
 * server would answer about something else entirely.</p>
 */
public final class OperationPathTemplate {

    /** A hole in a template, written {@code {name}}. The name may not itself hold braces. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)\\}");

    private OperationPathTemplate() {
    }

    /**
     * The placeholder names a template contains, in the order they first appear.
     *
     * <p>De-duplicated, so a template naming {@code {id}} twice reports it once. Read by
     * {@link ServerOperation} to check that a template and its declared path parameters
     * agree.</p>
     */
    public static List<String> placeholdersIn(String template) {
        if (template == null || template.isBlank()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1).trim());
        }
        return List.copyOf(names);
    }

    /**
     * Substitutes the supplied values into the template.
     *
     * @param template the operation's path, for example {@code admin/export/{jobId}}
     * @param values   the caller's path parameter values, keyed by placeholder name
     * @return the path to request, relative to the base URL
     * @throws IllegalArgumentException when a required placeholder has no value, or a
     *         value is blank
     */
    public static String resolve(String template, Map<String, String> values) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("An operation path is required.");
        }
        Map<String, String> supplied = values == null ? Map.of() : values;
        List<String> missing = new ArrayList<>();
        StringBuilder resolved = new StringBuilder();
        Matcher matcher = PLACEHOLDER.matcher(template);
        int copied = 0;
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String value = supplied.get(name);
            if (value == null || value.isBlank()) {
                // Collected rather than thrown on, so one call reports every hole that was
                // left empty instead of making the caller resubmit to find the next one.
                missing.add(name);
                value = "";
            }
            resolved.append(template, copied, matcher.start());
            resolved.append(encode(value));
            copied = matcher.end();
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("The path '" + template + "' needs a value for "
                    + String.join(", ", missing) + ".");
        }
        resolved.append(template, copied, template.length());
        return resolved.toString();
    }

    /**
     * Percent-encodes one value so it can only ever be a single path segment.
     *
     * <p>Uses the stricter "unreserved characters only" set rather than
     * {@code application/x-www-form-urlencoded}, because that set would encode a space as
     * {@code +} — correct in a query string, but a literal plus sign in a path.</p>
     */
    public static String encode(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte octet : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = octet & 0xFF;
            if (isUnreserved(unsigned)) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((unsigned >> 4) & 0xF, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    /**
     * The characters RFC 3986 calls unreserved, and the only ones left as themselves.
     *
     * <p>{@code .} is deliberately <em>not</em> included. A value of {@code ..} would
     * otherwise survive encoding intact and walk one directory up the path, which is the
     * one traversal a percent-encoder alone does not stop.</p>
     */
    private static boolean isUnreserved(int character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '-' || character == '_' || character == '~';
    }
}
