---
title: Bounded transaction journal format
description: Record MeridianDB's versioned journal framing, exact identity encoding, checksum and incomplete-tail rules, informed by append-only database and realtime-search designs.
---

# ADR 0007: Bounded transaction journal format

- **Date:** October 28, 2026
- **Status:** pure codec implemented; local I/O and durable engine integration planned
- **Scope:** MDB-0015, the first required child of MDB-0006

## Context

The memory engine has explicit transaction publication and historical views but
loses all state on close. The owner asked for approaches used by immutable,
append-only ingestion and realtime-search systems. The
[dated primary-source comparison](../architecture/storage-research.md) supports
separate journal durability, index publication and query-visible cutoffs, with
batching and bounded downstream work.

MDB-0006 needs both byte-format correctness and filesystem failure handling.
Split it into MDB-0015 for format/codec and MDB-0016 for append, force, ownership
and recovery. Preserve the parent's acceptance criteria and downstream dependency;
completing this child alone does not supply a usable durable journal.

## Decision

Implement a pure Java v1 codec in the existing artifact, without a runtime
dependency. A checksummed lineage header precedes complete transaction frames.
Each frame preserves the original request identity, ordered operations, explicit
unbounded-versus-finite bounds, values and positive commit sequence. A physical
force may later cover several frames without merging their logical identities.

Use an explicit version, bounded payload length, separate prefix/payload CRC32C
and exact big-endian fields. Validate framing before trusting body size. Reject
unsupported versions and complete corrupt or malformed records; never skip damage
and resume at a later magic marker. CRC detects accidental damage, not malicious
tampering, stable storage or client acknowledgement.

Encode existing Java identities as length-prefixed UTF-16 code units. This
preserves lone surrogates and every exact spelling already accepted by the types;
replacement UTF-8 would collapse distinct identities. Do not tighten logical ID
semantics as an accidental serialization change. Bound IDs, operation counts and
frame bytes to protect allocations. These prototype limits are resource policy,
not throughput measurements.

Decode one complete item at a time without moving the caller's buffer on failure.
A whole-image scan validates contiguous sequences from 1 and unique new request
IDs. It may return the valid prefix plus an incomplete-candidate flag, but performs
no file mutation or automatic repair. Incomplete bytes are not proof that an
acknowledged transaction may safely be discarded. The
[journal specification](../architecture/journal-format.md) owns exact byte layout,
limits, decoder errors and the minimum local capability contract.

## Alternatives and consequences

Java object serialization would couple the format to implementation details and
would not provide these explicit boundaries. JSON would need additional exact
numeric, Unicode and resource-limit rules without removing framing checks.
An existing database or Kafka/RocksDB dependency would add an engine or service
before the local journal needs one. Their documented mechanisms inform this
format; no wire compatibility is intended.

Block fragmentation, compression, dictionaries and larger physical batches may
improve future workloads, but they add recovery states. Start with bounded complete
transaction frames and independent golden-byte/corruption tests. Evaluate another
format version when measured limitations justify it.

The codec adds no FileChannel writer, sync result, writer lock or durable engine.
MDB-0016 must test those boundaries with short/failed I/O and explicit tail repair;
MDB-0008 must test acknowledged replay and uncertain outcomes through engine reopen.
Future manifests name the included journal prefix before any retention decision.
Historical temporal answers and retry identity remain governed by the original
contract rather than a search engine's merge policy.
