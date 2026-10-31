---
title: Live checkpoint reader protection decision
description: Use a coarse shared file-lock barrier to preserve lazy checkpoint access across store lifetimes and processes before introducing physical reclamation.
---

# ADR 0017: Live checkpoint reader protection

- **Date:** October 29, 2026
- **Status:** Implemented shared-reader foundation; removal implemented separately
- **Scope:** Cooperating local lazy readers, OS lock lifetime and future deletion exclusion

## Context

[History-preserving compaction](0016-history-preserving-compaction.md) publishes
immutable replacements while retaining old files. A lazy checkpoint reader may
outlive store ownership and still need an old segment. A process-local map, elapsed
time or a selected latest manifest cannot prove that no other reader needs those
files. Logical retention remains complete history.

## Decision

Use a permanent `.meridiandb-readers.lock` file and a **store-wide shared OS lock**
for each admitted lazy reader. Verify/admit under exclusive store ownership and
transfer protection to the reader before returning it. Keep one reference-counted
channel per canonical underlying guard identity in a loaded JVM library copy;
use independent shared locks across cooperating processes. Require actual shared
lock support, with no silent exclusive fallback.

The reader serializes release with lazy opens, becomes logically closed before
cleanup and retains retryable protection after a release failure. Store close leaves
readers protected. Heap child readers and returned immutable values retain their
existing lifetimes. Database cleanup attempts all owners even when reader close fails.

The remover must acquire store ownership first, then nonblocking exclusive
reader protection. Any live reader defers all removal, including unrelated old
generations. Keep both forms of ownership through root verification, removal and
synchronization. Implement no deletion in this foundation.

Retain the guard name permanently and reject aliases before another handle opens.
Keep uncertain channel cleanup quarantined until process restart; a thrown native
close is not proven cleanup even when Java reports the channel closed; quarantine unexpected
JVM overlap without closing a handle that might release another owner's lock.
The [reader guide](../development/reader-protection.md) owns API, capacity, cleanup,
rollout and capability details.

## Primary evidence and inference

Sources checked October 29, 2026:

- JDK 25 describes JVM-wide locks, nonoverlapping regions even for shared locks,
  provider conversion of shared requests and the hazard of closing another same-file
  channel. File locks are cooperative and filesystem dependent.
  [FileLock](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html).
- `tryLock` distinguishes external contention from same-JVM overlap; it supplies
  nonblocking acquisition, without a portable shared-to-exclusive upgrade.
  [FileChannel.tryLock](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html#tryLock(long,long,boolean)).
- Java marks a channel closed before invoking native cleanup and attempts that
  cleanup at most once. A thrown close therefore cannot be cleared by a no-op retry;
  retain the ownership reservation until process restart.
  [AbstractInterruptibleChannel.close](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/spi/AbstractInterruptibleChannel.html#close()).
- Linux documents the any-close hazard for traditional process locks; Windows
  documents possible delay before process-exit unlock becomes visible.
  [fcntl locking](https://man7.org/linux/man-pages/man2/fcntl_locking.2.html),
  [LockFileEx](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-lockfileex).
- Lucene snapshot policy protects a held commit's files and counts references by
  generation; its default snapshot pins are in memory. Its deletion policy separately
  controls stale commits.
  [SnapshotDeletionPolicy](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/SnapshotDeletionPolicy.html),
  [IndexDeletionPolicy](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/IndexDeletionPolicy.html).

The coarse cross-process barrier is a MeridianDB design choice informed by these
sources. It inherits no Lucene deletion or OS durability guarantee. A lock protects
live readers; it does not represent an offline backup or retention policy.

## Alternatives and consequences

Per-generation lock files or durable pin records could permit more reclamation
progress but require additional admission, stale-record cleanup and crash proofs.
The coarse barrier supplies a smaller correctness foundation; long-lived readers
can indefinitely block removal and exhaust retained namespace capacity.

The protocol supports one loaded adapter registry per JVM and requires every
participating reader to acquire protection. Existing unprotected readers from an
older binary must be quiesced before physical deletion is enabled. A permanent guard,
explicit close, shared-lock support and write/name-sync permission are new resource
requirements for lazy reader admission. Existing segment/manifest/journal bytes and
all temporal query/retry semantics are unchanged.

## Inventory integration

[ADR 0018](0018-reclamation-inventory.md) now uses the exclusive barrier for bounded
retained-root and obsolete-object inspection. Any live lazy reader defers that
inspection. No physical deletion is introduced; all rollout, ownership and uncertain
cleanup requirements above continue to apply.

## Protected removal integration

[ADR 0019](0019-protected-physical-reclamation.md) implements the separate remover:
fresh inventory under both owners, retained-file/journal/namespace synchronization,
metadata retirement before unused dependencies and conservative interrupted-orphan
retention. This record's stage remains distinct; full integrated temporal/crash/space
conformance is separately required.
