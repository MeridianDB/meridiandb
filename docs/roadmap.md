---
title: Development roadmap
description: See MeridianDB's implemented memory engine and journal codec, the path to durable storage, and the correctness evidence required for later performance work.
---

# Development roadmap

MeridianDB's initial target is an embedded, single-node Java bitemporal database
with built-in local persistence. Optional adapters and replication extend that
foundation under the [persistence boundary design](architecture/persistence-boundaries.md).
The memory engine, local journal, immutable history files and manifest consistency
checks, local checkpoint publication, recovery and forced local writes establish
a correctness baseline, including process-exit/retry and seeded writable-reopen
conformance. Performance evaluation remains planned.
There is no release date or stable API commitment.

## Available today

| Capability | Current behavior |
| --- | --- |
| [Temporal model](architecture/temporal-contract.md) | Ordered whole-value puts and retractions, valid-time intervals and historical commit cutoffs |
| [Embedded memory engine](development/embedded-engine.md) | Serialized atomic commits, immutable read views, request retries and exact point/scan/aggregate answers |
| [Independent reference](development/reference-model.md) | Canonical histories and seeded comparisons against a separate dense-state representation |
| [Journal codec](architecture/journal-format.md) | Versioned transaction frames, exact identity, checksums, allocation bounds and strict byte validation |
| [Local journal](development/local-journal.md) | Exclusive writer access, explicit append/force cutoffs, bounded sequential replay and evidence-preserving tail repair |
| [Immutable history codec](architecture/immutable-segment-format.md) | Exact contiguous transaction ranges, strict complete-object checks and verified SHA-256 content identifiers |
| [Local immutable files](development/local-segments.md) | Create-only file writes, bounded expected-ID verification and explicit heap-reader lifetimes |
| [Manifest codec and verifier](architecture/checkpoint-manifest-format.md) | Bounded generation metadata, complete history references and exact reconstructed journal-prefix binding |
| [Local checkpoint publication](development/local-checkpoints.md) | Actual journal-prefix verification, exclusive generation selection, ordered forces and atomic rename on supported filesystems |
| [Local database](development/local-database.md) | Recovered historical queries, explicit writable startup and append/force before atomic commit publication; process-exit/retry conformance |
| [Typed temporal queries](development/temporal-queries.md) | Signed numeric predicates, deterministically ordered entity selection, filtered intervals and exact sums at one pinned cutoff |
| [Pure history compaction](development/history-compaction.md) | Complete graph validation, exact transaction repacking and encoded-space accounting under explicit budgets; no file installation/deletion |
| [Local compaction publication](development/history-compaction.md#install-and-publish-locally) | Exact source/owner-journal checks, verified reuse, create-only installation and same-cutoff generation publication; old files retained |
| [Live reader protection](development/reader-protection.md) | Shared cross-process barrier independent of store lifetime; any live lazy reader excludes future removal |
| [Reclamation inventory](development/reclamation-inventory.md) | Bounded complete graph/owner-prefix checks, explicit retained roots and immutable obsolete-object metadata; no deletion |
| [Protected physical reclamation](development/physical-reclamation.md) | Fresh owner-bound inventory, durable live roots, metadata retirement before dependency removal and conservative interruption recovery |
| [Integrated compaction conformance](development/compaction-conformance.md) | Actual fixture/seed publication and removal, historical queries/retries, richer process stops and named-file length accounting |
| [Java build](development/build.md) | Java 25, checksum-pinned Maven Wrapper and verification on Linux and Windows |

`InMemoryEngine` retains state only in memory. `LocalDatabase` recovers stored
history and provides explicit forced-write acknowledgement. The journal codec validates byte representations;
it does not write files or establish durable acknowledgement.

## Local storage and recovery

The local journal implements exclusive writer access, complete append loops,
explicit file force and bounded sequential replay. Incomplete candidates require
explicit evidence-preserving repair. Complete corruption and unsupported formats
fail closed. Directory-entry durability remains a platform-specific prerequisite;
the adapter does not provide a portable namespace publication protocol.

The local segment adapter writes new files, forces and closes them before
returning an ID, and validates whole objects before returning immutable heap
readers. It preserves failed candidates and never overwrites a segment. The manifest
codec describes a consistent segment set; its verifier checks every supplied object
and reconstructs the exact journal-prefix byte count and hash. The local checkpoint
store verifies the actual journal through its owner, forces dependencies and their
directory entries, then selects a complete generation by atomic rename and a final
directory force. Unsupported directory capabilities fail before store files are
created. Failed publication retains evidence and requires reopen; an attempted
rename can have an uncertain outcome. Old generations and segments remain retained.
The local recovery API now reconstructs all stored transactions, historical
answers and request positions before exposing reads. Writable startup forces the
recovered journal and namespace; new transactions prepare privately, append/force
and then publish one complete immutable state. Failed append attempts invalidate
the instance and require reopen to resolve uncertainty.
[Durability conformance](development/durability-conformance.md) checks publication
boundaries, lost acknowledgements and repeated writable reopen without duplicate
effects. These checks do not establish device power-loss behavior.

## Queries, retention and object storage

[Typed query execution](development/temporal-queries.md) now includes point lookup,
explicit-entity selection, coalesced and filtered interval scans, signed numeric
predicates and exact aggregates. It uses eager immutable results over one pinned
snapshot; indexes, pagination and total query budgets remain open. Indexes and physical layouts must preserve the temporal contract.
[Pure compaction](development/history-compaction.md) now retains every original
transaction and request identity while repacking segment boundaries. Logical
retention remains all history; a plan changes no selected generation or file.
Local installation/publication now verifies existing content and selects the
same-cutoff replacement through the checkpoint protocol. Shared cross-process
reader protection now outlives store close. A bounded [inventory](development/reclamation-inventory.md)
verifies selected/retained roots and reports obsolete dependencies. [Protected removal](development/physical-reclamation.md) now holds store ownership
and exclusive protection, establishes retained roots and synchronizes metadata
retirement before unused dependencies. [Integrated local conformance](development/compaction-conformance.md)
now combines full fixture/seed histories, richer process exits and exact named-file
space accounting. Earlier cutoffs, lazy readers, recovery and backup roots
remain part of that safety contract.

The object-storage work proceeds through bounded immutable-object access and a
local implementation, an optional S3-compatible adapter, conditional checkpoint
publication, engine recovery, protected reclamation and common conformance.
It retains the existing local journal and acknowledgement contract. Each stage
needs capability and failure evidence; the backend is not implemented today.
A common API cannot make object storage behave like a local filesystem.
[Comparable-system research](architecture/storage-research.md) informs these
boundaries without selecting another database's semantics.

## Optional replication

Replication is a separate future extension, beginning with one authoritative
writer and read-only followers. The design must preserve original transaction
identity and order through checkpoint bootstrap and journal catch-up, and expose
durable and query-visible follower progress. Local, asynchronous and quorum
acknowledgements need distinct guarantees. Automatic failover additionally needs
leadership fencing and committed-history agreement; it is not a storage-adapter
feature. No replication mode or network protocol is implemented.

## Performance evaluation

A reproducible workload baseline follows durable correctness. Measurements will
separate ingestion-to-sync latency, ingestion-to-query latency, replay time,
memory, read/write amplification and merge backlog. Batching, compression,
fragmentation and specialized indexes will be evaluated against measured limits
under equivalent semantics and durability.

## Open interface choices

SQL, document APIs, network protocols, distributed leadership, multiple writer
processes and additional language bindings remain open. Concrete integration
requirements and workload evidence will guide those decisions. The
[architectural records](decisions/index.md) explain current choices and conditions
for revisiting them.
