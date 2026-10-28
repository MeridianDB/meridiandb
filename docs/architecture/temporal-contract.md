---
title: Initial temporal contract
description: MeridianDB's initial bitemporal specification defines interval replacement, retraction, atomic commits, historical reads and retry identity, with exact workload fixtures.
---

# Initial temporal contract

**Status:** working specification v0, adopted for the initial prototype on
October 28, 2026. No Java engine implements it yet. The
[decision record](../decisions/0003-initial-temporal-semantics.md) explains the
choices and alternatives. The [JSON histories](fixtures/temporal-contract-v0.json)
provide exact expectations for later Java tests; their notation is a test format,
not a public API, query language or persistence format.

## Scope and values

One database has one writable engine instance, which serializes transactions.
Readers may run concurrently against immutable committed views. Multiple writable
instances or processes sharing storage are outside this contract; a backend must
reject unsupported writer ownership rather than imply safe coordination.

An entity ID is a nonempty, case-sensitive string, compared exactly without Unicode
normalization. Each entity has at most one whole value at a valid instant and a
knowledge cutoff. The initial conformance value is a signed 64-bit integer; `0`
and negative values are ordinary values. IDs and values cannot be null. A put
replaces the whole value, without field merging. Absence is distinct from every
value. Documents, attributes, schema, cross-entity constraints and public value
encodings require later decisions; this numeric domain is enough to exercise the
first temporal model and aggregates.

## Valid time

A finite valid instant is a signed 64-bit count of microseconds since
`1970-01-01T00:00:00Z`. It denotes UTC instants using Unix time; leap seconds are not
represented. Inputs must be exactly representable: finer precision is rejected,
never rounded. Calendar dates, local time zones and daylight-saving
rules are application concerns; convert them to explicit UTC instants first.

An interval is **half open** `[from, to)`: include the lower bound and exclude the
upper bound. Either endpoint may be unbounded. In fixtures, `null` in `from` means
negative infinity; `null` in `to` means positive infinity. Finite minimum/maximum
64-bit coordinates remain ordinary instants, not infinity sentinels. A point
lookup always requires a finite instant. An interval must contain at least one
representable finite instant; empty or reversed intervals are invalid. In particular, `[unbounded, MIN_VALUE)`
is empty, whereas `[MAX_VALUE, unbounded)` contains the maximum finite instant.

Fixture instants are JSON integers in epoch microseconds; for example,
`2026-02-01T00:00:00.000000Z` is `1769904000000000`. A fractional coordinate is
invalid. Dates in the tables below abbreviate midnight UTC in 2026. An external
timestamp parser or Java time API has not been selected.

## Writes, overlap and retraction

A transaction contains a request ID and a nonempty ordered list of operations:

| Operation | Effect in its valid interval |
| --- | --- |
| `put(entity, from, to, value)` | Replace any existing value, or fill absence, with the supplied whole value |
| `retract(entity, from, to)` | Make the entity absent, including where it was already absent |

An operation changes only its interval. Values before and after that interval
remain intact, which may split a formerly continuous value. Later operations in
the same transaction take precedence within an overlap. Later committed
transactions take precedence over earlier ones. There is no conflict rejection,
implicit effective date or priority derived from an event timestamp.

More precisely, for entity `e`, finite valid instant `t` and cutoff `c`, consider
all operations in commits `1..c` whose entity is `e` and whose interval contains
`t`. Select the operation with the greatest `(commit position, operation index)`.
A put supplies its value; a retraction supplies absence. No matching operation
also means absence. This rule defines the logical answer independently of any
interval-splitting implementation or storage layout.

Retraction preserves earlier knowledge. It is neither physical erasure nor a
privacy/deletion mechanism. Reusing a formerly retracted interval with a later put
is allowed. The initial contract retains all committed cutoffs and request
identities; compaction may change representation but not these answers. Any later
history-removal policy must explicitly revise that retention promise.

## Commit positions and atomic visibility

Each database starts at cutoff `0`, an empty genesis view. Each successful new
request receives the next positive signed 64-bit commit position: `1`, `2`, and so
on. Positions are contiguous in committed history, never wrap and are meaningful
only within that database's lineage. Exhaustion rejects a new commit. Event time,
wall-clock time and operation effective dates neither assign nor reorder them.
Mapping a calendar-time question to a commit cutoff is outside v0.

Validate the entire request before committing. An invalid operation rejects the
whole transaction, leaves the head unchanged and reserves no request ID. A valid
transaction is atomic across all operations and entities: readers observe either
the prior committed view or the whole new view. There is no dirty read or view
between operations of one transaction. Writer serialization gives a total commit
order; simultaneous callers must not assume a particular arrival order.

A new request still gets a commit if its operations leave the logical values
unchanged, for example retracting an absent entity or putting an equal value. Its
identity and ordering remain observable. A replay of an already committed request
does not get a new position.

A successful write acknowledgement means the commit is published and a subsequent
new head view includes it. The memory-only implementation will promise visibility
within its running instance, not survival of close or crash. A durable
implementation must recover every acknowledged commit and its request identity,
and make the durable commit recoverable before publishing it to readers. Recovery
may additionally expose complete committed requests whose acknowledgement was
lost. Recovery must never expose part of a transaction. Exact persistence steps
and crash tests belong to the journal/recovery queue items; no durability is
implemented by this specification.

## Read views and logical queries

Opening a view resolves either an explicit cutoff or `head`. The cutoff is an
integer from `0` through the currently published head, inclusive; negative,
foreign-database or future cutoffs are errors. It never silently clamps or waits.
`head` captures the latest published position once, at view creation. The view is
then pinned: subsequent commits cannot change any of its answers. Callers open a
new head view to see new knowledge. Queries in one view share the same cutoff,
including every entity used by an aggregate.

| Logical query | Required result |
| --- | --- |
| `get(e, t)` | Explicit presence and the whole value at finite valid instant `t`, or explicit absence |
| `scan(e, from, to)` | All present intervals within the requested window, clipped to it and sorted by start |
| `sum(entities, t)` | Count of present entities and their exact mathematical integer sum at `t` |

Scan results contain nonempty, nonoverlapping intervals. Coalesce adjacent
intervals with equal values, even when different commits produced them. Omit
absence; a gap remains a gap. Return an empty list when nothing is present.
Unbounded scan endpoints are allowed under the same interval rules as writes.
For canonical output, when an unbounded window starts with a present minimum
finite instant, the first segment starts unbounded; likewise a present maximum
finite instant extends the last segment to an unbounded window end. Finite query
bounds still clip the result. This avoids two spellings of the same state at the
coordinate limits. This is a state interval scan at one cutoff, not a change-log
query.

An aggregate takes an explicit set represented by a list of distinct, nonempty
entity IDs. Repeated IDs are invalid rather than double counted. Ignore absent
entities; include present zero values in the count. An empty set or no present
values returns count `0` and sum `0`. Use exact arithmetic, including sums outside
the signed 64-bit value range. Fixtures encode sums as decimal strings to preserve
that precision. This is a point-in-valid-time aggregate, not salary proration,
a duration-weighted integral or a grouping/public query language.

“Current state” always means a new head view **and an explicitly supplied valid
instant**. There is no hidden wall-clock read in the logical query contract.

## Request identity and uncertain outcomes

Every transaction has a nonempty, case-sensitive request ID scoped to its database
lineage. Normalize the logical fields and validate the full request, then compare
against committed identity records. Equality includes entity IDs, values, normalized
endpoints and the ordered operations; it does not depend on JSON whitespace or
object-key order. Preserve finite versus unbounded endpoints and operation order
during normalization; compare request fields, not resulting database states.

| Submission | Result |
| --- | --- |
| New ID, valid payload | Commit once; atomically record the identity, payload identity and position |
| Committed ID, identical logical payload | Return its original position with `replayed: true`; leave head and values unchanged |
| Committed ID, different valid payload | Reject as request-ID reuse; leave head and values unchanged |
| Invalid request | Reject atomically; leave no new commit or identity record |

A request lookup returns either its committed position or `not_found` as observed
at that lookup. The identity registry is current database metadata, not filtered
through a historical data view. A timeout, disconnect or interrupted acknowledgement
can leave the outcome unknown; it is not evidence of an abort. Look up the ID and/or
resubmit the identical request. If an earlier submission is still in flight,
`not_found` does not prove it cannot later commit; the same ID and payload still
prevent a second commit when serialized. Recovery must retain this property for
durable commits. A fresh in-memory database after data loss is a new lineage and
cannot promise deduplication against the old one.

## Worked history and workload expectations

The `salary` fixture starts in an empty database. Its first commit puts Alice's
salary and Bob's salary in one transaction. A bounded correction intentionally
also tests preservation of the valid interval **after** a correction.

| Commit | Request | Operations |
| --- | --- | --- |
| 1 | `initial` | Alice = 70,000 and Bob = 60,000, both `[Jan 1, +infinity)` |
| 2 | `correction` | Alice = 75,000 on `[Feb 1, Mar 1)` |
| 3 | `retraction` | Alice absent on `[Feb 10, Feb 20)` |

The earlier [conceptual salary example](../concepts/temporal-data.md) uses an
unbounded correction instead. Both use the same range-replacement rule; these
fixtures make the previously unspecified overlap policy explicit.

| Workload | Exact input | Expected answer |
| --- | --- | --- |
| Genesis | Alice at Feb 15, cutoff 0 | Absent |
| Current-state entity | New head view after commit 2; Alice at Feb 15 | Present, 75,000 |
| As-of entity | Alice at Feb 15, cutoff 1, even after commits 2 and 3 | Present, 70,000 |
| Lower boundary | Alice at Feb 1, cutoff 2 | Present, 75,000 |
| One microsecond before boundary | Alice at Jan 31 23:59:59.999999 UTC, cutoff 2 | Present, 70,000 |
| Upper boundary | Alice at Mar 1, cutoff 2 | Present, 70,000 |
| Interval scan | Alice on `[Jan 1, Apr 1)`, cutoff 2 | `[Jan 1, Feb 1)` 70,000; `[Feb 1, Mar 1)` 75,000; `[Mar 1, Apr 1)` 70,000 |
| Correction-sensitive aggregate | Sum Alice and Bob at Feb 15, cutoff 1 then 2 | Count 2 / sum 130,000, then count 2 / sum 135,000 |
| Retraction start | Alice at Feb 10, cutoff 3 | Absent |
| Retraction end | Alice at Feb 20, cutoff 3 | Present, 75,000 |
| Aggregate with absence | Sum Alice and Bob at Feb 15, cutoff 3 | Count 1 / sum 60,000 |
| Stable open view | View opened at head 1; read Alice at Feb 15 after commits 2 and 3 | Still present, 70,000 |
| Duplicate request | Resubmit `correction` unchanged after commit 3 | Original commit 2, replayed; head remains 3 and Alice remains absent at Feb 15 |

The other fixture histories cover unbounded intervals, operation order within a
transaction, putting into a retracted gap, equal-value coalescing, signed values,
sums beyond 64 bits, invalid-request rollback, corrected retry of a rejected ID,
invalid query/cutoff rejection, duplicate-ID conflict, and the finite time-coordinate
limits beside explicit infinity. They are deliberately small correctness workloads,
with no throughput or scale claims.

## Fixture format and implementation handoff

[Download the JSON histories](fixtures/temporal-contract-v0.json). Each history has
a unique ID and ordered `steps`, replayed against a fresh database. Each step has
an `id`, `kind`, input fields and an exact `expected` result:

- `transact`: `requestId` and ordered `operations` (`put` or `retract`, `entity`,
  `from`, `to`, and `value` for put). Expect commit/replay identity or an error.
- `openView`: unique `viewId` and `cutoff` (integer or `head`). Expect the captured
  commit position, or an error without creating the view.
- `query`: exactly one of a previously opened `viewId` or a new `cutoff`, plus a
  `query` object: `get` with `entity`/`validAt`, `scan` with `entity`/`from`/`to`,
  or `sum` with `entities`/`validAt`. Expect presence, an interval list, an aggregate
  or an error. An inline cutoff opens a view for that query only.
- `lookupRequest`: `requestId`. Expect a committed position or `not_found`.

Fixture errors are semantic labels: `INVALID_REQUEST`, `REQUEST_ID_REUSED`,
`INVALID_CUTOFF` and `INVALID_QUERY`. They do not freeze Java exception names.
Integer JSON values must be decoded exactly, not through floating point. A
successful transaction expects `status: committed`, its `commit`, and `replayed`;
a request lookup omits `replayed`. Expected scan bounds use the same integer/null
notation as writes.

MDB-0003 establishes the [Java/Maven foundation](../development/build.md) and an
experimental valid-time interval primitive with 18 boundary test invocations from
this specification. It does not execute the complete histories. MDB-0004 reuses
that primitive, completes temporal types and implements the independent Java
reference model with full fixture replay, additional validation and seeded histories.
Preserve failing seeds. MDB-0005 will check an actual
in-memory engine against them. Concurrent visibility and durable crash guarantees
also need implementation tests in their respective steps; sequential fixture
replay alone cannot establish them.
