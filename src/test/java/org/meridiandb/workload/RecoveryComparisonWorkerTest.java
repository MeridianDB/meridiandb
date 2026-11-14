// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.manifest.LocalCheckpointHistory;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.workload.LocalRecoveryComparison.Access;
import org.meridiandb.workload.LocalRecoveryComparison.MeasurementMode;
import org.meridiandb.workload.LocalRecoveryComparison.Operation;
import org.meridiandb.workload.LocalRecoveryComparison.OperationSample;
import org.meridiandb.workload.LocalRecoveryComparison.Shape;
import org.meridiandb.workload.LocalRecoveryComparison.Strategy;
import tools.jackson.databind.json.JsonMapper;

/** Producer contract and persisted bytes, without claiming source supervision or timing gains. */
@Timeout(90)
class RecoveryComparisonWorkerTest {
    private static final String RUN = "0123456789abcdef0123456789abcdef";
    private static final String SOURCE = "0000000000000000000000000000000000000000";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> ENVELOPE_KEYS = Set.of("schemaVersion", "protocol", "mode", "request", "workerPid",
            "status", "error", "failedRunRoot", "sourceHistory", "continuationFrameHex", "fixtureImages", "result");
    private static final Set<String> REQUEST_KEYS = Set.of("schemaVersion", "runId", "source", "mode", "profile", "seed",
            "spec", "config", "limits", "scratch", "output");
    private static final Set<String> RESULT_KEYS = Set.of("schemaVersion", "mode", "corpusVersion", "spec", "config", "limits",
            "fingerprint", "historyFingerprint", "encodedHistoryBytes", "workload", "runtime", "runRoot", "observations", "gaps");
    private static final Set<String> OBSERVATION_KEYS = Set.of("iteration", "warmup", "strategyOrdinal", "shape", "access",
            "strategy", "directory", "fixture", "operations", "validationNanos", "teardownNanos", "closedJournalBytes", "closedJournalSha256");

    @TempDir Path directory;

    @Test
    void exactlySixteenBoundedNonNullControlFreeArgumentsAreRequiredBeforeExecution() throws IOException {
        var request = request("lexical");
        assertInvalid(null);
        assertInvalid(new String[0]);
        assertInvalid(Arrays.copyOf(request.args(), 15));
        assertInvalid(Arrays.copyOf(request.args(), 17));
        for (int index = 0; index < 16; index++) {
            var missing = request.args().clone(); missing[index] = null;
            assertInvalid(missing);
            for (String malformed : List.of("x\n", "x\u007f", "x".repeat(4097))) {
                var invalid = request.args().clone(); invalid[index] = malformed;
                assertInvalid(invalid);
            }
        }
        assertNoExecutionFiles(request);
    }

    @Test
    void exactLowercaseIdentitiesAndModeIdentifiersRejectEnumNamesAndUnrelatedWorkloadModes() throws IOException {
        var request = request("identities");
        for (String invalid : List.of("a".repeat(31), "A".repeat(32), "g".repeat(32))) {
            assertInvalid(changed(request.args(), 0, invalid));
        }
        for (String invalid : List.of("a".repeat(39), "A".repeat(40), "g".repeat(40))) {
            assertInvalid(changed(request.args(), 1, invalid));
        }
        for (String mode : List.of("INSTRUMENTED", "UNINSTRUMENTED", "RECOVERY_UNINSTRUMENTED_UNKNOWN",
                "recovery_uninstrumented", "CALLER_THREAD_LOCAL_IO_INSTRUMENTED", "")) {
            assertInvalid(changed(request.args(), 2, mode));
        }
        assertInvalid(changed(request.args(), 3, "append"));
        assertNoExecutionFiles(request);
    }

    @Test
    void numericParsingSpecConfigurationAndActualLimitBoundsRejectWithoutFiles() throws IOException {
        var request = request("numbers");
        for (int index : List.of(4, 5, 6, 9, 10, 11, 12, 13, 14, 15)) {
            for (String invalid : List.of("invalid", "1.5", "9223372036854775808")) {
                assertInvalid(changed(request.args(), index, invalid));
            }
        }
        for (String[] invalid : List.of(
                changed(request.args(), 5, "-1"), changed(request.args(), 5, "3"),
                changed(request.args(), 6, "0"), changed(request.args(), 6, "6"),
                changed(request.args(), 9, "0"), changed(request.args(), 9, "1025"),
                changed(request.args(), 10, "1"), changed(request.args(), 10, "4097"),
                changed(request.args(), 11, "-1"), changed(request.args(), 11, "1025"),
                changed(request.args(), 12, "0"), changed(request.args(), 12, "17"),
                changed(request.args(), 13, "-1"), changed(request.args(), 13, "67108865"),
                changed(request.args(), 14, "-1"), changed(request.args(), 14, "67108865"),
                changed(request.args(), 15, "-1"), changed(request.args(), 15, "262145"))) {
            assertInvalid(invalid);
        }
        assertNoExecutionFiles(request);
    }

    @Test
    void conservativeWholeMatrixBudgetAcceptsExactlyFortyThousandUnitsAndRejectsOneQueryMoreBeforePaths()
            throws IOException {
        var admitted = request("admitted");
        var boundary = admitted.args().clone(); boundary[10] = "100"; boundary[11] = "992";
        var calls = new AtomicInteger();
        assertEquals(1, RecoveryComparisonWorker.execute(boundary, (scratch, corpus, config, mode, limits) -> {
            calls.incrementAndGet();
            assertEquals(100, corpus.spec().commits());
            assertEquals(992, corpus.spec().queries());
            assertEquals(40_000, 16L * (2L * (2L * 100 + 992) + 100 + 16));
            throw new IOException("Admitted execution sentinel");
        }));
        assertEquals(1, calls.get());
        assertEquals("failed", read(admitted.output()).get("status"));

        var rejected = request("rejected");
        var excessive = rejected.args().clone(); excessive[10] = "100"; excessive[11] = "993";
        excessive[7] = rejected.scratch().resolve("missing-must-not-be-inspected").toString();
        String diagnostic = assertInvalid(excessive);
        assertTrue(diagnostic.toLowerCase(java.util.Locale.ROOT).contains("admission"));
        assertNoExecutionFiles(rejected);
        var excessiveReference = rejected.args().clone(); excessiveReference[10] = "4001"; excessiveReference[11] = "0";
        assertTrue(assertInvalid(excessiveReference).toLowerCase(java.util.Locale.ROOT).contains("admission"));
        var excessiveRecovery = rejected.args().clone(); excessiveRecovery[10] = "1500"; excessiveRecovery[11] = "0";
        assertTrue(assertInvalid(excessiveRecovery).toLowerCase(java.util.Locale.ROOT).contains("admission"));
    }

    @Test
    void scratchAndOutputMustBeCanonicalNormalizedSafeFreshSiblings() throws IOException {
        var request = request("paths");
        var marker = Files.writeString(request.output().getParent().resolve("marker"), "retained");
        for (Path invalidScratch : List.of(Path.of("relative-scratch"), request.scratch().resolve("missing"), marker,
                request.scratch().resolve("..").resolve("scratch"))) {
            assertInvalid(changed(request.args(), 7, invalidScratch.toString()));
        }
        for (Path invalidOutput : List.of(Path.of("relative.json"), request.scratch().resolve("nested.json"),
                request.output().getParent().getParent().resolve("outside.json"),
                request.output().resolveSibling("worker.partial.json"), request.output().resolveSibling(".hidden"),
                request.output().resolveSibling("bad name.json"), request.output().resolveSibling("bad:name.json"),
                request.output().resolveSibling("x".repeat(129)),
                request.scratch().resolve("..").resolve("unnormalized.json"))) {
            assertInvalid(changed(request.args(), 8, invalidOutput.toString()));
        }
        assertEquals("retained", Files.readString(marker));
        assertNoExecutionFiles(request);
    }

    @Test
    void existingFinalOrPartialEvidenceRejectsWithoutStartingExecutionOrReplacingBytes() throws IOException {
        for (boolean partial : List.of(false, true)) {
            var request = request("existing-" + partial);
            Path occupied = partial ? request.output().resolveSibling("worker.partial.json") : request.output();
            byte[] original = "retained evidence\n".getBytes(StandardCharsets.UTF_8);
            Files.write(occupied, original, StandardOpenOption.CREATE_NEW);
            assertInvalid(request.args());
            assertArrayEquals(original, Files.readAllBytes(occupied));
            if (partial) { assertFalse(Files.exists(request.output())); }
            try (var files = Files.list(request.scratch())) { assertEquals(0, files.count()); }
        }
    }

    @Test
    void executionReceivesExactAdmittedBindingsAndDeclaredSourceIsPreservedWithoutAFalseTrustClaim() throws IOException {
        var request = request("bindings");
        var args = request.args().clone();
        args[3] = "HOT_ENTITY"; args[4] = Long.toString(Long.MIN_VALUE);
        args[13] = "0"; args[14] = "0"; args[15] = "0";
        var calls = new AtomicInteger();
        assertEquals(1, RecoveryComparisonWorker.execute(args, (scratch, corpus, config, mode, limits) -> {
            calls.incrementAndGet();
            assertEquals(request.scratch(), scratch);
            assertEquals(WorkloadCorpus.Profile.HOT_ENTITY.spec(Long.MIN_VALUE, 1, 4, 5, 1), corpus.spec());
            assertEquals(new LocalRecoveryComparison.Config(0, 1), config);
            assertEquals(MeasurementMode.UNINSTRUMENTED, mode);
            assertEquals(new LocalCheckpointHistory.Limits(0, 0, 0), limits);
            throw new IOException("No native execution needed");
        }));
        assertEquals(1, calls.get());
        var envelope = read(request.output());
        assertEnvelope(envelope, request, MeasurementMode.UNINSTRUMENTED);
        var admitted = map(envelope.get("request"));
        assertEquals(SOURCE, admitted.get("source"));
        assertEquals(Long.MIN_VALUE, number(admitted.get("seed")));
        assertEquals("HOT_ENTITY", admitted.get("profile"));
        assertEquals(0, number(map(admitted.get("limits")).get("maxObjectBytes")));
        assertFailure(envelope, "failed");
        assertNull(envelope.get("failedRunRoot"));
        try (var files = Files.list(request.scratch())) { assertEquals(0, files.count()); }
    }

    @Test
    void ordinaryUnsupportedLookingAndCyclicFailuresRemainFailedWithBoundedMessages() throws IOException {
        var cyclic = new IOException("cycle one");
        var second = new IOException("cycle two", cyclic); cyclic.initCause(second);
        List<Exception> failures = List.of(new IOException("directory sync unsupported"),
                new UnsupportedOperationException("native unavailable"), new IOException("x".repeat(4096)), cyclic);
        for (int index = 0; index < failures.size(); index++) {
            var request = request("ordinary-failure-" + index);
            var failure = failures.get(index);
            assertEquals(1, RecoveryComparisonWorker.execute(request.args(), (s, c, cfg, m, l) -> {
                if (failure instanceof IOException io) { throw io; }
                throw (RuntimeException) failure;
            }));
            var envelope = read(request.output());
            assertEnvelope(envelope, request, MeasurementMode.UNINSTRUMENTED);
            assertFailure(envelope, "failed");
            var error = map(envelope.get("error"));
            assertEquals(failure.getClass().getName(), error.get("type"));
            assertTrue(((String) error.get("message")).length() <= 1024);
            assertTrue(((String) error.get("type")).length() <= 256);
        }
    }

    @Test
    void onlyTypedDirectorySyncUnavailabilityReturnsThreeAndNestedFailuresRetainTheirExactOwnedRoot() throws Exception {
        var constructor = LocalCheckpointStore.DirectorySyncUnsupportedException.class.getDeclaredConstructor(Throwable.class);
        constructor.setAccessible(true);
        for (boolean unsupported : List.of(false, true)) {
            var request = request("nested-" + unsupported);
            var retained = Files.createDirectory(request.scratch().resolve("mdb-recovery-retained"));
            var marker = Files.writeString(retained.resolve("failure-evidence"), "retain this");
            IOException cause = unsupported ? constructor.newInstance(new UnsupportedOperationException("capability"))
                    : new IOException("ordinary native failure");
            var wrapped = new IOException("outer", new LocalRecoveryComparison.RunFailedException(retained, cause));
            assertEquals(unsupported ? 3 : 1,
                    RecoveryComparisonWorker.execute(request.args(), (s, c, cfg, m, l) -> { throw wrapped; }));
            var envelope = read(request.output());
            assertEnvelope(envelope, request, MeasurementMode.UNINSTRUMENTED);
            assertFailure(envelope, unsupported ? "unsupported" : "failed");
            assertEquals(retained.toString(), envelope.get("failedRunRoot"));
            assertEquals("retain this", Files.readString(marker));
        }
    }

    @Test
    void errorsPropagateAndRetainFilesWithoutPublishingAPartialSuccessfulEnvelope() throws IOException {
        var request = request("error");
        var failure = new AssertionError("intentional worker Error");
        var marker = request.scratch().resolve("retained-error");
        assertSame(failure, assertThrows(AssertionError.class,
                () -> RecoveryComparisonWorker.execute(request.args(), (s, c, cfg, m, l) -> {
                    Files.writeString(marker, "preserved", StandardOpenOption.CREATE_NEW);
                    throw failure;
                })));
        assertEquals("preserved", Files.readString(marker));
        assertFalse(Files.exists(request.output()));
        assertFalse(Files.exists(request.output().resolveSibling("worker.partial.json")));
    }

    @Test
    void evidenceIntroducedAfterPreflightIsNeverReplacedAndPartialCandidatesRemainAvailable() throws IOException {
        for (boolean partial : List.of(false, true)) {
            var request = request("publication-race-" + partial);
            Path introduced = partial ? request.output().resolveSibling("worker.partial.json") : request.output();
            assertEquals(1, RecoveryComparisonWorker.execute(request.args(), (s, c, cfg, m, l) -> {
                Files.writeString(introduced, "concurrent evidence", StandardOpenOption.CREATE_NEW);
                throw new IOException("After request preflight");
            }));
            assertEquals("concurrent evidence", Files.readString(introduced));
            if (partial) { assertFalse(Files.exists(request.output())); }
            else { assertFalse(Files.exists(request.output().resolveSibling("worker.partial.json"))); }
        }
    }

    @Test
    void producerRejectsCompleteLookingResultsWithWrongInputRuntimeMatrixOrOperationBindings() throws IOException {
        var request = request("returned-bindings");
        var baseline = nativeResult(request);
        assertEquals(0, RecoveryComparisonWorker.execute(request.args(), (s, c, cfg, m, l) -> baseline));
        var retainedOutput = Files.readAllBytes(request.output());
        int ordinal = 0;
        for (String fault : List.of("schema", "corpusVersion", "spec", "config", "limits", "fingerprint",
                "historyFingerprint", "encodedBytes", "runtime", "oracle", "missing", "duplicate", "operation", "root")) {
            var args = changed(request.args(), 8, request.output().resolveSibling("fault-" + ordinal++ + ".json").toString());
            assertEquals(1, RecoveryComparisonWorker.execute(args, (s, c, cfg, m, l) -> broken(baseline, fault)), fault);
            assertFailure(read(Path.of(args[8])), "failed");
        }
        var wrongMode = changed(request.args(), 8, request.output().resolveSibling("wrong-mode.json").toString());
        wrongMode[2] = LocalRecoveryComparison.MODE;
        assertEquals(1, RecoveryComparisonWorker.execute(wrongMode, (s, c, cfg, m, l) -> baseline));
        assertFailure(read(Path.of(wrongMode[8])), "failed");
        var noResult = changed(request.args(), 8, request.output().resolveSibling("no-result.json").toString());
        assertEquals(1, RecoveryComparisonWorker.execute(noResult, (s, c, cfg, m, l) -> null));
        assertFailure(read(Path.of(noResult[8])), "failed");
        assertArrayEquals(retainedOutput, Files.readAllBytes(request.output()));
    }

    @Test
    void producerReobservesClosedNativeBytesAndRejectsAChangedJournalDespiteAnUnchangedResult() throws IOException {
        var request = request("changed-journal");
        var baseline = nativeResult(request);
        var journal = baseline.observations().getFirst().directory().resolve("transactions.journal");
        byte[] before = Files.readAllBytes(journal);
        byte[] changed = before.clone(); changed[changed.length - 1] ^= 1;
        Files.write(journal, changed, StandardOpenOption.TRUNCATE_EXISTING);
        assertEquals(1, RecoveryComparisonWorker.execute(request.args(), (s, c, cfg, m, l) -> baseline));
        var envelope = read(request.output());
        assertFailure(envelope, "failed");
        assertEquals(baseline.runRoot().toString(), envelope.get("failedRunRoot"));
        assertArrayEquals(changed, Files.readAllBytes(journal));
    }

    @ParameterizedTest
    @EnumSource(MeasurementMode.class)
    void nativeProducerExportsEveryRawScopeAndIndependentlyDecodableOriginalAndContinuationImages(MeasurementMode mode)
            throws IOException {
        var request = request("native-" + mode);
        request.args()[2] = mode.identifier();
        int code = RecoveryComparisonWorker.execute(request.args());
        var envelope = read(request.output());
        assertEnvelope(envelope, request, mode);
        if (code == 3) {
            assertEquals("unsupported", envelope.get("status"));
            assertTrue(System.getProperty("os.name").startsWith("Windows"), "Only an explicit Windows capability gap may skip native coverage");
            assertTrue(((String) map(envelope.get("error")).get("type")).endsWith("RunFailedException"));
            assertFailure(envelope, "unsupported");
            assumeTrue(false, "Explicit Windows directory-sync unavailability");
        }
        assertEquals(0, code, () -> "Native producer failed: " + envelope.get("error"));
        assertEquals("success", envelope.get("status"));
        assertNull(envelope.get("error"));
        assertNull(envelope.get("failedRunRoot"));
        var result = map(envelope.get("result"));
        assertEquals(RESULT_KEYS, result.keySet());
        assertEquals(mode.identifier(), result.get("mode"));
        assertEquals(1, number(result.get("schemaVersion")));
        assertEquals(1, number(result.get("corpusVersion")));
        assertEquals(map(envelope.get("request")).get("spec"), result.get("spec"));
        assertEquals(map(envelope.get("request")).get("config"), result.get("config"));
        assertEquals(map(envelope.get("request")).get("limits"), result.get("limits"));
        var corpus = corpus();
        assertEquals(corpus.fingerprint(), result.get("fingerprint"));
        byte[] original = originalImage(envelope, corpus);
        assertEquals(original.length, number(result.get("encodedHistoryBytes")));
        assertEquals(hash(original), result.get("historyFingerprint"));
        byte[] continuation = hex(envelope.get("continuationFrameHex"));
        var continued = decodeFrame(continuation);
        assertEquals(5, continued.sequence());
        assertEquals("recovery-continuation-0", continued.requestId());
        assertEquals(List.of(new RawOperation(1, "recovery-continuation-entity-0", null, null, 17L)), continued.operations());
        assertEquals(156, continuation.length, "The named unbounded continuation has a literal 136-byte payload plus framing");
        var all = new ByteArrayOutputStream(); all.writeBytes(original); all.writeBytes(continuation);
        byte[] writableImage = all.toByteArray();
        var decoded = JournalCodec.scan(writableImage);
        assertFalse(decoded.incompleteTail());
        assertEquals(5, decoded.records().size());
        var fixtureImages = fixtureImages(envelope);
        assertEquals(Set.of(Shape.values()), fixtureImages.keySet());
        var observations = list(result.get("observations"));
        assertEquals(16, observations.size());
        var homes = new HashSet<Path>();
        int row = 0;
        for (var shape : Shape.values()) {
            for (var access : Access.values()) {
                for (int ordinal = 0; ordinal < 2; ordinal++) {
                    var observation = map(observations.get(row++));
                    assertEquals(OBSERVATION_KEYS, observation.keySet());
                    assertEquals(0, number(observation.get("iteration")));
                    assertEquals(false, observation.get("warmup"));
                    assertEquals(ordinal, number(observation.get("strategyOrdinal")));
                    assertEquals(shape.name(), observation.get("shape"));
                    assertEquals(access.name(), observation.get("access"));
                    assertEquals((ordinal == 0 ? Strategy.ORDINARY : Strategy.HISTORY_REUSE).name(), observation.get("strategy"));
                    Path home = Path.of((String) observation.get("directory"));
                    assertTrue(homes.add(home));
                    assertEquals(Path.of((String) result.get("runRoot")), home.getParent());
                    byte[] expected = access == Access.WRITABLE ? writableImage : original;
                    assertEquals(expected.length, number(observation.get("closedJournalBytes")));
                    assertEquals(hash(expected), observation.get("closedJournalSha256"));
                    assertArrayEquals(expected, Files.readAllBytes(home.resolve("transactions.journal")));
                    assertFixtureImages(map(observation.get("fixture")), fixtureImages.get(shape), home, original, shape, envelope);
                    assertOperations(list(observation.get("operations")), shape, access, mode);
                    assertTrue(number(observation.get("validationNanos")) >= 0);
                    assertTrue(number(observation.get("teardownNanos")) >= 0);
                    // Successful export must leave every native owner available for a separate ordinary reopen.
                    try (var database = LocalDatabase.open(home); var head = database.openView(ReadCutoff.Head.INSTANCE)) {
                        assertEquals(access == Access.WRITABLE ? 5 : 4, database.head().sequence());
                        var point = (TemporalResult.Point) head.query(new TemporalQuery.Get(new EntityId("recovery-continuation-entity-0"), 0));
                        assertEquals(access == Access.WRITABLE, point.value().isPresent());
                        if (access == Access.WRITABLE) { assertEquals(17, point.value().orElseThrow()); }
                    }
                }
            }
        }
        assertTrue(Files.size(request.output()) < 16 * 1024 * 1024);
        assertFalse(Files.exists(request.output().resolveSibling("worker.partial.json")));
        byte[] retained = Files.readAllBytes(request.output());
        assertEquals(2, RecoveryComparisonWorker.execute(request.args()));
        assertArrayEquals(retained, Files.readAllBytes(request.output()));
    }

    @ParameterizedTest
    @MethodSource("maximumRequests")
    void admittedMatrixAndHistoryExtremesExportCompleteBoundedRawEnvelopes(int warmups, int repetitions, int commits, int queries)
            throws IOException {
        var request = request("maximum-" + commits);
        var args = request.args();
        args[2] = LocalRecoveryComparison.MODE;
        args[5] = Integer.toString(warmups);
        args[6] = Integer.toString(repetitions);
        args[10] = Integer.toString(commits);
        args[11] = Integer.toString(queries);
        int code = RecoveryComparisonWorker.execute(args);
        var envelope = read(request.output());
        if (code == 3 && System.getProperty("os.name").startsWith("Windows")) {
            assertEquals("unsupported", envelope.get("status"));
            assumeTrue(false, "Explicit Windows directory-sync unavailability");
        }
        assertEquals(0, code, () -> "Maximum producer request failed: " + envelope.get("error"));
        assertEquals("success", envelope.get("status"));
        assertEquals(commits, list(map(envelope.get("sourceHistory")).get("framesHex")).size());
        var observations = list(map(envelope.get("result")).get("observations"));
        assertEquals(16 * (warmups + repetitions), observations.size());
        assertEquals(40L * (warmups + repetitions), observations.stream()
                .mapToLong(o -> list(map(o).get("operations")).size()).sum());
        int maximumInventory = 0;
        for (int iteration = 0; iteration < warmups + repetitions; iteration++) {
            boolean warmup = iteration < warmups;
            int order = warmup ? iteration : iteration - warmups;
            for (int index = 0; index < 16; index++) {
                var observation = map(observations.get(16 * iteration + index));
                assertEquals(iteration, number(observation.get("iteration")));
                assertEquals(warmup, observation.get("warmup"));
                assertEquals(index % 2, number(observation.get("strategyOrdinal")));
                assertEquals((order % 2 == index % 2 ? Strategy.ORDINARY : Strategy.HISTORY_REUSE).name(),
                        observation.get("strategy"));
                assertOperations(list(observation.get("operations")), Shape.valueOf((String) observation.get("shape")),
                        Access.valueOf((String) observation.get("access")), MeasurementMode.INSTRUMENTED);
                maximumInventory = Math.max(maximumInventory, map(map(observation.get("fixture")).get("initialFileSha256")).size());
            }
        }
        assertEquals(commits == 496 ? 21 : 5, maximumInventory);
        assertTrue(Files.size(request.output()) < WorkloadWorker.MAX_JSON_BYTES);
        assertTrue(jsonNodes(envelope) < WorkloadWorker.MAX_JSON_NODES);
        assertFalse(Files.exists(request.output().resolveSibling("worker.partial.json")));
    }

    private static Stream<Arguments> maximumRequests() {
        // Maximum observation/operation count, and maximum commit count at the exact 40,000-unit gate.
        return Stream.of(Arguments.of(2, 5, 2, 165), Arguments.of(0, 1, 496, 2));
    }

    private static long jsonNodes(Object value) {
        if (value instanceof Map<?, ?> map) {
            return 1 + map.entrySet().stream().mapToLong(e -> 1 + jsonNodes(e.getValue())).sum();
        }
        if (value instanceof List<?> list) { return 1 + list.stream().mapToLong(RecoveryComparisonWorkerTest::jsonNodes).sum(); }
        return 1;
    }

    @Test
    void syntacticallyValidButInsufficientCheckpointLimitsRetainAFailedEnvelopeWithoutDatabaseFiles() throws IOException {
        var request = request("insufficient-checkpoint");
        var args = request.args(); args[13] = "0"; args[14] = "0"; args[15] = "0";
        assertEquals(1, RecoveryComparisonWorker.execute(args));
        var envelope = read(request.output());
        assertFailure(envelope, "failed");
        assertNull(envelope.get("failedRunRoot"));
        assertTrue(((String) map(envelope.get("error")).get("message")).contains("Checkpoint limits"));
        try (var paths = Files.list(request.scratch())) { assertEquals(0, paths.count()); }
    }

    @Test
    void danglingEvidenceLinksAndAliasedScratchAreRejectedWithoutFollowingOrReplacingThem() throws IOException {
        var request = request("links");
        Path alias = request.scratch().resolveSibling("scratch-alias");
        try { Files.createSymbolicLink(alias, request.scratch()); }
        catch (UnsupportedOperationException unsupported) { assumeTrue(false, "Symbolic links explicitly unsupported"); }
        assertInvalid(changed(request.args(), 7, alias.toString()));
        assertTrue(Files.isSymbolicLink(alias));
        for (boolean partial : List.of(false, true)) {
            var test = request("dangling-" + partial);
            Path link = partial ? test.output().resolveSibling("worker.partial.json") : test.output();
            Path target = link.resolveSibling("absent-target");
            Files.createSymbolicLink(link, target);
            assertFalse(Files.exists(link));
            assertTrue(Files.isSymbolicLink(link));
            assertInvalid(test.args());
            assertEquals(target, Files.readSymbolicLink(link));
            assertFalse(Files.exists(target));
            try (var paths = Files.list(test.scratch())) { assertEquals(0, paths.count()); }
        }
    }

    private static void assertOperations(List<?> operations, Shape shape, Access access, MeasurementMode mode) {
        var expected = access == Access.READ_ONLY ? List.of("OPEN")
                : List.of("OPEN", "ORIGINAL_RETRY", "CONTINUATION", "CONTINUATION_RETRY");
        assertEquals(expected.size(), operations.size());
        for (int index = 0; index < operations.size(); index++) {
            var operation = map(operations.get(index));
            assertEquals(Set.of("operation", "measurement", "coverage", "counters"), operation.keySet());
            assertEquals(expected.get(index), operation.get("operation"));
            var measurement = map(operation.get("measurement"));
            assertEquals(Set.of("phase", "kind", "elapsedNanos", "threadCpuNanos", "threadAllocatedBytes",
                    "heapUsedBeforeBytes", "heapUsedAfterBytes"), measurement.keySet());
            assertEquals("MAINTENANCE", measurement.get("phase"));
            String kind = index == 0 ? shape == Shape.JOURNAL_ONLY ? "JOURNAL_REOPEN" : "CHECKPOINT_REOPEN"
                    : index == 2 ? "COMMIT" : "RETRY";
            assertEquals(kind, measurement.get("kind"));
            assertTrue(number(measurement.get("elapsedNanos")) >= 0);
            for (String optional : List.of("threadCpuNanos", "threadAllocatedBytes")) {
                if (measurement.get(optional) != null) { assertTrue(number(measurement.get(optional)) >= 0); }
            }
            assertTrue(number(measurement.get("heapUsedBeforeBytes")) >= 0);
            assertTrue(number(measurement.get("heapUsedAfterBytes")) >= 0);
            if (mode == MeasurementMode.UNINSTRUMENTED) {
                assertEquals("NOT_CAPTURED", operation.get("coverage"));
                assertNull(operation.get("counters"));
            } else {
                assertEquals("ADAPTERS_ONLY", operation.get("coverage"));
                var counters = map(operation.get("counters"));
                assertEquals(Set.of("schemaVersion", "overflow", "invalidResult", "counts"), counters.keySet());
                assertEquals(1, number(counters.get("schemaVersion")));
                assertEquals(false, counters.get("overflow"));
                assertEquals(false, counters.get("invalidResult"));
                var rows = list(counters.get("counts"));
                assertEquals(8, rows.size());
                for (var raw : rows) {
                    var counts = list(raw); assertEquals(14, counts.size());
                    for (var count : counts) { assertTrue(number(count) >= 0); }
                    assertEquals(0, number(counts.get(5)));
                    assertEquals(0, number(counts.get(10)));
                    assertEquals(0, number(counts.get(13)));
                }
                for (var count : list(rows.get(7))) { assertEquals(0, number(count)); }
            }
        }
    }

    private static void assertFixtureImages(Map<?, ?> fixture, Map<?, ?> images, Path home, byte[] original,
                                            Shape shape, Map<?, ?> envelope) throws IOException {
        assertEquals(Set.of("checkpointSequence", "prefixBytes", "prefixSha256", "selectedManifestBytes",
                "selectedSegmentBytes", "selectedSegments", "initialFileSha256"), fixture.keySet());
        var initial = map(fixture.get("initialFileSha256"));
        var names = new HashSet<>(initial.keySet()); names.remove("transactions.journal");
        assertEquals(names, images.keySet());
        assertFalse(images.containsKey("transactions.journal"));
        assertFalse(images.containsKey(".meridiandb-readers.lock"));
        assertEquals(hash(original), initial.get("transactions.journal"));
        for (var entry : images.entrySet()) {
            byte[] bytes = hex(entry.getValue());
            assertEquals(hash(bytes), initial.get(entry.getKey()));
            assertArrayEquals(bytes, Files.readAllBytes(home.resolve((String) entry.getKey())));
        }
        if (shape == Shape.JOURNAL_ONLY) {
            assertNull(fixture.get("checkpointSequence"));
            assertEquals(0, number(fixture.get("prefixBytes")));
            assertNull(fixture.get("prefixSha256"));
        } else {
            assertEquals(shape == Shape.GENESIS ? 0 : 2, number(fixture.get("checkpointSequence")));
            var source = map(envelope.get("sourceHistory"));
            int prefix = hex(source.get("headerHex")).length;
            if (shape != Shape.GENESIS) {
                var frames = list(source.get("framesHex")); prefix += hex(frames.get(0)).length + hex(frames.get(1)).length;
            }
            assertEquals(prefix, number(fixture.get("prefixBytes")));
            assertEquals(hash(Arrays.copyOf(original, prefix)), fixture.get("prefixSha256"));
        }
    }

    private static Map<Shape, Map<?, ?>> fixtureImages(Map<?, ?> envelope) {
        var images = new HashMap<Shape, Map<?, ?>>();
        for (var raw : list(envelope.get("fixtureImages"))) {
            var value = map(raw);
            assertEquals(Set.of("shape", "files"), value.keySet());
            assertNull(images.put(Shape.valueOf((String) value.get("shape")), map(value.get("files"))));
        }
        assertEquals(4, images.size());
        return images;
    }

    private static byte[] originalImage(Map<?, ?> envelope, WorkloadCorpus corpus) {
        var source = map(envelope.get("sourceHistory"));
        assertEquals(Set.of("headerHex", "framesHex"), source.keySet());
        byte[] header = hex(source.get("headerHex"));
        var input = ByteBuffer.wrap(header);
        assertEquals(0x4d52444e4a4e4c00L, input.getLong());
        assertEquals(1, input.getInt());
        assertEquals("workload-v1", readString(input));
        assertEquals(4, input.remaining());
        assertCrc(header, 0, header.length - 4, header.length - 4);
        var frames = list(source.get("framesHex"));
        assertEquals(4, frames.size());
        var original = new ByteArrayOutputStream(); original.writeBytes(header);
        var submits = corpus.actions().stream().filter(WorkloadCorpus.Submit.class::isInstance)
                .map(WorkloadCorpus.Submit.class::cast).filter(submit -> !submit.retry()).toList();
        for (int index = 0; index < frames.size(); index++) {
            byte[] bytes = hex(frames.get(index));
            var frame = decodeFrame(bytes);
            assertEquals(index + 1L, frame.sequence());
            assertEquals(submits.get(index).request().requestId().value(), frame.requestId());
            assertEquals(1, frame.operations().size());
            original.writeBytes(bytes);
        }
        assertEquals(corpus.encodedHistoryBytes(), original.size());
        assertEquals(corpus.historyFingerprint(), hash(original.toByteArray()));
        return original.toByteArray();
    }

    private record RawFrame(long sequence, String requestId, List<RawOperation> operations) { }
    private record RawOperation(int kind, String entity, Long from, Long to, Long value) { }

    /** Separate literal framing/CRC/UTF-16/payload decoding, without calling the production decoder. */
    private static RawFrame decodeFrame(byte[] bytes) {
        var input = ByteBuffer.wrap(bytes);
        assertEquals(0x4d54584e, input.getInt());
        assertEquals(1, input.getInt());
        int payload = input.getInt();
        assertEquals(payload + 20, bytes.length);
        assertCrc(bytes, 0, 12, 12);
        assertCrc(bytes, 16, payload, bytes.length - 4);
        input.position(16);
        long sequence = input.getLong();
        String request = readString(input);
        int count = input.getInt();
        assertTrue(count > 0 && count <= 16);
        var operations = new ArrayList<RawOperation>();
        for (int index = 0; index < count; index++) {
            int kind = Byte.toUnsignedInt(input.get());
            assertTrue(kind == 1 || kind == 2);
            String entity = readString(input);
            int flags = Byte.toUnsignedInt(input.get());
            assertEquals(0, flags & ~3);
            Long from = (flags & 1) == 0 ? null : input.getLong();
            Long to = (flags & 2) == 0 ? null : input.getLong();
            Long value = kind == 1 ? input.getLong() : null;
            operations.add(new RawOperation(kind, entity, from, to, value));
        }
        assertEquals(bytes.length - 4, input.position());
        return new RawFrame(sequence, request, List.copyOf(operations));
    }

    private static String readString(ByteBuffer input) {
        int units = input.getInt();
        assertTrue(units > 0 && units <= 128);
        var text = new StringBuilder(units);
        for (int index = 0; index < units; index++) { text.append(input.getChar()); }
        return text.toString();
    }

    private static void assertCrc(byte[] bytes, int offset, int length, int checksumOffset) {
        var crc = new CRC32C(); crc.update(bytes, offset, length);
        assertEquals((int) crc.getValue(), ByteBuffer.wrap(bytes).getInt(checksumOffset));
    }

    private static LocalRecoveryComparison.Result broken(LocalRecoveryComparison.Result base, String fault) {
        var observations = new ArrayList<>(base.observations());
        if (fault.equals("missing")) { observations.removeLast(); }
        if (fault.equals("duplicate")) { observations.set(1, observations.getFirst()); }
        if (fault.equals("operation")) {
            var old = observations.getFirst();
            var open = old.operations().getFirst();
            observations.set(0, new LocalRecoveryComparison.Observation(old.iteration(), old.warmup(), old.strategyOrdinal(), old.shape(),
                    old.access(), old.strategy(), old.directory(), old.fixture(),
                    List.of(new OperationSample(Operation.CONTINUATION, open.measurement(), open.counters())), old.validationNanos(),
                    old.teardownNanos(), old.closedJournalBytes(), old.closedJournalSha256()));
        }
        var runtime = base.runtime();
        if (fault.equals("runtime")) {
            runtime = new LocalWorkloadRunner.RuntimeInfo("unrelated-java", runtime.javaRuntimeVersion(), runtime.javaVendor(),
                    runtime.osName(), runtime.osVersion(), runtime.architecture(), runtime.availableProcessors(), runtime.maxHeapBytes(), runtime.fileStoreType());
        }
        var different = WorkloadCorpus.Profile.APPEND.spec(44, 1, 4, 5, 1);
        return new LocalRecoveryComparison.Result(fault.equals("schema") ? 2 : base.schemaVersion(), base.mode(),
                fault.equals("corpusVersion") ? 2 : base.corpusVersion(), fault.equals("spec") ? different : base.spec(),
                fault.equals("config") ? new LocalRecoveryComparison.Config(0, 2) : base.config(),
                fault.equals("limits") ? new LocalCheckpointHistory.Limits(0, 0, 0) : base.limits(),
                fault.equals("fingerprint") ? "0".repeat(64) : base.fingerprint(),
                fault.equals("historyFingerprint") ? "0".repeat(64) : base.historyFingerprint(),
                base.encodedHistoryBytes() + (fault.equals("encodedBytes") ? 1 : 0),
                fault.equals("oracle") ? WorkloadOracle.prepare(WorkloadCorpus.generate(WorkloadCorpus.Profile.APPEND.spec(43, 1, 2, 0, 1))).summary()
                        : base.workload(), runtime, fault.equals("root") ? base.runRoot().resolve("..") : base.runRoot(), observations, base.gaps());
    }

    private LocalRecoveryComparison.Result nativeResult(RequestCase request) throws IOException {
        try { return LocalRecoveryComparison.run(request.scratch(), corpus(), new LocalRecoveryComparison.Config(0, 1),
                MeasurementMode.UNINSTRUMENTED, LocalCheckpointHistory.Limits.DEFAULT); }
        catch (LocalRecoveryComparison.RunFailedException failure) {
            if (System.getProperty("os.name").startsWith("Windows")) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof LocalCheckpointStore.DirectorySyncUnsupportedException) {
                        assumeTrue(false, "Explicit Windows directory-sync unavailability: " + cause);
                    }
                }
            }
            throw failure;
        }
    }

    private static void assertEnvelope(Map<?, ?> envelope, RequestCase request, MeasurementMode mode) {
        assertEquals(ENVELOPE_KEYS, envelope.keySet());
        assertEquals(1, number(envelope.get("schemaVersion")));
        assertEquals("MERIDIANDB_RECOVERY_COMPARISON", envelope.get("protocol"));
        assertEquals(mode.identifier(), envelope.get("mode"));
        assertEquals(ProcessHandle.current().pid(), number(envelope.get("workerPid")));
        var value = map(envelope.get("request"));
        assertEquals(REQUEST_KEYS, value.keySet());
        assertEquals(1, number(value.get("schemaVersion")));
        assertEquals(RUN, value.get("runId"));
        assertEquals(SOURCE, value.get("source"));
        assertEquals(mode.identifier(), value.get("mode"));
        assertEquals(request.scratch().toString(), value.get("scratch"));
        assertEquals(request.output().toString(), value.get("output"));
    }

    private static void assertFailure(Map<?, ?> envelope, String status) {
        assertEquals(status, envelope.get("status"));
        assertNotNull(envelope.get("error"));
        assertNull(envelope.get("result"));
        assertNull(envelope.get("continuationFrameHex"));
        assertEquals(List.of(), envelope.get("fixtureImages"));
        assertEquals(4, list(map(envelope.get("sourceHistory")).get("framesHex")).size());
    }

    private String assertInvalid(String[] args) {
        var bytes = new ByteArrayOutputStream();
        var previous = System.err;
        try (var stderr = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(stderr);
            assertEquals(2, RecoveryComparisonWorker.execute(args, (s, c, cfg, m, l) -> {
                throw new AssertionError("Invalid input reached execution");
            }));
        } finally { System.setErr(previous); }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void assertNoExecutionFiles(RequestCase request) throws IOException {
        assertFalse(Files.exists(request.output()));
        assertFalse(Files.exists(request.output().resolveSibling("worker.partial.json")));
        try (var files = Files.list(request.scratch())) { assertEquals(0, files.count()); }
    }

    private record RequestCase(Path scratch, Path output, String[] args) { }

    private RequestCase request(String name) throws IOException {
        var root = Files.createDirectory(directory.resolve(name)).toRealPath();
        var scratch = Files.createDirectory(root.resolve("scratch")).toRealPath();
        var output = root.resolve("worker.json");
        var limits = LocalCheckpointHistory.Limits.DEFAULT;
        var args = new String[] {RUN, SOURCE, LocalRecoveryComparison.UNINSTRUMENTED_MODE, "APPEND", "43", "0", "1",
                scratch.toString(), output.toString(), "1", "4", "5", "1", Long.toString(limits.maxObjectBytes()),
                Long.toString(limits.maxJournalBytes()), Long.toString(limits.maxRecordWork())};
        return new RequestCase(scratch, output, args);
    }

    private static String[] changed(String[] args, int index, String value) { var changed = args.clone(); changed[index] = value; return changed; }
    private static WorkloadCorpus corpus() { return WorkloadCorpus.generate(WorkloadCorpus.Profile.APPEND.spec(43, 1, 4, 5, 1)); }
    private static Map<?, ?> read(Path output) throws IOException { return JSON.readValue(Files.readAllBytes(output), Map.class); }
    private static Map<?, ?> map(Object value) { return (Map<?, ?>) value; }
    private static List<?> list(Object value) { return (List<?>) value; }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static byte[] hex(Object value) { return HexFormat.of().parseHex((String) value); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
