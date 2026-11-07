// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.workload.LocalWorkloadIo.Category;

/** Explicit test-only instrumented execution, separate from observational latency datasets. */
public final class LocalAttributedWorkload {
    public static final int VERSION = 1;
    public static final int MAX_SCOPES = 2048;
    public static final String MODE = "CALLER_THREAD_LOCAL_IO_INSTRUMENTED";

    /** Logical original-history lengths, computed before native execution, never from I/O counters. */
    public record Denominators(long headerBytes, long seedFrameBytes, long mixedFrameBytes,
                               long encodedHistoryBytes, int newCommits, int exactRetries, int queries) {
        public Denominators {
            if (headerBytes < 1 || seedFrameBytes < 0 || mixedFrameBytes < 0 || newCommits < 1
                    || exactRetries < 0 || queries < 0
                    || Math.addExact(headerBytes, Math.addExact(seedFrameBytes, mixedFrameBytes)) != encodedHistoryBytes) {
                throw new IllegalArgumentException("Inconsistent original-history denominators");
            }
        }
        public long newFrameBytes() { return Math.addExact(seedFrameBytes, mixedFrameBytes); }
    }

    /** Timing samples are from this instrumented run; they are not original baseline observations. */
    public record Result(int schemaVersion, String mode, LocalWorkloadRunner.Result workload,
                         List<LocalWorkloadIo.Entry> scopes, Denominators denominators, List<String> gaps) {
        public Result {
            Objects.requireNonNull(workload, "workload");
            Objects.requireNonNull(denominators, "denominators");
            scopes = List.copyOf(scopes);
            gaps = List.copyOf(gaps);
            if (schemaVersion != VERSION || !MODE.equals(mode) || scopes.size() > MAX_SCOPES
                    || scopes.stream().anyMatch(entry -> !entry.accepted())
                    || denominators.encodedHistoryBytes() != workload.encodedHistoryBytes()
                    || denominators.headerBytes() != JournalCodec.encodeHeader(WorkloadCorpus.DATABASE).length
                    || denominators.newCommits() != workload.spec().commits()
                    || denominators.newCommits() != workload.workload().commits()
                    || denominators.exactRetries() != workload.workload().retries()
                    || denominators.queries() != workload.spec().queries()
                    || denominators.queries() != workload.workload().reads()) {
                throw new IllegalArgumentException("Incomplete or mismatched attributed result");
            }
            validateScopes(workload, scopes);
        }
    }

    /** Failed scope evidence and any created namespace remain available; no successful result exists. */
    public static final class RunFailedException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String runRoot;
        // Diagnostic scope objects are retained in-process, not via Java exception serialization.
        private final transient List<LocalWorkloadIo.Entry> scopes;
        RunFailedException(Path runRoot, List<LocalWorkloadIo.Entry> scopes, Exception failure) {
            super("Attributed local workload failed; retained root: " + runRoot, failure);
            this.runRoot = runRoot == null ? null : runRoot.toString();
            this.scopes = List.copyOf(scopes);
        }
        public Path runRoot() { return runRoot == null ? null : Path.of(runRoot); }
        public List<LocalWorkloadIo.Entry> scopes() { return scopes == null ? List.of() : scopes; }
    }

    private LocalAttributedWorkload() { }

    public static Result run(Path scratchParent, WorkloadCorpus corpus, LocalWorkloadRunner.Config config) throws IOException {
        int capacity = admittedCapacity(corpus, config);
        return run(scratchParent, corpus, config, new LocalWorkloadIo(capacity));
    }

    static Result run(Path scratchParent, WorkloadCorpus corpus, LocalWorkloadRunner.Config config,
                      LocalWorkloadIo trace) throws IOException {
        Objects.requireNonNull(scratchParent, "scratchParent");
        Objects.requireNonNull(trace, "trace").admit(admittedCapacity(corpus, config));
        var denominators = denominators(corpus);
        LocalWorkloadRunner.Result workload = null;
        try {
            workload = LocalWorkloadRunner.runAttributed(scratchParent, corpus, config, trace);
            return new Result(VERSION, MODE, workload, trace.entries(), denominators, List.of(
                    "Explicit caller-thread local adapter instrumentation; original latency data are unchanged",
                    "Counters cover hooked FileChannel transfers/force, not device traffic, metadata or other threads",
                    "External raw-journal hashing and metadata inventory have excluded coverage, not proven zero I/O",
                    "Preparation and directory creation use no counted metadata-byte estimate",
                    "Serialization/export and supervisor process telemetry execute outside this Java lifecycle",
                    "Source binding, serialized admission and named-ratio recomputation are not supplied by this core",
                    "Evaluated attributed Linux results and integrated baseline acceptance remain separate"));
        } catch (LocalWorkloadRunner.RunFailedException failure) {
            throw new RunFailedException(failure.runRoot(), trace.entries(), failure);
        } catch (IOException | RuntimeException failure) {
            throw new RunFailedException(workload == null ? null : workload.runRoot(), trace.entries(), failure);
        }
    }

    static int admittedCapacity(WorkloadCorpus corpus, LocalWorkloadRunner.Config config) {
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(config, "config");
        int commits = corpus.spec().commits();
        int chunk = Math.min(16, (commits + 1) / 2);
        int segments = (commits + chunk - 1) / chunk;
        long iterations = config.warmups() + config.repetitions();
        // Successful inventory is 2 preparation + (2 actions + 2 segments + 32) per iteration.
        // Reserve a further 32 slots per iteration for bounded failure/teardown evidence.
        long capacity = 2 + (2L * corpus.actions().size() + 2L * segments + 64) * iterations;
        if ((long) corpus.actions().size() * iterations > LocalWorkloadRunner.MAX_ITERATION_ACTIONS
                || capacity > MAX_SCOPES) {
            throw new IllegalArgumentException("Aggregate attributed scope admission exceeded before namespace creation");
        }
        return Math.toIntExact(capacity);
    }

    static Denominators denominators(WorkloadCorpus corpus) {
        long seed = 0, mixed = 0;
        int commits = 0, retries = 0, queries = 0;
        for (var action : corpus.actions()) {
            switch (action) {
                case WorkloadCorpus.Submit submit -> {
                    if (submit.retry()) { retries++; }
                    else {
                        long bytes = JournalCodec.encodedRecordBytes(new JournalRecord(submit.sequence(), submit.request()));
                        if (submit.stage() == WorkloadCorpus.Stage.SEED) { seed = Math.addExact(seed, bytes); }
                        else { mixed = Math.addExact(mixed, bytes); }
                        commits++;
                    }
                }
                case WorkloadCorpus.Read ignored -> queries++;
            }
        }
        if (commits != corpus.spec().commits() || queries != corpus.spec().queries()) {
            throw new IllegalArgumentException("Original action counts differ from specification");
        }
        return new Denominators(JournalCodec.encodeHeader(WorkloadCorpus.DATABASE).length,
                seed, mixed, corpus.encodedHistoryBytes(), commits, retries, queries);
    }

    private static void validateScopes(LocalWorkloadRunner.Result workload, List<LocalWorkloadIo.Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).ordinal() != i) { throw new IllegalArgumentException("Nonsequential scope ordinal"); }
        }
        if (entries.size() < 2 || entries.get(0).iteration() != -1 || entries.get(1).iteration() != -1
                || entries.get(0).category() != Category.PREPARATION || entries.get(1).category() != Category.PREPARATION) {
            throw new IllegalArgumentException("Missing preparation scopes");
        }
        int offset = 2;
        for (var iteration : workload.iterations()) {
            var actual = new ArrayList<LocalWorkloadIo.Entry>();
            while (offset < entries.size() && entries.get(offset).iteration() == iteration.index()) { actual.add(entries.get(offset++)); }
            int operations = iteration.samples().size();
            int actions = operations - iteration.sourceSegments() - 6;
            for (var category : Category.values()) {
                int expected = switch (category) {
                    case PREPARATION -> 0;
                    case NAMESPACE -> 4;
                    case OPERATION -> operations;
                    case OWNER_ACQUISITION, READER_ACQUISITION -> 2;
                    case PREFIX_VERIFICATION, EXTERNAL_HASH, FILE_INVENTORY -> 1;
                    case VALIDATION -> actions + iteration.sourceSegments() + 7;
                    case TEARDOWN -> 8;
                };
                if (actual.stream().filter(entry -> entry.category() == category).count() != expected) {
                    throw new IllegalArgumentException("Missing/excess " + category + " scopes in iteration " + iteration.index());
                }
            }
            var observed = actual.stream().filter(entry -> entry.category() == Category.OPERATION).toList();
            for (int i = 0; i < operations; i++) {
                var sample = iteration.samples().get(i);
                if (observed.get(i).phase() != sample.phase() || observed.get(i).kind() != sample.kind()) {
                    throw new IllegalArgumentException("Operation attribution differs from lifecycle samples");
                }
            }
        }
        if (offset != entries.size()) { throw new IllegalArgumentException("Unbound attributed scopes"); }
    }
}
