// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.s3.LocalS3PublicationState.Boundary;

/** Real JVM exits establish metadata reopen behavior, not device power-loss or remote selection. */
@Timeout(60)
class LocalS3PublicationStateProcessTest {
    private static final int HALT_STATUS = 74;
    private static final int UNSUPPORTED_STATUS = 77;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);
    private static final URI ENDPOINT = URI.create("https://example.test");
    private static final S3PublicationState.Target TARGET = new S3PublicationState.Target(ENDPOINT, "us-east-1", "bucket");
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final ObjectId LINEAGE = new ObjectId(
            "2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c");
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, LINEAGE);
    private static final String FIRST_TOKEN = "\"original,opaque\\token\"";
    private static final String SECOND_TOKEN = "\"selected-second\"";
    private static final ObjectReference MANIFEST = new ObjectReference(new ObjectId(
            "bd068a065754e52824efaf91c44bc7234985e9a93c024dcb90e6951152e66982"), 136);
    private static final ObjectCheckpointHead FIRST = head(1, 3, null);
    private static final byte[] FIRST_IMAGE = ObjectCheckpointHeadCodec.encode(FIRST);
    private static final byte[] SECOND_IMAGE = ObjectCheckpointHeadCodec.encode(
            head(2, 4, ObjectCheckpointHeadCodec.reference(FIRST)));

    @TempDir Path directory;

    private enum Stage { CREATE, PREPARE_CREATE, RECORD_CREATE, PREPARE_REPLACE, RECORD_REPLACE }

    static Stream<Arguments> installationBoundaries() {
        return Stream.of(Stage.values()).flatMap(stage -> Stream.of(
                Boundary.CANDIDATE_SYNCED, Boundary.STATE_RENAMED, Boundary.DIRECTORY_SYNCED)
                .map(boundary -> Arguments.of(stage, boundary)));
    }

    @ParameterizedTest(name = "{0} halted at {1}")
    @MethodSource("installationBoundaries")
    void haltedInstallReopensOnlySelectedMetadataAndRetainsExactUnselectedEvidence(Stage stage, Boundary boundary)
            throws Exception {
        var home = Files.createDirectory(directory.resolve("store"));
        setup(home, stage);
        try (var child = start("halt-install", home, stage.name(), boundary.name())) {
            assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
            assertTrue(child.diagnostics().lines().anyMatch(("HALT " + boundary)::equals), child.diagnostics());
        }
        var installed = after(stage);
        boolean renamed = boundary != Boundary.CANDIDATE_SYNCED;
        var candidates = pendingFiles(home);
        if (renamed) {
            assertTrue(candidates.isEmpty());
            assertSelectedImage(home, installed);
            assertReopened(home, installed);
            assertEquals(installed.generation(), selectedFiles(home).size());
        } else {
            assertEquals(1, candidates.size());
            assertCandidate(candidates.getFirst(), installed);
            var previous = before(stage);
            if (previous == null) {
                assertTrue(selectedFiles(home).isEmpty());
                assertThrows(IOException.class, () -> {
                    try (var unexpected = LocalS3PublicationState.open(home, TARGET, BINDING)) { unexpected.state(); }
                });
                assertThrows(IOException.class, () -> {
                    try (var unexpected = LocalS3PublicationState.create(home, TARGET, BINDING)) { unexpected.state(); }
                });
            } else {
                assertSelectedImage(home, previous);
                assertReopened(home, previous);
                assertReopened(home, previous); // Opening never installs a pending candidate implicitly.
                assertEquals(previous.generation(), selectedFiles(home).size());
            }
            assertCandidate(candidates.getFirst(), installed);
        }
    }

    @Test void haltAfterLockSyncLeavesNoSelectedStateAndDoesNotAuthorizeFreshRetry() throws Exception {
        var home = Files.createDirectory(directory.resolve("store"));
        try (var child = start("halt-install", home, Stage.CREATE.name(), Boundary.LOCK_SYNCED.name())) {
            assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
            assertTrue(child.diagnostics().lines().anyMatch("HALT LOCK_SYNCED"::equals), child.diagnostics());
        }
        assertTrue(Files.isRegularFile(home.resolve(".meridiandb-s3-publication.lock")));
        assertTrue(pendingFiles(home).isEmpty());
        assertTrue(selectedFiles(home).isEmpty());
        assertThrows(IOException.class, () -> {
            try (var unexpected = LocalS3PublicationState.open(home, TARGET, BINDING)) { unexpected.state(); }
        });
        assertThrows(IOException.class, () -> {
            try (var unexpected = LocalS3PublicationState.create(home, TARGET, BINDING)) { unexpected.state(); }
        });
    }

    @Test void repeatedPreRenameExitsRetainDistinctCandidatesWithTheSameExactIntent() throws Exception {
        var home = Files.createDirectory(directory.resolve("store"));
        setup(home, Stage.PREPARE_CREATE);
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var child = start("halt-install", home, Stage.PREPARE_CREATE.name(), Boundary.CANDIDATE_SYNCED.name())) {
                assertEquals(HALT_STATUS, child.awaitExit(), child.diagnostics());
            }
            assertReopened(home, before(Stage.PREPARE_CREATE));
        }
        var candidates = pendingFiles(home);
        assertEquals(2, candidates.size());
        assertEquals(2, candidates.stream().map(path -> path.getFileName().toString()).distinct().count());
        candidates.forEach(path -> assertCandidate(path, after(Stage.PREPARE_CREATE)));
        try (var owner = open(home)) {
            assertArrayEquals(S3PublicationStateCodec.encode(after(Stage.PREPARE_CREATE)),
                    S3PublicationStateCodec.encode(owner.prepare(FIRST_IMAGE)));
        }
        assertReopened(home, after(Stage.PREPARE_CREATE));
        assertEquals(candidates, pendingFiles(home));
        candidates.forEach(path -> assertCandidate(path, after(Stage.PREPARE_CREATE)));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anotherJvmOwnsMetadataUntilItsGracefulOrAbruptExit(boolean abrupt) throws Exception {
        var home = Files.createDirectory(directory.resolve("store"));
        setup(home, Stage.PREPARE_CREATE);
        try (var owner = start("hold", home)) {
            owner.awaitOutput("HELD");
            var localFailure = assertThrows(IOException.class, () -> {
                try (var unexpected = LocalS3PublicationState.open(home, TARGET, BINDING)) { unexpected.state(); }
            });
            assertEquals("Publication-state owner is held by another process", localFailure.getMessage());
            try (var contender = start("contend", home)) {
                assertEquals(0, contender.awaitExit(), contender.diagnostics());
                assertTrue(contender.diagnostics().lines().anyMatch("LOCKED"::equals), contender.diagnostics());
            }
            owner.release(abrupt ? 'H' : 'R');
            assertEquals(abrupt ? HALT_STATUS : 0, owner.awaitExit(), owner.diagnostics());
        }
        try (var reopened = open(home)) {
            assertEquals(1, reopened.state().generation());
            var pending = reopened.prepare(FIRST_IMAGE);
            assertEquals(2, pending.generation());
            assertEquals("*", pending.pending().orElseThrow().precondition());
        }
        assertReopened(home, after(Stage.PREPARE_CREATE));
    }

    @Test void rejectedSameJvmOpenCannotReleaseTheOriginalCrossProcessLock() throws Exception {
        var home = Files.createDirectory(directory.resolve("store"));
        setup(home, Stage.PREPARE_CREATE);
        try (var owner = open(home)) {
            var rejected = assertThrows(IOException.class, () -> {
                try (var unexpected = LocalS3PublicationState.open(home, TARGET, BINDING)) { unexpected.state(); }
            });
            assertTrue(rejected.getMessage().startsWith("Path is already owned by a local persistence adapter:"),
                    rejected.getMessage());
            try (var contender = start("contend", home)) {
                assertEquals(0, contender.awaitExit(), contender.diagnostics());
                assertTrue(contender.diagnostics().lines().anyMatch("LOCKED"::equals), contender.diagnostics());
            }
            assertEquals(2, owner.prepare(FIRST_IMAGE).generation());
        }
        assertReopened(home, after(Stage.PREPARE_CREATE));
    }

    @Test void damagedNewestSelectedImageFailsAcrossProcessesWithoutFallingBack() throws Exception {
        var home = Files.createDirectory(directory.resolve("store"));
        setup(home, Stage.PREPARE_REPLACE);
        var newest = before(Stage.PREPARE_REPLACE);
        var newestPath = home.resolve(selectedName(newest));
        var damaged = Files.readAllBytes(newestPath);
        damaged[damaged.length - 1] ^= 1;
        Files.write(newestPath, damaged);
        try (var child = start("reject-corrupt", home)) {
            assertEquals(0, child.awaitExit(), child.diagnostics());
            assertTrue(child.diagnostics().lines().anyMatch("CORRUPT NEWEST REJECTED"::equals), child.diagnostics());
        }
        var failure = assertThrows(S3PublicationStateException.class, () -> {
            try (var unexpected = open(home)) { unexpected.state(); }
        });
        assertEquals(S3PublicationStateException.Code.CORRUPT, failure.code());
        assertArrayEquals(damaged, Files.readAllBytes(newestPath));
        assertSelectedImage(home, after(Stage.CREATE));
        assertSelectedImage(home, after(Stage.PREPARE_CREATE));
        assertEquals(3, selectedFiles(home).size());
    }

    private static S3PublicationState before(Stage stage) {
        return switch (stage) {
            case CREATE -> null;
            case PREPARE_CREATE -> fresh();
            case RECORD_CREATE -> fresh().prepare(FIRST_IMAGE);
            case PREPARE_REPLACE -> ready();
            case RECORD_REPLACE -> ready().prepare(SECOND_IMAGE);
        };
    }

    private static S3PublicationState after(Stage stage) {
        return switch (stage) {
            case CREATE -> fresh();
            case PREPARE_CREATE -> fresh().prepare(FIRST_IMAGE);
            case RECORD_CREATE -> ready();
            case PREPARE_REPLACE -> ready().prepare(SECOND_IMAGE);
            case RECORD_REPLACE -> ready().prepare(SECOND_IMAGE).recordSelected(observation(SECOND_IMAGE, SECOND_TOKEN));
        };
    }

    private static void setup(Path home, Stage stage) throws IOException {
        if (stage == Stage.CREATE) { return; }
        try (var owner = create(home)) {
            if (stage == Stage.PREPARE_CREATE) { return; }
            owner.prepare(FIRST_IMAGE);
            if (stage == Stage.RECORD_CREATE) { return; }
            owner.recordSelected(observation(FIRST_IMAGE, FIRST_TOKEN));
            if (stage == Stage.PREPARE_REPLACE) { return; }
            owner.prepare(SECOND_IMAGE);
        }
    }

    private static LocalS3PublicationState create(Path home) throws IOException {
        try { return LocalS3PublicationState.create(home, TARGET, BINDING); }
        catch (LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force unavailable: " + unsupported);
            throw new AssertionError("Unreachable assumption continuation", unsupported);
        }
    }

    private static LocalS3PublicationState open(Path home) throws IOException {
        try { return LocalS3PublicationState.open(home, TARGET, BINDING); }
        catch (LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force unavailable: " + unsupported);
            throw new AssertionError("Unreachable assumption continuation", unsupported);
        }
    }

    private static void assertReopened(Path home, S3PublicationState expected) throws IOException {
        try (var owner = open(home)) {
            var actual = owner.state();
            assertArrayEquals(S3PublicationStateCodec.encode(expected), S3PublicationStateCodec.encode(actual));
            assertEquals(expected.generation(), actual.generation());
            if (expected.expected().isPresent()) {
                assertEquals(expected.expected().orElseThrow().token(), actual.expected().orElseThrow().token());
                assertArrayEquals(expected.expected().orElseThrow().image(), actual.expected().orElseThrow().image());
            }
            if (expected.pending().isPresent()) {
                assertEquals(expected.pending().orElseThrow().operation(), actual.pending().orElseThrow().operation());
                assertEquals(expected.pending().orElseThrow().precondition(), actual.pending().orElseThrow().precondition());
                assertArrayEquals(expected.pending().orElseThrow().image(), actual.pending().orElseThrow().image());
            }
        }
    }

    private static void assertSelectedImage(Path home, S3PublicationState expected) throws IOException {
        assertArrayEquals(S3PublicationStateCodec.encode(expected), Files.readAllBytes(home.resolve(selectedName(expected))));
    }

    private static void assertCandidate(Path path, S3PublicationState expected) {
        String prefix = "pending-publication-" + expected.generation() + "-";
        String name = path.getFileName().toString();
        assertTrue(name.startsWith(prefix) && name.endsWith(".state"), name);
        String identity = name.substring(prefix.length(), name.length() - ".state".length());
        assertEquals(identity, UUID.fromString(identity).toString());
        try { assertArrayEquals(S3PublicationStateCodec.encode(expected), Files.readAllBytes(path)); }
        catch (IOException failure) { throw new AssertionError("Cannot read retained candidate " + path, failure); }
    }

    private static String selectedName(S3PublicationState state) {
        return String.format(Locale.ROOT, "publication-%019d-%s.state", state.generation(),
                S3PublicationStateCodec.reference(state).id().value());
    }

    private static List<Path> selectedFiles(Path home) throws IOException { return files(home, "publication-"); }
    private static List<Path> pendingFiles(Path home) throws IOException { return files(home, "pending-publication-"); }
    private static List<Path> files(Path home, String prefix) throws IOException {
        try (var paths = Files.list(home)) {
            return paths.filter(path -> path.getFileName().toString().startsWith(prefix)).sorted().toList();
        }
    }

    private Child start(String mode, Path home, String... extra) throws IOException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) { classpath = System.getProperty("java.class.path"); }
        var arguments = new java.util.ArrayList<>(List.of(java.toString(), "-cp", classpath,
                PublicationProcess.class.getName(), mode, home.toAbsolutePath().toString()));
        arguments.addAll(List.of(extra));
        // Parent logs must never make the supposedly fresh metadata directory nonempty.
        var output = directory.resolve("child-" + UUID.randomUUID() + ".log");
        var process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new Child(process, output);
    }

    private static S3PublicationState fresh() { return S3PublicationState.fresh(TARGET, BINDING); }
    private static S3PublicationState ready() {
        return fresh().prepare(FIRST_IMAGE).recordSelected(observation(FIRST_IMAGE, FIRST_TOKEN));
    }
    private static S3HeadObservation observation(byte[] image, String token) {
        return S3HeadObservation.restore(ENDPOINT, "bucket", image, new S3HeadToken(token));
    }
    private static ObjectCheckpointHead head(long revision, long publication, ObjectReference predecessor) {
        return new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, publication), LINEAGE, revision, MANIFEST,
                new CheckpointManifest.JournalPrefix(0, 24, LINEAGE.value()), predecessor);
    }

    private static final class Child implements AutoCloseable {
        private final Process process;
        private final Path output;
        Child(Process process, Path output) { this.process = process; this.output = output; }
        String diagnostics() {
            try { return Files.readString(output, StandardCharsets.UTF_8); }
            catch (IOException failure) { return "Cannot read child output: " + failure; }
        }
        void awaitOutput(String marker) throws InterruptedException {
            long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (diagnostics().lines().anyMatch(marker::equals)) { return; }
                if (!process.isAlive()) {
                    supportedExit(process.exitValue());
                    fail("Child exited before " + marker + ": " + diagnostics());
                }
                Thread.sleep(10);
            }
            fail("Child did not report " + marker + ": " + diagnostics());
        }
        void release(char command) throws IOException {
            var input = process.getOutputStream();
            input.write(command);
            input.flush();
            input.close();
        }
        int awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    () -> "Child did not exit: " + diagnostics());
            int status = process.exitValue();
            supportedExit(status);
            return status;
        }
        private void supportedExit(int status) {
            assumeTrue(status != UNSUPPORTED_STATUS, "Child reports directory-force capability unavailable: " + diagnostics());
        }
        @Override public void close() throws IOException {
            if (process.isAlive()) { process.destroyForcibly(); }
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("Child did not terminate: " + diagnostics());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while terminating publication child", interrupted);
            } finally {
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        }
    }

    /** Only process tests invoke this entry point; it never contacts the configured remote endpoint. */
    public static final class PublicationProcess {
        private PublicationProcess() { }
        public static void main(String[] args) throws Exception {
            var home = Path.of(args[1]);
            try {
                switch (args[0]) {
                    case "halt-install" -> {
                        var stage = Stage.valueOf(args[2]);
                        var boundary = Boundary.valueOf(args[3]);
                        LocalS3PublicationState.PublicationProbe probe = reached -> {
                            if (reached == boundary) {
                                System.out.println("HALT " + reached);
                                System.out.flush();
                                Runtime.getRuntime().halt(HALT_STATUS);
                            }
                        };
                        if (stage == Stage.CREATE) {
                            try (var owner = LocalS3PublicationState.createObserved(home, TARGET, BINDING, probe)) {
                                owner.state();
                            }
                        } else {
                            try (var owner = LocalS3PublicationState.openObserved(home, TARGET, BINDING, probe)) {
                                switch (stage) {
                                    case PREPARE_CREATE -> owner.prepare(FIRST_IMAGE);
                                    case RECORD_CREATE -> owner.recordSelected(observation(FIRST_IMAGE, FIRST_TOKEN));
                                    case PREPARE_REPLACE -> owner.prepare(SECOND_IMAGE);
                                    case RECORD_REPLACE -> owner.recordSelected(observation(SECOND_IMAGE, SECOND_TOKEN));
                                    case CREATE -> throw new AssertionError("Unexpected creation branch");
                                }
                            }
                        }
                        throw new AssertionError("Requested production halt boundary was not reached");
                    }
                    case "hold" -> {
                        try (var owner = LocalS3PublicationState.open(home, TARGET, BINDING)) {
                            owner.state();
                            System.out.println("HELD");
                            System.out.flush();
                            int command = System.in.read();
                            if (command == 'H') { Runtime.getRuntime().halt(HALT_STATUS); }
                            if (command != 'R') { throw new IOException("Parent did not release metadata owner"); }
                        }
                    }
                    case "contend" -> {
                        try (var unexpected = LocalS3PublicationState.open(home, TARGET, BINDING)) {
                            unexpected.state();
                        } catch (IOException locked) {
                            if (!"Publication-state owner is held by another process".equals(locked.getMessage())) { throw locked; }
                            System.out.println("LOCKED");
                            return;
                        }
                        throw new AssertionError("Competing child unexpectedly acquired metadata owner");
                    }
                    case "reject-corrupt" -> {
                        try (var unexpected = LocalS3PublicationState.open(home, TARGET, BINDING)) {
                            unexpected.state();
                        } catch (S3PublicationStateException corrupt) {
                            if (corrupt.code() != S3PublicationStateException.Code.CORRUPT) { throw corrupt; }
                            System.out.println("CORRUPT NEWEST REJECTED");
                            return;
                        }
                        throw new AssertionError("Damaged newest state fell back to an older generation");
                    }
                    default -> throw new IllegalArgumentException("Unknown publication child mode: " + args[0]);
                }
            } catch (LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) {
                System.out.println("DIRECTORY SYNC UNSUPPORTED: " + unsupported);
                System.out.flush();
                System.exit(UNSUPPORTED_STATUS);
            }
        }
    }
}
