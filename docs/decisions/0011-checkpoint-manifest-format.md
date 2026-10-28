---
title: Checkpoint manifest representation
description: Bind complete immutable transaction history to generation metadata and an exact reconstructed journal prefix while separating dependency verification from local publication.
---

# ADR 0011: Checkpoint manifest representation

- **Date:** October 28, 2026
- **Status:** format, codec and referenced-object verification implemented; local publication planned
- **Scope:** Immutable checkpoint declarations and complete history consistency

## Context

An immutable file's identity does not establish which files form a complete
checkpoint or how far recovery may advance in the journal. A consistent segment
set must preserve all temporal history and retry identities, including across
segment boundaries. Metadata and physical publication require different evidence.

## Decision

Represent a checkpoint with exact database lineage, positive physical generation,
ordered segment IDs/encoded lengths/ranges and a journal-prefix sequence, byte
length and SHA-256. Require unique references covering exactly `1..cutoff`.
Sequence-zero genesis references no segment and binds the journal lineage header.
Generation remains independent of commit sequence; a later physical rewrite can
retain a temporal cutoff without inventing a database transaction.

Use a versioned bounded big-endian manifest. Reuse the exact journal lineage header,
protect the manifest header with CRC32C, and append SHA-256 over header/references.
A separate `ManifestId` hashes the whole image including that footer. Reject all
truncated, corrupt, unsupported, extra or invalid metadata without a partial result.
Limit manifests to 1 MiB and 4,096 segment references, with existing segment limits
and a conservative derived prefix-byte bound. These are prototype format limits.

Separate metadata decoding from complete dependency verification. For each supplied
segment, validate encoded length, expected full-object ID, lineage and range.
Reject request-ID reuse across segments. Reconstruct and hash the original journal
header/transaction frames incrementally, comparing exact prefix length and digest.
Return successful consistency only after all dependencies pass. Do not allocate a
whole prefix buffer; retain a global request-ID set and bounded per-object work.
This does not bound total heap or provide a measured high-throughput layout.

Verification here binds supplied objects to a reconstructed prefix. It neither
reads an actual journal source nor supplies a force receipt. Local publication
must separately verify the stored prefix, writer/generation ownership, force and
namespace persistence. Retain old referenced segments and journal history until
an explicit retention protocol establishes permission to reclaim them.

## Primary evidence and design inference

Sources checked October 28, 2026:

- Lucene's `segments_N` describes a consistent committed segment set. Its
  implementation separates preparing a pending manifest and file sync from
  finishing rename and directory metadata sync.
  [SegmentInfos](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/SegmentInfos.html),
  [implementation](https://github.com/apache/lucene/blob/releases/lucene/10.5.2/lucene/core/src/java/org/apache/lucene/index/SegmentInfos.java).
- Lucene separates data synchronization from directory metadata synchronization;
  those backend capabilities require their own implementation.
  [Directory](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/store/Directory.html).
- Quickwit advances source checkpoints atomically with immutable split publication.
  [Indexing](https://quickwit.io/docs/overview/concepts/indexing).
- XTDB aligns finalized immutable files with log progress and requires complete
  recovery artifacts rather than partial files.
  [Storage and backup](https://docs.xtdb.com/ops/backup-and-restore/overview.html).

These sources inform the distinction between object creation, complete-set
consistency and selected durable state. MeridianDB's exact manifest fields,
SHA-256 coverage and prefix reconstruction are design choices under its existing
temporal and journal contracts. Standard JDK SHA-256 and CRC32C add no dependency.

## Alternatives and consequences

A list of filenames or segment IDs alone cannot bind lineage, complete history
coverage or a journal recovery position. Flattening current intervals would lose
overwritten history and retry payloads. Repeating every request ID in metadata
adds another copy to reconcile; original frames already retain those identities.

A generic JSON representation is easier to inspect manually, but requires exact
rules for integer ranges, lossless identities, canonical bytes and duplicate fields.
The small fixed binary envelope reuses tested journal encoding and independent
golden fixtures. A future format change needs explicit version handling rather
than silently accepting another serialization.

A fully materialized journal prefix could simplify comparison but may be much
larger than one segment. Incremental digest reconstruction avoids that allocation
while the duplicate-ID set still grows with history. The source itself must bound
its retrieval and ownership; verification cannot limit arbitrary resolver behavior.

Selecting immutable generation files versus a separate head pointer remains a
local publication decision. Neither approach eliminates writer exclusion, actual
journal verification, atomic visibility or directory durability requirements.
The [format specification](../architecture/checkpoint-manifest-format.md) owns
bytes, Java usage, limits, failures and the precise verification claim.
