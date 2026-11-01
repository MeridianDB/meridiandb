// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/** Test-only bridge: independent reference tests use actual SDK publication/admission, never a fake engine. */
public final class S3ManagedRecoveryCorpusFixture implements AutoCloseable {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    final Path databaseDirectory;
    final Path stateDirectory;
    private final DatabaseId lineage;
    final S3CheckpointHeads.Binding binding;
    S3PublicationProtocolFixture fixture;
    private StaticCredentialsProvider credentials;
    S3PublicationState.Target target;
    S3ObjectStore objects;
    S3CheckpointHeads heads;
    private ObjectCheckpointHead selected;

    public S3ManagedRecoveryCorpusFixture(Path parent, DatabaseId lineage) throws IOException {
        Files.createDirectory(parent);
        databaseDirectory = Files.createDirectory(parent.resolve("database"));
        stateDirectory = Files.createDirectory(parent.resolve("publication"));
        this.lineage = lineage;
        binding = new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, ObjectCheckpointHeadVerifier.lineageId(lineage));
        try {
            fixture = new S3PublicationProtocolFixture(32768, 512, 2 * 1024 * 1024);
            credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("fixture-access", "fixture-secret"));
            target = new S3PublicationState.Target(fixture.endpoint(), "us-east-1", S3PublicationProtocolFixture.BUCKET);
            var timeouts = new S3ObjectStore.Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(2));
            objects = new S3ObjectStore(target.endpoint(), Region.of(target.regionId()), target.bucket(), credentials, timeouts);
            heads = new S3CheckpointHeads(target.endpoint(), Region.of(target.regionId()), target.bucket(), credentials, binding, timeouts);
            try (var created = LocalDatabase.create(databaseDirectory, lineage)) { assertEquals(0, created.head().sequence()); }
            try (var local = LocalS3PublicationState.create(stateDirectory, target, binding)) { assertTrue(local.state().expected().isEmpty()); }
        } catch (IOException | RuntimeException | Error failure) {
            try { close(); }
            catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
            throw failure;
        }
    }

    public Path databaseDirectory() { return databaseDirectory; }
    public int requestCount() { return fixture.requests().size(); }

    /** Publish each distinct checkpoint cutoff through real create/verify, prepare and conditional submit. */
    public void select(List<JournalRecord> records, int cutoff) throws IOException {
        if (records.size() > 64 || cutoff < 0 || cutoff > records.size()) { throw new IllegalArgumentException("Corpus history bounds"); }
        if (selected != null && selected.journalPrefix().sequence() == cutoff) { return; }
        byte[] image = candidate(records, cutoff);
        var candidate = ObjectCheckpointHeadCodec.decode(image).head();
        try (var local = LocalS3PublicationState.open(stateDirectory, target, binding);
             var checkpoints = LocalCheckpointStore.open(databaseDirectory);
             var journal = LocalJournal.open(checkpoints.journalPath())) {
            new S3PublicationPreparer(objects, heads, local, checkpoints, journal).prepare(image, ObjectCheckpointGraphs.DEFAULT_LIMITS);
            var result = new S3CheckpointPublisher(objects, heads, local, checkpoints, journal).submit(ObjectCheckpointGraphs.DEFAULT_LIMITS);
            assertArrayEquals(image, result.observation().image());
            assertTrue(result.state().pending().isEmpty());
            assertEquals(records.size(), result.forcedSequence());
        }
        selected = candidate;
    }

    /** Upload exact immutable dependencies without preparing or selecting the candidate. */
    byte[] candidate(List<JournalRecord> records, int cutoff) throws IOException {
        if (records.size() > 64 || cutoff < 0 || cutoff > records.size()) { throw new IllegalArgumentException("Corpus history bounds"); }
        var references = new ArrayList<CheckpointManifest.SegmentReference>();
        for (int start = 0; start < cutoff; start += 2) {
            var group = records.subList(start, Math.min(start + 2, cutoff));
            byte[] image = SegmentCodec.encode(new ImmutableSegment(lineage, group));
            var reference = ObjectReference.forBytes(image);
            var id = new SegmentId(reference.id().value());
            assertEquals(reference, objects.createOrVerify(ObjectCheckpointGraphs.segmentKey(NAMESPACE, id), image).reference());
            references.add(new CheckpointManifest.SegmentReference(id, image.length, group.getFirst().sequence(), group.getLast().sequence()));
        }
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(lineage));
        records.subList(0, cutoff).forEach(record -> bytes.writeBytes(JournalCodec.encodeRecord(record)));
        byte[] prefix = bytes.toByteArray();
        var journalPrefix = new CheckpointManifest.JournalPrefix(cutoff, prefix.length, ObjectReference.forBytes(prefix).id().value());
        long revision = selected == null ? 1 : selected.revision() + 1;
        byte[] manifestImage = ManifestCodec.encode(new CheckpointManifest(lineage, revision, journalPrefix, references));
        var manifest = ObjectReference.forBytes(manifestImage);
        assertEquals(manifest, objects.createOrVerify(ObjectCheckpointGraphs.manifestKey(NAMESPACE, manifest.id()), manifestImage).reference());
        var candidate = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, revision), binding.lineageId(),
                revision, manifest, journalPrefix, selected == null ? null : ObjectCheckpointHeadCodec.reference(selected));
        return ObjectCheckpointHeadCodec.encode(candidate);
    }

    public S3Database open(long minimum) throws IOException {
        return S3Database.openWritable(databaseDirectory, stateDirectory, target, binding, objects, heads,
                ObjectCheckpointGraphs.DEFAULT_LIMITS, minimum);
    }

    /** Capture raw metadata only while no managed/publisher owner is open. */
    public Map<String, byte[]> metadata() throws IOException {
        var images = new LinkedHashMap<String, byte[]>();
        try (var paths = Files.list(stateDirectory)) {
            for (var path : paths.sorted().toList()) { images.put(path.getFileName().toString(), Files.readAllBytes(path)); }
        }
        return images;
    }

    public void unchanged(Map<String, byte[]> before, int afterStartupRequests) throws IOException {
        assertEquals(afterStartupRequests, requestCount(), "Transactions and queries must make no remote request");
        var after = metadata();
        assertEquals(before.keySet(), after.keySet());
        before.forEach((name, image) -> assertArrayEquals(image, after.get(name), name));
        assertArrayEquals(ObjectCheckpointHeadCodec.encode(selected), fixture.state(S3CheckpointHeads.headKey(NAMESPACE).value()).bytes());
    }

    @Override public void close() throws IOException {
        Throwable failure = null;
        for (AutoCloseable resource : new AutoCloseable[] {heads, objects, fixture}) {
            if (resource == null) { continue; }
            try { resource.close(); }
            catch (Exception | Error cleanup) {
                if (failure == null) { failure = cleanup; }
                else if (failure != cleanup) { failure.addSuppressed(cleanup); }
            }
        }
        if (failure instanceof IOException io) { throw io; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
        if (failure != null) { throw new IOException("Corpus fixture cleanup failed", failure); }
        if (fixture != null) { assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString()); }
    }
}
