package com.spiraleyes.fhirviewer.ui;

import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spiraleyes.fhirviewer.server.ServerAuthKind;
import com.spiraleyes.fhirviewer.server.rest.RestMethod;
import com.spiraleyes.fhirviewer.server.rest.RestRequest;
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
 *       silently. It has no component in this record at all, so a later edit to the capture
 *       code cannot start persisting one by accident.</li>
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

    private static final Logger log = LoggerFactory.getLogger(RestConsoleState.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The preferences node this console owns.
     *
     * <p>Scoped to the application's own package. Earlier builds wrote to
     * {@link #LEGACY_NODE}, a placeholder package name that never matched this project; that
     * node is read and then removed rather than kept as a second source of truth.
     */
    private static final String NODE = "com/spiraleyes/fhirviewer/restConsole";

    /** Where earlier builds kept the same values. Read, migrated, then dropped. */
    private static final String LEGACY_NODE = "com/example/fhirviewer/restConsole";

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
            Preferences legacy = legacyNode();
            return new RestConsoleState(
                    remembered(node, legacy, "serverBaseUrl", ""),
                    readMethod(remembered(node, legacy, "method", "")),
                    remembered(node, legacy, "baseUrl", ""),
                    remembered(node, legacy, "path", ""),
                    readPairs(remembered(node, legacy, "parameters", "")),
                    readPairs(remembered(node, legacy, "headers", "")),
                    remembered(node, legacy, "contentType", RestRequest.DEFAULT_CONTENT_TYPE),
                    readAuthKind(remembered(node, legacy, "authKind", "")),
                    remembered(node, legacy, "userName", ""),
                    rememberedDouble(node, legacy, "windowWidth", 1400),
                    rememberedDouble(node, legacy, "windowHeight", 820),
                    rememberedDouble(node, legacy, "divider", 0.52));
        } catch (RuntimeException | BackingStoreException e) {
            log.debug("could not read the remembered console settings", e);
            return defaults();
        }
    }

    /**
     * The node earlier builds used, or {@code null} when it cannot be opened.
     *
     * <p>Being empty is the normal case once anything has been saved: the values have moved,
     * so there is nothing left to read from it.
     */
    private static Preferences legacyNode() {
        try {
            return Preferences.userRoot().node(LEGACY_NODE);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** A remembered string, falling back to the node earlier builds used. */
    private static String remembered(Preferences node, Preferences legacy, String key,
            String fallback) {
        String value = node.get(key, null);
        if (value == null && legacy != null) {
            value = legacy.get(key, null);
        }
        return value == null ? fallback : value;
    }

    /** A remembered number, from either node, tolerating text that is not one. */
    private static double rememberedDouble(Preferences node, Preferences legacy, String key,
            double fallback) {
        String value = remembered(node, legacy, key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException unreadable) {
            log.debug("could not read remembered " + key, unreadable);
            return fallback;
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
            // Earlier builds wrote the request body here. Drop one if it is still around
            // rather than leaving patient data in the store after the field is gone.
            node.remove("body");
            node.flush();
            // Earlier builds wrote these values to a placeholder package node. They have been
            // migrated by now, so it goes rather than surviving as a second source of truth.
            try {
                Preferences.userRoot().node(LEGACY_NODE).removeNode();
            } catch (RuntimeException alreadyGone) {
                log.debug("could not remove the earlier console node", alreadyGone);
            }
        } catch (RuntimeException | BackingStoreException e) {
            log.debug("could not remember the console settings", e);
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
            log.debug("could not remember " + key, notSerializable);
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
            log.debug("could not read remembered name/value pairs", unreadable);
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

    /** Describes the remembered request without its body, which is not remembered. */
    @Override
    public String toString() {
        return "RestConsoleState[" + method + " " + baseUrl + path + ", "
                + parameters.size() + " parameters, " + headers.size() + " headers, "
                + authKind + "]";
    }
}


