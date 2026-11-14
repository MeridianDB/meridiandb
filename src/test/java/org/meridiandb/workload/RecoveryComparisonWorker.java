// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;
import org.meridiandb.storage.manifest.LocalCheckpointHistory;
import org.meridiandb.workload.LocalRecoveryComparison.Access;
import org.meridiandb.workload.LocalRecoveryComparison.MeasurementMode;
import org.meridiandb.workload.LocalRecoveryComparison.Operation;
import org.meridiandb.workload.LocalRecoveryComparison.Shape;
import org.meridiandb.workload.LocalRecoveryComparison.Strategy;

/** Separate bounded developer producer. Declared source and exit zero are not supervised evidence. */
public final class RecoveryComparisonWorker {
    public static final int VERSION = 1;
    public static final String PROTOCOL = "MERIDIANDB_RECOVERY_COMPARISON";
    static final int MAX_RAW_FIXTURE_BYTES = 2 * 1024 * 1024;
    static final int MAX_CONTINUATION_BYTES = 4096;
    private static final String PARTIAL_NAME = "worker.partial.json";

    private record Request(String runId, String source, MeasurementMode mode, WorkloadCorpus.Profile profile,
                           WorkloadCorpus corpus, LocalRecoveryComparison.Config config,
                           LocalCheckpointHistory.Limits limits, Path scratch, Path output,
                           WorkloadWorker.SourceHistory history) { }

    public record FixtureImages(Shape shape, Map<String, String> files) {
        public FixtureImages { files = Collections.unmodifiableMap(new TreeMap<>(files)); }
    }

    private record Export(List<FixtureImages> fixtures, String continuationFrameHex, Map<String, Object> result) { }

    @FunctionalInterface
    interface Execution {
        LocalRecoveryComparison.Result run(Path scratch, WorkloadCorpus corpus, LocalRecoveryComparison.Config config,
                                           MeasurementMode mode, LocalCheckpointHistory.Limits limits) throws IOException;
    }

    private RecoveryComparisonWorker() { }

    public static void main(String[] args) { System.exit(execute(args)); }

    /** 0 produced; 1 execution/export failure; 2 invalid arguments; 3 explicit directory-sync unavailability. */
    static int execute(String[] args) { return execute(args, LocalRecoveryComparison::run); }

    static int execute(String[] args, Execution execution) {
        final Request request;
        try { request = request(args); }
        catch (IOException | IllegalArgumentException | NullPointerException invalid) {
            System.err.println("Invalid recovery-worker arguments: " + bounded(invalid.getMessage(), 512));
            return 2;
        }
        LocalRecoveryComparison.Result completed = null;
        Export exported = null;
        Exception failure = null;
        String status;
        try {
            completed = Objects.requireNonNull(execution, "execution").run(request.scratch(), request.corpus(),
                    request.config(), request.mode(), request.limits());
            exported = export(request, Objects.requireNonNull(completed, "result"));
            status = "success";
        } catch (Exception caught) {
            failure = caught;
            status = WorkloadWorker.failureStatus(caught);
        }
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("schemaVersion", VERSION);
        envelope.put("protocol", PROTOCOL);
        envelope.put("mode", request.mode().identifier());
        envelope.put("request", requestValue(request));
        envelope.put("workerPid", ProcessHandle.current().pid());
        envelope.put("status", status);
        envelope.put("error", failure == null ? null : Map.of("type", bounded(failure.getClass().getName(), 256),
                "message", bounded(failure.getMessage(), 1024)));
        envelope.put("failedRunRoot", failure == null ? null : failedRoot(request, completed, failure));
        envelope.put("sourceHistory", request.history());
        envelope.put("continuationFrameHex", exported == null ? null : exported.continuationFrameHex());
        envelope.put("fixtureImages", exported == null ? List.of() : exported.fixtures());
        envelope.put("result", exported == null ? null : exported.result());
        try { WorkloadWorker.writeEnvelope(request.output(), envelope); }
        catch (IOException | RuntimeException exportFailure) {
            System.err.println("Recovery-worker export failed: " + bounded(exportFailure.getMessage(), 512));
            return 1;
        }
        return status.equals("success") ? 0 : status.equals("unsupported") ? 3 : 1;
    }

    private static Request request(String[] args) throws IOException {
        if (args == null || args.length != 16) { throw new IllegalArgumentException("Exactly sixteen recovery-worker arguments required"); }
        for (String arg : args) {
            if (arg == null || arg.length() > 4096 || arg.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                throw new IllegalArgumentException("Argument has unsupported length or control characters");
            }
        }
        if (!args[0].matches("[0-9a-f]{32}") || !args[1].matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Expected lowercase run UUID and full declared source SHA");
        }
        MeasurementMode mode = Arrays.stream(MeasurementMode.values()).filter(m -> m.identifier().equals(args[2]))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown recovery measurement mode identifier"));
        var profile = WorkloadCorpus.Profile.valueOf(args[3]);
        long seed = Long.parseLong(args[4]);
        var config = new LocalRecoveryComparison.Config(Integer.parseInt(args[5]), Integer.parseInt(args[6]));
        var spec = profile.spec(seed, Integer.parseInt(args[9]), Integer.parseInt(args[10]),
                Integer.parseInt(args[11]), Integer.parseInt(args[12]));
        var limits = new LocalCheckpointHistory.Limits(Long.parseLong(args[13]), Long.parseLong(args[14]), Long.parseLong(args[15]));
        long opens = 16L * (config.warmups() + config.repetitions());
        // Conservative schedule admission, stricter than the core's actual-action bound.
        long actions = 2L * spec.commits() + spec.queries();
        if (opens * (2 * actions + spec.commits() + 16) > LocalRecoveryComparison.MAX_VALIDATION_ACTIONS
                || opens * (spec.commits() + 1L) * (spec.commits() + 1L) > LocalRecoveryComparison.MAX_RECOVERY_WORK) {
            throw new IllegalArgumentException("Aggregate recovery worker work exceeds admission");
        }
        var corpus = WorkloadCorpus.generate(spec);
        var history = WorkloadWorker.sourceHistory(corpus);
        if (corpus.encodedHistoryBytes() > MAX_RAW_FIXTURE_BYTES) {
            throw new IllegalArgumentException("Original history exceeds worker raw-byte admission");
        }
        // Invalid mode/numbers/configuration/work are rejected before any path inspection or output effects.
        Path suppliedScratch = Path.of(args[7]), suppliedOutput = Path.of(args[8]);
        if (!suppliedScratch.isAbsolute() || !suppliedOutput.isAbsolute()) {
            throw new IllegalArgumentException("Absolute canonical scratch and output paths required");
        }
        Path scratch = suppliedScratch.normalize(), output = suppliedOutput.normalize();
        if (!scratch.equals(suppliedScratch) || !output.equals(suppliedOutput)
                || !Files.readAttributes(scratch, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()
                || !scratch.toRealPath().equals(scratch)) {
            throw new IllegalArgumentException("Scratch must be an existing canonical real directory; paths must be normalized");
        }
        if (scratch.getParent() == null || !scratch.getParent().equals(output.getParent())
                || !output.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || PARTIAL_NAME.equals(output.getFileName().toString())) {
            throw new IllegalArgumentException("Output must be a fresh safe sibling of the scratch directory");
        }
        requireFresh(output);
        requireFresh(output.resolveSibling(PARTIAL_NAME));
        return new Request(args[0], args[1], mode, profile, corpus, config, limits, scratch, output, history);
    }

    private static Map<String, Object> requestValue(Request request) {
        var value = new LinkedHashMap<String, Object>();
        value.put("schemaVersion", VERSION);
        value.put("runId", request.runId());
        value.put("source", request.source());
        value.put("mode", request.mode().identifier());
        value.put("profile", request.profile());
        value.put("seed", request.corpus().spec().seed());
        value.put("spec", request.corpus().spec());
        value.put("config", request.config());
        value.put("limits", request.limits());
        value.put("scratch", request.scratch());
        value.put("output", request.output());
        return Collections.unmodifiableMap(value);
    }

    private static Export export(Request request, LocalRecoveryComparison.Result result) throws IOException {
        var corpus = request.corpus();
        require(result.schemaVersion() == LocalRecoveryComparison.VERSION && result.corpusVersion() == WorkloadCorpus.VERSION
                && result.mode().equals(request.mode().identifier()) && result.spec().equals(corpus.spec())
                && result.config().equals(request.config()) && result.limits().equals(request.limits())
                && result.fingerprint().equals(corpus.fingerprint()) && result.historyFingerprint().equals(corpus.historyFingerprint())
                && result.encodedHistoryBytes() == corpus.encodedHistoryBytes(), "Comparison result differs from the admitted request/input");
        Path root = Objects.requireNonNull(result.runRoot(), "runRoot");
        require(ownedRoot(request, root), "Comparison root is outside the canonical scratch parent");
        require(result.runtime().equals(runtime(root)), "Comparison runtime differs from the actual worker");
        require(result.workload().equals(WorkloadOracle.prepare(corpus).summary()), "Comparison workload summary differs from independent oracle");
        require(result.gaps().size() <= 32 && result.gaps().stream().allMatch(g -> g != null && g.length() <= 2048),
                "Comparison gap admission exceeded");
        int iterations = request.config().warmups() + request.config().repetitions();
        require(result.observations().size() == 16 * iterations, "Incomplete comparison matrix");
        var fixtures = new EnumMap<Shape, LocalRecoveryComparison.Fixture>(Shape.class);
        var images = new ArrayList<FixtureImages>();
        var observations = new ArrayList<Map<String, Object>>();
        byte[] continuation = null;
        long rawFixtureBytes = corpus.encodedHistoryBytes();
        int row = 0;
        for (int iteration = 0; iteration < iterations; iteration++) {
            boolean warmup = iteration < request.config().warmups();
            int orderIndex = warmup ? iteration : iteration - request.config().warmups();
            var order = orderIndex % 2 == 0 ? List.of(Strategy.ORDINARY, Strategy.HISTORY_REUSE)
                    : List.of(Strategy.HISTORY_REUSE, Strategy.ORDINARY);
            for (var shape : Shape.values()) {
                for (var access : Access.values()) {
                    for (int ordinal = 0; ordinal < 2; ordinal++) {
                        var observation = result.observations().get(row++);
                        var strategy = order.get(ordinal);
                        Path home = root.resolve(iteration + "-" + shape + "-" + access + "-" + strategy);
                        require(observation.iteration() == iteration && observation.warmup() == warmup
                                && observation.strategyOrdinal() == ordinal && observation.shape() == shape
                                && observation.access() == access && observation.strategy() == strategy
                                && home.equals(observation.directory()) && home.toRealPath().equals(home)
                                && Files.readAttributes(home, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory(),
                                "Comparison observation order/directory binding differs");
                        var fixture = Objects.requireNonNull(observation.fixture(), "fixture");
                        validateFixture(fixture, shape, corpus.spec().commits());
                        var prior = fixtures.putIfAbsent(shape, fixture);
                        require(prior == null || prior.equals(fixture), "Initial fixture differs between equivalent cohorts");
                        var retained = prior == null ? new TreeMap<String, String>() : null;
                        for (var entry : fixture.initialFileSha256().entrySet()) {
                            if (entry.getKey().equals("transactions.journal")) {
                                require(entry.getValue().equals(corpus.historyFingerprint()), "Initial journal fingerprint differs");
                                continue;
                            }
                            byte[] image = read(home.resolve(entry.getKey()), MAX_RAW_FIXTURE_BYTES);
                            require(hash(image).equals(entry.getValue()), "Retained fixture differs from its initial image");
                            if (retained != null) {
                                rawFixtureBytes = Math.addExact(rawFixtureBytes, image.length);
                                require(rawFixtureBytes <= MAX_RAW_FIXTURE_BYTES, "Aggregate raw fixture evidence exceeds admission");
                                retained.put(entry.getKey(), HexFormat.of().formatHex(image));
                            }
                        }
                        if (retained != null) { images.add(new FixtureImages(shape, retained)); }
                        byte[] journal = read(home.resolve("transactions.journal"), corpus.encodedHistoryBytes() + MAX_CONTINUATION_BYTES);
                        require(journal.length == observation.closedJournalBytes() && hash(journal).equals(observation.closedJournalSha256()),
                                "Closed journal differs from comparison result");
                        int originalBytes = Math.toIntExact(corpus.encodedHistoryBytes());
                        require(journal.length >= originalBytes && hash(Arrays.copyOf(journal, originalBytes)).equals(corpus.historyFingerprint()),
                                "Closed journal original history differs");
                        if (access == Access.READ_ONLY) { require(journal.length == originalBytes, "Read-only journal changed"); }
                        else {
                            byte[] frame = Arrays.copyOfRange(journal, originalBytes, journal.length);
                            require(frame.length > 0 && frame.length <= MAX_CONTINUATION_BYTES, "Continuation frame admission differs");
                            var buffer = ByteBuffer.wrap(frame);
                            var record = JournalCodec.readRecord(buffer).orElseThrow(() -> new IOException("Incomplete continuation frame"));
                            require(!buffer.hasRemaining() && record.equals(expectedContinuation(corpus)),
                                    "Continuation frame transaction/sequence or trailing bytes differs");
                            require(continuation == null || Arrays.equals(continuation, frame), "Continuation differs between writable cohorts");
                            continuation = frame;
                        }
                        require(observation.validationNanos() >= 0 && observation.teardownNanos() >= 0, "Invalid auxiliary duration");
                        observations.add(observationValue(observation, request.mode()));
                    }
                }
            }
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("schemaVersion", result.schemaVersion());
        value.put("mode", result.mode());
        value.put("corpusVersion", result.corpusVersion());
        value.put("spec", result.spec());
        value.put("config", result.config());
        value.put("limits", result.limits());
        value.put("fingerprint", result.fingerprint());
        value.put("historyFingerprint", result.historyFingerprint());
        value.put("encodedHistoryBytes", result.encodedHistoryBytes());
        value.put("workload", result.workload());
        value.put("runtime", result.runtime());
        value.put("runRoot", result.runRoot());
        value.put("observations", List.copyOf(observations));
        value.put("gaps", result.gaps());
        return new Export(List.copyOf(images), HexFormat.of().formatHex(Objects.requireNonNull(continuation)),
                Collections.unmodifiableMap(value));
    }

    private static Map<String, Object> observationValue(LocalRecoveryComparison.Observation observation, MeasurementMode mode)
            throws IOException {
        var operations = new ArrayList<Map<String, Object>>();
        var expected = observation.access() == Access.READ_ONLY ? List.of(Operation.OPEN)
                : List.of(Operation.OPEN, Operation.ORIGINAL_RETRY, Operation.CONTINUATION, Operation.CONTINUATION_RETRY);
        require(observation.operations().size() == expected.size(), "Operation list is incomplete");
        for (int i = 0; i < expected.size(); i++) {
            var operation = observation.operations().get(i);
            var sample = operation.measurement();
            var kind = switch (expected.get(i)) {
                case OPEN -> observation.shape() == Shape.JOURNAL_ONLY ? WorkloadMeasurements.Kind.JOURNAL_REOPEN
                        : WorkloadMeasurements.Kind.CHECKPOINT_REOPEN;
                case ORIGINAL_RETRY, CONTINUATION_RETRY -> WorkloadMeasurements.Kind.RETRY;
                case CONTINUATION -> WorkloadMeasurements.Kind.COMMIT;
            };
            require(operation.operation() == expected.get(i) && sample.phase() == WorkloadMeasurements.Phase.MAINTENANCE
                    && sample.kind() == kind, "Operation ordering or clock scope differs");
            var coverage = mode == MeasurementMode.UNINSTRUMENTED ? LocalRecoveryComparison.IoCoverage.NOT_CAPTURED
                    : LocalRecoveryComparison.IoCoverage.ADAPTERS_ONLY;
            require(operation.coverage() == coverage, "Operation coverage differs from request mode");
            var value = new LinkedHashMap<String, Object>();
            value.put("operation", operation.operation());
            value.put("measurement", sample);
            value.put("coverage", operation.coverage());
            value.put("counters", operation.counters());
            operations.add(Collections.unmodifiableMap(value));
        }
        var fixture = observation.fixture();
        var encodedFixture = new LinkedHashMap<String, Object>();
        encodedFixture.put("checkpointSequence", fixture.checkpointSequence().isPresent() ? fixture.checkpointSequence().getAsLong() : null);
        encodedFixture.put("prefixBytes", fixture.prefixBytes());
        encodedFixture.put("prefixSha256", fixture.prefixSha256());
        encodedFixture.put("selectedManifestBytes", fixture.selectedManifestBytes());
        encodedFixture.put("selectedSegmentBytes", fixture.selectedSegmentBytes());
        encodedFixture.put("selectedSegments", fixture.selectedSegments());
        encodedFixture.put("initialFileSha256", fixture.initialFileSha256());
        var value = new LinkedHashMap<String, Object>();
        value.put("iteration", observation.iteration());
        value.put("warmup", observation.warmup());
        value.put("strategyOrdinal", observation.strategyOrdinal());
        value.put("shape", observation.shape());
        value.put("access", observation.access());
        value.put("strategy", observation.strategy());
        value.put("directory", observation.directory());
        value.put("fixture", Collections.unmodifiableMap(encodedFixture));
        value.put("operations", List.copyOf(operations));
        value.put("validationNanos", observation.validationNanos());
        value.put("teardownNanos", observation.teardownNanos());
        value.put("closedJournalBytes", observation.closedJournalBytes());
        value.put("closedJournalSha256", observation.closedJournalSha256());
        return Collections.unmodifiableMap(value);
    }

    private static void validateFixture(LocalRecoveryComparison.Fixture fixture, Shape shape, int commits) throws IOException {
        int cutoffRecords = commits / 2;
        int chunk = Math.min(16, Math.max(1, cutoffRecords / 2));
        int sources = (cutoffRecords + chunk - 1) / chunk;
        int manifests = switch (shape) {
            case JOURNAL_ONLY -> 0;
            case GENESIS, CHECKPOINT_TAIL -> 1;
            case COMPACTED_CHECKPOINT_TAIL -> 2;
        };
        int segments = switch (shape) {
            case JOURNAL_ONLY, GENESIS -> 0;
            case CHECKPOINT_TAIL -> sources;
            case COMPACTED_CHECKPOINT_TAIL -> sources + (sources > 1 ? 1 : 0);
        };
        require(fixture.initialFileSha256().size() == 2 + manifests + segments
                && fixture.initialFileSha256().containsKey("transactions.journal")
                && fixture.initialFileSha256().containsKey(".meridiandb-checkpoints.lock"), "Initial fixture inventory differs");
        require(fixture.initialFileSha256().keySet().stream().filter(name -> name.endsWith(".manifest")).count() == manifests
                && fixture.initialFileSha256().keySet().stream().filter(name -> name.endsWith(".segment")).count() == segments,
                "Initial fixture object counts differ");
        for (var entry : fixture.initialFileSha256().entrySet()) {
            require((entry.getKey().equals("transactions.journal") || entry.getKey().equals(".meridiandb-checkpoints.lock")
                    || entry.getKey().matches("[0-9a-f]{64}\\.segment")
                    || entry.getKey().matches("checkpoint-[0-9]{19}-[0-9a-f]{64}\\.manifest"))
                    && entry.getValue().matches("[0-9a-f]{64}"), "Invalid initial fixture name/hash");
        }
        if (shape == Shape.JOURNAL_ONLY) {
            require(fixture.checkpointSequence().isEmpty() && fixture.prefixBytes() == 0 && fixture.prefixSha256() == null
                    && fixture.selectedManifestBytes() == 0 && fixture.selectedSegmentBytes() == 0
                    && fixture.selectedSegments() == 0, "Journal-only fixture declares checkpoint work");
        } else {
            long cutoff = shape == Shape.GENESIS ? 0 : commits / 2;
            require(fixture.checkpointSequence().isPresent() && fixture.checkpointSequence().getAsLong() == cutoff
                    && fixture.prefixBytes() > 0 && fixture.prefixSha256() != null && fixture.prefixSha256().matches("[0-9a-f]{64}")
                    && fixture.selectedManifestBytes() > 0 && fixture.selectedSegmentBytes() >= 0
                    && (shape == Shape.GENESIS ? fixture.selectedSegments() == 0 && fixture.selectedSegmentBytes() == 0
                            : fixture.selectedSegments() > 0 && fixture.selectedSegmentBytes() > 0), "Selected fixture metadata differs");
        }
    }

    private static JournalRecord expectedContinuation(WorkloadCorpus corpus) {
        var requests = new java.util.HashSet<String>();
        var entities = new java.util.HashSet<String>();
        for (var action : corpus.actions()) {
            if (action instanceof WorkloadCorpus.Submit submit) {
                requests.add(submit.request().requestId().value());
                submit.request().operations().forEach(operation -> entities.add(operation.entity().value()));
            }
        }
        int suffix = 0;
        while (requests.contains("recovery-continuation-" + suffix)) { suffix++; }
        var identity = new RequestId("recovery-continuation-" + suffix);
        suffix = 0;
        while (entities.contains("recovery-continuation-entity-" + suffix)) { suffix++; }
        var entity = new EntityId("recovery-continuation-entity-" + suffix);
        return new JournalRecord(corpus.spec().commits() + 1L,
                new Transaction(identity, List.of(new WriteOperation.Put(entity, new ValidTimeInterval(null, null), 17))));
    }

    private static LocalWorkloadRunner.RuntimeInfo runtime(Path root) throws IOException {
        return new LocalWorkloadRunner.RuntimeInfo(System.getProperty("java.version"), System.getProperty("java.runtime.version"),
                System.getProperty("java.vendor"), System.getProperty("os.name"), System.getProperty("os.version"),
                System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory(),
                Files.getFileStore(root).type());
    }

    private static boolean ownedRoot(Request request, Path root) throws IOException {
        return root.isAbsolute() && root.equals(root.normalize()) && request.scratch().equals(root.getParent())
                && root.getFileName().toString().startsWith("mdb-recovery-")
                && Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()
                && root.toRealPath().equals(root);
    }

    private static String failedRoot(Request request, LocalRecoveryComparison.Result result, Exception failure) {
        Path root = result == null ? null : result.runRoot();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof LocalRecoveryComparison.RunFailedException retained) { root = retained.runRoot(); break; }
        }
        try { return root != null && ownedRoot(request, root) ? root.toString() : null; }
        catch (IOException | RuntimeException unavailable) { return null; }
    }

    private static byte[] read(Path path, long bound) throws IOException {
        require(bound >= 0 && bound <= MAX_RAW_FIXTURE_BYTES + MAX_CONTINUATION_BYTES
                && Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile(),
                "Raw evidence is not a bounded regular file");
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(Math.toIntExact(bound) + 1);
            require(bytes.length <= bound, "Raw evidence exceeds byte admission");
            return bytes;
        }
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void requireFresh(Path path) throws IOException {
        require(Files.notExists(path, LinkOption.NOFOLLOW_LINKS), "Worker evidence path exists or cannot be inspected");
    }

    private static String bounded(String value, int limit) {
        return value == null ? "" : value.substring(0, Math.min(limit, value.length()));
    }

    private static void require(boolean value, String message) throws IOException { if (!value) { throw new IOException(message); } }
}
