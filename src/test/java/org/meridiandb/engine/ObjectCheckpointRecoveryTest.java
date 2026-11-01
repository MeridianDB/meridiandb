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
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalFormatException;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphException;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadException;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ImmutableObjectStore;
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
import org.meridiandb.temporal.TemporalSegment;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class ObjectCheckpointRecoveryTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final UUID OTHER = new UUID(0, 99);
    private static final DatabaseId DATABASE = new DatabaseId("object-recovery-db");
    private static final EntityId SALARY = new EntityId("salary");
    private static final EntityId BONUS = new EntityId("bonus");
    private static final ObjectCheckpointGraphs.Limits DEFAULTS = ObjectCheckpointGraphs.DEFAULT_LIMITS;
    private static final List<JournalRecord> HISTORY = List.of(
            new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 100)))),
            new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(10L, 20L), 120),
                    new WriteOperation.Put(BONUS, new ValidTimeInterval(null, null), 7)))),
            new JournalRecord(3, new Transaction(new RequestId("retraction"), List.of(
                    new WriteOperation.Retract(SALARY, new ValidTimeInterval(12L, 14L))))),
            new JournalRecord(4, new Transaction(new RequestId("same-value-no-op"), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(10L, 12L), 120)))));

    @TempDir Path directory;

    @ParameterizedTest @ValueSource(ints = {0, 2, 4})
    void explicitCheckpointAndOnlyItsJournalSuffixRecoverEveryOriginalCutoffAndRequest(int cutoff) throws IOException {
        var layout = layout(DATABASE, HISTORY, false);
        var fixture = fixture(DATABASE, HISTORY.subList(0, cutoff));
        var objects = objects(fixture);
        var before = snapshot(layout.home());
        try (var database = open(layout, objects, fixture)) {
            assertEquals(position(DATABASE, 4), database.head());
            assertEquals(Optional.of(position(DATABASE, cutoff)), database.checkpointPosition());
            assertCanonicalHistory(database);
            assertEquals(expectedReads(fixture), objects.reads);
            assertEquals(0, objects.creates);
            assertEquals(0, objects.closes);
            assertReadersClosed(objects);
            assertThrows(IllegalStateException.class, () -> database.transact(HISTORY.getFirst().transaction()));
            assertEquals(position(DATABASE, 4), database.head());
        }
        assertFilesEqual(before, snapshot(layout.home()));
        assertOwnershipReleased(layout);
        assertEquals(0, objects.closes);
    }

    @Test void emptyExplicitGenesisRecoversTheStoredLineageWithoutInventingRequests() throws IOException {
        var layout = layout(DATABASE, List.of(), false);
        var fixture = fixture(DATABASE, List.of());
        var objects = objects(fixture);
        try (var database = open(layout, objects, fixture); var view = database.openView(ReadCutoff.Head.INSTANCE)) {
            assertEquals(position(DATABASE, 0), database.head());
            assertEquals(Optional.of(position(DATABASE, 0)), database.checkpointPosition());
            assertEquals(absent(), view.query(new TemporalQuery.Get(SALARY, 0)));
            assertEquals(Optional.empty(), database.lookupRequest(new RequestId("initial")));
            assertEquals(1, objects.reads.size());
        }
        assertOwnershipReleased(layout);
    }

    @Test void unicodeLineageRequestAndEntityRemainExactAcrossObjectPrefixAndLocalTail() throws IOException {
        String exact = "A\u0000é" + (char) 0xd800 + "e" + (char) 0x0301 + (char) 0xdc00;
        var lineage = new DatabaseId(exact);
        var entity = new EntityId(exact);
        var prefix = new JournalRecord(1, new Transaction(new RequestId("é"), List.of(
                new WriteOperation.Put(entity, new ValidTimeInterval(Long.MIN_VALUE, null), Long.MAX_VALUE))));
        var suffix = new JournalRecord(2, new Transaction(new RequestId("e" + (char) 0x0301), List.of(
                new WriteOperation.Retract(new EntityId("missing"), new ValidTimeInterval(null, null)))));
        var records = List.of(prefix, suffix);
        var layout = layout(lineage, records, false);
        var fixture = fixture(lineage, List.of(prefix));
        var objects = objects(fixture);
        try (var database = open(layout, objects, fixture); var view = database.openView(ReadCutoff.Head.INSTANCE)) {
            assertEquals(position(lineage, 2), database.head());
            assertEquals(Optional.of(position(lineage, 1)), database.lookupRequest(prefix.transaction().requestId()));
            assertEquals(Optional.of(position(lineage, 2)), database.lookupRequest(suffix.transaction().requestId()));
            assertEquals(present(Long.MAX_VALUE), view.query(new TemporalQuery.Get(entity, Long.MIN_VALUE)));
        }
        assertOwnershipReleased(layout);
    }

    @Test void explicitOlderPinIgnoresBothNewerAndDamagedLocalCheckpointSelectionWithoutFallback() throws IOException {
        var layout = layout(DATABASE, HISTORY, true);
        var fixture = fixture(DATABASE, HISTORY.subList(0, 2));
        var objects = objects(fixture);
        try (var database = open(layout, objects, fixture)) {
            assertEquals(Optional.of(position(DATABASE, 2)), database.checkpointPosition());
            assertCanonicalHistory(database);
        }
        byte[] damaged = Files.readAllBytes(layout.newestLocalManifest());
        damaged[damaged.length - 1] ^= 1;
        Files.write(layout.newestLocalManifest(), damaged);
        var before = snapshot(layout.home());
        try (var database = open(layout, objects, fixture)) {
            assertEquals(Optional.of(position(DATABASE, 2)), database.checkpointPosition());
            assertCanonicalHistory(database);
        }
        assertFilesEqual(before, snapshot(layout.home()));
        objects.images.remove(manifestKey(fixture));
        assertThrows(NoSuchFileException.class, () -> open(layout, objects, fixture));
        assertOwnershipReleased(layout);
        assertArrayEquals(damaged, Files.readAllBytes(layout.newestLocalManifest()));
    }

    @Test void databaseOwnsBothLocalHandlesButNeverOwnsTheObjectAdapterAndCloseInvalidatesViews() throws IOException {
        var layout = layout(DATABASE, HISTORY, false);
        var fixture = fixture(DATABASE, HISTORY.subList(0, 2));
        var objects = objects(fixture);
        var boundaries = new ArrayList<LocalDatabase.Boundary>();
        var database = LocalDatabase.openFromObjectCheckpoint(layout.home(), objects, NAMESPACE, PUBLISHER, head(fixture), DEFAULTS, 0,
                boundaries::add);
        var view = database.openView(new ReadCutoff.At(position(DATABASE, 1)));
        var answer = view.query(new TemporalQuery.Get(SALARY, 13));
        try {
            assertEquals(List.of(LocalDatabase.Boundary.RECOVERED), boundaries, "read-only recovery never establishes writable acknowledgement");
            assertThrows(IOException.class, () -> {
                try (var unexpected = LocalCheckpointStore.open(layout.home())) { unexpected.journalPath(); }
            });
            assertThrows(IOException.class, () -> {
                try (var unexpected = LocalJournal.open(layout.journalPath())) { unexpected.prefix(0); }
            });
            assertThrows(IOException.class, () -> {
                try (var unexpected = open(layout, objects, fixture)) { unexpected.head(); }
            });
            assertEquals(0, objects.closes);
            assertReadersClosed(objects);
        } finally { database.close(); }
        assertThrows(IllegalStateException.class, database::head);
        assertThrows(IllegalStateException.class, () -> view.query(new TemporalQuery.Get(SALARY, 13)));
        view.close();
        database.close();
        assertEquals(present(100), answer);
        assertOwnershipReleased(layout);
        assertEquals(0, objects.closes);
        try (var reader = objects.read(manifestKey(fixture), ObjectReference.forBytes(fixture.manifestImage()))) {
            assertArrayEquals(fixture.manifestImage(), reader.bytes());
        }
    }

    @Test void closingTheCallerAdapterAfterStartupLeavesRecoveredQueriesUsable() throws IOException {
        var layout = layout(DATABASE, HISTORY, false);
        var fixture = fixture(DATABASE, HISTORY.subList(0, 2));
        var objects = objects(fixture);
        try (var database = open(layout, objects, fixture)) {
            int reads = objects.reads.size();
            objects.close();
            assertCanonicalHistory(database);
            assertEquals(reads, objects.reads.size(), "Recovered queries perform no lazy object access");
            assertEquals(1, objects.closes);
        }
        assertEquals(1, objects.closes, "The database never closes the caller's adapter");
        assertOwnershipReleased(layout);
    }

    @Test void pureInvalidArgumentsRejectBeforeTheDirectoryOrObjectSourceIsTouched() throws IOException {
        Path unopened = Files.createDirectory(directory.resolve("unopened"));
        var fixture = fixture(DATABASE, HISTORY.subList(0, 2));
        var objects = objects(fixture);
        var head = head(fixture);
        assertThrows(NullPointerException.class, () -> LocalDatabase.openFromObjectCheckpoint(null, objects, NAMESPACE, PUBLISHER, head, DEFAULTS));
        assertThrows(NullPointerException.class, () -> LocalDatabase.openFromObjectCheckpoint(unopened, null, NAMESPACE, PUBLISHER, head, DEFAULTS));
        assertThrows(NullPointerException.class, () -> LocalDatabase.openFromObjectCheckpoint(unopened, objects, null, PUBLISHER, head, DEFAULTS));
        assertThrows(NullPointerException.class, () -> LocalDatabase.openFromObjectCheckpoint(unopened, objects, NAMESPACE, null, head, DEFAULTS));
        assertThrows(NullPointerException.class, () -> LocalDatabase.openFromObjectCheckpoint(unopened, objects, NAMESPACE, PUBLISHER, null, DEFAULTS));
        assertThrows(NullPointerException.class, () -> LocalDatabase.openFromObjectCheckpoint(unopened, objects, NAMESPACE, PUBLISHER, head, null));
        assertThrows(IllegalArgumentException.class,
                () -> LocalDatabase.openFromObjectCheckpoint(unopened, objects, new UUID(0, 0), PUBLISHER, head, DEFAULTS));
        assertThrows(IllegalArgumentException.class,
                () -> LocalDatabase.openFromObjectCheckpoint(unopened, objects, NAMESPACE, new UUID(0, 0), head, DEFAULTS));
        assertThrows(IllegalArgumentException.class,
                () -> LocalDatabase.openFromObjectCheckpoint(unopened, objects, NAMESPACE, PUBLISHER, head, DEFAULTS, -1));
        try (var files = Files.list(unopened)) { assertEquals(List.of(), files.toList()); }
        assertTrue(objects.reads.isEmpty());
        assertEquals(0, objects.creates);
        assertEquals(0, objects.closes);
    }

    @ParameterizedTest @ValueSource(strings = {"namespace", "publisher", "lineage"})
    void independentBindingsRejectForeignPinsBeforeObjectReadsAndReleaseAcquiredOwners(String foreign) throws IOException {
        var actualDatabase = foreign.equals("lineage") ? new DatabaseId("foreign-db") : DATABASE;
        var layout = layout(actualDatabase, HISTORY, false);
        var fixture = fixture(DATABASE, HISTORY.subList(0, 2));
        var objects = objects(fixture);
        assertEquals(ObjectCheckpointHeadException.Code.INVALID_BINDING,
                assertThrows(ObjectCheckpointHeadException.class, () -> LocalDatabase.openFromObjectCheckpoint(layout.home(), objects,
                        foreign.equals("namespace") ? OTHER : NAMESPACE, foreign.equals("publisher") ? OTHER : PUBLISHER,
                        head(fixture), DEFAULTS)).code());
        assertTrue(objects.reads.isEmpty());
        assertEquals(0, objects.closes);
        assertOwnershipReleased(layout);
    }

    @Test void validCopiedLineageWithDivergentActualPrefixNeverExposesAnEngine() throws IOException {
        var different = new ArrayList<>(HISTORY);
        different.set(0, new JournalRecord(1, new Transaction(HISTORY.getFirst().transaction().requestId(), List.of(
                new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 999)))));
        var layout = layout(DATABASE, different, false);
        var fixture = fixture(DATABASE, HISTORY.subList(0, 2));
        var objects = objects(fixture);
        var escaped = new AtomicReference<LocalDatabase>();
        assertEquals(ObjectCheckpointGraphException.Code.JOURNAL_MISMATCH,
                assertThrows(ObjectCheckpointGraphException.class, () -> escaped.set(open(layout, objects, fixture))).code());
        assertNull(escaped.get());
        assertEquals(expectedReads(fixture), objects.reads);
        assertReadersClosed(objects);
        assertOwnershipReleased(layout);
        assertEquals(0, objects.closes);
    }

    @ParameterizedTest @ValueSource(strings = {"missing-manifest", "corrupt-segment", "prefix-digest"})
    void graphDamageCannotFallBackToTheValidLocalCheckpointOrReturnPartialQueries(String damage) throws IOException {
        var layout = layout(DATABASE, HISTORY, true);
        Fixture fixture = fixture(DATABASE, HISTORY.subList(0, 2));
        if (damage.equals("corrupt-segment")) {
            byte[] changed = fixture.segmentImage().clone();
            changed[changed.length - 1] ^= 1;
            var id = new SegmentId(hash(changed));
            var manifest = new CheckpointManifest(DATABASE, 17, fixture.manifest().journalPrefix(),
                    List.of(new SegmentReference(id, changed.length, 1, 2)));
            fixture = new Fixture(manifest, ManifestCodec.encode(manifest), id, changed);
        }
        if (damage.equals("prefix-digest")) {
            var old = fixture.manifest().journalPrefix();
            var manifest = new CheckpointManifest(DATABASE, 17, new JournalPrefix(old.sequence(), old.byteLength(), "0".repeat(64)),
                    fixture.manifest().segments());
            fixture = new Fixture(manifest, ManifestCodec.encode(manifest), fixture.segmentId(), fixture.segmentImage());
        }
        var supplied = fixture;
        var objects = objects(supplied);
        if (damage.equals("missing-manifest")) { objects.images.remove(manifestKey(supplied)); }
        var escaped = new AtomicReference<LocalDatabase>();
        var failure = assertThrows(IOException.class, () -> escaped.set(open(layout, objects, supplied)));
        if (damage.equals("missing-manifest")) { assertTrue(failure instanceof NoSuchFileException); }
        else { assertEquals(ObjectCheckpointGraphException.Code.INVALID_GRAPH, ((ObjectCheckpointGraphException) failure).code()); }
        assertNull(escaped.get());
        assertReadersClosed(objects);
        assertEquals(0, objects.closes);
        assertOwnershipReleased(layout);
    }

    @ParameterizedTest @ValueSource(strings = {"io", "runtime", "error"})
    void objectSourceFailuresPreserveTheOriginalThrowableAndReleaseBothLocalOwners(String kind) throws IOException {
        var layout = layout(DATABASE, HISTORY, false);
        var fixture = fixture(DATABASE, HISTORY.subList(0, 2));
        var objects = objects(fixture);
        var expected = failure(kind, "object source");
        objects.failure = expected;
        assertSame(expected, assertThrows(Throwable.class, () -> open(layout, objects, fixture)));
        assertEquals(1, objects.reads.size());
        assertEquals(0, objects.closes);
        assertOwnershipReleased(layout);
    }

    @ParameterizedTest @ValueSource(strings = {"io", "runtime", "error"})
    void failureAfterPrivateReconstructionDiscardsTheDatabaseAndReleasesOwners(String kind) throws IOException {
        var layout = layout(DATABASE, HISTORY, false);
        var fixture = fixture(DATABASE, HISTORY.subList(0, 2));
        var objects = objects(fixture);
        var expected = failure(kind, "recovered probe");
        var escaped = new AtomicReference<LocalDatabase>();
        var seen = new ArrayList<LocalDatabase.Boundary>();
        assertSame(expected, assertThrows(Throwable.class, () -> escaped.set(LocalDatabase.openFromObjectCheckpoint(layout.home(), objects,
                NAMESPACE, PUBLISHER, head(fixture), DEFAULTS, 0, boundary -> {
                    seen.add(boundary);
                    if (boundary == LocalDatabase.Boundary.RECOVERED) { throwFailure(expected); }
                }))));
        assertNull(escaped.get());
        assertEquals(List.of(LocalDatabase.Boundary.RECOVERED), seen);
        assertEquals(expectedReads(fixture), objects.reads);
        assertReadersClosed(objects);
        assertEquals(0, objects.closes);
        assertOwnershipReleased(layout);
    }

    @ParameterizedTest @ValueSource(strings = {"incomplete", "corrupt", "gap", "reused-identity"})
    void invalidJournalTailsRejectWithoutRepairOrObjectFetchAndReleaseFailedOpenOwnership(String damage) throws IOException {
        var layout = layout(DATABASE, HISTORY, false);
        byte[] original = Files.readAllBytes(layout.journalPath());
        byte[] invalid;
        if (damage.equals("incomplete")) { invalid = Arrays.copyOf(original, original.length - 1); }
        else if (damage.equals("corrupt")) { invalid = original.clone(); invalid[invalid.length - 1] ^= 1; }
        else {
            var records = new ArrayList<>(HISTORY);
            if (damage.equals("gap")) {
                records.set(2, new JournalRecord(4, HISTORY.get(2).transaction()));
                records.set(3, new JournalRecord(5, HISTORY.get(3).transaction()));
            } else {
                records.set(2, new JournalRecord(3, new Transaction(HISTORY.getFirst().transaction().requestId(),
                        HISTORY.get(2).transaction().operations())));
            }
            invalid = journalImage(DATABASE, records);
        }
        Files.write(layout.journalPath(), invalid);
        var fixture = fixture(DATABASE, HISTORY.subList(0, 2));
        var objects = objects(fixture);
        Class<? extends Throwable> expected = damage.equals("incomplete") ? LocalJournal.IncompleteTailException.class : JournalFormatException.class;
        assertThrows(expected, () -> open(layout, objects, fixture));
        assertTrue(objects.reads.isEmpty());
        assertEquals(0, objects.closes);
        // Reacquisition reaches the same decoder failure, proving the failed factory released both reservations.
        try (var store = LocalCheckpointStore.open(layout.home())) {
            assertThrows(expected, () -> {
                try (var unexpected = LocalJournal.open(store.journalPath())) { unexpected.prefix(0); }
            });
        }
        assertArrayEquals(invalid, Files.readAllBytes(layout.journalPath()));
        Files.write(layout.journalPath(), original);
        assertOwnershipReleased(layout);
    }

    @Test void missingActualJournalCannotBeReconstructedOrCreatedFromTheObjectCheckpoint() throws IOException {
        var layout = layout(DATABASE, HISTORY, false);
        byte[] original = Files.readAllBytes(layout.journalPath());
        Files.delete(layout.journalPath());
        var fixture = fixture(DATABASE, HISTORY.subList(0, 2));
        var objects = objects(fixture);
        assertThrows(NoSuchFileException.class, () -> open(layout, objects, fixture));
        assertTrue(objects.reads.isEmpty());
        assertFalse(Files.exists(layout.journalPath()));
        try (var store = LocalCheckpointStore.open(layout.home())) {
            assertThrows(NoSuchFileException.class, () -> {
                try (var unexpected = LocalJournal.open(store.journalPath())) { unexpected.prefix(0); }
            });
        }
        Files.write(layout.journalPath(), original);
        assertOwnershipReleased(layout);
        assertEquals(0, objects.closes);
    }

    @Test void independentMinimumDetectsKnownCompleteTailLossWhileDefaultReportsOnlyRecoverableHistory() throws IOException {
        var layout = layout(DATABASE, HISTORY, false);
        Files.write(layout.journalPath(), journalImage(DATABASE, HISTORY.subList(0, 2)));
        var fixture = fixture(DATABASE, HISTORY.subList(0, 2));
        var objects = objects(fixture);
        try (var database = open(layout, objects, fixture)) {
            assertEquals(position(DATABASE, 2), database.head());
            assertEquals(Optional.of(position(DATABASE, 2)), database.checkpointPosition());
            assertEquals(Optional.empty(), database.lookupRequest(HISTORY.get(2).transaction().requestId()));
        }
        try (var database = LocalDatabase.openFromObjectCheckpoint(layout.home(), objects, NAMESPACE, PUBLISHER, head(fixture), DEFAULTS, 2)) {
            assertEquals(position(DATABASE, 2), database.head());
        }
        objects.reads.clear();
        assertThrows(IOException.class,
                () -> LocalDatabase.openFromObjectCheckpoint(layout.home(), objects, NAMESPACE, PUBLISHER, head(fixture), DEFAULTS, 4));
        assertTrue(objects.reads.isEmpty(), "known minimum is admitted before any object retrieval");
        assertEquals(0, objects.closes);
        try (var store = LocalCheckpointStore.open(layout.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertEquals(2, journal.appendedSequence());
        }
    }

    @Test void graphBudgetFailureAlsoReleasesFactoryOwnedResourcesWithoutClosingCallerObjects() throws IOException {
        var layout = layout(DATABASE, HISTORY, false);
        var fixture = fixture(DATABASE, HISTORY.subList(0, 2));
        var objects = objects(fixture);
        var limits = new ObjectCheckpointGraphs.Limits(0, ObjectCheckpointGraphs.MAX_BYTES,
                ObjectCheckpointGraphs.MAX_RECORD_WORK, ObjectCheckpointGraphs.MAX_OBJECT_READS);
        assertEquals(ObjectCheckpointGraphException.Code.LIMIT_EXCEEDED,
                assertThrows(ObjectCheckpointGraphException.class, () -> LocalDatabase.openFromObjectCheckpoint(layout.home(), objects,
                        NAMESPACE, PUBLISHER, head(fixture), limits)).code());
        assertTrue(objects.reads.isEmpty());
        assertEquals(0, objects.closes);
        assertOwnershipReleased(layout);
    }

    private Layout layout(DatabaseId database, List<JournalRecord> records, boolean fullLocalCheckpoint) throws IOException {
        Path home = Files.createDirectory(directory.resolve("database"));
        Path journalPath;
        Path newest = null;
        try (var store = supportedStore(home); var journal = store.initialize(database)) {
            journalPath = journal.path().orElseThrow();
            for (var record : records) { journal.append(record); }
            if (fullLocalCheckpoint) {
                var segment = new ImmutableSegment(database, records);
                var id = store.writeSegment(segment);
                var prefix = journal.prefix(records.size());
                var manifest = new CheckpointManifest(database, 2,
                        new JournalPrefix(prefix.sequence(), prefix.byteLength(), prefix.sha256()),
                        List.of(new SegmentReference(id, SegmentCodec.encode(segment).length, 1, records.size())));
                var manifestId = store.publish(manifest, journal);
                newest = home.resolve(String.format(Locale.ROOT, "checkpoint-%019d-%s.manifest", 2, manifestId.value()));
            }
            journal.force();
        }
        return new Layout(home, journalPath, database, records.size(), newest);
    }

    private static LocalCheckpointStore supportedStore(Path directory) throws IOException {
        try { return LocalCheckpointStore.open(directory); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, () -> "Explicit directory-sync capability unavailable: " + unsupported.getMessage());
            throw unsupported;
        }
    }

    private record Layout(Path home, Path journalPath, DatabaseId database, long sequence, Path newestLocalManifest) { }

    private static LocalDatabase open(Layout layout, ImmutableObjectStore objects, Fixture fixture) throws IOException {
        return LocalDatabase.openFromObjectCheckpoint(layout.home(), objects, NAMESPACE, PUBLISHER, head(fixture), DEFAULTS);
    }

    private static void assertOwnershipReleased(Layout layout) throws IOException {
        try (var store = LocalCheckpointStore.open(layout.home()); var journal = LocalJournal.open(store.journalPath())) {
            assertEquals(layout.database(), journal.databaseId());
            assertEquals(layout.sequence(), journal.appendedSequence());
        }
    }

    private static void assertCanonicalHistory(LocalDatabase database) {
        for (int sequence = 0; sequence <= HISTORY.size(); sequence++) {
            try (var view = database.openView(new ReadCutoff.At(position(DATABASE, sequence)))) {
                assertEquals(position(DATABASE, sequence), view.position());
                assertEquals(sequence == 0 ? absent() : present(sequence >= 2 ? 120 : 100),
                        view.query(new TemporalQuery.Get(SALARY, 11)));
                assertEquals(sequence == 0 || sequence >= 3 ? absent() : present(sequence == 1 ? 100 : 120),
                        view.query(new TemporalQuery.Get(SALARY, 13)));
                assertEquals(sequence >= 2 ? present(7) : absent(), view.query(new TemporalQuery.Get(BONUS, 13)));
            }
        }
        for (var record : HISTORY) {
            assertEquals(Optional.of(position(DATABASE, record.sequence())), database.lookupRequest(record.transaction().requestId()));
        }
        assertEquals(Optional.empty(), database.lookupRequest(new RequestId("unseen")));
        try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
            assertEquals(new TemporalResult.Segments(List.of(
                    new TemporalSegment(new ValidTimeInterval(8L, 10L), 100),
                    new TemporalSegment(new ValidTimeInterval(10L, 12L), 120),
                    new TemporalSegment(new ValidTimeInterval(14L, 20L), 120),
                    new TemporalSegment(new ValidTimeInterval(20L, 22L), 100))),
                    view.query(new TemporalQuery.Scan(SALARY, new ValidTimeInterval(8L, 22L))));
            assertEquals(new TemporalResult.Aggregate(1, BigInteger.valueOf(7)),
                    view.query(new TemporalQuery.Sum(List.of(SALARY, BONUS), 13)));
        }
    }

    private static TemporalResult.Point present(long value) { return new TemporalResult.Point(OptionalLong.of(value)); }
    private static TemporalResult.Point absent() { return new TemporalResult.Point(OptionalLong.empty()); }
    private static CommitPosition position(DatabaseId database, long sequence) { return new CommitPosition(database, sequence); }

    private static Fixture fixture(DatabaseId database, List<JournalRecord> records) {
        byte[] prefix = journalImage(database, records);
        SegmentId segmentId = null;
        byte[] segmentImage = null;
        List<SegmentReference> references = List.of();
        if (!records.isEmpty()) {
            segmentImage = SegmentCodec.encode(new ImmutableSegment(database, records));
            segmentId = new SegmentId(hash(segmentImage));
            references = List.of(new SegmentReference(segmentId, segmentImage.length, 1, records.size()));
        }
        var manifest = new CheckpointManifest(database, 17, new JournalPrefix(records.size(), prefix.length, hash(prefix)), references);
        return new Fixture(manifest, ManifestCodec.encode(manifest), segmentId, segmentImage);
    }

    private record Fixture(CheckpointManifest manifest, byte[] manifestImage, SegmentId segmentId, byte[] segmentImage) { }

    private static ObjectCheckpointHead head(Fixture fixture) {
        return new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 3),
                ObjectCheckpointHeadVerifier.lineageId(fixture.manifest().databaseId()), 1,
                ObjectReference.forBytes(fixture.manifestImage()), fixture.manifest().journalPrefix(), null);
    }

    private static ObjectKey manifestKey(Fixture fixture) {
        return ObjectCheckpointGraphs.manifestKey(NAMESPACE, ObjectReference.forBytes(fixture.manifestImage()).id());
    }
    private static ObjectKey segmentKey(Fixture fixture) { return ObjectCheckpointGraphs.segmentKey(NAMESPACE, fixture.segmentId()); }

    private static List<ObjectKey> expectedReads(Fixture fixture) {
        return fixture.segmentId() == null ? List.of(manifestKey(fixture)) : List.of(manifestKey(fixture), segmentKey(fixture));
    }

    private static MapObjects objects(Fixture fixture) {
        var objects = new MapObjects();
        objects.images.put(manifestKey(fixture), fixture.manifestImage().clone());
        if (fixture.segmentId() != null) { objects.images.put(segmentKey(fixture), fixture.segmentImage().clone()); }
        return objects;
    }

    private static byte[] journalImage(DatabaseId database, List<JournalRecord> records) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(database));
        records.forEach(record -> bytes.writeBytes(JournalCodec.encodeRecord(record)));
        return bytes.toByteArray();
    }

    private static String hash(byte[] image) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    private static Map<String, byte[]> snapshot(Path directory) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var paths = Files.list(directory)) {
            for (var path : paths.toList()) { result.put(path.getFileName().toString(), Files.readAllBytes(path)); }
        }
        return result;
    }
    private static void assertFilesEqual(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, bytes) -> assertArrayEquals(bytes, actual.get(name), name));
    }

    private static void assertReadersClosed(MapObjects objects) {
        objects.readers.forEach(reader -> assertThrows(IllegalStateException.class, reader::reference));
    }

    private static Throwable failure(String kind, String stage) {
        return switch (kind) {
            case "io" -> new IOException(stage);
            case "runtime" -> new IllegalStateException(stage);
            case "error" -> new AssertionError(stage);
            default -> throw new AssertionError(kind);
        };
    }
    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException io) { throw io; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
        throw new AssertionError(failure);
    }

    private static final class MapObjects implements ImmutableObjectStore, AutoCloseable {
        private final Map<ObjectKey, byte[]> images = new HashMap<>();
        private final List<ObjectKey> reads = new ArrayList<>();
        private final List<VerifiedObject> readers = new ArrayList<>();
        private Throwable failure;
        private int creates;
        private int closes;

        @Override public ObjectReference createNew(ObjectKey key, byte[] image) {
            creates++;
            throw new AssertionError("Object checkpoint recovery cannot write immutable objects");
        }
        @Override public VerifiedObject read(ObjectKey key, ObjectReference expected) throws IOException {
            reads.add(key);
            if (failure != null) { throwFailure(failure); }
            byte[] image = images.get(key);
            if (image == null) { throw new NoSuchFileException(key.value()); }
            var reader = VerifiedObject.verify(image, expected);
            readers.add(reader);
            return reader;
        }
        @Override public void close() { closes++; }
    }
}
