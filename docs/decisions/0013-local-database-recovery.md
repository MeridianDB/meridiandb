---
title: Local database recovery decision
description: Restore temporal query state from verified immutable history and a journal suffix while preserving original lineage, request identity and an explicit read-only API boundary.
---

# ADR 0013: Local database recovery

- **Date:** October 28, 2026
- **Status:** Implemented; experimental read-only local API
- **Scope:** Complete checkpoint-plus-journal reconstruction before query exposure

## Context

Verified immutable objects and journal bytes do not themselves answer temporal
queries. Reopen must restore the stored lineage and all historical knowledge, not
only current values. A valid checkpoint can precede the journal head; ignoring the
suffix loses complete transactions, while applying its prefix twice can duplicate
effects or retry identities. Recovery also cannot infer an earlier writer's
acknowledgement from surviving bytes.

## Decision

Add `LocalDatabase.open` for existing local storage. Acquire the checkpoint store
and journal using their current exclusive ownership rules. Preserve the existing
capability probe, including directory force, and reject incomplete tails without
automatic repair. Verify the highest selected manifest, all referenced segments
and the actual owner-channel journal prefix before reconstructing query state.
Support journal-only and sequence-zero checkpoint histories.

Seed a private interval engine with the stored `DatabaseId`. Restore every original
checkpoint transaction, then only the journal suffix after its cutoff. Require
contiguous sequences and unique request identities, including no-op commits.
Keep exact request payloads and every historical snapshot. Expose the database
only after all replay succeeds; discard partial state and release acquired owners
on failure. Retain exclusive ownership for the opened database's lifetime.

Reuse the production interval-state algorithm while keeping the test reference
independent. The public `InMemoryEngine` constructor still creates a fresh lineage;
its package-private seeded constructor and restore operation serve recovery.
A repeated identity in stored history is invalid, even when its payload is
identical. It is not an application retry and must not silently skip a commit.

Expose read views, head, checkpoint cutoff, request lookup and close. Introduce
engine transactions only with an explicit prepare/append/force/atomic-publication
protocol and uncertain-outcome tests. This keeps recovered reads useful without
implying a write acknowledgement guarantee. The [recovery guide](../development/local-database.md)
owns API usage, lifetimes, failure handling and costs.

## Primary evidence and design inference

Sources checked October 28, 2026:

- XTDB aligns finalized immutable data with indexed log blocks and distinguishes
  later log progress. It also documents refusing startup when storage and log
  positions conflict. These inform explicit prefix/suffix alignment, rather than
  silent fallback or an assumed current-state snapshot.
  [Backup and restore](https://docs.xtdb.com/ops/backup-and-restore/overview.html),
  [Out-of-sync log](https://docs.xtdb.com/ops/backup-and-restore/out-of-sync-log.html).
- Lucene selects committed segment metadata by generation and validates the commit
  representation. Its data/metadata publication ordering provides a separate
  storage boundary; reader open alone should not be treated as verification of
  every payload byte.
  [SegmentInfos](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/SegmentInfos.html),
  [implementation](https://github.com/apache/lucene/blob/releases/lucene/10.5.2/lucene/core/src/java/org/apache/lucene/index/SegmentInfos.java).
- JDK file locks require platform/cooperating-writer assumptions; closing another
  same-file channel may release process locks. Reuse the journal owner's handle.
  Read-only file-channel force can still perform I/O, so logical read-only recovery
  retains an explicit directory-probe and writable-lock permission requirement.
  [FileLock](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html),
  [FileChannel](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)).

MeridianDB's exact replay order, read-only API, complete verification and retained
historical interval maps are design choices. These sources do not establish a
MeridianDB durability or throughput result.

## Alternatives and consequences

Replaying the whole journal directly would reconstruct the same state, but would
leave checkpoint integration unexercised. Replaying a checkpoint prefix and only
the journal suffix makes their boundary explicit while retaining full journal
validation. It currently costs additional sequential passes; it does not accelerate
reopen or authorize truncation.

Flattening the checkpoint to current intervals would discard earlier knowledge and
retry payloads. A future materialized-state format must preserve those semantics
and provide its own verification. Retaining all interval snapshots instead gives
a simple correctness baseline with growing heap and map-copy costs.

A writable API that only updates recovered heap state would be misleading for a
local database. Durable writes remain a separate implementation with force and
acknowledgement evidence. Independent crash/retry and temporal conformance will
complete that integration; this decision establishes read recovery only.
