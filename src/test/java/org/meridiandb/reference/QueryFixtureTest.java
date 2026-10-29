// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.engine.EngineException;
import org.meridiandb.engine.InMemoryEngine;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import tools.jackson.databind.JsonNode;

class QueryFixtureTest {
    @TempDir
    Path directory;

    @TestFactory
    Stream<DynamicTest> executesLiteralQueryHistoriesAcrossReferenceMemoryAndDurableReopen() throws IOException {
        var source = ContractFixtures.load("temporal-query-v0.json");
        assertEquals("meridiandb-temporal-query-v0", ContractFixtures.text(source, "format"));
        var histories = ContractFixtures.field(source, "histories");
        assertEquals(3, histories.size());
        int expectations = 0;
        var historyIds = new HashSet<String>();
        var tests = new ArrayList<DynamicTest>();
        for (var history : histories) {
            String id = ContractFixtures.text(history, "id");
            assertTrue(historyIds.add(id), "Duplicate history identity");
            var steps = ContractFixtures.field(history, "steps");
            expectations += steps.size();
            var stepIds = new HashSet<String>();
            for (var step : steps) {
                assertTrue(stepIds.add(ContractFixtures.text(step, "id")), "Duplicate step identity");
            }
            tests.add(dynamicTest("reference/" + id, () -> {
                var lineage = new DatabaseId(id);
                replay(ContractFixtures.reference(new ReferenceModel(lineage)), lineage, steps, "reference/" + id);
            }));
            tests.add(dynamicTest("memory/" + id, () -> {
                try (var engine = new InMemoryEngine()) {
                    replay(ContractFixtures.engine(engine), engine.head().databaseId(), steps, "memory/" + id);
                }
            }));
            for (boolean checkpoint : new boolean[] {false, true}) {
                String label = (checkpoint ? "durable-checkpoint/" : "durable-genesis/") + id;
                tests.add(dynamicTest(label, () -> {
                    var lineage = new DatabaseId(id);
                    var path = directory.resolve((checkpoint ? "checkpoint-" : "genesis-") + id);
                    try (var target = new WritableQueries(path, lineage, checkpoint)) {
                        replay(target, lineage, steps, label);
                        assertEquals(Optional.of(new CommitPosition(lineage, checkpoint ? 1 : 0)),
                                target.database.checkpointPosition(), label + "/selected-checkpoint");
                    } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
                        assumeTrue(false, "Directory force is unavailable: " + unsupported);
                    }
                }));
            }
        }
        assertEquals(90, expectations, "Execute every literal expectation against all four implementations/shapes");
        return tests.stream();
    }

    private static void replay(ContractFixtures.Database target, DatabaseId lineage,
                               JsonNode steps, String label) {
        var views = new HashMap<String, CommitPosition>();
        for (var step : steps) {
            Object actual;
            try {
                actual = ContractFixtures.step(target, lineage, step, views);
            } catch (ReferenceModel.Rejected rejected) {
                actual = ContractFixtures.fields("error", rejected.code());
            }
            assertEquals(ContractFixtures.field(step, "expected"), ContractFixtures.json(actual),
                    label + "/" + ContractFixtures.text(step, "id"));
        }
    }

    /** Actual local writes determine acceptance; every success releases owners and recovers a writer. */
    private static final class WritableQueries implements ContractFixtures.Database, AutoCloseable {
        private final Path directory;
        private final boolean checkpoint;
        private LocalDatabase database;
        private boolean checkpointPublished;

        WritableQueries(Path directory, DatabaseId lineage, boolean checkpoint) throws IOException {
            this.directory = directory;
            this.checkpoint = checkpoint;
            Files.createDirectory(directory);
            database = LocalDatabase.create(directory, lineage);
        }

        @Override
        public CommitResult transact(Transaction request) {
            return semantic(() -> io(() -> {
                var result = database.transact(request);
                var beforeClose = database.head();
                database.close();
                database = null;
                if (checkpoint && !checkpointPublished && !result.replayed()) {
                    assertEquals(1, result.position().sequence());
                    LocalDurableFixtureTest.publishCheckpoint(directory, 1);
                    checkpointPublished = true;
                }
                database = LocalDatabase.openWritable(directory);
                assertEquals(beforeClose, database.head(), "Reopen preserves the complete acknowledged head");
                assertEquals(Optional.of(result.position()), database.lookupRequest(request.requestId()));
                return result;
            }));
        }

        @Override
        public CommitPosition openView(ReadCutoff cutoff) {
            return semantic(() -> {
                try (var view = database.openView(cutoff)) { return view.position(); }
            });
        }

        @Override
        public TemporalResult query(CommitPosition position, TemporalQuery query) {
            return semantic(() -> {
                try (var view = database.openView(new ReadCutoff.At(position))) { return view.query(query); }
            });
        }

        @Override
        public Optional<CommitPosition> lookupRequest(RequestId requestId) {
            return database.lookupRequest(requestId);
        }

        @Override
        public void close() throws IOException {
            if (database != null) { database.close(); }
        }
    }

    private static <T> T semantic(Supplier<T> action) {
        try {
            return action.get();
        } catch (EngineException rejected) {
            throw new ReferenceModel.Rejected(rejected.code().name());
        }
    }

    private static <T> T io(IoAction<T> action) {
        try {
            return action.run();
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force is unavailable: " + unsupported);
            throw new AssertionError("Unreachable after failed assumption", unsupported);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @FunctionalInterface
    private interface IoAction<T> {
        T run() throws IOException;
    }
}
