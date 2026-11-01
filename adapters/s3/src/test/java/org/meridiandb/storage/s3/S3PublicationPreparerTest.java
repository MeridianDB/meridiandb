// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.JournalCodec;
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
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadException;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectIntegrityException;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.s3.S3PublicationState.Operation;
import org.meridiandb.storage.s3.S3PublicationState.Target;
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
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

class S3PublicationPreparerTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final UUID OTHER = new UUID(0, 99);
    private static final DatabaseId DATABASE = new DatabaseId("preparer-db");
    private static final ObjectId LINEAGE = ObjectCheckpointHeadVerifier.lineageId(DATABASE);
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, LINEAGE);
    private static final JournalRecord FIRST = record(1, "first!", 100);
    private static final JournalRecord SECOND = record(2, "second", 120);
    private static final JournalRecord THIRD = record(3, "third!", 140);
    private static final ObjectCheckpointGraphs.Limits DEFAULTS = ObjectCheckpointGraphs.DEFAULT_LIMITS;
    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("fixture-access", "fixture-secret"));
    private static final S3ObjectStore.Timeouts TIMEOUTS = new S3ObjectStore.Timeouts(
            Duration.ofSeconds(20), Duration.ofSeconds(2), Duration.ofSeconds(3));

    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void genesisVerifiesAndForcesBeforePersistingExactPendingCreateWithoutPublishing(boolean nonempty) throws IOException {
        var graph = graph(17, nonempty ? List.of(List.of(FIRST), List.of(SECOND)) : List.of());
        var candidate = first(graph);
        byte[] image = ObjectCheckpointHeadCodec.encode(candidate);
        S3PublicationPreparer.Preparation result;
        Path localDirectory;
        Target target;
        try (var env = environment()) {
            env.append(nonempty ? List.of(FIRST, SECOND) : List.of());
            env.install(graph);
            localDirectory = env.localDirectory;
            target = env.target;
            assertEquals(0, env.journal.forcedSequence());
            byte[] supplied = image.clone();
            result = env.preparer().prepare(supplied, exact(graph));
            supplied[0] ^= 1;
            assertPending(result.pending(), Operation.CREATE_IF_ABSENT, "*", image);
            assertEquals(2, result.pending().generation());
            assertTrue(result.pending().expected().isEmpty());
            assertEquals(candidate.publicationId(), result.pending().pending().orElseThrow().head().publicationId());
            assertEquals(ObjectReference.forBytes(image), result.intentReference());
            assertTrue(result.intentCreated());
            assertEquals(nonempty ? 2 : 0, result.forcedSequence());
            assertEquals(env.journal.appendedBytes(), env.journal.forcedBytes());
            assertEquals(1, result.verification().graphs().size());
            assertEquals(candidate, result.verification().graphs().getFirst().head());
            assertEquals(graph.manifest(), result.verification().graphs().getFirst().manifest());
            assertUsage(result.verification().usage(), graph);
            assertArrayEquals(image, env.fixture.bytes(intentKey(candidate)));
            assertNull(env.fixture.bytes(headKey()));
            assertEquals(1, env.fixture.count("GET", headKey()));
            assertEquals(1, env.fixture.count("PUT", intentKey(candidate)));
            assertNoMutablePut(env);
            var write = env.fixture.requests().getLast();
            assertEquals(intentKey(candidate), write.key());
            assertEquals("*", write.header("if-none-match"));
            assertNull(write.header("if-match"));
            assertArrayEquals(image, write.body());
            assertArrayEquals(S3PublicationStateCodec.encode(result.pending()), S3PublicationStateCodec.encode(env.local.state()));
            env.journal.append(nonempty ? THIRD : FIRST);
            assertEquals(nonempty ? 3 : 1, env.journal.appendedSequence(), "preparation retains caller ownership");
            assertEquals(nonempty ? 2 : 0, result.forcedSequence(), "result is historical evidence");
        }
        assertThrows(UnsupportedOperationException.class, () -> result.verification().graphs().clear());
        assertPending(result.pending(), Operation.CREATE_IF_ABSENT, "*", image);
        try (var reopened = LocalS3PublicationState.open(localDirectory, target, BINDING)) {
            assertArrayEquals(S3PublicationStateCodec.encode(result.pending()), S3PublicationStateCodec.encode(reopened.state()));
        }
    }

    @Test void replacementRechecksBothActualGraphsAndRetainsOriginalRemoteObservationAcrossReopen() throws IOException {
        var before = graph(19, List.of(List.of(FIRST)));
        var after = graph(7, List.of(List.of(FIRST, SECOND)));
        var selected = first(before);
        var candidate = next(selected, after);
        byte[] selectedImage = ObjectCheckpointHeadCodec.encode(selected);
        byte[] candidateImage = ObjectCheckpointHeadCodec.encode(candidate);
        S3PublicationState pending;
        Path localDirectory;
        Target target;
        try (var env = environment()) {
            env.append(List.of(FIRST, SECOND));
            env.install(before, after);
            env.select(selected, S3ProtocolFixture.ETAG);
            localDirectory = env.localDirectory;
            target = env.target;
            var result = env.preparer().prepare(candidateImage, exact(before, after));
            pending = result.pending();
            assertEquals(4, pending.generation());
            assertPending(pending, Operation.REPLACE_IF_MATCH, S3ProtocolFixture.ETAG, candidateImage);
            assertArrayEquals(selectedImage, pending.expected().orElseThrow().image());
            assertEquals(S3ProtocolFixture.ETAG, pending.expected().orElseThrow().token().value());
            assertEquals(2, result.verification().graphs().size());
            assertEquals(selected, result.verification().graphs().get(0).head());
            assertEquals(candidate, result.verification().graphs().get(1).head());
            assertUsage(result.verification().usage(), before, after);
            assertEquals(2, result.forcedSequence());
            assertArrayEquals(selectedImage, env.fixture.bytes(headKey()));
            assertArrayEquals(candidateImage, env.fixture.bytes(intentKey(candidate)));
            assertEquals(1, env.fixture.count("GET", headKey()));
            assertNoMutablePut(env);
        }
        try (var reopened = LocalS3PublicationState.open(localDirectory, target, BINDING)) {
            assertArrayEquals(S3PublicationStateCodec.encode(pending), S3PublicationStateCodec.encode(reopened.state()));
            assertPending(reopened.state(), Operation.REPLACE_IF_MATCH, S3ProtocolFixture.ETAG, candidateImage);
        }
    }

    @Test void exactExistingImmutableIntentIsReadBackAndReusedWithoutClaimingCreation() throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        var candidate = first(graph);
        byte[] image = ObjectCheckpointHeadCodec.encode(candidate);
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            env.fixture.put(intentKey(candidate), image);
            var result = env.preparer().prepare(image, DEFAULTS);
            assertFalse(result.intentCreated());
            assertEquals(ObjectReference.forBytes(image), result.intentReference());
            assertEquals(1, env.fixture.count("PUT", intentKey(candidate)));
            assertEquals(1, env.fixture.count("GET", intentKey(candidate)));
            assertArrayEquals(image, env.fixture.bytes(intentKey(candidate)));
            assertPending(result.pending(), Operation.CREATE_IF_ABSENT, "*", image);
            assertNoMutablePut(env);
        }
    }

    @Test void unchangedCutoffRepackingStillVerifiesBothGraphsBeforePreparingReplacement() throws IOException {
        var before = graph(17, List.of(List.of(FIRST), List.of(SECOND)));
        var after = graph(3, List.of(List.of(FIRST, SECOND)));
        var selected = first(before);
        var candidate = next(selected, after);
        try (var env = environment()) {
            env.append(List.of(FIRST, SECOND));
            env.install(before, after);
            env.select(selected, S3ProtocolFixture.ETAG);
            var result = env.preparer().prepare(ObjectCheckpointHeadCodec.encode(candidate), exact(before, after));
            assertEquals(result.verification().graphs().get(0).actualPrefix(), result.verification().graphs().get(1).actualPrefix());
            assertNotEquals(before.manifest().generation(), after.manifest().generation());
            assertUsage(result.verification().usage(), before, after);
            assertEquals(2, result.forcedSequence());
            assertNoMutablePut(env);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"object-endpoint", "object-region", "object-bucket", "head-endpoint",
            "head-region", "head-bucket", "head-namespace", "head-publisher", "head-lineage"})
    void actualSdkClientConfigurationMustMatchDurableScopeBeforeAnyRequest(String changed) throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        byte[] image = ObjectCheckpointHeadCodec.encode(first(graph));
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            URI endpoint = changed.endsWith("endpoint") ? URI.create(env.target.endpoint().toString() + "/") : env.target.endpoint();
            String region = changed.endsWith("region") ? "us-west-2" : env.target.regionId();
            String bucket = changed.endsWith("bucket") ? "other-bucket" : env.target.bucket();
            var alternate = new Target(endpoint, region, bucket);
            var binding = new S3CheckpointHeads.Binding(changed.equals("head-namespace") ? OTHER : NAMESPACE,
                    changed.equals("head-publisher") ? OTHER : PUBLISHER,
                    changed.equals("head-lineage") ? new ObjectId("0".repeat(64)) : LINEAGE);
            try (var objects = objectClient(changed.startsWith("object-") ? alternate : env.target);
                 var heads = headClient(changed.startsWith("head-") ? alternate : env.target, binding)) {
                var preparer = new S3PublicationPreparer(objects, heads, env.local, env.checkpoints, env.journal);
                assertThrows(IllegalArgumentException.class, () -> preparer.prepare(image, DEFAULTS));
                assertNoEffects(env, 1);
                assertTrue(env.fixture.requests().isEmpty());
            }
            assertEquals(env.journal.path().orElseThrow(), env.checkpoints.journalPath());
        }
    }

    @Test void actualJournalLineageIsCheckedIndependentlyOfMatchingSdkAndStateBindings() throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        try (var env = environment(new DatabaseId("copied-other-lineage"))) {
            env.append(List.of(FIRST));
            env.install(graph);
            assertThrows(IllegalArgumentException.class,
                    () -> env.preparer().prepare(ObjectCheckpointHeadCodec.encode(first(graph)), DEFAULTS));
            assertTrue(env.fixture.requests().isEmpty());
            assertNoEffects(env, 1);
        }
    }

    @Test void anOwnedSameLineageJournalAtAnotherPathCannotBeForcedThroughThisCheckpointOwner() throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        try (var env = environment(); var other = LocalJournal.create(directory.resolve("another.journal"), DATABASE)) {
            other.append(FIRST);
            env.install(graph);
            long forcedBytes = other.forcedBytes();
            var preparer = new S3PublicationPreparer(env.objects, env.heads, env.local, env.checkpoints, other);
            assertThrows(IOException.class, () -> preparer.prepare(ObjectCheckpointHeadCodec.encode(first(graph)), DEFAULTS));
            assertTrue(env.fixture.requests().isEmpty());
            assertEquals(forcedBytes, other.forcedBytes());
            assertNoEffects(env, 1);
            other.append(SECOND);
            assertEquals(2, other.appendedSequence());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"malformed", "binding", "transition"})
    void pureCandidateRejectionsHaveNoRequestsOrForceAndLeaveTheOwnerUsable(String invalidity) throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        var first = first(graph);
        byte[] valid = ObjectCheckpointHeadCodec.encode(first);
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            switch (invalidity) {
                case "malformed" -> assertThrows(IllegalArgumentException.class,
                        () -> env.preparer().prepare(new byte[255], DEFAULTS));
                case "binding" -> {
                    var foreign = new ObjectCheckpointHead(OTHER, PUBLISHER, first.publicationId(), LINEAGE, 1,
                            first.manifest(), first.journalPrefix(), null);
                    assertEquals(S3PublicationStateException.Code.INVALID_BINDING,
                            assertThrows(S3PublicationStateException.class,
                                    () -> env.preparer().prepare(ObjectCheckpointHeadCodec.encode(foreign), DEFAULTS)).code());
                }
                case "transition" -> assertEquals(S3PublicationStateException.Code.INVALID_TRANSITION,
                        assertThrows(S3PublicationStateException.class,
                                () -> env.preparer().prepare(ObjectCheckpointHeadCodec.encode(next(first, graph)), DEFAULTS)).code());
                default -> throw new AssertionError(invalidity);
            }
            assertTrue(env.fixture.requests().isEmpty());
            assertNoEffects(env, 1);
            assertPending(env.preparer().prepare(valid, DEFAULTS).pending(), Operation.CREATE_IF_ABSENT, "*", valid);
        }
    }

    @Test void unresolvedPendingIntentRejectsAnotherPreparationBeforeRequestsAndRetainsTheExactIntent() throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        byte[] image = ObjectCheckpointHeadCodec.encode(first(graph));
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            var original = env.local.prepare(image);
            assertEquals(S3PublicationStateException.Code.INVALID_TRANSITION,
                    assertThrows(S3PublicationStateException.class, () -> env.preparer().prepare(image, DEFAULTS)).code());
            assertTrue(env.fixture.requests().isEmpty());
            assertEquals(0, env.journal.forcedSequence());
            assertArrayEquals(S3PublicationStateCodec.encode(original), S3PublicationStateCodec.encode(env.local.state()));
            assertNull(env.fixture.bytes(intentKey(first(graph))));
            assertTrue(env.local.recordSelected(env.observation(image, S3ProtocolFixture.ETAG)).pending().isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"unexpected", "missing", "changed-bytes", "original-token"})
    void remoteSelectionMustEqualOriginalDurableBytesAndTokenWithoutAdoption(String mismatch) throws IOException {
        var before = graph(2, List.of(List.of(FIRST)));
        var after = graph(3, List.of(List.of(FIRST, SECOND)));
        var selected = first(before);
        try (var env = environment()) {
            env.append(List.of(FIRST, SECOND));
            env.install(before, after);
            final ObjectCheckpointHead candidate;
            if (mismatch.equals("unexpected")) {
                env.fixture.put(headKey(), ObjectCheckpointHeadCodec.encode(selected));
                candidate = selected;
            } else {
                String token = mismatch.equals("original-token") ? "\"durable-original-different-token\"" : S3ProtocolFixture.ETAG;
                env.select(selected, token);
                candidate = next(selected, after);
                if (mismatch.equals("missing")) { env.fixture.remove(headKey()); }
                if (mismatch.equals("changed-bytes")) {
                    var competing = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, OTHER, LINEAGE, 1,
                            selected.manifest(), selected.journalPrefix(), null);
                    env.fixture.put(headKey(), ObjectCheckpointHeadCodec.encode(competing));
                }
                if (mismatch.equals("original-token")) {
                    assertNotEquals(S3ProtocolFixture.ETAG, env.local.state().expected().orElseThrow().token().value());
                    assertArrayEquals(ObjectCheckpointHeadCodec.encode(selected), env.fixture.bytes(headKey()));
                }
            }
            assertThrows(IOException.class, () -> env.preparer().prepare(ObjectCheckpointHeadCodec.encode(candidate), DEFAULTS));
            assertEquals(1, env.fixture.requests().size(), "comparison precedes all dependency fetches");
            assertEquals(1, env.fixture.count("GET", headKey()));
            assertNoEffects(env, mismatch.equals("unexpected") ? 1 : 3);
            assertNull(env.fixture.bytes(intentKey(candidate)));
            assertNoMutablePut(env);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing-manifest", "missing-segment", "wrong-segment-content", "malformed-manifest"})
    void invalidCandidateDependenciesNeverForceOrInstallAnIntent(String invalidity) throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        var candidate = first(graph);
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            String manifestKey = ObjectCheckpointGraphs.manifestKey(NAMESPACE, candidate.manifest().id()).value();
            String segmentKey = ObjectCheckpointGraphs.segmentKey(NAMESPACE, graph.manifest().segments().getFirst().id()).value();
            switch (invalidity) {
                case "missing-manifest" -> env.fixture.remove(manifestKey);
                case "missing-segment" -> env.fixture.remove(segmentKey);
                case "wrong-segment-content" -> {
                    byte[] changed = env.fixture.bytes(segmentKey);
                    changed[changed.length / 2] ^= 1;
                    env.fixture.put(segmentKey, changed);
                }
                case "malformed-manifest" -> {
                    byte[] corrupt = graph.manifestImage().clone();
                    corrupt[0] ^= 1;
                    var reference = ObjectReference.forBytes(corrupt);
                    candidate = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, candidate.publicationId(), LINEAGE, 1,
                            reference, candidate.journalPrefix(), null);
                    env.fixture.put(ObjectCheckpointGraphs.manifestKey(NAMESPACE, reference.id()).value(), corrupt);
                }
                default -> throw new AssertionError(invalidity);
            }
            var supplied = candidate;
            var failure = assertThrows(IOException.class,
                    () -> env.preparer().prepare(ObjectCheckpointHeadCodec.encode(supplied), DEFAULTS));
            if (invalidity.startsWith("missing-")) { assertTrue(failure instanceof NoSuchFileException); }
            if (invalidity.equals("wrong-segment-content")) { assertTrue(failure instanceof ObjectIntegrityException); }
            if (invalidity.equals("malformed-manifest")) {
                assertEquals(ObjectCheckpointGraphException.Code.INVALID_GRAPH, ((ObjectCheckpointGraphException) failure).code());
            }
            assertNoEffects(env, 1);
            assertNull(env.fixture.bytes(intentKey(candidate)));
            assertEquals(1, env.fixture.count("GET", headKey()));
            assertNoMutablePut(env);
        }
    }

    @Test void invalidSelectedGraphDoesNotFallBackToAnOtherwiseValidCandidate() throws IOException {
        var before = graph(2, List.of(List.of(FIRST)));
        var after = graph(3, List.of(List.of(FIRST, SECOND)));
        var selected = first(before);
        var candidate = next(selected, after);
        try (var env = environment()) {
            env.append(List.of(FIRST, SECOND));
            env.install(before, after);
            env.select(selected, S3ProtocolFixture.ETAG);
            env.fixture.remove(ObjectCheckpointGraphs.segmentKey(NAMESPACE, before.manifest().segments().getFirst().id()).value());
            assertThrows(NoSuchFileException.class,
                    () -> env.preparer().prepare(ObjectCheckpointHeadCodec.encode(candidate), DEFAULTS));
            assertEquals(0, env.fixture.count("GET",
                    ObjectCheckpointGraphs.segmentKey(NAMESPACE, after.manifest().segments().getFirst().id()).value()));
            assertNoEffects(env, 3);
            assertNull(env.fixture.bytes(intentKey(candidate)));
            assertArrayEquals(ObjectCheckpointHeadCodec.encode(selected), env.fixture.bytes(headKey()));
            assertNoMutablePut(env);
        }
    }

    @Test void selectedCopiedSameLineageHistoryMustMatchActualJournalEvenWhenCandidateDoes() throws IOException {
        var otherFirst = record(1, "other!", 900);
        var before = graph(2, List.of(List.of(otherFirst)));
        var after = graph(3, List.of(List.of(FIRST, SECOND)));
        var selected = first(before);
        var candidate = next(selected, after);
        try (var env = environment()) {
            env.append(List.of(FIRST, SECOND));
            env.install(before, after);
            env.select(selected, S3ProtocolFixture.ETAG);
            var failure = assertThrows(ObjectCheckpointGraphException.class,
                    () -> env.preparer().prepare(ObjectCheckpointHeadCodec.encode(candidate), DEFAULTS));
            assertEquals(ObjectCheckpointGraphException.Code.JOURNAL_MISMATCH, failure.code());
            assertEquals(0, env.fixture.count("GET",
                    ObjectCheckpointGraphs.segmentKey(NAMESPACE, after.manifest().segments().getFirst().id()).value()));
            assertNoEffects(env, 3);
            assertNull(env.fixture.bytes(intentKey(candidate)));
            env.journal.append(THIRD);
            assertEquals(3, env.journal.appendedSequence(), "prefix mismatch is policy rejection, not journal corruption");
            assertNoMutablePut(env);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"object-bytes", "journal-bytes", "record-work", "object-reads"})
    void headKnownBudgetFailurePrecedesGraphFetchesAndAllDurabilityEffects(String limited) throws IOException {
        var graph = graph(2, List.of(List.of(FIRST, SECOND)));
        var candidate = first(graph);
        var limits = new ObjectCheckpointGraphs.Limits(
                limited.equals("object-bytes") ? graph.manifestImage().length - 1 : ObjectCheckpointGraphs.MAX_BYTES,
                limited.equals("journal-bytes") ? graph.prefix().byteLength() - 1 : ObjectCheckpointGraphs.MAX_BYTES,
                limited.equals("record-work") ? 3 : ObjectCheckpointGraphs.MAX_RECORD_WORK,
                limited.equals("object-reads") ? 0 : ObjectCheckpointGraphs.MAX_OBJECT_READS);
        try (var env = environment()) {
            env.append(List.of(FIRST, SECOND));
            env.install(graph);
            var failure = assertThrows(ObjectCheckpointGraphException.class,
                    () -> env.preparer().prepare(ObjectCheckpointHeadCodec.encode(candidate), limits));
            assertEquals(ObjectCheckpointGraphException.Code.LIMIT_EXCEEDED, failure.code());
            assertEquals(1, env.fixture.requests().size());
            assertEquals(1, env.fixture.count("GET", headKey()));
            assertNoEffects(env, 1);
            assertNull(env.fixture.bytes(intentKey(candidate)));
            assertNoMutablePut(env);
        }
    }

    @Test void replacementBudgetIncludesBothGraphsEvenForAnUnchangedManifest() throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        var selected = first(graph);
        var candidate = next(selected, graph);
        var limits = new ObjectCheckpointGraphs.Limits(ObjectCheckpointGraphs.MAX_BYTES,
                2 * graph.prefix().byteLength() - 1, ObjectCheckpointGraphs.MAX_RECORD_WORK, ObjectCheckpointGraphs.MAX_OBJECT_READS);
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            env.select(selected, S3ProtocolFixture.ETAG);
            var failure = assertThrows(ObjectCheckpointGraphException.class,
                    () -> env.preparer().prepare(ObjectCheckpointHeadCodec.encode(candidate), limits));
            assertEquals(ObjectCheckpointGraphException.Code.LIMIT_EXCEEDED, failure.code());
            assertEquals(1, env.fixture.requests().size());
            assertNoEffects(env, 3);
            assertNull(env.fixture.bytes(intentKey(candidate)));
            assertNoMutablePut(env);
        }
    }

    @Test void immutableIntentKeyIsPreciselyNamespaceAndWholeHeadContentAddressed() {
        var id = new ObjectId("0123456789abcdef".repeat(4));
        assertEquals("ns-00000000000000000000000000000001-head-" + id.value() + ".bin",
                S3PublicationPreparer.intentKey(NAMESPACE, id).value());
        assertThrows(IllegalArgumentException.class, () -> S3PublicationPreparer.intentKey(new UUID(0, 0), id));
        assertThrows(NullPointerException.class, () -> S3PublicationPreparer.intentKey(null, id));
        assertThrows(NullPointerException.class, () -> S3PublicationPreparer.intentKey(NAMESPACE, null));
    }

    private Environment environment() throws IOException { return environment(DATABASE); }

    private Environment environment(DatabaseId actualDatabase) throws IOException {
        try { return Environment.create(Files.createTempDirectory(directory, "preparer-"), actualDatabase); }
        catch (LocalS3PublicationState.DirectorySyncUnsupportedException | LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, () -> "Explicit directory-sync capability unavailable: " + unsupported.getMessage());
            throw unsupported;
        }
    }

    private static void assertPending(S3PublicationState state, Operation operation, String precondition, byte[] image) {
        var intent = state.pending().orElseThrow();
        assertEquals(operation, intent.operation());
        assertEquals(precondition, intent.precondition());
        assertArrayEquals(image, intent.image());
        assertEquals(ObjectReference.forBytes(image), intent.reference());
        byte[] copy = intent.image();
        copy[0] ^= 1;
        assertArrayEquals(image, intent.image());
    }

    private static void assertNoEffects(Environment env, long generation) throws IOException {
        assertEquals(0, env.journal.forcedSequence());
        assertEquals(generation, env.local.state().generation());
        assertTrue(env.local.state().pending().isEmpty());
        assertTrue(env.fixture.requests().stream().noneMatch(request -> request.method().equals("PUT")));
    }

    private static void assertNoMutablePut(Environment env) {
        assertEquals(0, env.fixture.count("PUT", headKey()));
        assertTrue(env.fixture.requests().stream().filter(request -> request.method().equals("PUT"))
                .allMatch(request -> request.key().contains("-head-") && request.key().endsWith(".bin")));
    }

    private static ObjectCheckpointGraphs.Limits exact(GraphFixture... graphs) {
        long objectBytes = 0;
        long journalBytes = 0;
        long work = 0;
        int reads = 0;
        for (var graph : graphs) {
            objectBytes += graph.manifestImage().length + graph.manifest().segments().stream().mapToLong(SegmentReference::byteLength).sum();
            journalBytes += graph.prefix().byteLength();
            work += 2 * graph.prefix().sequence();
            reads += 1 + graph.manifest().segments().size();
        }
        return new ObjectCheckpointGraphs.Limits(objectBytes, journalBytes, work, reads);
    }

    private static void assertUsage(ObjectCheckpointGraphs.Usage usage, GraphFixture... graphs) {
        var limits = exact(graphs);
        assertEquals(limits.maxObjectBytes(), usage.objectBytes());
        assertEquals(limits.maxJournalBytes(), usage.journalBytes());
        assertEquals(limits.maxRecordWork(), usage.recordWork());
        assertEquals(limits.maxObjectReads(), usage.objectReads());
        assertEquals(graphs.length, usage.journalReads());
    }

    private static GraphFixture graph(long generation, List<List<JournalRecord>> groups) {
        var references = new ArrayList<SegmentReference>();
        var segments = new LinkedHashMap<SegmentId, byte[]>();
        var journal = new ByteArrayOutputStream();
        journal.writeBytes(JournalCodec.encodeHeader(DATABASE));
        long count = 0;
        for (var group : groups) {
            var segment = new ImmutableSegment(DATABASE, group);
            byte[] image = SegmentCodec.encode(segment);
            var id = new SegmentId(hash(image));
            references.add(new SegmentReference(id, image.length, segment.firstSequence(), segment.lastSequence()));
            segments.put(id, image);
            for (var record : group) { journal.writeBytes(JournalCodec.encodeRecord(record)); count++; }
        }
        byte[] prefix = journal.toByteArray();
        var manifest = new CheckpointManifest(DATABASE, generation, new JournalPrefix(count, prefix.length, hash(prefix)), references);
        return new GraphFixture(manifest, ManifestCodec.encode(manifest), segments);
    }

    private record GraphFixture(CheckpointManifest manifest, byte[] manifestImage, Map<SegmentId, byte[]> segments) {
        JournalPrefix prefix() { return manifest.journalPrefix(); }
    }

    private static ObjectCheckpointHead first(GraphFixture graph) {
        return new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 10), LINEAGE, 1,
                ObjectReference.forBytes(graph.manifestImage()), graph.prefix(), null);
    }

    private static ObjectCheckpointHead next(ObjectCheckpointHead previous, GraphFixture graph) {
        return new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 11), LINEAGE, previous.revision() + 1,
                ObjectReference.forBytes(graph.manifestImage()), graph.prefix(), ObjectCheckpointHeadCodec.reference(previous));
    }

    private static JournalRecord record(long sequence, String request, long value) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(
                new WriteOperation.Put(new EntityId("salary"), new ValidTimeInterval(null, null), value))));
    }

    private static String hash(byte[] image) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    private static String headKey() { return S3CheckpointHeads.headKey(NAMESPACE).value(); }
    private static String intentKey(ObjectCheckpointHead head) {
        return S3PublicationPreparer.intentKey(NAMESPACE, ObjectCheckpointHeadCodec.reference(head).id()).value();
    }

    private static S3ObjectStore objectClient(Target target) {
        return new S3ObjectStore(target.endpoint(), Region.of(target.regionId()), target.bucket(), CREDENTIALS, TIMEOUTS);
    }

    private static S3CheckpointHeads headClient(Target target, S3CheckpointHeads.Binding binding) {
        return new S3CheckpointHeads(target.endpoint(), Region.of(target.regionId()), target.bucket(), CREDENTIALS, binding, TIMEOUTS);
    }

    private static final class Environment implements AutoCloseable {
        private S3ProtocolFixture fixture;
        private Target target;
        private Path localDirectory;
        private LocalCheckpointStore checkpoints;
        private LocalJournal journal;
        private LocalS3PublicationState local;
        private S3ObjectStore objects;
        private S3CheckpointHeads heads;

        static Environment create(Path directory, DatabaseId actualDatabase) throws IOException {
            var env = new Environment();
            try {
                env.checkpoints = LocalCheckpointStore.open(Files.createDirectory(directory.resolve("checkpoint")));
                env.journal = env.checkpoints.initialize(actualDatabase);
                env.fixture = new S3ProtocolFixture();
                env.target = new Target(env.fixture.endpoint(), "us-east-1", S3ProtocolFixture.BUCKET);
                env.localDirectory = Files.createDirectory(directory.resolve("publication"));
                env.local = LocalS3PublicationState.create(env.localDirectory, env.target, BINDING);
                env.objects = objectClient(env.target);
                env.heads = headClient(env.target, BINDING);
                return env;
            } catch (IOException | RuntimeException | Error failure) {
                try { env.close(); }
                catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
                throw failure;
            }
        }

        S3PublicationPreparer preparer() { return new S3PublicationPreparer(objects, heads, local, checkpoints, journal); }

        void append(List<JournalRecord> records) throws IOException {
            for (var record : records) { journal.append(record); }
        }

        void install(GraphFixture... graphs) {
            for (var graph : graphs) {
                fixture.put(ObjectCheckpointGraphs.manifestKey(NAMESPACE, ObjectReference.forBytes(graph.manifestImage()).id()).value(),
                        graph.manifestImage());
                graph.segments().forEach((id, image) -> fixture.put(ObjectCheckpointGraphs.segmentKey(NAMESPACE, id).value(), image));
            }
        }

        S3HeadObservation observation(byte[] image, String token) {
            return S3HeadObservation.restore(target.endpoint(), target.bucket(), image, new S3HeadToken(token));
        }

        void select(ObjectCheckpointHead selected, String token) throws IOException {
            byte[] image = ObjectCheckpointHeadCodec.encode(selected);
            local.prepare(image);
            local.recordSelected(observation(image, token));
            fixture.put(headKey(), image);
        }

        @Override public void close() throws IOException {
            IOException failed = null;
            for (AutoCloseable resource : new AutoCloseable[] {local, journal, checkpoints, heads, objects, fixture}) {
                if (resource == null) { continue; }
                try { resource.close(); }
                catch (Exception failure) {
                    if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                    var wrapped = failure instanceof IOException io ? io : new IOException("Fixture cleanup failed", failure);
                    if (failed == null) { failed = wrapped; }
                    else { failed.addSuppressed(wrapped); }
                }
            }
            if (failed != null) { throw failed; }
            if (fixture != null) { assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString()); }
        }
    }
}
