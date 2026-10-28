// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.engine;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.temporal.CommitPosition;
import org.meridiandb.temporal.CommitResult;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.ReadCutoff;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.TemporalQuery;
import org.meridiandb.temporal.TemporalResult;
import org.meridiandb.temporal.TemporalSegment;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/**
 * Experimental embedded engine retaining every commit in memory, without durability.
 * Writers serialize; readers capture immutable interval snapshots. Closing invalidates
 * future operations, while a query that already captured a view may finish.
 */
public final class InMemoryEngine implements AutoCloseable {
    private final DatabaseId databaseId;
    // One volatile publication includes values, historical cutoffs and request identities.
    // null marks the closed engine; all objects reachable from a published state are immutable.
    private volatile Published state;

    public InMemoryEngine() {
        this(new DatabaseId(UUID.randomUUID().toString()));
    }

    InMemoryEngine(DatabaseId databaseId) {
        this.databaseId = Objects.requireNonNull(databaseId, "databaseId");
        var genesis = new Snapshot(new CommitPosition(databaseId, 0), Map.of());
        state = new Published(genesis, Map.of(0L, genesis), Map.of());
    }

    public CommitPosition head() {
        return requireOpen().head().position();
    }

    /** Validated immutable requests commit entirely, or leave publication unchanged. */
    public synchronized CommitResult transact(Transaction request) {
        return publish(prepare(request));
    }

    /** All allocation and semantic work precedes any durable append or visible state change. */
    synchronized PreparedCommit prepare(Transaction request) {
        var before = requireOpen();
        Objects.requireNonNull(request, "request");
        var previous = before.requests().get(request.requestId());
        if (previous != null) {
            if (!previous.request().operations().equals(request.operations())) {
                throw new EngineException(EngineException.Code.REQUEST_ID_REUSED);
            }
            return new PreparedCommit(this, before, before, new CommitResult(previous.position(), true));
        }
        if (before.head().position().sequence() == Long.MAX_VALUE) {
            throw new EngineException(EngineException.Code.COMMIT_EXHAUSTED);
        }
        var values = new HashMap<>(before.head().values());
        for (var operation : request.operations()) {
            var updated = replace(values.getOrDefault(operation.entity(), List.of()), operation);
            if (updated.isEmpty()) {
                values.remove(operation.entity());
            } else {
                values.put(operation.entity(), updated);
            }
        }
        var position = new CommitPosition(databaseId, before.head().position().sequence() + 1);
        var snapshot = new Snapshot(position, Map.copyOf(values));
        var history = new HashMap<>(before.history());
        history.put(position.sequence(), snapshot);
        var requests = new HashMap<>(before.requests());
        requests.put(request.requestId(), new CommittedRequest(request, position));
        var after = new Published(snapshot, Map.copyOf(history), Map.copyOf(requests));
        return new PreparedCommit(this, before, after, new CommitResult(position, false));
    }

    /** On the valid path this only checks ownership/state, publishes one reference and returns a value. */
    synchronized CommitResult publish(PreparedCommit prepared) {
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.owner != this || requireOpen() != prepared.before) {
            throw new IllegalStateException("Prepared commit belongs to another engine or stale state");
        }
        state = prepared.after;
        return prepared.result;
    }

    static final class PreparedCommit {
        private final InMemoryEngine owner;
        private final Published before;
        private final Published after;
        private final CommitResult result;
        private PreparedCommit(InMemoryEngine owner, Published before, Published after, CommitResult result) {
            this.owner = owner;
            this.before = before;
            this.after = after;
            this.result = result;
        }
        CommitResult result() { return result; }
    }

    /** Restore one original commit; repeated identities are invalid history, never retry requests. */
    synchronized void restore(JournalRecord record) {
        var before = requireOpen();
        Objects.requireNonNull(record, "record");
        long previous = before.head().position().sequence();
        if (previous == Long.MAX_VALUE || record.sequence() != previous + 1
                || before.requests().containsKey(record.transaction().requestId())) {
            throw new java.io.UncheckedIOException(new IOException("Recovery requires contiguous unique commits"));
        }
        transact(record.transaction());
    }

    /** Current commit metadata, independent of a data view's cutoff. */
    public Optional<CommitPosition> lookupRequest(RequestId requestId) {
        var published = requireOpen();
        var committed = published.requests().get(Objects.requireNonNull(requestId, "requestId"));
        return committed == null ? Optional.empty() : Optional.of(committed.position());
    }

    /** Capture head once, or select an existing position from this engine's lineage. */
    public ReadView openView(ReadCutoff cutoff) {
        var published = requireOpen();
        Objects.requireNonNull(cutoff, "cutoff");
        var snapshot = switch (cutoff) {
            case ReadCutoff.Head ignored -> published.head();
            case ReadCutoff.At at -> {
                var position = at.position();
                var found = published.history().get(position.sequence());
                if (!databaseId.equals(position.databaseId()) || found == null) {
                    throw new EngineException(EngineException.Code.INVALID_CUTOFF);
                }
                yield found;
            }
        };
        return new ReadView(this, snapshot);
    }

    /** Idempotent; no data or request identity survives reopening a new instance. */
    @Override
    public synchronized void close() {
        state = null;
    }

    private Published requireOpen() {
        var published = state;
        if (published == null) {
            throw new IllegalStateException("Engine is closed");
        }
        return published;
    }

    private static List<TemporalSegment> replace(List<TemporalSegment> existing, WriteOperation operation) {
        // Normalize equivalent lower edges only in state; preserve original request fields for retries.
        var changed = stateInterval(operation.interval());
        var result = new ArrayList<TemporalSegment>();
        for (var segment : existing) {
            var old = segment.interval();
            if (!overlaps(old, changed)) {
                result.add(segment);
                continue;
            }
            if (compareLower(old.fromInclusiveMicros(), changed.fromInclusiveMicros()) < 0) {
                result.add(new TemporalSegment(new ValidTimeInterval(
                        old.fromInclusiveMicros(), changed.fromInclusiveMicros()), segment.value()));
            }
            if (compareUpper(changed.toExclusiveMicros(), old.toExclusiveMicros()) < 0) {
                result.add(new TemporalSegment(new ValidTimeInterval(
                        changed.toExclusiveMicros(), old.toExclusiveMicros()), segment.value()));
            }
        }
        if (operation instanceof WriteOperation.Put put) {
            result.add(new TemporalSegment(changed, put.value()));
        }
        result.sort((a, b) -> compareLower(a.interval().fromInclusiveMicros(), b.interval().fromInclusiveMicros()));
        var merged = new ArrayList<TemporalSegment>();
        for (var next : result) {
            if (!merged.isEmpty()) {
                var previous = merged.getLast();
                if (previous.value() == next.value() && previous.interval().toExclusiveMicros() != null
                        && previous.interval().toExclusiveMicros().equals(next.interval().fromInclusiveMicros())) {
                    merged.removeLast();
                    merged.add(new TemporalSegment(new ValidTimeInterval(previous.interval().fromInclusiveMicros(),
                            next.interval().toExclusiveMicros()), next.value()));
                    continue;
                }
            }
            merged.add(next);
        }
        return List.copyOf(merged);
    }

    private static ValidTimeInterval stateInterval(ValidTimeInterval interval) {
        Long from = interval.fromInclusiveMicros();
        return from != null && from == Long.MIN_VALUE
                ? new ValidTimeInterval(null, interval.toExclusiveMicros()) : interval;
    }

    private static boolean overlaps(ValidTimeInterval a, ValidTimeInterval b) {
        return !before(a, b) && !before(b, a);
    }

    private static boolean before(ValidTimeInterval a, ValidTimeInterval b) {
        return a.toExclusiveMicros() != null && b.fromInclusiveMicros() != null
                && a.toExclusiveMicros() <= b.fromInclusiveMicros();
    }

    private static int compareLower(Long a, Long b) {
        return a == null ? (b == null ? 0 : -1) : b == null ? 1 : Long.compare(a, b);
    }

    private static int compareUpper(Long a, Long b) {
        return a == null ? (b == null ? 0 : 1) : b == null ? -1 : Long.compare(a, b);
    }

    private static OptionalLong point(Snapshot snapshot, EntityId entity, long instant) {
        for (var segment : snapshot.values().getOrDefault(entity, List.of())) {
            if (segment.interval().contains(instant)) {
                return OptionalLong.of(segment.value());
            }
        }
        return OptionalLong.empty();
    }

    private static TemporalResult query(Snapshot snapshot, TemporalQuery query) {
        return switch (query) {
            case TemporalQuery.Get get -> new TemporalResult.Point(point(snapshot, get.entity(), get.validAtMicros()));
            case TemporalQuery.Scan scan -> {
                var result = new ArrayList<TemporalSegment>();
                for (var segment : snapshot.values().getOrDefault(scan.entity(), List.of())) {
                    if (!overlaps(segment.interval(), scan.window())) {
                        continue;
                    }
                    Long from = compareLower(segment.interval().fromInclusiveMicros(), scan.window().fromInclusiveMicros()) >= 0
                            ? segment.interval().fromInclusiveMicros() : scan.window().fromInclusiveMicros();
                    Long to = compareUpper(segment.interval().toExclusiveMicros(), scan.window().toExclusiveMicros()) <= 0
                            ? segment.interval().toExclusiveMicros() : scan.window().toExclusiveMicros();
                    result.add(new TemporalSegment(new ValidTimeInterval(from, to), segment.value()));
                }
                yield new TemporalResult.Segments(result);
            }
            case TemporalQuery.Sum sum -> {
                long count = 0;
                var total = BigInteger.ZERO;
                for (var entity : sum.entities()) {
                    var present = point(snapshot, entity, sum.validAtMicros());
                    if (present.isPresent()) {
                        count++;
                        total = total.add(BigInteger.valueOf(present.getAsLong()));
                    }
                }
                yield new TemporalResult.Aggregate(count, total);
            }
        };
    }

    /** Thread-safe pinned snapshot. Closing either owner or view rejects later operations. */
    public static final class ReadView implements AutoCloseable {
        private final InMemoryEngine owner;
        private volatile Snapshot snapshot;

        private ReadView(InMemoryEngine owner, Snapshot snapshot) {
            this.owner = owner;
            this.snapshot = snapshot;
        }

        public CommitPosition position() {
            return capture().position();
        }

        public TemporalResult query(TemporalQuery query) {
            var captured = capture();
            return InMemoryEngine.query(captured, Objects.requireNonNull(query, "query"));
        }

        private Snapshot capture() {
            owner.requireOpen();
            var captured = snapshot;
            if (captured == null) {
                throw new IllegalStateException("Read view is closed");
            }
            return captured;
        }

        @Override
        public void close() {
            snapshot = null;
        }
    }

    private record Snapshot(CommitPosition position, Map<EntityId, List<TemporalSegment>> values) { }
    private record CommittedRequest(Transaction request, CommitPosition position) { }
    private record Published(Snapshot head, Map<Long, Snapshot> history, Map<RequestId, CommittedRequest> requests) { }
}
