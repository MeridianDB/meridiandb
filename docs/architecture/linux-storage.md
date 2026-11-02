---
title: Linux storage and temporal file layouts
description: Design and measure Linux local persistence with separate transaction history, temporal query blocks, bounded integrity checks and workload-specific file and I/O experiments.
---

# Linux storage and temporal file layouts

Linux local persistence is MeridianDB's primary development target for production
use. The current implementation is experimental: it has a forced transaction
journal, immutable history segments and verified checkpoint publication, but no
production qualification or performance results. [ADR 0039](../decisions/0039-linux-local-storage-priority.md)
records the platform and storage priorities. The
[Linux qualification harness](../development/linux-storage-qualification.md)
now binds the complete core inventory and native probes to an observed scratch
filesystem, with fresh per-run environment and result evidence.

S3 remains an optional experimental integration with further development deferred.
Kafka-topic storage and other persistence adapters are future possibilities.
Windows has a basic compatibility role; native filesystem feature parity is
outside the current target. The existing Linux/Windows CI runs more checks than
that minimum and does not establish production support on either platform.

## Start from the existing format

The [v1 journal](journal-format.md) stores ordered transactions with request
identity, valid-time bounds and checksums. [V1 history segments](immutable-segment-format.md)
wrap contiguous original journal frames in bounded immutable objects with complete
integrity verification. [Manifests](checkpoint-manifest-format.md) bind their
history to the actual journal prefix. Current reads validate complete objects;
recovery reconstructs full history in memory, and a new transaction copies maps
while preparing its immutable state.

These choices establish expected answers and recovery behavior. They are not yet
a selective disk query engine. A new byte encoding alone will not remove full
replay, per-commit copying or unbounded aggregate heap use; the query path,
materialized state and memory budgets need corresponding designs and measurements.

## Separate transaction history from query layout

Use the following separation as a candidate architecture, not an implemented
replacement format:

| Component | Candidate role | Required invariant |
| --- | --- | --- |
| Transaction journal | Sequential append optimized for complete commits and force | Preserve original order, whole transactions, no-ops and exact retry payloads |
| Immutable history | Durable, versioned source for replay and verification | Retain every supported historical cutoff and original identity |
| Temporal query blocks | Sorted entity/time keys with compact value and interval columns | Resolve the same temporal state as the independent reference |
| Block index and filters | Locate entity groups and conservatively prune time regions | Skip only when no requested answer can be hidden |
| Published manifest | Bind layout version, lineage, indexed cutoff and all dependencies | Expose a complete generation under the existing publication protocol |

An initial candidate can use entity-first sorted blocks, dictionary/prefix-coded
entity identities, delta or bit-packed numeric coordinates, separate value columns
and small block metadata. Compare a second valid-time-oriented or multidimensional
access path only if interval/aggregate workloads justify its extra space and
maintenance cost. Block size, compression and index granularity are experimental
variables. No codec, dependency, extension or binary layout is selected here.

The current entity order is exact Java UTF-16 order, including lone surrogates;
changing to a convenient UTF-8 byte order is not automatically compatible. Finite
minimum/maximum timestamps must remain distinct from unbounded endpoints. Bounds,
decoded sizes, decompression ratios, nested allocations and total cache/recovery
memory need explicit limits before data is exposed.

### Corrections make temporal pruning different

Consider a value of `100` valid from time 10, committed at position 1. A correction
committed at position 2 replaces it with `120` on `[20, 30)`. At knowledge cutoff 1,
the original value still applies at time 25; at cutoff 2, the correction applies,
while the original remains effective outside the corrected interval.

A single system-end coordinate on the original unsplit assertion cannot represent
all those regions. An immutable event layout must resolve later corrections;
a materialized rectangle layout must split regions and bind its results to an
exact indexed cutoff. Derived end coordinates belong to that materialization,
not to an in-place rewrite of historical journal records.

Entity filters, minimum/maximum times and geometry are candidate-selection tools.
Apply the temporal visibility/precedence rules before value predicates: omitting a
newer nonmatching correction or retraction must not revive an older matching put.
Keep the complete original transaction/request stream for retries and recovery.
If an index lags committed history, merge a verified tail or explicitly wait for
the required cutoff; never silently return an older answer.

## Learn from existing file formats

Primary sources checked **November 2, 2026**. The implications below are design
inferences, not comparative performance results.

| Source | Documented mechanism | Candidate use in MeridianDB |
| --- | --- | --- |
| [Parquet file layout](https://parquet.apache.org/docs/file-format/) and [page indexes](https://parquet.apache.org/docs/file-format/pageindex/) | Column chunks grouped into row groups, footer metadata and optional page-level min/max and row-offset indexes | Compare compact column reads and selective page access; Parquet already supports pruning and point-lookup-oriented page selection |
| [RocksDB block table](https://github.com/facebook/rocksdb/wiki/Rocksdb-BlockBasedTable-Format) | Sorted data blocks with index, metadata and filter blocks | Compare small bounded reads, entity-key lookup and independently budgeted block caches |
| [Lucene BKD tree](https://raw.githubusercontent.com/apache/lucene/releases/lucene/10.6.0/lucene/core/src/java/org/apache/lucene/util/bkd/BKDReader.java) | Multidimensional point-tree traversal with cell intersection tests | Compare conservative temporal-region pruning after establishing an entity-first baseline; retain the separate temporal resolver |

Parquet is a credible baseline for analytical scans and an interoperability/export
candidate. A purpose-built format may reduce work for entity histories, as-of
lookups and corrections by matching their ordering and metadata needs. It may
lose on broad scans, compression, mature tooling or write/merge cost. Evaluate
those tradeoffs rather than claiming universal superiority.

Selective block reads also need a new integrity contract. The current expected
whole-object SHA-256 cannot independently validate a selected block without the
rest of the object. Specify trusted manifest/index
binding, block lengths/checksums and any independently verifiable hashes, corrupted
offset handling and complete publication before introducing lazy access. Retain v1
readers, golden vectors and an explicit migration or rebuild path for any successor.

## Linux I/O candidates

Primary sources checked **November 2, 2026**. Keep a Java `FileChannel` buffered-I/O
baseline and profile the bottleneck before choosing a native mechanism.

| Mechanism | Possible experiment | Correctness boundary |
| --- | --- | --- |
| [File and directory synchronization](https://man7.org/linux/man-pages/man2/fsync.2.html) | Measure force latency and later compare explicitly specified batching | File synchronization alone does not persist a new directory entry; keep namespace barriers and acknowledgement guarantees |
| [Preallocation](https://man7.org/linux/man-pages/man2/fallocate.2.html) and [access advice](https://man7.org/linux/man-pages/man2/posix_fadvise.2.html) | Test allocation stalls, sequential replay and cache pollution | Separate logical length/valid frames from allocated extents; hints and reserved space do not commit data |
| [Reflink cloning](https://man7.org/linux/man-pages/man2/ioctl_ficlonerange.2.html) and [kernel-assisted copying](https://man7.org/linux/man-pages/man2/copy_file_range.2.html) | Test immutable checkpoint/backup installation with less copying | Reflinks share a storage failure domain; copy can be short. Neither operation by itself provides node replication or durable publication |
| [Direct I/O](https://man7.org/linux/man-pages/man2/open.2.html) | Compare a controlled cache strategy only if buffered I/O is the measured limit | Discover alignment and supported filesystem/kernel behavior; avoid overlapping buffered/direct/mapped access and retain synchronization |
| [io_uring](https://man7.org/linux/man-pages/man7/io_uring.7.html) | Compare batched independent block reads when submission cost matters | Model dependencies, partial I/O, cancellation and buffer lifetime; completion order is not transaction or force order |

[Hadoop's native library](https://hadoop.apache.org/docs/current/hadoop-project-dist/hadoop-common/NativeLibraries.html)
includes I/O utilities and compression/checksum support. Its
[short-circuit reads](https://hadoop.apache.org/docs/current/hadoop-project-dist/hadoop-hdfs/ShortCircuitLocalReads.html)
bypass a DataNode for colocated reads. This supports investigating narrow native
facilities; it does not imply an embedded local engine needs HDFS or that native
code is always faster. Filesystem cloning/copying and network replication remain
different problems.

Any native binding needs a scoped decision covering kernel/filesystem support,
JVM integration, alignment, ownership, cleanup, error propagation and packaging.
Java remains the engine language. Native code and new runtime dependencies are
unselected; unsupported optional accelerators can use a verified baseline path,
while missing required durability capabilities must fail explicitly.

## Qualification and measurement order

1. **Define and probe the Linux profile.** Record kernel, JDK, architecture,
   filesystem/mount settings, storage device/cache and required lock, identity,
   rename, file-force and directory-force behavior. Select supported configurations
   from actual evidence; ext4 and XFS are candidates, not blanket support claims.
2. **Measure the unchanged local engine.** Include current and historical entity
   lookups, interval scans, correction-heavy and retraction-heavy histories,
   ingestion, checkpointing, restart and compaction with pinned readers. Vary entity
   count, history depth, interval overlap, skew and correction rate using saved seeds.
   The test-only [workload corpus](../development/workload-corpus.md) now supplies
   bounded actions, independent expected answers and observed distributions;
   timing/resource accounting and actual Linux baseline evaluation remain separate.
3. **Compare one layout change.** Hold semantics, acknowledgement and retention
   equal. Report latency distributions, throughput, CPU, allocation/heap, encoded
   and allocated bytes, bytes read, write amplification, replay cost and merge
   backlog. Include cold/warm cache conditions and mixed-workload regressions.
4. **Compare one I/O change when justified.** Isolate its effect from layout,
   compression and batching. Ordinary hosted CI establishes correctness, not
   controlled-device performance. Reject an optimization that lacks useful benefit.
5. **Close production gaps.** Verify bounded resource use, lifecycle/checkpoint
   operation, diagnostics, compatibility and crash/recovery behavior on the selected
   profiles. Process exits and injected faults do not establish device power-loss
   guarantees. State which evidence is still missing.

Future replicas should own their own local files and consume original ordered
transactions through a separately specified protocol. Local qualification and
format measurements do not wait for S3, Kafka or distributed leadership.
