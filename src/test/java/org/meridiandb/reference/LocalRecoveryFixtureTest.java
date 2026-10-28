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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
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

class LocalRecoveryFixtureTest {
    @TempDir
    Path directory;

    @TestFactory
    Stream<DynamicTest> recoversEveryCanonicalHistoryFromEachSupportedCheckpointShape() throws IOException {
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
            for (var mode : CheckpointShape.values()) {
                String label = mode.name().toLowerCase(Locale.ROOT) + "/" + id;
                tests.add(dynamicTest(label, () -> replayHistory(directory.resolve(mode.name() + "-" + id),
                        new DatabaseId(id), mode, steps, label)));
            }
        }
        assertEquals(84, expectations, "Retain every canonical expected result in each checkpoint shape");
        return tests.stream();
    }

    private static void replayHistory(Path directory, DatabaseId database, CheckpointShape mode,
                                      JsonNode steps, String label) {
        var target = new RecoveringFixtureDatabase(directory, database, mode);
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
    }

    private enum CheckpointShape { JOURNAL_ONLY, GENESIS_CHECKPOINT, FIRST_COMMIT_CHECKPOINT }

    /**
     * The independent reference accepts fixture writes, while all observed reads use disk recovery.
     * Each operation releases writer ownership before opening the production read-only database.
     */
    private static final class RecoveringFixtureDatabase implements ContractFixtures.Database {
        private final Path directory;
        private final DatabaseId database;
        private final CheckpointShape shape;
        private final ReferenceModel reference;
        private final List<JournalRecord> committed = new ArrayList<>();

        RecoveringFixtureDatabase(Path directory, DatabaseId database, CheckpointShape shape) {
            this.directory = directory;
            this.database = database;
            this.shape = shape;
            reference = new ReferenceModel(database);
            io(() -> {
                Files.createDirectory(directory);
                try (var store = LocalCheckpointStore.open(directory);
                        var journal = LocalJournal.create(store.journalPath(), database)) {
                    if (shape == CheckpointShape.GENESIS_CHECKPOINT) {
                        var prefix = journal.prefix(0);
                        store.publish(new CheckpointManifest(database, 1, manifestPrefix(prefix), List.of()), journal);
                    }
                }
                return null;
            });
        }

        @Override
        public CommitResult transact(Transaction request) {
            var result = reference.transact(request);
            if (!result.replayed()) {
                var record = new JournalRecord(result.position().sequence(), request);
                io(() -> {
                    try (var store = LocalCheckpointStore.open(directory);
                            var journal = LocalJournal.open(store.journalPath())) {
                        journal.append(record);
                        if (shape == CheckpointShape.FIRST_COMMIT_CHECKPOINT && record.sequence() == 1) {
                            var segment = new ImmutableSegment(database, List.of(record));
                            var id = store.writeSegment(segment);
                            var segmentReference = new CheckpointManifest.SegmentReference(id,
                                    SegmentCodec.encode(segment).length, 1, 1);
                            store.publish(new CheckpointManifest(database, 1, manifestPrefix(journal.prefix(1)),
                                    List.of(segmentReference)), journal);
                        } else {
                            journal.force();
                        }
                    }
                    return null;
                });
                committed.add(record);
            }
            return result;
        }

        @Override
        public CommitPosition openView(ReadCutoff cutoff) {
            return semantic(() -> recovered(database -> {
                try (var view = database.openView(cutoff)) { return view.position(); }
            }));
        }

        @Override
        public TemporalResult query(CommitPosition position, TemporalQuery query) {
            return semantic(() -> recovered(database -> {
                try (var view = database.openView(new ReadCutoff.At(position))) { return view.query(query); }
            }));
        }

        @Override
        public Optional<CommitPosition> lookupRequest(RequestId requestId) {
            return recovered(database -> database.lookupRequest(requestId));
        }

        void checkEveryHistoricalCutoff(JsonNode steps, String label) {
            var queries = new LinkedHashSet<TemporalQuery>();
            for (var step : steps) {
                if (ContractFixtures.text(step, "kind").equals("query")) {
                    try {
                        queries.add(ContractFixtures.query(ContractFixtures.field(step, "query")));
                    } catch (ReferenceModel.Rejected malformedQuery) {
                        // Invalid fixture queries retain their canonical assertion above.
                    }
                }
            }
            recovered(database -> {
                for (long sequence = 0; sequence <= reference.head().sequence(); sequence++) {
                    var position = new CommitPosition(this.database, sequence);
                    try (var view = database.openView(new ReadCutoff.At(position))) {
                        assertEquals(position, view.position(), label + "/historical-position");
                        for (var query : queries) {
                            assertEquals(reference.query(position, query), view.query(query),
                                    label + "/cutoff-" + sequence + "/" + query);
                        }
                    }
                }
                for (var record : committed) {
                    assertEquals(Optional.of(new CommitPosition(this.database, record.sequence())),
                            database.lookupRequest(record.transaction().requestId()),
                            label + "/request-" + record.transaction().requestId());
                }
                return null;
            });
        }

        private <T> T recovered(Function<LocalDatabase, T> action) {
            return io(() -> {
                try (var recovered = LocalDatabase.open(directory)) {
                    assertEquals(reference.head(), recovered.head(), "Recovery retains the stored lineage and current head");
                    return action.apply(recovered);
                }
            });
        }
    }

    private static CheckpointManifest.JournalPrefix manifestPrefix(LocalJournal.Prefix prefix) {
        return new CheckpointManifest.JournalPrefix(prefix.sequence(), prefix.byteLength(), prefix.sha256());
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
