---
title: Immutable transaction-history segments
description: Record MeridianDB's bounded immutable history-segment representation, reuse of exact journal frames, integrity footer and full-object content identity.
---

# ADR 0009: Immutable transaction-history segments

- **Date:** October 28, 2026
- **Status:** format/codec implemented; [local adapter](0010-local-segment-io.md) implemented separately; [manifest consistency](0011-checkpoint-manifest-format.md) implemented; local publication planned
- **Scope:** Immutable history representation and validation

## Context

A durable temporal checkpoint must preserve earlier knowledge cutoffs, original
request identities and overwritten facts. A current-state interval snapshot alone
cannot reproduce that history. Before selecting a query-optimized layout, the
storage model needs a simple immutable representation that can be scanned and
checked against exact transactions.

## Decision

Represent one nonempty contiguous range as an immutable lineage plus ordered
transactions. Reuse existing v1 journal headers and transaction frames, preserving
exact strings, bounds, values and operation order. A segment may begin after
sequence one; a later manifest must establish complete range coverage across
segments and the included journal prefix.

Add an outer versioned envelope with lineage-header size, first/last sequences,
record count, body length and header CRC32C. A SHA-256 footer covers the header and
body. The external `SegmentId` hashes the complete encoded object, including the
footer. Specify the two coverage ranges separately, and verify the full object
before returning any records. An expected external identifier must match the
validated complete bytes.

Reject truncation, extra bytes, malformed fields, unsupported versions, checksum
failures, invalid ranges and repeated identities. Finalized objects have no
recoverable tail. Bound the complete encoding to 32 MiB, records to 65,536 and
nested fields to journal v1 limits. Preflight frame and aggregate sizes before
allocating the output. These are prototype resource limits, not throughput results.

The initial API uses bounded byte arrays and immutable decoded lists; no file
handle or mapped memory escapes. The [local adapter](0010-local-segment-io.md)
adds file creation and reader lifetimes.
[Manifest consistency](0011-checkpoint-manifest-format.md), namespace publication
and durable engine integration remain separate boundaries.

## Comparable design evidence

Primary sources checked October 28, 2026:

- Lucene's codec utilities distinguish header/version checks, expected object
  identity and whole-file integrity validation. Retrieving a stored checksum alone
  does not validate all file bytes. [CodecUtil](https://github.com/apache/lucene/blob/releases/lucene/10.5.2/lucene/core/src/java/org/apache/lucene/codecs/CodecUtil.java).
- Lucene's `segments_N` lists a consistent active segment set; creating a data file
  alone does not select it for a committed view. [SegmentInfos](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/SegmentInfos.html).
- Quickwit publishes immutable splits with atomic source-checkpoint advancement.
  [Indexing and checkpoints](https://quickwit.io/docs/overview/concepts/indexing).
- XTDB aligns finalized immutable storage with log progress and excludes partial
  files from recovery artifacts. [Storage and backup](https://docs.xtdb.com/ops/backup-and-restore/overview.html).

These mechanisms inform the separation of data integrity, referenced identity and
checkpoint publication. MeridianDB's history representation and SHA-256 coverage
are design choices governed by its temporal contract. Java requires standard
SHA-256 support, so this choice adds no library dependency.
[JDK MessageDigest](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/MessageDigest.html).

## Alternatives and consequences

Flattened current-state intervals lose overwritten history unless additional
system-time and identity data are retained. An entity-oriented or columnar format
may later improve queries, but must preserve the same temporal answers. Parquet,
Arrow, compression and specialized indexes remain experiments requiring their
own layout and workload evidence.

Reusing exact journal frames supplies a transparent baseline while accepting
repeated framing and string overhead. A checksum and content address cannot
establish durable creation of a file or permission to reclaim its journal source.
The [manifest representation](0011-checkpoint-manifest-format.md) binds verified
segment IDs, lengths, lineage, ordered ranges and a reconstructed journal prefix.
Actual stored-journal verification and atomic/namespace durability still require
the local publication protocol.

The [format specification](../architecture/immutable-segment-format.md) owns bytes,
limits, failures and ownership. Passing codec tests does not establish file I/O,
checkpoint publication or durable engine recovery; those boundaries require
separate implementations and evidence.
