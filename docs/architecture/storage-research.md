---
title: Immutable storage and realtime search lessons
description: Compare primary-source designs from XTDB, Kafka, RocksDB, Lucene, Elasticsearch, Quickwit and ClickHouse, and apply their journal and immutable-publication lessons to MeridianDB.
---

# Immutable storage and realtime search lessons

Immutable, append-only systems offer useful approaches to ordered ingestion,
recovery and realtime search. This comparison examines how those approaches apply
to MeridianDB's temporal storage design.

**Sources checked October 28, 2026.** The linked documentation describes each
system's guarantees. The MeridianDB implications are design judgments; they do not
establish dependencies or performance results.

## What the comparable systems teach

| System and primary evidence | Observed approach | MeridianDB implication |
| --- | --- | --- |
| [XTDB transactions/consistency](https://docs.xtdb.com/about/txs-in-xtdb.html) | A transaction's operations enter a durable ordered log as one atomic message; indexing follows log order, with explicit query visibility coordination. | Keep one complete ordered transaction per journal frame. Distinguish an accepted log position from the position available to queries. |
| [Kafka 4.4 record batches](https://kafka.apache.org/44/implementation/message-format/) | Versioned batches have lengths, CRC32C coverage and producer sequence metadata; compaction preserves sequence information needed for duplicate checks. | Protect framing and payload, preserve commit/request identity and plan group I/O without merging logical transaction identities. Adopt no Kafka wire compatibility. |
| [RocksDB WAL format](https://github.com/facebook/rocksdb/wiki/Write-Ahead-Log-File-Format) | Physical record fragments carry checksums and types, allowing larger logical records to span blocks. | Specify corruption and incomplete-record handling before writing files. Start with bounded complete frames; fragmentation requires a demonstrated workload and another format decision. |
| [Lucene 10.5.2 IndexWriter](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/IndexWriter.html) | Readers retain point-in-time snapshots. Flush moves buffered segments into a directory; commit supplies the sync boundary. One writer owns a directory lock. | Keep reader publication, byte append and durable force explicit. Use exclusive local writer ownership and retain old segment references while readers need them. |
| [Elasticsearch translog](https://www.elastic.co/docs/reference/elasticsearch/index-settings/translog) | A transaction log recovers operations absent from the last Lucene commit. Request and async durability policies provide different acknowledgement guarantees. | A later durable engine must force recoverable log state before durable acknowledgement. A complete decoded frame alone proves neither sync nor acknowledgement. |
| [Quickwit 0.9 indexing/checkpoints](https://quickwit.io/docs/overview/concepts/indexing) | Immutable splits are published with atomic checkpoint advancement in metadata; restart resumes from the published source position. | A manifest must name the included journal prefix and publish only when all referenced immutable objects are available. Segment creation alone cannot justify advancing the checkpoint. |
| [ClickHouse table parts](https://clickhouse.com/docs/concepts/core-concepts/parts) | Inserts create immutable sorted parts that are merged; batching reduces small-part and merge overhead. | Avoid a new disk segment per tiny transaction. Batch physical work separately from temporal commit order and measure the resulting visibility lag. |
| [ClickHouse async inserts](https://clickhouse.com/docs/concepts/features/operations/insert/asyncinserts) | Waiting for buffer flush gives different error/acknowledgement behavior from accepting buffered data immediately; bounded ingestion must respond to downstream pressure. | Do not label a queue admission as durable success. Bound future queues by bytes and work; block or reject explicitly when storage/indexing falls behind. |

These systems have different products and replication models. Their mechanisms do
not establish MeridianDB's bitemporal semantics, historical retention, throughput,
latency or crash guarantees. Search-engine merging cannot decide which historical
answers a temporal database is permitted to discard.

## Separate the publication boundaries

| Boundary | Meaning in MeridianDB's design | Current implementation |
| --- | --- | --- |
| Logical commit position | Total order of complete transactions within one lineage | Implemented by the memory engine; encoded by the journal codec |
| Complete appended prefix | Bytes contain complete, validated transaction frames | Implemented by codec and local sequential writer |
| Forced journal prefix | Local storage has completed the declared force protocol | Explicit local file force; namespace durability remains separate |
| Query-visible cutoff | An immutable view can answer queries at that commit | Implemented in memory; durable integration is planned |
| Immutable checkpoint | Published segments/manifest cover an identified journal prefix | Local generation publication implemented with explicit filesystem requirements; engine integration planned |

For the initial durable engine, the intended order is: prepare and validate a
transaction, append its frame, complete the force boundary, publish its immutable
read state, then acknowledge. A lost acknowledgement can leave a recoverable
complete transaction; retry identity must resolve it without a second effect.
This protocol remains future work, with crash tests required before claiming it.

If later indexing becomes asynchronous, expose its position and an explicit
wait-for-cutoff mechanism. An ingestion acknowledgement must not silently change
from query-visible durable success into mere queue acceptance. Any such API change
needs its own contract and compatibility decision.

## Throughput work that preserves the contract

- Preserve a single ordered sequencer first. Physical group commit may append
  several complete frames and force them together while each transaction keeps
  its own sequence, identity and atomic boundary. Measure the group size/latency
  tradeoff before introducing scheduling or concurrency machinery.
- Separate ingestion from immutable segment generation and merging. Keep queues
  bounded and observable by bytes, pending commits and oldest age; include disk
  space and downstream publication backlog in admission decisions.
- Treat indexes as derived state. A checkpoint identifies the exact included
  commit prefix; replay restores ordered operations and request identity rather
  than only the latest values. Reclaim journal data only under a proven recovery
  and retention policy covering old views, history and backups.
- Keep short/failed writes, force failures and uncertain outcomes observable.
  Preserve diagnostic bytes and stop an uncertain writer; never resynchronize past
  corruption and present the remaining suffix as a valid contiguous history.
- Benchmark equivalent durability and query visibility. Report ingest-to-force
  latency, ingest-to-search latency, replay time, memory, write/read amplification
  and merge backlog separately. The map-copying memory prototype is a correctness
  baseline, not the intended high-throughput layout.

## Application to MeridianDB

The [journal format and codec](journal-format.md) implement bounded, complete
transaction frames. The [local adapter](../development/local-journal.md) adds append/force,
writer exclusion, sequential recovery and explicit evidence-preserving tail repair.
The [immutable history codec](immutable-segment-format.md) preserves original
transaction ranges with complete-object integrity and expected content identifiers.
The [local segment adapter](../development/local-segments.md) supplies create-only
file force and fully verified heap readers. The
[manifest codec and verifier](checkpoint-manifest-format.md) validate a supplied
segment set and reconstruct its exact journal-prefix binding. The separate
[local checkpoint store](../development/local-checkpoints.md) compares that binding
with the actual owner journal, forces dependencies, then atomically selects and
synchronizes an immutable generation. [ADR 0012](../decisions/0012-local-checkpoint-publication.md)
refreshes JDK and Lucene filesystem evidence and explains generation selection,
capability gating and uncertain publication. Engine acknowledgement and recovery
remain separate integration work.

Compression, block fragmentation, memory mapping, persistent collections and
specialized indexes remain experiments driven by measured limitations. See the
[technical roadmap](../roadmap.md) for the current capabilities and planned work.
