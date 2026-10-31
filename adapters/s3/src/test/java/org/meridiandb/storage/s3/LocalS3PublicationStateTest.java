// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest.JournalPrefix;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.s3.S3PublicationState.Operation;
import org.meridiandb.storage.s3.S3PublicationState.Target;
import org.meridiandb.storage.s3.S3PublicationStateException.Code;
import org.meridiandb.temporal.DatabaseId;

class LocalS3PublicationStateTest {
    private static final String LOCK = ".meridiandb-s3-publication.lock";
    private static final URI ENDPOINT = URI.create("https://example.test");
    private static final Target TARGET = new Target(ENDPOINT, "us-east-1", "bucket");
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final UUID OTHER = new UUID(0, 99);
    private static final ObjectId LINEAGE = new ObjectId(
            "2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c");
    private static final S3CheckpointHeads.Binding BINDING = new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, LINEAGE);
    private static final ObjectReference MANIFEST = new ObjectReference(new ObjectId(
            "bd068a065754e52824efaf91c44bc7234985e9a93c024dcb90e6951152e66982"), 136);
    private static final ObjectCheckpointHead FIRST = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 3),
            LINEAGE, 1, MANIFEST, new JournalPrefix(0, 24, LINEAGE.value()), null);
    private static final byte[] FIRST_IMAGE = ObjectCheckpointHeadCodec.encode(FIRST);
    private static final ObjectCheckpointHead SECOND = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 4),
            LINEAGE, 2, MANIFEST, FIRST.journalPrefix(), ObjectCheckpointHeadCodec.reference(FIRST));
    private static final byte[] SECOND_IMAGE = ObjectCheckpointHeadCodec.encode(SECOND);
    private static final String ORIGINAL_TOKEN = "\"original,opaque\\token\"";

    @TempDir Path directory;

    @Test void createAndReopenPreserveTheExactInitialSnapshotAndContentAddressedName() throws IOException {
        Path home = newHome("publication");
        S3PublicationState first;
        try (var store = create(home)) {
            first = store.state();
            assertEquals(1, first.generation());
            assertTrue(first.expected().isEmpty());
            assertTrue(first.pending().isEmpty());
            assertEquals(Set.of(LOCK, selectedName(first)), names(home));
        }
        assertArrayEquals(S3PublicationStateCodec.encode(first), Files.readAllBytes(home.resolve(selectedName(first))));
        try (var reopened = open(home)) { assertState(first, reopened.state()); }
        assertEquals(Set.of(LOCK, selectedName(first)), names(home));
    }

    @Test void reopenPreservesPendingCreateWithoutResettingOrInventingRemoteSelection() throws IOException {
        Path home = newHome("publication");
        S3PublicationState prepared;
        try (var store = create(home)) {
            byte[] supplied = FIRST_IMAGE.clone();
            prepared = store.prepare(supplied);
            supplied[0] ^= 1;
            assertEquals(2, prepared.generation());
            assertIntent(prepared, Operation.CREATE_IF_ABSENT, "*", FIRST_IMAGE);
            assertTrue(prepared.expected().isEmpty());
        }
        try (var reopened = open(home)) {
            assertState(prepared, reopened.state());
            assertIntent(reopened.state(), Operation.CREATE_IF_ABSENT, "*", FIRST_IMAGE);
            expectState(Code.INVALID_TRANSITION, () -> reopened.prepare(FIRST_IMAGE));
            assertEquals(2, reopened.state().generation());
        }
        assertEquals(2, selectedNames(home).size());
    }

    @Test void exactSelectedRecognitionAndReplacementSurviveSeparateReopens() throws IOException {
        Path home = newHome("publication");
        try (var store = create(home)) { store.prepare(FIRST_IMAGE); }
        S3PublicationState expected;
        try (var reopened = open(home)) {
            expected = reopened.recordSelected(observation(FIRST_IMAGE, ORIGINAL_TOKEN));
            assertEquals(3, expected.generation());
            assertTrue(expected.pending().isEmpty());
        }
        S3PublicationState pending;
        try (var reopened = open(home)) {
            assertState(expected, reopened.state());
            pending = reopened.prepare(SECOND_IMAGE);
            assertEquals(4, pending.generation());
            assertIntent(pending, Operation.REPLACE_IF_MATCH, ORIGINAL_TOKEN, SECOND_IMAGE);
        }
        try (var reopened = open(home)) {
            assertState(pending, reopened.state());
            expectState(Code.INVALID_TRANSITION,
                    () -> reopened.recordSelected(observation(FIRST_IMAGE, "\"fresh-old-head-token\"")));
            assertState(pending, reopened.state());
            var done = reopened.recordSelected(observation(SECOND_IMAGE, "\"second-token\""));
            assertEquals(5, done.generation());
            assertTrue(done.pending().isEmpty());
            assertArrayEquals(SECOND_IMAGE, done.expected().orElseThrow().image());
        }
        try (var reopened = open(home)) { assertEquals(5, reopened.state().generation()); }
        assertEquals(5, selectedNames(home).size());
    }

    @Test void pureTransitionErrorsLeaveTheOwnerUsableAndCreateNoGeneration() throws IOException {
        Path home = newHome("publication");
        try (var store = create(home)) {
            Set<String> initial = names(home);
            expectState(Code.INVALID_TRANSITION, () -> store.recordSelected(observation(FIRST_IMAGE, ORIGINAL_TOKEN)));
            assertThrows(IllegalArgumentException.class, () -> store.prepare(new byte[255]));
            assertEquals(initial, names(home));
            assertEquals(1, store.state().generation());
            var pending = store.prepare(FIRST_IMAGE);
            Set<String> before = names(home);
            expectState(Code.INVALID_TRANSITION, () -> store.prepare(SECOND_IMAGE));
            expectState(Code.INVALID_TRANSITION, () -> store.recordSelected(observation(SECOND_IMAGE, "\"competing\"")));
            assertEquals(before, names(home));
            assertState(pending, store.state());
            assertEquals(3, store.recordSelected(observation(FIRST_IMAGE, ORIGINAL_TOKEN)).generation());
        }
    }

    @Test void secondOwnerAttemptsPreserveTheFirstOwnerAndReleaseOnlyAfterClose() throws IOException {
        Path home = newHome("publication");
        try (var store = create(home)) {
            expectIo(() -> openAndClose(home, TARGET, BINDING));
            expectIo(() -> createAndClose(home, TARGET, BINDING));
            assertEquals(1, store.state().generation());
            assertEquals(2, store.prepare(FIRST_IMAGE).generation());
        }
        try (var reopened = open(home)) { assertIntent(reopened.state(), Operation.CREATE_IF_ABSENT, "*", FIRST_IMAGE); }
    }

    @Test void closeIsIdempotentAndAllOperationsRejectTheClosedOwner() throws IOException {
        Path home = newHome("publication");
        var store = create(home);
        try {
            store.close();
            store.close();
            expectIo(store::state);
            expectIo(() -> store.prepare(FIRST_IMAGE));
            expectIo(() -> store.recordSelected(observation(FIRST_IMAGE, ORIGINAL_TOKEN)));
        } finally { store.close(); }
        try (var reopened = open(home)) { assertEquals(1, reopened.state().generation()); }
    }

    @Test void exactConfigurationMismatchOnOpenCleansUpWithoutAdoptingTheNewScope() throws IOException {
        Path home = newHome("publication");
        try (var store = create(home)) { assertEquals(1, store.state().generation()); }
        Set<String> original = names(home);
        for (var target : List.of(new Target(URI.create("https://EXAMPLE.test"), "us-east-1", "bucket"),
                new Target(ENDPOINT, "us-west-2", "bucket"), new Target(ENDPOINT, "us-east-1", "other-bucket"))) {
            expectState(Code.INVALID_BINDING, () -> openAndClose(home, target, BINDING));
            try (var reopened = open(home)) { assertEquals(TARGET, reopened.state().target()); }
        }
        for (var binding : List.of(new S3CheckpointHeads.Binding(OTHER, PUBLISHER, LINEAGE),
                new S3CheckpointHeads.Binding(NAMESPACE, OTHER, LINEAGE),
                new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, new ObjectId("0".repeat(64))))) {
            expectState(Code.INVALID_BINDING, () -> openAndClose(home, TARGET, binding));
            try (var reopened = open(home)) { assertEquals(BINDING, reopened.state().binding()); }
        }
        assertEquals(original, names(home));
    }

    @Test void createRequiresAnExistingTrulyEmptyDirectoryAndOpenCreatesNoMissingState() throws IOException {
        Path missing = directory.resolve("missing");
        expectIo(() -> createAndClose(missing, TARGET, BINDING));
        expectIo(() -> openAndClose(missing, TARGET, BINDING));
        assertFalse(Files.exists(missing));
        Path file = Files.write(directory.resolve("ordinary-file"), new byte[] {9});
        expectIo(() -> createAndClose(file, TARGET, BINDING));
        expectIo(() -> openAndClose(file, TARGET, BINDING));
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(file));
        Path nonempty = newHome("nonempty");
        Path sentinel = Files.write(nonempty.resolve("sentinel.bin"), new byte[] {1, 2, 3});
        expectIo(() -> createAndClose(nonempty, TARGET, BINDING));
        assertEquals(Set.of("sentinel.bin"), names(nonempty));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(sentinel));
        Path empty = newHome("empty");
        expectIo(() -> openAndClose(empty, TARGET, BINDING));
        assertTrue(names(empty).isEmpty());
    }

    @Test void invalidPureConfigurationFailsBeforeAnyPathEffects() throws IOException {
        Path home = newHome("publication");
        assertThrows(NullPointerException.class, () -> LocalS3PublicationState.create(home, null, BINDING));
        assertThrows(NullPointerException.class, () -> LocalS3PublicationState.create(home, TARGET, null));
        assertTrue(names(home).isEmpty());
    }

    @Test void finalSymlinkDirectoryIsRejectedWithoutFollowingIt() throws IOException {
        Path target = newHome("target");
        Path alias = directory.resolve("alias");
        symbolicLink(alias, target);
        expectIo(() -> createAndClose(alias, TARGET, BINDING));
        expectIo(() -> openAndClose(alias, TARGET, BINDING));
        assertTrue(Files.isSymbolicLink(alias));
        assertTrue(names(target).isEmpty());
    }

    @Test void missingSelectedStateWithLockEvidenceCannotBeResetByCreateOrOpen() throws IOException {
        Path home = initialized();
        for (String name : selectedNames(home)) { Files.delete(home.resolve(name)); }
        String pending = "pending-publication-1-00000000-0000-0000-0000-000000000077.state";
        byte[] candidate = S3PublicationStateCodec.encode(S3PublicationState.fresh(TARGET, BINDING));
        Files.write(home.resolve(pending), candidate);
        Set<String> before = names(home);
        expectIo(() -> openAndClose(home, TARGET, BINDING));
        expectIo(() -> createAndClose(home, TARGET, BINDING));
        assertEquals(before, names(home));
        assertArrayEquals(candidate, Files.readAllBytes(home.resolve(pending)));
    }

    @Test void aMissingOwnershipFileDoesNotTriggerAResetOrRecreation() throws IOException {
        Path home = initialized();
        Files.delete(home.resolve(LOCK));
        Set<String> before = names(home);
        expectIo(() -> openAndClose(home, TARGET, BINDING));
        expectIo(() -> createAndClose(home, TARGET, BINDING));
        assertEquals(before, names(home));
        assertFalse(Files.exists(home.resolve(LOCK), LinkOption.NOFOLLOW_LINKS));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void partialOrEmptyNewestStateFailsClosedInsteadOfUsingTheOldGeneration(boolean empty) throws IOException {
        Path home = initialized();
        var next = S3PublicationState.fresh(TARGET, BINDING).prepare(FIRST_IMAGE);
        byte[] complete = S3PublicationStateCodec.encode(next);
        byte[] truncated = Arrays.copyOf(complete, empty ? 0 : complete.length / 2);
        Path newest = home.resolve(selectedName(next));
        Files.write(newest, truncated);
        Set<String> before = names(home);
        expectState(Code.INCOMPLETE, () -> openAndClose(home, TARGET, BINDING));
        assertEquals(before, names(home));
        assertArrayEquals(truncated, Files.readAllBytes(newest));
        Files.write(newest, complete);
        try (var reopened = open(home)) { assertState(next, reopened.state()); }
    }

    @Test void corruptNewestStatePreservesAllEvidenceAndDoesNotFallBack() throws IOException {
        Path home = initialized();
        var next = S3PublicationState.fresh(TARGET, BINDING).prepare(FIRST_IMAGE);
        byte[] damaged = S3PublicationStateCodec.encode(next);
        damaged[damaged.length - 1] ^= 1;
        Path newest = home.resolve(selectedName(next));
        Files.write(newest, damaged);
        Set<String> before = names(home);
        expectState(Code.CORRUPT, () -> openAndClose(home, TARGET, BINDING));
        assertEquals(before, names(home));
        assertArrayEquals(damaged, Files.readAllBytes(newest));
    }

    @Test void anOlderDamagedImageIsRetainedWithoutReplacingTheVerifiedNewestState() throws IOException {
        Path home = newHome("publication");
        String older;
        S3PublicationState pending;
        try (var store = create(home)) {
            older = selectedName(store.state());
            pending = store.prepare(FIRST_IMAGE);
        }
        byte[] evidence = {1, 5, 9};
        Files.write(home.resolve(older), evidence);
        Set<String> before = names(home);
        try (var reopened = open(home)) { assertState(pending, reopened.state()); }
        assertEquals(before, names(home));
        assertArrayEquals(evidence, Files.readAllBytes(home.resolve(older)));
    }

    @Test void selectedFilenameRequiresTheWholeImageIdentifier() throws IOException {
        Path home = initialized();
        var state = S3PublicationState.fresh(TARGET, BINDING);
        Path original = home.resolve(selectedName(state));
        Path wrong = home.resolve(selectedName(1, "0".repeat(64)));
        Files.move(original, wrong);
        expectState(Code.REFERENCE_MISMATCH, () -> openAndClose(home, TARGET, BINDING));
        assertTrue(Files.exists(wrong));
        Files.move(wrong, original);
        try (var reopened = open(home)) { assertEquals(1, reopened.state().generation()); }
    }

    @Test void selectedFilenameGenerationMustMatchTheDecodedState() throws IOException {
        Path home = initialized();
        var state = S3PublicationState.fresh(TARGET, BINDING);
        byte[] image = S3PublicationStateCodec.encode(state);
        Path wrong = home.resolve(selectedName(2, hash(image)));
        Files.write(wrong, image);
        Set<String> before = names(home);
        expectIo(() -> openAndClose(home, TARGET, BINDING));
        assertEquals(before, names(home));
        assertArrayEquals(image, Files.readAllBytes(wrong));
    }

    @Test void duplicatePhysicalGenerationIsRejectedEvenWhenBothImagesAreValid() throws IOException {
        Path home = initialized();
        var another = S3PublicationState.restore(1, TARGET, BINDING, observation(FIRST_IMAGE, ORIGINAL_TOKEN), null);
        Files.write(home.resolve(selectedName(another)), S3PublicationStateCodec.encode(another));
        Set<String> before = names(home);
        assertEquals(2, selectedNames(home).size());
        expectIo(() -> openAndClose(home, TARGET, BINDING));
        assertEquals(before, names(home));
    }

    @ParameterizedTest @ValueSource(strings = {"publication-1-bad.state",
            "publication-0000000000000000000-0000000000000000000000000000000000000000000000000000000000000000.state",
            "publication-9999999999999999999-0000000000000000000000000000000000000000000000000000000000000000.state",
            "publication-0000000000000000002-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA.state"})
    void malformedReservedSelectedNamesPreventFallback(String malformed) throws IOException {
        Path home = initialized();
        Files.write(home.resolve(malformed), new byte[] {7});
        Set<String> before = names(home);
        expectIo(() -> openAndClose(home, TARGET, BINDING));
        assertEquals(before, names(home));
        assertArrayEquals(new byte[] {7}, Files.readAllBytes(home.resolve(malformed)));
    }

    @Test void oversizedNewestImageIsRejectedBeforeDecoding() throws IOException {
        Path home = initialized();
        byte[] image = new byte[S3PublicationStateCodec.MAX_BYTES + 1];
        Path newest = home.resolve(selectedName(2, hash(image)));
        Files.write(newest, image);
        expectIo(() -> openAndClose(home, TARGET, BINDING));
        assertEquals(image.length, Files.size(newest));
        assertEquals(2, selectedNames(home).size());
    }

    @Test void pendingAndUnknownEntriesRemainUnselectedAndRetainedAcrossAnUpdate() throws IOException {
        Path home = initialized();
        String pendingName = "pending-publication-99-00000000-0000-0000-0000-000000000077.state";
        byte[] partial = {1, 4, 9};
        byte[] notes = {2, 7, 1, 8};
        Files.write(home.resolve(pendingName), partial);
        Files.write(home.resolve("notes.bin"), notes);
        Files.createDirectory(home.resolve("unknown-directory"));
        Set<String> before = names(home);
        S3PublicationState next;
        try (var reopened = open(home)) {
            assertEquals(1, reopened.state().generation());
            next = reopened.prepare(FIRST_IMAGE);
        }
        var expected = new java.util.HashSet<>(before);
        expected.add(selectedName(next));
        assertEquals(expected, names(home));
        assertArrayEquals(partial, Files.readAllBytes(home.resolve(pendingName)));
        assertArrayEquals(notes, Files.readAllBytes(home.resolve("notes.bin")));
        assertTrue(Files.isDirectory(home.resolve("unknown-directory")));
    }

    @Test void ownershipFileMustRemainARegularNonSymlinkFile() throws IOException {
        Path home = initialized();
        Files.delete(home.resolve(LOCK));
        Files.createDirectory(home.resolve(LOCK));
        expectIo(() -> openAndClose(home, TARGET, BINDING));
        assertTrue(Files.isDirectory(home.resolve(LOCK), LinkOption.NOFOLLOW_LINKS));
        Files.delete(home.resolve(LOCK));
        Path outside = Files.write(directory.resolve("outside-lock"), new byte[] {3});
        symbolicLink(home.resolve(LOCK), outside);
        expectIo(() -> openAndClose(home, TARGET, BINDING));
        assertTrue(Files.isSymbolicLink(home.resolve(LOCK)));
        assertArrayEquals(new byte[] {3}, Files.readAllBytes(outside));
    }

    @Test void selectedStateMustNotFollowASymbolicLink() throws IOException {
        Path home = initialized();
        String selected = selectedNames(home).getFirst();
        Path outside = directory.resolve("outside-state");
        Files.move(home.resolve(selected), outside);
        symbolicLink(home.resolve(selected), outside);
        expectIo(() -> openAndClose(home, TARGET, BINDING));
        assertTrue(Files.isSymbolicLink(home.resolve(selected)));
        assertArrayEquals(S3PublicationStateCodec.encode(S3PublicationState.fresh(TARGET, BINDING)), Files.readAllBytes(outside));
    }

    @Test void selectedStateCannotHardLinkItsOwnershipFile() throws IOException {
        Path home = initialized();
        String selected = selectedNames(home).getFirst();
        Files.delete(home.resolve(selected));
        hardLink(home.resolve(selected), home.resolve(LOCK));
        expectIo(() -> openAndClose(home, TARGET, BINDING));
        assertTrue(Files.isSameFile(home.resolve(selected), home.resolve(LOCK)));
    }

    @Test void publicationControlAliasCannotOpenOverAnOwnedLocalJournal() throws IOException {
        Path home = initialized();
        Path journalPath = directory.resolve("journal");
        try (var journal = LocalJournal.create(journalPath, new DatabaseId("db"))) { assertEquals(0, journal.appendedSequence()); }
        Files.delete(home.resolve(LOCK));
        hardLink(home.resolve(LOCK), journalPath);
        try (var journal = LocalJournal.open(journalPath)) {
            expectIo(() -> openAndClose(home, TARGET, BINDING));
            assertEquals(0, journal.prefix(0).sequence());
            assertEquals(0, journal.force());
            expectIo(() -> { try (var duplicate = LocalJournal.open(journalPath)) { duplicate.prefix(0); } });
        }
        assertTrue(Files.isSameFile(home.resolve(LOCK), journalPath));
    }

    @Test void journalAliasCannotOpenOverAnOwnedPublicationControl() throws IOException {
        Path home = initialized();
        Path alias = directory.resolve("journal-alias");
        hardLink(alias, home.resolve(LOCK));
        try (var store = open(home)) {
            expectIo(() -> { try (var journal = LocalJournal.open(alias)) { journal.prefix(0); } });
            assertEquals(1, store.state().generation());
            assertEquals(2, store.prepare(FIRST_IMAGE).generation());
            expectIo(() -> openAndClose(home, TARGET, BINDING));
        }
        try (var reopened = open(home)) { assertEquals(2, reopened.state().generation()); }
    }

    @Test void selectedStateAliasIsRejectedBeforeReadingAnOwnedJournalAsMetadata() throws IOException {
        Path home = initialized();
        Path journalPath = directory.resolve("journal");
        try (var journal = LocalJournal.create(journalPath, new DatabaseId("db"))) { assertEquals(0, journal.appendedSequence()); }
        Path selected = home.resolve(selectedNames(home).getFirst());
        Files.delete(selected);
        hardLink(selected, journalPath);
        try (var journal = LocalJournal.open(journalPath)) {
            expectIo(() -> openAndClose(home, TARGET, BINDING));
            assertEquals(0, journal.prefix(0).sequence());
            assertEquals(0, journal.force());
        }
        assertTrue(Files.isSameFile(selected, journalPath));
    }

    @Test void publicationControlAliasCannotOpenOverAnOwnedCheckpointControl() throws IOException {
        Path home = initialized();
        Path checkpointHome = newHome("checkpoints");
        try (var checkpoints = checkpointStore(checkpointHome)) { checkpoints.journalPath(); }
        Path checkpointLock = checkpointHome.resolve(".meridiandb-checkpoints.lock");
        Files.delete(home.resolve(LOCK));
        hardLink(home.resolve(LOCK), checkpointLock);
        try (var checkpoints = checkpointStore(checkpointHome)) {
            expectIo(() -> openAndClose(home, TARGET, BINDING));
            assertEquals(checkpointHome.toRealPath().resolve("transactions.journal"), checkpoints.journalPath());
            expectIo(() -> { try (var duplicate = LocalCheckpointStore.open(checkpointHome)) { duplicate.journalPath(); } });
        }
        assertTrue(Files.isSameFile(home.resolve(LOCK), checkpointLock));
    }

    @Test void generationCapacityRejectsBeforeCreatingACandidateAndReopenPreservesHighestState() throws IOException {
        Path home = initialized();
        for (int generation = 2; generation <= LocalS3PublicationState.MAX_GENERATIONS; generation++) {
            var state = S3PublicationState.restore(generation, TARGET, BINDING, null, null);
            Files.write(home.resolve(selectedName(state)), S3PublicationStateCodec.encode(state), StandardOpenOption.CREATE_NEW);
        }
        Set<String> before = names(home);
        try (var store = open(home)) {
            assertEquals(LocalS3PublicationState.MAX_GENERATIONS, store.state().generation());
            expectIo(() -> store.prepare(FIRST_IMAGE));
            assertEquals(before, names(home));
            expectIo(store::state);
        }
        try (var reopened = open(home)) { assertEquals(LocalS3PublicationState.MAX_GENERATIONS, reopened.state().generation()); }
        var tooMany = S3PublicationState.restore(LocalS3PublicationState.MAX_GENERATIONS + 1L, TARGET, BINDING, null, null);
        Files.write(home.resolve(selectedName(tooMany)), S3PublicationStateCodec.encode(tooMany), StandardOpenOption.CREATE_NEW);
        Set<String> over = names(home);
        expectIo(() -> openAndClose(home, TARGET, BINDING));
        assertEquals(over, names(home));
    }

    @Test void directoryCapacityRejectsBeforeEffectsAndCountsEveryUnknownEntry() throws IOException {
        Path home = initialized();
        int initial = names(home).size();
        for (int index = 0; index < LocalS3PublicationState.MAX_DIRECTORY_ENTRIES - initial; index++) {
            Files.createFile(home.resolve(String.format(Locale.ROOT, "unknown-%05d", index)));
        }
        Set<String> before = names(home);
        assertEquals(LocalS3PublicationState.MAX_DIRECTORY_ENTRIES, before.size());
        try (var store = open(home)) {
            expectIo(() -> store.prepare(FIRST_IMAGE));
            assertEquals(before, names(home));
            expectIo(store::state);
        }
        try (var reopened = open(home)) { assertEquals(1, reopened.state().generation()); }
        Files.createFile(home.resolve("one-more-unknown"));
        Set<String> over = names(home);
        expectIo(() -> openAndClose(home, TARGET, BINDING));
        assertEquals(over, names(home));
    }

    private Path newHome(String name) throws IOException { return Files.createDirectory(directory.resolve(name)); }
    private Path initialized() throws IOException {
        Path home = newHome("publication");
        try (var store = create(home)) { assertEquals(1, store.state().generation()); }
        return home;
    }
    private static LocalS3PublicationState create(Path home) throws IOException {
        try { return LocalS3PublicationState.create(home, TARGET, BINDING); }
        catch (LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) { return unavailable(unsupported); }
    }
    private static LocalS3PublicationState open(Path home) throws IOException {
        try { return LocalS3PublicationState.open(home, TARGET, BINDING); }
        catch (LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) { return unavailable(unsupported); }
    }
    private static LocalS3PublicationState unavailable(LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) {
        assumeTrue(false, "Publication-state directory synchronization is explicitly unavailable: " + unsupported);
        throw new AssertionError("Assumption did not abort", unsupported);
    }
    private static LocalCheckpointStore checkpointStore(Path home) throws IOException {
        try { return LocalCheckpointStore.open(home); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Checkpoint directory synchronization is explicitly unavailable: " + unsupported);
            throw new AssertionError("Assumption did not abort", unsupported);
        }
    }
    private static void createAndClose(Path home, Target target, S3CheckpointHeads.Binding binding) throws IOException {
        try (var store = LocalS3PublicationState.create(home, target, binding)) { store.state(); }
    }
    private static void openAndClose(Path home, Target target, S3CheckpointHeads.Binding binding) throws IOException {
        try (var store = LocalS3PublicationState.open(home, target, binding)) { store.state(); }
    }
    private static IOException expectIo(Checked operation) throws IOException {
        try { operation.run(); }
        catch (LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) {
            unavailable(unsupported);
        } catch (IOException expected) { return expected; }
        throw new AssertionError("Expected an ordinary storage I/O rejection");
    }
    private static S3PublicationStateException expectState(Code code, Checked operation) throws IOException {
        try { operation.run(); }
        catch (LocalS3PublicationState.DirectorySyncUnsupportedException unsupported) {
            unavailable(unsupported);
        } catch (S3PublicationStateException expected) {
            assertEquals(code, expected.code());
            return expected;
        }
        throw new AssertionError("Expected publication-state failure " + code);
    }
    @FunctionalInterface private interface Checked { void run() throws IOException; }

    private static Set<String> names(Path home) throws IOException {
        try (var entries = Files.list(home)) { return entries.map(path -> path.getFileName().toString()).collect(Collectors.toSet()); }
    }
    private static List<String> selectedNames(Path home) throws IOException {
        return names(home).stream().filter(name -> name.startsWith("publication-")).sorted().toList();
    }
    private static String selectedName(S3PublicationState state) {
        return selectedName(state.generation(), hash(S3PublicationStateCodec.encode(state)));
    }
    private static String selectedName(long generation, String id) {
        return String.format(Locale.ROOT, "publication-%019d-%s.state", generation, id);
    }
    private static String hash(byte[] image) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
    private static S3HeadObservation observation(byte[] image, String token) {
        return S3HeadObservation.restore(ENDPOINT, "bucket", image, new S3HeadToken(token));
    }
    private static void assertIntent(S3PublicationState state, Operation operation, String token, byte[] image) {
        var pending = state.pending().orElseThrow();
        assertEquals(operation, pending.operation());
        assertEquals(token, pending.precondition());
        assertArrayEquals(image, pending.image());
        assertEquals(ObjectReference.forBytes(image), pending.reference());
        assertEquals(ObjectCheckpointHeadCodec.decode(image).head(), pending.head());
    }
    private static void assertState(S3PublicationState expected, S3PublicationState actual) {
        assertArrayEquals(S3PublicationStateCodec.encode(expected), S3PublicationStateCodec.encode(actual));
    }

    private static void symbolicLink(Path alias, Path target) throws IOException {
        try { Files.createSymbolicLink(alias, target); }
        catch (UnsupportedOperationException unsupported) { unsupportedLink(unsupported); }
        catch (FileSystemException failure) { if (linkCapability(failure)) { unsupportedLink(failure); } else { throw failure; } }
    }
    private static void hardLink(Path alias, Path target) throws IOException {
        try { Files.createLink(alias, target); }
        catch (UnsupportedOperationException unsupported) { unsupportedLink(unsupported); }
        catch (FileSystemException failure) { if (linkCapability(failure)) { unsupportedLink(failure); } else { throw failure; } }
    }
    private static boolean linkCapability(FileSystemException failure) {
        String reason = failure.getReason();
        if (reason == null) { return false; }
        String lower = reason.toLowerCase(Locale.ROOT);
        return lower.contains("not supported") || lower.contains("not implemented") || lower.contains("privilege");
    }
    private static void unsupportedLink(Throwable unsupported) {
        assumeTrue(false, "Requested link capability is explicitly unavailable: " + unsupported);
        throw new AssertionError("Assumption did not abort", unsupported);
    }
}
