package com.spiraleyes.fhirviewer.server.smart;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A local HTTP server that answers discovery requests with whatever the test last
 * configured.
 *
 * <p>Kept separate from the UI package's {@code StubServer} on purpose: that one records
 * whole requests for a UI layer that sends operations, while discovery needs scripted
 * status lines, sleeping responses and oversized bodies. Sharing it would couple two test
 * suites through one server's vocabulary.</p>
 *
 * <p>Bound to {@code 127.0.0.1} on an ephemeral port, like its UI sibling.</p>
 */
final class SmartStubServer {

    private final com.sun.net.httpserver.HttpServer http;
    private final AtomicInteger answerStatus = new AtomicInteger(200);
    private final AtomicReference<String> answerType =
            new AtomicReference<>("application/json");
    private final AtomicReference<String> answer = new AtomicReference<>("");
    private final AtomicReference<String> location = new AtomicReference<>("");
    private final AtomicInteger delayMillis = new AtomicInteger(0);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastPath = new AtomicReference<>("");

    SmartStubServer() throws IOException {
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

    /** The FHIR base URL of this stub, so tests ask for discovery from it. */
    String fhirBaseUrl() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/fhir";
    }

    void answerWith(int status, String contentType, String body) {
        answerStatus.set(status);
        answerType.set(contentType);
        answer.set(body == null ? "" : body);
    }

    /** Adds a {@code Location} header, for the redirect the service must not follow. */
    void redirectTo(String url) {
        answerStatus.set(302);
        location.set(url);
    }

    /** Holds the response open for this long, for the timeout the service must report. */
    void answerAfter(int millis) {
        delayMillis.set(millis);
    }

    int requestCount() {
        return requests.get();
    }

    String lastPath() {
        return lastPath.get();
    }

    private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        lastPath.set(exchange.getRequestURI().getRawPath());
        try (java.io.InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
        int delay = delayMillis.get();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!location.get().isEmpty()) {
            exchange.getResponseHeaders().set("Location", location.get());
        }
        if (answerType.get() != null) {
            exchange.getResponseHeaders().set("Content-Type", answerType.get());
        }
        byte[] bytes = answer.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(answerStatus.get(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
