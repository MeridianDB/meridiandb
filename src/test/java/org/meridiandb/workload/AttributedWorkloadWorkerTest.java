// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import tools.jackson.databind.json.JsonMapper;

@Timeout(120)
class AttributedWorkloadWorkerTest {
    private static final String RUN = "0123456789abcdef0123456789abcdef";
    private static final String SOURCE = "0123456789abcdef0123456789abcdef01234567";
    private static final String MODE = "CALLER_THREAD_LOCAL_IO_INSTRUMENTED";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> V3_KEYS = Set.of("schemaVersion", "runId", "source", "profile", "seed", "workerPid",
            "status", "error", "io", "result", "dimensions", "mode", "attribution");
    private static final Set<String> ATTRIBUTION_KEYS = Set.of("schemaVersion", "mode", "scopes", "denominators", "gaps", "sourceHistory");

    @TempDir Path directory;

    @Test
    void modeAndTraceAdmissionRejectBeforeFilesystemInspectionOrEffects() throws IOException {
        Path missing = directory.resolve("must-not-be-inspected");
        var valid = args(missing, directory.resolve("worker.json"), 1, 100, 1024, 1, 0, 1);
        assertTrue(diagnostic(valid).contains("Aggregate attributed scope admission"));
        var unknown = valid.clone(); unknown[0] = "--different";
        assertTrue(diagnostic(unknown).contains("Exactly eight legacy or twelve"));
        assertTrue(diagnostic(Arrays.copyOf(valid, 9)).contains("Exactly eight legacy or twelve"));
        assertTrue(diagnostic(Arrays.copyOf(valid, 14)).contains("Exactly eight legacy or twelve"));
        var invalidSource = valid.clone(); invalidSource[2] = "abc";
        assertTrue(diagnostic(invalidSource).contains("full source SHA"));
        var excessiveReference = args(missing, directory.resolve("worker.json"), 1, 4001, 0, 1, 0, 1);
        assertTrue(diagnostic(excessiveReference).contains("reference-work admission"));
        var excessiveActions = args(missing, directory.resolve("worker.json"), 1, 1500, 0, 1, 2, 5);
        assertTrue(diagnostic(excessiveActions).contains("Conservative aggregate iteration action admission"));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test
    void actualGeneratedTraceBudgetAdmitsDefaultsAndItsExactBoundary() {
        var config = LocalWorkloadRunner.Config.DEFAULT;
        var defaultCorpus = WorkloadWorker.admitAttributedCorpus(WorkloadCorpus.Profile.CORRECTIONS.spec(42), config);
        assertEquals(205, defaultCorpus.actions().size());
        assertEquals(1962, LocalAttributedWorkload.admittedCapacity(defaultCorpus, config));
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.admitAttributedCorpus(defaultCorpus.spec(),
                new LocalWorkloadRunner.Config(2, 3)));
        var spec = boundarySpec();
        var boundary = WorkloadWorker.admitAttributedCorpus(spec, new LocalWorkloadRunner.Config(0, 1));
        assertEquals(984, boundary.actions().size());
        assertEquals(2048, LocalAttributedWorkload.admittedCapacity(boundary, new LocalWorkloadRunner.Config(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.admitAttributedCorpus(
                WorkloadCorpus.Profile.APPEND.spec(42, 1, 100, spec.queries() + 1, 1), new LocalWorkloadRunner.Config(0, 1)));
    }

    @Test
    void originalHistoryVectorHasLiteralFramingChecksumsSequenceAndIdentityWithoutRetries() throws Exception {
        var corpus = WorkloadCorpus.generate(new WorkloadCorpus.Spec(7, 1, 4, 5, 2, 100, 0, 0, 100, 100, 100));
        var history = WorkloadWorker.sourceHistory(corpus);
        assertEquals(4, history.framesHex().size(), "Three exact retries must not add original frames");
        var header = HexFormat.of().parseHex(history.headerHex());
        assertEquals(42, header.length);
        assertEquals("4d52444e4a4e4c00000000010000000b0077006f0072006b006c006f00610064002d00760031",
                history.headerHex().substring(0, 76));
        assertCrc(header, 0, 38, 38);
        var digest = MessageDigest.getInstance("SHA-256"); digest.update(header);
        for (int index = 0; index < 4; index++) {
            String hex = history.framesHex().get(index);
            assertTrue(hex.matches("[0-9a-f]{156}"));
            var frame = HexFormat.of().parseHex(hex);
            assertEquals(0x4d54584e, ByteBuffer.wrap(frame).getInt(0));
            assertEquals(1, ByteBuffer.wrap(frame).getInt(4));
            assertEquals(58, ByteBuffer.wrap(frame).getInt(8));
            assertEquals(index + 1L, ByteBuffer.wrap(frame).getLong(16));
            assertCrc(frame, 0, 12, 12);
            assertCrc(frame, 16, 58, 74);
            digest.update(frame);
        }
        assertEquals("cdbb2a26ffde6b13937bf196fa3fe77630c6b65922696e67073d37050be7a211",
                HexFormat.of().formatHex(digest.digest()));
        assertThrows(UnsupportedOperationException.class, () -> history.framesHex().clear());
        for (String invalid : List.of("", "a", "AA", "0g")) {
            assertThrows(IllegalArgumentException.class, () -> new WorkloadWorker.SourceHistory(invalid, List.of("00")));
        }
        var actions = new ArrayList<>(corpus.actions());
        var first = (WorkloadCorpus.Submit) actions.getFirst();
        actions.set(0, new WorkloadCorpus.Submit(2, first.request(), first.stage(), first.intent()));
        var unordered = new WorkloadCorpus(corpus.spec(), actions);
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.sourceHistory(unordered));
    }

    @Test
    void explicitDefaultRunExportsModeRawScopesHistoryAndFourSeparateProcessObservations() throws IOException {
        var outcome = execute("default", 16, 128, 64, 8, 1, 3, null);
        assertV3(outcome.envelope());
        if (outcome.code() == 3) { assertUnsupported(outcome.envelope()); return; }
        assertEquals(0, outcome.code());
        assertEquals("success", outcome.envelope().get("status"));
        assertNull(outcome.envelope().get("error"));
        var result = map(outcome.envelope().get("result"));
        assertEquals(1, number(result.get("schemaVersion")));
        var iterations = list(result.get("iterations"));
        var io = list(outcome.envelope().get("io"));
        assertEquals(4, iterations.size()); assertEquals(4, io.size());
        for (int index = 0; index < 4; index++) {
            var observation = map(io.get(index)); var iteration = map(iterations.get(index));
            assertEquals(index, number(observation.get("index")));
            assertEquals(index == 0, observation.get("warmup"));
            assertEquals(iteration.get("directory"), observation.get("directory"));
        }
        var attributed = map(outcome.envelope().get("attribution"));
        assertEquals(128, number(map(attributed.get("denominators")).get("newCommits")));
        assertEquals(128, list(map(attributed.get("sourceHistory")).get("framesHex")).size());
        var scopes = list(attributed.get("scopes"));
        assertTrue(scopes.size() <= 1962);
        assertSequentialScopes(scopes);
        assertTrue(Files.size(outcome.output()) < WorkloadWorker.MAX_JSON_BYTES);
    }

    @Test
    void maximumAdmittedTraceSerializesWithinExistingJsonByteNodeAndDepthLimits() throws IOException {
        var spec = boundarySpec();
        var outcome = execute("boundary", WorkloadCorpus.Profile.APPEND, 1, 100, spec.queries(), 1, 0, 1, null);
        assertV3(outcome.envelope());
        if (outcome.code() == 3) { assertUnsupported(outcome.envelope()); return; }
        assertEquals(0, outcome.code());
        var attribution = map(outcome.envelope().get("attribution"));
        // Admission reserves32 extra failure/teardown slots; successful scope inventory is2016.
        assertEquals(2016, list(attribution.get("scopes")).size());
        assertEquals(spec.queries(), number(map(attribution.get("denominators")).get("queries")));
        assertNotNull(WorkloadWorker.json(outcome.envelope()));
        assertTrue(Files.size(outcome.output()) < 16 * 1024 * 1024);
        assertFalse(Files.exists(outcome.output().resolveSibling("worker.partial.json")));
    }

    @Test
    void nativeFailureRetainsPartialScopesAndUnfinishedProcessObservationWithoutResult() throws IOException {
        var outcome = execute("native-failure", 1, 4, 5, 2, 0, 1, (scratch, corpus, config, observer) ->
                LocalAttributedWorkload.run(scratch, corpus, config, new LocalWorkloadRunner.IterationObserver() {
                    @Override public void before(int index, boolean warmup, Path home) throws IOException {
                        observer.before(index, warmup, home);
                        Files.createDirectory(home.resolve("transactions.journal"));
                    }
                    @Override public void after(int index, boolean warmup, Path home) throws IOException { observer.after(index, warmup, home); }
                }));
        assertV3(outcome.envelope());
        if (outcome.code() == 3) { assertUnsupported(outcome.envelope()); return; }
        assertEquals(1, outcome.code());
        assertEquals("failed", outcome.envelope().get("status"));
        assertNull(outcome.envelope().get("result"));
        var attribution = map(outcome.envelope().get("attribution"));
        var scopes = list(attribution.get("scopes"));
        assertTrue(scopes.size() > 2); assertSequentialScopes(scopes);
        assertTrue(scopes.stream().map(AttributedWorkloadWorkerTest::map).anyMatch(entry -> Boolean.FALSE.equals(entry.get("callReturned"))));
        assertNotNull(attribution.get("denominators")); assertNotNull(attribution.get("sourceHistory"));
        var io = list(outcome.envelope().get("io"));
        assertEquals(1, io.size()); assertNull(map(io.getFirst()).get("after"));
        assertNotNull(map(io.getFirst()).get("unavailable"));
        assertFalse(list(attribution.get("gaps")).isEmpty());
    }

    @Test
    void nestedFailedAndExplicitUnsupportedOutcomesPreserveDiagnosticScopes() throws Exception {
        var constructor = LocalCheckpointStore.DirectorySyncUnsupportedException.class.getDeclaredConstructor(Throwable.class);
        constructor.setAccessible(true);
        for (boolean unsupported : List.of(false, true)) {
            IOException cause = unsupported ? constructor.newInstance(new UnsupportedOperationException("capability")) : new IOException("ordinary I/O");
            var outcome = execute("injected-" + unsupported, 1, 2, 0, 1, 0, 1, (scratch, corpus, config, observer) -> {
                var trace = new LocalWorkloadIo(LocalAttributedWorkload.admittedCapacity(corpus, config));
                try { trace.call(LocalWorkloadIo.Category.PREPARATION, null, null, () -> { throw cause; }); }
                catch (IOException failure) {
                    throw new IOException("outer diagnostic", new LocalAttributedWorkload.RunFailedException(null, trace.entries(), failure));
                }
                throw new AssertionError("Failed preparation returned");
            });
            assertEquals(unsupported ? 3 : 1, outcome.code());
            assertV3(outcome.envelope());
            assertEquals(unsupported ? "unsupported" : "failed", outcome.envelope().get("status"));
            assertNull(outcome.envelope().get("result")); assertNotNull(outcome.envelope().get("error"));
            assertTrue(list(outcome.envelope().get("io")).isEmpty());
            var scopes = list(map(outcome.envelope().get("attribution")).get("scopes"));
            assertEquals(1, scopes.size()); assertEquals(false, map(scopes.getFirst()).get("callReturned"));
            assertEquals(cause.getClass().getName(), map(scopes.getFirst()).get("operationFailureType"));
        }
    }

    @Test
    void repeatedInvocationsStartFreshScopesAndKeepEarlierEvidenceUnchanged() throws IOException {
        var first = execute("first", 2, 5, 5, 2, 0, 1, null);
        var bytes = Files.readAllBytes(first.output());
        var second = execute("second", 2, 5, 5, 2, 0, 1, null);
        assertV3(first.envelope()); assertV3(second.envelope());
        assertArrayEquals(bytes, Files.readAllBytes(first.output()));
        if (first.code() == 3 || second.code() == 3) {
            if (first.code() == 3) { assertUnsupported(first.envelope()); }
            if (second.code() == 3) { assertUnsupported(second.envelope()); }
            return;
        }
        assertEquals(0, first.code()); assertEquals(0, second.code());
        var firstAttribution = map(first.envelope().get("attribution"));
        var secondAttribution = map(second.envelope().get("attribution"));
        assertSequentialScopes(list(firstAttribution.get("scopes"))); assertSequentialScopes(list(secondAttribution.get("scopes")));
        assertEquals(firstAttribution.get("sourceHistory"), secondAttribution.get("sourceHistory"));
        assertEquals(firstAttribution.get("denominators"), secondAttribution.get("denominators"));
        assertNotEquals(map(first.envelope().get("result")).get("runRoot"), map(second.envelope().get("result")).get("runRoot"));
    }

    @Test
    void legacyInvocationsKeepTheirOriginalExactKeySetsAndNeverUseAttributedExecution() throws IOException {
        for (boolean dimensioned : List.of(false, true)) {
            var root = Files.createDirectory(directory.resolve("legacy-" + dimensioned));
            var scratch = Files.createDirectory(root.resolve("scratch")).toRealPath();
            var output = root.toRealPath().resolve("worker.json");
            var attributed = args(scratch, output, 1, 2, 0, 1, 0, 1);
            var legacy = Arrays.copyOfRange(attributed, 1, dimensioned ? 13 : 9);
            int code = WorkloadWorker.execute(legacy, (s, c, cfg, observer) -> { throw new AssertionError("Legacy execution activated attribution"); });
            assertTrue(code == 0 || code == 3);
            var envelope = read(output);
            var expected = new java.util.HashSet<>(V3_KEYS); expected.remove("mode"); expected.remove("attribution");
            if (!dimensioned) { expected.remove("dimensions"); }
            assertEquals(expected, envelope.keySet());
            assertEquals(dimensioned ? 2 : 1, number(envelope.get("schemaVersion")));
        }
    }

    private record Outcome(int code, Path output, Map<?, ?> envelope) { }

    private Outcome execute(String name, int entities, int commits, int queries, int width, int warmups, int repetitions,
                            WorkloadWorker.AttributedExecution execution) throws IOException {
        return execute(name, WorkloadCorpus.Profile.CORRECTIONS, entities, commits, queries, width, warmups, repetitions, execution);
    }

    private Outcome execute(String name, WorkloadCorpus.Profile profile, int entities, int commits, int queries, int width,
                            int warmups, int repetitions, WorkloadWorker.AttributedExecution execution) throws IOException {
        var root = Files.createDirectory(directory.resolve(name));
        var scratch = Files.createDirectory(root.resolve("scratch")).toRealPath();
        var output = root.toRealPath().resolve("worker.json");
        var args = args(scratch, output, entities, commits, queries, width, warmups, repetitions);
        args[3] = profile.name();
        int code = execution == null ? WorkloadWorker.execute(args) : WorkloadWorker.execute(args, execution);
        assertTrue(code == 0 || code == 1 || code == 3, "Valid request should execute and export: " + code);
        return new Outcome(code, output, read(output));
    }

    private static WorkloadCorpus.Spec boundarySpec() {
        int attempts = WorkloadCorpus.generate(WorkloadCorpus.Profile.APPEND.spec(42, 1, 100, 0, 1)).actions().size();
        return WorkloadCorpus.Profile.APPEND.spec(42, 1, 100, 984 - attempts, 1);
    }

    private static String[] args(Path scratch, Path output, int entities, int commits, int queries, int width, int warmups, int repetitions) {
        return new String[] {"--attributed", RUN, SOURCE, "CORRECTIONS", "42", Integer.toString(warmups), Integer.toString(repetitions),
                scratch.toAbsolutePath().toString(), output.toAbsolutePath().toString(), Integer.toString(entities), Integer.toString(commits),
                Integer.toString(queries), Integer.toString(width)};
    }

    private static String diagnostic(String[] args) {
        var bytes = new ByteArrayOutputStream();
        var previous = System.err;
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(output);
            assertEquals(2, WorkloadWorker.execute(args));
        } finally { System.setErr(previous); }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static Map<?, ?> read(Path output) throws IOException { return JSON.readValue(Files.readAllBytes(output), Map.class); }
    private static Map<?, ?> map(Object value) { return (Map<?, ?>) value; }
    private static List<?> list(Object value) { return (List<?>) value; }
    private static long number(Object value) { return ((Number) value).longValue(); }

    private static void assertV3(Map<?, ?> envelope) {
        assertEquals(V3_KEYS, envelope.keySet()); assertEquals(3, number(envelope.get("schemaVersion")));
        assertEquals(RUN, envelope.get("runId")); assertEquals(SOURCE, envelope.get("source")); assertEquals(MODE, envelope.get("mode"));
        var attribution = map(envelope.get("attribution"));
        assertEquals(ATTRIBUTION_KEYS, attribution.keySet()); assertEquals(1, number(attribution.get("schemaVersion")));
        assertEquals(MODE, attribution.get("mode"));
    }

    private static void assertUnsupported(Map<?, ?> envelope) {
        assertEquals("unsupported", envelope.get("status")); assertNull(envelope.get("result")); assertNotNull(envelope.get("error"));
        assertNotNull(envelope.get("attribution"));
    }

    private static void assertSequentialScopes(List<?> scopes) {
        for (int i = 0; i < scopes.size(); i++) { assertEquals(i, number(map(scopes.get(i)).get("ordinal"))); }
    }

    private static void assertCrc(byte[] bytes, int offset, int length, int checksumOffset) {
        var crc = new CRC32C(); crc.update(bytes, offset, length);
        assertEquals((int) crc.getValue(), ByteBuffer.wrap(bytes).getInt(checksumOffset));
    }
}
