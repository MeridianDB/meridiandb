---
description: Explore MeridianDB's memory engine, journal codec, researched immutable-storage lessons and remaining persistence boundaries before durable storage and stable APIs.
---

# Architecture overview

MeridianDB has an experimental memory-only embedded engine. Its API remains
unstable; the transaction journal codec has an experimental format, while an
immutable history-segment codec preserves exact transactions. Query-optimized
layouts remain open. This page describes the working direction and the questions
the storage prototype still needs to resolve.

## Start with Java and JVM embedding

Java is the default for the initial engine and correctness reference model. It
supports direct JVM embedding, static checking and one build/debugging environment
for the temporal model and storage prototype.
The [Java/Maven foundation](../development/build.md) now uses Java 25, a pinned
Maven Wrapper and immutable experimental temporal types. The
[test-only reference model](../development/reference-model.md) executes canonical
histories and seeded comparisons alongside a separate
[embedded engine](../development/embedded-engine.md). That engine stores immutable
per-commit interval snapshots, serializes writes and publishes complete state
atomically. The documentation site has a separate Python build.

Other languages remain options for bounded experiments or concrete integration
needs. They are not promised modules or current dependencies. The
[language strategy](../decisions/0001-language-strategy.md) records the alternatives
and conditions for revisiting this decision.

## Separate the decisions

| Layer | Question it must answer |
| --- | --- |
| Temporal semantics | What does a correction, retraction or historical query mean? |
| Logical query operations | Which facts and intervals should a query return? |
| Physical layout and indexes | How are those facts organized for efficient access? |
| Persistence backend | Which reads, writes and publication guarantees are available? |

The [initial temporal contract](temporal-contract.md) defines the first layer,
with [conceptual examples](../concepts/temporal-data.md) and reusable exact
expectations. A physical optimization must preserve those answers, including
after recovery and compaction. The
[temporal decision](../decisions/0003-initial-temporal-semantics.md) records its
rationale and alternatives.

Parquet is a candidate file format. An object store is a persistence backend.
A MergeTree-like organization is a candidate storage technique. Selecting one
does not select the others. Local files and S3-compatible object storage are
research targets. The [transaction journal codec](journal-format.md) and
[local file adapter](../development/local-journal.md) are implemented. The
[immutable history-segment codec](immutable-segment-format.md) adds complete-object
validation and content identity. Its [local adapter](../development/local-segments.md)
creates new files and verifies expected IDs before returning heap-owned readers.
Manifests, object storage and optimized query layouts remain planned.

## Learn from immutable ingestion and search systems

Comparable systems provide useful design evidence for temporal storage. The
[dated source comparison](storage-research.md) covers XTDB's ordered transaction
log, Kafka/RocksDB framing, Lucene/Elasticsearch publication and durability,
Quickwit checkpoints and ClickHouse batching. Adopt the separation of durable
append, immutable checkpoint and query-visible cutoff; adapt mechanisms to the
existing bitemporal contract rather than inheriting another product's semantics.

The journal codec implements bounded versioned frames and strict byte validation.
The local adapter adds writer exclusion, append/force and sequential recovery.
The engine stays memory-only until journal/segment integration and crash tests
establish durable behavior.
Group commit, bounded queues and avoiding tiny immutable parts are future throughput
considerations; there are no MeridianDB performance results yet.

SQL, document APIs, network protocols and replication remain open. Representative
logical queries can guide experiments before a public interface is stabilized:
current-state lookups, historical entity lookups, interval scans and aggregates
whose answers change after a retroactive correction.

## Development direction

The [technical roadmap](../roadmap.md) progresses from the implemented memory
engine, local journal and immutable segment files to manifest publication,
durable engine recovery and workload measurements. The initial engine serializes writes within one JVM;
multi-process and distributed writing require additional coordination contracts.

## Establish correctness before optimizing

1. Use the specified [temporal contract](temporal-contract.md) and fixtures for
   interval boundaries, correction rules, transaction ordering and exact results.
2. Check the memory-only engine against the sequential Java reference, exact
   histories and independent dense snapshots. This is implemented with additional
   lifecycle and concurrency checks; production uses interval replacement while
   the oracle searches whole transaction history.
3. Add one durable implementation with a stated writer model and crash/restart
   tests. Make acknowledgement and recovery behavior observable.
4. Exercise its persistence contract with local storage and an object-store
   backend, including retries and uncertain commit outcomes.
5. Compare layouts and indexes under equivalent semantics and durability.

The reference model should use simpler, independently reasoned algorithms than
an optimized implementation. Two implementations repeating the same assumptions
would provide weak evidence of correctness.

## Make persistence capabilities explicit

Each storage adapter will need a clear contract for the operations it uses:
range reads, immutable data writes, conditional updates where needed, durable
commit publication, and recovery after interrupted or uncertain operations.
An adapter boundary cannot make local files and object stores behave identically.

The logical contract specifies one serialized writer, atomic publication, pinned
views and request identity. Before durable code is considered correct, its backend
and recovery tests must demonstrate:

- Enforcement of writer ownership and the specified commit order.
- Recoverable commit and request identity before reader publication or acknowledgement.
- Retry/lookup behavior after an interrupted acknowledgement.
- Whole-transaction recovery after a crash at each publication step.
- Reclamation that preserves supported history and active readers under an explicit
  retention policy.

Performance work follows a reproducible correctness baseline. Measurements should
include query latency, memory use, write/read amplification and recovery behavior
under a stated workload. No benchmark results are available yet.
