// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObjectTypesTest {
    private static final byte[] ABC = {97, 98, 99};
    private static final ObjectId ABC_ID = new ObjectId(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    private static final ObjectReference ABC_REFERENCE = new ObjectReference(ABC_ID, 3);

    @Test
    void contentIdentityMatchesIndependentSha256Vectors() {
        assertEquals(ABC_REFERENCE, ObjectReference.forBytes(ABC));
        assertEquals(new ObjectReference(new ObjectId(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), 0),
                ObjectReference.forBytes(new byte[0]));
        byte[] caller = ABC.clone();
        var reference = ObjectReference.forBytes(caller);
        caller[0] = 0;
        assertEquals(ABC_REFERENCE, reference);
    }

    @Test
    void identifiersRejectAmbiguousOrNonCanonicalForms() {
        for (String invalid : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64),
                "g".repeat(64), " " + "a".repeat(64))) {
            assertThrows(IllegalArgumentException.class, () -> new ObjectId(invalid), invalid);
        }
        assertThrows(NullPointerException.class, () -> new ObjectId(null));
        assertEquals("0".repeat(64), new ObjectId("0".repeat(64)).value());
    }

    @Test
    void portableKeysAcceptBoundariesAndRejectPathsAndDeviceNames() {
        for (String valid : List.of("a", "0", "a".repeat(128), "0123456789.segment", "x_y.z-9")) {
            assertEquals(valid, new ObjectKey(valid).value());
        }
        for (String invalid : List.of("", "a".repeat(129), ".", "..", "/abc", "../abc", "a/b",
                "a\\b", "a:9", "A", " a", "a ", "a.", "a-", "_a", "a_", "é", "a\n9",
                "con", "con.txt", "prn", "aux.data", "nul", "com1", "com9.bin", "lpt1", "lpt9.bin")) {
            assertThrows(IllegalArgumentException.class, () -> new ObjectKey(invalid), invalid);
        }
        assertThrows(NullPointerException.class, () -> new ObjectKey(null));
    }

    @Test
    void expectedLengthIsBoundedBeforeAnyObjectCanBeConstructed() {
        assertEquals(0, new ObjectReference(ABC_ID, 0).byteLength());
        assertEquals(32 * 1024 * 1024, new ObjectReference(ABC_ID, 32 * 1024 * 1024).byteLength());
        assertThrows(IllegalArgumentException.class, () -> new ObjectReference(ABC_ID, -1));
        assertThrows(IllegalArgumentException.class, () -> new ObjectReference(ABC_ID, 32 * 1024 * 1024 + 1));
        assertThrows(IllegalArgumentException.class, () -> new ObjectReference(ABC_ID, Integer.MAX_VALUE));
        assertThrows(NullPointerException.class, () -> new ObjectReference(null, 0));
        assertThrows(NullPointerException.class, () -> ObjectReference.forBytes(null));
        assertThrows(NullPointerException.class, () -> new ImmutableObjectStore.CreateResult(null, true));
    }

    @Test
    void verificationSeparatesWrongLengthFromWrongContent() {
        var wrongLength = assertThrows(ObjectIntegrityException.class,
                () -> VerifiedObject.verify(new byte[] {97, 98}, ABC_REFERENCE));
        assertEquals(ObjectIntegrityException.Code.LENGTH_MISMATCH, wrongLength.code());
        var wrongId = assertThrows(ObjectIntegrityException.class,
                () -> VerifiedObject.verify(new byte[] {97, 98, 100}, ABC_REFERENCE));
        assertEquals(ObjectIntegrityException.Code.ID_MISMATCH, wrongId.code());
        assertThrows(NullPointerException.class, () -> VerifiedObject.verify(null, ABC_REFERENCE));
        assertThrows(NullPointerException.class, () -> VerifiedObject.verify(ABC, null));
    }

    @Test
    void verifiedSnapshotsDefendEveryArrayBoundary() throws Exception {
        byte[] input = ABC.clone();
        try (var object = VerifiedObject.verify(input, ABC_REFERENCE)) {
            input[0] = 0;
            byte[] output = object.bytes();
            output[1] = 0;
            byte[] range = object.readRange(0, 2);
            range[0] = 0;
            assertArrayEquals(ABC, object.bytes());
            assertArrayEquals(new byte[] {97, 98}, object.readRange(0, 2));
            assertEquals(ABC_REFERENCE, object.reference());
        }
    }

    @Test
    void everySmallRangeHasExactBoundsAndNeverOverflows() throws Exception {
        try (var object = VerifiedObject.verify(ABC, ABC_REFERENCE)) {
            for (int start = 0; start <= 3; start++) {
                for (int end = start; end <= 3; end++) {
                    assertArrayEquals(Arrays.copyOfRange(ABC, start, end), object.readRange(start, end - start));
                }
            }
            for (long offset : new long[] {-1, Long.MIN_VALUE, 4, Long.MAX_VALUE}) {
                assertThrows(IllegalArgumentException.class, () -> object.readRange(offset, 0));
            }
            assertThrows(IllegalArgumentException.class, () -> object.readRange(0, -1));
            assertThrows(IllegalArgumentException.class, () -> object.readRange(0, Integer.MAX_VALUE));
            assertThrows(IllegalArgumentException.class, () -> object.readRange(2, 2));
            assertThrows(IllegalArgumentException.class, () -> object.readRange(3, 1));
        }
    }

    @Test
    void closingRejectsNewAccessButPreservesPreviouslyReturnedValues() throws Exception {
        var object = VerifiedObject.verify(ABC, ABC_REFERENCE);
        byte[] bytes = object.bytes();
        byte[] range = object.readRange(1, 2);
        var reference = object.reference();
        object.close();
        object.close();
        assertThrows(IllegalStateException.class, object::reference);
        assertThrows(IllegalStateException.class, object::bytes);
        assertThrows(IllegalStateException.class, () -> object.readRange(0, 0));
        assertArrayEquals(ABC, bytes);
        assertArrayEquals(new byte[] {98, 99}, range);
        assertEquals(ABC_REFERENCE, reference);
    }
}
