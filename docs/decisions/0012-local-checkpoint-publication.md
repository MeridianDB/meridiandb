---
title: Local checkpoint publication decision
description: Select immutable checkpoint generations using exclusive ownership, exact stored-journal binding, atomic rename and separate file and directory synchronization.
---

# ADR 0012: Local checkpoint publication

- **Date:** October 28, 2026
- **Status:** Implemented; experimental local adapter
- **Scope:** Local generation publication and verified reopen; engine recovery remains separate

## Context

A consistent manifest can describe bytes that have never reached storage. File
force does not itself establish a new directory entry, and an exception from a
rename cannot establish whether the name became visible. Recovery needs one
explicit selection rule and a complete dependency graph tied to the real journal.

## Decision

Use immutable generation manifests in one existing trusted, stable, durably named
directory. The journal and content-addressed segments share this directory.
Select the highest positive generation, verifying its filename ID, metadata,
complete history and actual journal prefix. Reject malformed selected names,
duplicate generations and damaged newest state without fallback. Pending files
are evidence, never recovery candidates. Retain all generations and dependencies;
reader close does not authorize deletion.

Require exclusive cooperating ownership and exact next-generation publication.
Read and hash the stored journal through its existing `LocalJournal` owner,
validating framing, sequence and request uniqueness to the requested cutoff.
Never open a second journal channel to verify or force it. Reject object aliases
of owned journal/lock files before opening another handle.

Probe directory open/force before creating lock files. For publication, verify
all dependencies, force the owner journal, force referenced segments, then force
their directory entries. Write a create-only pending manifest, force and close it,
atomically rename it to the selected name, and force the directory again before
success. Atomic move has no non-atomic fallback. Reserve directory/generation
capacity before publication side effects.

Poison the adapter after storage failure. Mark the outcome uncertain before
attempting rename because a provider may perform it and then report an error.
Reopen to inspect and validate selected state; never infer rollback, restore an
older generation or delete evidence. These operations remain separate from
engine acknowledgement, replay after the checkpoint and retention.

The [local checkpoint guide](../development/local-checkpoints.md) owns the exact
API procedure, filenames, limits, failures and reader lifetimes.

## Primary evidence and design inference

Sources checked October 28, 2026:

- JDK `FileChannel.force` describes forcing updates to the underlying storage and
  conditions its guarantees on local storage. File force alone does not define
  portable namespace persistence.
  [FileChannel](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)).
- JDK `Files.move` defines `ATOMIC_MOVE` and unsupported-operation failure. Target
  replacement under atomic move is implementation specific. Exclusive ownership
  and stable paths establish the destination precondition here; the API does not
  provide a universal atomic create-if-absent primitive.
  [Files.move](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)).
- JDK locks have platform-dependent behavior, including possible release when
  another channel on the same file closes. Reserve same-JVM ownership before
  opening and keep verification on the owning journal handle.
  [FileLock](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html).
- Linux explicitly separates file synchronization from synchronizing its containing
  directory entry. Other providers require their own capability evidence.
  [fsync](https://man7.org/linux/man-pages/man2/fsync.2.html).
- Lucene prepares a pending segment manifest, syncs it, then renames and syncs
  directory metadata when finishing a commit. Its directory abstraction separates
  data and metadata synchronization.
  [SegmentInfos implementation](https://github.com/apache/lucene/blob/releases/lucene/10.5.2/lucene/core/src/java/org/apache/lucene/index/SegmentInfos.java),
  [Directory](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/store/Directory.html).

The exact filenames, prefix binding, limits and fail-closed selection rule are
MeridianDB design choices. A successful directory probe and process-reopen test
are bounded evidence for the tested environment, not a universal power-loss
claim. This implementation adds no native or external runtime dependency.

## Alternatives and consequences

A mutable `CURRENT` pointer makes discovery small but adds a separately replaced
and synchronized publication object. Immutable generation names avoid that pointer
and retain diagnostic history, at the cost of bounded directory scanning and an
explicit future retention requirement. Selecting the highest valid older manifest
would hide damage to the newest selected state; strict failure makes the fault
observable instead.

Forcing only newly written files omits namespace persistence. A non-atomic rename
fallback can expose a partial publication protocol. Both weaken the stated
contract. Providers without directory force or atomic rename remain unsupported
for this adapter rather than receiving an implicit weaker guarantee.

A native platform-specific durability implementation may support more environments
or stronger device controls. It needs separate implementation and fault evidence.
The current JDK-only adapter deliberately reports its capability boundary.
[Read-only checkpoint-plus-tail recovery](0013-local-database-recovery.md) is now
implemented separately. [ADR 0014](0014-durable-commit-publication.md) adds the
engine acknowledgement protocol. Process crash conformance and orphan cleanup
remain subsequent work.
