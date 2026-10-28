---
title: Durable local commit publication
description: Order private commit preparation, journal append and force, then atomic query publication, with synchronized writable startup and explicit uncertain outcomes.
---

# ADR 0014: Durable local commit publication

- **Date:** October 28, 2026
- **Status:** Implemented; experimental protocol with crash conformance still pending
- **Scope:** Local creation, writable reopen, forced acknowledgement and atomic query publication

## Context

Read recovery preserves stored transactions but cannot prove an earlier writer's
force or acknowledgement. A writable API must establish its own storage boundary
before promising a commit, and readers must never see a partially prepared state.
A force error can leave complete bytes on storage, so an exception cannot be treated
as rollback. Identical retries after recovery also need a newly established force
boundary before returning a successful acknowledgement.

## Decision

Keep `LocalDatabase.open` logically read-only. Add explicit `create` and
`openWritable` modes with the same exclusive journal/store ownership and trusted,
stable, durably named directory requirements.

Creation accepts a fresh directory containing at most the store's lock file.
Validate lineage before store mutation, create the owned journal and publish a
sequence-zero genesis manifest as generation one through the existing checkpoint
protocol. This forces dependencies and namespace before creation succeeds. Retain
all files after failure and release acquired owners; never implicitly delete and
recreate storage.

Writable reopen first verifies and reconstructs complete history, then forces the
owner journal and its directory before exposing a writer. This re-establishes a
synchronization boundary for all recovered complete transactions, including a
previously unacknowledged suffix. It does not prove their earlier acknowledgement.
The provider and device must honor force for existing dirty data and directory
names; a successful JDK call alone is not a universal hardware guarantee.

For each new transaction, serialize the following sequence with other writers
and close:

1. Prepare complete immutable values, history, request identity, commit position
   and result without changing the published state. Preflight record encoding and
   format bounds before invoking append.
2. Mark the operation as an attempted write before calling the journal adapter.
   Append the exact next record, then force through that same owning journal.
3. Publish the prevalidated immutable state with one reference assignment and
   return its preallocated result. The valid publication path allocates no new
   state/result after force.

Readers can continue using the previous committed state during append/force.
Publication includes values, historical cutoffs and request metadata together.
Identical retries return the original position without another append or force;
that position was covered by writable startup or an earlier acknowledged write.
Conflicting reuse fails without storage effects. No-op requests still allocate a
new commit. Existing historical views retain their cutoffs.

Any I/O or runtime failure after the append attempt produces
`UncertainCommitException` with the request ID and proposed position. Poison the
database, invalidate future operations/views and retain locks until close. Errors
also poison before propagating. Reopen to resolve whether the complete request is
present; do not infer rollback, continue writing or silently repair an incomplete
tail. Cleanup failure after an already successful commit does not revoke that
commit's acknowledgement.

No automatic checkpoint advancement or reclamation is added. The database owns
the store lock while open; external checkpoint publication requires closing the
database and acquiring the storage primitives. The startup checkpoint cutoff can
remain behind the current head. The [local database guide](../development/local-database.md)
owns usage, failures, lifetimes and implementation limits.

## Primary evidence and design inference

Sources checked October 28, 2026:

- Elasticsearch request durability waits for translog synchronization before
  reporting success; its asynchronous setting admits a different loss window.
  [Translog](https://www.elastic.co/docs/reference/elasticsearch/index-settings/translog).
- RocksDB distinguishes synchronous writes from WAL writes buffered by the OS.
  Process termination and machine failure have different persistence boundaries.
  [Synchronous writes](https://github.com/facebook/rocksdb/wiki/Basic-Operations#synchronous-writes).
- Lucene distinguishes flush from commit and warns that hardware can defeat
  filesystem synchronization assumptions.
  [IndexWriter.commit](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/IndexWriter.html#commit()).
- JDK file force is local-storage scoped and depends on covered channel/provider
  behavior. Linux requires a separate directory synchronization for names. Keep
  all journal operations on its owner handle to preserve platform lock behavior.
  [FileChannel](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)),
  [fsync](https://man7.org/linux/man-pages/man2/fsync.2.html),
  [FileLock](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html).

MeridianDB's prepare/publish split, genesis selection, startup barrier and exception
contract are design choices informed by these sources. They do not inherit another
product's replication, transactional engine or performance guarantees.

## Alternatives and consequences

Publishing memory before force allows readers to observe a transaction before its
promised storage boundary. Rebuilding state after force introduces substantial
allocation/failure work after storage may have committed. Preparing a complete
immutable candidate first keeps the remaining publication small and explicit.

Treating every append error as known absence is unsafe when an operation may have
taken effect and then failed. Conservative uncertainty sacrifices continued use of
the instance while retaining inspectable history. A recovered complete suffix may
become visible even when its earlier acknowledgement was lost.

Forcing every new transaction is a simple initial contract with no group-commit
throughput claim. Existing snapshots and request history still grow in memory.
Batching, checkpoint scheduling and native durability capabilities need separate
evidence. Process crash/retry and seeded durable temporal conformance remain
required subsequent verification; current tests do not simulate device power loss.
