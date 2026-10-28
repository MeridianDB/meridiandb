---
description: Explore MeridianDB’s proposed layers, persistence boundaries and correctness milestones before storage formats and public APIs are selected.
---

# Architecture overview

MeridianDB is in architectural research. There is no implemented engine, stable
API or selected physical format. This page describes the working direction and
the questions an initial prototype needs to resolve.

## Start with Java and JVM embedding

Java is the default for the initial engine and correctness reference model. The
maintainer's familiarity with Java supports code review, debugging and long-term
maintenance, and it fits the initial assumption of in-process JVM embedding.
Maven is the chosen Java build tool. The JDK baseline and Maven Wrapper will be
established with the first Java implementation; the documentation site has a
separate npm build.

Other languages remain options for bounded experiments or concrete integration
needs. They are not promised modules or current dependencies. The
[language strategy](/decisions/0001-language-strategy) records the alternatives
and conditions for revisiting this decision.

## Separate the decisions

| Layer | Question it must answer |
| --- | --- |
| Temporal semantics | What does a correction, retraction or historical query mean? |
| Logical query operations | Which facts and intervals should a query return? |
| Physical layout and indexes | How are those facts organized for efficient access? |
| Persistence backend | Which reads, writes and publication guarantees are available? |

The [temporal data examples](/concepts/temporal-data) are a starting point for
the first layer. A physical optimization must preserve those answers, including
after recovery and compaction.

Parquet is a candidate file format. An object store is a persistence backend.
A MergeTree-like organization is a candidate storage technique. Selecting one
does not select the others. Local files and S3-compatible object storage are
research targets; neither backend nor format has been implemented.

SQL, document APIs, network protocols and replication remain open. Representative
logical queries can guide experiments before a public interface is stabilized:
current-state lookups, historical entity lookups, interval scans and aggregates
whose answers change after a retroactive correction.

## Establish correctness before optimizing

1. Write a small temporal contract with explicit expected results, interval
   boundaries, correction rules and transaction ordering.
2. Build a straightforward in-memory Java reference model. Check randomized
   histories against independent expectations as well as worked examples.
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

Before durable code is considered correct, the prototype must answer:

- How many writers are supported, and how are conflicting commits ordered?
- When does a commit become visible, and what does an acknowledgement guarantee?
- How does a client resolve an uncertain outcome without duplicating a change?
- What may recovery expose after a crash at each publication step?
- Which retention rules permit reclamation while protecting readers and history?

Performance work follows a reproducible correctness baseline. Measurements should
include query latency, memory use, write/read amplification and recovery behavior
under a stated workload. No benchmark results are available yet.
