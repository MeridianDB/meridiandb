// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalFormatException;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class LocalInitializationTest {
    private static final DatabaseId DATABASE = new DatabaseId("initialized-db");
    private static final JournalRecord FIRST = new JournalRecord(1,
            new Transaction(new RequestId("first"), List.of(new WriteOperation.Put(
                    new EntityId("salary"), new ValidTimeInterval(null, null), 100))));

    @TempDir
    Path directory;

    @Test
    void realInitializationReturnsOwnedJournalWithSelectedGenesisAndSynchronizesItsTail() throws IOException {
        try (var store = supportedStore(); var journal = store.initialize(DATABASE)) {
            assertEquals(DATABASE, journal.databaseId());
            assertEquals(0, journal.appendedSequence());
            assertEquals(0, journal.forcedSequence());
            assertEquals(journal.appendedBytes(), journal.forcedBytes());
            try (var selected = store.readLatest(journal).orElseThrow()) {
                assertEquals(DATABASE, selected.manifest().databaseId());
                assertEquals(1, selected.manifest().generation());
                assertEquals(0, selected.manifest().journalPrefix().sequence());
                assertEquals(journal.prefix(0).sha256(), selected.manifest().journalPrefix().sha256());
                assertEquals(List.of(), selected.manifest().segments());
            }
            assertThrows(IOException.class, () -> LocalJournal.open(directory.resolve("transactions.journal")));
            journal.append(FIRST);
            assertEquals(0, journal.forcedSequence());
            store.synchronizeJournal(journal);
            assertEquals(1, journal.forcedSequence());
            assertEquals(journal.appendedBytes(), journal.forcedBytes());
            try (var selected = store.readLatest(journal).orElseThrow()) {
                assertEquals(0, selected.manifest().journalPrefix().sequence());
            }
        }
        // Inspect the raw journal only after releasing its writer lock.
        byte[] actual = Files.readAllBytes(directory.resolve("transactions.journal"));
        assertArrayEquals(concat(JournalCodec.encodeHeader(DATABASE), JournalCodec.encodeRecord(FIRST)), actual);
        try (var store = LocalCheckpointStore.open(directory);
                var journal = LocalJournal.open(store.journalPath());
                var selected = store.readLatest(journal).orElseThrow()) {
            assertEquals(1, selected.manifest().generation());
            assertEquals(0, selected.manifest().journalPrefix().sequence());
            assertEquals(1, journal.appendedSequence());
        }
    }

    @Test
    void invalidMetadataCreatesNoCandidateAndDoesNotPoisonInitialization() throws IOException {
        var namespace = new TestNamespace(directory);
        try (var store = new LocalCheckpointStore(namespace)) {
            assertThrows(NullPointerException.class, () -> store.initialize(null));
            var oversized = new DatabaseId("x".repeat(JournalCodec.MAX_ID_CODE_UNITS + 1));
            var failure = assertThrows(JournalFormatException.class, () -> store.initialize(oversized));
            assertEquals(JournalFormatException.Code.LIMIT_EXCEEDED, failure.code());
            assertFalse(Files.exists(directory.resolve("transactions.journal")));
            assertEquals(Map.of(), namespace.files);
            assertEquals(List.of(), namespace.events);
            try (var journal = store.initialize(DATABASE)) {
                assertEquals(0, journal.appendedSequence());
                assertTrue(store.readLatest(journal).isPresent());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"notes.txt", "pending-1-candidate.manifest", "transactions.journal", ".unrecognized"})
    void initializationRejectsEveryExistingEntryExceptItsOwnOwnershipFile(String existing) throws IOException {
        var namespace = new TestNamespace(directory);
        byte[] preserved = {9, 3, 7};
        namespace.files.put(existing, preserved.clone());
        try (var store = new LocalCheckpointStore(namespace)) {
            assertThrows(IOException.class, () -> store.initialize(DATABASE));
            assertArrayEquals(preserved, namespace.files.get(existing));
            assertFalse(Files.exists(directory.resolve("transactions.journal")));
            assertEquals(1, namespace.files.size());
            assertPoisoned(store, namespace);
        }
    }

    @Test
    void injectedNamespaceMayContainOnlyTheOwnershipFileAtInitialization() throws IOException {
        var namespace = new TestNamespace(directory);
        namespace.files.put(".meridiandb-checkpoints.lock", new byte[] {6, 2});
        try (var store = new LocalCheckpointStore(namespace); var journal = store.initialize(DATABASE)) {
            assertArrayEquals(new byte[] {6, 2}, namespace.files.get(".meridiandb-checkpoints.lock"));
            try (var selected = store.readLatest(journal).orElseThrow()) {
                assertEquals(1, selected.manifest().generation());
                assertEquals(0, selected.manifest().journalPrefix().sequence());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"write-io", "write-runtime", "write-error", "move", "directory-before", "directory-after"})
    void initializationFailureRetainsEvidenceClosesTheCreatedJournalAndPoisonsTheStore(String phase) throws IOException {
        var namespace = new TestNamespace(directory);
        namespace.failurePhase = phase;
        Class<? extends Throwable> expected = phase.endsWith("runtime") ? IllegalStateException.class
                : phase.endsWith("error") ? AssertionError.class : IOException.class;
        try (var store = new LocalCheckpointStore(namespace)) {
            var failure = assertThrows(expected, () -> store.initialize(DATABASE));
            if (phase.equals("write-runtime") || phase.equals("write-error")) {
                assertSame(namespace.injectedFailure, failure);
            }
            var journalPath = directory.resolve("transactions.journal");
            assertArrayEquals(JournalCodec.encodeHeader(DATABASE), Files.readAllBytes(journalPath));
            try (var released = LocalJournal.open(journalPath)) {
                assertEquals(DATABASE, released.databaseId());
                assertEquals(0, released.appendedSequence());
            }
            long candidates = namespace.files.keySet().stream().filter(name -> name.startsWith("pending-")).count();
            long selected = namespace.files.keySet().stream().filter(name -> name.startsWith("checkpoint-")).count();
            assertEquals(phase.startsWith("write-") || phase.equals("move") ? 1 : 0, candidates);
            assertEquals(phase.equals("directory-after") ? 1 : 0, selected);
            assertPoisoned(store, namespace);
        }
    }

    @Test
    void synchronizationForcesTheJournalBeforeTheDirectoryAndLeavesTheCheckpointAtGenesis() throws IOException {
        var namespace = new TestNamespace(directory);
        try (var store = new LocalCheckpointStore(namespace); var journal = store.initialize(DATABASE)) {
            journal.append(FIRST);
            namespace.events.clear();
            namespace.directoryObserver = () -> {
                assertEquals(1, journal.forcedSequence(), "Directory synchronization follows the successful owner force");
                assertEquals(journal.appendedBytes(), journal.forcedBytes());
            };
            store.synchronizeJournal(journal);
            assertEquals(List.of("directory-force"), namespace.events);
            namespace.directoryObserver = () -> {};
            try (var selected = store.readLatest(journal).orElseThrow()) {
                assertEquals(0, selected.manifest().journalPrefix().sequence());
            }
        }
    }

    @Test
    void synchronizationRejectsAnOwnerBoundToAnotherPathBeforeForcingIt() throws IOException {
        var namespace = new TestNamespace(directory);
        try (var store = new LocalCheckpointStore(namespace);
                var foreign = LocalJournal.create(directory.resolve("other.journal"), DATABASE)) {
            foreign.append(FIRST);
            assertThrows(IOException.class, () -> store.synchronizeJournal(foreign));
            assertEquals(0, foreign.forcedSequence());
            assertEquals(List.of(), namespace.events);
        }
    }

    @Test
    void failedJournalForcePreventsDirectoryForceAndPoisonsSynchronization() throws IOException {
        var namespace = new TestNamespace(directory);
        try (var store = new LocalCheckpointStore(namespace)) {
            var journal = LocalJournal.create(directory.resolve("transactions.journal"), DATABASE);
            journal.close();
            assertThrows(IOException.class, () -> store.synchronizeJournal(journal));
            assertEquals(List.of(), namespace.events);
            assertPoisoned(store, namespace);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime", "error"})
    void directorySynchronizationFailurePoisonsTheStoreAfterTheCompletedJournalForce(String kind) throws IOException {
        var namespace = new TestNamespace(directory);
        try (var store = new LocalCheckpointStore(namespace); var journal = store.initialize(DATABASE)) {
            journal.append(FIRST);
            namespace.injectedFailure = switch (kind) {
                case "io" -> new IOException("Injected directory failure");
                case "runtime" -> new IllegalStateException("Injected directory failure");
                case "error" -> new AssertionError("Injected directory failure");
                default -> throw new AssertionError(kind);
            };
            namespace.failNextDirectory = true;
            var failure = assertThrows(namespace.injectedFailure.getClass(), () -> store.synchronizeJournal(journal));
            assertSame(namespace.injectedFailure, failure);
            assertEquals(1, journal.forcedSequence());
            assertEquals(journal.appendedBytes(), journal.forcedBytes());
            assertPoisoned(store, namespace);
        }
    }

    @Test
    void closedStoreRejectsInitializationAndSynchronizationWithoutCreatingAPath() throws IOException {
        var namespace = new TestNamespace(directory);
        var store = new LocalCheckpointStore(namespace);
        store.close();
        assertThrows(IOException.class, () -> store.initialize(DATABASE));
        assertFalse(Files.exists(directory.resolve("transactions.journal")));
        try (var journal = LocalJournal.create(directory.resolve("transactions.journal"), DATABASE)) {
            journal.append(FIRST);
            assertThrows(IOException.class, () -> store.synchronizeJournal(journal));
            assertEquals(0, journal.forcedSequence());
        }
    }

    private LocalCheckpointStore supportedStore() throws IOException {
        try {
            return LocalCheckpointStore.open(directory);
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force is unavailable: " + unsupported);
            throw new AssertionError("Unreachable after failed assumption", unsupported);
        }
    }

    private static void assertPoisoned(LocalCheckpointStore store, TestNamespace namespace) {
        int events = namespace.events.size();
        assertThrows(IOException.class, store::journalPath);
        assertThrows(IOException.class, () -> store.initialize(DATABASE));
        assertEquals(events, namespace.events.size());
    }

    private static byte[] concat(byte[] first, byte[] second) {
        var result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** Real journal ownership with deterministic metadata/namespace boundaries on every platform. */
    private static final class TestNamespace implements LocalCheckpointStore.Namespace {
        private final Path directory;
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        String failurePhase = "";
        Throwable injectedFailure;
        int directoryForces;
        boolean failNextDirectory;
        Runnable directoryObserver = () -> {};

        TestNamespace(Path directory) throws IOException { this.directory = directory.toRealPath(); }
        @Override public Path directory() { return directory; }
        @Override public LocalReaderBarrier.Protection acquireReader() {
            return new LocalReaderBarrier.Protection() {
                @Override public void checkValid() { }
                @Override public void close() { }
            };
        }
        @Override public java.util.Optional<LocalReaderBarrier.Protection> tryReclamationBarrier() {
            return java.util.Optional.of(acquireReader());
        }

        @Override public List<String> entries() throws IOException {
            var entries = new LinkedHashSet<>(files.keySet());
            try (var actual = Files.list(directory)) {
                actual.forEach(path -> entries.add(path.getFileName().toString()));
            }
            return List.copyOf(entries);
        }

        @Override public void checkObjectAliases(String name, List<String> entries) { }
        @Override public byte[] read(String name, int maxBytes) throws IOException {
            var value = files.get(name);
            if (value == null || value.length > maxBytes) { throw new IOException("Missing or oversized object"); }
            return value.clone();
        }

        @Override public void writeNew(String name, byte[] image) throws IOException {
            events.add("write:" + name);
            if (files.containsKey(name)) { throw new IOException("Existing candidate"); }
            files.put(name, image.clone());
            if (failurePhase.startsWith("write-")) {
                files.put(name, Arrays.copyOf(image, image.length / 2));
                injectedFailure = switch (failurePhase) {
                    case "write-io" -> new IOException("Injected candidate write failure");
                    case "write-runtime" -> new IllegalStateException("Injected candidate write failure");
                    case "write-error" -> new AssertionError("Injected candidate write failure");
                    default -> throw new AssertionError(failurePhase);
                };
                throwInjected();
            }
        }

        @Override public void forceFile(String name) throws IOException {
            events.add("file-force:" + name);
            if (!files.containsKey(name)) { throw new IOException("Missing dependency"); }
        }

        @Override public void atomicMove(String from, String to) throws IOException {
            events.add("move:" + from + "->" + to);
            if (failurePhase.equals("move")) { throw new IOException("Injected move failure"); }
            if (!files.containsKey(from) || files.containsKey(to)) { throw new IOException("Invalid move"); }
            files.put(to, files.remove(from));
        }

        @Override public void forceDirectory() throws IOException {
            events.add("directory-force");
            directoryForces++;
            directoryObserver.run();
            if (failurePhase.equals("directory-before") && directoryForces == 1
                    || failurePhase.equals("directory-after") && directoryForces == 2) {
                throw new IOException("Injected initialization directory force failure");
            }
            if (failNextDirectory) { throwInjected(); }
        }

        private void throwInjected() throws IOException {
            if (injectedFailure instanceof IOException io) { throw io; }
            if (injectedFailure instanceof RuntimeException runtime) { throw runtime; }
            if (injectedFailure instanceof Error error) { throw error; }
            throw new AssertionError("No injected failure configured");
        }

        @Override public void close() { events.add("close"); }
    }
}
