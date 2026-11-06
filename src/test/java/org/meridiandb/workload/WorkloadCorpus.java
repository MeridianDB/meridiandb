// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.workload;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.ValuePredicate;
import org.meridiandb.temporal.WriteOperation;

/** Test/developer workload inputs, without clocks, database execution or timing results. */
public final class WorkloadCorpus {
    public static final int VERSION = 1;
    public static final DatabaseId DATABASE = new DatabaseId("workload-v1");
    public static final long MAX_REFERENCE_WORK = 32_000_000;
    public static final long MAX_ENCODED_BYTES = 16 * 1024 * 1024;
    public static final long WRITE_SALT = 0x13579bdf2468ace0L;
    public static final long READ_SALT = 0x275a3c691dbf804eL;
    public static final long RETRY_SALT = 0x691fe5d7320cab84L;

    public record Spec(long seed, int entities, int commits, int queries, int intervalWidth,
                       int correctionPercent, int retractionPercent, int repeatPercent,
                       int retryPercent, int hotEntityPercent, int historicalReadPercent) {
        public Spec {
            if (entities < 1 || entities > 1024 || commits <= entities || commits > 4096
                    || queries < 0 || queries > 1024 || intervalWidth < 1 || intervalWidth > 16) {
                throw new IllegalArgumentException("Workload dimensions exceed v1 bounds");
            }
            for (int percent : new int[] {correctionPercent, retractionPercent, repeatPercent,
                    retryPercent, hotEntityPercent, historicalReadPercent}) {
                if (percent < 0 || percent > 100) {
                    throw new IllegalArgumentException("Workload percentages must be in [0,100]");
                }
            }
            if (correctionPercent + retractionPercent + repeatPercent > 100) {
                throw new IllegalArgumentException("Write intent percentages exceed 100");
            }
            long work = (long) commits * commits * (intervalWidth + 1)
                    + (long) commits * queries * (intervalWidth + Math.min(entities, 16) + 2);
            if (work > MAX_REFERENCE_WORK) {
                throw new IllegalArgumentException("Workload reference-work admission exceeded");
            }
        }

        public long initialRandomState(long streamSalt) {
            return (seed ^ streamSalt ^ 0x5deece66dL) & ((1L << 48) - 1);
        }
    }

    public enum Profile {
        APPEND, CORRECTIONS, RETRACTIONS, HOT_ENTITY;

        public Spec spec(long seed) {
            return spec(seed, 16, 128, 64, 8);
        }

        public Spec spec(long seed, int entities, int commits, int queries, int intervalWidth) {
            return switch (this) {
                case APPEND -> new Spec(seed, entities, commits, queries, intervalWidth, 0, 0, 0, 5, 0, 50);
                case CORRECTIONS -> new Spec(seed, entities, commits, queries, intervalWidth, 70, 10, 10, 10, 10, 75);
                case RETRACTIONS -> new Spec(seed, entities, commits, queries, intervalWidth, 20, 60, 10, 10, 10, 50);
                case HOT_ENTITY -> new Spec(seed, entities, commits, queries, intervalWidth, 60, 10, 10, 20, 95, 80);
            };
        }
    }

    public enum Stage { SEED, MIXED }
    public enum Intent { SEED, APPEND, CORRECTION, RETRACTION, REPEAT, RETRY }
    public sealed interface Action permits Submit, Read { }

    /** Expected sequence is an input declaration, independently checked by the oracle. */
    public record Submit(long sequence, Transaction request, Stage stage, Intent intent) implements Action {
        public Submit {
            if (sequence <= 0) {
                throw new IllegalArgumentException("Positive submission sequence required");
            }
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(intent, "intent");
            if (request.operations().size() != 1) {
                throw new IllegalArgumentException("V1 workload requests have one operation");
            }
        }
        public boolean retry() { return intent == Intent.RETRY; }
    }

    /** Numeric cutoff is pinned at this action; it is never resolved again as Head. */
    public record Read(long cutoff, TemporalQuery query) implements Action {
        public Read {
            if (cutoff < 0) {
                throw new IllegalArgumentException("Nonnegative read cutoff required");
            }
            Objects.requireNonNull(query, "query");
        }
    }

    private final Spec spec;
    private final List<Action> actions;
    private final String fingerprint;
    private final String historyFingerprint;
    private final long encodedHistoryBytes;

    public WorkloadCorpus(Spec spec, List<Action> actions) {
        this.spec = Objects.requireNonNull(spec, "spec");
        if (actions.size() > 2L * spec.commits() + spec.queries()) {
            throw new IllegalArgumentException("Aggregate workload action bound exceeded");
        }
        int commits = 0;
        int reads = 0;
        long bytes = JournalCodec.encodeHeader(DATABASE).length;
        long attemptBytes = 0;
        for (Action action : actions) {
            Objects.requireNonNull(action, "action");
            switch (action) {
                case Submit submit -> {
                    boolean seed = !submit.retry() && commits < spec.entities();
                    if ((submit.stage() == Stage.SEED) != seed || (submit.intent() == Intent.SEED) != seed
                            || (submit.retry() && commits < spec.entities())) {
                        throw new IllegalArgumentException("Seed submissions must precede mixed submissions and retries");
                    }
                    if (submit.sequence() > spec.commits()) {
                        throw new IllegalArgumentException("Submission exceeds workload cutoff");
                    }
                    WriteOperation operation = submit.request().operations().getFirst();
                    checkEntity(operation.entity());
                    checkWindow(operation.interval());
                    if (submit.request().requestId().value().length() > 128) {
                        throw new IllegalArgumentException("Workload request identity bound exceeded");
                    }
                    int length = JournalCodec.encodedRecordBytes(new JournalRecord(submit.sequence(), submit.request()));
                    attemptBytes += length;
                    if (!submit.retry()) {
                        commits++;
                        bytes += length;
                    }
                }
                case Read read -> {
                    if (read.cutoff() > spec.commits()) {
                        throw new IllegalArgumentException("Read exceeds workload cutoff");
                    }
                    checkQuery(read.query());
                    reads++;
                }
            }
            if (bytes > MAX_ENCODED_BYTES || attemptBytes > MAX_ENCODED_BYTES) {
                throw new IllegalArgumentException("Aggregate workload encoded-byte bound exceeded");
            }
        }
        if (commits != spec.commits() || reads != spec.queries()) {
            throw new IllegalArgumentException("Workload action totals differ from specification");
        }
        this.actions = List.copyOf(actions);
        encodedHistoryBytes = bytes;
        historyFingerprint = digest(true);
        fingerprint = digest(false);
    }

    public Spec spec() { return spec; }
    public List<Action> actions() { return actions; }
    public String fingerprint() { return fingerprint; }
    public String historyFingerprint() { return historyFingerprint; }
    public long encodedHistoryBytes() { return encodedHistoryBytes; }

    public static WorkloadCorpus generate(Spec spec) {
        var writes = new Random(spec.seed() ^ WRITE_SALT);
        var reads = new Random(spec.seed() ^ READ_SALT);
        var retries = new Random(spec.seed() ^ RETRY_SALT);
        var actions = new ArrayList<Action>(2 * spec.commits() + spec.queries());
        var original = new ArrayList<Submit>(spec.commits());
        var primary = new Long[spec.entities()][spec.intervalWidth()];
        var frontier = new long[spec.entities()];
        Arrays.fill(frontier, spec.intervalWidth());
        int emittedReads = 0;
        for (int sequence = 1; sequence <= spec.commits(); sequence++) {
            boolean seed = sequence <= spec.entities();
            int choice = seed ? -1 : writes.nextInt(100);
            Intent intent = seed ? Intent.SEED : choice < spec.repeatPercent() ? Intent.REPEAT
                    : choice < spec.repeatPercent() + spec.retractionPercent() ? Intent.RETRACTION
                    : choice < spec.repeatPercent() + spec.retractionPercent() + spec.correctionPercent()
                            ? Intent.CORRECTION : Intent.APPEND;
            WriteOperation operation;
            if (intent == Intent.REPEAT) {
                operation = original.getLast().request().operations().getFirst();
            } else {
                int entity = seed ? sequence - 1 : chooseEntity(writes, spec);
                long value = sequence % 17 == 1 ? Long.MIN_VALUE
                        : sequence % 17 == 0 ? Long.MAX_VALUE : writes.nextLong();
                EntityId identity = entity(entity);
                if (seed) {
                    operation = new WriteOperation.Put(identity, new ValidTimeInterval(0L,
                            (long) spec.intervalWidth()), value);
                    Arrays.fill(primary[entity], value);
                } else if (intent == Intent.APPEND) {
                    long from = frontier[entity];
                    frontier[entity] += spec.intervalWidth();
                    operation = new WriteOperation.Put(identity,
                            new ValidTimeInterval(from, frontier[entity]), value);
                } else {
                    int start = writes.nextInt(spec.intervalWidth());
                    int visited = 0;
                    while (visited < spec.intervalWidth() && primary[entity][start] == null) {
                        start = (start + 1) % spec.intervalWidth();
                        visited++;
                    }
                    if (visited == spec.intervalWidth()) {
                        // Restore an empty primary window; realized metadata records this Put.
                        operation = new WriteOperation.Put(identity,
                                new ValidTimeInterval(0L, (long) spec.intervalWidth()), value);
                        Arrays.fill(primary[entity], value);
                    } else {
                        int end = start + 1 + writes.nextInt(spec.intervalWidth() - start);
                        var interval = new ValidTimeInterval((long) start, (long) end);
                        boolean retract = intent == Intent.RETRACTION;
                        operation = retract ? new WriteOperation.Retract(identity, interval)
                                : new WriteOperation.Put(identity, interval, value);
                        Arrays.fill(primary[entity], start, end, retract ? null : value);
                    }
                }
            }
            var request = new Transaction(new RequestId("r-" + sequence), List.of(operation));
            var submit = new Submit(sequence, request, seed ? Stage.SEED : Stage.MIXED, intent);
            actions.add(submit);
            original.add(submit);
            if (!seed) {
                if (retries.nextInt(100) < spec.retryPercent()) {
                    Submit prior = original.get(retries.nextInt(original.size()));
                    actions.add(new Submit(prior.sequence(), prior.request(), Stage.MIXED, Intent.RETRY));
                }
                int due = (sequence - spec.entities()) * spec.queries() / (spec.commits() - spec.entities());
                while (emittedReads < due) {
                    long cutoff = reads.nextInt(100) < spec.historicalReadPercent()
                            ? reads.nextInt(sequence) : sequence;
                    actions.add(new Read(cutoff, query(reads, spec, frontier, emittedReads++)));
                }
            }
        }
        return new WorkloadCorpus(spec, actions);
    }

    private static int chooseEntity(Random random, Spec spec) {
        return random.nextInt(100) < spec.hotEntityPercent() ? 0 : random.nextInt(spec.entities());
    }

    private static EntityId entity(int index) { return new EntityId("e-" + index); }

    private static TemporalQuery query(Random random, Spec spec, long[] frontier, int index) {
        int selected = chooseEntity(random, spec);
        EntityId entity = index % 7 == 0 ? new EntityId("absent-" + index) : entity(selected);
        long point = switch (index % 3) {
            case 0 -> random.nextInt(spec.intervalWidth());
            case 1 -> frontier[selected] - spec.intervalWidth() + random.nextInt(spec.intervalWidth());
            default -> frontier[selected] + spec.intervalWidth();
        };
        var window = new ValidTimeInterval(point, point + spec.intervalWidth());
        var selectedEntities = new ArrayList<EntityId>();
        for (int i = Math.min(spec.entities(), 16) - 1; i >= 0; i--) {
            selectedEntities.add(entity((selected + i) % spec.entities()));
        }
        selectedEntities.add(new EntityId("absent-selection"));
        long threshold = switch ((index / 5) % 5) {
            case 0 -> Long.MIN_VALUE;
            case 1 -> Long.MAX_VALUE;
            default -> index % 3 - 1;
        };
        var predicate = new ValuePredicate(ValuePredicate.Operator.values()[index % 6], threshold);
        return switch (index % 5) {
            case 0 -> new TemporalQuery.Get(entity, point);
            case 1 -> new TemporalQuery.Scan(entity, window);
            case 2 -> new TemporalQuery.Select(selectedEntities, point, predicate);
            case 3 -> new TemporalQuery.FilterScan(entity, window, predicate);
            default -> new TemporalQuery.Sum(selectedEntities, point);
        };
    }

    private static void checkEntity(EntityId entity) {
        if (entity.value().length() > 128) {
            throw new IllegalArgumentException("Workload entity identity bound exceeded");
        }
    }

    private void checkWindow(ValidTimeInterval window) {
        Long from = window.fromInclusiveMicros(), to = window.toExclusiveMicros();
        // A valid finite span wider than signed long range wraps negative; reject it.
        if (from == null || to == null || to - from <= 0 || to - from > spec.intervalWidth()) {
            throw new IllegalArgumentException("V1 workload windows must fit the configured finite width");
        }
    }

    private void checkQuery(TemporalQuery query) {
        switch (query) {
            case TemporalQuery.Get get -> checkEntity(get.entity());
            case TemporalQuery.Scan scan -> { checkEntity(scan.entity()); checkWindow(scan.window()); }
            case TemporalQuery.FilterScan scan -> { checkEntity(scan.entity()); checkWindow(scan.window()); }
            case TemporalQuery.Select select -> checkEntities(select.entities());
            case TemporalQuery.Sum sum -> checkEntities(sum.entities());
        }
    }

    private void checkEntities(List<EntityId> entities) {
        if (entities.size() > Math.min(spec.entities(), 16) + 1) {
            throw new IllegalArgumentException("Workload query fanout exceeds configured admission");
        }
        entities.forEach(WorkloadCorpus::checkEntity);
    }

    private String digest(boolean historyOnly) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                if (historyOnly) {
                    out.write(JournalCodec.encodeHeader(DATABASE));
                } else {
                    text(out, "meridiandb-workload");
                    out.writeInt(VERSION);
                    out.writeLong(spec.seed());
                    for (int value : new int[] {spec.entities(), spec.commits(), spec.queries(),
                            spec.intervalWidth(), spec.correctionPercent(), spec.retractionPercent(),
                            spec.repeatPercent(), spec.retryPercent(), spec.hotEntityPercent(),
                            spec.historicalReadPercent()}) { out.writeInt(value); }
                    out.writeInt(actions.size());
                }
                for (Action action : actions) {
                    switch (action) {
                        case Submit submit -> {
                            if (historyOnly && submit.retry()) { continue; }
                            if (!historyOnly) {
                                out.writeByte(1);
                                text(out, submit.stage().name());
                                text(out, submit.intent().name());
                            }
                            byte[] frame = JournalCodec.encodeRecord(new JournalRecord(submit.sequence(), submit.request()));
                            if (!historyOnly) { out.writeInt(frame.length); }
                            out.write(frame);
                        }
                        case Read read -> {
                            if (!historyOnly) {
                                out.writeByte(2);
                                out.writeLong(read.cutoff());
                                writeQuery(out, read.query());
                            }
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void text(DataOutputStream out, String text) throws IOException {
        out.writeInt(text.length());
        out.writeChars(text);
    }

    private static void window(DataOutputStream out, ValidTimeInterval window) throws IOException {
        // Explicit null tags preserve the temporal bound representation if a later version permits it.
        for (Long bound : new Long[] {window.fromInclusiveMicros(), window.toExclusiveMicros()}) {
            out.writeBoolean(bound != null);
            if (bound != null) { out.writeLong(bound); }
        }
    }

    private static void entities(DataOutputStream out, List<EntityId> entities) throws IOException {
        out.writeInt(entities.size());
        for (EntityId entity : entities) { text(out, entity.value()); }
    }

    private static void predicate(DataOutputStream out, ValuePredicate predicate) throws IOException {
        text(out, predicate.operator().name());
        out.writeLong(predicate.operand());
    }

    private static void writeQuery(DataOutputStream out, TemporalQuery query) throws IOException {
        switch (query) {
            case TemporalQuery.Get get -> { out.writeByte(1); text(out, get.entity().value()); out.writeLong(get.validAtMicros()); }
            case TemporalQuery.Scan scan -> { out.writeByte(2); text(out, scan.entity().value()); window(out, scan.window()); }
            case TemporalQuery.Select select -> {
                out.writeByte(3); entities(out, select.entities()); out.writeLong(select.validAtMicros()); predicate(out, select.predicate());
            }
            case TemporalQuery.FilterScan scan -> {
                out.writeByte(4); text(out, scan.entity().value()); window(out, scan.window()); predicate(out, scan.predicate());
            }
            case TemporalQuery.Sum sum -> { out.writeByte(5); entities(out, sum.entities()); out.writeLong(sum.validAtMicros()); }
        }
    }
}
