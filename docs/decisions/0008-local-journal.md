---
title: Local journal I/O and recovery
description: Explain MeridianDB's local journal locking, append and force cutoffs, sequential recovery and explicit tail repair, including namespace durability limits.
---

# ADR 0008: Local journal I/O and recovery

- **Date:** October 28, 2026
- **Status:** implemented; experimental local storage API
- **Scope:** Local transaction journal, independent of the memory engine

## Context

The pure codec establishes exact bytes and failure classifications. A usable local
adapter additionally needs serialized writes, exclusive ownership, explicit force,
bounded recovery and a policy for incomplete candidates. Treating every readable
frame as acknowledged would lose the distinction between appended, forced and
query-visible state.

## Decision

Implement `LocalJournal` over one owning `FileChannel`, with no runtime dependency.
Use positional complete-write loops, contiguous sequences and unique request IDs.
Expose appended and successfully forced byte/sequence cutoffs separately. Validate
before append; an uncertain I/O failure stops further operations until close and
recovery. Close does not silently force or repair.

Reserve same-JVM ownership before opening another channel, using canonical parent
paths and existing-file identity checks for aliases. Then acquire an exclusive OS
lock. This ordering matters because closing another same-file channel can release
JVM locks on some systems. Locks are cooperative; use stable paths and a single
adapter copy. The protocol does not defend against external mutation or renamed
open files. [JDK FileLock](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html),
[SQLite locking hazards](https://www.sqlite.org/howtocorrupt.html#file_locking_problems).

Recover sequentially with validated bounded prefixes and one frame buffer. Retain
an exact request-ID set to reject duplicate history; this identity memory still
grows with retained history. Keep complete recovered transactions even when their
previous acknowledgement is unknown. Force counters describe only successful
calls in the current instance, not an inferred persisted acknowledgement record.

Reject incomplete tails by default. Explicit repair first writes, forces and
closes an exact suffix evidence file, then truncates and forces the retained
journal prefix. Never repair complete corruption, malformed history, unknown
formats or partial lineage headers. An incomplete candidate may be damage rather
than an unacknowledged torn write; preserve that uncertainty and the original bytes.

## Alternatives and limits

`APPEND` mode supplies no required transaction atomicity and its positional-write
behavior is unspecified. Explicit offsets under serialization are easier to test.
Group force is possible without changing logical identities; background batching,
rotation and scheduling need measured requirements before implementation.
[JDK FileChannel](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html).

Automatic truncation would discard evidence before a loss decision. Keeping the
entire journal untouched by default and requiring explicit suffix preservation
makes that boundary reviewable. Checksums detect accidental corruption; they do
not prove client acknowledgement or distinguish every damaged tail from torn I/O.

File force does not prove durable directory-entry creation. Linux documents a
separate directory sync requirement; Windows directory handles require platform
flags that ordinary channel creation does not provide. A portable directory-force
claim is therefore unsupported. New journal and evidence names require an external
platform-specific namespace publication protocol before a power-loss guarantee.
[Linux fsync](https://man7.org/linux/man-pages/man2/fsync.2.html),
[Windows directory handles](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew#directories),
[JDK Windows channel factory](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.base/windows/classes/sun/nio/fs/WindowsChannelFactory.java).

## Consequences

The [local journal guide](../development/local-journal.md) defines usage, cutoffs,
repair artifacts and tested assumptions. Short/failed-I/O injection and separate
JVM tests exercise the adapter; sudden power loss and remote storage remain outside
that evidence. The memory engine remains nonpersistent. Immutable segments,
[checkpoint publication](0012-local-checkpoint-publication.md) and
[read-only local recovery](0013-local-database-recovery.md) are implemented separately.
[Forced local writes](0014-durable-commit-publication.md) and
[durability conformance](../development/durability-conformance.md) now cover engine
acknowledgement/retry recovery and real process-exit boundaries.
