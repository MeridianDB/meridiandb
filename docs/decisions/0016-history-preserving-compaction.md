---
title: History-preserving compaction decision
description: Preserve all temporal history and request identity during bounded deterministic segment repacking, separating pure plans from durable publication and protected physical reclamation.
---

# ADR 0016: History-preserving compaction

- **Date:** October 29, 2026
- **Status:** Pure bounded repacking and local publication implemented; protected removal implemented separately
- **Scope:** Complete checkpoint history, logical retention and physical object lifetimes

## Context

The [temporal contract](../architecture/temporal-contract.md) retains every commit
cutoff and original request identity. History segments store complete ordered
transactions, while immutable manifests bind an exact journal prefix. Replacing
those objects must preserve corrected, retracted and no-op history. Keeping only
the latest value would violate the existing query and retry contract.

Local checkpoint readers can lazily open old segments after their store closes.
Heap segment readers instead own verified immutable values without open channels.
The current publisher retains every old object; a compaction result alone supplies
no proof that a physical file is unused.

## Decision

Keep **all logical history and request identities**. Implement deterministic pure
repacking of a completely verified checkpoint into whole-transaction groups under
explicit serialized-byte/record budgets. Preserve original sequences, ordered
operations, exact UTF-16 identities and finite versus unbounded endpoints. Return
an immutable plan proposing source physical generation plus one at the same
journal prefix/cutoff. Do not change existing storage formats or truncate the journal.

Validate expected IDs, complete dependency coverage, cross-segment request uniqueness
and reconstructed prefix length/hash before returning a plan. Read each supplied
image once. Bound aggregate input/output segment bytes and record count in addition
to existing per-object limits; fail rather than splitting a transaction or silently
exceeding a hard target. Encoded-byte budgets do not claim an exact JVM heap bound.
The [compaction guide](../development/history-compaction.md) owns API details,
limits, space accounting and reproduction commands.

Keep local installation/publication as a separate explicit operation.
`LocalCheckpointStore.publishCompaction` rechecks the exact selected source ID and
actual owned journal, verifies all existing expected images byte for byte, reserves
all missing-object and manifest capacity, then create-only installs missing objects.
It revalidates the replacement and uses the established checkpoint force/rename
protocol, including forcing reused objects. Genesis and unchanged layouts still
publish the next generation at the same cutoff. Retain the complete journal, all
old files and failed candidates through publication uncertainty. No automatic
scheduling or physical deletion is introduced.

[ADR 0017](0017-live-reader-protection.md) now implements a coarse shared reader
barrier across store lifetimes/processes. Permit physical deletion only after a
remover holds store ownership and exclusive reader protection throughout verified
root selection, removal and synchronization. Pins belong to lazy
readers, outlive the store and release only after active lazy access completes.
Live roots include selected/retained generations, active reader generations and
explicit backup roots. Heap-owned returned values need no file-name pin. Unknown,
uncertain or diagnostic objects remain retained without a verified removal policy.
No timer or same-process registry alone establishes safety. Physical reclamation
must preserve all logical history and the complete journal.

## Primary evidence and design inference

Sources checked October 29, 2026:

- Lucene distinguishes stale commit deletion policy from merging, and snapshot
  policy protects files referenced by held snapshots. Its default snapshot pins
  are in memory; persistence needs separate support.
  [IndexDeletionPolicy](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/IndexDeletionPolicy.html),
  [SnapshotDeletionPolicy](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/SnapshotDeletionPolicy.html).
- RocksDB compaction preserves data visible to active snapshots.
  [Snapshots](https://github.com/meta/rocksdb/wiki/Snapshot).
- Kafka compaction can remove older keyed records and eventually tombstones while
  retaining ordering of remaining records. That contract does not preserve every
  MeridianDB historical answer or retry payload.
  [Log compaction](https://kafka.apache.org/41/design/design/).
- XTDB retains history by default; recovery guidance aligns complete immutable
  file sets with log progress.
  [XTDB](https://docs.xtdb.com/index.html),
  [Backup and restore](https://docs.xtdb.com/ops/backup-and-restore/overview.html).
- Lucene's directory contract separates create-only output, file synchronization,
  metadata synchronization and complete-content rename visibility; `SegmentInfos`
  selects the largest commit generation.
  [Directory](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/store/Directory.html),
  [SegmentInfos](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/SegmentInfos.html).
- JDK 25 documents atomic check/create with `CREATE_NEW`, provider-specific existing
  target behavior for `ATOMIC_MOVE`, and the storage scope of `FileChannel.force`.
  Exclusive ownership and destination-absence checks remain required; file force
  alone does not establish directory-name durability.
  [Files](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html),
  [FileChannel.force](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)).

MeridianDB's exact-history repacking, budgets and required reader-protection
boundaries are design choices informed by these sources. They do not inherit
another engine's retention, locking, replication or crash guarantees.

## Alternatives and consequences

Latest-value compaction would save space by discarding logical history, producing incompatible
historical answers and request lookup. A history-pruning mode needs a new explicit
retention decision, rather than a physical layout optimization.

Publishing or deleting directly from a pure plan would conflate consistency,
actual stored-journal verification, namespace durability and reader liveness.
Separating them preserves the existing fail-closed publication contract while
making each new boundary testable. Generation advancement is physical bookkeeping;
a caller can avoid submitting an unchanged layout. Once explicitly submitted,
`publishCompaction` verifies it and selects the next generation consistently,
consuming one retained-generation slot even when all content IDs are reused.

Greedy whole-record packing is a simple deterministic baseline. It may reduce
repeated header/footer overhead; a smaller target can increase it. Plan byte totals
are not reclaimed disk space, faster queries or a measured throughput result.
All output history is materialized, so larger databases need bounded streaming or
range compaction under a separately verified contract.

## Retained-root inspection

[ADR 0018](0018-reclamation-inventory.md) now implements a bounded inventory of
verified obsolete physical objects under explicit retained roots and exclusive
reader protection. It removes no files and preserves this decision's complete
logical-history contract. Ordered removal is implemented separately and
[integrated conformance](../development/compaction-conformance.md) now exercises
these stages together.

## Protected removal integration

[ADR 0019](0019-protected-physical-reclamation.md) implements the separate remover:
fresh inventory under both owners, retained-file/journal/namespace synchronization,
metadata retirement before unused dependencies and conservative interrupted-orphan
retention. The stages remain distinct; [integrated conformance](../development/compaction-conformance.md)
now exercises full temporal histories, process recovery and named-file lengths
across them. Evidence checked October 31, 2026.
