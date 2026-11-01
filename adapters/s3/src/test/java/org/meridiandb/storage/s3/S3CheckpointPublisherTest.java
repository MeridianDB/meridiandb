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
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectIntegrityException;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.s3.S3CheckpointPublicationException.Code;
import org.meridiandb.storage.s3.S3CheckpointPublisher.WriteReply;
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

class S3CheckpointPublisherTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final UUID OTHER = new UUID(0, 99);
    private static final DatabaseId DATABASE = new DatabaseId("publisher-db");
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
    void submitCreatesExactPendingHeadWithOneConditionalPutAndTwoGraphPasses(boolean nonempty) throws IOException {
        var graph = graph(17, nonempty ? List.of(List.of(FIRST)) : List.of());
        var candidate = first(graph);
        byte[] image = ObjectCheckpointHeadCodec.encode(candidate);
        S3CheckpointPublisher.Result result;
        Path localDirectory;
        Target target;
        try (var env = environment()) {
            env.append(nonempty ? List.of(FIRST) : List.of());
            env.install(graph);
            env.prepare(candidate);
            env.journal.append(nonempty ? SECOND : FIRST);
            long originalForced = env.journal.forcedSequence();
            int start = env.fixture.requests().size();
            result = env.publisher().submit(limits(2, graph));
            assertEquals(nonempty ? 1 : 0, originalForced);
            assertEquals(nonempty ? 2 : 1, result.forcedSequence());
            assertResult(result, candidate, WriteReply.ACKNOWLEDGED);
            assertEquals(3, result.state().generation());
            assertUsage(result.usage(), 2, graph);
            assertEquals(2, countSince(env, start, "GET", headKey()));
            assertEquals(1, countSince(env, start, "GET", intentKey(candidate)));
            assertEquals(1, countSince(env, start, "PUT", headKey()));
            assertNoImmutablePuts(env, start);
            var put = requestsSince(env, start).stream().filter(request -> request.method().equals("PUT")).findFirst().orElseThrow();
            assertEquals("*", put.header("if-none-match"));
            assertNull(put.header("if-match"));
            assertArrayEquals(image, put.body());
            assertArrayEquals(image, env.fixture.bytes(headKey()));
            assertEquals(env.fixture.state(headKey()).etag(), result.observation().token().value());
            assertArrayEquals(S3PublicationStateCodec.encode(result.state()), S3PublicationStateCodec.encode(env.local.state()));
            localDirectory = env.localDirectory;
            target = env.target;
            env.journal.append(nonempty ? THIRD : SECOND);
            assertEquals(nonempty ? 3 : 2, env.journal.appendedSequence());
            assertEquals(env.journal.path().orElseThrow(), env.checkpoints.journalPath());
            assertArrayEquals(image, env.heads.read().orElseThrow().image());
            try (var stored = env.objects.read(S3PublicationPreparer.intentKey(NAMESPACE, result.observation().reference().id()),
                    result.observation().reference())) {
                assertArrayEquals(image, stored.bytes());
            }
            int completed = env.fixture.requests().size();
            assertEquals(S3PublicationStateException.Code.INVALID_TRANSITION,
                    assertThrows(S3PublicationStateException.class, () -> env.publisher().submit(DEFAULTS)).code());
            assertEquals(completed, env.fixture.requests().size(), "a completed result grants no implicit new intent");
        }
        assertResult(result, candidate, WriteReply.ACKNOWLEDGED);
        byte[] returned = result.observation().image();
        returned[0] ^= 1;
        assertArrayEquals(image, result.observation().image());
        try (var reopened = LocalS3PublicationState.open(localDirectory, target, BINDING)) {
            assertArrayEquals(S3PublicationStateCodec.encode(result.state()), S3PublicationStateCodec.encode(reopened.state()));
            assertTrue(reopened.state().pending().isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void replacementUsesOriginalOpaqueTokenAndVerifiesOriginalPairTwiceIncludingRepacking(boolean repack) throws IOException {
        var before = graph(19, repack ? List.of(List.of(FIRST), List.of(SECOND)) : List.of(List.of(FIRST)));
        var after = graph(7, List.of(List.of(FIRST, SECOND)));
        var selected = first(before);
        var candidate = next(selected, after);
        try (var env = environment()) {
            env.append(List.of(FIRST, SECOND));
            env.install(before, after);
            var original = env.selectPrepared(selected);
            env.prepare(candidate);
            env.journal.append(THIRD);
            int start = env.fixture.requests().size();
            var result = env.publisher().submit(limits(2, before, after));
            assertResult(result, candidate, WriteReply.ACKNOWLEDGED);
            assertEquals(5, result.state().generation());
            assertEquals(3, result.forcedSequence());
            assertUsage(result.usage(), 2, before, after);
            assertEquals(2, countSince(env, start, "GET", headKey()));
            assertEquals(1, countSince(env, start, "PUT", headKey()));
            var put = requestsSince(env, start).stream().filter(request -> request.method().equals("PUT")).findFirst().orElseThrow();
            assertEquals(original.token().value(), put.header("if-match"));
            assertNull(put.header("if-none-match"));
            assertArrayEquals(ObjectCheckpointHeadCodec.encode(candidate), put.body());
            assertNotEquals(original.token(), result.observation().token(), "fixture assigns a fresh opaque token to selection");
            assertArrayEquals(ObjectCheckpointHeadCodec.encode(candidate), env.fixture.bytes(headKey()));
            assertNoImmutablePuts(env, start);
            if (repack) { assertEquals(selected.journalPrefix(), candidate.journalPrefix()); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anAlreadySelectedExactCandidateReconcilesOneFullPairWithoutPut(boolean reconcile) throws IOException {
        var before = graph(2, List.of(List.of(FIRST)));
        var after = graph(3, List.of(List.of(FIRST, SECOND)));
        var selected = first(before);
        var candidate = next(selected, after);
        try (var env = environment()) {
            env.append(List.of(FIRST, SECOND));
            env.install(before, after);
            env.selectPrepared(selected);
            env.prepare(candidate);
            var remote = env.fixture.put(headKey(), ObjectCheckpointHeadCodec.encode(candidate));
            env.journal.append(THIRD);
            int start = env.fixture.requests().size();
            var result = operate(env.publisher(), reconcile, limits(1, before, after));
            assertResult(result, candidate, WriteReply.NOT_ATTEMPTED);
            assertUsage(result.usage(), 1, before, after);
            assertEquals(remote.etag(), result.observation().token().value());
            assertEquals(3, result.forcedSequence());
            assertEquals(1, countSince(env, start, "GET", headKey()));
            assertEquals(1, countSince(env, start, "GET", intentKey(candidate)));
            assertTrue(requestsSince(env, start).stream().noneMatch(request -> request.method().equals("PUT")));
            assertEquals(5, env.local.state().generation());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void operationsRequireDurablePendingBeforeAnyRemoteIo(boolean reconcile) throws IOException {
        try (var env = environment()) {
            int start = env.fixture.requests().size();
            var publisher = env.publisher();
            assertEquals(S3PublicationStateException.Code.INVALID_TRANSITION,
                    assertThrows(S3PublicationStateException.class, () -> operate(publisher, reconcile, DEFAULTS)).code());
            assertEquals(start, env.fixture.requests().size());
            assertEquals(1, env.local.state().generation());
            assertEquals(0, env.journal.forcedSequence());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"object-endpoint", "object-region", "object-bucket", "head-endpoint",
            "head-region", "head-bucket", "head-namespace", "head-publisher", "head-lineage"})
    void actualClientConfigurationIsCheckedOnOperationBeforeAnyRequest(String changed) throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        var candidate = first(graph);
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            var pending = env.prepare(candidate).pending();
            env.journal.append(SECOND);
            int start = env.fixture.requests().size();
            var alternate = new Target(changed.endsWith("endpoint") ? URI.create(env.target.endpoint().toString() + "/") : env.target.endpoint(),
                    changed.endsWith("region") ? "us-west-2" : env.target.regionId(),
                    changed.endsWith("bucket") ? "other-bucket" : env.target.bucket());
            var binding = new S3CheckpointHeads.Binding(changed.equals("head-namespace") ? OTHER : NAMESPACE,
                    changed.equals("head-publisher") ? OTHER : PUBLISHER,
                    changed.equals("head-lineage") ? new ObjectId("0".repeat(64)) : LINEAGE);
            try (var objects = objectClient(changed.startsWith("object-") ? alternate : env.target);
                 var heads = headClient(changed.startsWith("head-") ? alternate : env.target, binding)) {
                var publisher = new S3CheckpointPublisher(objects, heads, env.local, env.checkpoints, env.journal);
                assertEquals(start, env.fixture.requests().size(), "constructor only captures caller resources");
                assertThrows(IllegalArgumentException.class, () -> publisher.submit(DEFAULTS));
                assertEquals(start, env.fixture.requests().size());
                assertRetained(env, pending, 1);
            }
        }
    }

    @Test void actualOwnedJournalLineageIsRecheckedAfterEarlierValidPreparation() throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            var pending = env.prepare(first(graph)).pending();
            env.replaceClosedJournal(new DatabaseId("another-lineage"), List.of(FIRST));
            int start = env.fixture.requests().size();
            long forced = env.journal.forcedSequence();
            assertThrows(IllegalArgumentException.class, () -> env.publisher().submit(DEFAULTS));
            assertEquals(start, env.fixture.requests().size());
            assertRetained(env, pending, forced);
        }
    }

    @Test void matchingLineageAtAnotherOwnedJournalPathDoesNotAuthorizePublication() throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        try (var env = environment(); var other = LocalJournal.create(directory.resolve("other.journal"), DATABASE)) {
            env.append(List.of(FIRST));
            env.install(graph);
            var pending = env.prepare(first(graph)).pending();
            other.append(FIRST);
            int start = env.fixture.requests().size();
            var publisher = new S3CheckpointPublisher(env.objects, env.heads, env.local, env.checkpoints, other);
            assertThrows(IOException.class, () -> publisher.submit(DEFAULTS));
            assertEquals(start, env.fixture.requests().size());
            assertEquals(0, other.forcedSequence());
            assertRetained(env, pending, 1);
            other.append(SECOND);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "truncated", "changed"})
    void existingImmutableIntentMustBeCompleteAndExactAndIsNeverRecreated(String damage) throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        var candidate = first(graph);
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            var pending = env.prepare(candidate).pending();
            env.journal.append(SECOND);
            String key = intentKey(candidate);
            if (damage.equals("missing")) { env.fixture.remove(key); }
            else if (damage.equals("truncated")) { env.fixture.put(key, new byte[255]); }
            else {
                byte[] changed = env.fixture.bytes(key);
                changed[100] ^= 1;
                env.fixture.put(key, changed);
            }
            int start = env.fixture.requests().size();
            var failure = assertThrows(IOException.class, () -> env.publisher().submit(DEFAULTS));
            if (damage.equals("missing")) { assertTrue(failure instanceof NoSuchFileException); }
            else { assertTrue(failure instanceof ObjectIntegrityException); }
            assertEquals(1, requestsSince(env, start).size());
            assertEquals(1, countSince(env, start, "GET", key));
            assertEquals(0, countSince(env, start, "GET", headKey()));
            assertRetained(env, pending, 1);
            assertTrue(requestsSince(env, start).stream().noneMatch(request -> request.method().equals("PUT")));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"selected", "candidate"})
    void exactCandidateObservationStillRequiresBothUndamagedGraphsWithoutFallback(String damaged) throws IOException {
        var before = graph(2, List.of(List.of(FIRST)));
        var after = graph(3, List.of(List.of(FIRST, SECOND)));
        var selected = first(before);
        var candidate = next(selected, after);
        try (var env = environment()) {
            env.append(List.of(FIRST, SECOND));
            env.install(before, after);
            env.selectPrepared(selected);
            var pending = env.prepare(candidate).pending();
            env.fixture.put(headKey(), ObjectCheckpointHeadCodec.encode(candidate));
            env.journal.append(THIRD);
            var graph = damaged.equals("selected") ? before : after;
            String key = segmentKey(graph.manifest().segments().getFirst().id());
            env.fixture.remove(key);
            int start = env.fixture.requests().size();
            assertThrows(NoSuchFileException.class, () -> env.publisher().reconcile(DEFAULTS));
            assertRetained(env, pending, 2);
            assertTrue(requestsSince(env, start).stream().noneMatch(request -> request.method().equals("PUT")));
            assertEquals(1, countSince(env, start, "GET", key));
            if (damaged.equals("selected")) {
                assertEquals(0, countSince(env, start, "GET", segmentKey(after.manifest().segments().getFirst().id())));
            }
        }
    }

    @Test void copiedSameLineageJournalMustStillMatchOriginalPreparedGraphBeforeSelection() throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        var candidate = first(graph);
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            var pending = env.prepare(candidate).pending();
            env.replaceClosedJournal(DATABASE, List.of(record(1, "other!", 900)));
            long forced = env.journal.forcedSequence();
            int start = env.fixture.requests().size();
            var failure = assertThrows(ObjectCheckpointGraphException.class, () -> env.publisher().submit(DEFAULTS));
            assertEquals(ObjectCheckpointGraphException.Code.JOURNAL_MISMATCH, failure.code());
            assertRetained(env, pending, forced);
            assertNull(env.fixture.bytes(headKey()));
            assertTrue(requestsSince(env, start).stream().noneMatch(request -> request.method().equals("PUT")));
            env.journal.append(SECOND);
            assertEquals(2, env.journal.appendedSequence(), "policy rejection leaves the actual journal usable");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"unexpected-create", "missing-replacement", "changed-body", "changed-token"})
    void observedConflictCannotRefreshTheOriginalPreconditionOrClearPending(String mismatch) throws IOException {
        var before = graph(2, List.of(List.of(FIRST)));
        var after = graph(3, List.of(List.of(FIRST, SECOND)));
        var selected = first(before);
        var candidate = mismatch.equals("unexpected-create") ? selected : next(selected, after);
        try (var env = environment()) {
            env.append(List.of(FIRST, SECOND));
            env.install(before, after);
            S3HeadObservation original = null;
            if (!mismatch.equals("unexpected-create")) { original = env.selectPrepared(selected); }
            var pending = env.prepare(candidate).pending();
            if (mismatch.equals("missing-replacement")) { env.fixture.remove(headKey()); }
            else if (mismatch.equals("changed-token")) {
                var sameBytes = env.fixture.put(headKey(), ObjectCheckpointHeadCodec.encode(selected));
                assertNotEquals(original.token().value(), sameBytes.etag());
            } else {
                var competing = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, OTHER, LINEAGE, 1,
                        selected.manifest(), selected.journalPrefix(), null);
                if (original == null) { env.fixture.put(headKey(), ObjectCheckpointHeadCodec.encode(competing)); }
                else { env.fixture.forceState(headKey(), ObjectCheckpointHeadCodec.encode(competing), original.token().value()); }
            }
            env.journal.append(THIRD);
            int start = env.fixture.requests().size();
            var failure = assertThrows(S3CheckpointPublicationException.class, () -> env.publisher().submit(DEFAULTS));
            assertEquals(Code.REMOTE_CONFLICT, failure.code());
            assertFalse(failure.writeAttempted());
            assertEquals(2, requestsSince(env, start).size(), "only exact immutable intent and current head are read");
            assertEquals(1, countSince(env, start, "GET", headKey()));
            assertTrue(requestsSince(env, start).stream().noneMatch(request -> request.method().equals("PUT")));
            assertRetained(env, pending, 2);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reconciliationOfOriginalHeadOrFreshAbsenceRemainsPendingWithoutGraphFetchOrPut(boolean replacement) throws IOException {
        var before = graph(2, List.of(List.of(FIRST)));
        var after = graph(3, List.of(List.of(FIRST, SECOND)));
        var selected = first(before);
        try (var env = environment()) {
            env.append(List.of(FIRST, SECOND));
            env.install(before, after);
            if (replacement) { env.selectPrepared(selected); }
            var pending = env.prepare(replacement ? next(selected, after) : selected).pending();
            env.journal.append(THIRD);
            int start = env.fixture.requests().size();
            var failure = assertThrows(S3CheckpointPublicationException.class, () -> env.publisher().reconcile(DEFAULTS));
            assertEquals(Code.STILL_PENDING, failure.code());
            assertFalse(failure.writeAttempted());
            assertEquals(2, requestsSince(env, start).size());
            assertTrue(requestsSince(env, start).stream().noneMatch(request -> request.method().equals("PUT")));
            assertRetained(env, pending, 2);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"object-bytes", "journal-bytes", "record-work", "object-reads"})
    void aSecondFullGraphPassIsReservedBeforeAnyForceOrConditionalWrite(String limited) throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        var candidate = first(graph);
        var full = limits(2, graph);
        var limit = new ObjectCheckpointGraphs.Limits(full.maxObjectBytes() - (limited.equals("object-bytes") ? 1 : 0),
                full.maxJournalBytes() - (limited.equals("journal-bytes") ? 1 : 0),
                full.maxRecordWork() - (limited.equals("record-work") ? 1 : 0),
                full.maxObjectReads() - (limited.equals("object-reads") ? 1 : 0));
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            var pending = env.prepare(candidate).pending();
            env.journal.append(SECOND);
            int start = env.fixture.requests().size();
            var failure = assertThrows(S3CheckpointPublicationException.class, () -> env.publisher().submit(limit));
            assertEquals(Code.LIMIT_EXCEEDED, failure.code());
            assertFalse(failure.writeAttempted());
            assertEquals(1, countSince(env, start, "GET", manifestKey(candidate)));
            assertEquals(1, countSince(env, start, "GET", segmentKey(graph.manifest().segments().getFirst().id())));
            assertTrue(requestsSince(env, start).stream().noneMatch(request -> request.method().equals("PUT")));
            assertNull(env.fixture.bytes(headKey()));
            assertRetained(env, pending, 1);
        }
    }

    @Test void unchangedPairCountsRepeatedDependenciesInBothPassesBeforeEffects() throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        var selected = first(graph);
        var candidate = next(selected, graph);
        var full = limits(2, graph, graph);
        var limit = new ObjectCheckpointGraphs.Limits(full.maxObjectBytes(), full.maxJournalBytes() - 1,
                full.maxRecordWork(), full.maxObjectReads());
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            env.selectPrepared(selected);
            var pending = env.prepare(candidate).pending();
            env.journal.append(SECOND);
            int start = env.fixture.requests().size();
            var failure = assertThrows(S3CheckpointPublicationException.class, () -> env.publisher().submit(limit));
            assertEquals(Code.LIMIT_EXCEEDED, failure.code());
            assertFalse(failure.writeAttempted());
            assertEquals(2, countSince(env, start, "GET", manifestKey(candidate)));
            assertEquals(2, countSince(env, start, "GET", segmentKey(graph.manifest().segments().getFirst().id())));
            assertTrue(requestsSince(env, start).stream().noneMatch(request -> request.method().equals("PUT")));
            assertRetained(env, pending, 1);
        }
    }

    @Test void fullLocalGenerationCapacityRejectsBeforeRemoteReadsAndRetainsPendingOnReopen() throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        var candidate = first(graph);
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            var prepared = env.prepare(candidate).pending();
            env.fillClosedLocalGenerations(prepared);
            var pending = env.local.state();
            assertEquals(LocalS3PublicationState.MAX_GENERATIONS, pending.generation());
            env.journal.append(SECOND);
            int start = env.fixture.requests().size();
            assertThrows(IOException.class, () -> env.publisher().submit(DEFAULTS));
            assertEquals(start, env.fixture.requests().size());
            assertEquals(1, env.journal.forcedSequence());
            assertNull(env.fixture.bytes(headKey()));
            env.reopenLocal();
            assertArrayEquals(S3PublicationStateCodec.encode(pending), S3PublicationStateCodec.encode(env.local.state()));
            assertArrayEquals(ObjectCheckpointHeadCodec.encode(candidate), env.local.state().pending().orElseThrow().image());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void exhaustedRestoredGenerationRejectsBeforeRemoteIoAndLeavesPendingOwnerHealthy(boolean reconcile) throws IOException {
        var graph = graph(2, List.of(List.of(FIRST)));
        var candidate = first(graph);
        try (var env = environment()) {
            env.append(List.of(FIRST));
            env.install(graph);
            var prepared = env.prepare(candidate).pending();
            env.restoreClosedLocalGeneration(prepared, Long.MAX_VALUE);
            var pending = env.local.state();
            assertEquals(Long.MAX_VALUE, pending.generation());
            env.journal.append(SECOND);
            int start = env.fixture.requests().size();
            assertEquals(S3PublicationStateException.Code.INVALID_TRANSITION,
                    assertThrows(S3PublicationStateException.class, () -> operate(env.publisher(), reconcile, DEFAULTS)).code());
            assertEquals(start, env.fixture.requests().size());
            assertRetained(env, pending, 1);
            env.reopenLocal();
            assertRetained(env, pending, 1);
            assertArrayEquals(ObjectCheckpointHeadCodec.encode(candidate), env.local.state().pending().orElseThrow().image());
        }
    }

    private Environment environment() throws IOException {
        try { return Environment.create(Files.createTempDirectory(directory, "publisher-")); }
        catch (LocalS3PublicationState.DirectorySyncUnsupportedException | LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, () -> "Explicit directory-sync capability unavailable: " + unsupported.getMessage());
            throw unsupported;
        }
    }

    private static S3CheckpointPublisher.Result operate(S3CheckpointPublisher publisher, boolean reconcile,
                                                        ObjectCheckpointGraphs.Limits limits) throws IOException {
        return reconcile ? publisher.reconcile(limits) : publisher.submit(limits);
    }

    private static void assertResult(S3CheckpointPublisher.Result result, ObjectCheckpointHead head, WriteReply reply) {
        assertTrue(result.state().pending().isEmpty());
        assertEquals(head, result.observation().head());
        assertArrayEquals(ObjectCheckpointHeadCodec.encode(head), result.observation().image());
        assertEquals(ObjectCheckpointHeadCodec.reference(head), result.observation().reference());
        assertEquals(reply, result.writeReply());
        assertEquals(reply != WriteReply.NOT_ATTEMPTED, result.writeAttempted());
        assertEquals(reply == WriteReply.ACKNOWLEDGED, result.writeAcknowledged());
        assertTrue(result.writeFailureCode().isEmpty());
    }

    private static void assertRetained(Environment env, S3PublicationState pending, long forcedSequence) throws IOException {
        assertArrayEquals(S3PublicationStateCodec.encode(pending), S3PublicationStateCodec.encode(env.local.state()));
        assertEquals(forcedSequence, env.journal.forcedSequence());
    }

    private static List<S3PublicationProtocolFixture.Request> requestsSince(Environment env, int start) {
        var requests = env.fixture.requests();
        return requests.subList(start, requests.size());
    }

    private static long countSince(Environment env, int start, String method, String key) {
        return requestsSince(env, start).stream().filter(request -> request.method().equals(method) && request.key().equals(key)).count();
    }

    private static void assertNoImmutablePuts(Environment env, int start) {
        assertTrue(requestsSince(env, start).stream().filter(request -> request.method().equals("PUT"))
                .allMatch(request -> request.key().equals(headKey())));
    }

    private static ObjectCheckpointGraphs.Limits limits(int passes, GraphFixture... graphs) {
        long objectBytes = 0;
        long journalBytes = 0;
        long recordWork = 0;
        int objectReads = 0;
        for (var graph : graphs) {
            objectBytes += graph.manifestImage().length + graph.manifest().segments().stream().mapToLong(SegmentReference::byteLength).sum();
            journalBytes += graph.prefix().byteLength();
            recordWork += 2 * graph.prefix().sequence();
            objectReads += 1 + graph.manifest().segments().size();
        }
        return new ObjectCheckpointGraphs.Limits(passes * objectBytes, passes * journalBytes, passes * recordWork, passes * objectReads);
    }

    private static void assertUsage(ObjectCheckpointGraphs.Usage usage, int passes, GraphFixture... graphs) {
        var expected = limits(passes, graphs);
        assertEquals(expected.maxObjectBytes(), usage.objectBytes());
        assertEquals(expected.maxJournalBytes(), usage.journalBytes());
        assertEquals(expected.maxRecordWork(), usage.recordWork());
        assertEquals(expected.maxObjectReads(), usage.objectReads());
        assertEquals(passes * graphs.length, usage.journalReads());
    }

    private static GraphFixture graph(long generation, List<List<JournalRecord>> groups) {
        var references = new ArrayList<SegmentReference>();
        var segments = new LinkedHashMap<SegmentId, byte[]>();
        var records = new ArrayList<JournalRecord>();
        for (var group : groups) {
            var segment = new ImmutableSegment(DATABASE, group);
            byte[] image = SegmentCodec.encode(segment);
            var id = new SegmentId(hash(image));
            references.add(new SegmentReference(id, image.length, segment.firstSequence(), segment.lastSequence()));
            segments.put(id, image);
            records.addAll(group);
        }
        byte[] prefix = journalImage(DATABASE, records);
        var manifest = new CheckpointManifest(DATABASE, generation, new JournalPrefix(records.size(), prefix.length, hash(prefix)), references);
        return new GraphFixture(manifest, ManifestCodec.encode(manifest), segments);
    }

    private static byte[] journalImage(DatabaseId database, List<JournalRecord> records) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(database));
        for (var record : records) { bytes.writeBytes(JournalCodec.encodeRecord(record)); }
        return bytes.toByteArray();
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

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    private static String headKey() { return S3CheckpointHeads.headKey(NAMESPACE).value(); }
    private static String intentKey(ObjectCheckpointHead head) {
        return S3PublicationPreparer.intentKey(NAMESPACE, ObjectCheckpointHeadCodec.reference(head).id()).value();
    }
    private static String manifestKey(ObjectCheckpointHead head) {
        return ObjectCheckpointGraphs.manifestKey(NAMESPACE, head.manifest().id()).value();
    }
    private static String segmentKey(SegmentId id) { return ObjectCheckpointGraphs.segmentKey(NAMESPACE, id).value(); }

    private static S3ObjectStore objectClient(Target target) {
        return new S3ObjectStore(target.endpoint(), Region.of(target.regionId()), target.bucket(), CREDENTIALS, TIMEOUTS);
    }
    private static S3CheckpointHeads headClient(Target target, S3CheckpointHeads.Binding binding) {
        return new S3CheckpointHeads(target.endpoint(), Region.of(target.regionId()), target.bucket(), CREDENTIALS, binding, TIMEOUTS);
    }

    private static final class Environment implements AutoCloseable {
        private S3PublicationProtocolFixture fixture;
        private Target target;
        private Path checkpointDirectory;
        private Path localDirectory;
        private LocalCheckpointStore checkpoints;
        private LocalJournal journal;
        private LocalS3PublicationState local;
        private S3ObjectStore objects;
        private S3CheckpointHeads heads;

        static Environment create(Path directory) throws IOException {
            var env = new Environment();
            try {
                env.checkpointDirectory = Files.createDirectory(directory.resolve("checkpoint"));
                env.checkpoints = LocalCheckpointStore.open(env.checkpointDirectory);
                env.journal = env.checkpoints.initialize(DATABASE);
                env.fixture = new S3PublicationProtocolFixture();
                env.target = new Target(env.fixture.endpoint(), "us-east-1", S3PublicationProtocolFixture.BUCKET);
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

        S3CheckpointPublisher publisher() { return new S3CheckpointPublisher(objects, heads, local, checkpoints, journal); }

        S3PublicationPreparer.Preparation prepare(ObjectCheckpointHead head) throws IOException {
            return new S3PublicationPreparer(objects, heads, local, checkpoints, journal)
                    .prepare(ObjectCheckpointHeadCodec.encode(head), DEFAULTS);
        }

        void append(List<JournalRecord> records) throws IOException {
            for (var record : records) { journal.append(record); }
        }

        void install(GraphFixture... graphs) {
            for (var graph : graphs) {
                fixture.put(ObjectCheckpointGraphs.manifestKey(NAMESPACE, ObjectReference.forBytes(graph.manifestImage()).id()).value(),
                        graph.manifestImage());
                graph.segments().forEach((id, image) -> fixture.put(segmentKey(id), image));
            }
        }

        S3HeadObservation selectPrepared(ObjectCheckpointHead head) throws IOException {
            prepare(head);
            byte[] image = ObjectCheckpointHeadCodec.encode(head);
            var remote = fixture.put(headKey(), image);
            var observed = S3HeadObservation.restore(target.endpoint(), target.bucket(), remote.bytes(), new S3HeadToken(remote.etag()));
            local.recordSelected(observed);
            return observed;
        }

        void replaceClosedJournal(DatabaseId database, List<JournalRecord> records) throws IOException {
            Path path = journal.path().orElseThrow();
            journal.close();
            journal = null;
            checkpoints.close();
            checkpoints = null;
            Files.write(path, journalImage(database, records), StandardOpenOption.TRUNCATE_EXISTING);
            checkpoints = LocalCheckpointStore.open(checkpointDirectory);
            journal = LocalJournal.open(path);
        }

        void fillClosedLocalGenerations(S3PublicationState pending) throws IOException {
            local.close();
            local = null;
            for (int generation = 3; generation <= LocalS3PublicationState.MAX_GENERATIONS; generation++) {
                writeDeclaredGeneration(pending, generation);
            }
            local = LocalS3PublicationState.open(localDirectory, target, BINDING);
        }

        void restoreClosedLocalGeneration(S3PublicationState pending, long generation) throws IOException {
            local.close();
            local = null;
            writeDeclaredGeneration(pending, generation);
            local = LocalS3PublicationState.open(localDirectory, target, BINDING);
        }

        private void writeDeclaredGeneration(S3PublicationState pending, long generation) throws IOException {
            var state = S3PublicationState.restore(generation, target, BINDING, pending.expected().orElse(null),
                    pending.pending().orElseThrow().image());
            byte[] image = S3PublicationStateCodec.encode(state);
            String name = String.format(Locale.ROOT, "publication-%019d-%s.state", generation, ObjectReference.forBytes(image).id().value());
            Files.write(localDirectory.resolve(name), image, StandardOpenOption.CREATE_NEW);
        }

        void reopenLocal() throws IOException {
            local.close();
            local = null;
            local = LocalS3PublicationState.open(localDirectory, target, BINDING);
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
