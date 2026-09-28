package com.example.fhirviewer.server.rest;

import com.example.fhirviewer.server.ServerOperationException;

/**
 * The one way the application talks HTTP to a FHIR or vendor server.
 *
 * <p>This is the abstraction the whole REST feature hangs off. A plugin asks for an
 * operation, the client performs it, and the caller receives data — it never sees a URL, a
 * socket, a header map or a status line. That is what keeps vendor endpoints out of the UI:
 * a plugin that needs {@code /fhir/admin/export} asks for it here, and the UI only ever
 * asks the plugin what it can do.
 *
 * <p><b>Threading.</b> Implementations block and may wait on the network, so every method
 * must be called from a background thread. Nothing in this interface, or in the
 * {@code server.rest} package, touches JavaFX; the existing dialogs already run their work
 * on daemon threads and hand the result back with {@code Platform.runLater}.
 *
 * <p><b>Errors.</b> A response that arrived — including {@code 404} and {@code 500} — comes
 * back as a {@link RestResponse}, because a caller usually wants to read the server's
 * explanation. A request that produced no response at all comes back as a
 * {@link ServerOperationException}, since there is nothing to inspect. {@link RestFailures}
 * maps between the two, and {@link RestResponse#diagnostics()} keeps the server's own words.
 *
 * <p>Implementations are thread-safe: one instance may serve several concurrent requests.
 */
public interface RestClient extends AutoCloseable {

    /**
     * Performs one request.
     *
     * @param request what to send; never {@code null}
     * @return the server's response, whatever its status
     * @throws ServerOperationException when no response was obtained: the host was
     *         unreachable, the request timed out, the TLS handshake failed, or the answer
     *         could not be read
     */
    RestResponse execute(RestRequest request) throws ServerOperationException;

    /**
     * The base URL this client sends to, for diagnostics and error messages.
     *
     * <p>Never contains a credential. Authentication travels in request headers, which this
     * string never does.
     */
    String baseUrl();

    /**
     * Reads something with {@code GET}.
     *
     * @param path the path relative to the base URL, or an absolute URL
     * @param queryParameters name/value pairs to append, in the given order
     */
    RestResponse get(String path, java.util.Map<String, java.util.List<String>> queryParameters)
            throws ServerOperationException;

    /** Reads something with {@code GET} and no query parameters. */
    default RestResponse get(String path) throws ServerOperationException {
        return get(path, java.util.Map.of());
    }

    /**
     * Sends a body with {@code POST}.
     *
     * @param path        the path relative to the base URL, or an absolute URL
     * @param body        the request body
     * @param contentType the media type of the body, for example
     *                    {@code application/fhir+json}
     */
    RestResponse post(String path, String body, String contentType)
            throws ServerOperationException;

    /**
     * Replaces a resource with {@code PUT}.
     *
     * <p>Callers that need a conditional write set the {@code If-Match} header through
     * {@link #execute(RestRequest)}; the verb helpers exist for the common unconditional
     * case so a plugin is not forced to build a request for every call.
     */
    RestResponse put(String path, String body, String contentType)
            throws ServerOperationException;

    /**
     * Applies a partial update with {@code PATCH}.
     *
     * @param contentType the server's patch format, for example
     *                    {@code application/json-patch+json} or
     *                    {@code application/fhir+json}
     */
    RestResponse patch(String path, String body, String contentType)
            throws ServerOperationException;

    /** Removes a resource or calls a vendor delete endpoint. */
    RestResponse delete(String path) throws ServerOperationException;

    /**
     * Releases whatever the client holds.
     *
     * <p>The default does nothing, so a stateless implementation needs no code. The JDK
     * client pools connections, and {@code close} is what lets a plugin that is being
     * replaced hand those back rather than leaving them until the process exits.
     */
    @Override
    default void close() {
    }
}
