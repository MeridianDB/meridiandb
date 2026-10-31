// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded loopback S3 protocol fixture, not a service emulator or durability model.
 * It checks that SigV4 authorization is present but does not authenticate signatures.
 * Persistent faults keep a transport's transparent retry from masking lost replies.
 * All keys and bodies are in heap; no cloud credentials, filesystem or outside service is used.
 */
final class S3ProtocolFixture implements AutoCloseable {
    static final String BUCKET = "bucket";
    static final String ETAG = "\"opaque-provider-token-71\"";
    static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_REQUESTS = 256;
    private static final int MAX_OBJECTS = 64;
    private static final long MAX_STORED_BYTES = 128L * 1024 * 1024;
    private static final Duration SLOW_DELAY = Duration.ofSeconds(2);

    enum Fault {
        NORMAL, DROP_BEFORE, DROP_AFTER, CONFLICT_409, PRECONDITION_412,
        FORBIDDEN, NO_BUCKET, PARTIAL_206, CHUNKED, SHORT_BODY, LONG_BODY,
        TRUNCATED_BODY, SLOW_BODY, OVERSIZE_LENGTH, HUGE_ERROR,
        SUCCESS_ERROR_BODY, HUGE_SUCCESS_BODY, CONTENT_RANGE_200
    }

    /** Header names are lowercase. Body access returns an independent snapshot. */
    record Request(String method, String key, String path, Map<String, List<String>> headers,
                   byte[] body) {
        Request {
            var copy = new HashMap<String, List<String>>();
            headers.forEach((name, values) -> copy.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            headers = Collections.unmodifiableMap(copy);
            body = body.clone();
        }

        @Override public byte[] body() { return body.clone(); }
        String header(String name) {
            var values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.getFirst();
        }
    }

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, Fault> faults = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final HttpServer server;

    S3ProtocolFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
    }

    URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

    /** Replace external bytes deliberately, to exercise readers against changed or corrupt objects. */
    synchronized void put(String key, byte[] body) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(body, "body");
        if (body.length > MAX_BYTES) { throw new IllegalArgumentException("Fixture object exceeds limit"); }
        checkStorage(key, body.length);
        objects.put(key, body.clone());
    }

    byte[] bytes(String key) {
        byte[] body = objects.get(key);
        return body == null ? null : body.clone();
    }

    void remove(String key) { objects.remove(key); }
    void fault(String method, String key, Fault fault) { faults.put(route(method, key), fault); }
    void clearFault(String method, String key) { faults.remove(route(method, key)); }
    List<Request> requests() { return List.copyOf(requests); }
    List<String> violations() { return List.copyOf(violations); }
    long count(String method, String key) {
        return requests.stream().filter(r -> r.method().equals(method) && r.key().equals(key)).count();
    }

    @Override public void close() throws IOException {
        server.stop(0);
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IOException("Fixture workers did not stop");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while closing fixture", interrupted);
        }
    }

    private static String route(String method, String key) { return method + " " + key; }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (requestCount.incrementAndGet() > MAX_REQUESTS) {
                error(exchange, 503, "SlowDown", "Fixture request limit");
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith("/" + BUCKET + "/")) {
                error(exchange, 404, "NoSuchBucket", "Bucket does not exist");
                return;
            }
            String key = path.substring(BUCKET.length() + 2);
            if (key.isEmpty() || key.indexOf('/') >= 0) {
                error(exchange, 400, "InvalidArgument", "Fixture requires a flat key");
                return;
            }
            byte[] body = readRequest(exchange.getRequestBody());
            var request = new Request(method, key, path, exchange.getRequestHeaders(), body);
            requests.add(request);
            if (!validate(exchange, request)) { return; }
            Fault fault = faults.getOrDefault(route(method, key), Fault.NORMAL);
            if (fault == Fault.DROP_BEFORE) { return; }
            if (fault == Fault.CONFLICT_409) {
                error(exchange, 409, "ConditionalRequestConflict", "Concurrent request");
                return;
            }
            if (fault == Fault.PRECONDITION_412) {
                error(exchange, 412, "PreconditionFailed", "Condition not met");
                return;
            }
            if (fault == Fault.FORBIDDEN || fault == Fault.HUGE_ERROR) {
                error(exchange, 403, "AccessDenied",
                        fault == Fault.HUGE_ERROR ? "x".repeat(1024 * 1024) : "Denied");
                return;
            }
            if (fault == Fault.NO_BUCKET) {
                error(exchange, 404, "NoSuchBucket", "Bucket does not exist");
                return;
            }
            if (method.equals("PUT")) { put(exchange, key, body, fault); }
            else if (method.equals("GET")) { get(exchange, key, fault); }
            else { error(exchange, 405, "MethodNotAllowed", "Unsupported fixture method"); }
        }
    }

    private boolean validate(HttpExchange exchange, Request request) throws IOException {
        var errors = new ArrayList<String>();
        String auth = request.header("authorization");
        if (auth == null || !auth.startsWith("AWS4-HMAC-SHA256 ") || !auth.contains("Signature=")) {
            errors.add("Missing SigV4-shaped Authorization");
        }
        if (request.method().equals("PUT")) {
            if (!"*".equals(request.header("if-none-match"))) { errors.add("PUT lacks If-None-Match: *"); }
            if (!Integer.toString(request.body.length).equals(request.header("content-length"))) {
                errors.add("PUT Content-Length differs from binary request body");
            }
            if (request.header("content-encoding") != null) {
                errors.add("Fixture expects an unencoded binary PUT body");
            }
        }
        if (request.method().equals("GET") && request.header("range") != null) {
            errors.add("Complete object GET unexpectedly has a Range header");
        }
        if (errors.isEmpty()) { return true; }
        errors.forEach(error -> violations.add(request.method() + " " + request.key() + ": " + error));
        error(exchange, 400, "InvalidRequest", "Fixture request contract failed");
        return false;
    }

    private byte[] readRequest(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) { throw new IOException("Fixture request exceeds object limit"); }
        return bytes;
    }

    private synchronized void checkStorage(String key, int newLength) {
        if (!objects.containsKey(key) && objects.size() >= MAX_OBJECTS) {
            throw new IllegalStateException("Fixture object count limit");
        }
        long total = objects.values().stream().mapToLong(value -> value.length).sum();
        byte[] old = objects.get(key);
        if (total - (old == null ? 0 : old.length) + newLength > MAX_STORED_BYTES) {
            throw new IllegalStateException("Fixture stored-byte limit");
        }
    }

    private void put(HttpExchange exchange, String key, byte[] body, Fault fault) throws IOException {
        boolean created;
        synchronized (this) {
            checkStorage(key, body.length);
            created = objects.putIfAbsent(key, body.clone()) == null;
        }
        if (fault == Fault.DROP_AFTER) { return; }
        if (!created) { error(exchange, 412, "PreconditionFailed", "Object exists"); return; }
        if (fault == Fault.SUCCESS_ERROR_BODY || fault == Fault.HUGE_SUCCESS_BODY) {
            error(exchange, 200, "InternalError", fault == Fault.HUGE_SUCCESS_BODY ? "x".repeat(1024 * 1024) : "Uncertain write");
            return;
        }
        exchange.getResponseHeaders().set("ETag", ETAG);
        exchange.getResponseHeaders().set("x-amz-request-id", "fixture-put");
        exchange.sendResponseHeaders(200, -1);
    }

    private void get(HttpExchange exchange, String key, Fault fault) throws IOException {
        byte[] body = objects.get(key);
        if (body == null) { error(exchange, 404, "NoSuchKey", "Object does not exist"); return; }
        if (fault == Fault.DROP_AFTER) { return; }
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("ETag", ETAG);
        exchange.getResponseHeaders().set("x-amz-request-id", "fixture-get");
        if (fault == Fault.CONTENT_RANGE_200) {
            exchange.getResponseHeaders().set("Content-Range", "bytes 0-2/3");
        }
        if (fault == Fault.SLOW_BODY) {
            exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
            try { Thread.sleep(SLOW_DELAY); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        } else if (fault == Fault.PARTIAL_206) {
            exchange.getResponseHeaders().set("Content-Range", "bytes 0-0/" + body.length);
            exchange.sendResponseHeaders(206, 1);
            exchange.getResponseBody().write(body.length == 0 ? 0 : body[0]);
            return;
        } else if (fault == Fault.CHUNKED || fault == Fault.LONG_BODY) {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(body);
            if (fault == Fault.LONG_BODY) { exchange.getResponseBody().write(1); }
            return;
        } else if (fault == Fault.SHORT_BODY) {
            int shorter = Math.max(0, body.length - 1);
            exchange.sendResponseHeaders(200, shorter == 0 ? -1 : shorter);
            exchange.getResponseBody().write(body, 0, shorter);
            return;
        } else if (fault == Fault.OVERSIZE_LENGTH) {
            exchange.sendResponseHeaders(200, MAX_BYTES + 1L);
            return;
        } else if (fault == Fault.TRUNCATED_BODY) {
            exchange.sendResponseHeaders(200, body.length == 0 ? 1 : body.length);
            exchange.getResponseBody().write(body, 0, Math.max(0, body.length - 1));
            return;
        } else {
            exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        }
        if (body.length == 0) {
            // HttpServer emits Content-Length: 0 for a response with no body.
            return;
        }
        exchange.getResponseBody().write(body);
    }

    private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        byte[] image = ("<?xml version=\"1.0\"?><Error><Code>" + code + "</Code><Message>" + message
                + "</Message><RequestId>fixture-error</RequestId></Error>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.getResponseHeaders().set("x-amz-request-id", "fixture-error");
        exchange.sendResponseHeaders(status, image.length);
        exchange.getResponseBody().write(image);
    }
}
