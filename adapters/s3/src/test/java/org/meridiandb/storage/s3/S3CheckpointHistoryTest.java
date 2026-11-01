// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import org.meridiandb.storage.object.ImmutableObjectStore;
import org.meridiandb.storage.object.ObjectIntegrityException;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.object.VerifiedObject;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/** Real SDK byte-consumer checks; no current-head observation, engine recovery or provider proof. */
@Timeout(30)
class S3CheckpointHistoryTest {
    private static final DatabaseId DATABASE = new DatabaseId("s3-checkpoint-history");
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final JournalRecord FIRST = record(1, "first!", 100);
    private static final JournalRecord SECOND = record(2, "second", 100); // Distinct identity, unchanged value.
    private static final JournalRecord THIRD = record(3, "third!", 120);
    private static final JournalRecord FOURTH = record(4, "fourth", 140);
    private static final List<JournalRecord> PREFIX = List.of(FIRST, SECOND);
    private static final S3ObjectStore.Timeouts TIMEOUTS = new S3ObjectStore.Timeouts(
            Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(2));

    @TempDir Path directory;
    private S3ProtocolFixture fixture;

    @BeforeEach void start() throws IOException { fixture = new S3ProtocolFixture(); }

    @AfterEach void stop() throws IOException {
        try {
            assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString());
            assertTrue(fixture.requests().stream().allMatch(request -> request.method().equals("GET")
                    && (request.key().contains("-manifest-") || request.key().contains("-segment-"))),
                    "Loading supplied history may only GET preinstalled immutable dependencies");
        } finally { fixture.close(); }
    }

    @Test void orderedOriginalPrefixUsesOneFetchPerDependencyAndExcludesLocalSuffix() throws IOException {
        var graph = install(PREFIX);
        Path path = directory.resolve("journal");
        try (var journal = journal(path); var store = S3ObjectStoreTest.connect(fixture)) {
            var tracking = new TrackingObjects(store);
            var history = loader(tracking, journal).loadHistory(graph.head(), graph.limits());
            assertEquals(PREFIX, history.records());
            assertEquals(FIRST.transaction().operations(), SECOND.transaction().operations());
            assertFalse(FIRST.transaction().requestId().equals(SECOND.transaction().requestId()));
            assertFalse(history.records().contains(THIRD));
            assertEquals(3, journal.appendedSequence());
            assertEquals(graph.head(), history.graph().head());
            assertEquals(graph.manifest(), history.graph().manifest());
            assertEquals(ObjectCheckpointHeadCodec.reference(graph.head()), history.graph().headReference());
            assertEquals(journal.prefix(2), history.graph().actualPrefix());
            assertEquals(graph.usage(), history.usage());
            assertEquals(graph.keys(), fixture.requests().stream().map(S3ProtocolFixture.Request::key).toList());
            graph.keys().forEach(key -> assertEquals(1, fixture.count("GET", key)));
            assertEquals(0, journal.forcedSequence(), "Read-only history loading does not force appended history");
            assertReadersClosed(tracking);
            assertThrows(UnsupportedOperationException.class, () -> history.records().add(THIRD));
            assertThrows(UnsupportedOperationException.class, () -> history.records().clear());
        }
        assertArrayEquals(journalImage(List.of(FIRST, SECOND, THIRD)), Files.readAllBytes(path));
    }

    @Test void genesisLoadsNoRecordsAndReadsOnlyManifestDespiteAvailableLocalTail() throws IOException {
        var graph = install(List.of());
        try (var journal = journal(directory.resolve("journal")); var store = S3ObjectStoreTest.connect(fixture)) {
            var tracking = new TrackingObjects(store);
            var history = loader(tracking, journal).loadHistory(graph.head(), graph.limits());
            assertTrue(history.records().isEmpty());
            assertEquals(graph.manifest(), history.graph().manifest());
            assertEquals(journal.prefix(0), history.graph().actualPrefix());
            assertEquals(graph.usage(), history.usage());
            assertEquals(1, fixture.requests().size());
            assertEquals(graph.keys().getFirst(), fixture.requests().getFirst().key());
            assertEquals(3, journal.appendedSequence());
            assertEquals(0, journal.forcedSequence());
            assertReadersClosed(tracking);
        }
    }

    @Test void missingLateDependencyReturnsNoHistoryAfterEarlierObjectsWereRead() throws IOException {
        var graph = install(PREFIX);
        String missing = graph.keys().getLast();
        fixture.remove(missing);
        var exposed = new AtomicReference<ObjectCheckpointGraphs.History>();
        try (var journal = journal(directory.resolve("journal")); var store = S3ObjectStoreTest.connect(fixture)) {
            var tracking = new TrackingObjects(store);
            assertThrows(NoSuchFileException.class,
                    () -> exposed.set(loader(tracking, journal).loadHistory(graph.head(), graph.limits())));
            assertNull(exposed.get());
            assertEquals(graph.keys(), fixture.requests().stream().map(S3ProtocolFixture.Request::key).toList());
            assertEquals(2, tracking.readers.size(), "Manifest and first segment completed before the missing late segment");
            assertReadersClosed(tracking);
            assertEquals(3, journal.prefix(3).sequence());
            try (var independent = store.read(graph.manifestKey(), ObjectReference.forBytes(graph.manifestImage()))) {
                assertArrayEquals(graph.manifestImage(), independent.bytes());
            }
        }
    }

    @Test void changedLateDependencyReturnsNoHistoryAndLeavesCallerResourcesUsable() throws IOException {
        var graph = install(PREFIX);
        String damaged = graph.keys().getLast();
        byte[] original = fixture.bytes(damaged);
        byte[] corrupt = original.clone();
        corrupt[corrupt.length - 1] ^= 1;
        fixture.put(damaged, corrupt);
        var exposed = new AtomicReference<ObjectCheckpointGraphs.History>();
        try (var journal = journal(directory.resolve("journal")); var store = S3ObjectStoreTest.connect(fixture)) {
            var tracking = new TrackingObjects(store);
            var failure = assertThrows(ObjectIntegrityException.class,
                    () -> exposed.set(loader(tracking, journal).loadHistory(graph.head(), graph.limits())));
            assertEquals(ObjectIntegrityException.Code.ID_MISMATCH, failure.code());
            assertNull(exposed.get());
            assertEquals(graph.keys(), fixture.requests().stream().map(S3ProtocolFixture.Request::key).toList());
            assertReadersClosed(tracking);
            assertEquals(3, journal.prefix(3).sequence());
            // Only the fixture operator restores changed bytes; the loader performs no repair.
            fixture.put(damaged, original);
            var recovered = loader(tracking, journal).loadHistory(graph.head(), graph.limits());
            assertEquals(PREFIX, recovered.records());
            assertEquals(graph.usage(), recovered.usage());
            assertReadersClosed(tracking);
        }
    }

    @Test void malformedLateHttpBodyReturnsNoHistoryAndExplicitRetryUsesTheSameSdkClient() throws IOException {
        var graph = install(PREFIX);
        String damaged = graph.keys().getLast();
        fixture.fault("GET", damaged, S3ProtocolFixture.Fault.CHUNKED);
        var exposed = new AtomicReference<ObjectCheckpointGraphs.History>();
        try (var journal = journal(directory.resolve("journal")); var store = S3ObjectStoreTest.connect(fixture)) {
            var tracking = new TrackingObjects(store);
            assertThrows(IOException.class,
                    () -> exposed.set(loader(tracking, journal).loadHistory(graph.head(), graph.limits())));
            assertNull(exposed.get());
            assertEquals(2, tracking.readers.size());
            assertReadersClosed(tracking);
            fixture.clearFault("GET", damaged);
            var history = loader(tracking, journal).loadHistory(graph.head(), graph.limits());
            assertEquals(PREFIX, history.records());
            assertEquals(graph.usage(), history.usage());
            graph.keys().forEach(key -> assertEquals(2, fixture.count("GET", key)));
            assertEquals(0, journal.forcedSequence());
            assertReadersClosed(tracking);
        }
    }

    @Test void historySurvivesReaderSdkAndJournalCloseWithoutTakingCallerCredentialOwnership() throws IOException {
        var graph = install(PREFIX);
        var credentials = new CallerCredentials();
        ObjectCheckpointGraphs.History history;
        try {
            try (var journal = journal(directory.resolve("journal"))) {
                try (var store = new S3ObjectStore(fixture.endpoint(), Region.US_EAST_1, S3ProtocolFixture.BUCKET,
                        credentials, TIMEOUTS)) {
                    var tracking = new TrackingObjects(store);
                    history = loader(tracking, journal).loadHistory(graph.head(), graph.limits());
                    assertReadersClosed(tracking);
                    try (var independent = store.read(graph.manifestKey(), ObjectReference.forBytes(graph.manifestImage()))) {
                        assertArrayEquals(graph.manifestImage(), independent.bytes());
                    }
                    // Stored objects can disappear after this heap history is complete.
                    graph.keys().forEach(fixture::remove);
                }
                assertEquals(0, credentials.closes);
                assertEquals("fixture-access", credentials.resolveCredentials().accessKeyId());
                journal.append(FOURTH);
                assertEquals(4, journal.appendedSequence(), "The loader leaves the actual journal caller-owned");
                assertEquals(2, history.graph().actualPrefix().sequence(), "Verification evidence is historical");
            }
            assertEquals(PREFIX, history.records());
            assertEquals(graph.head(), history.graph().head());
            assertEquals(graph.usage(), history.usage());
            assertThrows(UnsupportedOperationException.class, () -> history.records().clear());
            assertEquals(0, credentials.closes);
        } finally { credentials.close(); }
        assertEquals(1, credentials.closes);
    }

    @Test void eachInsufficientBudgetRefusesBeforeSegmentFetchAndExactBudgetStillSucceeds() throws IOException {
        var graph = install(PREFIX);
        var exact = graph.limits();
        var shortages = List.of(
                new ObjectCheckpointGraphs.Limits(exact.maxObjectBytes() - 1, exact.maxJournalBytes(), exact.maxRecordWork(), exact.maxObjectReads()),
                new ObjectCheckpointGraphs.Limits(exact.maxObjectBytes(), exact.maxJournalBytes() - 1, exact.maxRecordWork(), exact.maxObjectReads()),
                new ObjectCheckpointGraphs.Limits(exact.maxObjectBytes(), exact.maxJournalBytes(), exact.maxRecordWork() - 1, exact.maxObjectReads()),
                new ObjectCheckpointGraphs.Limits(exact.maxObjectBytes(), exact.maxJournalBytes(), exact.maxRecordWork(), exact.maxObjectReads() - 1));
        try (var journal = journal(directory.resolve("journal")); var store = S3ObjectStoreTest.connect(fixture)) {
            var tracking = new TrackingObjects(store);
            for (int index = 0; index < shortages.size(); index++) {
                var limits = shortages.get(index);
                int before = fixture.requests().size();
                var failure = assertThrows(ObjectCheckpointGraphException.class,
                        () -> loader(tracking, journal).loadHistory(graph.head(), limits));
                assertEquals(ObjectCheckpointGraphException.Code.LIMIT_EXCEEDED, failure.code());
                var attempted = fixture.requests().subList(before, fixture.requests().size());
                assertEquals(index == 0 || index == 3 ? 1 : 0, attempted.size());
                assertTrue(attempted.stream().allMatch(request -> request.key().equals(graph.manifestKey().value())));
                assertReadersClosed(tracking);
            }
            assertEquals(0, fixture.count("GET", graph.keys().get(1)));
            assertEquals(0, fixture.count("GET", graph.keys().get(2)));
            var history = loader(tracking, journal).loadHistory(graph.head(), exact);
            assertEquals(PREFIX, history.records());
            assertEquals(graph.usage(), history.usage());
            assertReadersClosed(tracking);
            assertEquals(3, journal.prefix(3).sequence());
            assertEquals(0, journal.forcedSequence());
        }
    }

    private Graph install(List<JournalRecord> records) {
        var segments = new LinkedHashMap<SegmentId, byte[]>();
        var references = new ArrayList<CheckpointManifest.SegmentReference>();
        for (var record : records) {
            byte[] image = SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(record)));
            var id = new SegmentId(hash(image));
            segments.put(id, image);
            references.add(new CheckpointManifest.SegmentReference(id, image.length, record.sequence(), record.sequence()));
        }
        byte[] prefix = journalImage(records);
        var declaredPrefix = new CheckpointManifest.JournalPrefix(records.size(), prefix.length, hash(prefix));
        var manifest = new CheckpointManifest(DATABASE, 17, declaredPrefix, references);
        byte[] manifestImage = ManifestCodec.encode(manifest);
        var reference = ObjectReference.forBytes(manifestImage);
        var manifestKey = ObjectCheckpointGraphs.manifestKey(NAMESPACE, reference.id());
        var head = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 3),
                ObjectCheckpointHeadVerifier.lineageId(DATABASE), 1, reference, declaredPrefix, null);
        fixture.put(manifestKey.value(), manifestImage);
        var keys = new ArrayList<String>();
        keys.add(manifestKey.value());
        long bytes = manifestImage.length;
        for (var entry : segments.entrySet()) {
            String key = ObjectCheckpointGraphs.segmentKey(NAMESPACE, entry.getKey()).value();
            fixture.put(key, entry.getValue());
            keys.add(key);
            bytes += entry.getValue().length;
        }
        var usage = new ObjectCheckpointGraphs.Usage(bytes, prefix.length, 2L * records.size(), keys.size(), 1);
        return new Graph(head, manifest, manifestImage, manifestKey, List.copyOf(keys), usage);
    }

    private record Graph(ObjectCheckpointHead head, CheckpointManifest manifest, byte[] manifestImage,
                         ObjectKey manifestKey, List<String> keys, ObjectCheckpointGraphs.Usage usage) {
        ObjectCheckpointGraphs.Limits limits() {
            return new ObjectCheckpointGraphs.Limits(usage.objectBytes(), usage.journalBytes(), usage.recordWork(), usage.objectReads());
        }
    }

    private static LocalJournal journal(Path path) throws IOException {
        var journal = LocalJournal.create(path, DATABASE);
        try {
            journal.append(FIRST);
            journal.append(SECOND);
            journal.append(THIRD);
            return journal;
        } catch (IOException | RuntimeException | Error failure) {
            try { journal.close(); }
            catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
            throw failure;
        }
    }

    private static ObjectCheckpointGraphs loader(ImmutableObjectStore store, LocalJournal journal) {
        return new ObjectCheckpointGraphs(store, journal, NAMESPACE, PUBLISHER);
    }

    private static void assertReadersClosed(TrackingObjects tracking) {
        tracking.readers.forEach(reader -> assertThrows(IllegalStateException.class, reader::reference));
    }

    private static final class TrackingObjects implements ImmutableObjectStore {
        final S3ObjectStore delegate;
        final List<VerifiedObject> readers = new ArrayList<>();
        TrackingObjects(S3ObjectStore delegate) { this.delegate = delegate; }
        @Override public ObjectReference createNew(ObjectKey key, byte[] image) { throw new AssertionError("History loader must not create objects"); }
        @Override public VerifiedObject read(ObjectKey key, ObjectReference expected) throws IOException {
            var reader = delegate.read(key, expected);
            readers.add(reader);
            return reader;
        }
    }

    private static final class CallerCredentials implements AwsCredentialsProvider, AutoCloseable {
        int closes;
        @Override public AwsCredentials resolveCredentials() {
            if (closes != 0) { throw new IllegalStateException("Caller provider was closed"); }
            return AwsBasicCredentials.create("fixture-access", "fixture-secret");
        }
        @Override public void close() { closes++; }
    }

    private static JournalRecord record(long sequence, String request, long value) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(
                new WriteOperation.Put(new EntityId("salary"), new ValidTimeInterval(null, null), value))));
    }

    private static byte[] journalImage(List<JournalRecord> records) {
        var image = new ByteArrayOutputStream();
        image.writeBytes(JournalCodec.encodeHeader(DATABASE));
        records.forEach(record -> image.writeBytes(JournalCodec.encodeRecord(record)));
        return image.toByteArray();
    }

    private static String hash(byte[] image) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
}
