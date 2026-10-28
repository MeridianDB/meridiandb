// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.reference;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;
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
 * Sequential test oracle, deliberately outside the production JAR.
 * It stores whole transactions, searches history backwards and partitions query
 * windows with arbitrary-precision coordinates; it does not split mutable state.
 */
public final class ReferenceModel {
    private static final BigInteger FIRST = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger AFTER_LAST = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
    private final DatabaseId databaseId;
    private final List<Transaction> commits = new ArrayList<>();
    private final Map<RequestId, CommittedRequest> requests = new HashMap<>();

    public ReferenceModel(DatabaseId databaseId) {
        this.databaseId = Objects.requireNonNull(databaseId, "databaseId");
    }

    public CommitPosition head() {
        return new CommitPosition(databaseId, commits.size());
    }

    public CommitResult transact(Transaction request) {
        Objects.requireNonNull(request, "request");
        var previous = requests.get(request.requestId());
        if (previous != null) {
            if (!previous.request().operations().equals(request.operations())) {
                throw new Rejected("REQUEST_ID_REUSED");
            }
            return new CommitResult(previous.position(), true);
        }
        // The immutable request is completely constructed/validated before publication.
        var position = new CommitPosition(databaseId, commits.size() + 1L);
        commits.add(request);
        requests.put(request.requestId(), new CommittedRequest(request, position));
        return new CommitResult(position, false);
    }

    public Optional<CommitPosition> lookupRequest(RequestId requestId) {
        var request = requests.get(Objects.requireNonNull(requestId, "requestId"));
        return request == null ? Optional.empty() : Optional.of(request.position());
    }

    public CommitPosition openView(ReadCutoff cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        return switch (cutoff) {
            case ReadCutoff.Head ignored -> head();
            case ReadCutoff.At at -> validate(at.position());
        };
    }

    public TemporalResult query(CommitPosition view, TemporalQuery query) {
        validate(view);
        Objects.requireNonNull(query, "query");
        return switch (query) {
            case TemporalQuery.Get get -> new TemporalResult.Point(value(view, get.entity(), get.validAtMicros()));
            case TemporalQuery.Scan scan -> new TemporalResult.Segments(scan(view, scan));
            case TemporalQuery.Sum sum -> aggregate(view, sum);
        };
    }

    private CommitPosition validate(CommitPosition position) {
        if (position == null || !databaseId.equals(position.databaseId())
                || position.sequence() > commits.size()) {
            throw new Rejected("INVALID_CUTOFF");
        }
        return position;
    }

    private OptionalLong value(CommitPosition view, EntityId entity, long instant) {
        var point = BigInteger.valueOf(instant);
        for (int c = Math.toIntExact(view.sequence()) - 1; c >= 0; c--) {
            var operations = commits.get(c).operations();
            for (int i = operations.size() - 1; i >= 0; i--) {
                var operation = operations.get(i);
                var interval = operation.interval();
                if (operation.entity().equals(entity)
                        && lower(interval).compareTo(point) <= 0 && point.compareTo(upper(interval)) < 0) {
                    return switch (operation) {
                        case WriteOperation.Put put -> OptionalLong.of(put.value());
                        case WriteOperation.Retract ignored -> OptionalLong.empty();
                    };
                }
            }
        }
        return OptionalLong.empty();
    }

    private List<TemporalSegment> scan(CommitPosition view, TemporalQuery.Scan query) {
        var low = lower(query.window());
        var high = upper(query.window());
        var cuts = new TreeSet<BigInteger>();
        cuts.add(low);
        cuts.add(high);
        for (int c = 0; c < view.sequence(); c++) {
            for (var operation : commits.get(c).operations()) {
                if (operation.entity().equals(query.entity())) {
                    for (var bound : List.of(lower(operation.interval()), upper(operation.interval()))) {
                        if (low.compareTo(bound) < 0 && bound.compareTo(high) < 0) {
                            cuts.add(bound);
                        }
                    }
                }
            }
        }
        var cells = new ArrayList<Cell>();
        var ordered = new ArrayList<>(cuts);
        for (int i = 0; i + 1 < ordered.size(); i++) {
            var from = ordered.get(i);
            var to = ordered.get(i + 1);
            var present = value(view, query.entity(), from.longValueExact());
            if (present.isEmpty()) {
                continue;
            }
            long current = present.getAsLong();
            if (!cells.isEmpty() && cells.getLast().to().equals(from)
                    && cells.getLast().value() == current) {
                var previous = cells.removeLast();
                cells.add(new Cell(previous.from(), to, current));
            } else {
                cells.add(new Cell(from, to, current));
            }
        }
        return cells.stream().map(cell -> {
            Long from = query.window().fromInclusiveMicros() == null && cell.from().equals(FIRST)
                    ? null : cell.from().longValueExact();
            Long to = query.window().toExclusiveMicros() == null && cell.to().equals(AFTER_LAST)
                    ? null : cell.to().longValueExact();
            return new TemporalSegment(new ValidTimeInterval(from, to), cell.value());
        }).toList();
    }

    private TemporalResult.Aggregate aggregate(CommitPosition view, TemporalQuery.Sum query) {
        long count = 0;
        var sum = BigInteger.ZERO;
        for (var entity : query.entities()) {
            var present = value(view, entity, query.validAtMicros());
            if (present.isPresent()) {
                count++;
                sum = sum.add(BigInteger.valueOf(present.getAsLong()));
            }
        }
        return new TemporalResult.Aggregate(count, sum);
    }

    private static BigInteger lower(ValidTimeInterval interval) {
        return interval.fromInclusiveMicros() == null ? FIRST : BigInteger.valueOf(interval.fromInclusiveMicros());
    }

    private static BigInteger upper(ValidTimeInterval interval) {
        return interval.toExclusiveMicros() == null ? AFTER_LAST : BigInteger.valueOf(interval.toExclusiveMicros());
    }

    private record CommittedRequest(Transaction request, CommitPosition position) { }
    private record Cell(BigInteger from, BigInteger to, long value) { }

    /** Semantic fixture label, not a proposed public engine exception hierarchy. */
    public static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        public Rejected(String code) {
            super(code);
            this.code = code;
        }
        public String code() { return code; }
    }
}
