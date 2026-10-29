// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.journal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class LocalJournalTest {
    private static final DatabaseId DATABASE = new DatabaseId("local-db");
    private static final EntityId ENTITY = new EntityId("salary");
    private static final JournalRecord FIRST = record(1, "first", 100);
    private static final JournalRecord SECOND = record(2, "second", 120);
    private static final byte[] HEADER = JournalCodec.encodeHeader(DATABASE);

    @TempDir
    Path directory;

    @Test
    void symlinkParentThenDotDotUsesTheProviderRealParentForCreateAndReopen() throws IOException {
        var base = Files.createDirectory(directory.resolve("base"));
        var elsewhere = Files.createDirectory(directory.resolve("elsewhere"));
        var child = Files.createDirectory(elsewhere.resolve("child"));
        try { Files.createSymbolicLink(base.resolve("link"), child); }
        catch (UnsupportedOperationException unavailable) { assumeTrue(false, "Symbolic links unavailable: " + unavailable); }
        var requested = base.resolve("link").resolve("..").resolve("transactions.journal");
        var actual = requested.getParent().toRealPath().resolve(requested.getFileName());
        try (var journal = LocalJournal.create(requested, DATABASE)) {
            assertEquals(Optional.of(actual), journal.path());
            journal.append(FIRST);
            assertEquals(1, journal.force());
        }
        assertTrue(Files.isSameFile(actual, requested));
        var lexicalNeighbor = base.toRealPath().resolve("transactions.journal");
        if (!actual.equals(lexicalNeighbor)) { assertFalse(Files.exists(lexicalNeighbor)); }
        try (var journal = LocalJournal.open(requested)) {
            assertEquals(Optional.of(actual), journal.path());
            assertEquals(List.of(FIRST), replay(journal));
        }
    }

    @Test
    void realFilePreservesLineageExactIdentityOperationOrderAndExplicitForceCutoff() throws IOException {
        var path = directory.resolve("transactions.journal");
        var identity = "A\u0000é" + (char) 0xd800 + "e" + (char) 0x0301 + (char) 0xdc00;
        var database = new DatabaseId(identity);
        var entity = new EntityId(identity);
        var first = new JournalRecord(1, new Transaction(new RequestId(identity), List.of(
                new WriteOperation.Put(entity, new ValidTimeInterval(null, null), Long.MIN_VALUE),
                new WriteOperation.Retract(entity, new ValidTimeInterval(Long.MIN_VALUE, Long.MAX_VALUE)),
                new WriteOperation.Put(entity, new ValidTimeInterval(Long.MAX_VALUE, null), Long.MAX_VALUE))));
        try (var journal = LocalJournal.create(path, database)) {
            assertEquals(database, journal.databaseId());
            assertEquals(0, journal.appendedSequence());
            assertEquals(0, journal.forcedSequence());
            journal.append(first);
            assertEquals(1, journal.appendedSequence());
            assertEquals(0, journal.forcedSequence());
            assertEquals(1, journal.force());
            assertEquals(journal.appendedBytes(), journal.forcedBytes());
            journal.append(SECOND);
            assertEquals(2, journal.appendedSequence());
            assertEquals(1, journal.forcedSequence());
            assertTrue(journal.appendedBytes() > journal.forcedBytes());
            assertEquals(List.of(first, SECOND), replay(journal));
        }
        assertArrayEquals(concat(JournalCodec.encodeHeader(database), JournalCodec.encodeRecord(first),
                JournalCodec.encodeRecord(SECOND)), Files.readAllBytes(path));
        try (var journal = LocalJournal.open(path)) {
            assertEquals(database, journal.databaseId());
            assertEquals(List.of(first, SECOND), replay(journal));
            assertEquals(2, journal.appendedSequence());
            // Existing complete bytes carry no persisted evidence of a previous force.
            assertEquals(0, journal.forcedSequence());
            assertEquals(0, journal.forcedBytes());
            assertEquals(2, journal.force());
            assertEquals(Files.size(path), journal.forcedBytes());
            assertEquals(Optional.empty(), journal.repair());
        }
    }

    @Test
    void closeIsIdempotentRejectsFurtherUseAndReleasesWriterOwnership() throws IOException {
        var path = directory.resolve("journal");
        var journal = LocalJournal.create(path, DATABASE);
        journal.append(FIRST);
        journal.close();
        journal.close();
        assertEquals(DATABASE, journal.databaseId());
        assertEquals(1, journal.appendedSequence());
        assertThrows(IOException.class, () -> journal.append(SECOND));
        assertThrows(IOException.class, journal::force);
        assertThrows(IOException.class, () -> journal.replay(ignored -> {}));
        try (var reopened = LocalJournal.open(path)) {
            assertEquals(List.of(FIRST), replay(reopened));
        }
    }

    @Test
    void createNeverOverwritesAnExistingFile() throws IOException {
        var path = directory.resolve("existing");
        byte[] original = {9, 4, 2, 1};
        Files.write(path, original);
        assertThrows(IOException.class, () -> LocalJournal.create(path, DATABASE));
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test
    void refusesAnotherWriterAndNormalizedPathAliasWithoutDisturbingFirstWriter() throws IOException {
        var path = directory.resolve("journal");
        try (var journal = LocalJournal.create(path, DATABASE)) {
            assertThrows(IOException.class, () -> LocalJournal.open(path));
            assertThrows(IOException.class, () -> LocalJournal.open(directory.resolve(".").resolve("journal")));
            journal.append(FIRST);
            assertEquals(1, journal.force());
            assertEquals(List.of(FIRST), replay(journal));
        }
    }

    @Test
    void refusesHardLinkAliasWithoutClosingTheFirstWritersLock() throws IOException {
        var path = directory.resolve("journal");
        var alias = directory.resolve("hard-link");
        try (var journal = LocalJournal.create(path, DATABASE)) {
            try {
                Files.createLink(alias, path);
            } catch (UnsupportedOperationException | FileSystemException unsupported) {
                assumeTrue(false, "Hard links unavailable: " + unsupported);
            }
            assertThrows(IOException.class, () -> LocalJournal.open(alias));
            journal.append(FIRST);
            assertEquals(1, journal.force());
            assertEquals(List.of(FIRST), replay(journal));
        }
    }

    @Test
    void refusesSymbolicLinkAliasWithoutDisturbingFirstWriter() throws IOException {
        var path = directory.resolve("journal");
        var alias = directory.resolve("symbolic-link");
        try (var journal = LocalJournal.create(path, DATABASE)) {
            try {
                Files.createSymbolicLink(alias, path.getFileName());
            } catch (UnsupportedOperationException | FileSystemException unsupported) {
                assumeTrue(false, "Symbolic links unavailable: " + unsupported);
            }
            assertThrows(IOException.class, () -> LocalJournal.open(alias));
            journal.append(FIRST);
            assertEquals(List.of(FIRST), replay(journal));
        }
    }

    @Test
    void incompleteTailIsRejectedByDefaultWithExactValidatedBoundary() throws IOException {
        var path = directory.resolve("journal");
        byte[] valid = image(FIRST);
        byte[] original = concat(valid, Arrays.copyOf(JournalCodec.encodeRecord(SECOND), 17));
        Files.write(path, original);
        var failure = assertThrows(LocalJournal.IncompleteTailException.class, () -> LocalJournal.open(path));
        assertEquals(valid.length, failure.validBytes());
        assertEquals(original.length, failure.fileBytes());
        assertArrayEquals(original, Files.readAllBytes(path));
        // A failed open releases ownership, allowing an explicit repair attempt.
        try (var repaired = LocalJournal.open(path, LocalJournal.TailPolicy.PRESERVE_AND_TRUNCATE)) {
            assertEquals(List.of(FIRST), replay(repaired));
        }
    }

    @Test
    void explicitRepairPreservesEveryCandidateByteBeforeTruncating() throws IOException {
        byte[] frame = JournalCodec.encodeRecord(SECOND);
        for (int suffixLength : new int[] {1, 8, 15, 16, 17, frame.length - 1}) {
            var path = directory.resolve("partial-" + suffixLength);
            byte[] valid = image(FIRST);
            byte[] suffix = Arrays.copyOf(frame, suffixLength);
            byte[] original = concat(valid, suffix);
            Files.write(path, original);
            try (var journal = LocalJournal.open(path, LocalJournal.TailPolicy.PRESERVE_AND_TRUNCATE)) {
                var repair = journal.repair().orElseThrow();
                assertEquals(valid.length, repair.startOffset());
                assertEquals(original.length, repair.originalSize());
                assertArrayEquals(suffix, Files.readAllBytes(repair.evidence()));
                assertFalse(repair.evidence().equals(path));
                assertEquals(valid.length, journal.appendedBytes());
                assertEquals(1, journal.appendedSequence());
                assertEquals(1, journal.forcedSequence());
                assertEquals(valid.length, journal.forcedBytes());
                journal.append(SECOND);
                assertEquals(List.of(FIRST, SECOND), replay(journal));
            }
            // Inspect through a second handle only after releasing the journal's lock.
            assertArrayEquals(concat(valid, frame), Files.readAllBytes(path));
            try (var journal = LocalJournal.open(path)) {
                assertEquals(Optional.empty(), journal.repair());
                assertEquals(List.of(FIRST, SECOND), replay(journal));
            }
        }
    }

    @Test
    void incompleteHeaderIsNeverRepaired() {
        for (int length : new int[] {0, 1, 12, HEADER.length - 1}) {
            var file = new MemoryFile(Arrays.copyOf(HEADER, length));
            var backups = new AtomicInteger();
            var failure = assertThrows(JournalFormatException.class,
                    () -> LocalJournal.open(file, LocalJournal.TailPolicy.PRESERVE_AND_TRUNCATE,
                            (source, start, originalSize) -> {
                                backups.incrementAndGet();
                                return directory.resolve("unused");
                            }));
            assertEquals(JournalFormatException.Code.INCOMPLETE_HEADER, failure.code());
            assertEquals(0, backups.get());
            assertEquals(0, file.truncations);
            assertTrue(file.closed);
            assertArrayEquals(Arrays.copyOf(HEADER, length), file.bytes());
        }
    }

    @Test
    void completeCorruptionAndUnsupportedVersionsFailClosedWithoutTailRepair() {
        byte[] corrupt = image(FIRST);
        corrupt[corrupt.length - 1] ^= 1;
        byte[] unsupported = image(FIRST);
        ByteBuffer.wrap(unsupported).putInt(HEADER.length + 4, 2);
        replaceChecksum(unsupported, HEADER.length, 12);
        for (byte[] original : List.of(corrupt, unsupported)) {
            var file = new MemoryFile(original);
            var backups = new AtomicInteger();
            var failure = assertThrows(JournalFormatException.class,
                    () -> LocalJournal.open(file, LocalJournal.TailPolicy.PRESERVE_AND_TRUNCATE,
                            (source, start, size) -> {
                                backups.incrementAndGet();
                                return directory.resolve("unused");
                            }));
            assertEquals(original == corrupt ? JournalFormatException.Code.CORRUPT
                    : JournalFormatException.Code.UNSUPPORTED_VERSION, failure.code());
            assertEquals(0, backups.get());
            assertEquals(0, file.truncations);
            assertTrue(file.closed);
            assertArrayEquals(original, file.bytes());
        }
    }

    @Test
    void checkedOversizedLengthIsRejectedAfterOnlyTheFixedPrefixRead() {
        // The CRC is correct: the scanner must enforce the v1 bound before
        // allocating/requesting a body rather than relying on checksum failure.
        byte[] original = concat(HEADER, recordPrefix(8_388_609), new byte[] {7});
        var file = new MemoryFile(original);
        var failure = assertThrows(JournalFormatException.class,
                () -> LocalJournal.open(file, LocalJournal.TailPolicy.PRESERVE_AND_TRUNCATE,
                        (source, start, size) -> {
                            throw new AssertionError("An oversized checked length is not a repairable tail");
                        }));
        assertEquals(JournalFormatException.Code.LIMIT_EXCEEDED, failure.code());
        assertTrue(file.reads.stream().allMatch(read -> read.offset() < HEADER.length + 16));
        assertTrue(file.reads.stream().allMatch(read -> read.requested() <= HEADER.length));
        assertEquals(0, file.truncations);
        assertArrayEquals(original, file.bytes());
        assertTrue(file.closed);
    }

    @Test
    void maximumValidLengthWithMissingBodyDoesNotDemandAFullPayloadRead() throws IOException {
        byte[] valid = image(FIRST);
        byte[] suffix = concat(recordPrefix(8_388_608), new byte[] {9, 8});
        byte[] original = concat(valid, suffix);
        var refusing = new MemoryFile(original);
        var failure = assertThrows(LocalJournal.IncompleteTailException.class, () -> open(refusing));
        assertEquals(valid.length, failure.validBytes());
        assertEquals(original.length, failure.fileBytes());
        assertTrue(refusing.reads.stream().allMatch(read -> read.offset() < valid.length + 16));
        assertTrue(refusing.reads.stream().allMatch(read -> read.requested() < 1024));
        assertArrayEquals(original, refusing.bytes());
        assertTrue(refusing.closed);

        var repairing = new MemoryFile(original);
        var evidence = directory.resolve("maximum-length-tail");
        try (var journal = LocalJournal.open(repairing, LocalJournal.TailPolicy.PRESERVE_AND_TRUNCATE,
                preserve(repairing, evidence))) {
            assertEquals(1, journal.appendedSequence());
            assertEquals(1, journal.forcedSequence());
            assertEquals(valid.length, journal.repair().orElseThrow().startOffset());
            assertArrayEquals(valid, repairing.bytes());
            assertArrayEquals(suffix, Files.readAllBytes(evidence));
            assertTrue(repairing.reads.stream().allMatch(read -> read.offset() < valid.length + 16));
            assertTrue(repairing.reads.stream().allMatch(read -> read.requested() < 1024));
        }
    }

    @Test
    void recoveryRejectsSequenceGapsAndRepeatedRequestsWithoutRepair() {
        for (var records : List.of(List.of(SECOND), List.of(FIRST, record(3, "gap", 1)),
                List.of(FIRST, record(2, "first", 1)))) {
            var file = new MemoryFile(image(records.toArray(JournalRecord[]::new)));
            var failure = assertThrows(JournalFormatException.class,
                    () -> LocalJournal.open(file, LocalJournal.TailPolicy.PRESERVE_AND_TRUNCATE,
                            (source, start, size) -> {
                                throw new AssertionError("Invalid complete history must not be repaired");
                            }));
            assertEquals(JournalFormatException.Code.INVALID_HISTORY, failure.code());
            assertEquals(0, file.truncations);
            assertTrue(file.closed);
        }
    }

    @Test
    void rejectedSequenceAndRequestDoNotWriteOrReserveFutureIdentities() throws IOException {
        var file = new MemoryFile(new byte[0]);
        try (var journal = LocalJournal.create(file, DATABASE)) {
            journal.append(FIRST);
            byte[] before = file.bytes();
            int writes = file.writes;
            assertThrows(IllegalArgumentException.class, () -> journal.append(record(3, "future", 999)));
            assertThrows(IllegalArgumentException.class, () -> journal.append(record(2, "first", 999)));
            assertArrayEquals(before, file.bytes());
            assertEquals(writes, file.writes);
            journal.append(record(2, "future", 999));
            assertEquals(2, journal.force());
            assertEquals(List.of(FIRST, record(2, "future", 999)), replay(journal));
        }
    }

    @Test
    void oversizedTransactionIsRejectedBeforeWriteWithoutPoisoningOrRequestReservation() throws IOException {
        var file = new MemoryFile(new byte[0]);
        try (var journal = LocalJournal.create(file, DATABASE)) {
            var tooLarge = new JournalRecord(1, new Transaction(new RequestId("retry"), List.of(
                    new WriteOperation.Put(new EntityId("x".repeat(32_769)),
                            new ValidTimeInterval(null, null), 3))));
            byte[] before = file.bytes();
            int writes = file.writes;
            var failure = assertThrows(JournalFormatException.class, () -> journal.append(tooLarge));
            assertEquals(JournalFormatException.Code.LIMIT_EXCEEDED, failure.code());
            assertArrayEquals(before, file.bytes());
            assertEquals(writes, file.writes);
            journal.append(record(1, "retry", 3));
            assertEquals(1, journal.force());
        }
    }

    @Test
    void shortReadsAndWritesAssembleExactFramesAndPreserveForceBoundary() throws IOException {
        var file = new MemoryFile(new byte[0]);
        file.maxRead = 3;
        file.maxWrite = 2;
        try (var journal = LocalJournal.create(file, DATABASE)) {
            journal.append(FIRST);
            int forces = file.forces;
            journal.append(SECOND);
            assertEquals(forces, file.forces, "Append is not an implicit durability barrier");
            assertEquals(List.of(FIRST, SECOND), replay(journal));
            assertArrayEquals(image(FIRST, SECOND), file.bytes());
            assertEquals(2, journal.force());
            assertEquals(forces + 1, file.forces);
            assertEquals(file.bytes().length, journal.forcedBytes());
        }
    }

    @Test
    void transientZeroProgressCanBeRetriedWithoutDuplicatingBytes() throws IOException {
        var file = new MemoryFile(new byte[0]);
        try (var journal = LocalJournal.create(file, DATABASE)) {
            file.zeroWrites = 1;
            journal.append(FIRST);
            file.zeroReads = 1;
            assertEquals(List.of(FIRST), replay(journal));
            assertArrayEquals(image(FIRST), file.bytes());
        }
    }

    @Test
    void sustainedZeroWriteProgressFailsBoundedlyAndPoisonsInsteadOfSpinning() throws IOException {
        var file = new MemoryFile(new byte[0]);
        try (var journal = LocalJournal.create(file, DATABASE)) {
            file.zeroWrites = 1000;
            assertThrows(IOException.class, () -> journal.append(FIRST));
            assertTrue(file.zeroWrites > 0, "The writer must stop before exhausting arbitrary zero progress");
            assertArrayEquals(HEADER, file.bytes());
            assertPoisoned(journal, file);
        }
    }

    @Test
    void sustainedZeroReadProgressFailsBoundedlyAndPoisonsInsteadOfSpinning() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            file.zeroReads = 1000;
            assertThrows(IOException.class, () -> journal.replay(ignored -> {}));
            assertTrue(file.zeroReads > 0, "The reader must stop before exhausting arbitrary zero progress");
            assertPoisoned(journal, file);
        }
    }

    @Test
    void failedPartialAppendPoisonsInstanceAndNeverAdvancesAcknowledgedPrefix() throws IOException {
        var file = new MemoryFile(new byte[0]);
        try (var journal = LocalJournal.create(file, DATABASE)) {
            journal.append(FIRST);
            journal.force();
            long validBytes = journal.appendedBytes();
            file.maxWrite = 3;
            file.failWriteAt = validBytes + 5;
            assertThrows(IOException.class, () -> journal.append(SECOND));
            assertEquals(1, journal.appendedSequence());
            assertEquals(validBytes, journal.appendedBytes());
            assertEquals(1, journal.forcedSequence());
            assertEquals(validBytes, journal.forcedBytes());
            assertEquals(validBytes + 5, file.bytes().length);
            assertPoisoned(journal, file);
        }
    }

    @Test
    void forceFailurePreservesPriorForceCutoffAndPoisonsInstance() throws IOException {
        var file = new MemoryFile(new byte[0]);
        try (var journal = LocalJournal.create(file, DATABASE)) {
            journal.append(FIRST);
            journal.force();
            long forcedBytes = journal.forcedBytes();
            journal.append(SECOND);
            file.failForce = true;
            assertThrows(IOException.class, journal::force);
            assertEquals(2, journal.appendedSequence());
            assertEquals(1, journal.forcedSequence());
            assertEquals(forcedBytes, journal.forcedBytes());
            assertPoisoned(journal, file);
        }
    }

    @Test
    void replayReadFailurePoisonsInstanceInsteadOfAllowingFurtherAppend() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            file.failRead = true;
            assertThrows(IOException.class, () -> journal.replay(ignored -> {}));
            assertPoisoned(journal, file);
        }
    }

    @Test
    void corruptionDiscoveredDuringReplayPoisonsTheWritableInstance() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            file.data[file.length - 1] ^= 1;
            var failure = assertThrows(JournalFormatException.class, () -> journal.replay(ignored -> {}));
            assertEquals(JournalFormatException.Code.CORRUPT, failure.code());
            assertPoisoned(journal, file);
        }
    }

    @Test
    void reentrantReplayMutationIsRejectedWithoutPoisoningWhenCallbackHandlesIt() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            var seen = new ArrayList<JournalRecord>();
            journal.replay(record -> {
                assertThrows(IOException.class, () -> journal.append(SECOND));
                assertThrows(IOException.class, journal::force);
                assertThrows(IOException.class, journal::close);
                seen.add(record);
            });
            assertEquals(List.of(FIRST), seen);
            journal.append(SECOND);
            assertEquals(2, journal.force());
        }
    }

    @Test
    void callbackFailurePropagatesWithoutPoisoningAValidJournal() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            var expected = new IllegalStateException("consumer failed");
            assertEquals(expected, assertThrows(IllegalStateException.class,
                    () -> journal.replay(record -> { throw expected; })));
            journal.append(SECOND);
            assertEquals(List.of(FIRST, SECOND), replay(journal));
        }
    }

    @Test
    void consumerFormatFailurePropagatesWithoutBeingMistakenForJournalCorruption() throws IOException {
        var file = new MemoryFile(image(FIRST));
        try (var journal = open(file)) {
            var failure = assertThrows(JournalFormatException.class,
                    () -> journal.replay(record -> JournalCodec.scan(new byte[0])));
            assertEquals(JournalFormatException.Code.INCOMPLETE_HEADER, failure.code());
            journal.append(SECOND);
            assertEquals(List.of(FIRST, SECOND), replay(journal));
            assertEquals(2, journal.force());
        }
    }

    @Test
    void backupFailureLeavesOriginalTailIntactAndNeverTruncates() {
        byte[] original = partialImage();
        var file = new MemoryFile(original);
        assertThrows(IOException.class, () -> LocalJournal.open(file,
                LocalJournal.TailPolicy.PRESERVE_AND_TRUNCATE, (source, start, size) -> {
                    assertArrayEquals(original, file.bytes());
                    throw new IOException("backup failed");
                }));
        assertArrayEquals(original, file.bytes());
        assertEquals(0, file.truncations);
        assertEquals(0, file.forces);
        assertTrue(file.closed);
    }

    @Test
    void repairBacksUpBeforeTruncationAndForcesTheRetainedPrefix() throws IOException {
        byte[] original = partialImage();
        var file = new MemoryFile(original);
        var evidence = directory.resolve("evidence");
        var events = new ArrayList<String>();
        file.events = events;
        try (var journal = LocalJournal.open(file, LocalJournal.TailPolicy.PRESERVE_AND_TRUNCATE,
                (source, start, size) -> {
                    events.add("backup");
                    assertEquals(image(FIRST).length, start);
                    assertEquals(original.length, size);
                    assertArrayEquals(original, file.bytes());
                    Files.write(evidence, Arrays.copyOfRange(file.bytes(), (int) start, (int) size));
                    return evidence;
                })) {
            assertEquals(List.of("backup", "truncate", "force"), events);
            assertEquals(evidence, journal.repair().orElseThrow().evidence());
            assertArrayEquals(Arrays.copyOfRange(original, image(FIRST).length, original.length),
                    Files.readAllBytes(evidence));
            assertArrayEquals(image(FIRST), file.bytes());
            assertEquals(1, journal.forcedSequence());
            assertEquals(image(FIRST).length, journal.forcedBytes());
        }
    }

    @Test
    void truncateFailureLeavesEvidenceAndClosesFailedOpen() throws IOException {
        byte[] original = partialImage();
        var file = new MemoryFile(original);
        file.failTruncate = true;
        var evidence = directory.resolve("evidence");
        assertThrows(IOException.class, () -> LocalJournal.open(file,
                LocalJournal.TailPolicy.PRESERVE_AND_TRUNCATE, preserve(file, evidence)));
        assertTrue(Files.exists(evidence));
        assertArrayEquals(original, file.bytes());
        assertEquals(0, file.forces);
        assertTrue(file.closed);
    }

    @Test
    void forceFailureAfterRepairRetainsEvidenceAndReturnsNoWritableJournal() throws IOException {
        byte[] original = partialImage();
        var file = new MemoryFile(original);
        file.failForce = true;
        var evidence = directory.resolve("evidence");
        assertThrows(IOException.class, () -> LocalJournal.open(file,
                LocalJournal.TailPolicy.PRESERVE_AND_TRUNCATE, preserve(file, evidence)));
        assertArrayEquals(Arrays.copyOfRange(original, image(FIRST).length, original.length),
                Files.readAllBytes(evidence));
        assertArrayEquals(image(FIRST), file.bytes());
        assertTrue(file.closed);
    }

    @Test
    void closeDoesNotForceAnUnforcedTransaction() throws IOException {
        var file = new MemoryFile(new byte[0]);
        var journal = LocalJournal.create(file, DATABASE);
        assertEquals(HEADER.length, journal.forcedBytes());
        int forces = file.forces;
        journal.append(FIRST);
        journal.close();
        assertEquals(forces, file.forces);
        assertEquals(0, journal.forcedSequence());
        assertEquals(HEADER.length, journal.forcedBytes());
        assertArrayEquals(image(FIRST), file.bytes());
    }

    @Test
    void failedCreationForceClosesFileAccessEvenAfterACompleteHeaderWrite() {
        var file = new MemoryFile(new byte[0]);
        file.failForce = true;
        assertThrows(IOException.class, () -> LocalJournal.create(file, DATABASE));
        assertArrayEquals(HEADER, file.bytes());
        assertTrue(file.closed);
    }

    @Test
    void failedCreateClosesFileAccessAndDoesNotPretendHeaderIsComplete() {
        var file = new MemoryFile(new byte[0]);
        file.maxWrite = 2;
        file.failWriteAt = 7;
        assertThrows(IOException.class, () -> LocalJournal.create(file, DATABASE));
        assertEquals(7, file.bytes().length);
        assertTrue(file.closed);
    }

    @Test
    void failedOpenReadClosesFileAccessWithoutChangingBytes() {
        byte[] original = image(FIRST);
        var file = new MemoryFile(original);
        file.failRead = true;
        assertThrows(IOException.class, () -> open(file));
        assertArrayEquals(original, file.bytes());
        assertTrue(file.closed);
    }

    @Test
    void closeFailureCanBeRetriedButJournalRemainsUnavailableForWrites() throws IOException {
        var file = new MemoryFile(new byte[0]);
        var journal = LocalJournal.create(file, DATABASE);
        file.failCloseCount = 1;
        assertThrows(IOException.class, journal::close);
        assertFalse(file.closed);
        assertThrows(IOException.class, () -> journal.append(FIRST));
        journal.close();
        journal.close();
        assertTrue(file.closed);
    }

    private static LocalJournal open(MemoryFile file) throws IOException {
        return LocalJournal.open(file, LocalJournal.TailPolicy.REJECT,
                (source, start, originalSize) -> { throw new AssertionError("Unexpected repair"); });
    }

    private static LocalJournal.Backup preserve(MemoryFile file, Path evidence) {
        return (source, start, originalSize) -> {
            assertEquals(file, source);
            Files.write(evidence, Arrays.copyOfRange(file.bytes(), (int) start, (int) originalSize));
            return evidence;
        };
    }

    private static List<JournalRecord> replay(LocalJournal journal) throws IOException {
        var result = new ArrayList<JournalRecord>();
        journal.replay(result::add);
        return List.copyOf(result);
    }

    private static void assertPoisoned(LocalJournal journal, MemoryFile file) {
        int writes = file.writes;
        int forces = file.forces;
        assertThrows(IOException.class, () -> journal.append(record(3, "third", 1)));
        assertThrows(IOException.class, journal::force);
        assertThrows(IOException.class, () -> journal.replay(ignored -> {}));
        assertEquals(writes, file.writes);
        assertEquals(forces, file.forces);
    }

    private static JournalRecord record(long sequence, String request, long value) {
        return new JournalRecord(sequence, new Transaction(new RequestId(request), List.of(
                new WriteOperation.Put(ENTITY, new ValidTimeInterval(null, null), value))));
    }

    private static byte[] image(JournalRecord... records) {
        var parts = new ArrayList<byte[]>();
        parts.add(HEADER);
        for (var record : records) {
            parts.add(JournalCodec.encodeRecord(record));
        }
        return concat(parts.toArray(byte[][]::new));
    }

    private static byte[] partialImage() {
        return concat(image(FIRST), Arrays.copyOf(JournalCodec.encodeRecord(SECOND), 17));
    }

    private static byte[] concat(byte[]... parts) {
        int length = Arrays.stream(parts).mapToInt(part -> part.length).sum();
        var result = new byte[length];
        int offset = 0;
        for (var part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static byte[] recordPrefix(int payloadBytes) {
        var prefix = ByteBuffer.allocate(16);
        prefix.putInt(0x4d54584e).putInt(1).putInt(payloadBytes).putInt(0);
        replaceChecksum(prefix.array(), 0, 12);
        return prefix.array();
    }

    private record ReadRequest(long offset, int requested) { }

    private static void replaceChecksum(byte[] bytes, int offset, int length) {
        var crc = new CRC32C();
        crc.update(bytes, offset, length);
        ByteBuffer.wrap(bytes).putInt(offset + length, (int) crc.getValue());
    }

    /** Positional fake that can fail after mutating part of a frame. */
    private static final class MemoryFile implements LocalJournal.FileAccess {
        byte[] data;
        int length;
        int maxRead = Integer.MAX_VALUE;
        int maxWrite = Integer.MAX_VALUE;
        int zeroReads;
        int zeroWrites;
        long failWriteAt = Long.MAX_VALUE;
        boolean failRead;
        boolean failForce;
        boolean failTruncate;
        int failCloseCount;
        int writes;
        int forces;
        int truncations;
        boolean closed;
        List<String> events = new ArrayList<>();
        final List<ReadRequest> reads = new ArrayList<>();

        MemoryFile(byte[] initial) {
            data = initial.clone();
            length = initial.length;
        }

        byte[] bytes() {
            return Arrays.copyOf(data, length);
        }

        @Override
        public int read(ByteBuffer destination, long position) throws IOException {
            ensureOpen();
            reads.add(new ReadRequest(position, destination.remaining()));
            if (failRead) {
                throw new IOException("injected read failure");
            }
            if (zeroReads > 0) {
                zeroReads--;
                return 0;
            }
            if (position >= length) {
                return -1;
            }
            int count = Math.min(Math.min(maxRead, destination.remaining()), length - (int) position);
            destination.put(data, (int) position, count);
            return count;
        }

        @Override
        public int write(ByteBuffer source, long position) throws IOException {
            ensureOpen();
            writes++;
            if (position >= failWriteAt) {
                throw new IOException("injected write failure");
            }
            if (zeroWrites > 0) {
                zeroWrites--;
                return 0;
            }
            int count = (int) Math.min(Math.min(maxWrite, source.remaining()), failWriteAt - position);
            int end = Math.toIntExact(position + count);
            if (end > data.length) {
                data = Arrays.copyOf(data, end);
            }
            source.get(data, (int) position, count);
            length = Math.max(length, end);
            return count;
        }

        @Override
        public long size() throws IOException {
            ensureOpen();
            return length;
        }

        @Override
        public void force() throws IOException {
            ensureOpen();
            forces++;
            events.add("force");
            if (failForce) {
                throw new IOException("injected force failure");
            }
        }

        @Override
        public void truncate(long size) throws IOException {
            ensureOpen();
            truncations++;
            events.add("truncate");
            if (failTruncate) {
                throw new IOException("injected truncate failure");
            }
            length = (int) Math.min(length, size);
        }

        @Override
        public void close() throws IOException {
            if (failCloseCount > 0) {
                failCloseCount--;
                throw new IOException("injected close failure");
            }
            closed = true;
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("file already closed");
            }
        }
    }
}
