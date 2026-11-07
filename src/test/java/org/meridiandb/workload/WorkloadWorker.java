// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;

/** JDK-only worker for the supervised developer harness; invocation alone is not trusted evidence. */
public final class WorkloadWorker {
    static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    static final int MAX_JSON_DEPTH = 32;
    static final int MAX_JSON_NODES = 500_000;
    static final int MAX_PROC_BYTES = 4096;
    static final List<String> IO_FIELDS = List.of("rchar", "wchar", "syscr", "syscw", "read_bytes",
            "write_bytes", "cancelled_write_bytes");
    private static final String PARTIAL_NAME = "worker.partial.json";

    private record Request(int schemaVersion, String runId, String source, WorkloadCorpus.Profile profile,
                           long seed, WorkloadCorpus.Spec spec, Dimensions dimensions,
                           LocalWorkloadRunner.Config config, Path scratch, Path output,
                           WorkloadCorpus attributedCorpus) { }

    public record Dimensions(int entities, int commits, int queries, int intervalWidth) { }

    public record SourceHistory(String headerHex, List<String> framesHex) {
        public SourceHistory {
            requireHex(headerHex);
            if (framesHex == null || framesHex.size() > 4096) { throw new IllegalArgumentException("Invalid original frame count"); }
            framesHex = List.copyOf(framesHex);
            framesHex.forEach(WorkloadWorker::requireHex);
        }
    }

    /** Instrumented evidence is separate from the unchanged inner measurement result. */
    public record Attribution(int schemaVersion, String mode, List<LocalWorkloadIo.Entry> scopes,
                              LocalAttributedWorkload.Denominators denominators, List<String> gaps,
                              SourceHistory sourceHistory) {
        public Attribution {
            if (schemaVersion != 1 || !LocalAttributedWorkload.MODE.equals(mode)
                    || scopes.size() > LocalAttributedWorkload.MAX_SCOPES) {
                throw new IllegalArgumentException("Invalid attributed evidence shape");
            }
            scopes = List.copyOf(scopes);
            gaps = List.copyOf(gaps);
        }
    }

    @FunctionalInterface
    interface AttributedExecution {
        LocalAttributedWorkload.Result run(Path scratch, WorkloadCorpus corpus, LocalWorkloadRunner.Config config,
                                          LocalWorkloadRunner.IterationObserver observer) throws IOException;
    }

    record IoSnapshot(Map<String, Long> counters, String unavailable) {
        IoSnapshot {
            if (counters != null) { counters = Collections.unmodifiableMap(new LinkedHashMap<>(counters)); }
        }
    }

    public record IoObservation(int index, boolean warmup, Path directory, Map<String, Long> before,
                                Map<String, Long> after, Map<String, Long> delta, String unavailable) { }

    private WorkloadWorker() { }

    public static void main(String[] args) { System.exit(execute(args)); }

    /** Exit 0 is success; 3 explicit directory-sync unavailability; 1 execution/export failure; 2 invalid input. */
    static int execute(String[] args) {
        return execute(args, LocalAttributedWorkload::run);
    }

    // Test-only execution seam preserves the real process observer and actual argument/export paths.
    static int execute(String[] args, AttributedExecution attributedExecution) {
        final Request request;
        try {
            request = request(args);
        } catch (IOException | IllegalArgumentException | NullPointerException invalid) {
            System.err.println("Invalid workload-worker arguments: " + bounded(invalid.getMessage(), 512));
            return 2;
        }
        var io = new ProcessIoObserver(Path.of("/proc/self/io"));
        LocalWorkloadRunner.Result result = null;
        List<LocalWorkloadIo.Entry> scopes = List.of();
        LocalAttributedWorkload.Denominators denominators = null;
        SourceHistory history = null;
        List<String> attributedGaps = List.of("Attributed lifecycle did not complete; no successful amplification result");
        Exception failure = null;
        String status;
        try {
            var corpus = request.attributedCorpus() == null ? WorkloadCorpus.generate(request.spec()) : request.attributedCorpus();
            if (request.schemaVersion() == 3) {
                denominators = LocalAttributedWorkload.denominators(corpus);
                history = sourceHistory(corpus);
                var attributed = Objects.requireNonNull(attributedExecution, "attributedExecution")
                        .run(request.scratch(), corpus, request.config(), io);
                result = attributed.workload();
                scopes = attributed.scopes();
                attributedGaps = attributed.gaps();
            } else {
                result = LocalWorkloadRunner.run(request.scratch(), corpus, request.config(),
                        WorkloadMeasurements.systemProbe(), io);
            }
            status = "success";
        } catch (Exception caught) {
            failure = caught;
            status = failureStatus(caught);
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable current = caught; current != null && seen.add(current); current = current.getCause()) {
                if (current instanceof LocalAttributedWorkload.RunFailedException attributed) { scopes = attributed.scopes(); break; }
            }
        }
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("schemaVersion", request.schemaVersion());
        envelope.put("runId", request.runId());
        envelope.put("source", request.source());
        envelope.put("profile", request.profile().name());
        envelope.put("seed", request.seed());
        envelope.put("workerPid", ProcessHandle.current().pid());
        envelope.put("status", status);
        envelope.put("error", failure == null ? null : Map.of(
                "type", bounded(failure.getClass().getName(), 256),
                "message", bounded(failure.getMessage(), 1024)));
        envelope.put("io", io.observations());
        envelope.put("result", result);
        if (request.dimensions() != null) { envelope.put("dimensions", request.dimensions()); }
        if (request.schemaVersion() == 3) {
            envelope.put("mode", LocalAttributedWorkload.MODE);
            envelope.put("attribution", new Attribution(1, LocalAttributedWorkload.MODE, scopes,
                    denominators, attributedGaps, history));
        }
        try {
            writeEnvelope(request.output(), envelope);
        } catch (IOException | RuntimeException exportFailure) {
            // Retain partial/final images for inspection; the supervisor must reject a nonzero export.
            System.err.println("Workload-worker evidence export failed: " + bounded(exportFailure.getMessage(), 512));
            return 1;
        }
        return status.equals("success") ? 0 : status.equals("unsupported") ? 3 : 1;
    }

    private static Request request(String[] args) throws IOException {
        boolean attributed = args != null && args.length == 13 && "--attributed".equals(args[0]);
        if (args == null || (!attributed && args.length != 8 && args.length != 12)) {
            throw new IllegalArgumentException("Exactly eight legacy or twelve dimensioned arguments required; --attributed requires twelve arguments");
        }
        if (attributed) { args = java.util.Arrays.copyOfRange(args, 1, args.length); }
        for (String arg : args) {
            if (arg == null || arg.length() > 4096 || arg.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                throw new IllegalArgumentException("Argument contains unsupported length or control characters");
            }
        }
        if (!args[0].matches("[0-9a-f]{32}") || !args[1].matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Expected lowercase run UUID and full source SHA");
        }
        var profile = WorkloadCorpus.Profile.valueOf(args[2]);
        long seed = Long.parseLong(args[3]);
        var config = new LocalWorkloadRunner.Config(Integer.parseInt(args[4]), Integer.parseInt(args[5]));
        boolean dimensioned = args.length == 12;
        var dimensions = dimensioned ? new Dimensions(Integer.parseInt(args[8]), Integer.parseInt(args[9]),
                Integer.parseInt(args[10]), Integer.parseInt(args[11])) : null;
        var spec = dimensioned ? admitSpec(profile, seed, config, dimensions.entities(), dimensions.commits(),
                dimensions.queries(), dimensions.intervalWidth()) : admitSpec(profile, seed, config, 16, 128, 64, 8);
        WorkloadCorpus attributedCorpus = attributed ? admitAttributedCorpus(spec, config) : null;
        // Reference/action work and the optional generated trace are admitted before inspecting paths or creating storage.
        Path suppliedScratch = Path.of(args[6]);
        Path suppliedOutput = Path.of(args[7]);
        if (!suppliedScratch.isAbsolute() || !suppliedOutput.isAbsolute()) {
            throw new IllegalArgumentException("Absolute scratch and output paths required");
        }
        Path scratch = suppliedScratch.normalize();
        var attributes = Files.readAttributes(scratch, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || !scratch.toRealPath().equals(scratch)) {
            throw new IllegalArgumentException("Scratch parent must be an existing canonical real directory");
        }
        Path output = suppliedOutput.normalize();
        if (scratch.getParent() == null || !scratch.getParent().equals(output.getParent())
                || !output.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || output.getFileName().toString().equals(PARTIAL_NAME)) {
            throw new IllegalArgumentException("Output must be a fresh direct child of the run-owned scratch parent directory");
        }
        requireFresh(output);
        requireFresh(output.resolveSibling(PARTIAL_NAME));
        return new Request(attributed ? 3 : dimensioned ? 2 : 1, args[0], args[1], profile, seed, spec, dimensions,
                config, scratch, output, attributedCorpus);
    }

    /** Generate deterministic actions and admit their complete trace before any path inspection. */
    static WorkloadCorpus admitAttributedCorpus(WorkloadCorpus.Spec spec, LocalWorkloadRunner.Config config) {
        var corpus = WorkloadCorpus.generate(spec);
        LocalAttributedWorkload.admittedCapacity(corpus, config);
        return corpus;
    }

    static SourceHistory sourceHistory(WorkloadCorpus corpus) {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Required SHA-256 unavailable", unavailable); }
        byte[] header = JournalCodec.encodeHeader(WorkloadCorpus.DATABASE);
        digest.update(header);
        long bytes = header.length;
        var frames = new ArrayList<String>(corpus.spec().commits());
        for (var action : corpus.actions()) {
            if (action instanceof WorkloadCorpus.Submit submit && !submit.retry()) {
                if (submit.sequence() != frames.size() + 1L) { throw new IllegalArgumentException("Nonsequential original history"); }
                byte[] frame = JournalCodec.encodeRecord(new JournalRecord(submit.sequence(), submit.request()));
                digest.update(frame);
                bytes = Math.addExact(bytes, frame.length);
                frames.add(HexFormat.of().formatHex(frame));
            }
        }
        if (frames.size() != corpus.spec().commits() || bytes != corpus.encodedHistoryBytes()
                || !HexFormat.of().formatHex(digest.digest()).equals(corpus.historyFingerprint())) {
            throw new IllegalArgumentException("Original source history binding differs");
        }
        return new SourceHistory(HexFormat.of().formatHex(header), frames);
    }

    private static void requireHex(String value) {
        if (value == null || value.isEmpty() || (value.length() & 1) != 0
                || value.length() > 2L * WorkloadCorpus.MAX_ENCODED_BYTES
                || !value.matches("[0-9a-f]+")) { throw new IllegalArgumentException("Require bounded lowercase original-history hex"); }
    }

    static WorkloadCorpus.Spec admitSpec(WorkloadCorpus.Profile profile, long seed, LocalWorkloadRunner.Config config,
                                        int entities, int commits, int queries, int intervalWidth) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(config, "config");
        var spec = profile.spec(seed, entities, commits, queries, intervalWidth);
        long actions = (2L * commits + queries) * (config.warmups() + config.repetitions());
        if (actions > LocalWorkloadRunner.MAX_ITERATION_ACTIONS) {
            throw new IllegalArgumentException("Conservative aggregate iteration action admission exceeded");
        }
        return spec;
    }

    static String failureStatus(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof LocalCheckpointStore.DirectorySyncUnsupportedException) { return "unsupported"; }
        }
        return "failed";
    }

    static void writeEnvelope(Path output, Object envelope) throws IOException {
        requireFresh(output);
        Path partial = output.resolveSibling(PARTIAL_NAME);
        try (var writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            new Json(writer, MAX_JSON_BYTES - 1, MAX_JSON_DEPTH, MAX_JSON_NODES).value(envelope, 0);
            writer.write('\n');
        }
        // Unlike ATOMIC_MOVE, createLink cannot replace a destination introduced after preflight.
        Files.createLink(output, partial);
        Files.delete(partial);
    }

    private static void requireFresh(Path path) throws IOException {
        if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Worker evidence path already exists or cannot be inspected: " + path.getFileName());
        }
    }

    /** Exactly seven bounded nonnegative signed-long counters; malformed/new formats remain explicit gaps. */
    static Map<String, Long> parseProcIo(String input) {
        if (input.length() > MAX_PROC_BYTES) { throw new IllegalArgumentException("Process I/O report exceeds byte admission"); }
        var parsed = new LinkedHashMap<String, Long>();
        for (String line : input.split("\\R")) {
            if (line.isBlank()) { continue; }
            int colon = line.indexOf(':');
            if (colon <= 0 || line.indexOf(':', colon + 1) >= 0) {
                throw new IllegalArgumentException("Malformed process I/O line");
            }
            String key = line.substring(0, colon);
            String raw = line.substring(colon + 1).trim();
            if (!IO_FIELDS.contains(key) || !raw.matches("[0-9]{1,19}") || parsed.containsKey(key)) {
                throw new IllegalArgumentException("Unknown, duplicate or invalid process I/O field");
            }
            parsed.put(key, Long.parseLong(raw));
        }
        if (parsed.size() != IO_FIELDS.size()) { throw new IllegalArgumentException("Incomplete process I/O report"); }
        var ordered = new LinkedHashMap<String, Long>();
        IO_FIELDS.forEach(key -> ordered.put(key, parsed.get(key)));
        return Collections.unmodifiableMap(ordered);
    }

    private static IoSnapshot readProcIo(Path path) {
        try (var source = Files.newInputStream(path)) {
            byte[] bytes = source.readNBytes(MAX_PROC_BYTES + 1);
            if (bytes.length > MAX_PROC_BYTES) { throw new IOException("Process I/O report exceeds byte admission"); }
            return new IoSnapshot(parseProcIo(new String(bytes, StandardCharsets.US_ASCII)), null);
        } catch (IOException | IllegalArgumentException unavailable) {
            return new IoSnapshot(null, bounded(unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage(), 512));
        }
    }

    /** proc reads themselves affect rchar/syscr. This scope includes checks/JVM activity, not only engine calls. */
    static final class ProcessIoObserver implements LocalWorkloadRunner.IterationObserver {
        private final Path path;
        private final List<IoObservation> observations = new ArrayList<>();
        private final Set<Integer> completed = new java.util.HashSet<>();

        ProcessIoObserver(Path path) { this.path = Objects.requireNonNull(path, "path"); }

        @Override public void before(int index, boolean warmup, Path directory) {
            if (index != observations.size()) { throw new IllegalArgumentException("Process I/O iteration order differs"); }
            var before = readProcIo(path);
            observations.add(new IoObservation(index, warmup, directory, before.counters(), null, null,
                    before.unavailable() == null ? "Iteration did not complete" : "Before snapshot unavailable: " + before.unavailable()));
        }

        @Override public void after(int index, boolean warmup, Path directory) {
            if (observations.isEmpty()) { throw new IllegalArgumentException("Process I/O before snapshot missing"); }
            var before = observations.getLast();
            if (before.index() != index || before.warmup() != warmup || !before.directory().equals(directory)
                    || completed.contains(index)) {
                throw new IllegalArgumentException("Process I/O iteration binding differs");
            }
            var after = readProcIo(path);
            Map<String, Long> delta = null;
            String unavailable = null;
            if (before.before() == null) {
                unavailable = before.unavailable();
            } else if (after.counters() == null) {
                unavailable = "After snapshot unavailable: " + after.unavailable();
            } else {
                var values = new LinkedHashMap<String, Long>();
                for (String key : IO_FIELDS) {
                    long first = before.before().get(key), last = after.counters().get(key);
                    if (last < first) {
                        unavailable = "Nonmonotonic process I/O counter: " + key;
                        break;
                    }
                    values.put(key, last - first);
                }
                if (unavailable == null) { delta = Collections.unmodifiableMap(values); }
            }
            observations.set(observations.size() - 1, new IoObservation(index, warmup, directory,
                    before.before(), after.counters(), delta, unavailable));
            completed.add(index);
        }

        List<IoObservation> observations() { return List.copyOf(observations); }
    }

    static String json(Object value) throws IOException {
        return json(value, MAX_JSON_BYTES, MAX_JSON_DEPTH, MAX_JSON_NODES);
    }

    static String json(Object value, int maxBytes, int maxDepth, int maxNodes) throws IOException {
        var destination = new StringWriter();
        new Json(destination, maxBytes, maxDepth, maxNodes).value(value, 0);
        return destination.toString();
    }

    /** All strings are ASCII JSON escapes of exact UTF-16 units, so character and UTF-8 byte counts agree. */
    private static final class Json {
        private final Writer destination;
        private final int maxBytes;
        private final int maxDepth;
        private final int maxNodes;
        private int bytes;
        private int nodes;

        Json(Writer destination, int maxBytes, int maxDepth, int maxNodes) {
            if (maxBytes < 1 || maxBytes > MAX_JSON_BYTES || maxDepth < 0 || maxDepth > MAX_JSON_DEPTH
                    || maxNodes < 1 || maxNodes > MAX_JSON_NODES) {
                throw new IllegalArgumentException("Invalid JSON admission limits");
            }
            this.destination = destination;
            this.maxBytes = maxBytes;
            this.maxDepth = maxDepth;
            this.maxNodes = maxNodes;
        }

        void value(Object value, int depth) throws IOException {
            if (depth > maxDepth || ++nodes > maxNodes) { throw new IllegalArgumentException("JSON structure admission exceeded"); }
            if (value == null) { text("null"); }
            else if (value instanceof String string) { string(string); }
            else if (value instanceof Path path) { string(path.toString()); }
            else if (value instanceof Enum<?> enumeration) { string(enumeration.name()); }
            else if (value instanceof Boolean bool) { text(bool.toString()); }
            else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long) { text(value.toString()); }
            else if (value instanceof BigInteger integer) {
                if (integer.bitLength() > (long) maxBytes * 4) {
                    throw new IllegalArgumentException("JSON integer admission exceeded");
                }
                text(integer.toString());
            }
            else if (value instanceof Double number) {
                if (!Double.isFinite(number)) { throw new IllegalArgumentException("Nonfinite JSON number"); }
                text(number.toString());
            } else if (value instanceof Float number) {
                if (!Float.isFinite(number)) { throw new IllegalArgumentException("Nonfinite JSON number"); }
                text(number.toString());
            } else if (value instanceof List<?> list) {
                text("[");
                for (int index = 0; index < list.size(); index++) {
                    if (index != 0) { text(","); }
                    value(list.get(index), depth + 1);
                }
                text("]");
            } else if (value instanceof Map<?, ?> map) {
                text("{");
                var keys = new java.util.HashSet<String>();
                for (var entry : map.entrySet()) {
                    String key = entry.getKey() instanceof String string ? string
                            : entry.getKey() instanceof Enum<?> enumeration ? enumeration.name() : null;
                    if (key == null || !keys.add(key)) { throw new IllegalArgumentException("Invalid or duplicate JSON map key"); }
                    if (keys.size() != 1) { text(","); }
                    value(key, depth + 1);
                    text(":");
                    value(entry.getValue(), depth + 1);
                }
                text("}");
            } else if (value.getClass().isRecord() && Modifier.isPublic(value.getClass().getModifiers())) {
                text("{");
                var components = value.getClass().getRecordComponents();
                for (int index = 0; index < components.length; index++) {
                    if (index != 0) { text(","); }
                    value(components[index].getName(), depth + 1);
                    text(":");
                    try {
                        value(components[index].getAccessor().invoke(value), depth + 1);
                    } catch (IllegalAccessException | InvocationTargetException reflection) {
                        throw new IllegalArgumentException("Cannot export public record component", reflection);
                    }
                }
                text("}");
            } else { throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName()); }
        }

        private void string(String value) throws IOException {
            text("\"");
            for (int index = 0; index < value.length(); index++) {
                char unit = value.charAt(index);
                if (unit == '"' || unit == '\\') { text("\\" + unit); }
                else if (unit < 0x20 || unit > 0x7e) {
                    String hex = Integer.toHexString(unit);
                    text("\\u" + "0".repeat(4 - hex.length()) + hex);
                } else { text(String.valueOf(unit)); }
            }
            text("\"");
        }

        private void text(String token) throws IOException {
            if (token.length() > maxBytes - bytes) { throw new IllegalArgumentException("JSON byte admission exceeded"); }
            destination.write(token);
            bytes += token.length();
        }
    }

    private static String bounded(String value, int limit) {
        if (value == null) { return ""; }
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
