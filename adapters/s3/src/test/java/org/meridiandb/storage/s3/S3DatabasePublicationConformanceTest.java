// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.engine.EngineException;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/** Crosses actual publication, managed admission and local force boundaries; no remote durability claim. */
@Timeout(60)
class S3DatabasePublicationConformanceTest {
    private static final DatabaseId LINEAGE = new DatabaseId("managed-publication-conformance");
    private static final EntityId ENTITY = new EntityId("salary");
    private static final ObjectCheckpointGraphs.Limits LIMITS = ObjectCheckpointGraphs.DEFAULT_LIMITS;
    private static final S3ObjectStore.Timeouts TIMEOUTS = new S3ObjectStore.Timeouts(
            Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(2));
    private static final S3ObjectStore.Timeouts HELD_TIMEOUTS = new S3ObjectStore.Timeouts(
            Duration.ofMillis(800), Duration.ofSeconds(1), Duration.ofSeconds(2));
    private static final List<JournalRecord> HISTORY = List.of(
            new JournalRecord(1, transaction("initial", new WriteOperation.Put(ENTITY, new ValidTimeInterval(null, null), 100))),
            new JournalRecord(2, transaction("correction", new WriteOperation.Put(ENTITY, new ValidTimeInterval(10L, 20L), 120))),
            new JournalRecord(3, transaction("retraction", new WriteOperation.Retract(ENTITY, new ValidTimeInterval(12L, 14L)))));
    private static final Transaction NEXT = transaction("after-publication", new WriteOperation.Put(ENTITY, new ValidTimeInterval(13L, 14L), 999));
    private static final Duration WAIT = Duration.ofSeconds(6);
    private static final int HALT = 78;

    @TempDir Path directory;

    @Test void lateTimedOutSelectionRequiresExplicitReconciliationBeforeManagedWrites() throws Exception {
        try (var env = warm(); var timedHeads = heads(env.target, env.binding, HELD_TIMEOUTS)) {
            byte[] candidate = env.candidate(HISTORY, 3);
            prepare(env, candidate);
            String key = key(env);
            var previous = env.fixture.state(key);
            var gate = env.fixture.holdNextPut(key);
            var outcome = new FutureTask<Throwable>(() -> {
                try { publish(env, timedHeads, null, true); return null; }
                catch (Throwable failure) { return failure; }
            });
            var caller = new Thread(outcome, "managed-late-selection");
            caller.start();
            try {
                assertTrue(gate.awaitStarted(WAIT));
                var failure = assertInstanceOf(S3CheckpointPublicationException.class, outcome.get(6, TimeUnit.SECONDS));
                assertEquals(S3CheckpointPublicationException.Code.STILL_PENDING, failure.code());
                assertTrue(failure.writeAttempted());
                assertFalse(gate.awaitFinished(Duration.ofMillis(1)));
                assertArrayEquals(previous.bytes(), env.fixture.state(key).bytes());
                assertEquals(previous.etag(), env.fixture.state(key).etag());
                refusePendingWithoutNetwork(env, candidate);
                long puts = env.fixture.count("PUT", key);
                assertEquals(2, puts, "One initial selection and one held original-precondition PUT");
                var attempt = env.fixture.requests().stream().filter(r -> r.method().equals("PUT") && r.key().equals(key)).toList().getLast();
                assertEquals(previous.etag(), attempt.header("if-match"));
                assertArrayEquals(candidate, attempt.body());
                gate.release();
                assertTrue(gate.awaitFinished(WAIT));
                assertArrayEquals(candidate, env.fixture.state(key).bytes());
                // A current candidate does not clear durable pending as a side effect of engine open.
                refusePendingWithoutNetwork(env, candidate);
                var resolved = publish(env, env.heads, null, false);
                assertFalse(resolved.writeAttempted());
                assertEquals(puts, env.fixture.count("PUT", key));
                assertArrayEquals(candidate, resolved.observation().image());
                continueWrites(env, 3);
            } finally {
                gate.release();
                caller.join(6000);
                if (caller.isAlive()) { caller.interrupt(); caller.join(1000); }
                assertFalse(caller.isAlive(), "Publication caller must terminate");
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"WRITE_FINISHED", "CANDIDATE_VERIFIED", "STATE_SYNCED"})
    void publicationProcessExitThenManagedRecoveryPreservesTemporalAnswersAndRetryIdentity(String boundary) throws Exception {
        try (var env = warm()) {
            byte[] candidate = env.candidate(HISTORY, 3);
            prepare(env, candidate);
            try (var child = start(env, boundary)) {
                assertEquals(HALT, child.awaitExit(), child.diagnostics());
                assertTrue(child.diagnostics().contains("HALT " + boundary), child.diagnostics());
            }
            assertArrayEquals(candidate, env.fixture.state(key(env)).bytes());
            if (boundary.equals("STATE_SYNCED")) {
                try (var local = LocalS3PublicationState.open(env.stateDirectory, env.target, env.binding)) {
                    assertTrue(local.state().pending().isEmpty());
                    assertArrayEquals(candidate, local.state().expected().orElseThrow().image());
                }
            } else {
                refusePendingWithoutNetwork(env, candidate);
                long puts = env.fixture.count("PUT", key(env));
                assertFalse(publish(env, env.heads, null, false).writeAttempted());
                assertEquals(puts, env.fixture.count("PUT", key(env)));
            }
            continueWrites(env, 3);
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 3})
    void cleanTailLossRequiresIndependentCoverageAndNeverReconstructsMissingTransactions(int cutoff) throws Exception {
        try (var env = warm()) {
            env.select(HISTORY, cutoff);
            Path journal = env.databaseDirectory.resolve("transactions.journal");
            byte[] complete = Files.readAllBytes(journal);
            Files.write(directory.resolve("original-journal.evidence"), complete);
            byte[] surviving = journalImage(HISTORY.subList(0, 2));
            // Deliberate offline storage-loss injection after every owner has closed.
            Files.write(journal, surviving);
            var metadata = env.metadata();
            int requests = env.requestCount();
            assertThrows(IOException.class, () -> env.open(3));
            assertEquals(requests + 1, env.requestCount(), "Minimum coverage fails after fixed head GET, before graph reads");
            if (cutoff == 3) {
                assertThrows(IOException.class, () -> openDefault(env));
            } else {
                try (var recovered = openDefault(env)) {
                    assertEquals(position(2), recovered.head());
                    assertEquals(Optional.of(position(cutoff)), recovered.checkpointPosition());
                    assertEquals(Optional.empty(), recovered.lookupRequest(HISTORY.getLast().transaction().requestId()));
                    assertEquals(new CommitResult(position(1), true), recovered.transact(HISTORY.getFirst().transaction()));
                    assertEquals(EngineException.Code.INVALID_CUTOFF, assertThrows(EngineException.class,
                            () -> recovered.openView(new ReadCutoff.At(position(3)))).code());
                    try (var view = recovered.openView(ReadCutoff.Head.INSTANCE)) {
                        assertEquals(point(120), view.query(new TemporalQuery.Get(ENTITY, 13)));
                    }
                }
            }
            assertArrayEquals(surviving, Files.readAllBytes(journal));
            sameImages(metadata, env.metadata());
            Files.write(journal, complete);
            try (var recovered = env.open(3)) {
                assertEquals(new CommitResult(position(3), true), recovered.transact(HISTORY.getLast().transaction()));
                assertEquals(position(3), recovered.head());
            }
            assertArrayEquals(complete, Files.readAllBytes(journal));
        }
    }

    @Test void missingCompleteJournalFailsDespiteFullRemoteCheckpointAndPreservesEvidence() throws Exception {
        try (var env = warm()) {
            env.select(HISTORY, 3);
            Path journal = env.databaseDirectory.resolve("transactions.journal");
            Path evidence = directory.resolve("missing-journal.evidence");
            Files.move(journal, evidence);
            var metadata = env.metadata();
            assertThrows(IOException.class, () -> openDefault(env));
            assertFalse(Files.exists(journal), "A remote checkpoint must not fabricate a replacement journal");
            assertArrayEquals(journalImage(HISTORY), Files.readAllBytes(evidence));
            sameImages(metadata, env.metadata());
            Files.move(evidence, journal);
            try (var recovered = env.open(3)) { assertEquals(position(3), recovered.head()); }
        }
    }

    @Test void failedOnlineStartupDoesNotFallBackToLocalHistoryOrMutateEitherAuthority() throws Exception {
        try (var env = warm()) {
            var metadata = env.metadata();
            byte[] journal = Files.readAllBytes(env.databaseDirectory.resolve("transactions.journal"));
            var remote = env.fixture.state(key(env));
            env.fixture.fault("GET", key(env), S3PublicationProtocolFixture.Fault.FORBIDDEN);
            assertThrows(IOException.class, () -> env.open(3));
            assertArrayEquals(journal, Files.readAllBytes(env.databaseDirectory.resolve("transactions.journal")));
            sameImages(metadata, env.metadata());
            assertArrayEquals(remote.bytes(), env.fixture.state(key(env)).bytes());
            assertEquals(remote.etag(), env.fixture.state(key(env)).etag());
            env.fixture.clearFault("GET", key(env));
            continueWrites(env, 0);
        }
    }

    @Test void capturedExpectedHeadDoesNotBecomeContinuingSelectionOrWriterFencing() throws Exception {
        try (var env = warm()) {
            var original = env.fixture.state(key(env));
            byte[] later = env.candidate(HISTORY, 3);
            env.fixture.captureNextGet(key(env), later);
            var metadata = env.metadata();
            try (var recovered = env.open(3)) {
                int requests = env.requestCount();
                assertArrayEquals(original.bytes(), recovered.observation().image());
                assertEquals(original.etag(), recovered.observation().token().value());
                assertEquals(Optional.of(position(0)), recovered.checkpointPosition());
                assertArrayEquals(later, env.fixture.state(key(env)).bytes());
                assertEquals(new CommitResult(position(4), false), recovered.transact(NEXT));
                assertEquals(position(4), recovered.head());
                assertEquals(requests, env.requestCount());
            }
            sameImages(metadata, env.metadata());
            assertThrows(IOException.class, () -> env.open(4), "Next startup must reject changed current selection rather than adopt it");
            // Restore deliberately modified fixture evidence; this is not an automatic repair path.
            env.fixture.forceState(key(env), original.bytes(), original.etag());
            try (var recovered = env.open(4)) { assertEquals(new CommitResult(position(4), true), recovered.transact(NEXT)); }
        }
    }

    private S3ManagedRecoveryCorpusFixture warm() throws IOException {
        S3ManagedRecoveryCorpusFixture env = null;
        try {
            env = new S3ManagedRecoveryCorpusFixture(directory.resolve("home"), LINEAGE);
            env.select(List.of(), 0);
            try (var writer = env.open(0)) {
                for (var record : HISTORY) { assertEquals(new CommitResult(position(record.sequence()), false), writer.transact(record.transaction())); }
            }
            return env;
        } catch (IOException | RuntimeException | Error failure) {
            if (env != null) {
                try { env.close(); }
                catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
            }
            if (failure instanceof LocalCheckpointStore.DirectorySyncUnsupportedException
                    || failure instanceof LocalS3PublicationState.DirectorySyncUnsupportedException) {
                assumeTrue(false, "Required directory synchronization unavailable: " + failure);
            }
            throw failure;
        }
    }

    private static void continueWrites(S3ManagedRecoveryCorpusFixture env, int cutoff) throws IOException {
        var metadata = env.metadata();
        var remote = env.fixture.state(key(env));
        int requests;
        try (var recovered = env.open(3)) {
            requests = env.requestCount();
            assertEquals(position(3), recovered.head());
            assertEquals(Optional.of(position(cutoff)), recovered.checkpointPosition());
            for (var record : HISTORY) {
                assertEquals(Optional.of(position(record.sequence())), recovered.lookupRequest(record.transaction().requestId()));
                assertEquals(new CommitResult(position(record.sequence()), true), recovered.transact(record.transaction()));
            }
            for (int at = 0; at <= 3; at++) {
                try (var view = recovered.openView(new ReadCutoff.At(position(at)))) {
                    assertEquals(at == 0 || at == 3 ? new TemporalResult.Point(OptionalLong.empty()) : point(at == 1 ? 100 : 120),
                            view.query(new TemporalQuery.Get(ENTITY, 13)));
                }
            }
            try (var old = recovered.openView(ReadCutoff.Head.INSTANCE)) {
                assertEquals(new CommitResult(position(4), false), recovered.transact(NEXT));
                assertEquals(position(3), old.position());
                assertEquals(new TemporalResult.Point(OptionalLong.empty()), old.query(new TemporalQuery.Get(ENTITY, 13)));
            }
            assertEquals(new CommitResult(position(4), true), recovered.transact(NEXT));
            assertEquals(Optional.of(position(cutoff)), recovered.checkpointPosition());
            assertArrayEquals(remote.bytes(), recovered.observation().image());
            assertEquals(remote.etag(), recovered.observation().token().value());
        }
        assertEquals(requests, env.requestCount());
        sameImages(metadata, env.metadata());
        assertArrayEquals(remote.bytes(), env.fixture.state(key(env)).bytes());
        assertEquals(remote.etag(), env.fixture.state(key(env)).etag());
        var expected = new java.util.ArrayList<>(HISTORY);
        expected.add(new JournalRecord(4, NEXT));
        assertArrayEquals(journalImage(expected), Files.readAllBytes(env.databaseDirectory.resolve("transactions.journal")));
        try (var reopened = env.open(4)) {
            assertEquals(new CommitResult(position(4), true), reopened.transact(NEXT));
            assertEquals(position(4), reopened.head());
        }
        sameImages(metadata, env.metadata());
    }

    private static void prepare(S3ManagedRecoveryCorpusFixture env, byte[] candidate) throws IOException {
        try (var local = LocalS3PublicationState.open(env.stateDirectory, env.target, env.binding);
             var store = LocalCheckpointStore.open(env.databaseDirectory);
             var journal = LocalJournal.open(store.journalPath())) {
            new S3PublicationPreparer(env.objects, env.heads, local, store, journal).prepare(candidate, LIMITS);
        }
    }

    private static S3CheckpointPublisher.Result publish(S3ManagedRecoveryCorpusFixture env, S3CheckpointHeads heads,
            S3CheckpointPublisher.PublicationProbe probe, boolean submit) throws IOException {
        try (var local = LocalS3PublicationState.open(env.stateDirectory, env.target, env.binding);
             var store = LocalCheckpointStore.open(env.databaseDirectory);
             var journal = LocalJournal.open(store.journalPath())) {
            var publisher = new S3CheckpointPublisher(env.objects, heads, local, store, journal, probe);
            return submit ? publisher.submit(LIMITS) : publisher.reconcile(LIMITS);
        }
    }

    private static void refusePendingWithoutNetwork(S3ManagedRecoveryCorpusFixture env, byte[] candidate) throws IOException {
        var metadata = env.metadata();
        int requests = env.requestCount();
        assertThrows(IOException.class, () -> env.open(3));
        assertEquals(requests, env.requestCount());
        sameImages(metadata, env.metadata());
        try (var local = LocalS3PublicationState.open(env.stateDirectory, env.target, env.binding)) {
            assertArrayEquals(candidate, local.state().pending().orElseThrow().image());
        }
    }

    private static S3Database openDefault(S3ManagedRecoveryCorpusFixture env) throws IOException {
        return S3Database.openWritable(env.databaseDirectory, env.stateDirectory, env.target, env.binding, env.objects, env.heads, LIMITS);
    }
    private static Transaction transaction(String id, WriteOperation operation) { return new Transaction(new RequestId(id), List.of(operation)); }
    private static CommitPosition position(long sequence) { return new CommitPosition(LINEAGE, sequence); }
    private static TemporalResult.Point point(long value) { return new TemporalResult.Point(OptionalLong.of(value)); }
    private static String key(S3ManagedRecoveryCorpusFixture env) { return S3CheckpointHeads.headKey(env.binding.namespaceId()).value(); }
    private static byte[] journalImage(List<JournalRecord> records) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(LINEAGE));
        records.forEach(record -> bytes.writeBytes(JournalCodec.encodeRecord(record)));
        return bytes.toByteArray();
    }
    private static void sameImages(Map<String, byte[]> before, Map<String, byte[]> after) {
        assertEquals(before.keySet(), after.keySet());
        before.forEach((name, image) -> assertArrayEquals(image, after.get(name), name));
    }
    private static S3CheckpointHeads heads(S3PublicationState.Target target, S3CheckpointHeads.Binding binding, S3ObjectStore.Timeouts timeouts) {
        return new S3CheckpointHeads(target.endpoint(), Region.of(target.regionId()), target.bucket(), credentials(), binding, timeouts);
    }
    private static StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("fixture-access", "fixture-secret"));
    }

    private Child start(S3ManagedRecoveryCorpusFixture env, String boundary) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        Path output = directory.resolve("publication-" + boundary + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath, PublicationProcess.class.getName(),
                env.databaseDirectory.toAbsolutePath().toString(), env.stateDirectory.toAbsolutePath().toString(),
                env.target.endpoint().toString(), boundary).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new Child(process, output);
    }

    private record Child(Process process, Path output) implements AutoCloseable {
        int awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), this::diagnostics);
            return process.exitValue();
        }
        String diagnostics() {
            try (var input = Files.newInputStream(output)) { return new String(input.readNBytes(8192), StandardCharsets.UTF_8); }
            catch (IOException failure) { return failure.toString(); }
        }
        @Override public void close() throws IOException {
            if (process.isAlive()) { process.destroyForcibly(); }
            try { if (!process.waitFor(20, TimeUnit.SECONDS)) { throw new IOException("Publication child did not terminate"); } }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted terminating publication child", interrupted);
            } finally {
                process.getOutputStream().close(); process.getInputStream().close(); process.getErrorStream().close();
            }
        }
    }

    public static final class PublicationProcess {
        private PublicationProcess() { }
        public static void main(String[] args) throws Exception {
            var target = new S3PublicationState.Target(URI.create(args[2]), "us-east-1", S3PublicationProtocolFixture.BUCKET);
            var binding = new S3CheckpointHeads.Binding(new UUID(0, 1), new UUID(0, 2), ObjectCheckpointHeadVerifier.lineageId(LINEAGE));
            try (var objects = new S3ObjectStore(target.endpoint(), Region.of(target.regionId()), target.bucket(), credentials(), TIMEOUTS);
                 var heads = heads(target, binding, TIMEOUTS);
                 var local = LocalS3PublicationState.open(Path.of(args[1]), target, binding);
                 var store = LocalCheckpointStore.open(Path.of(args[0]));
                 var journal = LocalJournal.open(store.journalPath())) {
                new S3CheckpointPublisher(objects, heads, local, store, journal, boundary -> {
                    if (boundary.name().equals(args[3])) {
                        System.out.println("HALT " + boundary); System.out.flush(); Runtime.getRuntime().halt(HALT);
                    }
                }).submit(LIMITS);
                throw new AssertionError("Expected publication halt at " + args[3]);
            }
        }
    }
}
