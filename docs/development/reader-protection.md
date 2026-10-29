---
title: Local checkpoint reader protection
description: Protect live lazy checkpoint readers across store close and cooperating processes with a shared file-lock barrier, explicit cleanup and conservative reclamation limits.
---

# Local checkpoint reader protection

A `LocalCheckpointReader` now holds shared protection independently of its
`LocalCheckpointStore`. Closing the store releases publisher ownership, but leaves
each returned reader able to lazily load its original files. New publications may
proceed while those readers remain open. No physical removal API is implemented.

This protection is deliberately coarse: **any live lazy reader prevents a future
cooperating reclaimer from obtaining exclusive protection for the entire store**.
It does not promise reclamation progress for generations unrelated to that reader.
All original history, the complete journal and old/failed files remain retained.

## Admission and lifetime

Use the existing API and close the returned reader explicitly:

```java
try (var store = LocalCheckpointStore.open(directory);
     var journal = LocalJournal.open(store.journalPath());
     var reader = store.readLatest(journal).orElseThrow()) {
    var metadata = reader.manifest();
    try (var segment = reader.openSegment(0)) {
        var immutableHistory = segment.segment();
    }
}
```

Imports are `org.meridiandb.storage.manifest.LocalCheckpointStore` and
`org.meridiandb.storage.journal.LocalJournal`; `directory` is a `java.nio.file.Path`.
A selected genesis has no segments, so open a segment only when its metadata
contains a reference. An empty selected namespace still returns `Optional.empty()`.

The store holds exclusive publisher ownership throughout complete selected-graph
and actual journal-prefix verification. It then acquires shared reader protection
before returning the reader. A future remover must acquire that same store
ownership before requesting an exclusive reader barrier, so it cannot interleave
with verification or admission. Empty or invalid selection returns no reader and
creates no reader barrier. Publication's internal graph checks create no reader.

The reader owns its protection after admission; store close neither releases it
nor invalidates its metadata. Multiple readers in one JVM share a single channel
and OS lock with separate reference counts. Another cooperating process requests
its own shared OS lock. Closing one reader leaves other references protected.

`openSegment` and `close` serialize on the reader. A lazy open completes its bounded
load before close can release protection. Its returned `LocalSegmentReader` owns
verified heap values with no file channel; it and previously returned metadata
survive parent-reader close. Closing the parent prevents new lazy opens.

## Permanent barrier and capabilities

| Entry or capability | Contract |
| --- | --- |
| `.meridiandb-readers.lock` | Permanent empty regular file in the trusted store directory; never delete, replace or unlink it |
| Shared lock | Nonblocking `FileChannel.tryLock(0, 1, true)`; actual shared support is required |
| JVM ownership | One reference registry and channel per canonical guard path/identity, from one loaded MeridianDB library copy |
| Exclusive barrier | Internal nonblocking foundation for future removal; live shared references or another process's conflict defer acquisition |
| Directory capacity | Store acquisition and object publication reserve an absent barrier's slot; first reader admission uses it and later references reuse the name |
| Name synchronization | Force the barrier through its sole channel and synchronize its directory name before admission succeeds |

JDKs may convert a shared request into an exclusive lock. That result raises
`LocalCheckpointStore.SharedReaderLockUnsupportedException` after cleanup instead
of silently changing reader concurrency. Ordinary I/O and permission errors remain
failures. [Local checkpoint directory/rename requirements](local-checkpoints.md#directory-and-ownership-requirements)
also apply. These are cooperating local-file protocols, without a remote-filesystem
or hostile namespace guarantee.

Before another channel opens, reject a guard that is a symlink, nonempty, nonregular
or aliases the journal/store lock. Store ownership acquisition, checkpoint metadata
and segment reads/forces, reuse checks and lazy segment opens also reject guard
aliases, including control files owned in other stores in the same JVM. Shared
cross-adapter reservations coordinate control ownership with transient metadata/segment
read, force and create-only channels before opening. Resolve real parent paths before interpreting
`..` beside a symlink. Hard-link checks matter: closing another handle to the same file can release
process locks on some platforms. Inspect identity/attributes without independently
reading the protected guard through another channel.

Keep directory paths and entries stable. All participating readers and any future
remover must use this protocol and one loaded adapter copy per JVM. An older binary
that returned an unprotected reader cannot be discovered by the barrier. Upgrading
code does not retroactively protect such readers; quiesce them before enabling any
future removal. The barrier supplies live-process protection, not offline backup or
retained-generation policy.

## Failure and close

`LocalCheckpointReader.close()` now declares `IOException`. It first invalidates
lazy access, then releases its protection. If release fails, call close again to
retry cleanup; the reader stays logically closed. A native-close uncertainty
requires restart as described below, rather than repeated no-op closes. The last reference attempts both
OS-lock release and channel close even when one fails, preserving suppressed errors.
A database close similarly attempts reader, journal and store cleanup independently.

A registry reservation remains until channel close completes successfully. Java can
mark a channel closed before native cleanup fails; `isOpen() == false` after a thrown
close is not a cleanup receipt. The ownership wrapper permanently quarantines that
uncertain close, and a later no-op close cannot clear it: restart the process. The
same rule applies to journal/store controls and transient data channels. New control
admission and exclusive acquisition fail closed. An invalid lock is not evidence
that files are unused. Failed admission
with uncertain cleanup can require a process restart because no reader was returned
to own a retry. Unexpected overlapping JVM locks retain their duplicate channel
rather than closing it and potentially dropping another owner's lock; restart all
participating copies before reuse.

Process exit eventually releases the OS lock. Tests use bounded retries for unlock
visibility rather than a PID observation or a fixed grace period. A process exit is
not device power loss, and lock release does not itself grant deletion permission.
Always close readers explicitly; there is no timer, garbage-collection release or
public pin identifier.

## Reclamation boundary

A future reclaimer must hold **exclusive store ownership and exclusive reader
protection** through root verification, metadata/dependency removal and directory
synchronization. Any live reader conservatively defers all removal. It must still
preserve selected/retained generations and explicit backup roots, retain the journal,
ownership/barrier files, unknown and diagnostic candidates, and fail closed on
uncertainty. None of those removal/root-selection operations is implemented here.
Long-lived readers may indefinitely delay cleanup and exhaust generation or entry
capacity; fine-grained per-generation protection would require another verified
protocol. See the [compaction boundary](history-compaction.md#physical-reclamation-boundaries)
and [ADR 0017](../decisions/0017-live-reader-protection.md).

## Verification

```bash
./mvnw -B -Dtest=LocalReaderBarrierTest,LocalCheckpointReaderProtectionTest,LocalReaderProtectionIntegrationTest,LocalFileOwnershipTest,LocalResourceCleanupTest test
```

The 70 reader protection/identity/cleanup invocations comprise 27 barrier protocol,
seven reader lifetime/concurrency, 18 shared identity/native-close, 14 real process/
filesystem and four database cleanup cases. Four further compaction cases verify
reserved permanent-guard capacity. Injected checks exercise shared reference lifetimes, exclusive deferral, invalid or
uncertain cleanup, retry, conversion to exclusive locks and lazy-open/close ordering.
Real-file/process checks verify shared admission, publication with retained readers,
release/process-exit visibility and pre-open alias rejection without losing existing
locks. The full command is `./mvnw -B verify`; capability-dependent results are kept
separate from injected checks. No test of this foundation claims safe physical
reclamation or device power-loss conformance.
