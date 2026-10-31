---
title: Protected physical reclamation decision
description: Recompute retention under both local owners, establish durable live roots, retire metadata before unused dependencies and preserve interrupted orphan evidence.
---

# ADR 0019: Protected physical reclamation

- **Date:** October 30, 2026
- **Status:** Local removal protocol and integrated local conformance implemented
- **Scope:** Explicit physical cleanup with complete logical history retained

## Context

[Verified inventory](0018-reclamation-inventory.md) distinguishes retained roots,
verified obsolete objects and unclassified evidence. A preview can become stale
immediately. Live lazy readers can outlive the publisher; their
[shared barrier](0017-live-reader-protection.md) must exclude removal across processes.
Visible newest metadata may also follow a publication whose final synchronization
was uncertain. Verification alone cannot justify retiring older roots.

## Decision

Provide `LocalCheckpointStore.reclaim`, which recomputes inventory while holding
exclusive store ownership plus exclusive reader protection. The selected newest
root is mandatory; every explicit retained/backup ID must resolve. Require complete
published graphs and actual owner-journal prefixes before effects, with the same
bounded inspection and conservative diagnostic policy as inventory.

Before deletion, force the journal, every retained manifest image and retained
segment, then force the directory. Delete obsolete complete manifests and force
the directory before any newly unreachable dependency. Delete only their verified
unused dependencies and force the directory again. Check/release reader protection
successfully before returning an immutable receipt. Known reader contention defers
all work; a verified empty candidate set returns an empty completed receipt.

Recheck regular/nonsymlink paths and aliases at deletion. Reserve identity using
the shared ownership helper without opening another file channel. Use strict
`Files.delete`; an absent candidate is an error. Keep the journal and permanent
control names, every live root dependency and unknown/orphan/diagnostic evidence.
No stale preview is accepted, and no operation prunes temporal history.

Mark uncertainty before the first deletion attempt. I/O/runtime failures after
that point return explicit uncertainty; all storage failures and errors poison
the store. Preserve failed protection cleanup for close retry and independently
attempt publisher cleanup. Reopen discovers remaining names and recomputes; it
never infers rollback from an exception or repeats a stale deletion list.

Use no persistent intent record. If interruption retires metadata but leaves old
segments, those segments are now unclassified orphans and remain preserved.
This sacrifices cleanup progress to avoid automatic deletion of diagnostic output.
The [physical reclamation guide](../development/physical-reclamation.md) owns the
API, interruption table, capacity, rollout and receipt limits.

## Primary evidence and inference

Checked October 30, 2026:

- JDK deletion is not necessarily atomic relative to other filesystem operations
  and may fail when files are open. [Files.delete](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#delete(java.nio.file.Path)).
- Locks are cooperative and JVM-wide; closing another same-file channel can
  compromise ownership. [FileLock](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html).
- Lucene distinguishes file synchronization from namespace synchronization; Linux
  describes the need to synchronize directory entries separately.
  [Directory](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/store/Directory.html),
  [fsync](https://man7.org/linux/man-pages/man2/fsync.2.html).
- Lucene separates commit-retention policy and snapshot-held dependencies.
  [IndexDeletionPolicy](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/IndexDeletionPolicy.html),
  [SnapshotDeletionPolicy](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/SnapshotDeletionPolicy.html).

Live-root forcing, the ordered retirement protocol, conservative orphan retention
and explicit uncertainty are MeridianDB design inferences. These sources confer no
inherited Lucene implementation, throughput or device power-loss guarantee.

## Alternatives and consequences

A persistent retirement intent could identify interrupted leftovers for later
removal, but adds its own publication, validation, crash and capacity protocol.
A filename-only orphan sweep cannot distinguish failed diagnostic output. Both
remain separate choices rather than prerequisites for this conservative remover.

Coarse reader protection can indefinitely defer cleanup. Retained roots and bounded
inspection can prevent progress near namespace limits. Receipts count synchronized
names and encoded lengths, not freed blocks. [Integrated conformance](../development/compaction-conformance.md)
now combines canonical/seeded temporal and retry histories, richer process stops
and exact named-file length accounting. This bounded evidence supplies local
conformance coverage without a device power-loss or performance claim.
