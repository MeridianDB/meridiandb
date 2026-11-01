// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentFormatException;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/** Bounded reclamation binds segment record ranges before processing unbudgeted nested history. */
class LocalBoundedSegmentPreflightTest {
    private static final DatabaseId DATABASE = new DatabaseId("bounded-local-preflight");
    private static final JournalRecord FIRST = record(1);
    private static final JournalRecord SECOND = record(2);

    @TempDir Path directory;

    static Stream<Arguments> nestedDamage() {
        return Stream.of(false, true).flatMap(removal -> Stream.of("nested-record", "footer")
                .map(damage -> Arguments.of(removal, damage)));
    }

    @ParameterizedTest(name = "removal={0}, damage={1}") @MethodSource("nestedDamage")
    void oneRecordManifestRejectsLargerCrcValidHeaderBeforeNestedBodyOrFooter(boolean removal, String damage)
            throws IOException {
        byte[] image = oversizedHistory(damage);
        var fixture = fixture(image, image);
        assertLargerCrcValidHeader(image);
        // The unrestricted codec/raw verifier keep their existing corruption diagnostics.
        assertEquals(SegmentFormatException.Code.CORRUPT,
                assertThrows(SegmentFormatException.class, () -> SegmentCodec.decode(image, fixture.segmentId())).code());
        assertEquals(SegmentFormatException.Code.CORRUPT, assertThrows(SegmentFormatException.class,
                () -> CheckpointVerifier.verify(fixture.manifest(), ignored -> image)).code());
        var before = fixture.namespace().snapshot();
        try (var store = new LocalCheckpointStore(fixture.namespace())) {
            var failure = assertThrows(SegmentFormatException.class,
                    () -> operate(store, fixture.journal(), fixture.limits(), removal));
            assertEquals(SegmentFormatException.Code.INVALID_HISTORY, failure.code(),
                    "The budgeted reference range must fail before malformed nested content");
            assertEquals(List.of(new ReadCall(fixture.segmentName(), image.length, image.length)),
                    fixture.namespace().reads.stream().filter(read -> read.name().equals(fixture.segmentName())).toList());
            assertEquals(fixture.limits().maxBytes(), fixture.namespace().reads.stream().mapToLong(ReadCall::returnedBytes).sum());
            assertEquals(List.of(0L), fixture.journal().prefixCalls,
                    "Only the preceding empty genesis is checked; the refused graph never reaches its owner prefix");
            assertRefusedWithoutEffects(store, fixture, before, removal);
        }
        assertEquals(1, fixture.namespace().closes);
        assertEquals(1, fixture.namespace().protection.releases, "Known cleanup is idempotent during store close");
        assertImages(before, fixture.namespace().snapshot());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void oversizedStoredFileCannotExpandAnExactDeclaredSegmentReadBudget(boolean removal) throws IOException {
        byte[] declared = SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(FIRST)));
        byte[] stored = Arrays.copyOf(declared, declared.length + 1);
        stored[stored.length - 1] = 7;
        var fixture = fixture(declared, stored);
        var before = fixture.namespace().snapshot();
        try (var store = new LocalCheckpointStore(fixture.namespace())) {
            var failure = assertThrows(IOException.class, () -> operate(store, fixture.journal(), fixture.limits(), removal));
            assertTrue(failure.getMessage().startsWith("Stored image exceeds admitted read bound:"));
            assertEquals(List.of(new ReadCall(fixture.segmentName(), declared.length, 0)),
                    fixture.namespace().reads.stream().filter(read -> read.name().equals(fixture.segmentName())).toList());
            assertTrue(fixture.namespace().reads.stream().allMatch(read -> read.returnedBytes() <= read.maxBytes()));
            assertEquals(List.of(0L), fixture.journal().prefixCalls);
            assertRefusedWithoutEffects(store, fixture, before, removal);
        }
        assertImages(before, fixture.namespace().snapshot());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void realRefusalReleasesProtectionAndReopenPreservesEveryImageWithoutFallbackOrRepair(boolean removal)
            throws IOException {
        Path home = Files.createDirectory(directory.resolve("checkpoint"));
        try (var store = supportedStore(home); var journal = store.initialize(DATABASE)) {
            journal.append(FIRST);
            journal.append(SECOND);
            journal.force();
        }
        byte[] segment = oversizedHistory("nested-record");
        var id = new SegmentId(hash(segment));
        var invalid = manifest(2, segment, id);
        byte[] manifestImage = ManifestCodec.encode(invalid);
        Files.write(home.resolve(LocalCheckpointStore.segmentName(id)), segment);
        Files.write(home.resolve(name(invalid)), manifestImage);
        var before = snapshot(home); // Read raw journal/control images only after their owners close.
        long budget = before.entrySet().stream().filter(entry -> entry.getKey().startsWith("checkpoint-"))
                .mapToLong(entry -> entry.getValue().length).sum() + segment.length;
        var limits = new ReclamationInventory.Limits(budget, 1);
        try (var store = supportedStore(home); var journal = LocalJournal.open(store.journalPath())) {
            var failure = assertThrows(SegmentFormatException.class, () -> operate(store, journal, limits, removal));
            assertEquals(SegmentFormatException.Code.INVALID_HISTORY, failure.code());
            assertThrows(IOException.class, store::journalPath);
            // Check release before closing the poisoned publisher, not merely after owner cleanup.
            try (var protection = LocalReaderBarrier.acquire(home, true).orElseThrow()) { protection.checkValid(); }
        }
        var after = snapshot(home);
        assertOnlyGuardMayHaveBeenAdded(before, after);
        try (var store = supportedStore(home); var journal = LocalJournal.open(store.journalPath())) {
            var failure = assertThrows(SegmentFormatException.class, () -> operate(store, journal, limits, removal));
            assertEquals(SegmentFormatException.Code.INVALID_HISTORY, failure.code(),
                    "Reopen keeps the same invalid newest root instead of adopting an older generation");
            assertThrows(IOException.class, store::journalPath);
            try (var protection = LocalReaderBarrier.acquire(home, true).orElseThrow()) { protection.checkValid(); }
        }
        assertImages(after, snapshot(home));
        assertArrayEquals(segment, after.get(LocalCheckpointStore.segmentName(id)));
        assertArrayEquals(manifestImage, after.get(name(invalid)));
    }

    private static void operate(LocalCheckpointStore store, LocalCheckpointStore.JournalAccess journal,
                                ReclamationInventory.Limits limits, boolean removal) throws IOException {
        if (removal) { store.reclaim(Set.of(), journal, limits); }
        else { store.inspectReclamation(Set.of(), journal, limits); }
    }

    private static void operate(LocalCheckpointStore store, LocalJournal journal,
                                ReclamationInventory.Limits limits, boolean removal) throws IOException {
        if (removal) { store.reclaim(Set.of(), journal, limits); }
        else { store.inspectReclamation(Set.of(), journal, limits); }
    }

    private static void assertRefusedWithoutEffects(LocalCheckpointStore store, Fixture fixture,
                                                    Map<String, byte[]> before, boolean removal) throws IOException {
        assertFalse(fixture.namespace().protection.held, "Refusal releases exclusive reader protection");
        assertEquals(1, fixture.namespace().protection.closeCalls);
        assertEquals(1, fixture.namespace().protection.releases);
        assertEquals(0, fixture.namespace().mutations);
        assertEquals(0, fixture.journal().forces);
        assertImages(before, fixture.namespace().snapshot());
        int reads = fixture.namespace().reads.size();
        int barriers = fixture.namespace().barrierAdmissions;
        assertThrows(IOException.class, store::journalPath);
        assertThrows(IOException.class, () -> operate(store, fixture.journal(), fixture.limits(), removal));
        assertEquals(reads, fixture.namespace().reads.size(), "A poisoned owner admits no more object reads");
        assertEquals(barriers, fixture.namespace().barrierAdmissions);
    }

    private Fixture fixture(byte[] declared, byte[] stored) {
        var namespace = new MemoryNamespace(directory);
        namespace.files.put(LocalCheckpointStore.LOCK, new byte[0]);
        namespace.files.put(LocalReaderBarrier.NAME, new byte[0]);
        namespace.files.put(LocalCheckpointStore.JOURNAL, journalImage(2));
        namespace.files.put("operator-evidence", new byte[] {7, 3});
        var genesisPrefix = prefix(0);
        var genesis = new CheckpointManifest(DATABASE, 1,
                new CheckpointManifest.JournalPrefix(0, genesisPrefix.byteLength(), genesisPrefix.sha256()), List.of());
        namespace.files.put(name(genesis), ManifestCodec.encode(genesis));
        var id = new SegmentId(hash(declared));
        var root = manifest(2, declared, id);
        byte[] metadata = ManifestCodec.encode(root);
        namespace.files.put(name(root), metadata);
        String segmentName = LocalCheckpointStore.segmentName(id);
        namespace.files.put(segmentName, stored.clone());
        long budget = ManifestCodec.encode(genesis).length + metadata.length + declared.length;
        return new Fixture(namespace, new MemoryJournal(), root, id, segmentName, new ReclamationInventory.Limits(budget, 1));
    }

    private record Fixture(MemoryNamespace namespace, MemoryJournal journal, CheckpointManifest manifest,
                           SegmentId segmentId, String segmentName, ReclamationInventory.Limits limits) { }

    private static CheckpointManifest manifest(long generation, byte[] image, SegmentId id) {
        var actual = prefix(1);
        return new CheckpointManifest(DATABASE, generation,
                new CheckpointManifest.JournalPrefix(1, actual.byteLength(), actual.sha256()), List.of(
                new CheckpointManifest.SegmentReference(id, image.length, 1, 1)));
    }

    private static byte[] oversizedHistory(String damage) {
        byte[] image = SegmentCodec.encode(new ImmutableSegment(DATABASE, List.of(FIRST, SECOND)));
        if (damage.equals("nested-record")) {
            int headerBytes = 48 + ByteBuffer.wrap(image).getInt(12);
            image[headerBytes] ^= 1; // Break nested transaction magic, preserving the checked header.
            byte[] footer = digest(Arrays.copyOf(image, image.length - 32));
            System.arraycopy(footer, 0, image, image.length - footer.length, footer.length);
        } else if (damage.equals("footer")) { image[image.length - 1] ^= 1; }
        else { throw new AssertionError(damage); }
        return image;
    }

    private static void assertLargerCrcValidHeader(byte[] image) {
        var input = ByteBuffer.wrap(image);
        int lineageBytes = input.getInt(12);
        int rangeOffset = 16 + lineageBytes;
        assertEquals(1, input.getLong(rangeOffset));
        assertEquals(2, input.getLong(rangeOffset + 8));
        assertEquals(2, input.getInt(rangeOffset + 16));
        int checksumOffset = 44 + lineageBytes;
        var crc = new CRC32C();
        crc.update(image, 0, checksumOffset);
        assertEquals((int) crc.getValue(), input.getInt(checksumOffset));
    }

    private static JournalRecord record(long sequence) {
        return new JournalRecord(sequence, new Transaction(new RequestId("request-" + sequence), List.of(
                new WriteOperation.Put(new EntityId("balance"), new ValidTimeInterval(null, null), sequence))));
    }

    private static byte[] journalImage(long sequence) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(JournalCodec.encodeHeader(DATABASE));
        if (sequence >= 1) { bytes.writeBytes(JournalCodec.encodeRecord(FIRST)); }
        if (sequence >= 2) { bytes.writeBytes(JournalCodec.encodeRecord(SECOND)); }
        return bytes.toByteArray();
    }

    private static LocalJournal.Prefix prefix(long sequence) {
        byte[] image = journalImage(sequence);
        return new LocalJournal.Prefix(sequence, image.length, hash(image));
    }

    private static byte[] digest(byte[] image) {
        try { return MessageDigest.getInstance("SHA-256").digest(image); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    private static String hash(byte[] image) { return HexFormat.of().formatHex(digest(image)); }

    private static String name(CheckpointManifest manifest) {
        var id = new ManifestId(hash(ManifestCodec.encode(manifest)));
        return LocalCheckpointStore.generationName(manifest.generation(), id);
    }

    private static LocalCheckpointStore supportedStore(Path home) throws IOException {
        try { return LocalCheckpointStore.open(home); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force unavailable: " + unsupported);
            throw unsupported;
        }
    }

    private static Map<String, byte[]> snapshot(Path home) throws IOException {
        var images = new LinkedHashMap<String, byte[]>();
        try (var files = Files.list(home)) {
            for (var file : files.sorted().toList()) { images.put(file.getFileName().toString(), Files.readAllBytes(file)); }
        }
        return images;
    }

    private static void assertOnlyGuardMayHaveBeenAdded(Map<String, byte[]> before, Map<String, byte[]> after) {
        var names = new java.util.HashSet<>(before.keySet());
        if (after.containsKey(LocalReaderBarrier.NAME)) {
            names.add(LocalReaderBarrier.NAME);
            assertEquals(0, after.get(LocalReaderBarrier.NAME).length);
        }
        assertEquals(names, after.keySet());
        before.forEach((name, image) -> assertArrayEquals(image, after.get(name), name));
    }

    private static void assertImages(Map<String, byte[]> before, Map<String, byte[]> after) {
        assertEquals(before.keySet(), after.keySet());
        before.forEach((name, image) -> assertArrayEquals(image, after.get(name), name));
    }

    private record ReadCall(String name, int maxBytes, int returnedBytes) { }

    private static final class MemoryJournal implements LocalCheckpointStore.JournalAccess {
        final List<Long> prefixCalls = new ArrayList<>();
        int forces;
        @Override public DatabaseId databaseId() { return DATABASE; }
        @Override public LocalJournal.Prefix prefix(long sequence) {
            prefixCalls.add(sequence);
            if (sequence != 0) { throw new AssertionError("Refused graph reached the actual owner prefix"); }
            return LocalBoundedSegmentPreflightTest.prefix(sequence);
        }
        @Override public void force() { forces++; throw new AssertionError("Refusal must not force the journal"); }
    }

    private static final class MemoryProtection implements LocalReaderBarrier.Protection {
        boolean held = true;
        int closeCalls;
        int releases;
        @Override public void checkValid() {
            if (!held) { throw new AssertionError("Protection was already released"); }
        }
        @Override public void close() {
            closeCalls++;
            if (held) { held = false; releases++; }
        }
    }

    private static final class MemoryNamespace implements LocalCheckpointStore.Namespace {
        final Path directory;
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final List<ReadCall> reads = new ArrayList<>();
        final Set<String> checkedAliases = new java.util.HashSet<>();
        final MemoryProtection protection = new MemoryProtection();
        int barrierAdmissions;
        int mutations;
        int closes;
        MemoryNamespace(Path directory) { this.directory = directory; }
        Map<String, byte[]> snapshot() {
            var images = new LinkedHashMap<String, byte[]>();
            files.forEach((name, image) -> images.put(name, image.clone()));
            return images;
        }
        @Override public Path directory() { return directory; }
        @Override public LocalReaderBarrier.Protection acquireReader() { throw new AssertionError("Expected exclusive inventory protection"); }
        @Override public Optional<LocalReaderBarrier.Protection> tryReclamationBarrier() {
            barrierAdmissions++;
            return Optional.of(protection);
        }
        @Override public List<String> entries() { return new ArrayList<>(files.keySet()); }
        @Override public void checkObjectAliases(String name, List<String> entries) {
            assertTrue(entries.contains(name));
            checkedAliases.add(name);
        }
        @Override public byte[] read(String name, int maxBytes) throws IOException {
            assertTrue(protection.held);
            assertTrue(checkedAliases.contains(name), "Alias preflight must precede object read");
            byte[] image = files.get(name);
            if (image == null) { throw new IOException("Missing image: " + name); }
            if (image.length > maxBytes) {
                reads.add(new ReadCall(name, maxBytes, 0));
                throw new IOException("Stored image exceeds admitted read bound: " + name);
            }
            reads.add(new ReadCall(name, maxBytes, image.length));
            return image.clone();
        }
        private void mutate() { mutations++; throw new AssertionError("Bounded refusal must precede mutation or force"); }
        @Override public void writeNew(String name, byte[] image) { mutate(); }
        @Override public void forceFile(String name) { mutate(); }
        @Override public void atomicMove(String from, String to) { mutate(); }
        @Override public void delete(String name) { mutate(); }
        @Override public void forceDirectory() { mutate(); }
        @Override public void close() { closes++; }
    }
}
