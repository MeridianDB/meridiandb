---
title: Verified reclamation inventory decision
description: Define explicit physical retention roots and bounded verified obsolete-object inventory before implementing protected deletion.
---

# ADR 0018: Verified reclamation inventory

- **Date:** October 29, 2026
- **Status:** Inventory implemented; protected removal implemented separately
- **Scope:** Local checkpoint retention, diagnostic evidence and candidate accounting

## Context

[Exact-history compaction](0016-history-preserving-compaction.md) creates another
physical representation while retaining every transaction. The [reader barrier](0017-live-reader-protection.md)
protects cooperating lazy readers across processes, but absence of a live reader
alone cannot prove that an old file is obsolete. Backups and explicitly retained
generations need roots, while failed candidates need an inspected evidence policy.

## Decision

Provide `LocalCheckpointStore.inspectReclamation`: acquire exclusive store and
reader protection, validate all published manifests and complete dependencies
against the actual owned journal, then expose an immutable deterministic inventory.
The selected generation is always retained. Additional published IDs explicitly
retain physical generations and their dependencies; missing IDs fail. They are
per-invocation choices, not persistent pins. Generation gaps remain valid.

Candidate metadata consists only of verified obsolete published manifests.
Candidate segments consist only of those manifests' dependencies absent from all
selected/retained roots. Preserve unknown and unreferenced objects, the full journal
and permanent controls. Refuse any pending candidate until separate inspection
resolves its role. Reject identity aliases before opening another object channel;
closing a second handle to a control inode could already compromise ownership.

Bound repeated graph bytes, declared record work and identity comparisons. Count
shared history again for each root's verification. Return no partial inventory,
including on protection cleanup failure. Known reader contention returns a deferred
status and leaves the store usable. Other failures poison it; preserve retryable
protection cleanup and quarantine uncertain native close. The
[inventory guide](../development/reclamation-inventory.md) specifies API, budgets,
retention responsibilities and failure behavior.

This operation removes no files. First exclusive admission may create/synchronize
the permanent guard. A result can immediately become stale; the separate remover must
recompute while holding both forms of ownership through checks, retirement and
sync. Durable metadata retirement must precede dependency removal. Interrupted
retirement may leave orphaned dependencies requiring separate inspected evidence;
no filename-only sweep is implied. Logical retention stays complete history.

## Evidence and design inference

Primary sources checked October 29, 2026:

- Lucene separates commit-retention policy, a commit's referenced filenames and
  snapshot-held references. [IndexDeletionPolicy](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/IndexDeletionPolicy.html),
  [IndexCommit](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/IndexCommit.html),
  [SnapshotDeletionPolicy](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/SnapshotDeletionPolicy.html).
- JDK deletion may fail for open files and is not necessarily atomic relative to
  other filesystem operations. Locks are cooperative and JVM-wide; closing another
  same-file channel can affect them. [Files.delete](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#delete(java.nio.file.Path)),
  [FileLock](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html).
- Lucene distinguishes file synchronization from namespace synchronization; Linux
  describes the need to synchronize directory entries separately. JDK force retains
  provider/storage assumptions. [Directory](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/store/Directory.html),
  [fsync](https://man7.org/linux/man-pages/man2/fsync.2.html),
  [FileChannel.force](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)).

Explicit roots, conservative diagnostic refusal, aggregate budgets and the
metadata-before-dependencies protocol are MeridianDB design inferences. No Lucene
retention implementation, OS durability guarantee or measured throughput is inherited.

## Alternatives and consequences

A broad sweep of unreferenced filename-shaped segments could recover more space
but cannot distinguish unrelated or failed diagnostic output under the current
contract. A newest-only graph check would leave obsolete dependencies unverified.
Persistent backup pins and per-generation live-reader locks could provide different
retention ergonomics and progress; both require additional lifecycle/recovery rules.

This preview favors evidence and bounded implementation. Repeated old history,
pending candidates, an inaccessible alias, namespace pressure or a long-lived reader
can prevent useful inventory. Candidate bytes are encoded-object counts rather
than physical-space receipts. The separate remover implements ordered retirement;
[integrated conformance](../development/compaction-conformance.md) now exercises
full temporal histories, process recovery and named-file lengths across the stages.

## Protected removal integration

[ADR 0019](0019-protected-physical-reclamation.md) implements the separate remover:
fresh inventory under both owners, retained-file/journal/namespace synchronization,
metadata retirement before unused dependencies and conservative interrupted-orphan
retention. The stages remain distinct; [integrated conformance](../development/compaction-conformance.md)
now exercises full temporal histories, process recovery and named-file lengths
across them. Evidence checked October 31, 2026.
