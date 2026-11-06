// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import tools.jackson.databind.json.JsonMapper;

@Timeout(90)
class WorkloadWorkerTest {
    private static final String RUN = "0123456789abcdef0123456789abcdef";
    private static final String SOURCE = "0123456789abcdef0123456789abcdef01234567";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path directory;

    public record Exported(String name, List<Object> values, Path path) { }

    /** Deterministic collision after publication preflight and before final-name installation. */
    public record PublicationCollision(Path target) {
        @Override public Path target() {
            try {
                Files.writeString(target, "concurrent destination", StandardOpenOption.CREATE_NEW);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            return target;
        }
    }

    @Test
    void jsonRoundTripsExactUtf16UnitsAndAsciiEscapesWithoutLocaleDependence() throws IOException {
        String original = "\"\\\n\u0000\uD800\uDC00\uD800\uFFFD";
        String encoded = WorkloadWorker.json(original);
        assertEquals("\"\\\"\\\\\\u000a\\u0000\\ud800\\udc00\\ud800\\ufffd\"", encoded);
        assertTrue(encoded.chars().allMatch(unit -> unit <= 0x7f));
        assertEquals(original, JSON.readTree(encoded).stringValue());
        assertEquals("-9223372036854775808", WorkloadWorker.json(Long.MIN_VALUE));
        assertEquals("9223372036854775807", WorkloadWorker.json(Long.MAX_VALUE));
    }

    @Test
    void publicRecordsMapsEnumsListsAndNullHaveExplicitJsonRepresentations() throws IOException {
        var value = new Exported("sample", List.of(1, true, WorkloadMeasurements.Phase.MIXED), Path.of("home"));
        assertEquals("{\"name\":\"sample\",\"values\":[1,true,\"MIXED\"],\"path\":\"home\"}",
                WorkloadWorker.json(value));
        var map = new LinkedHashMap<Object, Object>();
        map.put(WorkloadMeasurements.Kind.GET, null);
        assertEquals("{\"GET\":null}", WorkloadWorker.json(map));
        map.put("GET", 2);
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.json(map));
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.json(Map.of(1, "invalid key")));
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.json(new IOException("not recursive evidence")));
    }

    @Test
    void jsonRejectsNonfiniteNumbersInsteadOfEmittingNonstandardConstants() {
        for (Number number : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.json(number));
        }
    }

    @Test
    void byteDepthAndNodeBoundsRejectBeforeUnboundedMaterialization() throws IOException {
        assertEquals("null", WorkloadWorker.json(null, 4, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.json(null, 3, 0, 1));
        assertEquals("\"\\ud800\"", WorkloadWorker.json("\uD800", 8, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.json("\uD800", 7, 0, 1));
        var nested = List.of(List.of("leaf"));
        assertEquals("[[\"leaf\"]]", WorkloadWorker.json(nested, 32, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.json(nested, 32, 1, 3));
        assertEquals("{\"a\":1}", WorkloadWorker.json(Map.of("a", 1), 16, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.json(Map.of("a", 1), 16, 1, 2));
        var cyclic = new ArrayList<Object>();
        cyclic.add(cyclic);
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.json(cyclic));
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.json(null, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.json(null, 4, 33, 1));
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.json(null, 4, 0, 500_001));
    }

    @Test
    void completedEvidenceIsCreateOnlyAndPartialCandidatesRemainOnExportFailure() throws IOException {
        Path output = directory.resolve("worker.json");
        WorkloadWorker.writeEnvelope(output, Map.of("ok", true));
        assertEquals("{\"ok\":true}\n", Files.readString(output));
        assertFalse(Files.exists(directory.resolve("worker.partial.json")));
        assertThrows(IOException.class, () -> WorkloadWorker.writeEnvelope(output, Map.of("overwrite", true)));
        assertEquals("{\"ok\":true}\n", Files.readString(output));

        Path failedRoot = Files.createDirectory(directory.resolve("failed"));
        Path failedOutput = failedRoot.resolve("worker.json");
        var invalid = new LinkedHashMap<String, Object>();
        invalid.put("prefix", 1);
        invalid.put("unsupported", new Object());
        assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.writeEnvelope(failedOutput, invalid));
        assertFalse(Files.exists(failedOutput));
        Path partial = failedRoot.resolve("worker.partial.json");
        assertTrue(Files.readString(partial).startsWith("{\"prefix\":1,"));
        String retained = Files.readString(partial);
        assertThrows(IOException.class, () -> WorkloadWorker.writeEnvelope(failedOutput, Map.of("retry", true)));
        assertEquals(retained, Files.readString(partial));

        Path collisionRoot = Files.createDirectory(directory.resolve("collision"));
        Path collisionOutput = collisionRoot.resolve("worker.json");
        assertThrows(IOException.class,
                () -> WorkloadWorker.writeEnvelope(collisionOutput, new PublicationCollision(collisionOutput)));
        assertEquals("concurrent destination", Files.readString(collisionOutput));
        Path collisionPartial = collisionRoot.resolve("worker.partial.json");
        assertEquals(collisionOutput.toString(), JSON.readTree(Files.readString(collisionPartial)).get("target").stringValue());
        assertFalse(Files.isSameFile(collisionOutput, collisionPartial));
    }

    @Test
    void procParserRequiresSevenExactNonnegativeBoundedCounters() {
        var parsed = WorkloadWorker.parseProcIo(proc(7));
        assertEquals(7, parsed.size());
        assertEquals(WorkloadWorker.IO_FIELDS, new ArrayList<>(parsed.keySet()));
        assertTrue(parsed.values().stream().allMatch(value -> value == 7));
        assertThrows(UnsupportedOperationException.class, parsed::clear);
        for (String invalid : List.of(proc(7) + "rchar: 7\n", proc(7).replace("rchar: 7", "rchar: -1"),
                proc(7).replace("rchar: 7", "rchar: 9223372036854775808"),
                proc(7).replace("rchar: 7\n", ""), proc(7) + "future_counter: 1\n",
                proc(7).replace("rchar: 7", "rchar 7"), "x".repeat(4097))) {
            assertThrows(IllegalArgumentException.class, () -> WorkloadWorker.parseProcIo(invalid));
        }
        assertEquals(Long.MAX_VALUE, WorkloadWorker.parseProcIo(proc(Long.MAX_VALUE)).get("write_bytes"));
    }

    @Test
    void procObserverPreservesRawSnapshotsAndExactInstrumentedIterationDeltas() throws IOException {
        Path source = directory.resolve("proc-io");
        Files.writeString(source, proc(10));
        var observer = new WorkloadWorker.ProcessIoObserver(source);
        observer.before(0, true, directory);
        Files.writeString(source, proc(14));
        observer.after(0, true, directory);
        var observation = observer.observations().getFirst();
        assertTrue(observation.warmup());
        assertEquals(directory, observation.directory());
        assertTrue(observation.before().values().stream().allMatch(value -> value == 10));
        assertTrue(observation.after().values().stream().allMatch(value -> value == 14));
        assertTrue(observation.delta().values().stream().allMatch(value -> value == 4));
        assertNull(observation.unavailable());
        assertThrows(UnsupportedOperationException.class, () -> observer.observations().clear());
        assertThrows(UnsupportedOperationException.class, () -> observation.before().clear());
        assertThrows(UnsupportedOperationException.class, () -> observation.delta().clear());
        assertThrows(IllegalArgumentException.class, () -> observer.after(0, true, directory));
        assertThrows(IllegalArgumentException.class, () -> observer.before(2, false, directory));
    }

    @Test
    void missingMalformedResetAndUnfinishedProcEvidenceStayUnavailableRatherThanZero() throws IOException {
        Path source = directory.resolve("proc-io");
        var missing = new WorkloadWorker.ProcessIoObserver(source);
        missing.before(0, false, directory);
        missing.after(0, false, directory);
        var absent = missing.observations().getFirst();
        assertNull(absent.before());
        assertNull(absent.after());
        assertNull(absent.delta());
        assertNotNull(absent.unavailable());
        assertThrows(IllegalArgumentException.class, () -> missing.after(0, false, directory));

        Files.writeString(source, proc(10));
        var reset = new WorkloadWorker.ProcessIoObserver(source);
        reset.before(0, false, directory);
        Files.writeString(source, proc(9));
        reset.after(0, false, directory);
        assertNull(reset.observations().getFirst().delta());
        assertTrue(reset.observations().getFirst().unavailable().contains("Nonmonotonic"));
        assertNotNull(reset.observations().getFirst().before());
        assertNotNull(reset.observations().getFirst().after());

        var malformed = new WorkloadWorker.ProcessIoObserver(source);
        malformed.before(0, false, directory);
        Files.writeString(source, "rchar: 1\n");
        malformed.after(0, false, directory);
        assertNotNull(malformed.observations().getFirst().before());
        assertNull(malformed.observations().getFirst().after());
        assertNull(malformed.observations().getFirst().delta());

        Files.writeString(source, proc(1));
        var unfinished = new WorkloadWorker.ProcessIoObserver(source);
        unfinished.before(0, false, directory);
        assertNotNull(unfinished.observations().getFirst().before());
        assertNull(unfinished.observations().getFirst().delta());
        assertEquals("Iteration did not complete", unfinished.observations().getFirst().unavailable());
    }

    @Test
    void observerRefusesMismatchedBindings() throws IOException {
        Path source = directory.resolve("proc-io");
        Files.writeString(source, proc(1));
        var observer = new WorkloadWorker.ProcessIoObserver(source);
        assertThrows(IllegalArgumentException.class, () -> observer.after(0, false, directory));
        observer.before(0, true, directory);
        assertThrows(IllegalArgumentException.class, () -> observer.after(0, false, directory));
        assertThrows(IllegalArgumentException.class, () -> observer.after(1, true, directory));
        assertThrows(IllegalArgumentException.class, () -> observer.after(0, true, directory.resolve("different")));
    }

    @Test
    void unsupportedStatusRequiresTheExactCapabilityExceptionInTheCauseChain() throws ReflectiveOperationException {
        assertEquals("failed", WorkloadWorker.failureStatus(new IOException("directory sync unsupported")));
        assertEquals("failed", WorkloadWorker.failureStatus(new UnsupportedOperationException("native")));
        var constructor = LocalCheckpointStore.DirectorySyncUnsupportedException.class.getDeclaredConstructor(Throwable.class);
        constructor.setAccessible(true);
        var unsupported = constructor.newInstance(new UnsupportedOperationException("native capability"));
        assertEquals("unsupported", WorkloadWorker.failureStatus(new IOException("wrapped", unsupported)));
        var first = new IOException("one");
        var second = new IOException("two", first);
        first.initCause(second);
        assertEquals("failed", WorkloadWorker.failureStatus(first));
    }

    @Test
    void invalidCliDoesNotCreateOutputOrStartTheRunner() throws IOException {
        Path scratch = Files.createDirectory(directory.resolve("scratch")).toRealPath();
        Path output = scratch.getParent().resolve("worker.json");
        String[] valid = args(scratch, output);
        assertEquals(2, WorkloadWorker.execute(new String[0]));
        for (int position : List.of(0, 1, 2, 3, 4, 5)) {
            String[] invalid = valid.clone();
            invalid[position] = "invalid";
            assertEquals(2, WorkloadWorker.execute(invalid));
        }
        String[] negativeWarmups = valid.clone();
        negativeWarmups[4] = "-1";
        assertEquals(2, WorkloadWorker.execute(negativeWarmups));
        String[] outsideOutput = valid.clone();
        outsideOutput[7] = scratch.resolve("worker.json").toString();
        assertEquals(2, WorkloadWorker.execute(outsideOutput));
        String[] partialOutput = valid.clone();
        partialOutput[7] = output.resolveSibling("worker.partial.json").toString();
        assertEquals(2, WorkloadWorker.execute(partialOutput));
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(output.resolveSibling("worker.partial.json")));
        try (var contents = Files.list(scratch)) { assertTrue(contents.findAny().isEmpty()); }
    }

    @Test
    void nativeWorkerEmitsExactEnvelopeAndWarmupBindingsWithoutExternalJsonDependencies() throws IOException {
        Path scratch = Files.createDirectory(directory.resolve("scratch")).toRealPath();
        Path output = scratch.getParent().resolve("worker.json");
        String[] arguments = args(scratch, output);
        arguments[4] = "1";
        int code = WorkloadWorker.execute(arguments);
        assertTrue(code == 0 || code == 3, "Ordinary worker failure is not an unsupported success: " + code);
        Map<?, ?> envelope = JSON.readValue(Files.readAllBytes(output), Map.class);
        assertEquals(Set.of("schemaVersion", "runId", "source", "profile", "seed", "workerPid",
                "status", "error", "io", "result"), envelope.keySet());
        assertEquals(1, ((Number) envelope.get("schemaVersion")).intValue());
        assertEquals(RUN, envelope.get("runId"));
        assertEquals(SOURCE, envelope.get("source"));
        assertEquals("APPEND", envelope.get("profile"));
        assertEquals(42, ((Number) envelope.get("seed")).intValue());
        assertEquals(ProcessHandle.current().pid(), ((Number) envelope.get("workerPid")).longValue());
        assertFalse(Files.exists(output.resolveSibling("worker.partial.json")));
        if (code == 3) {
            assertEquals("unsupported", envelope.get("status"));
            assertNotNull(envelope.get("error"));
            assertNull(envelope.get("result"));
        } else {
            assertEquals("success", envelope.get("status"));
            assertNull(envelope.get("error"));
            Map<?, ?> result = (Map<?, ?>) envelope.get("result");
            assertEquals(WorkloadCorpus.generate(WorkloadCorpus.Profile.APPEND.spec(42)).fingerprint(), result.get("fingerprint"));
            List<?> iterations = (List<?>) result.get("iterations");
            List<?> io = (List<?>) envelope.get("io");
            assertEquals(2, iterations.size());
            assertEquals(2, io.size());
            for (int index = 0; index < io.size(); index++) {
                Map<?, ?> observation = (Map<?, ?>) io.get(index);
                assertEquals(Set.of("index", "warmup", "directory", "before", "after", "delta", "unavailable"), observation.keySet());
                assertEquals(index, ((Number) observation.get("index")).intValue());
                assertEquals(index == 0, observation.get("warmup"));
                assertEquals(((Map<?, ?>) iterations.get(index)).get("directory"), observation.get("directory"));
            }
        }
        byte[] retained = Files.readAllBytes(output);
        assertEquals(2, WorkloadWorker.execute(args(scratch, output)));
        assertEquals(new String(retained, StandardCharsets.UTF_8), Files.readString(output));
    }

    @Test
    void dimensionOverloadPreservesDefaultProfilesAndTheirExistingHistoryIdentity() {
        for (var profile : WorkloadCorpus.Profile.values()) {
            var legacy = profile.spec(42);
            var explicit = profile.spec(42, 16, 128, 64, 8);
            assertEquals(legacy, explicit);
            var original = WorkloadCorpus.generate(legacy);
            var repeated = WorkloadCorpus.generate(explicit);
            assertEquals(original.actions(), repeated.actions());
            assertEquals(original.fingerprint(), repeated.fingerprint());
            assertEquals(original.historyFingerprint(), repeated.historyFingerprint());
            assertEquals(original.encodedHistoryBytes(), repeated.encodedHistoryBytes());
            var scaled = profile.spec(42, 7, 33, 11, 3);
            assertEquals(List.of(legacy.correctionPercent(), legacy.retractionPercent(), legacy.repeatPercent(),
                            legacy.retryPercent(), legacy.hotEntityPercent(), legacy.historicalReadPercent()),
                    List.of(scaled.correctionPercent(), scaled.retractionPercent(), scaled.repeatPercent(),
                            scaled.retryPercent(), scaled.hotEntityPercent(), scaled.historicalReadPercent()));
        }
        var append = WorkloadCorpus.generate(WorkloadCorpus.Profile.APPEND.spec(42));
        assertEquals("b33801d18462c16e588432baee439948b6aea87d2a8866dfeb6b0d15415150aa", append.fingerprint());
        assertEquals("0a3b5455dfad2e0ac05cbf95954de3a43f9f95b3b7832b7ab0e20e367e51c8c5", append.historyFingerprint());
        assertEquals(10432, append.encodedHistoryBytes());
    }

    @Test
    void independentDimensionsRespectReferenceAndConservativeRepeatedActionBoundaries() {
        var profile = WorkloadCorpus.Profile.CORRECTIONS;
        var one = new LocalWorkloadRunner.Config(0, 1);
        var five = new LocalWorkloadRunner.Config(0, 5);
        var minimum = WorkloadWorker.admitSpec(profile, 42, one, 1, 2, 0, 1);
        assertEquals(1, minimum.entities());
        assertEquals(2, minimum.commits());
        assertEquals(0, minimum.queries());
        assertEquals(1, minimum.intervalWidth());
        assertEquals(1024, WorkloadWorker.admitSpec(profile, 42, one, 1024, 1025, 0, 16).entities());
        assertEquals(1024, WorkloadWorker.admitSpec(profile, 42, one, 1, 2, 1024, 1).queries());
        assertEquals(4000, WorkloadWorker.admitSpec(profile, 42, one, 1, 4000, 0, 1).commits(),
                "Exactly32,000,000 reference-work units remain admitted without generating a large corpus");
        assertThrows(IllegalArgumentException.class,
                () -> WorkloadWorker.admitSpec(profile, 42, one, 1, 4001, 0, 1));
        assertEquals(2000, WorkloadWorker.admitSpec(profile, 42, five, 1, 2000, 0, 1).commits(),
                "The conservative repeated action count is exactly20,000");
        assertThrows(IllegalArgumentException.class,
                () -> WorkloadWorker.admitSpec(profile, 42, five, 1, 2001, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> WorkloadWorker.admitSpec(profile, 42, new LocalWorkloadRunner.Config(2, 5), 1, 1500, 0, 1));
    }

    @Test
    void extendedArgumentAdmissionRejectsInvalidWorkBeforeFilesystemInspectionOrGeneration() throws IOException {
        Path missing = directory.resolve("must-not-be-inspected").toAbsolutePath();
        String[] valid = dimensionedArgs(missing, directory.resolve("worker.json").toAbsolutePath(), 7, 33, 11, 3);
        for (int length : List.of(9, 10, 11, 13)) {
            assertTrue(invalidDiagnostic(Arrays.copyOf(valid, length)).contains("Exactly eight legacy or twelve"));
        }
        for (int[] dimensions : List.of(new int[] {0, 2, 0, 1}, new int[] {1025, 1026, 0, 1},
                new int[] {7, 7, 11, 3}, new int[] {1, 4097, 0, 1}, new int[] {1, 2, -1, 1},
                new int[] {1, 2, 1025, 1}, new int[] {1, 2, 0, 0}, new int[] {1, 2, 0, 17})) {
            var invalid = dimensionedArgs(missing, directory.resolve("worker.json").toAbsolutePath(),
                    dimensions[0], dimensions[1], dimensions[2], dimensions[3]);
            assertTrue(invalidDiagnostic(invalid).contains("Workload dimensions exceed"));
        }
        assertTrue(invalidDiagnostic(dimensionedArgs(missing, directory.resolve("worker.json").toAbsolutePath(),
                1, 4001, 0, 1)).contains("reference-work admission"));
        var overRepeatedWork = dimensionedArgs(missing, directory.resolve("worker.json").toAbsolutePath(), 1, 1500, 0, 1);
        overRepeatedWork[4] = "2";
        overRepeatedWork[5] = "5";
        assertTrue(invalidDiagnostic(overRepeatedWork).contains("Conservative aggregate iteration action admission"));
        var integerOverflow = valid.clone();
        integerOverflow[8] = "2147483648";
        assertTrue(invalidDiagnostic(integerOverflow).contains("2147483648"));
        assertFalse(Files.exists(missing));
        assertFalse(Files.exists(directory.resolve("worker.json")));
        assertFalse(Files.exists(directory.resolve("worker.partial.json")));
    }

    @Test
    void scaledNativeWorkerRetainsExactDimensionsReadsAndNonmultipleSegmentHistory() throws IOException {
        Path scratch = Files.createDirectory(directory.resolve("scaled-scratch")).toRealPath();
        Path output = scratch.getParent().resolve("scaled-worker.json");
        String[] arguments = dimensionedArgs(scratch, output, 7, 33, 11, 3);
        arguments[2] = "CORRECTIONS";
        int code = WorkloadWorker.execute(arguments);
        assertTrue(code == 0 || code == 3, "Ordinary scaled worker failure is not an unsupported success: " + code);
        Map<?, ?> envelope = JSON.readValue(Files.readAllBytes(output), Map.class);
        assertEquals(Set.of("schemaVersion", "runId", "source", "profile", "seed", "workerPid",
                "status", "error", "io", "result", "dimensions"), envelope.keySet());
        assertEquals(2, ((Number) envelope.get("schemaVersion")).intValue());
        assertEquals(Map.of("entities", 7, "commits", 33, "queries", 11, "intervalWidth", 3), envelope.get("dimensions"));
        assertEquals(RUN, envelope.get("runId"));
        assertEquals(SOURCE, envelope.get("source"));
        assertEquals("CORRECTIONS", envelope.get("profile"));
        assertEquals(42, ((Number) envelope.get("seed")).intValue());
        assertEquals(ProcessHandle.current().pid(), ((Number) envelope.get("workerPid")).longValue());
        if (code == 3) {
            assertEquals("unsupported", envelope.get("status"));
            assertNotNull(envelope.get("error"));
            assertNull(envelope.get("result"));
            return;
        }
        assertEquals("success", envelope.get("status"));
        assertNull(envelope.get("error"));
        Map<?, ?> result = (Map<?, ?>) envelope.get("result");
        assertEquals(1, ((Number) result.get("schemaVersion")).intValue());
        assertEquals(1, ((Number) result.get("corpusVersion")).intValue());
        var corpus = WorkloadCorpus.generate(WorkloadCorpus.Profile.CORRECTIONS.spec(42, 7, 33, 11, 3));
        assertEquals(corpus.fingerprint(), result.get("fingerprint"));
        assertEquals(corpus.historyFingerprint(), result.get("historyFingerprint"));
        assertEquals(corpus.encodedHistoryBytes(), ((Number) result.get("encodedHistoryBytes")).longValue());
        Map<?, ?> spec = (Map<?, ?>) result.get("spec");
        for (String name : List.of("entities", "commits", "queries", "intervalWidth")) {
            assertEquals(((Map<?, ?>) envelope.get("dimensions")).get(name), spec.get(name));
        }
        List<?> iterations = (List<?>) result.get("iterations");
        assertEquals(1, iterations.size());
        Map<?, ?> iteration = (Map<?, ?>) iterations.getFirst();
        assertEquals(3, ((Number) iteration.get("sourceSegments")).intValue());
        assertEquals(1, ((Number) iteration.get("replacementSegments")).intValue());
        Map<?, ?> named = (Map<?, ?>) iteration.get("namedStorage");
        assertEquals(10, ((Map<?, ?>) named.get("fileLengths")).size());
        List<?> samples = (List<?>) iteration.get("samples");
        var counts = samples.stream().map(sample -> (Map<?, ?>) sample).collect(java.util.stream.Collectors.groupingBy(
                sample -> sample.get("phase") + ":" + sample.get("kind"), java.util.stream.Collectors.counting()));
        assertEquals(7L, counts.get("LOAD:COMMIT"));
        assertEquals(26L, counts.get("MIXED:COMMIT"));
        assertEquals(3L, counts.get("MIXED:GET"));
        for (String kind : List.of("SCAN", "SELECT", "FILTER_SCAN", "SUM")) {
            assertEquals(2L, counts.get("MIXED:" + kind));
        }
        List<?> io = (List<?>) envelope.get("io");
        assertEquals(1, io.size());
        Map<?, ?> observation = (Map<?, ?>) io.getFirst();
        assertEquals(false, observation.get("warmup"));
        assertEquals(iteration.get("directory"), observation.get("directory"));

        Path zeroScratch = Files.createDirectory(directory.resolve("zero-read-scratch")).toRealPath();
        Path zeroOutput = zeroScratch.getParent().resolve("zero-worker.json");
        assertEquals(0, WorkloadWorker.execute(dimensionedArgs(zeroScratch, zeroOutput, 1, 2, 0, 1)));
        Map<?, ?> zeroEnvelope = JSON.readValue(Files.readAllBytes(zeroOutput), Map.class);
        assertEquals(2, ((Number) zeroEnvelope.get("schemaVersion")).intValue());
        assertEquals(Map.of("entities", 1, "commits", 2, "queries", 0, "intervalWidth", 1), zeroEnvelope.get("dimensions"));
        Map<?, ?> zeroResult = (Map<?, ?>) zeroEnvelope.get("result");
        assertEquals(0, ((Number) ((Map<?, ?>) zeroResult.get("workload")).get("reads")).intValue());
        Map<?, ?> zeroIteration = (Map<?, ?>) ((List<?>) zeroResult.get("iterations")).getFirst();
        assertEquals(2, ((Number) zeroIteration.get("sourceSegments")).intValue());
        assertEquals(1, ((Number) zeroIteration.get("replacementSegments")).intValue());
        assertTrue(((List<?>) zeroIteration.get("samples")).stream().map(sample -> (Map<?, ?>) sample)
                .noneMatch(sample -> Set.of("GET", "SCAN", "SELECT", "FILTER_SCAN", "SUM").contains(sample.get("kind"))));
    }

    private static String[] args(Path scratch, Path output) {
        return new String[] {RUN, SOURCE, "APPEND", "42", "0", "1", scratch.toString(), output.toString()};
    }

    private static String[] dimensionedArgs(Path scratch, Path output, int entities, int commits, int queries, int intervalWidth) {
        String[] result = Arrays.copyOf(args(scratch, output), 12);
        result[8] = Integer.toString(entities);
        result[9] = Integer.toString(commits);
        result[10] = Integer.toString(queries);
        result[11] = Integer.toString(intervalWidth);
        return result;
    }

    private static String invalidDiagnostic(String[] arguments) throws IOException {
        var bytes = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            try { assertEquals(2, WorkloadWorker.execute(arguments)); }
            finally { System.setErr(previous); }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String proc(long value) {
        return WorkloadWorker.IO_FIELDS.stream().map(key -> key + ": " + value + "\n")
                .collect(java.util.stream.Collectors.joining());
    }
}
