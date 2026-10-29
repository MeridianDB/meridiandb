// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.temporal.*;

class LocalBoundaryTest {
    private static final DatabaseId ID = new DatabaseId("observed-local");
    private static final Transaction REQUEST = new Transaction(new RequestId("one"), List.of(
            new WriteOperation.Put(new EntityId("a"), new ValidTimeInterval(null, null), 7)));
    @TempDir Path directory;

    @Test
    void instanceProbeObservesStartupAndWriteOrderButExactRetryAddsNoStorageBoundary() throws IOException {
        var seen = new ArrayList<LocalDatabase.Boundary>();
        try (var database = create(seen::add)) {
            assertEquals(List.of(LocalDatabase.Boundary.RECOVERED, LocalDatabase.Boundary.SYNCHRONIZED), seen);
            seen.clear();
            database.transact(REQUEST);
            assertEquals(List.of(LocalDatabase.Boundary.BEFORE_APPEND, LocalDatabase.Boundary.AFTER_APPEND,
                    LocalDatabase.Boundary.AFTER_FORCE, LocalDatabase.Boundary.AFTER_PUBLICATION), seen);
            seen.clear();
            assertTrue(database.transact(REQUEST).replayed());
            assertTrue(seen.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(value = LocalDatabase.Boundary.class, names = {"RECOVERED", "SYNCHRONIZED"})
    void failedHiddenStartupReleasesOwnershipAndLeavesSelectedGenesis(LocalDatabase.Boundary stop) throws IOException {
        try {
            var failure = assertThrows(IOException.class, () -> LocalDatabase.create(directory, ID, boundary -> {
                if (boundary == stop) { throw new IOException("startup observer"); }
            }));
            if (failure instanceof LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
                assumeTrue(false, "Directory force unavailable: " + unsupported);
            }
            assertEquals("startup observer", failure.getMessage());
            try (var database = LocalDatabase.openWritable(directory)) {
                assertEquals(new CommitPosition(ID, 0), database.head());
            }
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force unavailable: " + unsupported);
        }
    }

    @Test
    void failureAfterPublicationPoisonsViewsAndReopenResolvesTheLostAcknowledgement() throws IOException {
        var database = create(boundary -> {
            if (boundary == LocalDatabase.Boundary.AFTER_PUBLICATION) { throw new IOException("lost acknowledgement"); }
        });
        try (database; var view = database.openView(ReadCutoff.Head.INSTANCE)) {
            var failure = assertThrows(LocalDatabase.UncertainCommitException.class, () -> database.transact(REQUEST));
            assertEquals(REQUEST.requestId(), failure.requestId());
            assertEquals(new CommitPosition(ID, 1), failure.proposedPosition());
            assertThrows(IllegalStateException.class, database::head);
            assertThrows(IllegalStateException.class, view::position);
        }
        try (var recovered = LocalDatabase.openWritable(directory)) {
            assertEquals(new CommitPosition(ID, 1), recovered.head());
            assertTrue(recovered.transact(REQUEST).replayed());
            assertEquals(new CommitPosition(ID, 1), recovered.head());
        }
    }

    private LocalDatabase create(LocalDatabase.BoundaryProbe probe) throws IOException {
        try { return LocalDatabase.create(directory, ID, probe); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force unavailable: " + unsupported);
            throw new AssertionError("Unreachable", unsupported);
        }
    }
}
