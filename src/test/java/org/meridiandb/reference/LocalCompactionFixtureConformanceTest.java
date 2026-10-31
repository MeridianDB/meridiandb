// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.ByteArrayOutputStream;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.engine.EngineException;
import org.meridiandb.engine.LocalDatabase;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.manifest.HistoryCompactor;
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

/** Honest literal streams interleave real writes, source publication, compaction, removal and reopen. */
@Timeout(120)
class LocalCompactionFixtureConformanceTest {
    @TempDir
    Path directory;

    @TestFactory
    Stream<DynamicTest> everyOriginalAndQueryLiteralSurvivesFullAndTailCheckpointMaintenance() throws IOException {
        var tests = new ArrayList<DynamicTest>();
        add(tests, ContractFixtures.load(), "temporal", 5, 84);
        add(tests, ContractFixtures.load("temporal-query-v0.json"), "query", 3, 90);
        assertEquals(16, tests.size());
        return tests.stream();
    }

    private void add(List<DynamicTest> tests, JsonNode corpus, String kind, int count, int expectedSteps) {
        var histories = ContractFixtures.field(corpus, "histories");
        assertEquals(count, histories.size());
        int steps = 0;
        var names = new HashSet<String>();
        for (var history : histories) {
            String name = ContractFixtures.text(history, "id");
            assertTrue(names.add(name));
            var stream = ContractFixtures.field(history, "steps");
            steps += stream.size();
            for (boolean tail : new boolean[] {false, true}) {
                String label = kind + "/" + name + (tail ? "/checkpoint-tail" : "/full-checkpoint");
                Path home = directory.resolve(kind + "-" + name + (tail ? "-tail" : "-full"));
                tests.add(dynamicTest(label, () -> replay(home, new DatabaseId(label), stream, tail, label)));
            }
        }
        assertEquals(expectedSteps, steps, "Retain every original literal expectation in both real storage shapes");
    }

    private static void replay(Path home, DatabaseId lineage, JsonNode steps, boolean tail, String label) throws IOException {
        var reference = new ReferenceModel(lineage);
        var referenceAdapter = ContractFixtures.reference(reference);
        var referenceViews = new HashMap<String, CommitPosition>();
        var realViews = new HashMap<String, CommitPosition>();
        var queries = validQueries(steps);
        List<JournalRecord> records;
        try (var target = new MaintainedDatabase(home, lineage, tail, reference, queries)) {
            for (var step : steps) {
                String context = label + "/" + ContractFixtures.text(step, "id");
                Object wanted;
                try { wanted = ContractFixtures.step(referenceAdapter, lineage, step, referenceViews); }
                catch (ReferenceModel.Rejected rejected) { wanted = ContractFixtures.fields("error", rejected.code()); }
                assertEquals(ContractFixtures.field(step, "expected"), ContractFixtures.json(wanted), "reference/" + context);
                Object actual;
                try { actual = ContractFixtures.step(target, lineage, step, realViews); }
                catch (ReferenceModel.Rejected rejected) { actual = ContractFixtures.fields("error", rejected.code()); }
                assertEquals(ContractFixtures.field(step, "expected"), ContractFixtures.json(actual), "real/" + context);
                assertEquals(ContractFixtures.json(wanted), ContractFixtures.json(actual), context);
            }
            target.verify(target.database, "final", true);
            records = List.copyOf(target.records);
            assertEquals(records.size(), target.cycles, "Every actual new write completes a real maintenance cycle");
            assertTrue(target.cycles > 0);
            if (records.size() > 2) { assertTrue(target.pinExercised, "A nonempty older lazy graph must be protected"); }
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unavailable) {
            assumeTrue(false, "Required directory synchronization unavailable: " + unavailable);
            return;
        }
        var expectedJournal = new ByteArrayOutputStream();
        expectedJournal.writeBytes(JournalCodec.encodeHeader(lineage));
        records.forEach(record -> expectedJournal.writeBytes(JournalCodec.encodeRecord(record)));
        assertArrayEquals(expectedJournal.toByteArray(), Files.readAllBytes(home.resolve("transactions.journal")),
                "Every original transaction frame remains exact after all owners close");
    }

    private static List<TemporalQuery> validQueries(JsonNode steps) {
        var queries = new LinkedHashSet<TemporalQuery>();
        for (var step : steps) {
            if (!ContractFixtures.text(step, "kind").equals("query")) { continue; }
            try { queries.add(ContractFixtures.query(ContractFixtures.field(step, "query"))); }
            catch (ReferenceModel.Rejected malformed) {
                // Its exact literal rejection is still executed against both adapters.
            }
        }
        return List.copyOf(queries);
    }

    private static final class MaintainedDatabase implements ContractFixtures.Database, AutoCloseable {
        final Path home;
        final boolean tail;
        final ReferenceModel reference;
        final List<TemporalQuery> queries;
        final List<JournalRecord> records = new ArrayList<>();
        LocalDatabase database;
        int cycles;
        boolean pinExercised;
        MaintainedDatabase(Path home, DatabaseId lineage, boolean tail, ReferenceModel reference,
                           List<TemporalQuery> queries) throws IOException {
            this.home = Files.createDirectory(home);
            this.tail = tail;
            this.reference = reference;
            this.queries = queries;
            database = LocalDatabase.create(home, lineage);
        }
        @Override public CommitResult transact(Transaction request) {
            return semantic(() -> io(() -> {
                var result = database.transact(request); // Production chooses new acceptance versus replay.
                assertEquals(reference.head(), database.head());
                if (!result.replayed()) {
                    records.add(new JournalRecord(result.position().sequence(), request));
                    verify(database, "before", true);
                    database.close();
                    database = null;
                    long cutoff = tail ? Math.max(0, result.position().sequence() - 1) : result.position().sequence();
                    boolean exercisePins = !pinExercised && cutoff >= 2;
                    var cycle = LocalCompactionConformanceSupport.cycle(home, cutoff,
                            tail ? 1024 : HistoryCompactor.Limits.DEFAULT.targetSegmentBytes(), exercisePins, this::verify);
                    assertEquals(cutoff, cycle.cutoff());
                    if (exercisePins) { pinExercised = true; }
                    cycles++;
                    database = LocalDatabase.openWritable(home);
                    assertEquals(Optional.of(new CommitPosition(reference.head().databaseId(), cutoff)), database.checkpointPosition());
                    verify(database, "reopened-for-next-literal", true);
                }
                assertEquals(Optional.of(result.position()), database.lookupRequest(request.requestId()));
                return result; // Preserve the honest original replay flag and position.
            }));
        }
        @Override public CommitPosition openView(ReadCutoff cutoff) {
            return semantic(() -> { try (var view = database.openView(cutoff)) { return view.position(); } });
        }
        @Override public TemporalResult query(CommitPosition position, TemporalQuery query) {
            return semantic(() -> {
                try (var view = database.openView(new ReadCutoff.At(position))) { return view.query(query); }
            });
        }
        @Override public Optional<CommitPosition> lookupRequest(RequestId requestId) { return database.lookupRequest(requestId); }
        void verify(LocalDatabase target, String stage, boolean writable) throws IOException {
            assertEquals(reference.head(), target.head(), stage + "/lineage and committed head");
            for (long cutoff = 0; cutoff <= reference.head().sequence(); cutoff++) {
                var position = new CommitPosition(reference.head().databaseId(), cutoff);
                try (var view = target.openView(new ReadCutoff.At(position))) {
                    assertEquals(position, view.position());
                    for (var query : queries) {
                        assertEquals(reference.query(position, query), view.query(query), stage + "/cutoff=" + cutoff + "/" + query);
                    }
                }
            }
            LocalCompactionConformanceSupport.checkIdentity(target, records, stage, writable);
        }
        @Override public void close() throws IOException { if (database != null) { database.close(); } }
    }

    private static <T> T semantic(Supplier<T> action) {
        try { return action.get(); }
        catch (EngineException rejected) { throw new ReferenceModel.Rejected(rejected.code().name()); }
    }
    private static <T> T io(IoAction<T> action) {
        try { return action.run(); }
        catch (LocalCheckpointStore.DirectorySyncUnsupportedException unavailable) {
            assumeTrue(false, "Required directory synchronization unavailable: " + unavailable);
            throw new AssertionError("Unreachable after failed assumption", unavailable);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    @FunctionalInterface private interface IoAction<T> { T run() throws IOException; }
}
