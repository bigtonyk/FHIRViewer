package com.example.fhirviewer.ui;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A local HTTP server that answers whatever the test last configured.
 *
 * <p>Shared by the Phase 6 UI tests so that each of them does not carry its own copy of a
 * stub server. Deliberately minimal: it records the method, path, query, headers and body
 * of the last request, and answers with a status, a content type and a body the test sets.
 * That is enough to prove the UI layer sends what the operation descriptor promised, which
 * is the thing these tests are about.</p>
 *
 * <p>Bound to {@code 127.0.0.1} on an ephemeral port, so it never collides with anything and
 * never listens on a routable address.</p>
 */
final class StubServer {

    private final com.sun.net.httpserver.HttpServer http;
    private final AtomicReference<String> answer = new AtomicReference<>("");
    private final AtomicReference<String> answerType = new AtomicReference<>("application/json");
    private final AtomicInteger answerStatus = new AtomicInteger(200);
    private final AtomicReference<String> method = new AtomicReference<>("");
    private final AtomicReference<String> path = new AtomicReference<>("");
    private final AtomicReference<String> query = new AtomicReference<>("");
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicReference<String> contentType = new AtomicReference<>("");
    private final AtomicInteger requests = new AtomicInteger();

    StubServer() throws IOException {
        this.http = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        this.http.createContext("/", this::handle);
    }

    void start() {
        http.start();
    }

    void stop() {
        http.stop(0);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/fhir";
    }

    void answerWith(int status, String contentType, String body) {
        answerStatus.set(status);
        answerType.set(contentType);
        answer.set(body == null ? "" : body);
    }

    String lastMethod() {
        return method.get();
    }

    String lastPath() {
        return path.get();
    }

    String lastQuery() {
        return query.get();
    }

    String lastBody() {
        return body.get();
    }

    String lastContentType() {
        return contentType.get();
    }

    int requestCount() {
        return requests.get();
    }

    /**
     * Records the request, then answers with whatever the test last configured.
     *
     * <p>A {@code 204} is answered with no body and no {@code Content-Length}, because
     * sending a length of zero with a status that forbids one makes the JDK client treat
     * the exchange as malformed — which would be the stub lying, not the code under test.</p>
     */
    private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        method.set(exchange.getRequestMethod());
        // The raw path and query, so a percent-encoded path parameter is visible as sent.
        path.set(exchange.getRequestURI().getRawPath());
        query.set(exchange.getRequestURI().getRawQuery());
        contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        try (java.io.InputStream in = exchange.getRequestBody()) {
            // Always drained: a stream nobody read leaves the client waiting on a socket.
            body.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        byte[] bytes = answer.get().getBytes(StandardCharsets.UTF_8);
        int status = answerStatus.get();
        if (answerType.get() != null && status != 204) {
            exchange.getResponseHeaders().set("Content-Type", answerType.get());
        }
        if (status == 204 || bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
