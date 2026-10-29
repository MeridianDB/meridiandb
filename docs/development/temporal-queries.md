---
title: Temporal query operators
description: Query one pinned temporal snapshot with signed numeric predicates, deterministic entity selection, clipped interval scans and exact correction-sensitive sums in Java.
---

# Temporal query operators

The experimental Java API queries one immutable committed view at a time.
`InMemoryEngine.ReadView` and `LocalDatabase.ReadView` accept the same typed
queries. A view selects a database knowledge cutoff; each query supplies a valid
instant or interval. Both time dimensions remain explicit.

## Operators and results

| Query | Result | Ordering and bounds |
| --- | --- | --- |
| `Get(entity, validAtMicros)` | `Point(OptionalLong)` | One present value or explicit absence |
| `Scan(entity, window)` | `Segments(List<TemporalSegment>)` | Present intervals clipped to the window, ascending valid start, adjacent equal values coalesced |
| `Select(entities, validAtMicros, predicate)` | `Rows(List<EntityValue>)` | Matching present entities, ascending exact entity string order; at most one row per candidate |
| `FilterScan(entity, window, predicate)` | `Segments(List<TemporalSegment>)` | Matching resolved intervals with the same clipping and ordering as `Scan`; omitted values and absence remain gaps |
| `Sum(entities, validAtMicros)` | `Aggregate(count, BigInteger sum)` | Present count and exact mathematical sum; empty input returns zero/zero |

Candidate lists for `Select` and `Sum` contain distinct nonnull entity IDs. Empty
lists are valid and inspect no entities. Constructors copy the lists, reject
repeated identities and retain exact strings without normalization. Selection
returns only the supplied candidates; it does not enumerate the database.

Selection sorts by `EntityId.value().compareTo`: Java's lexicographic UTF-16 code
unit order, independent of candidate order, hash iteration, locale or numeric suffix.
Case and normalization-distinct identities remain distinct. This order can differ
from Unicode code-point order for supplementary characters. Journal identities retain
those same UTF-16 code units, including embedded zero and lone surrogates.

## Numeric predicates

`new ValuePredicate(operator, operand)` compares a **present** signed 64-bit value
against one signed 64-bit operand:

| Operator | Match |
| --- | --- |
| `EQ` | Equal |
| `NE` | Not equal |
| `LT` | Less than |
| `LE` | Less than or equal |
| `GT` | Greater than |
| `GE` | Greater than or equal |

Comparisons use signed relations without subtraction, so minimum/maximum values
cannot overflow the comparison. Zero and negative values are ordinary values.
Absence matches **none** of the operators, including `NE`; it is not a zero value.
The predicate and query arguments are immutable data, with no application callback
invoked during a query. Predicate composition, arbitrary functions and SQL syntax
are outside the current API.

## Resolve temporal state before filtering

For a fixed entity, valid instant and committed cutoff, the latest applicable
operation determines the value or absence. Only then is its value compared.
An older matching put cannot reappear because a newer correction fails a predicate.
A retraction cannot reveal an earlier matching value either.

For example, Alice is 100 everywhere at commit one. Commit two replaces `[10,20)`
with 120; commit three retracts `[12,14)`. At cutoff three, filtering for `EQ 100`
returns only the parts outside `[10,20)`. Filtering for `GE 110` returns
`[10,12)` and `[14,20)`. The retracted gap stays absent. Earlier cutoffs still
return their original results.

A filtered scan clips matching intervals to the requested half-open window.
It retains the original scan's canonical unbounded endpoint handling at finite
coordinate limits. Equal matches separated by absence or an excluded value stay
separate. A completely absent or rejected window returns an empty list.

## Java example

With an open `view`, explicit candidate IDs and valid instant:

```java
var query = new TemporalQuery.Select(List.of(bob, alice), 15,
        new ValuePredicate(ValuePredicate.Operator.GT, 75));
var rows = (TemporalResult.Rows) view.query(query);

var matchingIntervals = view.query(new TemporalQuery.FilterScan(alice,
        new ValidTimeInterval(0L, 30L),
        new ValuePredicate(ValuePredicate.Operator.GE, 110)));
```

Types are in `org.meridiandb.temporal`; `List` is `java.util.List`.
Use `openView(ReadCutoff.Head.INSTANCE)` to capture head once, or
`new ReadCutoff.At(position)` for an existing historical commit in that lineage.
A future or foreign cutoff fails rather than waiting or clamping. There is no
hidden wall-clock valid instant.

Run the complete example from the repository root after `./mvnw -B verify`:

```bash
java --class-path target/classes examples/QueryExample.java
```

It checks a pinned pre-correction view, a new view, selection and filtered interval
answers, and the exact sum before/after a correction and retraction. The same query
values work on [local database views](local-database.md), including verified reopen.

## Snapshot lifetimes and execution costs

Every query captures one immutable snapshot before reading any entity. Selection
and aggregation therefore cannot mix commits across candidates. A pinned view's
position and answers remain unchanged during writer commits. Opening a new head
view observes newer knowledge. Query operations share the existing view/owner close
contract: close rejects future operations; a call that already captured state may
finish. Returned rows, segments and aggregate values remain immutable heap values
after view/database close and hold no file or cursor handle.

Execution is eager. `Get` linearly examines an entity's intervals. `Select` examines
candidate entities' interval lists and sorts its matching rows, adding
`O(m log m)` sorting for `m` matches. `Scan` and `FilterScan` visit the requested
entity's retained state intervals; filtering allocates only matching clipped
segments. `Sum` uses exact `BigInteger` arithmetic over present candidates.

The explicit candidate list and valid-time window bound logical scope. They do
not impose a total query-memory or work budget: all matching results are materialized,
and even a narrow window may inspect the full entity interval list. There is no
index, pagination, streaming cursor or cancellation contract. Retained history,
replay costs and writer map-copy costs remain those of the
[embedded engine](embedded-engine.md) and [local database](local-database.md).
No latency or throughput result is implied.

## Verify and reproduce

The [query fixtures](../architecture/fixtures/temporal-query-v0.json) add exact
expected selection/filter answers while retaining the original temporal histories.
The reference evaluates comparisons independently and resolves transaction history
backwards; production reads materialized interval state. Persisted-seed comparisons
also use forward dense cells to check clipped intervals and rows.

```bash
./mvnw -B -Dtest=QueryTypesTest,QueryLifecycleTest,QueryFixtureTest,QueryPropertiesTest test
```

Reproduce a reported seed:

```bash
./mvnw -B -Dtest=QueryPropertiesTest -Dmeridiandb.seed=42 test
```

Coverage includes all comparison operators, extreme values/time coordinates,
absence, empty inputs, duplicate rejection, exact entity ordering, corrections,
retractions, historical cutoffs and real writable reopen with checkpoint tails.
Concurrent checks verify new operators within pinned views while atomic writes run.
Directory-sync capability may prevent local-file cases on an unsupported provider;
memory/reference checks remain independent. The full suite retains the separate
[durability conformance](durability-conformance.md) boundaries.
