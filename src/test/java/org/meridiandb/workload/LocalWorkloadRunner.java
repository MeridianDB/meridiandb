// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.HistoryCompactor;
import org.meridiandb.storage.manifest.LocalCheckpointReader;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.workload.WorkloadMeasurements.Kind;
import org.meridiandb.workload.WorkloadMeasurements.Phase;
import org.meridiandb.workload.WorkloadMeasurements.Probe;
import org.meridiandb.workload.WorkloadMeasurements.Recorder;

/** Test-only synchronous operation measurements. This is not a supervised benchmark CLI. */
public final class LocalWorkloadRunner {
    public static final int VERSION = 1;
    public static final int MAX_ITERATION_ACTIONS = 20_000;

    public record Config(int warmups, int repetitions) {
        public static final Config DEFAULT = new Config(1, 3);
        public Config {
            if (warmups < 0 || warmups > 2 || repetitions < 1 || repetitions > 5) {
                throw new IllegalArgumentException("Require 0..2 warmups and 1..5 measured repetitions");
            }
        }
    }

    public record RuntimeInfo(String javaVersion, String javaRuntimeVersion, String javaVendor,
                              String osName, String osVersion, String architecture,
                              int availableProcessors, long maxHeapBytes, String fileStoreType) { }

    /** Named logical lengths include retained old generations and objects, not allocated blocks. */
    public record NamedStorage(Map<String, Long> fileLengths, long totalBytes) {
        public NamedStorage {
            fileLengths = java.util.Collections.unmodifiableMap(new TreeMap<>(fileLengths));
            if (totalBytes < 0 || fileLengths.values().stream().anyMatch(v -> v < 0)
                    || fileLengths.values().stream().mapToLong(Long::longValue).reduce(0, Math::addExact) != totalBytes) {
                throw new IllegalArgumentException("Inconsistent named storage lengths");
            }
        }
    }

    public record Iteration(int index, boolean warmup, Path directory,
                            long namespacePreparationNanos,
                            long actionLoopNanos, long actionServiceNanos, long actionValidationNanos,
                            long actionOtherNanos, Double actionLoopOperationsPerSecond,
                            long totalValidationNanos, long validationTasks,
                            int sourceSegments, int replacementSegments, int pinnedReadersDuringPublication,
                            NamedStorage namedStorage, List<WorkloadMeasurements.Sample> samples,
                            List<WorkloadMeasurements.Summary> summaries) {
        public Iteration { samples = List.copyOf(samples); summaries = List.copyOf(summaries); }
    }

    /** Returned only after every operation, independent answer and recovery/history check succeeds. */
    public record Result(int schemaVersion, int corpusVersion, WorkloadCorpus.Spec spec, Config config,
                         String fingerprint, String historyFingerprint, long encodedHistoryBytes,
                         WorkloadOracle.Summary workload, RuntimeInfo runtime, Path runRoot,
                         long preparationNanos, List<Iteration> iterations, List<String> gaps) {
        public Result { iterations = List.copyOf(iterations); gaps = List.copyOf(gaps); }
    }

    public static final class RunFailedException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String runRoot;
        RunFailedException(Path runRoot, Exception cause) {
            super("Local workload failed; retained run directory: " + runRoot, cause);
            this.runRoot = runRoot.toString();
        }
        public Path runRoot() { return Path.of(runRoot); }
    }

    private record Inputs(List<JournalRecord> records, List<ImmutableSegment> segments,
                          List<CheckpointManifest.SegmentReference> references, Map<SegmentId, byte[]> images) { }

    /** Supervisor telemetry brackets the complete instrumented iteration, outside every operation timer. */
    interface IterationObserver {
        void before(int index, boolean warmup, Path directory) throws IOException;
        void after(int index, boolean warmup, Path directory) throws IOException;
    }

    private static final IterationObserver NO_OBSERVER = new IterationObserver() {
        @Override public void before(int index, boolean warmup, Path directory) { }
        @Override public void after(int index, boolean warmup, Path directory) { }
    };

    private LocalWorkloadRunner() { }

    public static Result run(Path scratchParent, WorkloadCorpus corpus, Config config) throws IOException {
        return run(scratchParent, corpus, config, WorkloadMeasurements.systemProbe());
    }

    static Result run(Path scratchParent, WorkloadCorpus corpus, Config config, Probe probe) throws IOException {
        return run(scratchParent, corpus, config, probe, NO_OBSERVER);
    }

    static Result run(Path scratchParent, WorkloadCorpus corpus, Config config, Probe probe,
                      IterationObserver observer) throws IOException {
        Objects.requireNonNull(scratchParent, "scratchParent");
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(observer, "observer");
        if ((long) corpus.actions().size() * (config.warmups() + config.repetitions()) > MAX_ITERATION_ACTIONS) {
            throw new IllegalArgumentException("Aggregate iteration action admission exceeded");
        }
        var attributes = Files.readAttributes(scratchParent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) { throw new IOException("Scratch parent must be an existing real directory"); }
        long start = probe.nanoTime();
        var prepared = WorkloadOracle.prepare(corpus);
        var inputs = inputs(corpus);
        long preparationNanos = elapsed(start, probe.nanoTime());
        // All oracle/segment preparation and optional telemetry initialization precede storage execution.
        Path root = Files.createTempDirectory(scratchParent.toRealPath(), "mdb-workload-");
        try {
            var runtime = new RuntimeInfo(System.getProperty("java.version"), System.getProperty("java.runtime.version"),
                    System.getProperty("java.vendor"), System.getProperty("os.name"), System.getProperty("os.version"),
                    System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory(),
                    Files.getFileStore(root).type());
            var results = new ArrayList<Iteration>();
            for (int index = 0; index < config.warmups() + config.repetitions(); index++) {
                Path home = Files.createDirectory(root.resolve("iteration-" + index));
                boolean warmup = index < config.warmups();
                observer.before(index, warmup, home);
                var result = iteration(home, index, warmup, corpus, prepared, inputs, probe);
                observer.after(index, warmup, home);
                results.add(result);
            }
            return new Result(VERSION, WorkloadCorpus.VERSION, corpus.spec(), config, corpus.fingerprint(),
                    corpus.historyFingerprint(), corpus.encodedHistoryBytes(), prepared.summary(), runtime, root,
                    preparationNanos, results, List.of(
                    "Standalone core has no process supervisor or serialized-evidence admission",
                    "Standalone core does not bind source revision or complete Linux deployment provenance",
                    "OS cache is uncontrolled; warmups exercise the JVM on separate fresh stores, not cold storage",
                    "CREATE excludes namespace/control-file preflight and directory-name synchronization",
                    "CPU/allocation cover the executing platform thread and counter instrumentation, not process/GC totals",
                    "Heap is sampled used heap including uncollected garbage, not peak, retained heap or RSS",
                    "Named file lengths and encoded journal bytes are not allocated blocks or physical device I/O",
                    "Arrival latency, asynchronous lag and merge backlog are not measured by this synchronous runner"));
        } catch (Exception failure) {
            throw new RunFailedException(root, failure);
        }
    }

    private static Iteration iteration(Path home, int index, boolean warmup, WorkloadCorpus corpus,
                                       WorkloadOracle.Prepared prepared, Inputs inputs, Probe probe) throws IOException {
        var metrics = new Recorder(probe, corpus.actions().size() + inputs.segments().size() + 8);
        var checks = new Checks(probe);
        long namespaceStart = probe.nanoTime();
        // Bootstrap capability/control ownership before timing. Only the allowed lock file is created.
        try (var preflight = LocalCheckpointStore.open(home)) { preflight.journalPath(); }
        // The caller durably named the scratch parent; persist each newly introduced child name.
        for (Path directory : List.of(home, home.getParent(), home.getParent().getParent())) {
            try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        }
        long namespacePreparationNanos = elapsed(namespaceStart, probe.nanoTime());
        long loopNanos, loopService, loopValidation;
        try (var database = metrics.measure(Phase.LOAD, Kind.CREATE, () -> LocalDatabase.create(home, WorkloadCorpus.DATABASE))) {
            long beforeService = metrics.serviceNanos(), beforeValidation = checks.nanos;
            long loopStart = probe.nanoTime();
            long committed = 0;
            for (var outcome : prepared.outcomes()) {
                switch (outcome) {
                    case WorkloadOracle.ExpectedSubmit submit -> {
                        Phase phase = submit.action().stage() == WorkloadCorpus.Stage.SEED ? Phase.LOAD : Phase.MIXED;
                        Kind kind = submit.action().retry() ? Kind.RETRY : Kind.COMMIT;
                        var actual = metrics.measure(phase, kind, () -> database.transact(submit.action().request()));
                        checks.check(() -> require(actual.equals(submit.result()), "Commit/retry answer differs"));
                        if (!actual.replayed()) { committed++; }
                    }
                    case WorkloadOracle.ExpectedRead read -> {
                        Phase phase = committed < corpus.spec().entities() ? Phase.LOAD : Phase.MIXED;
                        var answer = metrics.measure(phase, kind(read.action().query()),
                                () -> query(database, read.action()));
                        checks.check(() -> require(answer.equals(read.result()), "Pinned query answer differs"));
                    }
                }
            }
            loopNanos = elapsed(loopStart, probe.nanoTime());
            loopService = metrics.serviceNanos() - beforeService;
            loopValidation = checks.nanos - beforeValidation;
            checks.check(() -> require(database.head().sequence() == corpus.spec().commits(), "Final head differs"));
        }
        try (var reopened = metrics.measure(Phase.MAINTENANCE, Kind.JOURNAL_REOPEN, () -> LocalDatabase.openWritable(home))) {
            checks.check(() -> verifyDatabase(reopened, prepared, corpus, false));
        }
        int replacements = checkpoint(home, corpus, inputs, metrics, checks);
        try (var reopened = metrics.measure(Phase.MAINTENANCE, Kind.CHECKPOINT_REOPEN, () -> LocalDatabase.openWritable(home))) {
            checks.check(() -> verifyDatabase(reopened, prepared, corpus, true));
        }
        // Raw journal access occurs only after every native journal/store/database owner closes.
        checks.check(() -> verifyJournal(home.resolve("transactions.journal"), corpus));
        var storage = namedStorage(home);
        long other = loopNanos - Math.addExact(loopService, loopValidation);
        require(other >= 0, "Loop wall/service/validation timing is incoherent");
        Double rate = loopNanos == 0 ? null : corpus.actions().size() * 1_000_000_000.0 / loopNanos;
        return new Iteration(index, warmup, home, namespacePreparationNanos, loopNanos, loopService, loopValidation, other, rate,
                checks.nanos, checks.tasks, inputs.segments().size(), replacements, 1,
                storage, metrics.samples(), metrics.summaries());
    }

    private static int checkpoint(Path home, WorkloadCorpus corpus, Inputs inputs, Recorder metrics, Checks checks) throws IOException {
        try (var store = LocalCheckpointStore.open(home); var journal = LocalJournal.open(store.journalPath())) {
            var prefix = journal.prefix(corpus.spec().commits());
            checks.check(() -> require(prefix.byteLength() == corpus.encodedHistoryBytes()
                    && prefix.sha256().equals(corpus.historyFingerprint()), "Actual journal prefix differs"));
            for (int i = 0; i < inputs.segments().size(); i++) {
                var segment = inputs.segments().get(i);
                var expected = inputs.references().get(i).id();
                var actual = metrics.measure(Phase.MAINTENANCE, Kind.SEGMENT_WRITE, () -> store.writeSegment(segment));
                checks.check(() -> require(actual.equals(expected), "Installed segment identity differs"));
            }
            var manifest = new CheckpointManifest(WorkloadCorpus.DATABASE, 2,
                    new CheckpointManifest.JournalPrefix(corpus.spec().commits(), prefix.byteLength(), prefix.sha256()), inputs.references());
            var sourceId = metrics.measure(Phase.MAINTENANCE, Kind.CHECKPOINT_PUBLISH, () -> store.publish(manifest, journal));
            try (var pinned = store.readLatest(journal).orElseThrow()) {
                checks.check(() -> require(pinned.manifestId().equals(sourceId) && pinned.manifest().equals(manifest), "Source selection differs"));
                var plan = metrics.measure(Phase.MAINTENANCE, Kind.COMPACTION_PLAN,
                        () -> HistoryCompactor.compact(manifest, inputs.images()::get, HistoryCompactor.Limits.DEFAULT));
                checks.check(() -> require(plan.segments().size() < inputs.segments().size()
                        && plan.replacementManifest().journalPrefix().equals(manifest.journalPrefix()), "Compaction did not preserve/combine history"));
                var replacementId = metrics.measure(Phase.MAINTENANCE, Kind.COMPACTION_PUBLISH,
                        () -> store.publishCompaction(plan, journal));
                try (var selected = store.readLatest(journal).orElseThrow()) {
                    checks.check(() -> {
                        require(selected.manifestId().equals(replacementId) && selected.manifest().equals(plan.replacementManifest()), "Replacement selection differs");
                        require(selected.manifest().generation() == 3 && selected.manifest().journalPrefix().equals(manifest.journalPrefix()), "Replacement cutoff/generation differs");
                        require(records(pinned).equals(inputs.records()), "Pinned old history differs");
                        require(records(selected).equals(inputs.records()), "Replacement history differs");
                    });
                }
                return plan.segments().size();
            }
        }
    }

    private static Inputs inputs(WorkloadCorpus corpus) {
        var records = corpus.actions().stream().filter(WorkloadCorpus.Submit.class::isInstance)
                .map(WorkloadCorpus.Submit.class::cast).filter(s -> !s.retry())
                .map(s -> new JournalRecord(s.sequence(), s.request())).toList();
        int chunk = Math.min(16, (records.size() + 1) / 2);
        var segments = new ArrayList<ImmutableSegment>();
        var references = new ArrayList<CheckpointManifest.SegmentReference>();
        var images = new HashMap<SegmentId, byte[]>();
        for (int i = 0; i < records.size(); i += chunk) {
            var segment = new ImmutableSegment(WorkloadCorpus.DATABASE, records.subList(i, Math.min(i + chunk, records.size())));
            byte[] image = SegmentCodec.encode(segment);
            var id = SegmentCodec.decode(image).id();
            segments.add(segment); images.put(id, image);
            references.add(new CheckpointManifest.SegmentReference(id, image.length, segment.firstSequence(), segment.lastSequence()));
        }
        return new Inputs(records, List.copyOf(segments), List.copyOf(references), Map.copyOf(images));
    }

    private static List<JournalRecord> records(LocalCheckpointReader checkpoint) throws IOException {
        var records = new ArrayList<JournalRecord>();
        for (int i = 0; i < checkpoint.manifest().segments().size(); i++) {
            try (var segment = checkpoint.openSegment(i)) { records.addAll(segment.segment().records()); }
        }
        return records;
    }

    private static void verifyDatabase(LocalDatabase database, WorkloadOracle.Prepared prepared,
                                       WorkloadCorpus corpus, boolean checkpoint) throws IOException {
        require(database.head().equals(new CommitPosition(WorkloadCorpus.DATABASE, corpus.spec().commits())), "Recovered head differs");
        require(database.checkpointPosition().orElseThrow().sequence() == (checkpoint ? corpus.spec().commits() : 0), "Recovery source differs");
        for (var outcome : prepared.outcomes()) {
            switch (outcome) {
                case WorkloadOracle.ExpectedRead read -> require(query(database, read.action()).equals(read.result()), "Recovered pinned query differs");
                case WorkloadOracle.ExpectedSubmit submit -> require(database.lookupRequest(submit.action().request().requestId())
                        .orElseThrow().equals(submit.result().position()), "Recovered original request position differs");
            }
        }
    }

    private static TemporalResult query(LocalDatabase database, WorkloadCorpus.Read read) {
        try (var view = database.openView(new ReadCutoff.At(new CommitPosition(WorkloadCorpus.DATABASE, read.cutoff())))) {
            return view.query(read.query());
        }
    }

    private static Kind kind(TemporalQuery query) {
        return switch (query) {
            case TemporalQuery.Get ignored -> Kind.GET;
            case TemporalQuery.Scan ignored -> Kind.SCAN;
            case TemporalQuery.Select ignored -> Kind.SELECT;
            case TemporalQuery.FilterScan ignored -> Kind.FILTER_SCAN;
            case TemporalQuery.Sum ignored -> Kind.SUM;
        };
    }

    private static NamedStorage namedStorage(Path home) throws IOException {
        var lengths = new TreeMap<String, Long>();
        try (var entries = Files.list(home)) {
            var paths = entries.limit(1025).toList();
            require(paths.size() <= 1024, "Named file inventory limit exceeded");
            for (var path : paths) {
                var a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                require(a.isRegularFile(), "Unexpected nonregular file in owned workload namespace");
                lengths.put(path.getFileName().toString(), a.size());
            }
        }
        return new NamedStorage(lengths, lengths.values().stream().mapToLong(Long::longValue).reduce(0, Math::addExact));
    }

    private static void verifyJournal(Path journal, WorkloadCorpus corpus) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (var input = Files.newInputStream(journal)) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    require(count <= WorkloadCorpus.MAX_ENCODED_BYTES, "Journal exceeds workload admission");
                    digest.update(buffer, 0, read);
                }
            }
            require(count == corpus.encodedHistoryBytes() && HexFormat.of().formatHex(digest.digest()).equals(corpus.historyFingerprint()), "Closed journal identity differs");
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) { throw new IOException(message); }
    }

    private static long elapsed(long start, long end) {
        long elapsed = end - start;
        if (elapsed < 0) { throw new IllegalStateException("Nonmonotonic or excessive elapsed time"); }
        return elapsed;
    }

    @FunctionalInterface private interface Check { void run() throws IOException; }

    private static final class Checks {
        final Probe probe;
        long nanos, tasks;
        Checks(Probe probe) { this.probe = probe; }
        void check(Check check) throws IOException {
            long start = probe.nanoTime(); check.run();
            nanos = Math.addExact(nanos, elapsed(start, probe.nanoTime())); tasks++;
        }
    }
}
