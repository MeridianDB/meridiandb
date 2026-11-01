---
title: "ADR 0030: Bounded pinned object history"
description: Materialize original checkpoint transactions during the same bounded graph and actual-owned-prefix verification pass, without exposing partial history or inferring current selection.
---

# ADR 0030: Bounded pinned object history

Date: October 31, 2026. Status: implemented, experimental history loader;
remote engine recovery remains planned.

## Context

The object graph verifier returns metadata and actual-prefix observations while
discarding decoded records. Engine recovery will need original checkpoint
transactions. Verifying metadata and then fetching those records again would
introduce another I/O/decoding pass and allow the later bytes to differ from the
ones checked. A caller-visible visitor would expose records before a late graph,
prefix or cleanup failure could invalidate the operation.

## Decision

Add `ObjectCheckpointGraphs.loadHistory(head, limits)` for one explicitly supplied
pinned head. Collect records privately inside the same complete verification pass
used by `verify`. Preserve independent namespace/publisher binding, actual owned
journal lineage, early segment-range admission and the existing shared budgets.
Expose immutable `History` only after the whole graph, actual owner prefix and
object-reader cleanup succeed. Fetch and decode each dependency once per call.

Keep existing `Verification` and `Graph` metadata-only. `History` additionally
retains original immutable ordered `JournalRecord` values, including no-op commits
and exact request payloads. It owns no caller adapter, journal or open reader.
Its records cover only the supplied checkpoint cutoff, excluding any local tail.
The [graph guide](../development/object-checkpoint-graphs.md#load-pinned-history)
owns API usage, retained-memory costs, failure behavior and scope.

Loading is evidence about that head and owner during that operation. It neither
observes the current remote head nor supplies a transferable certificate for a
different or reopened journal. A future engine factory must load within its own
verified startup ownership and separately validate/replay the contiguous tail.
Keep engine reconstruction, managed S3 startup, writable acknowledgement and remote
reclamation separate from this loader. There is no fallback, repair or remote write.

## Primary evidence and design inference

Sources checked November 1, 2026 at 01:46:44 UTC (October 31, 21:46:44 EDT):

- XTDB distinguishes immutable indexed storage coverage from later log entries;
  losing an unindexed suffix loses those transactions unless separately preserved.
  Its out-of-sync procedure is explicit operator intervention, rather than silent
  recovery fallback. [Backup overview](https://docs.xtdb.com/ops/backup-and-restore/overview.html),
  [out-of-sync log](https://docs.xtdb.com/ops/backup-and-restore/out-of-sync-log.html).
- Lucene identifies a committed generation and its referenced file set, with an
  API to open a particular commit. Writer-based near-real-time readers may also
  expose uncommitted changes. [IndexCommit](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/IndexCommit.html),
  [DirectoryReader](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/DirectoryReader.html).
- Reader construction is separate from full integrity checking of every referenced
  payload. [SegmentInfos implementation](https://github.com/apache/lucene/blob/releases/lucene/10.5.2/lucene/core/src/java/org/apache/lucene/index/SegmentInfos.java),
  [LeafReader.checkIntegrity](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/LeafReader.html#checkIntegrity()).

MeridianDB's same-pass materialization and actual owned-prefix requirement are
project choices. A checkpoint covering cutoff C does not establish the existence
of later local transactions, replicated durability, force or acknowledgement.
The loader requires an existing matching local journal; it does not rebuild one.

## Alternatives and consequences

A second independently bounded verification pass could also recover records, but
would need fresh integrity checks and explicit repeated-work accounting. Reusing
metadata evidence alone would leave a gap between verification and replay. Public
callbacks would complicate failure atomicity. Private collection keeps incomplete
records inaccessible and leaves existing metadata-only operations without a
retained history list.

The materialized list retains more heap than metadata verification. Existing
record-work reservations bound cardinality, and encoded-byte limits bound supplied
text, but neither is an exact heap/CPU or total engine-recovery budget. List growth,
defensive copies, decoded values and identity sets all add memory costs. This is
a bounded whole-checkpoint foundation; large streaming recovery needs a separate
lifetime, verification and work-accounting contract.
