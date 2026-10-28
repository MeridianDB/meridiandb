---
title: Reference model and conformance checks
description: Run MeridianDB's sequential Java reference model against exact temporal histories and independent seeded dense-state checks, and reproduce failing cases.
---

# Reference model and conformance checks

The project provides immutable temporal types and a sequential reference interpreter
for the [initial temporal contract](../architecture/temporal-contract.md). It is a
test oracle for the separate [embedded engine](embedded-engine.md). The oracle
itself has no engine lifecycle, reader concurrency, persistence or recovery
implementation. All Java names remain experimental.

## Types and source layout

Production types live in `src/main/java/org/meridiandb/temporal/`:

| Types | Meaning |
| --- | --- |
| `EntityId`, `RequestId`, `DatabaseId` | Nonempty exact string identities, without normalization |
| `ValidTimeInterval` | Half-open microsecond interval with explicit nullable unbounded endpoints |
| `WriteOperation.Put`, `WriteOperation.Retract`, `Transaction` | Validated immutable ordered commands with a request identity |
| `CommitPosition`, `CommitResult`, `ReadCutoff` | Database-local sequence, commit acknowledgement and head/explicit cutoff |
| `TemporalQuery.Get`, `.Scan`, `.Sum` | Point, interval and distinct-entity aggregate requests |
| `TemporalResult.Point`, `.Segments`, `.Aggregate`, `TemporalSegment` | Explicit absence, coalesced scan answers and exact integer sums |

Lists are defensively copied. Invalid intervals, empty transactions, duplicate
aggregate entities and missing required arguments are rejected during construction.
The logical contract owns behavior; these records do not promise a public query
language or wire format.

`ReferenceModel` lives in `src/test/java/org/meridiandb/reference/`. It stores
immutable committed transactions and request identities. A point lookup searches
commits backwards, then operations backwards, taking the first covering operation.
A scan collects boundaries, partitions the query window, looks up each cell and
coalesces equal adjacent values. Arbitrary-precision coordinates handle the
exclusive boundary after the largest finite instant without overflow. This path
does not call the production interval membership helper.

An opened head view captures one commit position. Later transactions cannot change
queries at that position. Matching request retries return their original position;
conflicting reuse is rejected. Request lookup reads current commit metadata rather
than a historical data view. This interpreter is sequential and keeps its whole
history in memory; it is neither thread-safe nor intended for large workloads.

The production JAR contains the immutable types, memory-only engine and local
journal adapter. The oracle, fixtures, seeds,
JUnit and Jackson remain test-only. [ADR 0005](../decisions/0005-reference-model.md)
records the separation and algorithm choices.

## Canonical histories

The authoritative source is
[`temporal-contract-v0.json`](../architecture/fixtures/temporal-contract-v0.json).
Maven exposes it under `contracts/` on the test classpath, so there is no maintained
copy. `ReferenceFixtureTest` executes five independent histories with **84 exact
step expectations** covering corrections, retractions, overlap ordering, retry
identity, pinned views, invalid inputs, empty state and coordinate extremes.
`EngineFixtureTest` runs those same 84 expectations through the actual engine.
The fixture adapter represents named views as captured positions and reopens that
cutoff for each query; direct engine tests separately retain actual `ReadView`
objects across commits and concurrent reads.

`ContractFixtures` is a test adapter. It decodes integral JSON values exactly,
rejects decimals, booleans, missing endpoints and out-of-range numbers, and validates
the whole transaction before submitting it. A missing endpoint is invalid; an
explicit null endpoint is unbounded. Semantic error labels and named view aliases
belong to the fixture notation rather than a public Java exception/API contract.

Run only the canonical histories from the repository root:

```bash
./mvnw -B -Dtest=ReferenceFixtureTest,EngineFixtureTest test
```

## Independent seeded histories

`ReferencePropertiesTest` uses **16 persisted seeds**, each producing 32 new
transactions with one to three ordered puts/retractions across three entities.
It applies each history to both engine and reference, checking each against
independently built dense forward snapshots for integer instants in `[-8, 9)`.
This representation assigns cells directly; it does not search transaction history,
partition arbitrary-precision boundaries or use the interval membership helper.

At every new commit it checks point answers, coalesced scans and exact aggregates,
plus a prior cutoff. It retries a random committed request, periodically attempts
conflicting reuse, and verifies that neither allocates another commit or changes
answers. After the history, it rechecks every saved prefix. Generated values
include signed 64-bit extremes; the fixed fixtures separately exercise extreme
time coordinates and canonical unbounded scans. The bounded generated grid is
useful evidence, not exhaustive coverage of every possible history.

The default suite reads `src/test/resources/reference-seeds.txt` and creates
**512 new transactions per implementation**, in addition to retries and conflicts. Reproduce one seed:

```bash
./mvnw -B -Dtest=ReferencePropertiesTest -Dmeridiandb.seed=42 test
```

An assertion reports the seed, step, cutoff and typed transaction history. When a
failure is found, preserve its seed and a minimized input with independently
reasoned expected answers before fixing the defect. Add a regression to the
appropriate fixture or focused test, then rerun it and the affected suite. Keep
seeds that expose distinct behavior; do not adjust expected answers solely to
match the implementation. No failing semantic seed has been found in the current corpus.

## Verification and engine checks

The standard [Java verification](build.md) command is:

```bash
./mvnw -B verify
```

The current default suite has **138 JUnit test invocations**: 29 journal codec
checks, 38 [local journal I/O/process checks](local-journal.md) and 71 temporal/engine
checks comprising 18 interval boundary checks, eight type checks, ten reference/decoding checks, five history tests per
implementation, 16 shared seed tests and nine engine lifecycle/concurrency tests.
History steps and generated transactions are assertions inside those tests, not
additional JUnit invocations.

The [engine guide](embedded-engine.md) explains the memory-only implementation,
atomic publication, serialized writing and lifecycle tests. Keep the oracle in
test support; do not use it as the engine. Durable engine recovery, compaction and performance require their own later
implementations and evidence.
