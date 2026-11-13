// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeMap;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.internal.LocalIoObservation;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.HistoryCompactor;
import org.meridiandb.storage.manifest.LocalCheckpointHistory;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;
import org.meridiandb.workload.WorkloadMeasurements.Kind;
import org.meridiandb.workload.WorkloadMeasurements.Phase;
import org.meridiandb.workload.WorkloadMeasurements.Probe;

/** Test-only synchronous comparison core. No serialized protocol or source-supervised evidence. */
public final class LocalRecoveryComparison {
    public static final int VERSION = 1;
    public static final String MODE = "CALLER_THREAD_RECOVERY_IO_INSTRUMENTED";
    public static final String UNINSTRUMENTED_MODE = "RECOVERY_UNINSTRUMENTED";
    public enum MeasurementMode {
        INSTRUMENTED(MODE), UNINSTRUMENTED(UNINSTRUMENTED_MODE);
        private final String identifier;
        MeasurementMode(String identifier) { this.identifier = identifier; }
        public String identifier() { return identifier; }
    }
    public enum IoCoverage { NOT_CAPTURED, ADAPTERS_ONLY }
    public static final long MAX_VALIDATION_ACTIONS = 40_000;
    public static final long MAX_RECOVERY_WORK = 32_000_000;
    public static final long MAX_FIXTURE_BYTES = 256L * 1024 * 1024;
    public enum Strategy { ORDINARY, HISTORY_REUSE }
    public enum Shape { JOURNAL_ONLY, GENESIS, CHECKPOINT_TAIL, COMPACTED_CHECKPOINT_TAIL }
    public enum Access { READ_ONLY, WRITABLE }
    public enum Operation { OPEN, ORIGINAL_RETRY, CONTINUATION, CONTINUATION_RETRY }

    /** Even measured repetitions give each shape/access equal AB and BA orders. */
    public record Config(int warmups, int repetitions) {
        public static final Config DEFAULT = new Config(1, 2);
        public Config {
            if (warmups < 0 || warmups > 2 || repetitions < 1 || repetitions > 5) {
                throw new IllegalArgumentException("Require 0..2 warmups and 1..5 measured repetitions");
            }
        }
    }

    public record Fixture(OptionalLong checkpointSequence, long prefixBytes, String prefixSha256,
                          long selectedManifestBytes, long selectedSegmentBytes, int selectedSegments,
                          Map<String, String> initialFileSha256) {
        public Fixture { initialFileSha256 = java.util.Collections.unmodifiableMap(new TreeMap<>(initialFileSha256)); }
    }
    public record OperationSample(Operation operation, WorkloadMeasurements.Sample measurement,
                                  LocalIoObservation.Snapshot counters) {
        public OperationSample {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(measurement, "measurement");
            if (counters != null && !counters.complete()) {
                throw new IllegalArgumentException("Captured comparison counters must be complete");
            }
        }
        public IoCoverage coverage() { return counters == null ? IoCoverage.NOT_CAPTURED : IoCoverage.ADAPTERS_ONLY; }
    }
    public record Observation(int iteration, boolean warmup, int strategyOrdinal, Shape shape,
                              Access access, Strategy strategy, Path directory, Fixture fixture,
                              List<OperationSample> operations, long validationNanos, long teardownNanos,
                              long closedJournalBytes, String closedJournalSha256) {
        public Observation { operations = List.copyOf(operations); }
    }
    public record Result(int schemaVersion, String mode, int corpusVersion, WorkloadCorpus.Spec spec, Config config,
                         LocalCheckpointHistory.Limits limits, String fingerprint, String historyFingerprint,
                         long encodedHistoryBytes, WorkloadOracle.Summary workload,
                         LocalWorkloadRunner.RuntimeInfo runtime, Path runRoot,
                         List<Observation> observations, List<String> gaps) {
        public Result {
            Objects.requireNonNull(mode, "mode");
            if (!MODE.equals(mode) && !UNINSTRUMENTED_MODE.equals(mode)) {
                throw new IllegalArgumentException("Unknown recovery measurement mode");
            }
            observations = List.copyOf(observations); gaps = List.copyOf(gaps);
            var expected = MODE.equals(mode) ? IoCoverage.ADAPTERS_ONLY : IoCoverage.NOT_CAPTURED;
            for (var observation : observations) {
                for (var operation : observation.operations()) {
                    if (operation.coverage() != expected) {
                        throw new IllegalArgumentException("Operation counter coverage differs from result mode");
                    }
                }
            }
        }
    }
    public static final class RunFailedException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String root;
        RunFailedException(Path root, Exception cause) {
            super("Recovery comparison failed; retained run directory: " + root, cause);
            this.root = root.toString();
        }
        public Path runRoot() { return Path.of(root); }
    }

    private record Inputs(List<JournalRecord> records, List<ImmutableSegment> segments,
                          CheckpointManifest genesis, CheckpointManifest checkpoint,
                          HistoryCompactor.Plan compacted, Transaction continuation,
                          EntityId continuationEntity, long continuedBytes, String continuedSha256) { }
    private record Measured<T>(T value, OperationSample sample) { }
    private LocalRecoveryComparison() { }

    public static Result run(Path scratchParent, WorkloadCorpus corpus, Config config,
                             LocalCheckpointHistory.Limits limits) throws IOException {
        return run(scratchParent, corpus, config, limits, WorkloadMeasurements.systemProbe());
    }

    static Result run(Path scratchParent, WorkloadCorpus corpus, Config config,
                      LocalCheckpointHistory.Limits limits, Probe probe) throws IOException {
        return run(scratchParent, corpus, config, MeasurementMode.INSTRUMENTED, limits, probe);
    }

    /** Explicit mode precedes limits to preserve the existing nullable probe overload. */
    public static Result run(Path scratchParent, WorkloadCorpus corpus, Config config,
                             MeasurementMode mode, LocalCheckpointHistory.Limits limits) throws IOException {
        return run(scratchParent, corpus, config, mode, limits, WorkloadMeasurements.systemProbe());
    }

    static Result run(Path scratchParent, WorkloadCorpus corpus, Config config, MeasurementMode mode,
                      LocalCheckpointHistory.Limits limits, Probe probe) throws IOException {
        Objects.requireNonNull(scratchParent, "scratchParent");
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(mode, "mode");
        if (mode == MeasurementMode.UNINSTRUMENTED) { LocalIoObservation.requireNoCurrentCapture(); }
        long opens = 16L * (config.warmups() + config.repetitions());
        long commits = corpus.spec().commits();
        if (opens * (2L * corpus.actions().size() + commits + 16) > MAX_VALIDATION_ACTIONS
                || opens * (commits + 1) * (commits + 1) > MAX_RECOVERY_WORK) {
            throw new IllegalArgumentException("Aggregate recovery/validation work exceeds comparison admission");
        }
        var prepared = WorkloadOracle.prepare(corpus);
        var inputs = inputs(corpus);
        long manifests = 0, sourceSegments = 0;
        for (var manifest : List.of(inputs.genesis(), inputs.checkpoint(), inputs.compacted().replacementManifest())) {
            long bytes = ManifestCodec.encode(manifest).length;
            manifests += bytes;
            for (var segment : manifest.segments()) { bytes = Math.addExact(bytes, segment.byteLength()); }
            if (bytes > limits.maxObjectBytes() || manifest.journalPrefix().byteLength() > limits.maxJournalBytes()
                    || 2 * manifest.journalPrefix().sequence() > limits.maxRecordWork()) {
                throw new IllegalArgumentException("Checkpoint limits cannot admit every selected comparison shape");
            }
        }
        for (var segment : inputs.checkpoint().segments()) { sourceSegments += segment.byteLength(); }
        // Sum initial logical images for all four shapes, conservatively counting reused compacted
        // images twice. Four opens per shape/iteration cover two strategies and two access modes.
        long imageBytes = 4 * corpus.encodedHistoryBytes() + manifests
                + ManifestCodec.encode(inputs.checkpoint()).length + 2 * sourceSegments + inputs.compacted().outputBytes();
        if (imageBytes * 4 * (config.warmups() + config.repetitions()) > MAX_FIXTURE_BYTES) {
            throw new IllegalArgumentException("Aggregate initial fixture-image bytes exceed comparison admission");
        }
        var attributes = Files.readAttributes(scratchParent, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) { throw new IOException("Scratch parent must be an existing real directory"); }
        Path root = Files.createTempDirectory(scratchParent.toRealPath(), "mdb-recovery-");
        try {
            var runtime = new LocalWorkloadRunner.RuntimeInfo(System.getProperty("java.version"),
                    System.getProperty("java.runtime.version"), System.getProperty("java.vendor"),
                    System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"),
                    Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory(), Files.getFileStore(root).type());
            var observations = new ArrayList<Observation>();
            for (int iteration = 0; iteration < config.warmups() + config.repetitions(); iteration++) {
                boolean warmup = iteration < config.warmups();
                int orderIndex = warmup ? iteration : iteration - config.warmups();
                var strategies = orderIndex % 2 == 0 ? List.of(Strategy.ORDINARY, Strategy.HISTORY_REUSE)
                        : List.of(Strategy.HISTORY_REUSE, Strategy.ORDINARY);
                for (var shape : Shape.values()) {
                    for (var access : Access.values()) {
                        Fixture prior = null;
                        for (int ordinal = 0; ordinal < strategies.size(); ordinal++) {
                            var strategy = strategies.get(ordinal);
                            Path home = Files.createDirectory(root.resolve(iteration + "-" + shape + "-" + access + "-" + strategy));
                            var fixture = setup(home, shape, inputs);
                            if (prior != null) { require(prior.equals(fixture), "Strategies started from different file images"); }
                            prior = fixture;
                            observations.add(observe(home, iteration, warmup, ordinal, shape, access, strategy,
                                    fixture, corpus, inputs, prepared, limits, mode, probe));
                        }
                    }
                }
            }
            return new Result(VERSION, mode.identifier(), WorkloadCorpus.VERSION, corpus.spec(), config, limits, corpus.fingerprint(),
                    corpus.historyFingerprint(), corpus.encodedHistoryBytes(), prepared.summary(), runtime, root,
                    observations, List.of(
                    "Synchronous core has no process supervision, source binding or serialized-evidence admission",
                    "Raw observations keep warmup, shape, access, strategy and order separate; no pooled timing summary",
                    "Measured AB/BA counts are equal only for even measured repetitions; warmups do not shift their order",
                    "Fresh files do not establish cold cache; setup, JVM compilation and filesystem cache are uncontrolled",
                    "CPU/allocation describe the executing platform thread, not process, GC or other-thread work",
                    "Heap before/after samples include uncollected garbage; they are not peak, retained heap or RSS",
                    mode == MeasurementMode.INSTRUMENTED
                        ? "Native counters bracket recorder/probe work as well as each call; service clocks cover the call only"
                        : "Native counters are NOT_CAPTURED, not zero; call clocks include a bounded caller-capture guard",
                    mode == MeasurementMode.INSTRUMENTED
                        ? "Operation timings include native capture-hook overhead; compare separately with uninstrumented controls"
                        : "This mode activates no caller-thread capture; existing inactive hooks, recorder and JVM probes remain",
                    "Fixture creation/forcing, oracle checks, raw hashing, validation and teardown are outside operation counters/clocks",
                    "Compaction is setup for the compacted-tail shape; this core does not time compaction or reclamation",
                    "Reader-admission work differs intentionally; writable journal/name startup and forced commits remain unchanged",
                    "Checkpoint limits exclude journal open/replay/tail/engine heap; source-supervised comparison and adoption remain separate"));
        } catch (IOException | RuntimeException failure) {
            throw new RunFailedException(root, failure);
        }
    }

    private static Observation observe(Path home, int iteration, boolean warmup, int ordinal, Shape shape,
            Access access, Strategy strategy, Fixture fixture, WorkloadCorpus corpus, Inputs inputs,
            WorkloadOracle.Prepared prepared, LocalCheckpointHistory.Limits limits, MeasurementMode mode, Probe probe) throws IOException {
        var operations = new ArrayList<OperationSample>();
        var opened = measured(Operation.OPEN, shape == Shape.JOURNAL_ONLY ? Kind.JOURNAL_REOPEN : Kind.CHECKPOINT_REOPEN,
                mode, probe, () -> open(home, access, strategy, limits));
        long validationNanos, closeStart;
        try (var database = opened.value()) {
            operations.add(opened.sample());
            long start = probe.nanoTime();
            verify(database, prepared, corpus, fixture);
            try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
                require(view.query(new TemporalQuery.Get(inputs.continuationEntity(), 0))
                        .equals(new TemporalResult.Point(OptionalLong.empty())), "Fresh continuation entity was already present");
            }
            validationNanos = elapsed(start, probe.nanoTime());
            if (access == Access.WRITABLE) {
                var retry = measured(Operation.ORIGINAL_RETRY, Kind.RETRY, mode, probe,
                        () -> database.transact(inputs.records().getFirst().transaction()));
                operations.add(retry.sample());
                start = probe.nanoTime();
                require(retry.value().equals(new CommitResult(position(1), true)), "Recovered original retry differs");
                validationNanos = Math.addExact(validationNanos, elapsed(start, probe.nanoTime()));
                var commit = measured(Operation.CONTINUATION, Kind.COMMIT, mode, probe,
                        () -> database.transact(inputs.continuation()));
                operations.add(commit.sample());
                start = probe.nanoTime();
                var next = position(corpus.spec().commits() + 1L);
                require(commit.value().equals(new CommitResult(next, false)), "Forced continuation position differs");
                verifyContinuation(database, next, inputs);
                validationNanos = Math.addExact(validationNanos, elapsed(start, probe.nanoTime()));
                var repeated = measured(Operation.CONTINUATION_RETRY, Kind.RETRY, mode, probe,
                        () -> database.transact(inputs.continuation()));
                operations.add(repeated.sample());
                start = probe.nanoTime();
                require(repeated.value().equals(new CommitResult(next, true)), "Continuation retry identity differs");
                verifyContinuation(database, next, inputs);
                verifyHistory(database, prepared);
                validationNanos = Math.addExact(validationNanos, elapsed(start, probe.nanoTime()));
            }
            closeStart = probe.nanoTime();
        }
        long teardownNanos = elapsed(closeStart, probe.nanoTime());
        long expectedBytes = access == Access.WRITABLE ? inputs.continuedBytes() : corpus.encodedHistoryBytes();
        String expectedHash = access == Access.WRITABLE ? inputs.continuedSha256() : corpus.historyFingerprint();
        Path journal = home.resolve("transactions.journal");
        long hashStart = probe.nanoTime();
        require(Files.size(journal) == expectedBytes && hash(readBounded(journal, expectedBytes)).equals(expectedHash), "Closed journal image differs");
        validationNanos = Math.addExact(validationNanos, elapsed(hashStart, probe.nanoTime()));
        return new Observation(iteration, warmup, ordinal, shape, access, strategy, home, fixture, operations,
                validationNanos, teardownNanos, expectedBytes, expectedHash);
    }

    private static LocalDatabase open(Path home, Access access, Strategy strategy, LocalCheckpointHistory.Limits limits) throws IOException {
        return switch (strategy) {
            case ORDINARY -> access == Access.WRITABLE ? LocalDatabase.openWritable(home) : LocalDatabase.open(home);
            case HISTORY_REUSE -> access == Access.WRITABLE ? LocalDatabase.openWritableWithCheckpointHistory(home, limits)
                    : LocalDatabase.openWithCheckpointHistory(home, limits);
        };
    }

    private static <T> Measured<T> measured(Operation operation, Kind kind, MeasurementMode mode, Probe probe,
                                           WorkloadMeasurements.Call<T> call) throws IOException {
        var recorder = new WorkloadMeasurements.Recorder(probe, 1);
        T value = null;
        try {
            if (mode == MeasurementMode.UNINSTRUMENTED) {
                LocalIoObservation.requireNoCurrentCapture();
                value = recorder.measure(Phase.MAINTENANCE, kind, () -> {
                    // Also reject a capture activated by the injectable pre-call probe.
                    // This bounded read-only check is included in the call clock.
                    LocalIoObservation.requireNoCurrentCapture();
                    return call.invoke();
                });
                LocalIoObservation.requireNoCurrentCapture();
                return new Measured<>(value, new OperationSample(operation, recorder.samples().getFirst(), null));
            }
            var capture = LocalIoObservation.open();
            try (capture) { value = recorder.measure(Phase.MAINTENANCE, kind, call); }
            var counters = capture.snapshot();
            require(counters.complete(), "Recovery comparison native counters are incomplete");
            return new Measured<>(value, new OperationSample(operation, recorder.samples().getFirst(), counters));
        } catch (IOException | RuntimeException | Error failure) {
            if (value instanceof AutoCloseable owner) {
                try { owner.close(); }
                catch (Throwable cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
            }
            throw failure;
        }
    }

    private static void verify(LocalDatabase database, WorkloadOracle.Prepared prepared,
                               WorkloadCorpus corpus, Fixture fixture) throws IOException {
        require(database.head().equals(position(corpus.spec().commits())), "Recovered head differs");
        var selected = database.checkpointPosition();
        require(selected.isPresent() == fixture.checkpointSequence().isPresent()
                && (selected.isEmpty() || selected.orElseThrow().equals(position(fixture.checkpointSequence().orElseThrow()))),
                "Recovered checkpoint differs");
        for (long cutoff = 0; cutoff <= corpus.spec().commits(); cutoff++) {
            try (var view = database.openView(new ReadCutoff.At(position(cutoff)))) {
                require(view.position().equals(position(cutoff)), "Historical cutoff differs");
            }
        }
        verifyHistory(database, prepared);
    }

    private static void verifyHistory(LocalDatabase database, WorkloadOracle.Prepared prepared) throws IOException {
        for (var expected : prepared.outcomes()) {
            switch (expected) {
                case WorkloadOracle.ExpectedSubmit submit -> require(database.lookupRequest(submit.action().request().requestId())
                        .orElseThrow().equals(submit.result().position()), "Original request position differs");
                case WorkloadOracle.ExpectedRead read -> {
                    try (var view = database.openView(new ReadCutoff.At(position(read.action().cutoff())))) {
                        require(view.query(read.action().query()).equals(read.result()), "Independent historical answer differs");
                    }
                }
            }
        }
    }

    private static void verifyContinuation(LocalDatabase database, CommitPosition next, Inputs inputs) throws IOException {
        require(database.head().equals(next)
                && database.lookupRequest(inputs.continuation().requestId()).orElseThrow().equals(next),
                "Continuation publication or request position differs");
        try (var view = database.openView(new ReadCutoff.At(next))) {
            require(view.query(new TemporalQuery.Get(inputs.continuationEntity(), 0))
                    .equals(new TemporalResult.Point(OptionalLong.of(17))), "New-head continuation value differs");
        }
        try (var view = database.openView(new ReadCutoff.At(position(next.sequence() - 1)))) {
            require(view.query(new TemporalQuery.Get(inputs.continuationEntity(), 0))
                    .equals(new TemporalResult.Point(OptionalLong.empty())), "Continuation changed the previous cutoff");
        }
    }

    private static Fixture setup(Path home, Shape shape, Inputs inputs) throws IOException {
        // Probe through the production typed capability boundary before any raw directory force.
        try (var probe = LocalCheckpointStore.open(home)) { probe.journalPath(); }
        // The caller owns a durably named scratch parent. Synchronize each introduced child name.
        for (Path directory : List.of(home, home.getParent(), home.getParent().getParent())) {
            try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        }
        CheckpointManifest selected = null;
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.create(store.journalPath(), WorkloadCorpus.DATABASE)) {
            for (var record : inputs.records()) { journal.append(record); }
            store.synchronizeJournal(journal);
            if (shape == Shape.GENESIS) { selected = inputs.genesis(); store.publish(selected, journal); }
            if (shape == Shape.CHECKPOINT_TAIL || shape == Shape.COMPACTED_CHECKPOINT_TAIL) {
                for (var segment : inputs.segments()) { store.writeSegment(segment); }
                selected = inputs.checkpoint();
                store.publish(selected, journal);
                if (shape == Shape.COMPACTED_CHECKPOINT_TAIL) {
                    store.publishCompaction(inputs.compacted(), journal);
                    selected = inputs.compacted().replacementManifest();
                }
            }
        }
        var files = new TreeMap<String, String>();
        try (var entries = Files.list(home)) {
            var paths = entries.limit(513).toList();
            require(paths.size() <= 512, "Initial fixture entry admission exceeded");
            for (var path : paths) {
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Unexpected initial fixture entry");
                files.put(path.getFileName().toString(), hash(readBounded(path, SegmentCodec.MAX_SEGMENT_BYTES)));
            }
        }
        long segmentBytes = 0;
        if (selected != null) { for (var segment : selected.segments()) { segmentBytes = Math.addExact(segmentBytes, segment.byteLength()); } }
        return new Fixture(selected == null ? OptionalLong.empty() : OptionalLong.of(selected.journalPrefix().sequence()),
                selected == null ? 0 : selected.journalPrefix().byteLength(), selected == null ? null : selected.journalPrefix().sha256(),
                selected == null ? 0 : ManifestCodec.encode(selected).length, segmentBytes,
                selected == null ? 0 : selected.segments().size(), files);
    }

    private static Inputs inputs(WorkloadCorpus corpus) throws IOException {
        var records = corpus.actions().stream().filter(WorkloadCorpus.Submit.class::isInstance).map(WorkloadCorpus.Submit.class::cast)
                .filter(action -> !action.retry()).map(action -> new JournalRecord(action.sequence(), action.request())).toList();
        int cutoff = records.size() / 2;
        byte[] header = JournalCodec.encodeHeader(WorkloadCorpus.DATABASE);
        var prefixDigest = digest(); prefixDigest.update(header);
        long prefixBytes = header.length;
        for (var record : records.subList(0, cutoff)) {
            byte[] frame = JournalCodec.encodeRecord(record); prefixDigest.update(frame); prefixBytes += frame.length;
        }
        var references = new ArrayList<CheckpointManifest.SegmentReference>();
        var segments = new ArrayList<ImmutableSegment>();
        var images = new LinkedHashMap<SegmentId, byte[]>();
        int chunk = Math.min(16, Math.max(1, cutoff / 2));
        for (int first = 0; first < cutoff; first += chunk) {
            int last = Math.min(first + chunk, cutoff);
            var segment = new ImmutableSegment(WorkloadCorpus.DATABASE, records.subList(first, last));
            byte[] image = SegmentCodec.encode(segment);
            var id = new SegmentId(hash(image));
            segments.add(segment); images.put(id, image);
            references.add(new CheckpointManifest.SegmentReference(id, image.length, first + 1L, last));
        }
        var genesis = new CheckpointManifest(WorkloadCorpus.DATABASE, 1,
                new CheckpointManifest.JournalPrefix(0, header.length, hash(header)), List.of());
        var checkpoint = new CheckpointManifest(WorkloadCorpus.DATABASE, 1,
                new CheckpointManifest.JournalPrefix(cutoff, prefixBytes, HexFormat.of().formatHex(prefixDigest.digest())), references);
        var compacted = HistoryCompactor.compact(checkpoint, images::get, HistoryCompactor.Limits.DEFAULT);
        int suffix = 0;
        RequestId identity;
        do { identity = new RequestId("recovery-continuation-" + suffix++); }
        while (containsRequest(records, identity));
        suffix = 0;
        EntityId entity;
        do { entity = new EntityId("recovery-continuation-entity-" + suffix++); }
        while (containsEntity(records, entity));
        var continuation = new Transaction(identity, List.of(new WriteOperation.Put(entity, new ValidTimeInterval(null, null), 17)));
        var continuedDigest = digest(); continuedDigest.update(header);
        long continuedBytes = header.length;
        for (var record : records) { byte[] frame = JournalCodec.encodeRecord(record); continuedDigest.update(frame); continuedBytes += frame.length; }
        byte[] extra = JournalCodec.encodeRecord(new JournalRecord(records.size() + 1L, continuation));
        continuedDigest.update(extra); continuedBytes += extra.length;
        return new Inputs(records, List.copyOf(segments), genesis, checkpoint, compacted, continuation, entity,
                continuedBytes, HexFormat.of().formatHex(continuedDigest.digest()));
    }
    private static boolean containsRequest(List<JournalRecord> records, RequestId identity) {
        return records.stream().anyMatch(record -> record.transaction().requestId().equals(identity));
    }
    private static boolean containsEntity(List<JournalRecord> records, EntityId entity) {
        return records.stream().flatMap(record -> record.transaction().operations().stream()).anyMatch(operation -> operation.entity().equals(entity));
    }
    private static CommitPosition position(long sequence) { return new CommitPosition(WorkloadCorpus.DATABASE, sequence); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hash(byte[] image) { return HexFormat.of().formatHex(digest().digest(image)); }
    private static byte[] readBounded(Path path, long limit) throws IOException {
        if (limit < 0 || limit >= Integer.MAX_VALUE) { throw new IOException("Invalid raw fixture-image bound"); }
        try (var input = Files.newInputStream(path)) {
            byte[] image = input.readNBytes((int) limit + 1);
            require(image.length <= limit, "Raw fixture image exceeds admitted length");
            return image;
        }
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) { throw new IOException(message); } }
    private static long elapsed(long start, long end) {
        long value = end - start;
        if (value < 0) { throw new IllegalStateException("Nonmonotonic or excessive comparison timing"); }
        return value;
    }
}
