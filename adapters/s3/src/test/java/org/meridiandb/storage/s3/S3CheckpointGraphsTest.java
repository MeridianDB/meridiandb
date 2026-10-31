// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphException;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectIntegrityException;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Real SDK/HTTP graph consumption; no head selection, cloud or provider durability claim. */
class S3CheckpointGraphsTest {
    private static final DatabaseId DATABASE = new DatabaseId("s3-graph-history");
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final JournalRecord FIRST = record(100);
    @TempDir Path directory;
    private S3ProtocolFixture fixture;

    @BeforeEach void start() throws IOException { fixture = new S3ProtocolFixture(); }

    @AfterEach void stop() throws IOException {
        try { assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString()); }
        finally { fixture.close(); }
    }

    @Test void genesisGraphReadsOneManifestAndTheActualOwnedLineage() throws IOException {
        var prepared = prepare(7, 1, null, List.of());
        try (var journal = LocalJournal.create(directory.resolve("journal"), DATABASE);
             var store = S3ObjectStoreTest.connect(fixture)) {
            upload(store, prepared);
            int before = fixture.requests().size();
            var verified = verifier(store, journal).verify(prepared.head(), ObjectCheckpointGraphs.DEFAULT_LIMITS);
            assertEquals(1, verified.graphs().size());
            var graph = verified.graphs().getFirst();
            assertEquals(prepared.head(), graph.head());
            assertEquals(prepared.manifest(), graph.manifest());
            assertEquals(journal.prefix(0), graph.actualPrefix());
            assertEquals(ObjectCheckpointHeadCodec.reference(prepared.head()), graph.headReference());
            assertEquals(new ObjectCheckpointGraphs.Usage(prepared.manifestObject().image().length,
                    JournalCodec.encodeHeader(DATABASE).length, 0, 1, 1), verified.usage());
            assertReadOnlyGets(before, 1);
            assertEquals(1, fixture.count("GET", prepared.manifestObject().key().value()));
            assertEquals(0, journal.appendedSequence());
        }
    }

    @Test void successorPairRepeatsSharedSegmentReadsAndChargesTheCompleteWork() throws IOException {
        var previous = prepare(17, 1, null, List.of(FIRST));
        var candidate = prepare(18, 2, previous.head(), List.of(FIRST));
        try (var journal = LocalJournal.create(directory.resolve("journal"), DATABASE);
             var store = S3ObjectStoreTest.connect(fixture)) {
            journal.append(FIRST);
            upload(store, previous);
            uploadObject(store, candidate.manifestObject()); // The segment is shared, not uploaded twice.
            int before = fixture.requests().size();
            var verified = verifier(store, journal).verifySuccessor(previous.head(), candidate.head(),
                    ObjectCheckpointGraphs.DEFAULT_LIMITS);
            assertEquals(List.of(previous.head(), candidate.head()),
                    verified.graphs().stream().map(ObjectCheckpointGraphs.Graph::head).toList());
            assertEquals(List.of(previous.manifest(), candidate.manifest()),
                    verified.graphs().stream().map(ObjectCheckpointGraphs.Graph::manifest).toList());
            var prefix = journal.prefix(1);
            verified.graphs().forEach(graph -> assertEquals(prefix, graph.actualPrefix()));
            long bytes = previous.manifestObject().image().length + candidate.manifestObject().image().length
                    + 2L * previous.segmentObject().image().length;
            assertEquals(new ObjectCheckpointGraphs.Usage(bytes, 2 * prefix.byteLength(), 4, 4, 2),
                    verified.usage());
            assertReadOnlyGets(before, 4);
            assertEquals(1, fixture.count("GET", previous.manifestObject().key().value()));
            assertEquals(1, fixture.count("GET", candidate.manifestObject().key().value()));
            assertEquals(2, fixture.count("GET", previous.segmentObject().key().value()));
            assertEquals(0, journal.forcedSequence(), "Read-only graph verification must not force appended history");
            assertThrows(UnsupportedOperationException.class, () -> verified.graphs().clear());
        }
    }

    @Test void missingCorruptAndForbiddenDependenciesExposeNoGraphOrHeadMutation() throws IOException {
        var prepared = prepare(17, 1, null, List.of(FIRST));
        var segment = prepared.segmentObject();
        try (var journal = LocalJournal.create(directory.resolve("journal"), DATABASE);
             var store = S3ObjectStoreTest.connect(fixture)) {
            journal.append(FIRST);
            upload(store, prepared);
            var verifier = verifier(store, journal);
            int before = fixture.requests().size();
            fixture.remove(segment.key().value());
            assertThrows(NoSuchFileException.class,
                    () -> verifier.verify(prepared.head(), ObjectCheckpointGraphs.DEFAULT_LIMITS));
            var corrupt = segment.image().clone();
            corrupt[corrupt.length - 1] ^= 1;
            fixture.put(segment.key().value(), corrupt);
            var integrity = assertThrows(ObjectIntegrityException.class,
                    () -> verifier.verify(prepared.head(), ObjectCheckpointGraphs.DEFAULT_LIMITS));
            assertEquals(ObjectIntegrityException.Code.ID_MISMATCH, integrity.code());
            fixture.put(segment.key().value(), segment.image());
            fixture.fault("GET", segment.key().value(), S3ProtocolFixture.Fault.FORBIDDEN);
            var failure = assertThrows(IOException.class,
                    () -> verifier.verify(prepared.head(), ObjectCheckpointGraphs.DEFAULT_LIMITS));
            assertFalse(failure instanceof NoSuchFileException);
            fixture.clearFault("GET", segment.key().value());
            assertReadOnlyGets(before, 6);
            assertEquals(3, fixture.count("GET", prepared.manifestObject().key().value()));
            assertEquals(3, fixture.count("GET", segment.key().value()));
            assertEquals(1, journal.prefix(1).sequence());
            assertEquals(0, journal.forcedSequence());
            try (var reader = store.read(segment.key(), segment.reference())) {
                assertEquals(segment.reference(), reader.reference());
            }
        }
    }

    @Test void internallyValidRemoteGraphCannotSubstituteSameLineageDivergentJournalHistory() throws IOException {
        var prepared = prepare(17, 1, null, List.of(FIRST));
        try (var journal = LocalJournal.create(directory.resolve("journal"), DATABASE);
             var store = S3ObjectStoreTest.connect(fixture)) {
            journal.append(record(101)); // Same identity, length and sequence; different original value.
            upload(store, prepared);
            var actual = journal.prefix(1);
            assertEquals(prepared.head().journalPrefix().byteLength(), actual.byteLength());
            assertNotEquals(prepared.head().journalPrefix().sha256(), actual.sha256());
            int before = fixture.requests().size();
            var failure = assertThrows(ObjectCheckpointGraphException.class,
                    () -> verifier(store, journal).verify(prepared.head(), ObjectCheckpointGraphs.DEFAULT_LIMITS));
            assertEquals(ObjectCheckpointGraphException.Code.JOURNAL_MISMATCH, failure.code());
            assertReadOnlyGets(before, 2);
            assertEquals(1, fixture.count("GET", prepared.manifestObject().key().value()));
            assertEquals(1, fixture.count("GET", prepared.segmentObject().key().value()));
            assertEquals(actual, journal.prefix(1));
            assertEquals(0, journal.forcedSequence());
        }
    }

    private void assertReadOnlyGets(int previousRequests, int expectedReads) {
        var requests = fixture.requests().subList(previousRequests, fixture.requests().size());
        assertEquals(expectedReads, requests.size());
        assertTrue(requests.stream().allMatch(request -> request.method().equals("GET")),
                "Graph verification must issue only immutable-object GETs, with no head CAS");
    }

    private static ObjectCheckpointGraphs verifier(S3ObjectStore store, LocalJournal journal) {
        return new ObjectCheckpointGraphs(store, journal, NAMESPACE, PUBLISHER);
    }

    private static void upload(S3ObjectStore store, Prepared prepared) throws IOException {
        if (prepared.segmentObject() != null) { uploadObject(store, prepared.segmentObject()); }
        uploadObject(store, prepared.manifestObject());
    }

    private static void uploadObject(S3ObjectStore store, Stored object) throws IOException {
        assertEquals(object.reference(), store.createNew(object.key(), object.image()));
    }

    private static Prepared prepare(long generation, long revision, ObjectCheckpointHead predecessor,
                                    List<JournalRecord> records) {
        var journalImage = new ByteArrayOutputStream();
        journalImage.writeBytes(JournalCodec.encodeHeader(DATABASE));
        records.forEach(record -> journalImage.writeBytes(JournalCodec.encodeRecord(record)));
        byte[] prefixImage = journalImage.toByteArray();
        long sequence = records.isEmpty() ? 0 : records.getLast().sequence();
        var prefix = new CheckpointManifest.JournalPrefix(sequence, prefixImage.length,
                ObjectReference.forBytes(prefixImage).id().value());
        Stored segmentObject = null;
        List<CheckpointManifest.SegmentReference> segments = List.of();
        if (!records.isEmpty()) {
            byte[] image = SegmentCodec.encode(new ImmutableSegment(DATABASE, records));
            var reference = ObjectReference.forBytes(image);
            var id = new SegmentId(reference.id().value());
            segmentObject = new Stored(ObjectCheckpointGraphs.segmentKey(NAMESPACE, id), image, reference);
            segments = List.of(new CheckpointManifest.SegmentReference(id, image.length, 1, sequence));
        }
        var manifest = new CheckpointManifest(DATABASE, generation, prefix, segments);
        byte[] manifestImage = ManifestCodec.encode(manifest);
        var manifestReference = ObjectReference.forBytes(manifestImage);
        var manifestObject = new Stored(ObjectCheckpointGraphs.manifestKey(NAMESPACE, manifestReference.id()),
                manifestImage, manifestReference);
        var head = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 100 + revision),
                ObjectCheckpointHeadVerifier.lineageId(DATABASE), revision, manifestReference, prefix,
                predecessor == null ? null : ObjectCheckpointHeadCodec.reference(predecessor));
        return new Prepared(head, manifest, manifestObject, segmentObject);
    }

    private static JournalRecord record(long value) {
        return new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                new WriteOperation.Put(new EntityId("salary"), new ValidTimeInterval(null, null), value))));
    }

    private record Stored(ObjectKey key, byte[] image, ObjectReference reference) { }
    private record Prepared(ObjectCheckpointHead head, CheckpointManifest manifest,
                            Stored manifestObject, Stored segmentObject) { }
}
