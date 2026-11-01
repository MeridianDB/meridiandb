// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.engine.LocalDatabase.Boundary;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ImmutableObjectStore;
import org.meridiandb.storage.object.LocalObjectStore;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.object.VerifiedObject;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Real process stops leave the OS running; these fixtures do not simulate native force failure or power loss. */
@Timeout(60)
class ObjectCheckpointWritableTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final DatabaseId DATABASE = new DatabaseId("object-writable-db");
    private static final EntityId SALARY = new EntityId("salary");
    private static final ObjectCheckpointGraphs.Limits DEFAULTS = ObjectCheckpointGraphs.DEFAULT_LIMITS;
    private static final Transaction FIRST = transaction("initial", new ValidTimeInterval(null, null), 100);
    private static final Transaction SECOND = transaction("local-tail", new ValidTimeInterval(10L, 20L), 120);
    private static final Transaction NOOP = transaction("new-no-op", new ValidTimeInterval(10L, 20L), 120);
    private static final Transaction CANDIDATE = transaction("new-correction", new ValidTimeInterval(12L, 14L), 130);
    private static final List<JournalRecord> INITIAL = List.of(new JournalRecord(1, FIRST), new JournalRecord(2, SECOND));
    private static final List<Boundary> COMMIT_BOUNDARIES = List.of(
            Boundary.BEFORE_APPEND, Boundary.AFTER_APPEND, Boundary.AFTER_FORCE, Boundary.AFTER_PUBLICATION);
    private static final int HALT_STATUS = 73;
    private static final int UNSUPPORTED_STATUS = 77;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    @TempDir Path directory;

    @Test void writableStartupSynchronizesBeforeReturningThenPreservesRetriesNoopsViewsAndOriginalHistory() throws IOException {
        var layout = layout();
        var fixture = fixture();
        var objects = objects(fixture);
        var boundaries = new ArrayList<Boundary>();
        var database = open(layout, objects, fixture, boundaries::add);
        var historical = database.openView(new ReadCutoff.At(position(1)));
        var oldHead = database.openView(ReadCutoff.Head.INSTANCE);
        try {
            assertEquals(List.of(Boundary.RECOVERED, Boundary.SYNCHRONIZED), boundaries);
            assertEquals(position(2), database.head());
            assertEquals(Optional.of(position(1)), database.checkpointPosition());
            assertEquals(expectedReads(fixture), objects.reads);
            assertReadersClosed(objects);
            assertEquals(0, objects.closes);
            assertOwnersLocked(layout);
            boundaries.clear();
            assertTrue(database.transact(FIRST).replayed());
            var tailRetry = database.transact(SECOND);
            assertTrue(tailRetry.replayed());
            assertEquals(position(2), tailRetry.position());
            assertTrue(boundaries.isEmpty(), "exact recovered retries perform no append/force/publication sequence");
            objects.close();
            var noOp = database.transact(NOOP);
            assertEquals(position(3), noOp.position());
            assertFalse(noOp.replayed());
            assertEquals(COMMIT_BOUNDARIES, boundaries);
            assertEquals(present(120), query(database, 13));
            boundaries.clear();
            var committed = database.transact(CANDIDATE);
            assertEquals(position(4), committed.position());
            assertFalse(committed.replayed());
            assertEquals(COMMIT_BOUNDARIES, boundaries);
            assertEquals(present(100), historical.query(new TemporalQuery.Get(SALARY, 13)));
            assertEquals(present(120), oldHead.query(new TemporalQuery.Get(SALARY, 13)));
            assertEquals(present(130), query(database, 13));
            assertEquals(present(120), query(database, 11));
            assertEquals(present(100), query(database, 21));
            assertEquals(Optional.of(position(1)), database.lookupRequest(FIRST.requestId()));
            assertEquals(Optional.of(position(2)), database.lookupRequest(SECOND.requestId()));
            assertEquals(Optional.of(position(3)), database.lookupRequest(NOOP.requestId()));
            assertEquals(Optional.of(position(4)), database.lookupRequest(CANDIDATE.requestId()));
            assertEquals(Optional.of(position(1)), database.checkpointPosition());
            boundaries.clear();
            assertTrue(database.transact(CANDIDATE).replayed());
            assertEquals(position(4), database.transact(CANDIDATE).position());
            assertTrue(boundaries.isEmpty());
            var conflict = transaction(CANDIDATE.requestId().value(), new ValidTimeInterval(12L, 14L), 999);
            assertThrows(EngineException.class, () -> database.transact(conflict));
            assertTrue(boundaries.isEmpty());
            assertEquals(position(4), database.head());
            assertEquals(expectedReads(fixture), objects.reads, "writes need no further object access after startup");
            assertEquals(0, objects.creates);
        } finally { database.close(); }
        assertThrows(IllegalStateException.class, () -> historical.query(new TemporalQuery.Get(SALARY, 13)));
        assertThrows(IllegalStateException.class, () -> oldHead.query(new TemporalQuery.Get(SALARY, 13)));
        historical.close();
        oldHead.close();
        assertEquals(1, objects.closes, "the database never closes the caller adapter, including after it was explicitly closed");
        var expected = List.of(new JournalRecord(1, FIRST), new JournalRecord(2, SECOND), new JournalRecord(3, NOOP), new JournalRecord(4, CANDIDATE));
        assertArrayEquals(journalImage(expected), Files.readAllBytes(layout.journalPath()));
        assertOwnershipReleased(layout, 4);
        try (var reopened = LocalDatabase.openWritableFromObjectCheckpoint(layout.home(), objects(fixture), NAMESPACE, PUBLISHER, head(fixture), DEFAULTS)) {
            assertEquals(position(4), reopened.head());
            assertTrue(reopened.transact(CANDIDATE).replayed());
            assertEquals(present(130), query(reopened, 13));
        }
        assertArrayEquals(journalImage(expected), Files.readAllBytes(layout.journalPath()));
    }

    @Test void readOnlyPinnedFactoryStillRefusesTransactionsAndNeverEntersStartupSynchronization() throws IOException {
        var layout = layout();
        var fixture = fixture();
        var objects = objects(fixture);
        var boundaries = new ArrayList<Boundary>();
        byte[] original = Files.readAllBytes(layout.journalPath());
        try (var database = LocalDatabase.openFromObjectCheckpoint(layout.home(), objects, NAMESPACE, PUBLISHER, head(fixture), DEFAULTS, 2,
                boundaries::add)) {
            assertEquals(List.of(Boundary.RECOVERED), boundaries);
            assertThrows(IllegalStateException.class, () -> database.transact(FIRST));
            assertThrows(IllegalStateException.class, () -> database.transact(CANDIDATE));
            assertEquals(position(2), database.head());
            assertEquals(Optional.empty(), database.lookupRequest(CANDIDATE.requestId()));
        }
        assertArrayEquals(original, Files.readAllBytes(layout.journalPath()));
        assertEquals(0, objects.closes);
        assertOwnershipReleased(layout, 2);
    }

    @Test void newFactoryPreflightsArgumentsBeforeCreatingOwnersOrFetchingObjects() throws IOException {
        Path unopened = Files.createDirectory(directory.resolve("unopened"));
        var fixture = fixture();
        var objects = objects(fixture);
        var head = head(fixture);
        assertThrows(NullPointerException.class,
                () -> LocalDatabase.openWritableFromObjectCheckpoint(null, objects, NAMESPACE, PUBLISHER, head, DEFAULTS));
        assertThrows(NullPointerException.class,
                () -> LocalDatabase.openWritableFromObjectCheckpoint(unopened, null, NAMESPACE, PUBLISHER, head, DEFAULTS));
        assertThrows(NullPointerException.class,
                () -> LocalDatabase.openWritableFromObjectCheckpoint(unopened, objects, null, PUBLISHER, head, DEFAULTS));
        assertThrows(NullPointerException.class,
                () -> LocalDatabase.openWritableFromObjectCheckpoint(unopened, objects, NAMESPACE, null, head, DEFAULTS));
        assertThrows(NullPointerException.class,
                () -> LocalDatabase.openWritableFromObjectCheckpoint(unopened, objects, NAMESPACE, PUBLISHER, null, DEFAULTS));
        assertThrows(NullPointerException.class,
                () -> LocalDatabase.openWritableFromObjectCheckpoint(unopened, objects, NAMESPACE, PUBLISHER, head, null));
        assertThrows(IllegalArgumentException.class,
                () -> LocalDatabase.openWritableFromObjectCheckpoint(unopened, objects, new UUID(0, 0), PUBLISHER, head, DEFAULTS));
        assertThrows(IllegalArgumentException.class,
                () -> LocalDatabase.openWritableFromObjectCheckpoint(unopened, objects, NAMESPACE, new UUID(0, 0), head, DEFAULTS));
        assertThrows(IllegalArgumentException.class,
                () -> LocalDatabase.openWritableFromObjectCheckpoint(unopened, objects, NAMESPACE, PUBLISHER, head, DEFAULTS, -1));
        try (var paths = Files.list(unopened)) { assertTrue(paths.findAny().isEmpty()); }
        assertTrue(objects.reads.isEmpty());
        assertEquals(0, objects.closes);
    }

    @Test void independentMinimumRejectsBeforeObjectFetchAndOnlyAnAdmittedStartupCanReturnRetryAcknowledgement() throws IOException {
        var layout = layout();
        var fixture = fixture();
        var objects = objects(fixture);
        assertThrows(IOException.class, () -> LocalDatabase.openWritableFromObjectCheckpoint(layout.home(), objects,
                NAMESPACE, PUBLISHER, head(fixture), DEFAULTS, 3));
        assertTrue(objects.reads.isEmpty());
        assertEquals(0, objects.closes);
        assertOwnershipReleased(layout, 2);
        try (var database = LocalDatabase.openWritableFromObjectCheckpoint(layout.home(), objects, NAMESPACE, PUBLISHER, head(fixture), DEFAULTS, 2)) {
            assertTrue(database.transact(FIRST).replayed());
            assertEquals(position(1), database.transact(FIRST).position());
            assertEquals(position(2), database.head());
        }
        assertArrayEquals(journalImage(INITIAL), Files.readAllBytes(layout.journalPath()));
    }

    @ParameterizedTest @ValueSource(strings = {"RECOVERED:io", "RECOVERED:runtime", "RECOVERED:error",
            "SYNCHRONIZED:io", "SYNCHRONIZED:runtime", "SYNCHRONIZED:error"})
    void startupBoundaryFailuresDiscardPrivateStateAndReleaseBothOwnersWithoutReturningAnAcknowledgingDatabase(String stage) throws IOException {
        String[] parts = stage.split(":");
        var boundary = Boundary.valueOf(parts[0]);
        var expected = failure(parts[1]);
        var layout = layout();
        var fixture = fixture();
        var objects = objects(fixture);
        var escaped = new AtomicReference<LocalDatabase>();
        var seen = new ArrayList<Boundary>();
        assertSame(expected, assertThrows(Throwable.class, () -> escaped.set(open(layout, objects, fixture, reached -> {
            seen.add(reached);
            assertOwnersLocked(layout);
            if (reached == boundary) { throwFailure(expected); }
        }))));
        assertNull(escaped.get());
        assertEquals(boundary == Boundary.RECOVERED ? List.of(Boundary.RECOVERED)
                : List.of(Boundary.RECOVERED, Boundary.SYNCHRONIZED), seen);
        assertEquals(expectedReads(fixture), objects.reads);
        assertReadersClosed(objects);
        assertEquals(0, objects.closes);
        assertOwnershipReleased(layout, 2);
        assertArrayEquals(journalImage(INITIAL), Files.readAllBytes(layout.journalPath()));
    }

    @ParameterizedTest @ValueSource(strings = {"io", "runtime", "error"})
    void sourceFailuresPrecedeStartupSynchronizationAndReleaseFactoryOwnersWithoutClosingTheAdapter(String kind) throws IOException {
        var layout = layout();
        var fixture = fixture();
        var objects = objects(fixture);
        var expected = failure(kind);
        objects.failure = expected;
        var seen = new ArrayList<Boundary>();
        assertSame(expected, assertThrows(Throwable.class, () -> open(layout, objects, fixture, seen::add)));
        assertTrue(seen.isEmpty());
        assertEquals(1, objects.reads.size());
        assertEquals(0, objects.closes);
        assertOwnershipReleased(layout, 2);
        assertArrayEquals(journalImage(INITIAL), Files.readAllBytes(layout.journalPath()));
    }

    @ParameterizedTest @ValueSource(strings = {"BEFORE_APPEND:io", "AFTER_APPEND:io", "AFTER_APPEND:runtime", "AFTER_APPEND:error",
            "AFTER_FORCE:io", "AFTER_PUBLICATION:io", "AFTER_PUBLICATION:runtime", "AFTER_PUBLICATION:error"})
    void failedWriteBoundaryPoisonsViewsRetainsOwnersAndResolvesOnlyByActualWritableReopen(String stage) throws IOException {
        String[] parts = stage.split(":");
        var boundary = Boundary.valueOf(parts[0]);
        var expected = failure(parts[1]);
        var layout = layout();
        var fixture = fixture();
        var objects = objects(fixture);
        var database = open(layout, objects, fixture, reached -> {
            if (reached == boundary) { throwFailure(expected); }
        });
        var view = database.openView(ReadCutoff.Head.INSTANCE);
        var returnedBeforeFailure = view.query(new TemporalQuery.Get(SALARY, 13));
        try {
            if (expected instanceof Error) { assertSame(expected, assertThrows(Error.class, () -> database.transact(CANDIDATE))); }
            else {
                var uncertain = assertThrows(LocalDatabase.UncertainCommitException.class, () -> database.transact(CANDIDATE));
                assertSame(expected, uncertain.getCause());
                assertEquals(CANDIDATE.requestId(), uncertain.requestId());
                assertEquals(position(3), uncertain.proposedPosition());
            }
            assertThrows(IllegalStateException.class, database::head);
            assertThrows(IllegalStateException.class, () -> database.lookupRequest(CANDIDATE.requestId()));
            assertThrows(IllegalStateException.class, () -> database.transact(CANDIDATE));
            assertThrows(IllegalStateException.class, () -> view.query(new TemporalQuery.Get(SALARY, 13)));
            assertOwnersLocked(layout);
            assertEquals(0, objects.closes);
        } finally { database.close(); }
        view.close();
        assertEquals(present(120), returnedBeforeFailure);
        boolean appended = boundary != Boundary.BEFORE_APPEND;
        var stored = appended ? List.of(new JournalRecord(1, FIRST), new JournalRecord(2, SECOND), new JournalRecord(3, CANDIDATE)) : INITIAL;
        assertOwnershipReleased(layout, appended ? 3 : 2);
        assertArrayEquals(journalImage(stored), Files.readAllBytes(layout.journalPath()));
        try (var reopened = LocalDatabase.openWritableFromObjectCheckpoint(layout.home(), objects(fixture), NAMESPACE, PUBLISHER, head(fixture), DEFAULTS)) {
            assertEquals(position(appended ? 3 : 2), reopened.head());
            assertEquals(appended ? Optional.of(position(3)) : Optional.empty(), reopened.lookupRequest(CANDIDATE.requestId()));
            var retry = reopened.transact(CANDIDATE);
            assertEquals(position(3), retry.position());
            assertEquals(appended, retry.replayed());
            assertEquals(present(130), query(reopened, 13));
        }
        assertArrayEquals(journalImage(List.of(new JournalRecord(1, FIRST), new JournalRecord(2, SECOND), new JournalRecord(3, CANDIDATE))),
                Files.readAllBytes(layout.journalPath()));
    }

    @ParameterizedTest @ValueSource(strings = {"RECOVERED", "SYNCHRONIZED", "AFTER_APPEND", "AFTER_FORCE", "AFTER_PUBLICATION"})
    void haltedWritableStartupOrCommitReopensExactCompleteLocalHistoryAndRetry(String stage) throws Exception {
        var boundary = Boundary.valueOf(stage);
        var layout = layout();
        var fixture = fixture();
        Path objectDirectory = Files.createDirectory(directory.resolve("objects"));
        var objects = new LocalObjectStore(objectDirectory);
        install(objects, fixture);
        byte[] originalManifest = Files.readAllBytes(objectDirectory.resolve(manifestKey(fixture).value()));
        byte[] originalSegment = Files.readAllBytes(objectDirectory.resolve(segmentKey(fixture).value()));
        try (var child = start(layout, objectDirectory, boundary)) {
            int status = child.awaitExit();
            assumeTrue(status != UNSUPPORTED_STATUS, () -> "Child explicit directory-sync capability: " + child.diagnostics());
            assertEquals(HALT_STATUS, status, child.diagnostics());
            assertTrue(child.diagnostics().contains("HALT " + boundary), child.diagnostics());
        }
        boolean appended = boundary == Boundary.AFTER_APPEND || boundary == Boundary.AFTER_FORCE || boundary == Boundary.AFTER_PUBLICATION;
        var expected = appended ? List.of(new JournalRecord(1, FIRST), new JournalRecord(2, SECOND), new JournalRecord(3, CANDIDATE)) : INITIAL;
        assertOwnershipReleased(layout, appended ? 3 : 2);
        assertArrayEquals(journalImage(expected), Files.readAllBytes(layout.journalPath()));
        try (var recovered = LocalDatabase.openWritableFromObjectCheckpoint(layout.home(), objects, NAMESPACE, PUBLISHER, head(fixture), DEFAULTS, 2)) {
            assertEquals(position(appended ? 3 : 2), recovered.head());
            assertEquals(Optional.of(position(1)), recovered.checkpointPosition());
            assertEquals(appended ? Optional.of(position(3)) : Optional.empty(), recovered.lookupRequest(CANDIDATE.requestId()));
            var resolved = recovered.transact(CANDIDATE);
            assertEquals(position(3), resolved.position());
            assertEquals(appended, resolved.replayed());
            assertEquals(present(130), query(recovered, 13));
        }
        assertArrayEquals(journalImage(List.of(new JournalRecord(1, FIRST), new JournalRecord(2, SECOND), new JournalRecord(3, CANDIDATE))),
                Files.readAllBytes(layout.journalPath()));
        assertArrayEquals(originalManifest, Files.readAllBytes(objectDirectory.resolve(manifestKey(fixture).value())));
        assertArrayEquals(originalSegment, Files.readAllBytes(objectDirectory.resolve(segmentKey(fixture).value())));
    }

    private Layout layout() throws IOException {
        Path home = Files.createDirectory(directory.resolve("database"));
        try (var store = supportedStore(home); var journal = store.initialize(DATABASE)) {
            for (var record : INITIAL) { journal.append(record); }
            journal.force();
            return new Layout(home, journal.path().orElseThrow());
        }
    }
    private static LocalCheckpointStore supportedStore(Path path) throws IOException {
        try { return LocalCheckpointStore.open(path); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, () -> "Explicit directory-sync capability unavailable: " + unsupported.getMessage());
            throw unsupported;
        }
    }
    private record Layout(Path home, Path journalPath) { }

    private static LocalDatabase open(Layout layout, ImmutableObjectStore objects, Fixture fixture, LocalDatabase.BoundaryProbe probe) throws IOException {
        return LocalDatabase.openWritableFromObjectCheckpoint(layout.home(), objects, NAMESPACE, PUBLISHER, head(fixture), DEFAULTS, 2, probe);
    }
    private static void assertOwnersLocked(Layout layout) {
        assertThrows(IOException.class, () -> {
            try (var unexpected = LocalCheckpointStore.open(layout.home())) { unexpected.journalPath(); }
        });
        assertThrows(IOException.class, () -> {
            try (var unexpected = LocalJournal.open(layout.journalPath())) { unexpected.prefix(0); }
        });
    }
    private static void assertOwnershipReleased(Layout layout, long sequence) throws IOException {
        try (var store = LocalCheckpointStore.open(layout.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertEquals(DATABASE, journal.databaseId());
            assertEquals(sequence, journal.appendedSequence());
        }
    }

    private static Fixture fixture() {
        byte[] segment = SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(INITIAL.getFirst())));
        var id = new SegmentId(hash(segment));
        byte[] prefix = journalImage(List.of(INITIAL.getFirst()));
        var manifest = new CheckpointManifest(DATABASE, 17, new JournalPrefix(1, prefix.length, hash(prefix)),
                List.of(new SegmentReference(id, segment.length, 1, 1)));
        return new Fixture(manifest, ManifestCodec.encode(manifest), id, segment);
    }
    private record Fixture(CheckpointManifest manifest, byte[] manifestImage, SegmentId segmentId, byte[] segmentImage) { }
    private static ObjectCheckpointHead head(Fixture fixture) {
        return new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 3), ObjectCheckpointHeadVerifier.lineageId(DATABASE), 1,
                ObjectReference.forBytes(fixture.manifestImage()), fixture.manifest().journalPrefix(), null);
    }
    private static ObjectKey manifestKey(Fixture fixture) {
        return ObjectCheckpointGraphs.manifestKey(NAMESPACE, ObjectReference.forBytes(fixture.manifestImage()).id());
    }
    private static ObjectKey segmentKey(Fixture fixture) { return ObjectCheckpointGraphs.segmentKey(NAMESPACE, fixture.segmentId()); }
    private static List<ObjectKey> expectedReads(Fixture fixture) { return List.of(manifestKey(fixture), segmentKey(fixture)); }
    private static void install(ImmutableObjectStore objects, Fixture fixture) throws IOException {
        objects.createNew(manifestKey(fixture), fixture.manifestImage());
        objects.createNew(segmentKey(fixture), fixture.segmentImage());
    }
    private static MapObjects objects(Fixture fixture) {
        var objects = new MapObjects();
        objects.images.put(manifestKey(fixture), fixture.manifestImage().clone());
        objects.images.put(segmentKey(fixture), fixture.segmentImage().clone());
        return objects;
    }
    private static void assertReadersClosed(MapObjects objects) {
        objects.readers.forEach(reader -> assertThrows(IllegalStateException.class, reader::reference));
    }

    private static Transaction transaction(String id, ValidTimeInterval interval, long value) {
        return new Transaction(new RequestId(id), List.of(new WriteOperation.Put(SALARY, interval, value)));
    }
    private static CommitPosition position(long sequence) { return new CommitPosition(DATABASE, sequence); }
    private static TemporalResult.Point present(long value) { return new TemporalResult.Point(OptionalLong.of(value)); }
    private static TemporalResult query(LocalDatabase database, long validAt) {
        try (var view = database.openView(ReadCutoff.Head.INSTANCE)) { return view.query(new TemporalQuery.Get(SALARY, validAt)); }
    }
    private static byte[] journalImage(List<JournalRecord> records) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(JournalCodec.encodeHeader(DATABASE));
        records.forEach(record -> output.writeBytes(JournalCodec.encodeRecord(record)));
        return output.toByteArray();
    }
    private static String hash(byte[] image) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
    private static Throwable failure(String kind) {
        return switch (kind) {
            case "io" -> new IOException("object writable boundary failure");
            case "runtime" -> new IllegalStateException("object writable boundary failure");
            case "error" -> new AssertionError("object writable boundary failure");
            default -> throw new AssertionError(kind);
        };
    }
    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException io) { throw io; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
        throw new AssertionError(failure);
    }

    private Child start(Layout layout, Path objects, Boundary boundary) throws IOException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        Path output = directory.resolve("writable-halt-" + boundary + ".log");
        var process = new ProcessBuilder(java.toString(), "-cp", classpath, WritablePinnedProcess.class.getName(),
                layout.home().toAbsolutePath().toString(), objects.toAbsolutePath().toString(), boundary.name())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new Child(process, output);
    }

    private static final class MapObjects implements ImmutableObjectStore, AutoCloseable {
        private final Map<ObjectKey, byte[]> images = new HashMap<>();
        private final List<ObjectKey> reads = new ArrayList<>();
        private final List<VerifiedObject> readers = new ArrayList<>();
        private Throwable failure;
        private int creates;
        private int closes;
        private boolean closed;
        @Override public ObjectReference createNew(ObjectKey key, byte[] image) {
            creates++;
            throw new AssertionError("Pinned writable recovery cannot publish remote immutable objects");
        }
        @Override public VerifiedObject read(ObjectKey key, ObjectReference expected) throws IOException {
            if (closed) { throw new IOException("Caller adapter is closed"); }
            reads.add(key);
            if (failure != null) { throwFailure(failure); }
            byte[] image = images.get(key);
            if (image == null) { throw new NoSuchFileException(key.value()); }
            var reader = VerifiedObject.verify(image, expected);
            readers.add(reader);
            return reader;
        }
        @Override public void close() { closed = true; closes++; images.clear(); }
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final Path output;
        Child(Process process, Path output) { this.process = process; this.output = output; }
        String diagnostics() {
            try (var input = Files.newInputStream(output)) { return new String(input.readNBytes(8192), StandardCharsets.UTF_8); }
            catch (IOException failure) { return "Cannot read child output: " + failure; }
        }
        int awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), () -> "Child did not exit: " + diagnostics());
            return process.exitValue();
        }
        @Override public void close() throws IOException {
            if (process.isAlive()) { process.destroyForcibly(); }
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) { throw new IOException("Writable child did not terminate"); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while terminating writable child", interrupted);
            } finally {
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        }
    }

    /** Only this file's bounded process tests invoke the entry point. */
    public static final class WritablePinnedProcess {
        private WritablePinnedProcess() { }
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            var objects = new LocalObjectStore(Path.of(args[1]));
            var boundary = Boundary.valueOf(args[2]);
            var fixture = fixture();
            try (var database = LocalDatabase.openWritableFromObjectCheckpoint(directory, objects, NAMESPACE, PUBLISHER, head(fixture), DEFAULTS, 2,
                    reached -> {
                        if (reached == boundary) {
                            System.out.println("HALT " + reached);
                            System.out.flush();
                            Runtime.getRuntime().halt(HALT_STATUS);
                        }
                    })) {
                var result = database.transact(CANDIDATE);
                throw new AssertionError("Expected halt before returning " + result);
            } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
                System.out.println("UNSUPPORTED " + unsupported.getMessage());
                System.exit(UNSUPPORTED_STATUS);
            }
        }
    }
}
