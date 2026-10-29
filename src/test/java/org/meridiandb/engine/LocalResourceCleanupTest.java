// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalResourceCleanupTest {
    @Test
    void failedReaderReleaseStillClosesJournalAndStoreWithSuppressedFailures() {
        var calls = new ArrayList<String>();
        var reader = new IOException("reader");
        var journal = new IOException("journal");
        var store = new IOException("store");
        var actual = assertThrows(IOException.class, () -> LocalDatabase.closeResources(
                () -> { calls.add("reader"); throw reader; },
                () -> { calls.add("journal"); throw journal; },
                () -> { calls.add("store"); throw store; }));
        assertSame(reader, actual);
        assertEquals(List.of("reader", "journal", "store"), calls);
        assertArrayEquals(new Throwable[] {journal, store}, actual.getSuppressed());
    }

    @Test
    void errorDoesNotStrandLaterOwnersOrHideTheOriginalError() {
        var calls = new ArrayList<String>();
        var failure = new AssertionError("reader");
        var later = new IOException("journal");
        assertSame(failure, assertThrows(AssertionError.class, () -> LocalDatabase.closeResources(
                () -> { throw failure; }, () -> { calls.add("journal"); throw later; }, () -> calls.add("store"))));
        assertEquals(List.of("journal", "store"), calls);
        assertArrayEquals(new Throwable[] {later}, failure.getSuppressed());
    }

    @Test
    void uncheckedFailurePreservesCleanupAndSameFailureIsNotSelfSuppressed() {
        var failure = new IllegalStateException("release");
        var calls = new ArrayList<String>();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> LocalDatabase.closeResources(
                () -> { throw failure; }, () -> { throw failure; }, () -> calls.add("store"))));
        assertEquals(List.of("store"), calls);
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void missingReaderAndSuccessfulOwnersAreClosedNormally() throws IOException {
        var calls = new ArrayList<String>();
        LocalDatabase.closeResources(null, () -> calls.add("journal"), () -> calls.add("store"));
        assertEquals(List.of("journal", "store"), calls);
    }
}
