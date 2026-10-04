package com.example.fhirviewer.server.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a cURL command into a request, and writes one back out.
 *
 * <p>Paste-a-cURL is the single most useful thing a REST console can do, and for FHIR it is
 * not a convenience: a bug report, a Confluence page, a vendor's own documentation or a
 * support bundle almost always contains a working {@code curl} line with the right headers
 * already on it. Being able to paste that and press Send turns a debugging session into a
 * single step. Copying a request back out as cURL matters for the same reason — the answer
 * to "how do I reproduce this?" is a command, and it belongs in a ticket.
 *
 * <p><b>The two directions have to agree.</b> A command this class writes must parse back to
 * the request it came from, or "copy as cURL", edit, "paste" quietly changes the request.
 * That is why the shell quoting lives here in one place and serves both directions, and why
 * {@code CurlCommandTest} asserts a round trip rather than checking each side against a
 * hand-written expectation.
 *
 * <p><b>This parses the flags a person produces, not the whole cURL language.</b> It
 * understands the ones that appear in real FHIR requests — {@code -X}, {@code -H},
 * {@code -d} and its relatives, {@code -u}, {@code --url}, {@code -G}, {@code -A},
 * {@code -e} — and ignores the ones that only affect cURL's own output ({@code -s},
 * {@code -v}, {@code -i}, {@code -L}, {@code --compressed}) rather than refusing the line.
 *
 * <p><b>{@code -k} is refused, not ignored.</b> It disables certificate checking, and
 * {@code JdkHttpRestClient} deliberately installs no {@code SSLContext} so platform
 * validation stays on. Accepting the flag and quietly dropping it would produce a request
 * that fails differently from the one the user is looking at; failing loudly is honest.
 *
 * <p>A header value is a credential often enough that {@link CurlRequest#toString()} never
 * prints one, and {@link #render} leaves it to the caller to decide whether an
 * {@code Authorization} line is included at all.
 */
public final class CurlCommand {

    private CurlCommand() {
    }

    /**
     * One header from the command.
     *
     * <p>A type of its own rather than a reuse of the console's grid row: this class lives
     * in {@code server.rest}, which must not depend on the {@code ui} package, and a parsed
     * command is a different thing from an in-progress edit to one.
     *
     * @param name  the header name, already trimmed
     * @param value the header value; may be empty, which is legal and meaningful
     */
    public record CurlHeader(String name, String value) {
    }

    /**
     * A cURL command read into the pieces the console understands.
     *
     * @param method  the verb
     * @param url     the address, query string included
     * @param headers header name/value pairs, in the order they appeared
     * @param body    the request body, or {@code null} when there was none
     */
    public record CurlRequest(RestMethod method, String url,
            List<CurlHeader> headers, String body) {

        public CurlRequest {
            headers = headers == null ? List.of() : List.copyOf(headers);
        }

        /** Never prints a header value: one of them is usually a token. */
        @Override
        public String toString() {
            return "CurlRequest[" + method + " " + url + ", " + headers.size()
                    + " headers, body " + (body == null ? "none" : body.length() + " characters")
                    + "]";
        }
    }

/**
     * Reads a cURL command.
     *
     * @param command the command line, with or without a leading {@code $} or {@code >}
     * @return what it describes, or empty when there is no URL to call
     * @throws IllegalArgumentException when the command asks for a behaviour this project
     *         will not perform, such as {@code --insecure}, so the refusal names the flag
     */
    public static Optional<CurlRequest> parse(String command) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        List<String> tokens = tokenize(command);
        String method = null;
        String url = null;
        String body = null;
        String basicCredentials = null;
        List<CurlHeader> headers = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            // The program name is the first token and is not part of the request. Without
            // this it becomes the URL, and every command pastes in as "curl".
            if (url == null && body == null && method == null && i == 0
                    && token.equalsIgnoreCase("curl")) {
                continue;
            }
            if (!token.startsWith("-")) {
                if (url == null) {
                    url = token;
                }
                continue;
            }
            String flag = token;
            String inline = null;
            int equals = token.indexOf('=');
            if (token.startsWith("--") && equals > 2) {
                // --header=Name: value is one token, unlike --header Name: value.
                flag = token.substring(0, equals);
                inline = token.substring(equals + 1);
            }
            switch (flag) {
                case "-X", "--request" -> method = valueOf(tokens, inline, ++i);
                case "-H", "--header" -> addHeader(headers, valueOf(tokens, inline, ++i));
                case "-d", "--data", "--data-raw", "--data-binary", "--data-ascii",
                        "--data-urlencode" -> body = valueOf(tokens, inline, ++i);
                case "-u", "--user" -> basicCredentials = valueOf(tokens, inline, ++i);
                case "--url" -> url = valueOf(tokens, inline, ++i);
                case "-A", "--user-agent" -> addNamed(headers, "User-Agent", valueOf(tokens, inline, ++i));
                case "-e", "--referer" -> addNamed(headers, "Referer", valueOf(tokens, inline, ++i));
                case "-G", "--get" -> {
                    if (method == null) {
                        method = "GET";
                    }
                }
                case "-k", "--insecure" -> throw new IllegalArgumentException(
                        "--insecure is not supported. Certificate checking stays on, because this "
                                + "viewer will not send a credential to a server it cannot verify. "
                                + "Fix the certificate or the trust store instead.");
                default -> {
                    // A flag that only changes how cURL behaves, or one this build has never
                    // seen. Ignoring it keeps a real command pasteable.
                }
            }
        }
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        if (basicCredentials != null && !basicCredentials.isBlank()) {
            // Kept as a header so the console can show what arrived; it is refused as an
            // editable header and the user is pointed at the Authentication selector.
            headers.add(new CurlHeader("Authorization", basicCredentials));
        }
        return Optional.of(new CurlRequest(verbOf(method, body), url, headers, body));
    }

/**
 * Writes a request out as a cURL command that {@link #parse} reads back identically.
 *
 * @param request      what to write
 * @param includeAuthorization whether an {@code Authorization} header is written out. It is
 *                             false by default in the console, because the rendered text is
 *                             what a user pastes into a ticket; a caller that is writing to
 *                             their own clipboard for their own machine may pass true.
 */
    public static String render(CurlRequest request, boolean includeAuthorization) {
        StringBuilder command = new StringBuilder("curl");
        command.append(" -X ").append(request.method().name());
        for (CurlHeader header : request.headers()) {
            if (!includeAuthorization && RestHeaders.isSecret(header.name())) {
                continue;
            }
            if (header.name().isBlank()) {
                continue;
            }
            command.append(" -H ").append(quote(header.name() + ": " + header.value()));
        }
        if (request.body() != null && !request.body().isEmpty()) {
            command.append(" -d ").append(quote(request.body()));
        }
        command.append(' ').append(quote(request.url()));
        return command.toString();
    }

    /** Writes a request out, leaving every credential out. */
    public static String render(CurlRequest request) {
        return render(request, false);
    }

    /**
     * Splits a command line the way a POSIX shell would, for the subset that matters.
     *
     * <p>Single quotes protect everything, double quotes protect everything except a
     * backslash escape, and a backslash escapes the next character outside quotes. That is
     * the whole of the rule that matters here, and it is why a body containing a space or a
     * double quote survives the round trip intact.
     */
    static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean started = false;
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < command.length()) {
                    current.append(command.charAt(++i));
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                started = true;
                continue;
            }
            if (c == '\\' && i + 1 < command.length()) {
                current.append(command.charAt(++i));
                started = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (started) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
                continue;
            }
            current.append(c);
            started = true;
        }
        if (started) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** Single-quotes a value so a shell gives it back exactly as written. */
    static String quote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        // Inside single quotes a literal quote is spelled '\'' — close, escape, reopen.
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** The value for a flag, whether it was {@code --flag value} or {@code --flag=value}. */
    private static String valueOf(List<String> tokens, String inline, int index) {
        if (inline != null) {
            return inline;
        }
        return index < tokens.size() ? tokens.get(index) : null;
    }

    private static void addHeader(List<CurlHeader> headers, String header) {
        int colon = header == null ? -1 : header.indexOf(':');
        // A line with no colon is not a header. Skipping it beats sending "Accept" with
        // no value, which is a request nobody meant to make.
        if (colon > 0) {
            headers.add(new CurlHeader(header.substring(0, colon).trim(), header.substring(colon + 1).trim()));
        }
    }

    private static void addNamed(List<CurlHeader> headers, String name, String value) {
        if (value != null) {
            headers.add(new CurlHeader(name, value));
        }
    }

    /** The verb to use: what was asked for, else {@code POST} with a body, else {@code GET}. */
    private static RestMethod verbOf(String method, String body) {
        if (method != null && !method.isBlank()) {
            try {
                return RestMethod.valueOf(method.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException notAVerb) {
                // HEAD and OPTIONS are real HTTP verbs this project does not model. GET is
                // the honest stand-in: it asks for the same representation without a body.
                return RestMethod.GET;
            }
        }
        return body == null || body.isEmpty() ? RestMethod.GET : RestMethod.POST;
    }
}