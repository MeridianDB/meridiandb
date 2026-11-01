---
title: "ADR 0027: Durable local S3 publication state"
description: Install exact publication metadata with exclusive local ownership, immutable synchronized generations, explicit create/open and fail-closed recovery.
---

# ADR 0027: Durable local S3 publication state

Date: October 31, 2026. Status: implemented, experimental local primitive;
[ADR 0029](0029-verified-s3-checkpoint-publication.md) adds complete checkpoint
publication/reconciliation. [Managed S3 read recovery](0032-managed-s3-read-recovery.md) is now available separately; managed S3 writable recovery is implemented separately;
[ADR 0031](0031-pinned-checkpoint-engine-recovery.md) adds core pinned read-only recovery.

## Context

Exact pending bytes and a provider precondition must survive process exit before
a conditional request is attempted. The pure state codec supplies no durable
installation or owner admission. A visible file after a failed call also does not
prove that its name was synchronized. Missing state must not recreate genesis or
erase unresolved remote intent.

## Decision

Store S3-specific metadata in a dedicated trusted, stable, durably named local
directory through the optional artifact. Reuse shared cross-adapter file identity
reservations and one permanent exclusive lock; reserve before another native
same-file open. Preserve uncertainty on cleanup and retain quarantined reservations
until process restart. Keep core and ordinary local embedding unchanged.

Separate fresh creation from existing-state open. Creation requires an empty
directory and independently established fresh remote namespace. Open requires
the existing ownership file and highest unique selected generation, validates
its complete identity/generation/configuration, then synchronizes it before use.
Neither a missing head nor missing local files authorize initialization.

Install immutable generations: bound discovery and bytes, validate the current
selected image, reserve capacity, create/write/force/close a unique candidate,
atomically rename it and force the directory before publishing the new local
value. Probe directory force before mutation; require atomic move without fallback.
Default Windows filesystem placement is explicitly unsupported by this adapter;
ordinary directory I/O failures elsewhere remain failures rather than capability skips.
Retain old generations and failed candidates. Reject damaged newest state without
fallback. Poison storage failures and mark uncertainty before attempting rename.

Expose only the existing pure prepare/exact-candidate-recognition transitions.
Return from prepare only after its exact image has been synchronized locally.
This does not verify a graph, observe a remote head, perform a conditional request,
acknowledge a transaction or clear pending intent on error. The complete publisher
must still combine those independent facts. Local locks and generations provide no
distributed writer admission or protection against external rollback/copied placement.

The [local state guide](../development/local-s3-publication-state.md) owns admission,
filenames, bounds, API ordering and failure outcomes. The
[state format](../development/s3-publication-state.md) remains unchanged.

## Evidence and alternatives

The design follows the separation of data and name synchronization used by the
[local checkpoint protocol](0012-local-checkpoint-publication.md). JDK and Lucene
contracts are primary evidence for these building blocks, not guarantees of this
project's entire protocol. Primary sources were rechecked October 31, 2026 at
23:33:55 UTC:

- [FileChannel.force](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean))
  and [FileLock](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html)
  describe local-storage force conditions and platform-dependent lock behavior.
- [Files.move](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...))
  defines atomic-move support and implementation-specific target replacement.
  Exclusive cooperating ownership and stable names supply the destination precondition.
- Lucene's [SegmentInfos](https://github.com/apache/lucene/blob/releases/lucene/10.5.2/lucene/core/src/java/org/apache/lucene/index/SegmentInfos.java)
  and [Directory](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/store/Directory.html)
  separate pending commit data, rename and metadata synchronization.
  This is an ordering analogy: its rename interface permits temporary dual names
  and its failure cleanup removes files, whereas this protocol requires atomic
  move and preserves failed evidence.

Fault tests inspect exact retained images and poisoning. Child-JVM exits inspect
reopen across installation boundaries and competing ownership. They do not emulate
power loss or qualify another filesystem/provider. The bounded highest-generation
scheme is a project choice with explicit no-external-rollback assumptions.

A mutable pointer would need its own replacement/force protocol. Reusing a missing
directory as a fresh state risks abandoning unresolved intent. Automatic cleanup
would need a separate evidence/retention design. Copying a directory to another
publisher cannot be made safe by its local file lock. Keep these limits explicit
while implementing complete verified publication separately.
