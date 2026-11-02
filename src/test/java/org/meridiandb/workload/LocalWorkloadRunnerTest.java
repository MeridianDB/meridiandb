// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.workload.WorkloadMeasurements.Kind;
import org.meridiandb.workload.WorkloadMeasurements.Phase;

@Timeout(120)
class LocalWorkloadRunnerTest {
    @TempDir Path parent;

    @ParameterizedTest
    @EnumSource(WorkloadCorpus.Profile.class)
    void actualForcedLifecycleRetainsExactAnswersAndCountsWithPinnedCompaction(WorkloadCorpus.Profile profile) throws IOException {
        var corpus = WorkloadCorpus.generate(profile.spec(42));
        var result = supported(corpus, new LocalWorkloadRunner.Config(0, 1));
        assertEquals(1, result.schemaVersion());
        assertEquals(corpus.fingerprint(), result.fingerprint());
        assertEquals(corpus.historyFingerprint(), result.historyFingerprint());
        assertEquals(corpus.encodedHistoryBytes(), result.encodedHistoryBytes());
        assertEquals(WorkloadOracle.prepare(corpus).summary(), result.workload());
        assertEquals(1, result.iterations().size());
        var iteration = result.iterations().getFirst();
        assertFalse(iteration.warmup());
        assertEquals(16, count(iteration, Phase.LOAD, Kind.COMMIT));
        assertEquals(112, count(iteration, Phase.MIXED, Kind.COMMIT));
        assertEquals(result.workload().retries(), count(iteration, Phase.MIXED, Kind.RETRY));
        for (var kind : List.of(Kind.GET, Kind.SCAN, Kind.SELECT, Kind.FILTER_SCAN, Kind.SUM)) {
            assertEquals(kind == Kind.SUM ? 12 : 13, count(iteration, Phase.MIXED, kind));
        }
        assertEquals(8, iteration.sourceSegments());
        assertEquals(1, iteration.replacementSegments());
        assertEquals(1, iteration.pinnedReadersDuringPublication());
        assertTrue(iteration.namespacePreparationNanos() >= 0);
        assertTrue(result.gaps().stream().anyMatch(s -> s.contains("CREATE excludes namespace/control-file preflight")));
        assertEquals(8, count(iteration, Phase.MAINTENANCE, Kind.SEGMENT_WRITE));
        for (var kind : List.of(Kind.CREATE, Kind.JOURNAL_REOPEN, Kind.CHECKPOINT_PUBLISH,
                Kind.COMPACTION_PLAN, Kind.COMPACTION_PUBLISH, Kind.CHECKPOINT_REOPEN)) {
            assertEquals(1, iteration.samples().stream().filter(s -> s.kind() == kind).count());
        }
        assertEquals(corpus.actions().size() + 14, iteration.samples().size());
        assertEquals(iteration.actionLoopNanos(), iteration.actionServiceNanos()
                + iteration.actionValidationNanos() + iteration.actionOtherNanos());
        assertTrue(iteration.totalValidationNanos() >= iteration.actionValidationNanos());
        assertTrue(iteration.validationTasks() > corpus.actions().size());
        assertTrue(iteration.samples().stream().allMatch(s -> s.elapsedNanos() >= 0));
        assertEquals(corpus.encodedHistoryBytes(), iteration.namedStorage().fileLengths().get("transactions.journal"));
        assertTrue(iteration.namedStorage().totalBytes() > result.encodedHistoryBytes());
        assertEquals(3, iteration.namedStorage().fileLengths().keySet().stream().filter(n -> n.startsWith("checkpoint-")).count());
        assertEquals(9, iteration.namedStorage().fileLengths().keySet().stream().filter(n -> n.endsWith(".segment")).count());
        assertTrue(result.runtime().maxHeapBytes() > 0);
        assertTrue(result.gaps().stream().anyMatch(s -> s.contains("not allocated blocks")));
        assertThrows(UnsupportedOperationException.class, () -> result.iterations().clear());
        assertThrows(UnsupportedOperationException.class, () -> iteration.samples().clear());
        assertThrows(UnsupportedOperationException.class, () -> iteration.namedStorage().fileLengths().clear());
        try (var reopened = LocalDatabase.openWritable(iteration.directory())) {
            assertEquals(128, reopened.head().sequence());
        }
    }

    @Test
    void warmupsAndMeasuredRepetitionsAlwaysUseFreshNamespacesAndNewCommits() throws IOException {
        var corpus = tiny();
        var result = supported(corpus, new LocalWorkloadRunner.Config(1, 2));
        assertEquals(List.of(true, false, false), result.iterations().stream().map(LocalWorkloadRunner.Iteration::warmup).toList());
        assertEquals(List.of(0, 1, 2), result.iterations().stream().map(LocalWorkloadRunner.Iteration::index).toList());
        var paths = new HashSet<Path>();
        for (var iteration : result.iterations()) {
            assertTrue(paths.add(iteration.directory()));
            assertEquals(result.runRoot(), iteration.directory().getParent());
            assertEquals(3, count(iteration, Phase.MIXED, Kind.COMMIT));
            assertEquals(3, count(iteration, Phase.MIXED, Kind.RETRY));
            assertEquals(2, iteration.sourceSegments());
        }
        var again = supported(corpus, new LocalWorkloadRunner.Config(0, 1));
        assertNotEquals(result.runRoot(), again.runRoot());
        assertEquals(result.historyFingerprint(), again.historyFingerprint());
        assertEquals(result.iterations().getFirst().namedStorage().totalBytes(), again.iterations().getFirst().namedStorage().totalBytes());
    }

    @Test
    void invalidConfigurationAndInvalidDeclaredHistoryMutateNoScratchNamespace() throws IOException {
        for (int[] values : List.of(new int[] {-1, 1}, new int[] {3, 1}, new int[] {0, 0}, new int[] {0, 6})) {
            assertThrows(IllegalArgumentException.class, () -> new LocalWorkloadRunner.Config(values[0], values[1]));
        }
        var corpus = tiny();
        var actions = new ArrayList<>(corpus.actions());
        var second = (WorkloadCorpus.Submit) actions.get(1);
        actions.set(1, new WorkloadCorpus.Submit(1, second.request(), second.stage(), second.intent()));
        var invalid = new WorkloadCorpus(corpus.spec(), actions);
        assertThrows(IllegalArgumentException.class, () -> LocalWorkloadRunner.run(parent, invalid, LocalWorkloadRunner.Config.DEFAULT));
        var excessive = WorkloadCorpus.generate(new WorkloadCorpus.Spec(1, 1, 1800, 0, 1, 0, 0, 0, 100, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LocalWorkloadRunner.run(parent, excessive, new LocalWorkloadRunner.Config(2, 5)));
        assertThrows(NullPointerException.class, () -> LocalWorkloadRunner.run(parent, corpus, null));
        try (var files = Files.list(parent)) { assertEquals(0, files.count()); }
    }

    @Test
    void nonDirectoryScratchAndMissingScratchFailWithoutOverwritingExistingBytes() throws IOException {
        Path file = Files.writeString(parent.resolve("retained"), "original");
        assertThrows(IOException.class, () -> LocalWorkloadRunner.run(file, tiny(), LocalWorkloadRunner.Config.DEFAULT));
        assertEquals("original", Files.readString(file));
        assertThrows(IOException.class, () -> LocalWorkloadRunner.run(parent.resolve("missing"), tiny(), LocalWorkloadRunner.Config.DEFAULT));
        assertFalse(Files.exists(parent.resolve("missing")));
    }

    @Test
    void postCreateTelemetryFailureClosesActualOwnersAndRetainsFailedEvidence() throws IOException {
        // A zero-operation probe first establishes whether this native fixture can execute.
        supported(tiny(), new LocalWorkloadRunner.Config(0, 1));
        var ticks = new AtomicLong();
        var probe = new WorkloadMeasurements.Probe() {
            int snapshots;
            @Override public long nanoTime() { return ticks.getAndAdd(10); }
            @Override public WorkloadMeasurements.ResourceSnapshot snapshot() {
                if (++snapshots == 2) { throw new IllegalStateException("injected post-create telemetry failure"); }
                return new WorkloadMeasurements.ResourceSnapshot(null, null, 0);
            }
        };
        var failed = assertThrows(LocalWorkloadRunner.RunFailedException.class,
                () -> LocalWorkloadRunner.run(parent, tiny(), new LocalWorkloadRunner.Config(0, 1), probe));
        assertTrue(Files.isDirectory(failed.runRoot()));
        assertTrue(failed.getCause().getMessage().contains("injected post-create"));
        Path home = failed.runRoot().resolve("iteration-0");
        assertTrue(Files.isRegularFile(home.resolve("transactions.journal")));
        try (var reopened = LocalDatabase.openWritable(home)) { assertEquals(0, reopened.head().sequence()); }
    }

    @Test
    void namedStorageRequiresCoherentNonnegativeLengthsAndMakesImmutableCopy() {
        var mutable = new java.util.HashMap<String, Long>(); mutable.put("a", 5L);
        var storage = new LocalWorkloadRunner.NamedStorage(mutable, 5); mutable.clear();
        assertEquals(java.util.Map.of("a", 5L), storage.fileLengths());
        assertThrows(IllegalArgumentException.class, () -> new LocalWorkloadRunner.NamedStorage(java.util.Map.of("a", -1L), -1));
        assertThrows(IllegalArgumentException.class, () -> new LocalWorkloadRunner.NamedStorage(java.util.Map.of("a", 5L), 4));
    }

    private LocalWorkloadRunner.Result supported(WorkloadCorpus corpus, LocalWorkloadRunner.Config config) throws IOException {
        try { return LocalWorkloadRunner.run(parent, corpus, config); }
        catch (LocalWorkloadRunner.RunFailedException failure) {
            if (failure.getCause() instanceof LocalCheckpointStore.DirectorySyncUnsupportedException
                    && System.getProperty("os.name").startsWith("Windows")) {
                assumeTrue(false, "Directory synchronization unavailable on this Windows provider");
            }
            throw failure;
        }
    }

    private static WorkloadCorpus tiny() {
        return WorkloadCorpus.generate(new WorkloadCorpus.Spec(7, 1, 4, 5, 2, 100, 0, 0, 100, 100, 100));
    }

    private static long count(LocalWorkloadRunner.Iteration iteration, Phase phase, Kind kind) {
        return iteration.samples().stream().filter(s -> s.phase() == phase && s.kind() == kind).count();
    }
}
