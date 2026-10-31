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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controlled real-HTTP fixture for one-key conditional head operations. It stores heap bytes and
 * checks request/signature shape, not credentials, TLS, service durability or distributed ownership.
 * Each normal write assigns a new opaque ETag; forceState deliberately permits rollback/token faults.
 * Persistent dropped replies prevent a transparent transport retry from hiding uncertainty.
 */
final class S3HeadProtocolFixture implements AutoCloseable {
    static final String BUCKET = "bucket";
    static final int HEAD_BYTES = 256;
    private static final int MAX_OBJECT_BYTES = 4096;
    private static final int MAX_REQUEST_BYTES = 4096;
    private static final int MAX_REQUESTS = 256;
    private static final int MAX_OBJECTS = 32;
    private static final long MAX_STORED_BYTES = 128 * 1024;
    private static final Duration SLOW_DELAY = Duration.ofSeconds(2);

    enum Fault {
        NORMAL, DROP_BEFORE, DROP_AFTER, CONFLICT_409, PRECONDITION_412,
        NOT_FOUND_404, FORBIDDEN, NO_BUCKET, PARTIAL_206, CONTENT_RANGE_200,
        CHUNKED, SHORT_BODY, TRUNCATED_BODY, LONG_BODY, OVERSIZE_LENGTH,
        SLOW_BODY, SLOW_PUT_BEFORE, HUGE_ERROR, SUCCESS_ERROR_BODY, HUGE_SUCCESS_BODY,
        MISSING_ETAG, WEAK_ETAG, MALFORMED_ETAG, MULTIPLE_ETAGS, OVERSIZE_ETAG,
        DUPLICATE_LENGTH
    }

    /** Independent bytes; callers cannot mutate stored or previously returned state. */
    record State(byte[] bytes, String etag) {
        State {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            Objects.requireNonNull(etag, "etag");
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    /** Header names are lowercase; repeated values remain distinct. */
    record Request(String method, String key, String path, Map<String, List<String>> headers,
                   byte[] body) {
        Request {
            var copy = new HashMap<String, List<String>>();
            headers.forEach((name, values) -> copy.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            headers = Collections.unmodifiableMap(copy);
            body = body.clone();
        }

        @Override public byte[] body() { return body.clone(); }
        List<String> headerValues(String name) { return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()); }
        String header(String name) {
            var values = headerValues(name);
            return values.isEmpty() ? null : values.getFirst();
        }
    }

    private record PutResult(int status, State state) { }

    /** One request can be held after receipt and before its atomic condition/effect. */
    static final class PendingPut {
        private static final Duration MAX_WAIT = Duration.ofSeconds(15);
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private boolean claimed;
        private volatile boolean cancelled;

        boolean awaitStarted(Duration timeout) throws InterruptedException { return await(started, timeout); }
        boolean awaitFinished(Duration timeout) throws InterruptedException { return await(finished, timeout); }
        void release() { released.countDown(); }

        private static boolean await(CountDownLatch event, Duration timeout) throws InterruptedException {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_WAIT) > 0) {
                throw new IllegalArgumentException("Gate wait must be positive and at most 15 seconds");
            }
            return event.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private boolean enter() {
            started.countDown();
            try { return released.await(MAX_WAIT.toNanos(), TimeUnit.NANOSECONDS) && !cancelled; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }

        private void cancel() {
            cancelled = true;
            released.countDown();
            finished.countDown();
        }
    }

    private final Map<String, State> objects = new HashMap<>();
    private final Map<String, byte[]> afterGet = new HashMap<>();
    private final Map<String, Fault> faults = new HashMap<>();
    private final Map<String, PendingPut> putGates = new HashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64));
    private final HttpServer server;
    private long serial;

    S3HeadProtocolFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        try {
            server.setExecutor(workers);
            server.createContext("/", this::handle);
            server.start();
        } catch (RuntimeException | Error failure) {
            server.stop(0);
            workers.shutdownNow();
            throw failure;
        }
    }

    URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

    /** Install external bytes using a new opaque token, including malformed images for read faults. */
    synchronized State put(String key, byte[] bytes) {
        checkStorage(key, bytes);
        var state = new State(bytes, nextToken());
        objects.put(key, state);
        return state;
    }

    synchronized State state(String key) { return objects.get(key); }
    synchronized void remove(String key) { objects.remove(key); }

    /** Deliberately violate immutable placement/token assumptions to expose ABA/rollback limits. */
    synchronized State forceState(String key, byte[] bytes, String etag) {
        checkStorage(key, bytes);
        var state = new State(bytes, etag);
        objects.put(key, state);
        return state;
    }

    /** The next GET returns its captured old state/absence, while stored state already advances. */
    synchronized void changeAfterNextGet(String key, byte[] bytes) {
        checkStorage(key, bytes);
        if (!afterGet.containsKey(key) && afterGet.size() >= MAX_OBJECTS) {
            throw new IllegalStateException("Head fixture pending-change limit");
        }
        afterGet.put(key, bytes.clone());
    }

    synchronized void fault(String method, String key, Fault fault) {
        String target = route(method, key);
        if (!faults.containsKey(target) && faults.size() >= MAX_REQUESTS) {
            throw new IllegalStateException("Head fixture fault count limit");
        }
        faults.put(target, Objects.requireNonNull(fault, "fault"));
    }

    synchronized void clearFault(String method, String key) { faults.remove(route(method, key)); }

    /** Register at most one pending/active gate per key; no gate holds the state-map lock. */
    synchronized PendingPut holdNextPut(String key) {
        Objects.requireNonNull(key, "key");
        if (closed.get()) { throw new IllegalStateException("Head fixture is closed"); }
        if (putGates.containsKey(key)) { throw new IllegalStateException("A PUT gate already exists for this key"); }
        if (putGates.size() >= MAX_OBJECTS) { throw new IllegalStateException("Head fixture PUT gate limit"); }
        var gate = new PendingPut();
        putGates.put(key, gate);
        return gate;
    }

    List<Request> requests() { return List.copyOf(requests); }
    List<String> violations() { return List.copyOf(violations); }
    long count(String method, String key) {
        return requests.stream().filter(request -> request.method().equals(method) && request.key().equals(key)).count();
    }

    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) { return; }
        synchronized (this) {
            putGates.values().forEach(PendingPut::cancel);
            putGates.clear();
        }
        try { server.stop(0); }
        finally { workers.shutdownNow(); }
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) { throw new IOException("Head fixture workers did not stop"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while closing head fixture", interrupted);
        }
    }

    private static String route(String method, String key) { return method + " " + key; }
    private String nextToken() { return "\"opaque-head-token-" + ++serial + "\""; }

    private synchronized Fault faultFor(String method, String key) {
        return faults.getOrDefault(route(method, key), Fault.NORMAL);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (requestCount.incrementAndGet() > MAX_REQUESTS) {
                error(exchange, 503, "SlowDown", "Head fixture request limit");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith("/" + BUCKET + "/")) {
                error(exchange, 404, "NoSuchBucket", "Bucket does not exist");
                return;
            }
            String key = path.substring(BUCKET.length() + 2);
            if (!key.matches("ns-[0-9a-f]{32}-current\\.head")) {
                error(exchange, 400, "InvalidArgument", "Fixture requires a namespaced current-head key");
                return;
            }
            var request = new Request(exchange.getRequestMethod(), key, path,
                    exchange.getRequestHeaders(), readRequest(exchange.getRequestBody()));
            requests.add(request);
            if (!validate(exchange, request)) { return; }
            Fault fault = faultFor(request.method(), key);
            if (fault == Fault.DROP_BEFORE) { return; }
            if (fault == Fault.CONFLICT_409) { error(exchange, 409, "ConditionalRequestConflict", "Concurrent operation"); return; }
            if (fault == Fault.PRECONDITION_412) { error(exchange, 412, "PreconditionFailed", "Condition not met"); return; }
            if (fault == Fault.NOT_FOUND_404) { error(exchange, 404, "NoSuchKey", "Object does not exist"); return; }
            if (fault == Fault.NO_BUCKET) { error(exchange, 404, "NoSuchBucket", "Bucket does not exist"); return; }
            if (fault == Fault.FORBIDDEN || fault == Fault.HUGE_ERROR) {
                error(exchange, 403, "AccessDenied", fault == Fault.HUGE_ERROR ? "x".repeat(1024 * 1024) : "Denied");
                return;
            }
            if (request.method().equals("PUT")) { put(exchange, request, fault); }
            else { get(exchange, key, fault); }
        }
    }

    private boolean validate(HttpExchange exchange, Request request) throws IOException {
        var errors = new ArrayList<String>();
        String authorization = request.header("authorization");
        if (authorization == null || !authorization.startsWith("AWS4-HMAC-SHA256 ") || !authorization.contains("Signature=")) {
            errors.add("Missing SigV4-shaped Authorization");
        }
        if (request.method().equals("PUT")) {
            var absent = request.headerValues("if-none-match");
            var matched = request.headerValues("if-match");
            boolean create = absent.size() == 1 && absent.getFirst().equals("*") && matched.isEmpty();
            boolean replace = absent.isEmpty() && matched.size() == 1 && strongTag(matched.getFirst());
            if (!create && !replace) { errors.add("PUT requires exactly one create or observed-token precondition"); }
            if (request.body.length != HEAD_BYTES) { errors.add("PUT must contain exactly 256 binary head bytes"); }
            if (request.headerValues("content-length").size() != 1
                    || !Integer.toString(request.body.length).equals(request.header("content-length"))) {
                errors.add("PUT Content-Length differs from binary request body");
            }
            if (request.header("content-encoding") != null) { errors.add("PUT must not use encoded upload framing"); }
        } else if (request.method().equals("GET")) {
            if (request.header("range") != null) { errors.add("Complete head GET unexpectedly has Range"); }
            if (request.body.length != 0) { errors.add("GET unexpectedly has a body"); }
        } else { errors.add("Only GET and conditional PUT are supported"); }
        if (errors.isEmpty()) { return true; }
        errors.forEach(message -> violations.add(request.method() + " " + request.key() + ": " + message));
        error(exchange, 400, "InvalidRequest", "Head fixture request contract failed");
        return false;
    }

    private static boolean strongTag(String value) {
        if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') { return false; }
        for (int i = 1; i < value.length() - 1; i++) {
            char character = value.charAt(i);
            if (character != 0x21 && (character < 0x23 || character > 0x7e)) { return false; }
        }
        return true;
    }

    private static byte[] readRequest(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) { throw new IOException("Head fixture request exceeds limit"); }
        return bytes;
    }

    private synchronized void checkStorage(String key, byte[] bytes) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_OBJECT_BYTES) { throw new IllegalArgumentException("Head fixture image exceeds limit"); }
        if (!objects.containsKey(key) && objects.size() >= MAX_OBJECTS) { throw new IllegalStateException("Head fixture object limit"); }
        long total = objects.values().stream().mapToLong(value -> value.bytes.length).sum();
        State previous = objects.get(key);
        if (total - (previous == null ? 0 : previous.bytes.length) + bytes.length > MAX_STORED_BYTES) {
            throw new IllegalStateException("Head fixture stored-byte limit");
        }
    }

    private synchronized PutResult replace(Request request) {
        State previous = objects.get(request.key());
        if (request.header("if-none-match") != null) {
            if (previous != null) { return new PutResult(412, previous); }
        } else {
            if (previous == null) { return new PutResult(404, null); }
            if (!previous.etag.equals(request.header("if-match"))) { return new PutResult(412, previous); }
        }
        return new PutResult(200, put(request.key(), request.body));
    }

    private synchronized PendingPut claimPut(String key) {
        PendingPut gate = putGates.get(key);
        if (gate == null || gate.claimed) { return null; }
        gate.claimed = true;
        return gate;
    }

    private synchronized void finishPut(String key, PendingPut gate) {
        putGates.remove(key, gate);
        gate.finished.countDown();
    }

    private void put(HttpExchange exchange, Request request, Fault fault) throws IOException {
        PendingPut gate = claimPut(request.key());
        try {
            if (gate != null && !gate.enter()) { return; }
            if (closed.get() || (fault == Fault.SLOW_PUT_BEFORE && !delay())) { return; }
            PutResult result = replace(request);
            // Signal completion after the condition/effect, before a possibly lost response.
            if (gate != null) { finishPut(request.key(), gate); }
            if (fault == Fault.DROP_AFTER) { return; }
            if (result.status == 404) { error(exchange, 404, "NoSuchKey", "Object does not exist"); return; }
            if (result.status == 412) { error(exchange, 412, "PreconditionFailed", "Observed token differs"); return; }
            if (fault == Fault.SUCCESS_ERROR_BODY || fault == Fault.HUGE_SUCCESS_BODY) {
                error(exchange, 200, "InternalError", fault == Fault.HUGE_SUCCESS_BODY ? "x".repeat(1024 * 1024) : "Uncertain write");
                return;
            }
            setToken(exchange, result.state.etag, fault);
            exchange.getResponseHeaders().set("x-amz-request-id", "head-fixture-put");
            exchange.sendResponseHeaders(200, -1);
        } finally {
            // Also release waiters after cancellation/failed attempts; tests inspect actual state
            // to distinguish an effect from a completed precondition failure or shutdown.
            if (gate != null) { finishPut(request.key(), gate); }
        }
    }

    private synchronized State capture(String key) {
        State snapshot = objects.get(key);
        byte[] replacement = afterGet.remove(key);
        if (replacement != null) { put(key, replacement); }
        return snapshot;
    }

    private void get(HttpExchange exchange, String key, Fault fault) throws IOException {
        State snapshot = capture(key);
        if (snapshot == null) { error(exchange, 404, "NoSuchKey", "Object does not exist"); return; }
        if (fault == Fault.DROP_AFTER) { return; }
        byte[] body = snapshot.bytes;
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("x-amz-request-id", "head-fixture-get");
        setToken(exchange, snapshot.etag, fault);
        if (fault == Fault.CONTENT_RANGE_200) {
            exchange.getResponseHeaders().set("Content-Range", "bytes 0-255/256");
        }
        if (fault == Fault.PARTIAL_206) {
            exchange.getResponseHeaders().set("Content-Range", "bytes 0-0/256");
            exchange.sendResponseHeaders(206, 1);
            exchange.getResponseBody().write(body.length == 0 ? 0 : body[0]);
        } else if (fault == Fault.CHUNKED || fault == Fault.LONG_BODY || fault == Fault.DUPLICATE_LENGTH) {
            if (fault == Fault.DUPLICATE_LENGTH) {
                // HttpServer replaces Content-Length for fixed bodies. Chunked framing preserves
                // these duplicate raw fields so the adapter/transport must reject the malformed reply.
                exchange.getResponseHeaders().put("Content-Length", List.of("256", "256"));
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(body);
            if (fault == Fault.LONG_BODY) { exchange.getResponseBody().write(1); }
        } else if (fault == Fault.SHORT_BODY) {
            int shorter = Math.max(0, body.length - 1);
            exchange.sendResponseHeaders(200, shorter == 0 ? -1 : shorter);
            exchange.getResponseBody().write(body, 0, shorter);
        } else if (fault == Fault.TRUNCATED_BODY) {
            exchange.sendResponseHeaders(200, HEAD_BYTES);
            exchange.getResponseBody().write(body, 0, Math.min(body.length, HEAD_BYTES - 1));
        } else if (fault == Fault.OVERSIZE_LENGTH) {
            exchange.sendResponseHeaders(200, HEAD_BYTES + 1L);
        } else {
            exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
            if (fault == Fault.SLOW_BODY && !delay()) { return; }
            if (body.length != 0) { exchange.getResponseBody().write(body); }
        }
    }

    private static void setToken(HttpExchange exchange, String token, Fault fault) {
        if (fault == Fault.MISSING_ETAG) { return; }
        String value = switch (fault) {
            case WEAK_ETAG -> "W/" + token;
            case MALFORMED_ETAG -> "not-a-quoted-token";
            case OVERSIZE_ETAG -> "\"" + "x".repeat(1023) + "\"";
            default -> token;
        };
        exchange.getResponseHeaders().set("ETag", value);
        if (fault == Fault.MULTIPLE_ETAGS) { exchange.getResponseHeaders().add("ETag", "\"another-head-token\""); }
    }

    private static boolean delay() {
        try { Thread.sleep(SLOW_DELAY); return true; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }

    private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        byte[] image = ("<?xml version=\"1.0\"?><Error><Code>" + code + "</Code><Message>" + message
                + "</Message><RequestId>head-fixture-error</RequestId></Error>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.getResponseHeaders().set("x-amz-request-id", "head-fixture-error");
        exchange.sendResponseHeaders(status, image.length);
        exchange.getResponseBody().write(image);
    }
}
