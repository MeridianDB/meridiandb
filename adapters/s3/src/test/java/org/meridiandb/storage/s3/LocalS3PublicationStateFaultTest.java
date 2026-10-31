// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.internal.LocalFileOwnership;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;

class LocalS3PublicationStateFaultTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final ObjectId LINEAGE = new ObjectId("01".repeat(32));
    private static final S3PublicationState.Target TARGET = new S3PublicationState.Target(URI.create("http://localhost:9000"), "us-east-1", "bucket");
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, LINEAGE);
    private static final S3PublicationState INITIAL = S3PublicationState.fresh(TARGET, BINDING);
    @TempDir Path directory;

    private static byte[] candidate() {
        return ObjectCheckpointHeadCodec.encode(new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 3),
                LINEAGE, 1, new ObjectReference(new ObjectId("02".repeat(32)), 100),
                new JournalPrefix(0, 30, LINEAGE.value()), null));
    }

    @Test void exactWriteMoveForceOrderPublishesOnlyAfterLastBarrier() throws IOException {
        var ns = new MemoryNamespace();
        var events = new ArrayList<String>();
        try (var store = new LocalS3PublicationState(ns, INITIAL, boundary -> events.add(boundary.name()))) {
            var pending = store.prepare(candidate());
            assertEquals(2, pending.generation());
            assertSame(pending, store.state());
            assertEquals(List.of("entries", "read", "write/force/close", "move", "directory-force"), ns.events);
            assertEquals(List.of("CANDIDATE_SYNCED", "STATE_RENAMED", "DIRECTORY_SYNCED"), events);
            assertEquals(2, ns.files.size());
            assertArrayEquals(S3PublicationStateCodec.encode(pending), ns.latest());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"entries", "read", "write-before", "write-after", "move-before", "move-after", "directory-force"})
    void storageFailurePoisonsWithoutDeletingOldOrFailedEvidence(String stage) throws IOException {
        var ns = new MemoryNamespace(); ns.fail = stage;
        try (var store = new LocalS3PublicationState(ns, INITIAL, null)) {
            var failure = assertThrows(IOException.class, () -> store.prepare(candidate()));
            boolean renameAttempted = stage.startsWith("move") || stage.equals("directory-force");
            assertEquals(renameAttempted, failure instanceof LocalS3PublicationState.UncertainInstallationException);
            assertThrows(IOException.class, store::state);
            assertThrows(IOException.class, () -> store.prepare(candidate()));
            assertTrue(ns.files.containsKey(name(INITIAL)));
            assertArrayEquals(S3PublicationStateCodec.encode(INITIAL), ns.files.get(name(INITIAL)));
            if (stage.equals("write-after") || stage.equals("move-before")) {
                var failed = ns.files.entrySet().stream().filter(entry -> entry.getKey().startsWith("pending-")).findFirst().orElseThrow();
                assertArrayEquals(candidate(), S3PublicationStateCodec.decode(failed.getValue()).state().pending().orElseThrow().image());
            }
            if (stage.equals("move-after") || stage.equals("directory-force")) {
                assertArrayEquals(candidate(), S3PublicationStateCodec.decode(ns.latest()).state().pending().orElseThrow().image());
            }
        }
        assertTrue(ns.closed);
    }

    @Test void invalidPurePreparationLeavesOwnerUsableWithoutStorageEffects() throws IOException {
        var ns = new MemoryNamespace();
        try (var store = new LocalS3PublicationState(ns, INITIAL, null)) {
            assertThrows(IllegalArgumentException.class, () -> store.prepare(new byte[1]));
            assertSame(INITIAL, store.state()); assertTrue(ns.events.isEmpty());
            store.prepare(candidate()); ns.events.clear();
            assertThrows(S3PublicationStateException.class, () -> store.prepare(candidate()));
            assertTrue(ns.events.isEmpty()); assertTrue(store.state().pending().isPresent());
        }
    }

    @Test void reentrantProbeCannotReadChangeOrCloseTheOperatingOwner() throws IOException {
        var ns = new MemoryNamespace();
        var holder = new LocalS3PublicationState[1];
        try (var store = new LocalS3PublicationState(ns, INITIAL, boundary -> {
            assertThrows(IOException.class, holder[0]::state);
            assertThrows(IOException.class, holder[0]::close);
            assertThrows(IOException.class, () -> holder[0].prepare(candidate()));
        })) { holder[0] = store; store.prepare(candidate()); assertEquals(2, store.state().generation()); }
    }

    @Test void runtimeAfterRenameIsUncertainAndErrorBeforeRenamePoisons() throws IOException {
        var ns = new MemoryNamespace();
        try (var store = new LocalS3PublicationState(ns, INITIAL, boundary -> {
            if (boundary == LocalS3PublicationState.Boundary.STATE_RENAMED) { throw new IllegalStateException("after effect"); }
        })) {
            var uncertain = assertThrows(LocalS3PublicationState.UncertainInstallationException.class, () -> store.prepare(candidate()));
            assertInstanceOf(IllegalStateException.class, uncertain.getCause()); assertThrows(IOException.class, store::state);
        }
        var other = new MemoryNamespace();
        try (var store = new LocalS3PublicationState(other, INITIAL, boundary -> { throw new AssertionError("probe"); })) {
            assertThrows(AssertionError.class, () -> store.prepare(candidate())); assertThrows(IOException.class, store::state);
            assertEquals(2, other.files.size());
        }
    }

    @Test void staleOrDamagedSelectedStateCannotBeOverwritten() throws IOException {
        var ns = new MemoryNamespace();
        ns.files.put(name(INITIAL), new byte[10]);
        try (var store = new LocalS3PublicationState(ns, INITIAL, null)) {
            assertThrows(S3PublicationStateException.class, () -> store.prepare(candidate()));
            assertThrows(IOException.class, store::state); assertFalse(ns.events.contains("write/force/close"));
        }
        var newer = new MemoryNamespace(); var actual = INITIAL.prepare(candidate());
        newer.files.put(name(actual), S3PublicationStateCodec.encode(actual));
        try (var store = new LocalS3PublicationState(newer, INITIAL, null)) {
            assertThrows(IOException.class, () -> store.prepare(candidate()));
            assertFalse(newer.events.contains("write/force/close"));
        }
    }

    @Test void directoryAndGenerationCapacityFailBeforeCandidateEffects() throws IOException {
        var full = new MemoryNamespace();
        for (int i = 1; i < LocalS3PublicationState.MAX_DIRECTORY_ENTRIES; i++) { full.files.put("unknown-" + i, new byte[0]); }
        try (var store = new LocalS3PublicationState(full, INITIAL, null)) {
            assertThrows(IOException.class, () -> store.prepare(candidate())); assertFalse(full.events.contains("write/force/close"));
        }
        var generations = new MemoryNamespace();
        S3PublicationState latest = INITIAL;
        for (int i = 2; i <= LocalS3PublicationState.MAX_GENERATIONS; i++) {
            latest = S3PublicationState.restore(i, TARGET, BINDING, null, null);
            generations.files.put(name(latest), S3PublicationStateCodec.encode(latest));
        }
        try (var store = new LocalS3PublicationState(generations, latest, null)) {
            assertThrows(IOException.class, () -> store.prepare(candidate())); assertFalse(generations.events.contains("write/force/close"));
        }
    }

    @Test void closeInvalidatesAccessAndAttemptsCleanupAgainAfterFailure() throws IOException {
        var ns = new MemoryNamespace(); ns.closeFailures = 1;
        var store = new LocalS3PublicationState(ns, INITIAL, null);
        assertThrows(IOException.class, store::close);
        assertThrows(IOException.class, store::state);
        store.close(); store.close(); assertEquals(2, ns.closes);
    }

    @Test void readCapsBeforeAllocationAndNeverRequestsPastPayloadPlusOneEofByte() throws IOException {
        var file = new MemoryFile(new byte[35]); file.chunk = 3;
        assertArrayEquals(new byte[35], LocalS3PublicationState.read(file));
        assertTrue(file.closed); assertEquals(36, file.totalRead); assertEquals(35, file.lastReadOffset);
        var huge = new MemoryFile(new byte[0]); huge.declaredSize = 4097;
        assertThrows(IOException.class, () -> LocalS3PublicationState.read(huge)); assertTrue(huge.closed); assertEquals(0, huge.reads);
    }

    @ParameterizedTest @ValueSource(strings = {"zero", "negative", "mismatch", "shrink", "grow", "size-change"})
    void readRejectsBadProgressAndChangedImagesAndAlwaysCloses(String fault) {
        var file = new MemoryFile(new byte[20]); file.fault = fault;
        assertThrows(IOException.class, () -> LocalS3PublicationState.read(file)); assertTrue(file.closed);
        if (fault.equals("zero")) { assertEquals(16, file.reads); }
    }

    @Test void shortWritesCompleteAndForceBeforeSuccessfulClose() throws IOException {
        var file = new MemoryFile(new byte[0]); file.chunk = 3;
        byte[] image = new byte[35]; Arrays.fill(image, (byte) 7);
        LocalS3PublicationState.write(file, image);
        assertArrayEquals(image, file.data); assertEquals(List.of("force", "close"), file.events);
    }

    @ParameterizedTest @ValueSource(strings = {"zero", "negative", "mismatch", "size-change", "force"})
    void writeFailuresAlwaysCloseAndNeverClaimSuccess(String fault) {
        var file = new MemoryFile(new byte[0]); file.fault = fault;
        assertThrows(IOException.class, () -> LocalS3PublicationState.write(file, new byte[20])); assertTrue(file.closed);
        if (fault.equals("zero")) { assertEquals(16, file.writes); }
    }

    @Test void fileCloseFailureIsPrimaryOrSuppressedWithoutSuccessfulCompletion() {
        var file = new MemoryFile(new byte[0]); file.closeFailure = true;
        assertThrows(IOException.class, () -> LocalS3PublicationState.write(file, new byte[10]));
        var failed = new MemoryFile(new byte[0]); failed.closeFailure = true; failed.fault = "force";
        var failure = assertThrows(IOException.class, () -> LocalS3PublicationState.write(failed, new byte[10]));
        assertEquals("force", failure.getMessage()); assertEquals(1, failure.getSuppressed().length);
    }

    @Test void unsupportedDirectoryProbePrecedesEveryPathMutation() throws IOException {
        assertThrows(LocalS3PublicationState.DirectorySyncUnsupportedException.class, () ->
                LocalS3PublicationState.acquire(directory, TARGET, BINDING, true, null, channel -> { throw new UnsupportedOperationException("unsupported"); }));
        try (var entries = Files.list(directory)) { assertEquals(0, entries.count()); }
    }

    @Test void ordinaryDirectoryIoFailureIsNotConvertedIntoUnsupportedCapability() throws IOException {
        try (var owner = supportedOwner()) { assertEquals(1, owner.state().generation()); }
        var failure = new IOException("ordinary directory I/O");
        assertSame(failure, assertThrows(IOException.class, () -> LocalS3PublicationState.acquire(directory,
                TARGET, BINDING, false, null, channel -> { throw failure; })));
    }

    private LocalS3PublicationState supportedOwner() throws IOException {
        try { return LocalS3PublicationState.create(directory, TARGET, BINDING); }
        catch (LocalS3PublicationState.DirectorySyncUnsupportedException unavailable) { assumeTrue(false, unavailable.getMessage()); throw unavailable; }
    }

    @Test void uncertainNativeControlCleanupRemainsQuarantinedAfterJavaChannelClosed() throws Exception {
        Path home = Files.createDirectory(directory.resolve("store"));
        Path log = directory.resolve("native-cleanup.log");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                NativeCleanupChild.class.getName(), home.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Native cleanup child timed out");
            String output = Files.readString(log);
            assumeTrue(child.exitValue() != 77, "Explicit unsupported directory placement: " + output);
            assertEquals(0, child.exitValue(), output); assertTrue(output.contains("QUARANTINED"), output);
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); }
        }
        // The quarantined reservation lasts until child process exit; parent test cleanup follows it.
    }

    public static final class NativeCleanupChild {
        private NativeCleanupChild() { }
        public static void main(String[] args) throws IOException {
            LocalS3PublicationState.NioNamespace ns;
            try { ns = LocalS3PublicationState.NioNamespace.acquire(Path.of(args[0]), true, channel -> channel.force(true)); }
            catch (LocalS3PublicationState.DirectorySyncUnsupportedException unavailable) { System.out.println(unavailable); System.exit(77); return; }
            ns.owned.channel().close(); // Wrapper never observed successful native cleanup.
            for (int i = 0; i < 2; i++) {
                try { ns.close(); throw new AssertionError("Uncertain cleanup was accepted"); }
                catch (IOException expected) { /* The reservation must remain quarantined. */ }
            }
            try {
                LocalFileOwnership.openControl(Path.of(args[0]).resolve(LocalS3PublicationState.LOCK), StandardOpenOption.WRITE);
                throw new AssertionError("Quarantined ownership was reopened");
            } catch (IOException expected) { System.out.println("QUARANTINED"); }
        }
    }

    private static String name(S3PublicationState state) {
        return String.format(Locale.ROOT, "publication-%019d-%s.state", state.generation(), S3PublicationStateCodec.reference(state).id().value());
    }
    private static final class MemoryNamespace implements LocalS3PublicationState.Namespace {
        final LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        final ArrayList<String> events = new ArrayList<>(); String fail = ""; boolean closed; int closes; int closeFailures;
        MemoryNamespace() { files.put(name(INITIAL), S3PublicationStateCodec.encode(INITIAL)); }
        void fail(String stage) throws IOException { if (fail.equals(stage)) { throw new IOException(stage); } }
        @Override public List<String> entries() throws IOException { events.add("entries"); fail("entries"); return List.copyOf(files.keySet()); }
        @Override public byte[] read(String name) throws IOException { events.add("read"); fail("read"); return files.get(name).clone(); }
        @Override public void writeNew(String name, byte[] image) throws IOException {
            events.add("write/force/close"); fail("write-before"); assertFalse(files.containsKey(name)); files.put(name, image.clone()); fail("write-after");
        }
        @Override public void forceFile(String name) { events.add("file-force"); }
        @Override public void atomicMove(String from, String to) throws IOException { events.add("move"); fail("move-before"); files.put(to, files.remove(from)); fail("move-after"); }
        @Override public void forceDirectory() throws IOException { events.add("directory-force"); fail("directory-force"); }
        @Override public void close() throws IOException { closes++; if (closeFailures-- > 0) { throw new IOException("cleanup"); } closed = true; }
        byte[] latest() { return files.entrySet().stream().filter(e -> e.getKey().startsWith("publication-")).reduce((a, b) -> b).orElseThrow().getValue(); }
    }
    private static final class MemoryFile implements LocalS3PublicationState.FileAccess {
        byte[] data; int chunk = Integer.MAX_VALUE; String fault = ""; boolean closed; boolean closeFailure;
        long declaredSize = -1; int reads; int writes; int totalRead; long lastReadOffset; int sizes; final List<String> events = new ArrayList<>();
        MemoryFile(byte[] data) { this.data = data; }
        @Override public long size() { sizes++; if (declaredSize >= 0) { return declaredSize; } return fault.equals("size-change") && sizes > 1 ? data.length + 1L : data.length; }
        @Override public int read(ByteBuffer buffer, long offset) {
            reads++; lastReadOffset = offset; totalRead += buffer.remaining();
            if (fault.equals("zero")) { return 0; }
            if (fault.equals("negative")) { return -2; }
            if (fault.equals("mismatch")) { return 1; }
            if (fault.equals("shrink")) { return -1; }
            if (offset == data.length) { if (fault.equals("grow")) { buffer.put((byte) 1); return 1; } return -1; }
            int count = Math.min(Math.min(chunk, buffer.remaining()), data.length - (int) offset);
            // Count actual requested windows in the short-read case separately below.
            totalRead -= buffer.remaining(); totalRead += count;
            buffer.put(data, (int) offset, count); return count;
        }
        @Override public int write(ByteBuffer buffer, long offset) {
            writes++; if (fault.equals("zero")) { return 0; } if (fault.equals("negative")) { return -1; } if (fault.equals("mismatch")) { return 1; }
            int count = Math.min(chunk, buffer.remaining()); data = Arrays.copyOf(data, (int) offset + count); buffer.get(data, (int) offset, count); return count;
        }
        @Override public void force() throws IOException { events.add("force"); if (fault.equals("force")) { throw new IOException("force"); } }
        @Override public void close() throws IOException { events.add("close"); closed = true; if (closeFailure) { throw new IOException("close"); } }
    }
}
