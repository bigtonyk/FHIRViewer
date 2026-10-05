package com.example.fhirviewer.ui;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import com.example.fhirviewer.server.ServerAuthKind;
import com.example.fhirviewer.server.rest.RestMethod;
import com.example.fhirviewer.server.rest.RestRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What the console remembers between openings, and where it keeps it.
 *
 * <p>The console closes itself whenever the user opens a resource in the viewer, so without
 * this every use would start from an empty form — and the common case is a repeated one:
 * search, open a result, look at it, go back and change the search. Re-typing the URL, the
 * parameters and the authentication each time is the kind of friction that makes people stop
 * using a tool.
 *
 * <p>Storage is {@link Preferences} under the user root, which is the same node
 * {@code PluginManagerDialog} uses for its remembered folder. Not a file beside the
 * application, and not {@code PluginSettingsStore}: those are for things a user configured
 * deliberately and expects to survive a reinstall.
 *
 * <p><b>What is deliberately not remembered.</b>
 * <ul>
 *   <li><b>The password and the token.</b> The console's whole credential posture is that
 *       what you type in it is used and forgotten (decision DD3 of
 *       {@code docs/plans/11_REST_CONSOLE_UI.md}). Persisting a token here would put a live
 *       bearer credential in a preference store that the server manager's encrypted store
 *       exists to avoid.</li>
 *   <li><b>The request body.</b> A body is far more likely to be a patient resource than a
 *       credential, and writing one to disk unasked is a decision this tool should not make
 *       silently. It is a one-line change if that trade is judged wrong.</li>
 * </ul>
 * The user name is remembered: it is half of a credential but not a secret, and retyping it
 * is pure friction.
 *
 * <p><b>Nothing here throws.</b> A locked or read-only preferences directory makes the very
 * first call fail, and a console that cannot remember its URL is still a working console.
 * Every read and write is wrapped and logged at {@code FINE}, exactly as
 * {@code PluginManagerDialog} does it.
 *
 * <p>JavaFX-free on purpose, so the read and write can be tested without a toolkit.
 */
public record RestConsoleState(
        String serverBaseUrl,
        RestMethod method,
        String baseUrl,
        String path,
        List<RestParameterList.Parameter> parameters,
        List<RestParameterList.Parameter> headers,
        String contentType,
        ServerAuthKind authKind,
        String userName,
        double windowWidth,
        double windowHeight,
        double dividerPosition) {

    private static final Logger log = Logger.getLogger(RestConsoleState.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The preferences node this console owns. */
    private static final String NODE = "com/example/fhirviewer/restConsole";

    /**
     * A preference value is limited to about 8 KB. Anything over this is dropped rather than
     * truncated, because a half-written parameter list is worse than none.
     */
    private static final int MAX_VALUE_LENGTH = 6000;

    public RestConsoleState {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        headers = headers == null ? List.of() : List.copyOf(headers);
    }

    /** An empty state: a fresh console starts here. */
    public static RestConsoleState defaults() {
        return new RestConsoleState("", RestMethod.GET, "", "", List.of(), List.of(),
                RestRequest.DEFAULT_CONTENT_TYPE, ServerAuthKind.ANONYMOUS, "",
                1400, 820, 0.52);
    }

/**
     * The remembered state, or {@link #defaults()} when there is none or it cannot be read.
     *
     * @param knownServerUrls the base URLs of the configured servers, so a remembered server
     *                        can be matched back and re-selected rather than shown as a bare
     *                        URL. A server that has since been deleted simply does not
     *                        match, and its URL is still remembered as a custom one.
     */
    public static RestConsoleState load(List<String> knownServerUrls) {
        try {
            Preferences node = nodeForTest();
            return new RestConsoleState(
                    node.get("serverBaseUrl", ""),
                    readMethod(node.get("method", "")),
                    node.get("baseUrl", ""),
                    node.get("path", ""),
                    readPairs(node.get("parameters", "")),
                    readPairs(node.get("headers", "")),
                    node.get("contentType", RestRequest.DEFAULT_CONTENT_TYPE),
                    readAuthKind(node.get("authKind", "")),
                    node.get("userName", ""),
                    node.getDouble("windowWidth", 1400),
                    node.getDouble("windowHeight", 820),
                    node.getDouble("divider", 0.52));
        } catch (RuntimeException | BackingStoreException e) {
            log.log(Level.FINE, "could not read the remembered console settings", e);
            return defaults();
        }
    }

    /**
     * The preferences node this console uses.
     *
     * <p>Package-visible rather than private so {@code RestConsoleStateTest} reads and writes
     * the same node the application does. Delegating instead of repeating the path is what
     * stops the test passing while the real key is broken.
     *
     * @throws BackingStoreException when the preferences backend is unavailable
     */
    static java.util.prefs.Preferences nodeForTest() throws BackingStoreException {
        return Preferences.userRoot().node(NODE);
    }

    /** Writes this state, ignoring any failure to do so. */
    public void save() {
        try {
            Preferences node = nodeForTest();
            node.put("serverBaseUrl", orEmpty(serverBaseUrl));
            node.put("method", method == null ? "" : method.name());
            node.put("baseUrl", orEmpty(baseUrl));
            node.put("path", orEmpty(path));
            writePairs(node, "parameters", parameters);
            writePairs(node, "headers", headers);
            node.put("contentType", orEmpty(contentType));
            node.put("authKind", authKind == null ? "" : authKind.name());
            node.put("userName", orEmpty(userName));
            node.putDouble("windowWidth", windowWidth);
            node.putDouble("windowHeight", windowHeight);
            node.putDouble("divider", dividerPosition);
            node.flush();
        } catch (RuntimeException | BackingStoreException e) {
            log.log(Level.FINE, "could not remember the console settings", e);
        }
    }

    /** True when this state names a configured server rather than a custom URL. */
    public boolean matchesAServer(List<String> knownServerUrls) {
        return serverBaseUrl != null && !serverBaseUrl.isBlank()
                && knownServerUrls != null && knownServerUrls.contains(serverBaseUrl);
    }

    private static void writePairs(Preferences node, String key,
            List<RestParameterList.Parameter> pairs) {
        try {
            String json = JSON.writeValueAsString(pairs);
            if (json.length() <= MAX_VALUE_LENGTH) {
                node.put(key, json);
            }
            // Over the limit it is left alone rather than truncated: the previous value stays,
            // and losing the whole list is better than restoring half of one.
        } catch (Exception notSerializable) {
            log.log(Level.FINE, "could not remember " + key, notSerializable);
        }
    }

    /** Reads a stored list back, tolerating anything that is not one. */
    private static List<RestParameterList.Parameter> readPairs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = JSON.readTree(json);
            List<RestParameterList.Parameter> pairs = new java.util.ArrayList<>();
            for (JsonNode node : root) {
                pairs.add(new RestParameterList.Parameter(
                        node.path("name").asText(""), node.path("value").asText("")));
            }
            return pairs;
        } catch (Exception unreadable) {
            log.log(Level.FINE, "could not read remembered name/value pairs", unreadable);
            return List.of();
        }
    }

    /**
     * A verb from stored text.
     *
     * <p>Falls back to {@code GET} rather than throwing: a stored value can be from an older
     * build, or hand-edited, and an unreadable verb should not stop the console opening.
     */
    private static RestMethod readMethod(String name) {
        for (RestMethod candidate : RestMethod.values()) {
            if (candidate.name().equalsIgnoreCase(name == null ? "" : name.trim())) {
                return candidate;
            }
        }
        return RestMethod.GET;
    }

    /** An auth kind from stored text, via the enum's own tolerant parser. */
    private static ServerAuthKind readAuthKind(String name) {
        return ServerAuthKind.fromName(name);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Never carries a credential, because the record cannot hold one. */
    @Override
    public String toString() {
        return "RestConsoleState[" + method + " " + baseUrl + path + ", "
                + parameters.size() + " parameters, " + headers.size() + " headers, "
                + authKind + "]";
    }
}