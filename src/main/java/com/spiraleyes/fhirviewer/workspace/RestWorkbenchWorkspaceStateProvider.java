package com.spiraleyes.fhirviewer.workspace;

import com.spiraleyes.fhirviewer.server.ServerAuthKind;
import com.spiraleyes.fhirviewer.server.rest.RestMethod;
import com.spiraleyes.fhirviewer.ui.RestConsoleState;
import com.spiraleyes.fhirviewer.ui.RestParameterList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Capture/restore boundary for REST workbench state.
 *
 * <p>Serialises saved requests and supported drafts (method, path, parameters, safe headers,
 * body reference). Excludes authorisation/secrets. Restore populates workbench state only;
 * it never auto-executes a request.</p>
 */
public class RestWorkbenchWorkspaceStateProvider implements WorkspaceStateProvider {

    private static final String NODE = "com/example/fhirviewer/restConsole";

    @Override
    public String providerId() {
        return "rest-workbench";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object capture() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Preferences node = Preferences.userRoot().node(NODE);
            RestConsoleState state = RestConsoleState.load(null);
            out.put("serverBaseUrl", state.serverBaseUrl());
            out.put("method", state.method() == null ? null : state.method().name());
            out.put("baseUrl", state.baseUrl());
            out.put("path", state.path());
            out.put("parameters", toList(state.parameters()));
            out.put("headers", toList(state.headers()));
            out.put("contentType", state.contentType());
            out.put("authKind", state.authKind() == null ? null : state.authKind().name());
            out.put("userName", state.userName());
            // Deliberately NOT captured: password, token (the remembered console
            // password field is also a credential).
            out.put("body", state.body());        } catch (Exception ignored) {
            // Preferences unavailable — workspace still opens
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restore(Object snapshot) {
        if (!(snapshot instanceof Map)) return;
        Map<String, Object> entries = (Map<String, Object>) snapshot;
        try {
            Preferences node = Preferences.userRoot().node(NODE);
            RestMethod method = RestMethod.GET;
            Object m = entries.get("method");
            if (m instanceof String s) {
                for (RestMethod candidate : RestMethod.values()) {
                    if (candidate.name().equalsIgnoreCase(s)) {
                        method = candidate;
                        break;
                    }
                }
            }
            ServerAuthKind authKind = ServerAuthKind.ANONYMOUS;
            Object a = entries.get("authKind");
            if (a instanceof String s) {
                authKind = ServerAuthKind.fromName(s);
            }
            List<RestParameterList.Parameter> params = asParameters(entries.get("parameters"));
            List<RestParameterList.Parameter> headers = asParameters(entries.get("headers"));
            RestConsoleState state = new RestConsoleState(
                    asString(entries.get("serverBaseUrl")),
                    method,
                    asString(entries.get("baseUrl")),
                    asString(entries.get("path")),
                    params,
                    headers,
                    asString(entries.get("contentType")),
                    authKind,
                    asString(entries.get("userName")),
                    0, 0, 0,
                    asString(entries.get("body"))
            );
            state.save();
        } catch (Exception ignored) {
            // Restore failure must not block workspace open
        }
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> toList(List<RestParameterList.Parameter> entries) {
        List<Map<String, String>> result = new java.util.ArrayList<>();
        for (RestParameterList.Parameter p : entries) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", p.name());
            entry.put("value", p.value());
            result.add(entry);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<RestParameterList.Parameter> asParameters(Object o) {
        if (o instanceof List<?> list) {
            List<RestParameterList.Parameter> result = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object nameObj = map.get("name");
                    Object valueObj = map.get("value");
                    String name = nameObj == null ? "" : String.valueOf(nameObj);
                    String value = valueObj == null ? "" : String.valueOf(valueObj);
                    result.add(new RestParameterList.Parameter(name, value));
                }
            }
            return result;
        }
        return java.util.List.of();
    }
}

