// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.CheckpointManifest.SegmentReference;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class LocalCheckpointStoreTest {
    private static final DatabaseId DATABASE = new DatabaseId("checkpoint-db");
    private static final List<JournalRecord> RECORDS = List.of(record(1, "initial", 100), record(2, "correction", 120));
    private static final ImmutableSegment SEGMENT = new ImmutableSegment(DATABASE, RECORDS);
    private static final byte[] SEGMENT_IMAGE = SegmentCodec.encode(SEGMENT);
    private static final SegmentId SEGMENT_ID = new SegmentId(hash(SEGMENT_IMAGE));

    @TempDir
    Path directory;

    @Test
    void realPublicationReopensExactPrefixAndSelectsHighestGeneration() throws IOException {
        ManifestId latestId;
        try (var store = supportedStore(); var journal = LocalJournal.create(store.journalPath(), DATABASE)) {
            assertTrue(store.readLatest(journal).isEmpty());
            for (var record : RECORDS) { journal.append(record); }
            var id = store.writeSegment(SEGMENT);
            assertEquals(SEGMENT_ID, id);
            var first = manifest(1, journal.prefix(2), SEGMENT);
            store.publish(first, journal);
            var second = manifest(2, journal.prefix(2), SEGMENT);
            latestId = store.publish(second, journal);
            assertEquals(2, journal.forcedSequence());
            try (var reader = store.readLatest(journal).orElseThrow(); var child = reader.openSegment(0)) {
                assertEquals(second, reader.manifest());
                assertEquals(latestId, reader.manifestId());
                assertEquals(RECORDS, child.segment().records());
            }
        }
        try (var store = LocalCheckpointStore.open(directory); var journal = LocalJournal.open(store.journalPath());
             var reader = store.readLatest(journal).orElseThrow()) {
            assertEquals(latestId, reader.manifestId());
            assertEquals(2, reader.manifest().generation());
            assertArrayEquals(SEGMENT_IMAGE, Files.readAllBytes(directory.resolve(LocalCheckpointStore.segmentName(SEGMENT_ID))));
        }
    }

    @Test
    void oldCheckpointAndChildReadersSurviveNewGenerationStoreCloseAndReaderClose() throws IOException {
        var store = supportedStore();
        LocalCheckpointReader old = null;
        org.meridiandb.storage.segment.LocalSegmentReader child = null;
        try {
            try (var journal = LocalJournal.create(store.journalPath(), DATABASE)) {
                journal.append(RECORDS.getFirst());
                var firstSegment = new ImmutableSegment(DATABASE, List.of(RECORDS.getFirst()));
                store.writeSegment(firstSegment);
                store.publish(manifest(1, journal.prefix(1), firstSegment), journal);
                old = store.readLatest(journal).orElseThrow();
                journal.append(RECORDS.get(1));
                store.writeSegment(SEGMENT);
                store.publish(manifest(2, journal.prefix(2), SEGMENT), journal);
            }
            store.close();
            assertEquals(1, old.manifest().generation());
            child = old.openSegment(0);
            var metadata = old.manifest();
            var id = old.manifestId();
            old.close();
            var closedReader = old;
            assertThrows(IllegalStateException.class, closedReader::manifest);
            assertThrows(IllegalStateException.class, closedReader::manifestId);
            assertThrows(IllegalStateException.class, () -> closedReader.openSegment(0));
            assertEquals(1, metadata.generation());
            assertEquals(ManifestCodec.decode(ManifestCodec.encode(metadata)).id(), id);
            assertEquals(List.of(RECORDS.getFirst()), child.segment().records());
            old.close();
        } finally {
            if (child != null) { child.close(); }
            if (old != null) { old.close(); }
            store.close();
        }
    }

    @Test
    void publicJournalBindingRejectsAnotherPathEvenWithIdenticalLineageAndHistory() throws IOException {
        try (var store = supportedStore(); var owner = LocalJournal.create(store.journalPath(), DATABASE);
             var other = LocalJournal.create(directory.resolve("other.journal"), DATABASE)) {
            for (var record : RECORDS) { owner.append(record); other.append(record); }
            store.writeSegment(SEGMENT);
            var manifest = manifest(1, owner.prefix(2), SEGMENT);
            assertThrows(IOException.class, () -> store.publish(manifest, other));
            assertThrows(IOException.class, () -> store.readLatest(other));
            store.publish(manifest, owner);
        }
    }

    @Test
    void realStoredJournalMismatchRejectsAnOtherwiseConsistentCheckpoint() throws IOException {
        try (var store = supportedStore(); var journal = LocalJournal.create(store.journalPath(), DATABASE)) {
            journal.append(record(1, "initial", 999));
            journal.append(RECORDS.get(1));
            store.writeSegment(SEGMENT);
            var declared = fixture().manifest(1);
            assertThrows(IOException.class, () -> store.publish(declared, journal));
            assertNoSelectedFiles();
            assertThrows(IOException.class, store::journalPath);
        }
    }

    @Test
    void missingRegularFileAndSymlinkDirectoryInputsAreRejected() throws IOException {
        var missing = directory.resolve("missing");
        assertThrows(IOException.class, () -> LocalCheckpointStore.open(missing));
        assertFalse(Files.exists(missing));
        var file = directory.resolve("ordinary-file");
        Files.write(file, new byte[] {1});
        assertThrows(IOException.class, () -> LocalCheckpointStore.open(file));
        var alias = directory.resolve("alias");
        var target = Files.createDirectory(directory.resolve("target"));
        try { Files.createSymbolicLink(alias, target.getFileName()); }
        catch (UnsupportedOperationException | FileSystemException unsupported) {
            assumeTrue(false, "Symbolic links unavailable: " + unsupported);
        }
        assertThrows(IOException.class, () -> LocalCheckpointStore.open(alias));
    }

    @Test
    void unsupportedAcquisitionProbeCreatesNoLockOrCandidateAndClosesItsDirectoryChannel() throws IOException {
        var sentinel = directory.resolve("existing.bin");
        byte[] original = {9, 4, 2};
        Files.write(sentinel, original);
        var channel = new java.util.concurrent.atomic.AtomicReference<java.nio.channels.FileChannel>();
        assertThrows(LocalCheckpointStore.DirectorySyncUnsupportedException.class,
                () -> LocalCheckpointStore.open(directory, opened -> {
                    channel.set(opened);
                    throw new IOException("Injected unsupported directory sync");
                }));
        assertArrayEquals(original, Files.readAllBytes(sentinel));
        try (var names = Files.list(directory)) {
            assertEquals(List.of("existing.bin"), names.map(path -> path.getFileName().toString()).toList());
        }
        // On a platform that cannot open directory channels, the earlier acquisition
        // boundary rejects before invoking the probe. Otherwise its channel is closed.
        assertTrue(channel.get() == null || !channel.get().isOpen());
    }

    @Test
    void publicationOrdersVerifiedDependenciesBeforePendingRenameAndDirectoryCommit() throws IOException {
        var fixture = fixture();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            var result = store.publish(fixture.manifest(1), fixture.journal);
            String selected = LocalCheckpointStore.generationName(1, result);
            var events = fixture.namespace.events;
            int prefix = events.indexOf("journal-prefix:2");
            int journalForce = events.indexOf("journal-force");
            int segmentForce = events.indexOf("force:" + LocalCheckpointStore.segmentName(SEGMENT_ID));
            int dependencySync = events.indexOf("directory-force");
            int pendingWrite = indexStarting(events, "write:pending-");
            int move = indexStarting(events, "move:");
            int publicationSync = events.lastIndexOf("directory-force");
            assertTrue(prefix >= 0 && prefix < journalForce);
            assertTrue(journalForce < segmentForce && segmentForce < dependencySync);
            assertTrue(dependencySync < pendingWrite && pendingWrite < move && move < publicationSync);
            assertTrue(fixture.namespace.files.containsKey(selected));
            assertFalse(fixture.namespace.files.keySet().stream().anyMatch(name -> name.startsWith("pending-")));
            assertArrayEquals(ManifestCodec.encode(fixture.manifest(1)), fixture.namespace.files.get(selected));
        }
    }

    @Test
    void pendingAndUnrelatedFilesAreIgnoredWithoutReadingOrPromotingThem() throws IOException {
        var fixture = fixture();
        fixture.namespace.files.put("pending-2-abandoned.manifest", new byte[] {1, 2});
        fixture.namespace.files.put("notes.txt", new byte[] {3});
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertTrue(store.readLatest(fixture.journal).isEmpty());
            store.publish(fixture.manifest(1), fixture.journal);
            assertArrayEquals(new byte[] {1, 2}, fixture.namespace.files.get("pending-2-abandoned.manifest"));
            assertFalse(fixture.namespace.events.contains("read:pending-2-abandoned.manifest"));
        }
    }

    @Test
    void highestGenerationWinsRegardlessOfDirectoryEnumerationOrder() throws IOException {
        var fixture = fixture();
        fixture.namespace.selected(fixture.manifest(9));
        fixture.namespace.selected(fixture.manifest(2));
        fixture.namespace.selected(fixture.manifest(7));
        try (var store = new LocalCheckpointStore(fixture.namespace); var reader = store.readLatest(fixture.journal).orElseThrow()) {
            assertEquals(9, reader.manifest().generation());
            assertFalse(fixture.namespace.events.stream().anyMatch(event -> event.startsWith("read:checkpoint-0000000000000000002-")));
        }
    }

    @Test
    void corruptNewestGenerationNeverFallsBackToAnOlderValidManifest() {
        var fixture = fixture();
        fixture.namespace.selected(fixture.manifest(1));
        var newestName = fixture.namespace.selected(fixture.manifest(2));
        fixture.namespace.files.get(newestName)[0] ^= 1;
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(ManifestFormatException.class, () -> store.readLatest(fixture.journal));
            assertPoisoned(store, fixture);
        } catch (IOException unexpected) { throw new AssertionError(unexpected); }
    }

    @Test
    void duplicateGenerationAndMalformedSelectedNamesFailBeforeReadingAnyCandidate() {
        for (String bad : List.of("checkpoint-bad.manifest", "checkpoint-0000000000000000000-" + "0".repeat(64) + ".manifest",
                "checkpoint-9999999999999999999-" + "0".repeat(64) + ".manifest")) {
            var fixture = fixture();
            fixture.namespace.files.put(bad, new byte[0]);
            try (var store = new LocalCheckpointStore(fixture.namespace)) {
                assertThrows(IOException.class, () -> store.readLatest(fixture.journal));
                assertEquals(0, fixture.namespace.reads);
            } catch (IOException unexpected) { throw new AssertionError(unexpected); }
        }
        var fixture = fixture();
        fixture.namespace.selected(fixture.manifest(1));
        fixture.namespace.files.put(LocalCheckpointStore.generationName(1, new ManifestId("0".repeat(64))), new byte[0]);
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.readLatest(fixture.journal));
            assertEquals(0, fixture.namespace.reads);
        } catch (IOException unexpected) { throw new AssertionError(unexpected); }
    }

    @Test
    void filenameIdAndPhysicalGenerationMustMatchEncodedManifest() {
        for (boolean badId : new boolean[] {true, false}) {
            var fixture = fixture();
            byte[] image = ManifestCodec.encode(fixture.manifest(1));
            var id = ManifestCodec.decode(image).id();
            fixture.namespace.files.put(LocalCheckpointStore.generationName(badId ? 1 : 2,
                    badId ? new ManifestId("0".repeat(64)) : id), image);
            try (var store = new LocalCheckpointStore(fixture.namespace)) {
                if (badId) {
                    assertThrows(ManifestFormatException.class, () -> store.readLatest(fixture.journal));
                } else {
                    assertThrows(IOException.class, () -> store.readLatest(fixture.journal));
                }
            } catch (IOException unexpected) { throw new AssertionError(unexpected); }
        }
    }

    @Test
    void missingOrChangedNewestDependencyFailsWithoutFallback() {
        var fixture = fixture();
        fixture.namespace.selected(fixture.manifest(1));
        fixture.namespace.selected(fixture.manifest(2));
        fixture.namespace.files.remove(LocalCheckpointStore.segmentName(SEGMENT_ID));
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.readLatest(fixture.journal));
            assertPoisoned(store, fixture);
        } catch (IOException unexpected) { throw new AssertionError(unexpected); }
    }

    @Test
    void generationMustBeExactlyNextAndNeverWrapOrSkip() {
        for (long generation : new long[] {1, 3, Long.MAX_VALUE}) {
            var fixture = fixture();
            fixture.namespace.selected(fixture.manifest(1));
            try (var store = new LocalCheckpointStore(fixture.namespace)) {
                assertThrows(IllegalArgumentException.class, () -> store.publish(fixture.manifest(generation), fixture.journal));
                assertEquals(0, fixture.namespace.writes);
                assertPoisoned(store, fixture);
            } catch (IOException unexpected) { throw new AssertionError(unexpected); }
        }
        var fixture = fixture();
        fixture.namespace.selected(fixture.manifest(Long.MAX_VALUE));
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IllegalArgumentException.class, () -> store.publish(fixture.manifest(1), fixture.journal));
            assertEquals(0, fixture.namespace.writes);
        } catch (IOException unexpected) { throw new AssertionError(unexpected); }
    }

    @Test
    void temporalCutoffCannotMoveBackwardAsPhysicalGenerationAdvances() {
        var fixture = fixture();
        fixture.namespace.selected(fixture.manifest(1));
        byte[] header = JournalCodec.encodeHeader(DATABASE);
        var backward = new CheckpointManifest(DATABASE, 2, new JournalPrefix(0, header.length, hash(header)), List.of());
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IllegalArgumentException.class, () -> store.publish(backward, fixture.journal));
            assertEquals(0, fixture.namespace.writes);
        } catch (IOException unexpected) { throw new AssertionError(unexpected); }
    }

    @Test
    void journalLineagePrefixAndForceFailuresPreserveAllSelectedState() {
        for (int fault = 0; fault < 3; fault++) {
            var fixture = fixture();
            if (fault == 0) { fixture.journal.database = new DatabaseId("other"); }
            if (fault == 1) { fixture.journal.wrongHash = true; }
            if (fault == 2) { fixture.journal.failForce = true; }
            var original = fixture.namespace.snapshot();
            try (var store = new LocalCheckpointStore(fixture.namespace)) {
                assertThrows(IOException.class, () -> store.publish(fixture.manifest(1), fixture.journal));
                assertFilesEqual(original, fixture.namespace.files);
                assertEquals(0, fixture.namespace.writes);
                assertPoisoned(store, fixture);
            } catch (IOException unexpected) { throw new AssertionError(unexpected); }
        }
    }

    @Test
    void dependencyFileOrDirectoryForceFailureCreatesNoPendingCandidate() {
        for (boolean directoryFailure : new boolean[] {false, true}) {
            var fixture = fixture();
            fixture.namespace.failForceFile = !directoryFailure;
            fixture.namespace.failDirectoryAt = directoryFailure ? 1 : -1;
            var original = fixture.namespace.snapshot();
            try (var store = new LocalCheckpointStore(fixture.namespace)) {
                assertThrows(IOException.class, () -> store.publish(fixture.manifest(1), fixture.journal));
                assertFilesEqual(original, fixture.namespace.files);
                assertEquals(0, fixture.namespace.writes);
                assertEquals(0, fixture.namespace.moves);
            } catch (IOException unexpected) { throw new AssertionError(unexpected); }
        }
    }

    @Test
    void unsupportedDependencyDirectorySyncLeavesAllCandidateBytesUntouched() {
        var fixture = fixture();
        fixture.namespace.failDirectoryAt = 1;
        fixture.namespace.unsupportedDirectory = true;
        var before = fixture.namespace.snapshot();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(LocalCheckpointStore.DirectorySyncUnsupportedException.class,
                    () -> store.publish(fixture.manifest(1), fixture.journal));
            assertFilesEqual(before, fixture.namespace.files);
            assertEquals(0, fixture.namespace.writes);
            assertEquals(0, fixture.namespace.moves);
        } catch (IOException unexpected) { throw new AssertionError(unexpected); }
    }

    @Test
    void incompletePendingWriteIsRetainedAndIgnoredOnReopen() throws IOException {
        var fixture = fixture();
        fixture.namespace.partialWriteFailure = true;
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(IOException.class, () -> store.publish(fixture.manifest(1), fixture.journal));
            assertEquals(0, fixture.namespace.moves);
            assertTrue(fixture.namespace.files.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith("pending-") && entry.getValue().length == 7));
            assertPoisoned(store, fixture);
        }
        try (var reopened = new LocalCheckpointStore(fixture.namespace.reopen())) {
            assertTrue(reopened.readLatest(fixture.journal).isEmpty());
        }
    }

    @Test
    void failedMoveBeforeVisibilityIsUncertainAndReopenFindsNoSelectedGeneration() throws IOException {
        var fixture = fixture();
        fixture.namespace.failMove = true;
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(LocalCheckpointStore.UncertainPublicationException.class,
                    () -> store.publish(fixture.manifest(1), fixture.journal));
            assertPoisoned(store, fixture);
            assertTrue(fixture.namespace.files.keySet().stream().anyMatch(name -> name.startsWith("pending-")));
        }
        try (var reopened = new LocalCheckpointStore(fixture.namespace.reopen())) {
            assertTrue(reopened.readLatest(fixture.journal).isEmpty());
        }
    }

    @Test
    void moveThatTakesEffectThenThrowsMustBeResolvedByReopen() throws IOException {
        var fixture = fixture();
        fixture.namespace.moveThenFail = true;
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(LocalCheckpointStore.UncertainPublicationException.class,
                    () -> store.publish(fixture.manifest(1), fixture.journal));
            assertPoisoned(store, fixture);
        }
        try (var reopened = new LocalCheckpointStore(fixture.namespace.reopen()); var reader = reopened.readLatest(fixture.journal).orElseThrow()) {
            assertEquals(fixture.manifest(1), reader.manifest());
        }
    }

    @Test
    void finalDirectoryForceFailureIsUncertainAndVisibleGenerationSurvivesReopen() throws IOException {
        var fixture = fixture();
        fixture.namespace.failDirectoryAt = 2;
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(LocalCheckpointStore.UncertainPublicationException.class,
                    () -> store.publish(fixture.manifest(1), fixture.journal));
            assertPoisoned(store, fixture);
        }
        try (var reopened = new LocalCheckpointStore(fixture.namespace.reopen()); var reader = reopened.readLatest(fixture.journal).orElseThrow()) {
            assertEquals(1, reader.manifest().generation());
        }
    }

    @Test
    void failedSegmentCreationRetainsCandidateAndPoisonsStore() throws IOException {
        var namespace = new MemoryNamespace(directory);
        namespace.partialWriteFailure = true;
        var fixture = new Fixture(namespace, new MemoryJournal(namespace.events));
        try (var store = new LocalCheckpointStore(namespace)) {
            assertThrows(IOException.class, () -> store.writeSegment(SEGMENT));
            assertArrayEquals(Arrays.copyOf(SEGMENT_IMAGE, 7), namespace.files.get(LocalCheckpointStore.segmentName(SEGMENT_ID)));
            assertPoisoned(store, fixture);
        }
    }

    @Test
    void metadataEncodeFailurePrecedesNamespaceMutationAndLeavesStoreUsable() throws IOException {
        var fixture = fixture();
        var invalid = new CheckpointManifest(new DatabaseId("x".repeat(32_769)), 1,
                new JournalPrefix(0, 65_558, "0".repeat(64)), List.of());
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            assertThrows(ManifestFormatException.class, () -> store.publish(invalid, fixture.journal));
            assertTrue(fixture.namespace.events.isEmpty());
            store.publish(fixture.manifest(1), fixture.journal);
        }
    }

    @Test
    void directoryAndGenerationReadBoundsFailBeforeLoadingObjects() {
        var excessiveEntries = fixture();
        excessiveEntries.namespace.entryOverride = java.util.Collections.nCopies(LocalCheckpointStore.MAX_DIRECTORY_ENTRIES + 1, "unrelated");
        try (var store = new LocalCheckpointStore(excessiveEntries.namespace)) {
            assertThrows(IOException.class, () -> store.readLatest(excessiveEntries.journal));
            assertEquals(0, excessiveEntries.namespace.reads);
        } catch (IOException unexpected) { throw new AssertionError(unexpected); }
        var excessiveGenerations = fixture();
        var names = new ArrayList<String>();
        for (int i = 1; i <= LocalCheckpointStore.MAX_GENERATIONS + 1; i++) {
            names.add(LocalCheckpointStore.generationName(i, new ManifestId("0".repeat(64))));
        }
        excessiveGenerations.namespace.entryOverride = names;
        try (var store = new LocalCheckpointStore(excessiveGenerations.namespace)) {
            assertThrows(IOException.class, () -> store.readLatest(excessiveGenerations.journal));
            assertEquals(0, excessiveGenerations.namespace.reads);
        } catch (IOException unexpected) { throw new AssertionError(unexpected); }
    }

    @Test
    void fullDirectoryStillReadsExistingGenerationButRefusesAnyNewCandidate() throws IOException {
        var fixture = fixture();
        String selected = fixture.namespace.selected(fixture.manifest(1));
        var names = new ArrayList<String>();
        names.add(selected);
        for (int i = 1; i < LocalCheckpointStore.MAX_DIRECTORY_ENTRIES; i++) { names.add("unrelated-" + i); }
        fixture.namespace.entryOverride = names;
        try (var store = new LocalCheckpointStore(fixture.namespace); var reader = store.readLatest(fixture.journal).orElseThrow()) {
            assertEquals(1, reader.manifest().generation());
            var before = fixture.namespace.snapshot();
            assertThrows(IOException.class, () -> store.publish(fixture.manifest(2), fixture.journal));
            assertFilesEqual(before, fixture.namespace.files);
            assertEquals(0, fixture.namespace.writes);
        }
        var namespace = fixture.namespace.reopen();
        namespace.entryOverride = names;
        try (var store = new LocalCheckpointStore(namespace)) {
            assertThrows(IOException.class, () -> store.writeSegment(SEGMENT));
            assertEquals(0, namespace.writes);
        }
    }

    @Test
    void fullGenerationCountStillReadsNewestButRejectsAnotherPublication() throws IOException {
        var fixture = fixture();
        var names = new ArrayList<String>();
        var dummy = new ManifestId("0".repeat(64));
        for (int i = 1; i < LocalCheckpointStore.MAX_GENERATIONS; i++) { names.add(LocalCheckpointStore.generationName(i, dummy)); }
        names.add(fixture.namespace.selected(fixture.manifest(LocalCheckpointStore.MAX_GENERATIONS)));
        fixture.namespace.entryOverride = names;
        try (var store = new LocalCheckpointStore(fixture.namespace); var reader = store.readLatest(fixture.journal).orElseThrow()) {
            assertEquals(LocalCheckpointStore.MAX_GENERATIONS, reader.manifest().generation());
            assertThrows(IOException.class, () -> store.publish(fixture.manifest(LocalCheckpointStore.MAX_GENERATIONS + 1L), fixture.journal));
            assertEquals(0, fixture.namespace.writes);
        }
    }

    @Test
    void reentrantReadWriteAndCloseInsideStorageCallbackAreRejectedWithoutCorruptingOuterOperation() throws IOException {
        var fixture = fixture();
        try (var store = new LocalCheckpointStore(fixture.namespace)) {
            fixture.namespace.readHook = () -> {
                assertThrows(IOException.class, store::close);
                assertThrows(IOException.class, () -> store.writeSegment(SEGMENT));
                assertThrows(IOException.class, () -> store.readLatest(fixture.journal));
            };
            store.publish(fixture.manifest(1), fixture.journal);
            assertEquals(1, fixture.namespace.moves);
        }
    }

    @Test
    void closeFailureCanBeRetriedAndStoreStaysClosedToOperations() throws IOException {
        var fixture = fixture();
        fixture.namespace.failCloseCount = 1;
        var store = new LocalCheckpointStore(fixture.namespace);
        assertThrows(IOException.class, store::close);
        assertThrows(IOException.class, store::journalPath);
        assertThrows(IOException.class, () -> store.readLatest(fixture.journal));
        store.close();
        store.close();
        assertEquals(2, fixture.namespace.closes);
    }

    @Test
    void shortFileIoAndTransientZerosPreserveExactBytesAndCloseBeforeSuccess() throws IOException {
        var output = new MemoryFile(new byte[0]);
        output.maxChunk = 3;
        output.zeros = 2;
        LocalCheckpointStore.write(output, SEGMENT_IMAGE);
        assertArrayEquals(SEGMENT_IMAGE, output.data);
        assertEquals(1, output.forces);
        assertEquals(1, output.closes);
        var input = new MemoryFile(SEGMENT_IMAGE);
        input.maxChunk = 2;
        input.zeros = 2;
        assertArrayEquals(SEGMENT_IMAGE, LocalCheckpointStore.read(input, SEGMENT_IMAGE.length));
        assertEquals(1, input.closes);
        assertEquals(0, input.forces);
        assertEquals(SEGMENT_IMAGE.length, input.lastReadOffset);
    }

    @Test
    void sustainedZeroReadWriteAndEofProbeFailBoundedlyAndClose() {
        for (boolean read : new boolean[] {true, false}) {
            var file = new MemoryFile(read ? SEGMENT_IMAGE : new byte[0]);
            file.zeros = 1000;
            if (read) { assertThrows(IOException.class, () -> LocalCheckpointStore.read(file, SEGMENT_IMAGE.length)); }
            else { assertThrows(IOException.class, () -> LocalCheckpointStore.write(file, SEGMENT_IMAGE)); }
            assertTrue(file.zeros > 0);
            assertEquals(1, file.closes);
            assertEquals(0, file.forces);
        }
        var eof = new MemoryFile(SEGMENT_IMAGE);
        eof.eofZeros = 1000;
        assertThrows(IOException.class, () -> LocalCheckpointStore.read(eof, SEGMENT_IMAGE.length));
        assertTrue(eof.eofZeros > 0);
        assertEquals(1, eof.closes);
    }

    @Test
    void partialFileFailureRetainsEvidenceAndCloseFailureIsSuppressedOnPrimary() {
        var output = new MemoryFile(new byte[0]);
        output.maxChunk = 3;
        output.failAt = 7;
        output.failClose = true;
        var failure = assertThrows(IOException.class, () -> LocalCheckpointStore.write(output, SEGMENT_IMAGE));
        assertSame(output.ioFailure, failure);
        assertArrayEquals(new Throwable[] {output.closeFailure}, failure.getSuppressed());
        assertArrayEquals(Arrays.copyOf(SEGMENT_IMAGE, 7), output.data);
        assertEquals(0, output.forces);
        var input = new MemoryFile(SEGMENT_IMAGE);
        input.maxChunk = 3;
        input.failAt = 7;
        input.failClose = true;
        failure = assertThrows(IOException.class, () -> LocalCheckpointStore.read(input, SEGMENT_IMAGE.length));
        assertSame(input.ioFailure, failure);
        assertArrayEquals(new Throwable[] {input.closeFailure}, failure.getSuppressed());
        assertEquals(1, input.closes);
    }

    @Test
    void fileForceAndCloseFailuresCannotReportSuccessfulWriteOrRead() {
        var force = new MemoryFile(new byte[0]);
        force.failForce = true;
        assertThrows(IOException.class, () -> LocalCheckpointStore.write(force, SEGMENT_IMAGE));
        assertArrayEquals(SEGMENT_IMAGE, force.data);
        assertEquals(1, force.closes);
        var output = new MemoryFile(new byte[0]);
        output.failClose = true;
        assertSame(output.closeFailure, assertThrows(IOException.class, () -> LocalCheckpointStore.write(output, SEGMENT_IMAGE)));
        assertEquals(1, output.forces);
        var input = new MemoryFile(SEGMENT_IMAGE);
        input.failClose = true;
        assertSame(input.closeFailure, assertThrows(IOException.class, () -> LocalCheckpointStore.read(input, SEGMENT_IMAGE.length)));
    }

    @Test
    void boundedFileReadRejectsOversizeShrinkGrowthAndSecondSizeChange() {
        var over = new MemoryFile(SEGMENT_IMAGE);
        over.sizes = new long[] {Long.MAX_VALUE};
        assertThrows(IOException.class, () -> LocalCheckpointStore.read(over, SEGMENT_IMAGE.length));
        assertEquals(0, over.reads);
        var shrank = new MemoryFile(SEGMENT_IMAGE);
        shrank.sizes = new long[] {SEGMENT_IMAGE.length + 1L};
        assertThrows(EOFException.class, () -> LocalCheckpointStore.read(shrank, SEGMENT_IMAGE.length + 1));
        var grown = new MemoryFile(Arrays.copyOf(SEGMENT_IMAGE, SEGMENT_IMAGE.length + 1));
        grown.sizes = new long[] {SEGMENT_IMAGE.length};
        assertThrows(IOException.class, () -> LocalCheckpointStore.read(grown, SEGMENT_IMAGE.length));
        var changed = new MemoryFile(SEGMENT_IMAGE);
        changed.sizes = new long[] {SEGMENT_IMAGE.length, SEGMENT_IMAGE.length - 1L};
        assertThrows(IOException.class, () -> LocalCheckpointStore.read(changed, SEGMENT_IMAGE.length));
        for (var file : List.of(over, shrank, grown, changed)) { assertEquals(1, file.closes); }
    }

    private LocalCheckpointStore supportedStore() throws IOException {
        try { return LocalCheckpointStore.open(directory); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force unavailable: " + unsupported);
            throw new AssertionError("Assumption did not abort", unsupported);
        }
    }

    private void assertNoSelectedFiles() throws IOException {
        try (var names = Files.list(directory)) { assertFalse(names.anyMatch(path -> path.getFileName().toString().startsWith("checkpoint-"))); }
    }

    private Fixture fixture() {
        var namespace = new MemoryNamespace(directory);
        namespace.files.put(LocalCheckpointStore.segmentName(SEGMENT_ID), SEGMENT_IMAGE.clone());
        return new Fixture(namespace, new MemoryJournal(namespace.events));
    }

    private record Fixture(MemoryNamespace namespace, MemoryJournal journal) {
        CheckpointManifest manifest(long generation) { return LocalCheckpointStoreTest.manifest(generation, journal.originalPrefix, SEGMENT); }
    }

    private static CheckpointManifest manifest(long generation, LocalJournal.Prefix prefix, ImmutableSegment segment) {
        var id = new SegmentId(hash(SegmentCodec.encode(segment)));
        return new CheckpointManifest(segment.databaseId(), generation,
                new JournalPrefix(prefix.sequence(), prefix.byteLength(), prefix.sha256()),
                List.of(new SegmentReference(id, SegmentCodec.encode(segment).length, segment.firstSequence(), segment.lastSequence())));
    }

    private static void assertPoisoned(LocalCheckpointStore store, Fixture fixture) {
        int writes = fixture.namespace.writes;
        int moves = fixture.namespace.moves;
        assertThrows(IOException.class, store::journalPath);
        assertThrows(IOException.class, () -> store.writeSegment(SEGMENT));
        assertThrows(IOException.class, () -> store.readLatest(fixture.journal));
        assertThrows(IOException.class, () -> store.publish(fixture.manifest(1), fixture.journal));
        assertEquals(writes, fixture.namespace.writes);
        assertEquals(moves, fixture.namespace.moves);
    }

    private static int indexStarting(List<String> values, String prefix) {
        for (int i = 0; i < values.size(); i++) { if (values.get(i).startsWith(prefix)) { return i; } }
        return -1;
    }

    private static void assertFilesEqual(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, bytes) -> assertArrayEquals(bytes, actual.get(name), name));
    }

    private static JournalRecord record(long sequence, String request, long value) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(
                new WriteOperation.Put(new EntityId("salary"), new ValidTimeInterval(null, null), value))));
    }

    private static LocalJournal.Prefix prefix(long sequence) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(DATABASE));
        for (var record : RECORDS) { if (record.sequence() <= sequence) { bytes.writeBytes(JournalCodec.encodeRecord(record)); } }
        return new LocalJournal.Prefix(sequence, bytes.size(), hash(bytes.toByteArray()));
    }

    private static String hash(byte[] image) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static final class MemoryJournal implements LocalCheckpointStore.JournalAccess {
        final List<String> events;
        final LocalJournal.Prefix originalPrefix = LocalCheckpointStoreTest.prefix(2);
        DatabaseId database = DATABASE;
        boolean wrongHash;
        boolean failForce;
        MemoryJournal(List<String> events) { this.events = events; }
        @Override public DatabaseId databaseId() { return database; }
        @Override public LocalJournal.Prefix prefix(long sequence) {
            events.add("journal-prefix:" + sequence);
            var expected = LocalCheckpointStoreTest.prefix(sequence);
            return wrongHash ? new LocalJournal.Prefix(sequence, expected.byteLength(), "0".repeat(64)) : expected;
        }
        @Override public void force() throws IOException {
            events.add("journal-force");
            if (failForce) { throw new IOException("journal force failed"); }
        }
    }

    private static final class MemoryNamespace implements LocalCheckpointStore.Namespace {
        final Path directory;
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        List<String> entryOverride;
        boolean partialWriteFailure;
        boolean failForceFile;
        int failDirectoryAt = -1;
        boolean unsupportedDirectory;
        boolean failMove;
        boolean moveThenFail;
        int failCloseCount;
        int directoryForces;
        int reads;
        int writes;
        int moves;
        int closes;
        Runnable readHook;
        MemoryNamespace(Path directory) { this.directory = directory; }
        @Override public Path directory() { return directory; }
        @Override public List<String> entries() { events.add("entries"); return entryOverride == null ? new ArrayList<>(files.keySet()) : entryOverride; }
        @Override public byte[] read(String name, int maxBytes) throws IOException {
            events.add("read:" + name);
            reads++;
            if (readHook != null) { readHook.run(); }
            var image = files.get(name);
            if (image == null) { throw new IOException("Missing " + name); }
            if (image.length > maxBytes) { throw new IOException("Oversized " + name); }
            return image.clone();
        }
        @Override public void writeNew(String name, byte[] image) throws IOException {
            events.add("write:" + name);
            writes++;
            if (files.containsKey(name)) { throw new IOException("Already exists " + name); }
            files.put(name, partialWriteFailure ? Arrays.copyOf(image, 7) : image.clone());
            if (partialWriteFailure) { throw new IOException("Partial candidate write"); }
        }
        @Override public void forceFile(String name) throws IOException {
            events.add("force:" + name);
            if (failForceFile) { throw new IOException("Dependency force failed"); }
        }
        @Override public void atomicMove(String from, String to) throws IOException {
            events.add("move:" + from + "->" + to);
            moves++;
            if (failMove) { throw new IOException("Move failed before visibility"); }
            if (files.containsKey(to)) { throw new IOException("Destination exists"); }
            files.put(to, files.remove(from));
            if (moveThenFail) { throw new IOException("Move took effect then failed"); }
        }
        @Override public void forceDirectory() throws IOException {
            events.add("directory-force");
            if (++directoryForces == failDirectoryAt) {
                if (unsupportedDirectory) { throw new LocalCheckpointStore.DirectorySyncUnsupportedException(new IOException("Unsupported injected capability")); }
                throw new IOException("Directory force failed");
            }
        }
        @Override public void close() throws IOException {
            events.add("close");
            closes++;
            if (failCloseCount > 0) { failCloseCount--; throw new IOException("Close failed"); }
        }
        Map<String, byte[]> snapshot() {
            var result = new LinkedHashMap<String, byte[]>();
            files.forEach((name, image) -> result.put(name, image.clone()));
            return result;
        }
        MemoryNamespace reopen() { var result = new MemoryNamespace(directory); result.files.putAll(snapshot()); return result; }
        String selected(CheckpointManifest manifest) {
            byte[] bytes = ManifestCodec.encode(manifest);
            String name = LocalCheckpointStore.generationName(manifest.generation(), ManifestCodec.decode(bytes).id());
            files.put(name, bytes);
            return name;
        }
    }

    private static final class MemoryFile implements LocalCheckpointStore.FileAccess {
        byte[] data;
        int maxChunk = Integer.MAX_VALUE;
        int zeros;
        int eofZeros;
        long failAt = Long.MAX_VALUE;
        long[] sizes;
        int sizeCalls;
        boolean failForce;
        boolean failClose;
        int forces;
        int closes;
        int reads;
        long lastReadOffset;
        final IOException ioFailure = new IOException("Injected I/O failure");
        final IOException closeFailure = new IOException("Injected close failure");
        MemoryFile(byte[] data) { this.data = data.clone(); }
        @Override public int read(ByteBuffer target, long offset) throws IOException {
            reads++;
            lastReadOffset = offset;
            if (offset >= failAt) { throw ioFailure; }
            if (offset >= data.length) { if (eofZeros > 0) { eofZeros--; return 0; } return -1; }
            if (zeros > 0) { zeros--; return 0; }
            int count = (int) Math.min(Math.min(maxChunk, target.remaining()), Math.min(data.length - offset, failAt - offset));
            target.put(data, (int) offset, count);
            return count;
        }
        @Override public int write(ByteBuffer source, long offset) throws IOException {
            if (offset >= failAt) { throw ioFailure; }
            if (zeros > 0) { zeros--; return 0; }
            int count = (int) Math.min(Math.min(maxChunk, source.remaining()), failAt - offset);
            int end = Math.toIntExact(offset + count);
            if (end > data.length) { data = Arrays.copyOf(data, end); }
            source.get(data, (int) offset, count);
            return count;
        }
        @Override public long size() { int index = sizeCalls++; return sizes != null && index < sizes.length ? sizes[index] : data.length; }
        @Override public void force() throws IOException { forces++; if (failForce) { throw ioFailure; } }
        @Override public void close() throws IOException { closes++; if (failClose) { throw closeFailure; } }
    }
}
