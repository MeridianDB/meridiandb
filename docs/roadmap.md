---
title: Development roadmap
description: See MeridianDB's implemented memory engine and journal codec, the path to durable storage, and the correctness evidence required for later performance work.
---

# Development roadmap

MeridianDB's initial target is an embedded, single-node Java temporal engine.
The memory engine and journal codec establish a correctness baseline. Local file
storage, durable recovery and performance evaluation are still in development.
There is no release date or stable API commitment.

## Available today

| Capability | Current behavior |
| --- | --- |
| [Temporal model](architecture/temporal-contract.md) | Ordered whole-value puts and retractions, valid-time intervals and historical commit cutoffs |
| [Embedded memory engine](development/embedded-engine.md) | Serialized atomic commits, immutable read views, request retries and exact point/scan/aggregate answers |
| [Independent reference](development/reference-model.md) | Canonical histories and seeded comparisons against a separate dense-state representation |
| [Journal codec](architecture/journal-format.md) | Versioned transaction frames, exact identity, checksums, allocation bounds and strict byte validation |
| [Java build](development/build.md) | Java 25, checksum-pinned Maven Wrapper and verification on Linux and Windows |

The engine retains state only in memory. The journal codec validates byte
representations; it does not write files or establish durable acknowledgement.

## Local storage and recovery

The next storage work adds exclusive writer access, complete append loops, an
explicit sync boundary and bounded sequential replay. Incomplete trailing bytes
need a documented repair policy that preserves evidence. Complete corruption and
unsupported formats must fail clearly.

Immutable segments and a manifest will describe a consistent checkpoint and the
journal prefix it includes. Durable engine integration must recover acknowledged
transactions and request identities, preserve historical answers, and handle a
lost acknowledgement without duplicate effects. Crash/restart tests must cover
each publication boundary before durability is claimed.

## Queries, retention and object storage

Query execution will grow from the existing point, interval and aggregate
operations. Indexes and physical layouts must preserve the temporal contract.
Compaction and reclamation require an explicit retention policy covering earlier
cutoffs, active readers and recovery.

An object-storage backend needs its own immutable-object publication, conditional
update and recovery protocol. A common API cannot make object storage behave like
a local filesystem. [Comparable-system research](architecture/storage-research.md)
informs these boundaries without selecting another database's semantics.

## Performance evaluation

A reproducible workload baseline follows durable correctness. Measurements will
separate ingestion-to-sync latency, ingestion-to-query latency, replay time,
memory, read/write amplification and merge backlog. Batching, compression,
fragmentation and specialized indexes will be evaluated against measured limits
under equivalent semantics and durability.

## Open interface choices

SQL, document APIs, network protocols, replication, multiple writer processes and
additional language bindings remain open. Concrete integration requirements and
workload evidence will guide those decisions. The
[architectural records](decisions/index.md) explain current choices and conditions
for revisiting them.
