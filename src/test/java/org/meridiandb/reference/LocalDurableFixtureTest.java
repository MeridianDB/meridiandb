// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.engine.EngineException;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.Transaction;
import tools.jackson.databind.JsonNode;

class LocalDurableFixtureTest {
    @TempDir
    Path directory;

    @TestFactory
    Stream<DynamicTest> writesAndReopensEveryCanonicalHistoryWithGenesisAndNonemptyCheckpoints() throws IOException {
        var source = ContractFixtures.load();
        assertEquals("meridiandb-temporal-contract-v0", ContractFixtures.text(source, "format"));
        var histories = ContractFixtures.field(source, "histories");
        assertEquals(5, histories.size());
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
            for (boolean checkpoint : new boolean[] {false, true}) {
                String label = (checkpoint ? "checkpoint-tail/" : "genesis-tail/") + id;
                tests.add(dynamicTest(label, () -> replayHistory(
                        directory.resolve((checkpoint ? "checkpoint-" : "genesis-") + id),
                        new DatabaseId(id), checkpoint, steps, label)));
            }
        }
        assertEquals(84, expectations, "Retain all canonical expectations in each storage shape");
        return tests.stream();
    }

    private static void replayHistory(Path path, DatabaseId database, boolean checkpoint,
                                      JsonNode steps, String label) throws IOException {
        try (var target = new WritableFixtureDatabase(path, database, checkpoint)) {
            var views = new HashMap<String, CommitPosition>();
            for (var step : steps) {
                Object actual;
                try {
                    actual = ContractFixtures.step(target, database, step, views);
                } catch (ReferenceModel.Rejected rejected) {
                    actual = ContractFixtures.fields("error", rejected.code());
                }
                assertEquals(ContractFixtures.field(step, "expected"), ContractFixtures.json(actual),
                        label + "/" + ContractFixtures.text(step, "id"));
            }
            target.checkEveryHistoricalCutoff(steps, label);
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            assumeTrue(false, "Directory force is unavailable: " + unsupported);
        }
    }

    /** Publish only after the database releases its owners; retain all complete journal suffix bytes. */
    static void publishCheckpoint(Path directory, long cutoff) throws IOException {
        try (var store = LocalCheckpointStore.open(directory); var journal = LocalJournal.open(store.journalPath())) {
            long generation;
            try (var previous = store.readLatest(journal).orElseThrow()) {
                generation = Math.addExact(previous.manifest().generation(), 1);
            }
            var records = new ArrayList<JournalRecord>();
            journal.replay(records::add);
            assertTrue(cutoff > 0 && cutoff <= records.size());
            var segment = new ImmutableSegment(journal.databaseId(), records.subList(0, Math.toIntExact(cutoff)));
            var id = store.writeSegment(segment);
            var reference = new CheckpointManifest.SegmentReference(id, SegmentCodec.encode(segment).length, 1, cutoff);
            var prefix = journal.prefix(cutoff);
            store.publish(new CheckpointManifest(journal.databaseId(), generation,
                    new CheckpointManifest.JournalPrefix(cutoff, prefix.byteLength(), prefix.sha256()),
                    List.of(reference)), journal);
        }
    }

    /** Actual durable writes decide acceptance; the independent reference supplies only later query checks. */
    private static final class WritableFixtureDatabase implements ContractFixtures.Database, AutoCloseable {
        private final Path directory;
        private final boolean nonemptyCheckpoint;
        private final ReferenceModel reference;
        private final List<Transaction> committed = new ArrayList<>();
        private LocalDatabase database;
        private boolean checkpointPublished;

        WritableFixtureDatabase(Path directory, DatabaseId lineage, boolean nonemptyCheckpoint) throws IOException {
            this.directory = directory;
            this.nonemptyCheckpoint = nonemptyCheckpoint;
            reference = new ReferenceModel(lineage);
            Files.createDirectory(directory);
            database = LocalDatabase.create(directory, lineage);
        }

        @Override
        public CommitResult transact(Transaction request) {
            return semantic(() -> io(() -> {
                var result = database.transact(request);
                var expected = assertDoesNotThrow(() -> reference.transact(request),
                        "A production-accepted write must not be converted into an oracle rejection");
                assertEquals(expected, result);
                if (!result.replayed()) { committed.add(request); }
                database.close();
                database = null;
                if (nonemptyCheckpoint && !checkpointPublished && result.position().sequence() == 1) {
                    publishCheckpoint(directory, 1);
                    checkpointPublished = true;
                }
                database = LocalDatabase.openWritable(directory);
                assertEquals(reference.head(), database.head(), "Reopen preserves stored lineage and current head");
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

        void checkEveryHistoricalCutoff(JsonNode steps, String label) {
            var queries = new LinkedHashSet<TemporalQuery>();
            for (var step : steps) {
                if (ContractFixtures.text(step, "kind").equals("query")) {
                    try {
                        queries.add(ContractFixtures.query(ContractFixtures.field(step, "query")));
                    } catch (ReferenceModel.Rejected malformed) {
                        // The malformed query keeps its literal canonical assertion above.
                    }
                }
            }
            var lineage = reference.head().databaseId();
            assertEquals(Optional.of(new CommitPosition(lineage, nonemptyCheckpoint ? 1 : 0)),
                    database.checkpointPosition());
            for (long sequence = 0; sequence <= reference.head().sequence(); sequence++) {
                var position = new CommitPosition(lineage, sequence);
                try (var view = database.openView(new ReadCutoff.At(position))) {
                    assertEquals(position, view.position(), label + "/historical-position");
                    for (var query : queries) {
                        assertEquals(reference.query(position, query), view.query(query),
                                label + "/cutoff-" + sequence + "/" + query);
                    }
                }
            }
            for (int index = 0; index < committed.size(); index++) {
                assertEquals(Optional.of(new CommitPosition(lineage, index + 1L)),
                        database.lookupRequest(committed.get(index).requestId()), label + "/request-" + index);
            }
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
