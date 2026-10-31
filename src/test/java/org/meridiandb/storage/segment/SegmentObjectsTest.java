// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.segment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.object.LocalObjectStore;
import org.meridiandb.storage.object.ObjectIntegrityException;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class SegmentObjectsTest {
    @TempDir Path directory;
    private final ObjectKey key = new ObjectKey("history.segment");

    @Test void reopensExactHistoryAndExplicitlyReusesOriginalBytes() throws Exception {
        var original = history();
        var store = new LocalObjectStore(directory);
        var first = SegmentObjects.createOrVerify(store, key, original);
        assertTrue(first.created());
        assertFalse(SegmentObjects.createOrVerify(store, key, original).created());
        var id = new SegmentId(first.reference().id().value());
        var reopened = SegmentObjects.read(new LocalObjectStore(directory), key, id, first.reference().byteLength());
        assertEquals(original, reopened.segment());
        assertEquals(id, reopened.id());
        try (var legacy = LocalSegmentFile.open(directory.resolve(key.value()), id)) {
            assertEquals(original, legacy.segment());
        }
    }

    @Test void readsUnchangedLegacyFilesUsingIndependentExpectedMetadata() throws Exception {
        var original = history();
        var id = LocalSegmentFile.writeNew(directory.resolve(key.value()), original);
        int length = Math.toIntExact(java.nio.file.Files.size(directory.resolve(key.value())));
        assertEquals(original, SegmentObjects.read(new LocalObjectStore(directory), key, id, length).segment());
    }

    @Test void wrongIdentityOrLengthReturnsNoHistory() throws Exception {
        var store = new LocalObjectStore(directory);
        var result = SegmentObjects.createOrVerify(store, key, history());
        int length = result.reference().byteLength();
        assertEquals(ObjectIntegrityException.Code.ID_MISMATCH,
                assertThrows(ObjectIntegrityException.class,
                        () -> SegmentObjects.read(store, key, new SegmentId("0".repeat(64)), length)).code());
        assertEquals(ObjectIntegrityException.Code.LENGTH_MISMATCH,
                assertThrows(ObjectIntegrityException.class,
                        () -> SegmentObjects.read(store, key, new SegmentId(result.reference().id().value()), length - 1)).code());
    }

    @Test void validContentHashDoesNotMakeMalformedBytesAHistorySegment() throws Exception {
        var store = new LocalObjectStore(directory);
        var reference = store.createNew(key, new byte[] {1, 2, 3});
        assertThrows(SegmentFormatException.class, () -> SegmentObjects.read(store, key,
                new SegmentId(reference.id().value()), reference.byteLength()));
    }

    private static ImmutableSegment history() {
        var entity = new EntityId("salary");
        return new ImmutableSegment(new DatabaseId("object-history"), List.of(
                new JournalRecord(1, new Transaction(new RequestId("initial"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(10L, null), 100)))),
                new JournalRecord(2, new Transaction(new RequestId("correction"), List.of(
                        new WriteOperation.Put(entity, new ValidTimeInterval(20L, 30L), 120)))),
                new JournalRecord(3, new Transaction(new RequestId("retract"), List.of(
                        new WriteOperation.Retract(entity, new ValidTimeInterval(25L, 28L)))))));
    }
}
