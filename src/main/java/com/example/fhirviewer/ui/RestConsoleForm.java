package com.example.fhirviewer.ui;

import java.util.Optional;

import com.example.fhirviewer.server.AdhocAuthentication;
import com.example.fhirviewer.server.AdhocServer;
import com.example.fhirviewer.server.ServerAuthKind;
import com.example.fhirviewer.server.ServerAuthentication;
import com.example.fhirviewer.server.ServerSession;
import com.example.fhirviewer.server.rest.RestMethod;
import com.example.fhirviewer.server.rest.RestRequest;
import com.example.fhirviewer.server.rest.RestUrls;

/**
 * What the console's form currently says, as data.
 *
 * <p>This is the half of the REST console that has to be right and the half worth testing:
 * it turns the fields a user filled in into a {@link RestRequest} on a
 * {@link ServerSession}, or into a sentence explaining what is still missing. The JavaFX
 * screen next door only draws the fields and calls {@link #toRequest()}.
 *
 * <p><b>It mirrors {@link ServerOperationForm} on purpose.</b> That class is the same idea
 * for a plugin-declared operation, and the reason it can be trusted is that the shape of
 * the decision — validate here, then build — lives in one tested place rather than spread
 * across a scene graph.
 *
 * <p><b>Validation happens before the socket opens.</b> {@link #problem()} names the first
 * field that is wrong, which is the only version of the message that is any use next to a
 * form; the transport's own complaint about a malformed URL arrives too late to point at
 * the text field that caused it.
 *
 * <p>Mutable, and owned by one dialog. Nothing here blocks, and nothing here knows about
 * JavaFX, so a test can fill in a form and assert the request it would have sent.
 */
public final class RestConsoleForm {

    private String baseUrl = "";
    private RestMethod method = RestMethod.GET;
    private String path = "";
    private RestParameterList parameters = RestParameterList.empty();
    private RestHeaderList headers = RestHeaderList.empty();
    private String body = "";
    private String contentType = RestRequest.DEFAULT_CONTENT_TYPE;
    private String accept = RestRequest.DEFAULT_ACCEPT;
    private ServerAuthKind authKind = ServerAuthKind.ANONYMOUS;
    private String userName = "";
    private String password = "";
    private String token = "";

    public String baseUrl() {
        return baseUrl;
    }

    public RestConsoleForm baseUrl(String value) {
        this.baseUrl = value == null ? "" : value.trim();
        return this;
    }

    public RestMethod method() {
        return method;
    }

    public RestConsoleForm method(RestMethod value) {
        this.method = value == null ? RestMethod.GET : value;
        return this;
    }

    public String path() {
        return path;
    }

    public RestConsoleForm path(String value) {
        this.path = value == null ? "" : value.trim();
        return this;
    }

    public RestParameterList parameters() {
        return parameters;
    }

    public RestConsoleForm parameters(RestParameterList value) {
        this.parameters = value == null ? RestParameterList.empty() : value;
        return this;
    }

    public RestHeaderList headers() {
        return headers;
    }

    public RestConsoleForm headers(RestHeaderList value) {
        this.headers = value == null ? RestHeaderList.empty() : value;
        return this;
    }

    public String body() {
        return body;
    }

    public RestConsoleForm body(String value) {
        this.body = value == null ? "" : value;
        return this;
    }

    public String contentType() {
        return contentType;
    }

    public RestConsoleForm contentType(String value) {
        this.contentType = value == null ? "" : value.trim();
        return this;
    }

    public String accept() {
        return accept;
    }

    public RestConsoleForm accept(String value) {
        this.accept = value == null ? "" : value.trim();
        return this;
    }

    public ServerAuthKind authKind() {
        return authKind;
    }

    public RestConsoleForm authentication(ServerAuthKind kind, String user, String secret,
            String bearerToken) {
        this.authKind = kind == null ? ServerAuthKind.ANONYMOUS : kind;
        this.userName = user == null ? "" : user;
        this.password = secret == null ? "" : secret;
        this.token = bearerToken == null ? "" : bearerToken;
        return this;
    }

    public String userName() {
        return userName;
    }

    /** True when a body may be sent with the chosen verb. */
    public boolean acceptsBody() {
        return method.allowsRequestBody();
    }

/**
     * The first thing wrong with the form, in words a user can act on.
     *
     * <p>Ordered so the message names the field most likely to be the one being looked at:
     * the address, then the path, then the body, then the credentials. Empty when the form
     * is ready to send.
     */
    public Optional<String> problem() {
        if (baseUrl.isBlank()) {
            return Optional.of("a base URL");
        }
        if (!AdhocServer.isUsableBaseUrl(baseUrl)) {
            return Optional.of("a base URL starting with http:// or https://");
        }
        if (path.isBlank()) {
            return Optional.of("a path, for example /Patient or Patient/123");
        }
        if (!body.isBlank() && !method.allowsRequestBody()) {
            return Optional.of("no body on a " + method + " request — "
                    + method + " cannot carry one");
        }
        if (AdhocAuthentication.isMissingField(authKind, userName, token)) {
            return Optional.of("an " + AdhocAuthentication.missingFieldLabel(authKind));
        }
        return Optional.empty();
    }

    /**
     * The session this request goes out on.
     *
     * <p>An {@link AdhocServer} rather than a saved definition, which is what lets a URL
     * that was never configured be called at all.
     *
     * @throws IllegalStateException when {@link #problem()} is not empty, so a caller cannot
     *         skip the check
     */
    public ServerSession toSession() {
        if (problem().isPresent()) {
            throw new IllegalStateException("The request is incomplete: " + problem().get());
        }
        ServerAuthentication authentication = AdhocAuthentication.of(authKind, userName, password, token);
        return new ServerSession(AdhocServer.at(baseUrl), authentication);
    }

    /**
     * The request this form describes.
     *
     * @throws IllegalStateException when {@link #problem()} is not empty
     */
    public RestRequest toRequest() {
        if (problem().isPresent()) {
            throw new IllegalStateException("The request is incomplete: " + problem().get());
        }
        RestRequest.Builder builder = RestRequest.builder(method, path)
                .parameters(parameters.toQueryMap());
        for (RestParameterList.Parameter header : headers.entries()) {
            if (!header.name().isBlank()) {
                builder.header(header.name(), header.value());
            }
        }
        if (!body.isBlank()) {
            builder.body(body);
            if (!contentType.isBlank()) {
                builder.contentType(contentType);
            }
        }
        if (!accept.isBlank()) {
            builder.accept(accept);
        }
        return builder.build();
    }

    /**
     * The full address this form will call, for the preview line under the URL fields.
     *
     * <p>Empty when the base URL is unusable, rather than throwing: this runs on every
     * keystroke while the user is still typing, so it has to be safe to call half-formed.
     */
    public String resolvedUrl() {
        if (!AdhocServer.isUsableBaseUrl(baseUrl)) {
            return "";
        }
        String joined;
        try {
            joined = RestUrls.join(baseUrl, path.isBlank() ? "/" : path);
        } catch (IllegalArgumentException incomplete) {
            return "";
        }
        String query = parameters.toQueryString();
        return query.isEmpty() ? joined : joined + "?" + query;
    }

    /** Never returns a password, a token or a parameter value. */
    @Override
    public String toString() {
        return "RestConsoleForm[" + method + " " + resolvedUrl() + ", "
                + parameters.size() + " parameters, " + headers.size() + " headers, "
                + authKind + "]";
    }
}