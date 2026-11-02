---
title: Permanent local S3 coordination guard
description: Internal shared and exclusive native leases on an existing permanent control file, with bounded ownership, identity checks and uncertain-close quarantine.
---

# Permanent local S3 coordination guard

**Status: internal native guard implemented; durable enrollment, ledger and SDK
participation remain unimplemented.** The package-private guard in the optional
`meridiandb-s3` artifact supplies shared/exclusive protection for one existing
permanent control file. It complements the
[pure coordination state format](../architecture/s3-coordination-state.md).
[ADR 0038](../decisions/0038-permanent-s3-coordination-guard.md) records this boundary.

Current S3 readers and publishers do not acquire these leases. This primitive
does not enroll a namespace, install state, establish complete root coverage,
settle a remote attempt or permit reclamation. It performs no network operation.
The [accepted remote protocol](../architecture/remote-reclamation.md) still requires
concrete durable coordination and participating SDK paths.

## Existing permanent identity

The guard requires an existing empty regular nonsymlink file named
`.meridiandb-s3-coordination.lock`. It never creates, removes or replaces that name.
The future ledger must own fresh initialization, exact configuration/placement
binding, file and directory synchronization, and refusal of missing initialized
state. Acquiring an existing guard proves none of those facts.

Supported placements use the default filesystem, an available nonnull file key,
native shared locks and a trusted stable directory on one host. Resolve the real
parent before handling the final name. Capture the guard's file key and recheck
regular/empty/nonsymlink identity before admission, after native locking, during
lease validation and before/after force. Missing, changed or replaced names
invalidate the entry. Returning the original name after an observed failure does
not restore a failed lease.

File keys and resolved paths are observations. They do not freeze directory names,
detect copied control placements or fence another host. File-key reuse after
deletion is unspecified, and native locks can be advisory. Stable administrative
ownership and cooperation remain required. Custom providers, arbitrary network
filesystems and hostile path replacement are outside this primitive's support.
The JDK documents these limits for [file keys](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/BasicFileAttributes.html#fileKey()),
[real paths](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Path.html#toRealPath(java.nio.file.LinkOption...))
and [native locks](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html).

## Share one channel per JVM identity

A bounded synchronized registry owns at most 256 simultaneous placements and
1,024 shared leases per placement. Capacity is reserved before opening another
channel or exposing another lease. Logical readers at the same canonical path
share one actual shared lock/channel. Final release alone closes it.

| Existing protection | New shared request | New exclusive request |
| --- | --- | --- |
| Shared leases | Reuse the same channel, within capacity | Defer |
| Exclusive lease | Defer | Defer |
| No local lease | Try an actual native shared lock | Try an actual native exclusive lock |

An exclusive request also defers while any shared lease exists. A known local
conflict opens no second channel. Native `tryLock(0, 1, shared)` supplies
cross-process exclusion, with a null result treated as contention only after the
unused channel closes successfully. Actual shared mode is checked: silent
conversion to an exclusive lock is explicitly unsupported. Other I/O failures
remain failures. These choices follow the JDK's
[nonblocking acquisition contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html#tryLock(long,long,boolean)).

Different registered paths with the same file-key identity are rejected before
another native open. The shared `LocalFileOwnership` reservation additionally
protects against aliases owned by other local persistence adapters. Use one loaded
adapter copy per JVM and the same canonical placement across cooperating processes.

The lease exposes only internal validation, force and close operations; it returns
no raw channel, lock or delegate. Force uses the sole owning channel under the
registry monitor and serializes with close. A force or native-health failure
invalidates every lease on that entry while retaining protection until cleanup.
Reentrant operations during force or cleanup refuse before native effects.

## Preserve cleanup evidence

Closing a lease invalidates its access before cleanup. Shared leases release their
logical reference; only the last one attempts native release and channel close.
Both actions are attempted independently, preserving the first failure and
suppressed cleanup failures.

| Cleanup evidence | Subsequent behavior |
| --- | --- |
| Native close returns successfully and the channel is closed | Remove the registry entry; a release exception is still reported |
| Close throws, including after reporting `isOpen() == false` | Keep permanent in-process quarantine; retry cannot clear it |
| Close returns with the channel still open | Quarantine and refuse new leases |
| Unexpected unregistered overlapping lock | Retain the duplicate channel and common reservation; require process restart |

A no-op second close is not positive native cleanup evidence. Access, force and
new admission remain refused after uncertain close. Process restart releases
process-local native resources; it supplies **no remote-attempt completion or
durable-state recovery proof**.

On some systems, closing another same-file channel can release all JVM locks on
that file. The sole-channel/common-reservation rules and overlap quarantine avoid
using duplicate opens as ordinary contention probes. The JDK also notes that
[`isSameFile`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#isSameFile(java.nio.file.Path,java.nio.file.Path))
may access or open files and may accept equal paths without checking existence.
The guard's own registered-alias comparison uses captured file-key equality,
without opening a second native channel to inspect the guarded inode. Existing
common reservations retain their default-provider assumptions. An opener that
fails before returning a handle remains responsible for its own cleanup; the
registry cannot certify an unreturned resource.

## Verification and remaining integration

The optional build adds 58 guard invocations: 43 registry/fault/capacity/lifetime
checks and 15 native/process checks. Independent injected handles exercise
I/O/runtime/error failures, native-health uncertainty, wrong lock modes,
pre-open rejection, exact capacities, changed names, serialized/reentrant force
and invalidation before cleanup. Real child JVMs exercise all shared/exclusive
combinations, last-reader protection, rejected cross-adapter aliases, unknown
overlap quarantine and graceful/abrupt process exit. Logs remain outside guarded
fixture directories and child lifetimes are bounded.

These tests use existing empty fixture files. They establish native exclusion
without requiring directory-sync support and do not prove initial durable naming.
Only an explicit missing shared-lock/file-key or link capability may skip the
applicable native check; ordinary permission and I/O failures fail it. Follow the
[optional build commands](s3-objects.md#build-and-verify).

The November 1 Windows CI placement returned null file keys. The native guard
therefore refuses that placement, and applicable native checks report the missing
identity capability. The 43 registry/fault checks use controlled identities and
handles so they still execute independently of native file-key availability.
This preserves the production identity requirement rather than substituting
path spelling or silently weakening checks on that platform.

The next integration boundary must compose this guard with explicit fresh/existing
state, exact independent binding, immutable forced intent/completion generations,
complete receipt history and fail-closed reopen. Shared activity admission does
not serialize ledger mutations: the concrete ledger must separately serialize
generation writers across processes, without an implicit shared-to-exclusive
upgrade. Acquire namespace protection before ledger/publication metadata, local
checkpoint/journal owners and SDK monitors. Participating SDK sessions must
then register all roots and establish each original terminal response. Inventory
and ordered deletion still require their own implementation and conformance.
Neither native exclusion nor valid state bytes alone provides those capabilities.

Primary JDK 25 sources were checked November 1, 2026 at 23:01:48 EST
(November 2, 04:01:48 UTC). The implementation's ownership and quarantine rules
are project deductions from those contracts, tested on the reported platforms;
they are not universal filesystem or device-power-loss guarantees.
