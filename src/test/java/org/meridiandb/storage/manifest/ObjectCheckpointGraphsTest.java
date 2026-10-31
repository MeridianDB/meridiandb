// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphException.Code;
import org.meridiandb.storage.object.ImmutableObjectStore;
import org.meridiandb.storage.object.LocalObjectStore;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectIntegrityException;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.object.VerifiedObject;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentFormatException;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class ObjectCheckpointGraphsTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final UUID OTHER = new UUID(0, 99);
    private static final DatabaseId DATABASE = new DatabaseId("graph-db");
    private static final JournalRecord FIRST = record(1, "first!", 100);
    private static final JournalRecord SECOND = record(2, "second", 120);
    private static final JournalRecord THIRD = record(3, "third!", 140);
    private static final ObjectId ZERO = new ObjectId("0".repeat(64));
    private static final ObjectCheckpointGraphs.Limits DEFAULTS = ObjectCheckpointGraphs.DEFAULT_LIMITS;

    @TempDir Path directory;
    private int journalNumber;

    @Test void emptyGraphVerifiesTheOwnedHeaderWithoutForceOrObjectCreation() throws IOException {
        var fixture = fixture(17, List.of());
        var objects = objects(fixture);
        try (var journal = journal()) {
            long forcedBytes = journal.forcedBytes();
            var verified = verifier(objects, journal).verify(first(fixture), exact(fixture));
            assertEquals(1, verified.graphs().size());
            assertGraph(verified.graphs().getFirst(), first(fixture), fixture.manifest());
            assertUsage(verified.usage(), fixture.objectBytes(), fixture.prefix().byteLength(), 0, 1, 1);
            assertEquals(List.of(manifestKey(first(fixture))), objects.keysRead());
            assertEquals(0, journal.forcedSequence());
            assertEquals(forcedBytes, journal.forcedBytes());
            assertEquals(0, objects.creates);
            assertReadersClosed(objects);
        }
    }

    @Test void oneSegmentPreservesExactHeadManifestAndActualPrefixEvidence() throws IOException {
        var fixture = fixture(23, List.of(List.of(FIRST, SECOND)));
        var head = first(fixture);
        var objects = objects(fixture);
        try (var journal = journal(FIRST, SECOND)) {
            long forcedBytes = journal.forcedBytes();
            var result = verifier(objects, journal).verify(head, exact(fixture));
            assertGraph(result.graphs().getFirst(), head, fixture.manifest());
            assertEquals(1, head.revision());
            assertEquals(23, result.graphs().getFirst().manifest().generation());
            assertUsage(result.usage(), fixture.objectBytes(), fixture.prefix().byteLength(), 4, 2, 1);
            assertEquals(0, journal.forcedSequence());
            assertEquals(forcedBytes, journal.forcedBytes());
            journal.append(THIRD);
            assertEquals(3, journal.appendedSequence());
            assertEquals(0, objects.creates);
            assertReadersClosed(objects);
        }
    }

    @Test void multipleSegmentsAndRepackingVerifyInPredecessorOrder() throws IOException {
        var before = fixture(17, List.of(List.of(FIRST), List.of(SECOND)));
        var after = fixture(7, List.of(List.of(FIRST, SECOND)));
        var previous = first(before);
        var candidate = next(previous, after);
        var objects = objects(before, after);
        try (var journal = journal(FIRST, SECOND)) {
            var result = verifier(objects, journal).verifySuccessor(previous, candidate, exact(before, after));
            assertEquals(2, result.graphs().size());
            assertGraph(result.graphs().get(0), previous, before.manifest());
            assertGraph(result.graphs().get(1), candidate, after.manifest());
            assertEquals(result.graphs().get(0).actualPrefix(), result.graphs().get(1).actualPrefix());
            assertNotEquals(before.manifest().generation(), candidate.revision());
            assertUsage(result.usage(), before.objectBytes() + after.objectBytes(),
                    2 * before.prefix().byteLength(), 8, 5, 2);
            assertEquals(List.of(manifestKey(previous), manifestKey(candidate),
                    segmentKey(before.manifest().segments().get(0).id()),
                    segmentKey(before.manifest().segments().get(1).id()),
                    segmentKey(after.manifest().segments().getFirst().id())), objects.keysRead());
            assertReadersClosed(objects);
        }
    }

    @Test void unchangedPairChargesRepeatedManifestAndSegmentWorkTwice() throws IOException {
        var fixture = fixture(17, List.of(List.of(FIRST, SECOND)));
        var previous = first(fixture);
        var candidate = next(previous, fixture);
        var objects = objects(fixture);
        try (var journal = journal(FIRST, SECOND)) {
            var result = verifier(objects, journal).verifySuccessor(previous, candidate, exact(fixture, fixture));
            assertUsage(result.usage(), 2 * fixture.objectBytes(), 2 * fixture.prefix().byteLength(), 8, 4, 2);
            assertEquals(2, Collections.frequency(objects.keysRead(), manifestKey(previous)));
            assertEquals(2, Collections.frequency(objects.keysRead(), segmentKey(fixture.manifest().segments().getFirst().id())));
            assertEquals(previous.manifest(), candidate.manifest());
            assertNotEquals(result.graphs().get(0).headReference(), result.graphs().get(1).headReference());
            assertTrue(objects.keysRead().stream().noneMatch(key -> key.value().contains("-head-")));
            assertReadersClosed(objects);
        }
    }

    @Test void successfulResultsOutliveSourcesAndExposeImmutableLists() throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST)));
        var head = first(fixture);
        var objects = objects(fixture);
        ObjectCheckpointGraphs.Verification result;
        try (var journal = journal(FIRST)) {
            result = verifier(objects, journal).verify(head, DEFAULTS);
            assertReadersClosed(objects);
        }
        objects.images.clear();
        assertEquals(head, result.graphs().getFirst().head());
        assertEquals(fixture.manifest(), result.graphs().getFirst().manifest());
        assertEquals(fixture.prefix().sha256(), result.graphs().getFirst().actualPrefix().sha256());
        assertEquals(ObjectCheckpointHeadCodec.reference(head), result.graphs().getFirst().headReference());
        assertThrows(UnsupportedOperationException.class, () -> result.graphs().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.graphs().getFirst().manifest().segments().clear());
    }

    @Test void headKnownSingleGraphLimitsFailBeforeTheFirstObjectFetch() throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST, SECOND)));
        var head = first(fixture);
        var objects = objects(fixture);
        try (var journal = journal(FIRST, SECOND)) {
            for (var limits : List.of(
                    new ObjectCheckpointGraphs.Limits(fixture.manifestImage().length - 1L, 1_000_000, 100, 100),
                    new ObjectCheckpointGraphs.Limits(1_000_000, fixture.prefix().byteLength() - 1, 100, 100),
                    new ObjectCheckpointGraphs.Limits(1_000_000, 1_000_000, 3, 100),
                    new ObjectCheckpointGraphs.Limits(1_000_000, 1_000_000, 100, 0))) {
                assertGraphFailure(Code.LIMIT_EXCEEDED, () -> verifier(objects, journal).verify(head, limits));
                assertTrue(objects.reads.isEmpty());
            }
            assertGraph(verifier(objects, journal).verify(head, exact(fixture)).graphs().getFirst(), head, fixture.manifest());
        }
    }

    @Test void headKnownPairTotalsFailBeforeFetchingEitherManifest() throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST, SECOND)));
        var previous = first(fixture);
        var candidate = next(previous, fixture);
        var objects = objects(fixture);
        try (var journal = journal(FIRST, SECOND)) {
            for (var limits : List.of(
                    new ObjectCheckpointGraphs.Limits(fixture.manifestImage().length, 1_000_000, 100, 100),
                    new ObjectCheckpointGraphs.Limits(1_000_000, fixture.prefix().byteLength(), 100, 100),
                    new ObjectCheckpointGraphs.Limits(1_000_000, 1_000_000, 4, 100),
                    new ObjectCheckpointGraphs.Limits(1_000_000, 1_000_000, 100, 1))) {
                assertGraphFailure(Code.LIMIT_EXCEEDED,
                        () -> verifier(objects, journal).verifySuccessor(previous, candidate, limits));
                assertTrue(objects.reads.isEmpty());
            }
        }
    }

    @Test void allSegmentTotalsAreReservedBeforeFetchingAnySegment() throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST), List.of(SECOND)));
        var head = first(fixture);
        for (var limits : List.of(
                new ObjectCheckpointGraphs.Limits(fixture.objectBytes() - 1, 1_000_000, 100, 100),
                new ObjectCheckpointGraphs.Limits(1_000_000, 1_000_000, 100, 2))) {
            var objects = objects(fixture);
            try (var journal = journal(FIRST, SECOND)) {
                assertGraphFailure(Code.LIMIT_EXCEEDED, () -> verifier(objects, journal).verify(head, limits));
                assertEquals(List.of(manifestKey(head)), objects.keysRead());
                assertReadersClosed(objects);
            }
        }
    }

    @Test void pairDependencyBudgetCountsRepeatedBodiesBeforeEitherGraphScan() throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST)));
        var previous = first(fixture);
        var candidate = next(previous, fixture);
        var objects = objects(fixture);
        try (var journal = journal(FIRST)) {
            var limits = new ObjectCheckpointGraphs.Limits(2 * fixture.objectBytes() - 1, 1_000_000, 100, 100);
            assertGraphFailure(Code.LIMIT_EXCEEDED,
                    () -> verifier(objects, journal).verifySuccessor(previous, candidate, limits));
            assertEquals(List.of(manifestKey(previous), manifestKey(candidate)), objects.keysRead());
            assertReadersClosed(objects);
        }
    }

    @Test void independentNamespacePublisherAndOwnedLineageBindBeforeFetches() throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST)));
        var head = first(fixture);
        var objects = objects(fixture);
        try (var journal = journal(FIRST)) {
            assertHeadFailure(ObjectCheckpointHeadException.Code.INVALID_BINDING,
                    () -> new ObjectCheckpointGraphs(objects, journal, OTHER, PUBLISHER).verify(head, DEFAULTS));
            assertHeadFailure(ObjectCheckpointHeadException.Code.INVALID_BINDING,
                    () -> new ObjectCheckpointGraphs(objects, journal, NAMESPACE, OTHER).verify(head, DEFAULTS));
            var wrongLineage = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, head.publicationId(), ZERO,
                    1, head.manifest(), head.journalPrefix(), null);
            assertHeadFailure(ObjectCheckpointHeadException.Code.INVALID_BINDING,
                    () -> verifier(objects, journal).verify(wrongLineage, DEFAULTS));
            assertTrue(objects.reads.isEmpty());
        }
        try (var foreign = journal(new DatabaseId("other-db"), FIRST)) {
            assertHeadFailure(ObjectCheckpointHeadException.Code.INVALID_BINDING,
                    () -> verifier(objects, foreign).verify(head, DEFAULTS));
            assertTrue(objects.reads.isEmpty());
        }
    }

    @Test void nonadjacentOrWrongPredecessorCandidatesFailWithoutObjectReads() throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST)));
        var previous = first(fixture);
        var objects = objects(fixture);
        try (var journal = journal(FIRST)) {
            assertHeadFailure(ObjectCheckpointHeadException.Code.INVALID_TRANSITION,
                    () -> verifier(objects, journal).verifySuccessor(previous, previous, DEFAULTS));
            var wrong = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 11), previous.lineageId(),
                    2, previous.manifest(), previous.journalPrefix(), new ObjectReference(ZERO, 256));
            assertHeadFailure(ObjectCheckpointHeadException.Code.INVALID_TRANSITION,
                    () -> verifier(objects, journal).verifySuccessor(previous, wrong, DEFAULTS));
            assertTrue(objects.reads.isEmpty());
        }
    }

    @Test void aHeadBeyondOwnedHistoryFailsBeforeObjectFetches() throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST, SECOND)));
        var objects = objects(fixture);
        try (var journal = journal(FIRST)) {
            assertGraphFailure(Code.JOURNAL_MISMATCH, () -> verifier(objects, journal).verify(first(fixture), DEFAULTS));
            assertTrue(objects.reads.isEmpty());
            assertEquals(1, journal.prefix(1, Long.MAX_VALUE).sequence());
        }
    }

    @Test void copiedSameLineageDivergenceRejectsSelectedGraphEvenWhenCandidateMatchesLocal() throws IOException {
        var selected = fixture(2, List.of(List.of(FIRST)));
        var differentFirst = record(1, "first!", 999);
        var localCandidate = fixture(3, List.of(List.of(differentFirst, SECOND)));
        var previous = first(selected);
        var candidate = next(previous, localCandidate);
        ObjectCheckpointHeadVerifier.requireSuccessor(previous, candidate);
        CheckpointVerifier.verify(selected.manifest(), id -> selected.segments().get(id));
        CheckpointVerifier.verify(localCandidate.manifest(), id -> localCandidate.segments().get(id));
        var objects = objects(selected, localCandidate);
        try (var journal = journal(differentFirst, SECOND)) {
            assertGraphFailure(Code.JOURNAL_MISMATCH,
                    () -> verifier(objects, journal).verifySuccessor(previous, candidate, DEFAULTS));
            assertEquals(List.of(manifestKey(previous), manifestKey(candidate),
                    segmentKey(selected.manifest().segments().getFirst().id())), objects.keysRead());
            assertFalse(objects.keysRead().contains(segmentKey(localCandidate.manifest().segments().getFirst().id())));
            assertEquals(localCandidate.prefix().sha256(), journal.prefix(2, Long.MAX_VALUE).sha256());
            assertEquals(0, journal.forcedSequence());
            assertReadersClosed(objects);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void differentActualPrefixLengthRejectsWithoutPoisoningHealthyOwner(boolean actualLonger) throws IOException {
        var shortRecord = record(1, "r1", 100);
        var longRecord = record(1, "r1-with-a-longer-identity", 100);
        var remote = fixture(2, List.of(List.of(actualLonger ? shortRecord : longRecord)));
        var actual = actualLonger ? longRecord : shortRecord;
        var objects = objects(remote);
        try (var journal = journal(actual)) {
            var failure = assertGraphFailure(Code.JOURNAL_MISMATCH,
                    () -> verifier(objects, journal).verify(first(remote), DEFAULTS));
            if (actualLonger) { assertTrue(failure.getCause() instanceof LocalJournal.PrefixLimitException); }
            assertEquals(hash(journalImage(DATABASE, List.of(actual))), journal.prefix(1, Long.MAX_VALUE).sha256());
            journal.append(SECOND);
            assertEquals(2, journal.appendedSequence());
            assertEquals(0, journal.forcedSequence());
        }
    }

    @Test void missingSelectedManifestNeverFallsBackToAValidCandidate() throws IOException {
        var before = fixture(2, List.of(List.of(FIRST)));
        var after = fixture(3, List.of(List.of(FIRST, SECOND)));
        var previous = first(before);
        var candidate = next(previous, after);
        var objects = objects(before, after);
        objects.images.remove(manifestKey(previous));
        try (var journal = journal(FIRST, SECOND)) {
            assertThrows(NoSuchFileException.class,
                    () -> verifier(objects, journal).verifySuccessor(previous, candidate, DEFAULTS));
            assertEquals(List.of(manifestKey(previous)), objects.keysRead());
            assertEquals(0, objects.creates);
        }
    }

    @Test void absentSegmentsPreserveTheAdapterFailureAndCloseEarlierReaders() throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST), List.of(SECOND)));
        var objects = objects(fixture);
        objects.images.remove(segmentKey(fixture.manifest().segments().get(1).id()));
        try (var journal = journal(FIRST, SECOND)) {
            assertThrows(NoSuchFileException.class, () -> verifier(objects, journal).verify(first(fixture), DEFAULTS));
            assertReadersClosed(objects);
            assertEquals(2, journal.prefix(2, Long.MAX_VALUE).sequence());
        }
    }

    @Test void adapterIoIsNotReclassifiedAsAbsenceOrAnInvalidGraph() throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST)));
        var objects = objects(fixture);
        var injected = new IOException("independent adapter failure");
        objects.failure = injected;
        objects.failureKey = manifestKey(first(fixture));
        try (var journal = journal(FIRST)) {
            assertSame(injected, assertThrows(IOException.class,
                    () -> verifier(objects, journal).verify(first(fixture), DEFAULTS)));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void changedManifestOrSegmentContentFailsIndependentExpectedIdentity(boolean segment) throws IOException {
        var fixture = fixture(2, List.of(List.of(FIRST)));
        var objects = objects(fixture);
        var key = segment ? segmentKey(fixture.manifest().segments().getFirst().id()) : manifestKey(first(fixture));
        byte[] image = objects.images.get(key).clone();
        image[image.length - 1] ^= 1;
        objects.images.put(key, image);
        try (var journal = journal(FIRST)) {
            var failure = assertThrows(ObjectIntegrityException.class,
                    () -> verifier(objects, journal).verify(first(fixture), DEFAULTS));
            assertEquals(ObjectIntegrityException.Code.ID_MISMATCH, failure.code());
            assertReadersClosed(objects);
        }
    }

    @Test void validContentHashDoesNotMakeMalformedBytesAHistorySegment() throws IOException {
        byte[] malformed = {1, 2, 3};
        var reference = ObjectReference.forBytes(malformed);
        var id = new SegmentId(reference.id().value());
        var prefix = prefix(DATABASE, List.of(FIRST));
        var fixture = declared(DATABASE, 2, prefix,
                List.of(new SegmentReference(id, malformed.length, 1, 1)), Map.of(id, malformed));
        var objects = objects(fixture);
        try (var journal = journal(FIRST)) {
            var failure = assertGraphFailure(Code.INVALID_GRAPH,
                    () -> verifier(objects, journal).verify(first(fixture), DEFAULTS));
            assertTrue(failure.getCause() instanceof SegmentFormatException);
            assertReadersClosed(objects);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void segmentLineageAndDeclaredSequenceRangeMustMatch(boolean wrongLineage) throws IOException {
        var segment = new ImmutableSegment(wrongLineage ? new DatabaseId("other-db") : DATABASE,
                List.of(wrongLineage ? FIRST : record(2, "first!", 100)));
        byte[] bytes = SegmentCodec.encode(segment);
        var id = new SegmentId(hash(bytes));
        var fixture = declared(DATABASE, 2, prefix(DATABASE, List.of(FIRST)),
                List.of(new SegmentReference(id, bytes.length, 1, 1)), Map.of(id, bytes));
        var objects = objects(fixture);
        try (var journal = journal(FIRST)) {
            assertGraphFailure(Code.INVALID_GRAPH, () -> verifier(objects, journal).verify(first(fixture), DEFAULTS));
            assertReadersClosed(objects);
        }
    }

    @Test void duplicateRequestsAcrossIndividuallyValidSegmentsFailBeforeOwnerComparison() throws IOException {
        var duplicate = fixture(2, List.of(List.of(record(1, "same-id", 100)), List.of(record(2, "same-id", 120))));
        var objects = objects(duplicate);
        try (var journal = journal(FIRST, SECOND)) {
            var failure = assertGraphFailure(Code.INVALID_GRAPH,
                    () -> verifier(objects, journal).verify(first(duplicate), DEFAULTS));
            assertTrue(failure.getCause() instanceof ManifestFormatException);
            assertEquals(ManifestFormatException.Code.INVALID_CHECKPOINT,
                    ((ManifestFormatException) failure.getCause()).code());
            assertReadersClosed(objects);
        }
    }

    @Test void reconstructedPrefixMustMatchDeclarationsEvenBeforeActualJournalComparison() throws IOException {
        var original = fixture(2, List.of(List.of(FIRST)));
        var wrongPrefix = new JournalPrefix(1, original.prefix().byteLength(), ZERO.value());
        var fixture = declared(DATABASE, 2, wrongPrefix, original.manifest().segments(), original.segments());
        var objects = objects(fixture);
        try (var journal = journal(FIRST)) {
            var failure = assertGraphFailure(Code.INVALID_GRAPH,
                    () -> verifier(objects, journal).verify(first(fixture), DEFAULTS));
            assertTrue(failure.getCause() instanceof ManifestFormatException);
            assertEquals(original.prefix().sha256(), journal.prefix(1, Long.MAX_VALUE).sha256());
        }
    }

    @Test void referencedManifestMetadataMustMatchTheSuppliedHead() throws IOException {
        var original = fixture(2, List.of(List.of(FIRST)));
        var other = fixture(3, List.of(List.of(FIRST, SECOND)));
        var originalHead = first(original);
        var head = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, originalHead.publicationId(), originalHead.lineageId(),
                1, ObjectReference.forBytes(other.manifestImage()), originalHead.journalPrefix(), null);
        var objects = objects(other);
        try (var journal = journal(FIRST, SECOND)) {
            var failure = assertGraphFailure(Code.INVALID_GRAPH, () -> verifier(objects, journal).verify(head, DEFAULTS));
            assertTrue(failure.getCause() instanceof ObjectCheckpointHeadException);
            assertEquals(ObjectCheckpointHeadException.Code.INVALID_MANIFEST,
                    ((ObjectCheckpointHeadException) failure.getCause()).code());
            assertEquals(List.of(manifestKey(head)), objects.keysRead());
            assertReadersClosed(objects);
        }
    }

    @Test void reservedKeysFollowTheProtocolAndAreScopedToIndependentNamespaces() {
        var id = new ObjectId("abcdef0123456789".repeat(4));
        var segment = new SegmentId(id.value());
        assertEquals("ns-00000000000000000000000000000001-manifest-" + id.value() + ".bin",
                ObjectCheckpointGraphs.manifestKey(NAMESPACE, id).value());
        assertEquals("ns-00000000000000000000000000000001-segment-" + id.value() + ".bin",
                ObjectCheckpointGraphs.segmentKey(NAMESPACE, segment).value());
        assertNotEquals(ObjectCheckpointGraphs.manifestKey(NAMESPACE, id), ObjectCheckpointGraphs.manifestKey(OTHER, id));
        assertNotEquals(ObjectCheckpointGraphs.segmentKey(NAMESPACE, segment), ObjectCheckpointGraphs.segmentKey(OTHER, segment));
    }

    @Test void localObjectAdapterAndOwnedJournalVerifyTheSameCompleteGraph() throws IOException {
        var fixture = fixture(19, List.of(List.of(FIRST), List.of(SECOND)));
        var local = new LocalObjectStore(Files.createDirectory(directory.resolve("objects")));
        install(local, fixture);
        try (var journal = journal(FIRST, SECOND)) {
            var result = verifier(local, journal).verify(first(fixture), exact(fixture));
            assertGraph(result.graphs().getFirst(), first(fixture), fixture.manifest());
            assertUsage(result.usage(), fixture.objectBytes(), fixture.prefix().byteLength(), 4, 3, 1);
            assertEquals(0, journal.forcedSequence());
            assertEquals(2, journal.appendedSequence());
        }
    }

    private static ObjectCheckpointGraphs verifier(ImmutableObjectStore objects, LocalJournal journal) {
        return new ObjectCheckpointGraphs(objects, journal, NAMESPACE, PUBLISHER);
    }

    private LocalJournal journal(JournalRecord... records) throws IOException { return journal(DATABASE, records); }

    private LocalJournal journal(DatabaseId database, JournalRecord... records) throws IOException {
        var journal = LocalJournal.create(directory.resolve("journal-" + journalNumber++), database);
        try {
            for (var record : records) { journal.append(record); }
            return journal;
        } catch (IOException | RuntimeException | Error failure) {
            try { journal.close(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static ObjectCheckpointHead first(Fixture fixture) {
        return new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 10),
                ObjectCheckpointHeadVerifier.lineageId(fixture.manifest().databaseId()), 1,
                ObjectReference.forBytes(fixture.manifestImage()), fixture.prefix(), null);
    }

    private static ObjectCheckpointHead next(ObjectCheckpointHead previous, Fixture fixture) {
        return new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 11), previous.lineageId(), previous.revision() + 1,
                ObjectReference.forBytes(fixture.manifestImage()), fixture.prefix(), ObjectCheckpointHeadCodec.reference(previous));
    }

    private static ObjectCheckpointGraphs.Limits exact(Fixture... fixtures) {
        long objectBytes = 0;
        long journalBytes = 0;
        long recordWork = 0;
        int reads = 0;
        for (var fixture : fixtures) {
            objectBytes += fixture.objectBytes();
            journalBytes += fixture.prefix().byteLength();
            recordWork += 2 * fixture.prefix().sequence();
            reads += 1 + fixture.manifest().segments().size();
        }
        return new ObjectCheckpointGraphs.Limits(objectBytes, journalBytes, recordWork, reads);
    }

    private static void assertGraph(ObjectCheckpointGraphs.Graph graph, ObjectCheckpointHead head, CheckpointManifest manifest) {
        assertEquals(head, graph.head());
        assertEquals(manifest, graph.manifest());
        assertEquals(head.journalPrefix().sequence(), graph.actualPrefix().sequence());
        assertEquals(head.journalPrefix().byteLength(), graph.actualPrefix().byteLength());
        assertEquals(head.journalPrefix().sha256(), graph.actualPrefix().sha256());
        assertEquals(ObjectCheckpointHeadCodec.reference(head), graph.headReference());
    }

    private static void assertUsage(ObjectCheckpointGraphs.Usage usage, long objects, long journal,
                                   long records, int objectReads, int journalReads) {
        assertEquals(objects, usage.objectBytes());
        assertEquals(journal, usage.journalBytes());
        assertEquals(records, usage.recordWork());
        assertEquals(objectReads, usage.objectReads());
        assertEquals(journalReads, usage.journalReads());
    }

    private static ObjectCheckpointGraphException assertGraphFailure(Code code, Throwing work) {
        var failure = assertThrows(ObjectCheckpointGraphException.class, work::run);
        assertEquals(code, failure.code());
        return failure;
    }

    private static void assertHeadFailure(ObjectCheckpointHeadException.Code code, Throwing work) {
        assertEquals(code, assertThrows(ObjectCheckpointHeadException.class, work::run).code());
    }

    private static void assertReadersClosed(MapObjects objects) {
        for (var reader : objects.readers) { assertThrows(IllegalStateException.class, reader::bytes); }
    }

    @FunctionalInterface private interface Throwing { void run() throws IOException; }

    private static Fixture fixture(long generation, List<List<JournalRecord>> groups) {
        var references = new ArrayList<SegmentReference>();
        var segments = new LinkedHashMap<SegmentId, byte[]>();
        var records = new ArrayList<JournalRecord>();
        for (var group : groups) {
            var segment = new ImmutableSegment(DATABASE, group);
            byte[] bytes = SegmentCodec.encode(segment);
            var id = new SegmentId(hash(bytes));
            segments.put(id, bytes);
            references.add(new SegmentReference(id, bytes.length, segment.firstSequence(), segment.lastSequence()));
            records.addAll(group);
        }
        return declared(DATABASE, generation, prefix(DATABASE, records), references, segments);
    }

    private static Fixture declared(DatabaseId database, long generation, JournalPrefix prefix,
                                    List<SegmentReference> references, Map<SegmentId, byte[]> segments) {
        var manifest = new CheckpointManifest(database, generation, prefix, references);
        return new Fixture(manifest, ManifestCodec.encode(manifest), segments);
    }

    private static JournalPrefix prefix(DatabaseId database, List<JournalRecord> records) {
        byte[] bytes = journalImage(database, records);
        return new JournalPrefix(records.size(), bytes.length, hash(bytes));
    }

    private static byte[] journalImage(DatabaseId database, List<JournalRecord> records) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(JournalCodec.encodeHeader(database));
        for (var record : records) { output.writeBytes(JournalCodec.encodeRecord(record)); }
        return output.toByteArray();
    }

    private static JournalRecord record(long sequence, String request, long value) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(
                new WriteOperation.Put(new EntityId("salary"), new ValidTimeInterval(null, null), value))));
    }

    private record Fixture(CheckpointManifest manifest, byte[] manifestImage, Map<SegmentId, byte[]> segments) {
        JournalPrefix prefix() { return manifest.journalPrefix(); }
        long objectBytes() {
            return manifestImage.length + manifest.segments().stream().mapToLong(SegmentReference::byteLength).sum();
        }
    }

    private static String namespacePrefix() { return "ns-00000000000000000000000000000001"; }
    private static ObjectKey manifestKey(ObjectCheckpointHead head) {
        return new ObjectKey(namespacePrefix() + "-manifest-" + head.manifest().id().value() + ".bin");
    }
    private static ObjectKey segmentKey(SegmentId id) { return new ObjectKey(namespacePrefix() + "-segment-" + id.value() + ".bin"); }

    private static MapObjects objects(Fixture... fixtures) {
        var objects = new MapObjects();
        for (var fixture : fixtures) {
            objects.put(manifestKey(first(fixture)), fixture.manifestImage());
            fixture.segments().forEach((id, bytes) -> objects.put(segmentKey(id), bytes));
        }
        return objects;
    }

    private static void install(ImmutableObjectStore objects, Fixture fixture) throws IOException {
        objects.createNew(manifestKey(first(fixture)), fixture.manifestImage());
        for (var entry : fixture.segments().entrySet()) { objects.createNew(segmentKey(entry.getKey()), entry.getValue()); }
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    private record Read(ObjectKey key, ObjectReference expected) { }

    private static final class MapObjects implements ImmutableObjectStore {
        final Map<ObjectKey, byte[]> images = new HashMap<>();
        final List<Read> reads = new ArrayList<>();
        final List<VerifiedObject> readers = new ArrayList<>();
        int creates;
        ObjectKey failureKey;
        IOException failure;
        void put(ObjectKey key, byte[] bytes) { images.put(key, bytes.clone()); }
        List<ObjectKey> keysRead() { return reads.stream().map(Read::key).toList(); }
        @Override public ObjectReference createNew(ObjectKey key, byte[] image) {
            creates++;
            throw new AssertionError("Graph verification must not create objects");
        }
        @Override public VerifiedObject read(ObjectKey key, ObjectReference expected) throws IOException {
            reads.add(new Read(key, expected));
            if (key.equals(failureKey)) { throw failure; }
            byte[] image = images.get(key);
            if (image == null) { throw new NoSuchFileException(key.value()); }
            var reader = VerifiedObject.verify(image, expected);
            readers.add(reader);
            return reader;
        }
    }
}
