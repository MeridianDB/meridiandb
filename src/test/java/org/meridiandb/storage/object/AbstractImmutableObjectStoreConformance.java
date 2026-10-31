// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Backend-independent observable contract; another real adapter can run this same suite. */
abstract class AbstractImmutableObjectStoreConformance {
    protected static final byte[] ABC = {97, 98, 99};
    protected static final ObjectReference ABC_REFERENCE = new ObjectReference(new ObjectId(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), 3);

    /** Each test receives a fresh, empty, independently accessible namespace. */
    protected abstract ImmutableObjectStore store() throws IOException;

    @Test
    void exactExpectedIdentityAndBytesSurviveAnIndependentOpen() throws IOException {
        var key = new ObjectKey("known-vector.bin");
        assertEquals(ABC_REFERENCE, store().createNew(key, ABC));
        try (var reader = store().read(key, ABC_REFERENCE)) {
            assertEquals(ABC_REFERENCE, reader.reference());
            assertArrayEquals(ABC, reader.bytes());
            assertArrayEquals(new byte[] {98, 99}, reader.readRange(1, 2));
        }
    }

    @Test
    void createOnlyRejectsBothIdenticalAndConflictingNames() throws IOException {
        var backend = store();
        var key = new ObjectKey("create-only.bin");
        backend.createNew(key, ABC);
        assertThrows(ObjectAlreadyExistsException.class, () -> backend.createNew(key, ABC));
        assertThrows(ObjectAlreadyExistsException.class, () -> backend.createNew(key, new byte[] {7, 8, 9}));
        try (var reader = store().read(key, ABC_REFERENCE)) { assertArrayEquals(ABC, reader.bytes()); }
    }

    @Test
    void explicitReuseDistinguishesCreationFromWholeObjectVerification() throws IOException {
        var key = new ObjectKey("reuse.bin");
        var first = store().createOrVerify(key, ABC);
        var second = store().createOrVerify(key, ABC);
        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(ABC_REFERENCE, first.reference());
        assertEquals(first.reference(), second.reference());
    }

    @Test
    void conflictingReuseNeverOverwritesOrRepairsTheOriginal() throws IOException {
        var key = new ObjectKey("conflicting.bin");
        store().createNew(key, ABC);
        var wrongId = assertThrows(ObjectIntegrityException.class,
                () -> store().createOrVerify(key, new byte[] {1, 2, 3}));
        assertEquals(ObjectIntegrityException.Code.ID_MISMATCH, wrongId.code());
        var wrongLength = assertThrows(ObjectIntegrityException.class,
                () -> store().createOrVerify(key, new byte[] {1, 2}));
        assertEquals(ObjectIntegrityException.Code.LENGTH_MISMATCH, wrongLength.code());
        try (var reader = store().read(key, ABC_REFERENCE)) { assertArrayEquals(ABC, reader.bytes()); }
    }

    @Test
    void readsRequireIndependentLengthAndIdentity() throws IOException {
        var key = new ObjectKey("expected.bin");
        var backend = store();
        backend.createNew(key, ABC);
        var wrongLength = assertThrows(ObjectIntegrityException.class,
                () -> backend.read(key, new ObjectReference(ABC_REFERENCE.id(), 2)));
        assertEquals(ObjectIntegrityException.Code.LENGTH_MISMATCH, wrongLength.code());
        var wrongId = assertThrows(ObjectIntegrityException.class,
                () -> backend.read(key, new ObjectReference(new ObjectId("0".repeat(64)), 3)));
        assertEquals(ObjectIntegrityException.Code.ID_MISMATCH, wrongId.code());
        try (var reader = backend.read(key, ABC_REFERENCE)) { assertArrayEquals(ABC, reader.bytes()); }
    }

    @Test
    void missingReadHasNoCreationSideEffect() throws IOException {
        var backend = store();
        var key = new ObjectKey("missing.bin");
        assertThrows(IOException.class, () -> backend.read(key, ABC_REFERENCE));
        assertEquals(ABC_REFERENCE, backend.createNew(key, ABC));
    }

    @Test
    void emptyObjectsAreCompleteImagesWithAnExactEofRange() throws IOException {
        var backend = store();
        var key = new ObjectKey("empty.bin");
        var empty = new ObjectReference(new ObjectId(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), 0);
        assertEquals(empty, backend.createNew(key, new byte[0]));
        try (var reader = backend.read(key, empty)) {
            assertArrayEquals(new byte[0], reader.bytes());
            assertArrayEquals(new byte[0], reader.readRange(0, 0));
            assertThrows(IllegalArgumentException.class, () -> reader.readRange(0, 1));
            assertThrows(IllegalArgumentException.class, () -> reader.readRange(1, 0));
        }
        assertFalse(backend.createOrVerify(key, new byte[0]).created());
    }

    @Test
    void inputAndReturnedCopiesCannotChangeStoredContent() throws IOException {
        var backend = store();
        var key = new ObjectKey("copies.bin");
        byte[] supplied = ABC.clone();
        backend.createNew(key, supplied);
        supplied[0] = 0;
        try (var reader = backend.read(key, ABC_REFERENCE)) {
            byte[] all = reader.bytes();
            byte[] part = reader.readRange(0, 2);
            all[0] = 0;
            part[1] = 0;
            assertArrayEquals(ABC, reader.bytes());
        }
        try (var reader = store().read(key, ABC_REFERENCE)) { assertArrayEquals(ABC, reader.bytes()); }
    }

    @Test
    void readersHaveIndependentLifetimesAndReturnedCopiesSurviveClose() throws IOException {
        var backend = store();
        var key = new ObjectKey("readers.bin");
        backend.createNew(key, ABC);
        var first = backend.read(key, ABC_REFERENCE);
        byte[] retained = first.readRange(1, 2);
        try (var second = backend.read(key, ABC_REFERENCE)) {
            first.close();
            first.close();
            assertThrows(IllegalStateException.class, first::bytes);
            assertThrows(IllegalStateException.class, first::reference);
            assertThrows(IllegalStateException.class, () -> first.readRange(0, 0));
            assertArrayEquals(ABC, second.bytes());
            assertArrayEquals(new byte[] {98, 99}, retained);
        } finally { first.close(); }
        try (var reader = backend.read(key, ABC_REFERENCE)) { assertArrayEquals(ABC, reader.bytes()); }
    }
}
