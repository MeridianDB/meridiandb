// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.internal.LocalFileOwnership;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalFormatException;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ManifestCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphs;
import org.meridiandb.storage.manifest.ObjectCheckpointGraphException;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadException;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadVerifier;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.s3.S3PublicationState.Target;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/** Actual SDK/loopback plus owned local recovery; no live service or replicated-durability claim. */
@Timeout(45)
class S3DatabaseTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final UUID OTHER = new UUID(0, 99);
    private static final DatabaseId DATABASE = new DatabaseId("managed-s3-recovery");
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(
            NAMESPACE, PUBLISHER, ObjectCheckpointHeadVerifier.lineageId(DATABASE));
    private static final ObjectCheckpointGraphs.Limits LIMITS = ObjectCheckpointGraphs.DEFAULT_LIMITS;
    private static final String TOKEN = "\"original,opaque\\token\"";
    private static final EntityId SALARY = new EntityId("salary");
    private static final EntityId BONUS = new EntityId("bonus");
    private static final List<JournalRecord> HISTORY = List.of(
            new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 100)))),
            new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(10L, 20L), 120),
                    new WriteOperation.Put(BONUS, new ValidTimeInterval(null, null), 7)))),
            new JournalRecord(3, new Transaction(new RequestId("retraction"), List.of(
                    new WriteOperation.Retract(SALARY, new ValidTimeInterval(12L, 14L))))),
            new JournalRecord(4, new Transaction(new RequestId("same-value"), List.of(
                    new WriteOperation.Put(SALARY, new ValidTimeInterval(10L, 12L), 120)))));
    private static final S3ObjectStore.Timeouts TIMEOUTS = new S3ObjectStore.Timeouts(
            Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(2));

    @TempDir Path directory;

    @ParameterizedTest @ValueSource(ints = {0, 2, 4})
    void exactFreshHeadRecoversGenesisSplitAndFullHistoryWithOriginalNoopIdentity(int cutoff) throws IOException {
        try (var env = environment(cutoff)) {
            var beforeDatabase = snapshot(env.databaseDirectory);
            var beforeMetadata = snapshot(env.stateDirectory);
            int before = env.fixture.requests().size();
            try (var database = env.open(4)) {
                assertHistoricalAnswers(database);
                assertEquals(Optional.of(position(cutoff)), database.checkpointPosition());
                assertArrayEquals(env.expected.image(), database.observation().image());
                assertEquals(env.expected.token(), database.observation().token());
                assertEquals(env.expected.reference(), database.observation().reference());
                assertEquals(env.target.endpoint(), database.observation().endpoint());
                assertEquals(env.target.bucket(), database.observation().bucket());
                assertEquals(headKey(), database.observation().key().value());
                var returned = database.observation().image();
                returned[0] ^= 1;
                assertArrayEquals(env.expected.image(), database.observation().image());
            }
            var requests = env.fixture.requests().subList(before, env.fixture.requests().size());
            assertEquals(headKey(), requests.getFirst().key());
            assertEquals(1, env.fixture.count("GET", headKey()));
            assertEquals(cutoff + 2, requests.size(), "One current head, one manifest and one read per segment");
            env.graph.keys().forEach(key -> assertEquals(1, env.fixture.count("GET", key)));
            assertNoWritesSince(env, before);
            assertReleased(env, env.savedState);
            assertImages(beforeDatabase, snapshot(env.databaseDirectory));
            assertImages(beforeMetadata, snapshot(env.stateDirectory));
        }
    }

    @Test void callerClientsCanCloseWhileRecoveredHeapQueriesAndObservationRemainUsable() throws IOException {
        try (var env = environment(2)) {
            S3HeadObservation observation;
            LocalDatabase.ReadView view;
            TemporalResult captured;
            var database = env.open(4);
            try {
                observation = database.observation();
                view = database.openView(ReadCutoff.Head.INSTANCE);
                captured = view.query(new TemporalQuery.Scan(SALARY, new ValidTimeInterval(8L, 16L)));
                env.objects.close();
                env.heads.close();
                env.fixture.forceState(headKey(), env.expected.image(), "\"later-token\"");
                assertEquals(0, env.credentials.closes);
                assertHistoricalAnswers(database);
                assertEquals(expectedScan(), view.query(new TemporalQuery.Scan(SALARY, new ValidTimeInterval(8L, 16L))));
                assertEquals(env.expected.token(), database.observation().token());
            } finally { database.close(); }
            assertThrows(IllegalStateException.class, database::head);
            assertThrows(IllegalStateException.class, database::observation);
            assertThrows(IllegalStateException.class, view::position);
            assertEquals(expectedScan(), captured);
            assertEquals(TOKEN, observation.token().value());
            assertEquals(0, env.credentials.closes);
            assertReleased(env, env.savedState);
            view.close();
        }
    }

    @Test void wrapperCloseLeavesSuppliedSdkClientsAndCredentialsAvailableForOtherReads() throws IOException {
        try (var env = environment(2)) {
            try (var database = env.open(4)) { assertEquals(position(4), database.head()); }
            assertEquals(env.expected.token(), env.heads.read().orElseThrow().token());
            try (var object = env.objects.read(env.graph.manifestKey(), ObjectReference.forBytes(env.graph.manifestImage()))) {
                assertArrayEquals(env.graph.manifestImage(), object.bytes());
            }
            assertEquals(0, env.credentials.closes);
            assertEquals("fixture-access", env.credentials.resolveCredentials().accessKeyId());
            assertReleased(env, env.savedState);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"fresh", "pending-create", "pending-old", "pending-candidate"})
    void absentExpectedAndPendingStateRefuseBeforeHeadGetAndSurviveStateReopen(String state) throws IOException {
        try (var env = environment(2)) {
            if (state.equals("fresh") || state.equals("pending-create")) {
                env.freshState();
                if (state.equals("pending-create")) {
                    env.fixture.remove(headKey());
                    try (var local = LocalS3PublicationState.open(env.stateDirectory, env.target, BINDING);
                         var checkpoints = LocalCheckpointStore.open(env.databaseDirectory);
                         var journal = LocalJournal.open(checkpoints.journalPath())) {
                        env.savedState = new S3PublicationPreparer(env.objects, env.heads, local, checkpoints, journal)
                                .prepare(env.expected.image(), LIMITS).pending();
                    }
                }
            } else { env.pending(state.equals("pending-candidate")); }
            byte[] stateImage = S3PublicationStateCodec.encode(env.savedState);
            var before = snapshot(env.stateDirectory);
            int requests = env.fixture.requests().size();
            var exposed = new AtomicReference<S3Database>();
            assertThrows(IOException.class, () -> exposed.set(env.open(4)));
            assertNull(exposed.get());
            assertEquals(requests, env.fixture.requests().size());
            assertImages(before, snapshot(env.stateDirectory));
            assertReleased(env, env.savedState);
            try (var reopened = LocalS3PublicationState.open(env.stateDirectory, env.target, BINDING)) {
                assertArrayEquals(stateImage, S3PublicationStateCodec.encode(reopened.state()));
                assertEquals(!state.equals("fresh"), reopened.state().pending().isPresent());
            }
        }
    }

    @Test void explicitExistingPublisherReconciliationEnablesManagedOpenWithoutStartupAdoptionOrPut() throws IOException {
        try (var env = environment(2)) {
            env.pending(true);
            int before = env.fixture.requests().size();
            assertThrows(IOException.class, () -> env.open(4));
            assertEquals(before, env.fixture.requests().size());
            long mutablePuts = env.fixture.count("PUT", headKey());
            try (var local = LocalS3PublicationState.open(env.stateDirectory, env.target, BINDING);
                 var checkpoints = LocalCheckpointStore.open(env.databaseDirectory);
                 var journal = LocalJournal.open(checkpoints.journalPath())) {
                var resolved = new S3CheckpointPublisher(env.objects, env.heads, local, checkpoints, journal).reconcile(LIMITS);
                assertFalse(resolved.writeAttempted());
                env.savedState = resolved.state();
                env.expected = resolved.observation();
            }
            assertEquals(mutablePuts, env.fixture.count("PUT", headKey()));
            before = env.fixture.requests().size();
            try (var database = env.open(4)) {
                assertHistoricalAnswers(database);
                assertEquals(Optional.of(position(4)), database.checkpointPosition());
                assertArrayEquals(env.expected.image(), database.observation().image());
                assertEquals(env.expected.token(), database.observation().token());
            }
            assertNoWritesSince(env, before);
            assertReleased(env, env.savedState);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"endpoint", "region", "bucket", "namespace", "publisher", "lineage"})
    void managedConfigurationMustMatchDurableStateBeforeAnyHeadRead(String changed) throws IOException {
        try (var env = environment(2)) {
            var target = alternateTarget(env.target, changed);
            var binding = alternateBinding(changed);
            int before = env.fixture.requests().size();
            try (var objects = objects(target, env.credentials); var heads = heads(target, binding, env.credentials)) {
                var failure = assertThrows(S3PublicationStateException.class,
                        () -> S3Database.open(env.databaseDirectory, env.stateDirectory, target, binding, objects, heads, LIMITS));
                assertEquals(S3PublicationStateException.Code.INVALID_BINDING, failure.code());
            }
            assertEquals(before, env.fixture.requests().size());
            assertReleased(env, env.savedState);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"object-endpoint", "object-region", "object-bucket", "head-endpoint",
            "head-region", "head-bucket", "head-namespace", "head-publisher", "head-lineage", "object-closed", "head-closed"})
    void actualClientScopeAndBindingAndUsabilityAreAdmittedBeforeHeadGet(String changed) throws IOException {
        try (var env = environment(2)) {
            int before = env.fixture.requests().size();
            if (changed.equals("object-closed") || changed.equals("head-closed")) {
                if (changed.equals("object-closed")) { env.objects.close(); }
                else { env.heads.close(); }
                assertThrows(IllegalStateException.class, () -> env.open(4));
            } else {
                String dimension = changed.substring(changed.indexOf('-') + 1);
                var target = alternateTarget(env.target, dimension);
                var binding = alternateBinding(dimension);
                try (var objects = objects(changed.startsWith("object-") ? target : env.target, env.credentials);
                     var heads = heads(changed.startsWith("head-") ? target : env.target,
                             changed.startsWith("head-") ? binding : BINDING, env.credentials)) {
                    assertThrows(IllegalArgumentException.class,
                            () -> S3Database.open(env.databaseDirectory, env.stateDirectory, env.target, BINDING, objects, heads, LIMITS));
                }
            }
            assertEquals(before, env.fixture.requests().size());
            assertReleased(env, env.savedState);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "foreign-namespace", "foreign-publisher", "foreign-lineage",
            "stale-image", "token-only", "corrupt", "forbidden", "dropped-head-reply"})
    void freshHeadMustMatchBothExactSavedImageAndOpaqueToken(String damage) throws IOException {
        try (var env = environment(2)) {
            byte[] image = env.expected.image();
            if (damage.equals("missing")) { env.fixture.remove(headKey()); }
            else if (damage.equals("forbidden")) { env.fixture.fault("GET", headKey(), S3PublicationProtocolFixture.Fault.FORBIDDEN); }
            else if (damage.equals("dropped-head-reply")) { env.fixture.fault("GET", headKey(), S3PublicationProtocolFixture.Fault.DROP_BEFORE); }
            else if (damage.equals("token-only")) { env.fixture.forceState(headKey(), image, "\"different-token\""); }
            else {
                if (damage.equals("corrupt")) { image[0] ^= 1; }
                else {
                    var original = env.expected.head();
                    var changed = new ObjectCheckpointHead(damage.equals("foreign-namespace") ? OTHER : NAMESPACE,
                            damage.equals("foreign-publisher") ? OTHER : PUBLISHER,
                            damage.equals("stale-image") ? OTHER : original.publicationId(),
                            damage.equals("foreign-lineage") ? new ObjectId("f".repeat(64)) : BINDING.lineageId(),
                            original.revision(), original.manifest(), original.journalPrefix(), original.predecessor());
                    image = ObjectCheckpointHeadCodec.encode(changed);
                }
                env.fixture.forceState(headKey(), image, TOKEN);
            }
            var beforeState = snapshot(env.stateDirectory);
            int before = env.fixture.requests().size();
            assertThrows(IOException.class, () -> env.open(4));
            var requests = env.fixture.requests().subList(before, env.fixture.requests().size());
            if (damage.equals("dropped-head-reply")) {
                assertFalse(requests.isEmpty());
                assertTrue(requests.stream().allMatch(request -> request.key().equals(headKey())),
                        "A native dropped-response retry may only repeat the head GET");
            } else { assertEquals(1, requests.size(), "Only the fresh current head may be read on mismatch"); }
            assertEquals(headKey(), requests.getLast().key());
            assertNoWritesSince(env, before);
            assertImages(beforeState, snapshot(env.stateDirectory));
            assertReleased(env, env.savedState);
        }
    }

    @Test void corruptNewestDurableStateReturnsNoDatabaseWithoutHeadGetOrFallback() throws IOException {
        try (var env = environment(2)) {
            Path newest = selectedFile(env.stateDirectory, env.savedState);
            byte[] corrupt = Files.readAllBytes(newest);
            corrupt[0] ^= 1;
            Files.write(newest, corrupt);
            var beforeState = snapshot(env.stateDirectory);
            int before = env.fixture.requests().size();
            var failure = assertThrows(S3PublicationStateException.class, () -> env.open(4));
            assertEquals(S3PublicationStateException.Code.CORRUPT, failure.code());
            assertEquals(before, env.fixture.requests().size());
            assertImages(beforeState, snapshot(env.stateDirectory));
            var repeated = assertThrows(S3PublicationStateException.class, () -> {
                try (var rejected = LocalS3PublicationState.open(env.stateDirectory, env.target, BINDING)) { rejected.state(); }
            });
            assertEquals(S3PublicationStateException.Code.CORRUPT, repeated.code(), "A second decode reaches the same evidence after owner cleanup");
            assertLocalOwnersReleased(env.databaseDirectory);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing-manifest", "corrupt-manifest", "missing-late-segment", "corrupt-late-segment"})
    void missingOrCorruptImmutableDependenciesReleaseAllStartupOwnersAndPreserveMetadata(String damage) throws IOException {
        try (var env = environment(2)) {
            String key = damage.endsWith("manifest") ? env.graph.manifestKey().value() : env.graph.keys().getLast();
            if (damage.startsWith("missing")) { env.fixture.remove(key); }
            else {
                byte[] corrupt = env.fixture.bytes(key);
                corrupt[corrupt.length - 1] ^= 1;
                env.fixture.put(key, corrupt);
            }
            var beforeDatabase = snapshot(env.databaseDirectory);
            var beforeState = snapshot(env.stateDirectory);
            int before = env.fixture.requests().size();
            var exposed = new AtomicReference<S3Database>();
            assertThrows(IOException.class, () -> exposed.set(env.open(4)));
            assertNull(exposed.get());
            assertEquals(1, env.fixture.count("GET", headKey()));
            assertNoWritesSince(env, before);
            assertReleased(env, env.savedState);
            assertImages(beforeState, snapshot(env.stateDirectory));
            assertImages(beforeDatabase, snapshot(env.databaseDirectory));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"foreign", "divergent-prefix", "missing-journal", "torn-suffix",
            "duplicate-suffix", "known-clean-tail-loss", "zero-budget"})
    void actualLocalJournalAndCoverageAreVerifiedWithoutRepairOrExposingDatabase(String damage) throws IOException {
        try (var env = environment(2)) {
            Path journal = env.databaseDirectory.resolve("transactions.journal");
            var limits = LIMITS;
            if (damage.equals("foreign")) { Files.write(journal, journalImage(new DatabaseId("foreign"), HISTORY)); }
            else if (damage.equals("divergent-prefix")) {
                var changed = new ArrayList<>(HISTORY);
                changed.set(0, new JournalRecord(1, new Transaction(HISTORY.getFirst().transaction().requestId(), List.of(
                        new WriteOperation.Put(SALARY, new ValidTimeInterval(null, null), 101)))));
                Files.write(journal, journalImage(DATABASE, changed));
            } else if (damage.equals("missing-journal")) { Files.delete(journal); }
            else if (damage.equals("torn-suffix")) {
                byte[] original = Files.readAllBytes(journal);
                Files.write(journal, Arrays.copyOf(original, original.length + 1));
            } else if (damage.equals("duplicate-suffix")) {
                var changed = new ArrayList<>(HISTORY);
                changed.add(new JournalRecord(5, HISTORY.getFirst().transaction()));
                Files.write(journal, journalImage(DATABASE, changed));
            } else if (damage.equals("known-clean-tail-loss")) { Files.write(journal, journalImage(DATABASE, HISTORY.subList(0, 2))); }
            else { limits = new ObjectCheckpointGraphs.Limits(0, 0, 0, 0); }
            var chosen = limits;
            var beforeDatabase = snapshot(env.databaseDirectory);
            var beforeState = snapshot(env.stateDirectory);
            int before = env.fixture.requests().size();
            var exposed = new AtomicReference<S3Database>();
            Executable open = () -> exposed.set(S3Database.open(env.databaseDirectory, env.stateDirectory,
                    env.target, BINDING, env.objects, env.heads, chosen, 4));
            switch (damage) {
                case "foreign" -> assertEquals(ObjectCheckpointHeadException.Code.INVALID_BINDING,
                        assertThrows(ObjectCheckpointHeadException.class, open).code());
                case "divergent-prefix" -> assertEquals(ObjectCheckpointGraphException.Code.JOURNAL_MISMATCH,
                        assertThrows(ObjectCheckpointGraphException.class, open).code());
                case "zero-budget" -> assertEquals(ObjectCheckpointGraphException.Code.LIMIT_EXCEEDED,
                        assertThrows(ObjectCheckpointGraphException.class, open).code());
                case "duplicate-suffix" -> assertEquals(JournalFormatException.Code.INVALID_HISTORY,
                        assertThrows(JournalFormatException.class, open).code());
                case "torn-suffix" -> assertThrows(LocalJournal.IncompleteTailException.class, open);
                case "missing-journal" -> assertThrows(NoSuchFileException.class, open);
                case "known-clean-tail-loss" -> assertTrue(assertThrows(IOException.class, open).getMessage().contains("shorter than required"));
                default -> throw new AssertionError(damage);
            }
            assertNull(exposed.get());
            assertEquals(1, env.fixture.count("GET", headKey()));
            if (!damage.equals("divergent-prefix")) {
                assertEquals(before + 1, env.fixture.requests().size(), "Local admission/budget refusal precedes immutable dependency reads");
            }
            assertNoWritesSince(env, before);
            assertReleased(env, env.savedState);
            assertImages(beforeDatabase, snapshot(env.databaseDirectory));
            assertImages(beforeState, snapshot(env.stateDirectory));
        }
    }

    @Test void defaultCoverageReportsAvailableHeadWithoutInventingAnUnknownCleanlyLostTail() throws IOException {
        try (var env = environment(2)) {
            Files.write(env.databaseDirectory.resolve("transactions.journal"), journalImage(DATABASE, HISTORY.subList(0, 2)));
            try (var database = S3Database.open(env.databaseDirectory, env.stateDirectory, env.target, BINDING,
                    env.objects, env.heads, LIMITS)) {
                assertEquals(position(2), database.head());
                assertEquals(Optional.of(position(2)), database.checkpointPosition());
                assertEquals(Optional.empty(), database.lookupRequest(HISTORY.get(2).transaction().requestId()));
                assertEquals(Optional.empty(), database.lookupRequest(HISTORY.get(3).transaction().requestId()));
                try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
                    assertEquals(point(120), view.query(new TemporalQuery.Get(SALARY, 13)));
                }
            }
            assertThrows(IOException.class, () -> env.open(4));
            assertReleased(env, env.savedState);
        }
    }

    @Test void anotherActualJournalDirectoryWithMatchingEvidenceDoesNotInventMetadataPathBinding() throws IOException {
        try (var env = environment(2)) {
            Path alternate = Files.createDirectory(env.home.resolve("alternate-database"));
            Files.write(alternate.resolve("transactions.journal"), journalImage(DATABASE, HISTORY));
            try (var database = S3Database.open(alternate, env.stateDirectory, env.target, BINDING,
                    env.objects, env.heads, LIMITS, 4)) {
                assertHistoricalAnswers(database);
                assertEquals(env.expected.token(), database.observation().token());
            }
            assertLocalOwnersReleased(alternate);
            assertReleased(env, env.savedState);
        }
    }

    private Environment environment(int cutoff) throws IOException {
        try { return Environment.create(Files.createDirectory(directory.resolve("home")), cutoff); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException | LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Required directory synchronization unavailable: " + unsupported);
            throw unsupported;
        }
    }

    private static void assertHistoricalAnswers(S3Database database) {
        assertEquals(position(4), database.head());
        for (var record : HISTORY) {
            assertEquals(Optional.of(position(record.sequence())), database.lookupRequest(record.transaction().requestId()));
        }
        assertEquals(Optional.empty(), database.lookupRequest(new RequestId("never-submitted")));
        for (int cutoff = 0; cutoff <= 4; cutoff++) {
            try (var view = database.openView(new ReadCutoff.At(position(cutoff)))) {
                assertEquals(position(cutoff), view.position());
                assertEquals(cutoff == 0 ? absent() : cutoff == 1 ? point(100) : cutoff == 2 ? point(120) : absent(),
                        view.query(new TemporalQuery.Get(SALARY, 13)));
                assertEquals(cutoff == 0 ? absent() : cutoff == 1 ? point(100) : point(120),
                        view.query(new TemporalQuery.Get(SALARY, 11)));
                assertEquals(cutoff < 2 ? absent() : point(7), view.query(new TemporalQuery.Get(BONUS, 13)));
            }
        }
        try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
            assertEquals(expectedScan(), view.query(new TemporalQuery.Scan(SALARY, new ValidTimeInterval(8L, 16L))));
            assertEquals(new TemporalResult.Aggregate(1, BigInteger.valueOf(7)),
                    view.query(new TemporalQuery.Sum(List.of(SALARY, BONUS), 13)));
            assertEquals(new TemporalResult.Aggregate(2, BigInteger.valueOf(127)),
                    view.query(new TemporalQuery.Sum(List.of(SALARY, BONUS), 11)));
        }
    }

    private static TemporalResult.Point point(long value) { return new TemporalResult.Point(OptionalLong.of(value)); }
    private static TemporalResult.Point absent() { return new TemporalResult.Point(OptionalLong.empty()); }
    private static CommitPosition position(long sequence) { return new CommitPosition(DATABASE, sequence); }
    private static TemporalResult.Segments expectedScan() {
        return new TemporalResult.Segments(List.of(
                new TemporalSegment(new ValidTimeInterval(8L, 10L), 100),
                new TemporalSegment(new ValidTimeInterval(10L, 12L), 120),
                new TemporalSegment(new ValidTimeInterval(14L, 16L), 120)));
    }

    private static void assertNoWritesSince(Environment env, int before) {
        assertTrue(env.fixture.requests().subList(before, env.fixture.requests().size()).stream()
                .allMatch(request -> request.method().equals("GET")), "Managed recovery must not PUT or mutate the provider");
    }

    private static void assertReleased(Environment env, S3PublicationState expected) throws IOException {
        try (var state = LocalS3PublicationState.open(env.stateDirectory, env.target, BINDING)) {
            assertArrayEquals(S3PublicationStateCodec.encode(expected), S3PublicationStateCodec.encode(state.state()));
        }
        assertLocalOwnersReleased(env.databaseDirectory);
    }

    private static void assertLocalOwnersReleased(Path directory) throws IOException {
        try (var store = LocalCheckpointStore.open(directory)) { assertEquals(directory.toRealPath().resolve("transactions.journal"), store.journalPath()); }
        // Reservation does not scan or alter deliberately damaged/missing journal evidence.
        try (var reservation = LocalFileOwnership.control(directory.resolve("transactions.journal"))) { assertNotNull(reservation); }
    }

    private static Target alternateTarget(Target original, String dimension) {
        return new Target(dimension.equals("endpoint") ? URI.create(original.endpoint().toString() + "/") : original.endpoint(),
                dimension.equals("region") ? "us-west-2" : original.regionId(), dimension.equals("bucket") ? "other-bucket" : original.bucket());
    }

    private static S3CheckpointHeads.Binding alternateBinding(String dimension) {
        return new S3CheckpointHeads.Binding(dimension.equals("namespace") ? OTHER : NAMESPACE,
                dimension.equals("publisher") ? OTHER : PUBLISHER,
                dimension.equals("lineage") ? new ObjectId("f".repeat(64)) : BINDING.lineageId());
    }

    private static S3ObjectStore objects(Target target, AwsCredentialsProvider credentials) {
        return new S3ObjectStore(target.endpoint(), Region.of(target.regionId()), target.bucket(), credentials, TIMEOUTS);
    }

    private static S3CheckpointHeads heads(Target target, S3CheckpointHeads.Binding binding, AwsCredentialsProvider credentials) {
        return new S3CheckpointHeads(target.endpoint(), Region.of(target.regionId()), target.bucket(), credentials, binding, TIMEOUTS);
    }

    private static String headKey() { return S3CheckpointHeads.headKey(NAMESPACE).value(); }

    private static Path selectedFile(Path directory, S3PublicationState state) {
        return directory.resolve(String.format(Locale.ROOT, "publication-%019d-%s.state", state.generation(),
                S3PublicationStateCodec.reference(state).id().value()));
    }

    private static Map<String, byte[]> snapshot(Path directory) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var files = Files.list(directory)) {
            for (var file : files.sorted().toList()) { result.put(file.getFileName().toString(), Files.readAllBytes(file)); }
        }
        return result;
    }

    private static void assertImages(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, image) -> assertArrayEquals(image, actual.get(name), name));
    }

    private static byte[] journalImage(DatabaseId database, List<JournalRecord> records) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(JournalCodec.encodeHeader(database));
        records.forEach(record -> output.writeBytes(JournalCodec.encodeRecord(record)));
        return output.toByteArray();
    }

    private static String hash(byte[] image) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    private record Graph(CheckpointManifest manifest, byte[] manifestImage, Map<SegmentId, byte[]> segments,
                         ObjectCheckpointHead head, List<String> keys) {
        ObjectReference reference() { return ObjectReference.forBytes(manifestImage); }
        ObjectKey manifestKey() { return ObjectCheckpointGraphs.manifestKey(NAMESPACE, reference().id()); }
    }

    private static Graph graph(int cutoff, ObjectCheckpointHead previous) {
        var references = new ArrayList<CheckpointManifest.SegmentReference>();
        var segments = new LinkedHashMap<SegmentId, byte[]>();
        var keys = new ArrayList<String>();
        for (var record : HISTORY.subList(0, cutoff)) {
            byte[] image = SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(record)));
            var id = new SegmentId(hash(image));
            segments.put(id, image);
            references.add(new CheckpointManifest.SegmentReference(id, image.length, record.sequence(), record.sequence()));
            keys.add(ObjectCheckpointGraphs.segmentKey(NAMESPACE, id).value());
        }
        byte[] prefix = journalImage(DATABASE, HISTORY.subList(0, cutoff));
        var journalPrefix = new CheckpointManifest.JournalPrefix(cutoff, prefix.length, hash(prefix));
        var manifest = new CheckpointManifest(DATABASE, 17 + cutoff, journalPrefix, references);
        byte[] image = ManifestCodec.encode(manifest);
        var reference = ObjectReference.forBytes(image);
        keys.addFirst(ObjectCheckpointGraphs.manifestKey(NAMESPACE, reference.id()).value());
        var head = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, previous == null ? 10 : 11), BINDING.lineageId(),
                previous == null ? 1 : previous.revision() + 1, reference, journalPrefix,
                previous == null ? null : ObjectCheckpointHeadCodec.reference(previous));
        return new Graph(manifest, image, segments, head, List.copyOf(keys));
    }

    private static final class CallerCredentials implements AwsCredentialsProvider, AutoCloseable {
        int closes;
        @Override public AwsCredentials resolveCredentials() {
            if (closes != 0) { throw new IllegalStateException("Caller provider is closed"); }
            return AwsBasicCredentials.create("fixture-access", "fixture-secret");
        }
        @Override public void close() { closes++; }
    }

    private static final class Environment implements AutoCloseable {
        Path home;
        Path databaseDirectory;
        Path stateDirectory;
        S3PublicationProtocolFixture fixture;
        Target target;
        CallerCredentials credentials;
        S3ObjectStore objects;
        S3CheckpointHeads heads;
        Graph graph;
        S3HeadObservation expected;
        S3PublicationState savedState;

        static Environment create(Path home, int cutoff) throws IOException {
            var env = new Environment();
            env.home = home;
            try {
                env.databaseDirectory = Files.createDirectory(home.resolve("database"));
                try (var database = LocalDatabase.create(env.databaseDirectory, DATABASE)) {
                    for (var record : HISTORY) {
                        assertEquals(record.sequence(), database.transact(record.transaction()).position().sequence());
                    }
                }
                env.fixture = new S3PublicationProtocolFixture();
                env.target = new Target(env.fixture.endpoint(), "us-east-1", S3PublicationProtocolFixture.BUCKET);
                env.credentials = new CallerCredentials();
                env.objects = objects(env.target, env.credentials);
                env.heads = heads(env.target, BINDING, env.credentials);
                env.graph = graph(cutoff, null);
                env.install(env.graph);
                byte[] image = ObjectCheckpointHeadCodec.encode(env.graph.head());
                env.fixture.forceState(headKey(), image, TOKEN);
                env.expected = S3HeadObservation.restore(env.target.endpoint(), env.target.bucket(), image, new S3HeadToken(TOKEN));
                env.stateDirectory = Files.createDirectory(home.resolve("publication"));
                try (var local = LocalS3PublicationState.create(env.stateDirectory, env.target, BINDING)) {
                    local.prepare(image);
                    env.savedState = local.recordSelected(env.expected);
                }
                return env;
            } catch (IOException | RuntimeException | Error failure) {
                try { env.close(); }
                catch (IOException | RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
                throw failure;
            }
        }

        S3Database open(long minimum) throws IOException {
            return S3Database.open(databaseDirectory, stateDirectory, target, BINDING, objects, heads, LIMITS, minimum);
        }

        void install(Graph value) {
            fixture.put(value.manifestKey().value(), value.manifestImage());
            value.segments().forEach((id, image) -> fixture.put(ObjectCheckpointGraphs.segmentKey(NAMESPACE, id).value(), image));
        }

        void freshState() throws IOException {
            stateDirectory = Files.createDirectory(home.resolve("fresh-publication"));
            try (var local = LocalS3PublicationState.create(stateDirectory, target, BINDING)) { savedState = local.state(); }
        }

        void pending(boolean selected) throws IOException {
            var next = graph(4, expected.head());
            install(next);
            byte[] image = ObjectCheckpointHeadCodec.encode(next.head());
            try (var local = LocalS3PublicationState.open(stateDirectory, target, BINDING);
                 var checkpoints = LocalCheckpointStore.open(databaseDirectory);
                 var journal = LocalJournal.open(checkpoints.journalPath())) {
                savedState = new S3PublicationPreparer(objects, heads, local, checkpoints, journal).prepare(image, LIMITS).pending();
                if (selected) { heads.replace(expected, image); }
            }
            graph = next;
        }

        @Override public void close() throws IOException {
            Throwable failed = null;
            for (AutoCloseable resource : new AutoCloseable[] {heads, objects, credentials, fixture}) {
                if (resource == null) { continue; }
                try { resource.close(); }
                catch (Exception | Error failure) {
                    if (failed == null) { failed = failure; }
                    else if (failed != failure) { failed.addSuppressed(failure); }
                }
            }
            if (failed instanceof IOException io) { throw io; }
            if (failed instanceof RuntimeException runtime) { throw runtime; }
            if (failed instanceof Error error) { throw error; }
            if (failed != null) { throw new IOException("S3 recovery fixture cleanup failed", failed); }
            if (fixture != null) { assertTrue(fixture.violations().isEmpty(), () -> fixture.violations().toString()); }
        }
    }
}
