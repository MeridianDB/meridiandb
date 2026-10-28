---
title: Embedded in-memory engine
description: Run MeridianDB's experimental Java embedded engine with atomic memory-only commits, pinned temporal views, retry identity and explicit resource lifetimes.
---

# Embedded in-memory engine

`org.meridiandb.engine.InMemoryEngine` implements the
[initial numeric temporal contract](../architecture/temporal-contract.md) in one
JVM. It supports ordered puts/retractions, historical point and interval queries,
exact aggregates and request retries. It has **no persistence**: closing or crashing
loses values, history and request identity. The API is experimental, with no library
release or compatibility promise.

## Run the example

Use JDK 25 and run from the repository root:

```bash
./mvnw -B verify
```

On Windows, verification uses `.\mvnw.cmd -B verify`. Then run the same Java command
on either platform:

```bash
java --class-path target/classes examples/EmbeddedExample.java
```

The [example source](https://github.com/MeridianDB/meridiandb/blob/main/examples/EmbeddedExample.java)
opens an engine, records a salary, pins a view, applies a retroactive correction,
and compares that view with a new one. Its output is:

```text
February salary before correction: 100
February salary after correction: 120
Identical retry: commit 2, replayed=true
```

Coordinates are explicit epoch microseconds. The example uses numeric conformance
values and does not define a salary schema, timestamp parser or public query
language. Java CI runs the example after verification on Linux and Windows.

## Entry points and lifetimes

| Entry point | Behavior |
| --- | --- |
| `new InMemoryEngine()` | Create an empty database with a fresh random lineage and genesis position 0 |
| `transact(Transaction)` | Serialize the validated ordered request; publish all changes together; return position/replay identity |
| `head()` | Capture the latest published database-local position |
| `openView(ReadCutoff.Head.INSTANCE)` | Pin the head once and return an independent `ReadView` |
| `openView(new ReadCutoff.At(position))` | Select an existing cutoff in this engine's lineage |
| `lookupRequest(RequestId)` | Read current committed request metadata, independently of historical data views |
| `ReadView.position()` / `.query(TemporalQuery)` | Read the captured position and immutable point/scan/aggregate answers |
| `ReadView.close()` | Idempotently invalidate that view and release its snapshot reference |
| `InMemoryEngine.close()` | Idempotently close the engine and invalidate future operations on it and its views |

Use try-with-resources for both engines and views. Closing one view leaves the
engine and other views usable. After close, engine operations and affected view
position/query calls throw `IllegalStateException`. A query or view open that
already captured open state may finish while another thread closes it; close does
not cancel work already in progress. Writer commits and engine close serialize on
the same monitor, so no later writer publishes after engine close completes.

Foreign or future cutoffs raise `EngineException` with `INVALID_CUTOFF`; conflicting
reuse of a committed request ID raises `REQUEST_ID_REUSED`. New commits reject
sequence exhaustion with `COMMIT_EXHAUSTED` rather than wrapping. Invalid typed
arguments fail their constructors or required-argument checks. Request and result
records remain immutable; mutable caller lists cannot change a committed payload.

## Publication and queries

The engine maintains sorted, nonoverlapping present-value intervals per entity.
A writer copies the current value map, splits affected intervals around each
operation, inserts a put or omits a retraction, then coalesces equal adjacent values.
It preserves the immutable snapshot for every prior cutoff. Normalizing the finite
minimum lower edge in stored state does not alter original request fields used for
retry identity.

Writers serialize within one engine instance; simultaneous callers receive a total
commit order without an arrival-order promise. A single volatile publication
replaces a complete immutable state containing values, historical snapshots and
request identities. Readers take a snapshot without acquiring the writer monitor.
They cannot observe an operation in the middle of a transaction. An acknowledgement
means that publication completed in memory. An identical retry returns its original
position, even after later commits; a logical no-op with a new ID still commits.

Views can be shared by concurrent readers. Every operation in one view, including
all entities in an aggregate, uses its captured snapshot. New commits affect new
head views only. Point queries inspect the entity's intervals; scans clip existing
segments to the requested window; sums use exact `BigInteger` arithmetic. The
[reference oracle](reference-model.md) instead searches transaction history and
partitions boundaries, providing a separate execution algorithm.

[ADR 0006](../decisions/0006-in-memory-engine.md) records these choices and their
alternatives. No backend or multi-process writer coordination exists here.

## Verification and limits

`./mvnw -B verify` currently runs **390 JUnit test invocations**, including the
71 temporal/engine checks below, 29 [journal codec checks](../architecture/journal-format.md),
57 [local journal I/O/process/prefix checks](local-journal.md),
27 [immutable segment codec checks](../architecture/immutable-segment-format.md),
36 [local segment I/O/lifetime checks](local-segments.md),
45 [manifest codec/consistency checks](../architecture/checkpoint-manifest-format.md),
39 [local checkpoint publication checks](local-checkpoints.md),
37 [local database recovery checks](local-database.md) and
49 [commit preparation/initialization/publication checks](local-database.md):

- The unchanged five canonical histories supply 84 expected steps for each of
  reference and engine, including invalid inputs and extreme coordinates.
- Sixteen persisted seeds supply 32 new transactions each, checked against forward
  dense snapshots as well as the reference. Every implementation receives 512 new
  transactions, plus retries/conflicts, with point, scan, aggregate and historical
  prefix comparisons.
- Nine focused engine tests cover fresh lineages/cutoffs, pinned views, close
  behavior, concurrent identical/conflicting retries, contiguous commits from
  distinct callers, atomic cross-entity reads and shared-view access.
- Type, interval boundary and strict fixture-decoding checks remain in the suite.

The concurrency checks coordinate bounded tasks with barriers and timed waits,
without sleeps. They exercise real overlapping calls, but are not exhaustive JVM
schedule exploration. The seed domain is deliberately small; this is correctness
evidence, not a scale or performance claim. No failing semantic seed was found.

Run focused engine lifecycle/concurrency checks:

```bash
./mvnw -B -Dtest=InMemoryEngineTest test
```

The [reference guide](reference-model.md) supplies fixture and seed reproduction
commands and the failing-history preservation procedure.

This implementation retains every cutoff and request identity. It copies entity,
history and request maps on new commits and retains changed interval lists, so
memory use and write cost grow with history. It has no reclamation, index,
backpressure, crash recovery or benchmark claim. Sequence overflow is guarded;
practical memory capacity is reached long before the numeric sequence limit.
The [journal codec](../architecture/journal-format.md) implements the byte format;
the [local adapter](local-journal.md) supplies append, force and sequential replay.
The separate [local database](local-database.md) shares interval-state and replay
logic. Private commit preparation creates all maps, request metadata and results
before storage I/O; publication checks the unchanged owner state, assigns one
immutable reference and returns the prepared result. `InMemoryEngine.transact`
uses the same split with no storage boundary and remains nonpersistent. The local
write path inserts append/force before publication. See
[ADR 0014](../decisions/0014-durable-commit-publication.md) for acknowledgement
and the [roadmap](../roadmap.md) for remaining crash conformance.
