---
title: Durable local S3 publication state
description: Store exact S3 expected and pending metadata under exclusive local ownership, with synchronized immutable generations and fail-closed reopen.
---

# Durable local S3 publication state

`LocalS3PublicationState` stores the [bounded S3 state image](s3-publication-state.md)
in a dedicated local directory. It installs each immutable generation through a
complete write, file force, successful close, atomic rename and directory force.
It performs no network operation and owns no credentials or clients. The class
lives in `org.meridiandb.storage.s3` in the optional `meridiandb-s3` artifact;
ordinary local embedding and the core artifact are unchanged.

This experimental primitive preserves the exact pending candidate and original
conditional precondition across process exit. It supplies the local storage part
of the [publication protocol](../architecture/object-checkpoint-protocol.md).
The separate [verified publisher](s3-checkpoint-publication.md) now composes
graph/owned-journal checks, conditional submission and remote reconciliation.
Remote engine recovery remains planned. Local metadata installation alone establishes
neither a remote selection nor a transaction acknowledgement.
[Verified preparation](s3-publication-preparation.md) now adds actual client scope,
complete graph/owned-journal checks and immutable intent before this local transition.

## Placement and admission

Use one existing, trusted, stable directory whose name its creator has already
durably established. The directory is dedicated to this metadata, separate from
the database journal/checkpoint directory. A final directory symlink is rejected;
parent aliases resolve through the filesystem. No-follow file opens and local
locks require cooperating writers and stable names. They do not protect against
external path replacement, backup rollback, copied directories or a second
publisher placement for the same remote namespace.

`create(directory, target, binding)` requires a completely empty directory and
independently established fresh remote namespace. It creates the ownership file
with `CREATE_NEW`, synchronizes it and the directory, then installs generation 1.
The code does not inspect the remote namespace. An existing lock, unknown file,
failed candidate or selected image prevents fresh creation. A failed initialization
retains evidence; repeating `create` never silently resets it.

`open(directory, target, binding)` requires the existing regular ownership file
and at least one selected generation. It validates the highest unique generation,
its complete identity and embedded generation, then checks independently managed
target/bindings. Finally it forces the ownership file, selected file and directory
before returning. This resynchronization matters when an earlier rename became
visible but its final directory force failed. Reopen establishes current local
storage, not proof that the earlier call returned success.

Opening first probes directory open/`force(true)` before creating a file. A provider
that cannot supply the capability raises `DirectorySyncUnsupportedException`.
The default Windows filesystem is explicitly outside this adapter's supported
placement policy; it is rejected before directory-channel open or file creation.
Other ordinary directory open/force I/O failures propagate as I/O failures,
without being converted into unsupported-capability results.
Atomic move is separately required during installation, with no weaker fallback.
Provider/device force assumptions still apply; process-exit tests do not simulate
power loss or qualify network filesystems.

One `.meridiandb-s3-publication.lock` excludes cooperating owners until close or
process exit. Shared same-JVM identity reservations precede native opens, including
hard-link aliases of journal, checkpoint, reader and other adapter controls.
Selected-file read/force handles use the same reservation mechanism. The lock is
forced through its existing channel, never a second same-file handle that might
release process locks. An uncertain native close of a reserved control/data channel
retains its reservation and
requires process restart; a no-op close retry is not cleanup evidence.

## Files and bounded selection

| Entry | Meaning |
| --- | --- |
| `.meridiandb-s3-publication.lock` | Permanent cooperating local ownership |
| `pending-publication-<generation>-<UUID>.state` | Unselected candidate, retained after failure |
| `publication-<19-digit-generation>-<64-hex-full-id>.state` | Selected immutable local state |

Select the highest positive generation. Malformed selected names, duplicate
generation numbers, an invalid newest image, wrong configured target/binding or
missing selected state fail closed. Reopen never selects a pending candidate or
falls back to an older valid state. A content ID covers the complete image,
including its integrity footer. No remote head is read during this procedure.

Discovery permits at most 16,384 total entries and 1,024 selected generations.
An image read checks its size against the 4,096-byte codec cap before allocation,
completes bounded short reads and checks EOF/unchanged size. Installation reserves
one new entry and generation before candidate effects. Old generations, unknown
entries and failed candidates remain; there is no automatic deletion or supported
retention/migration procedure yet. The limits are prototype capacity bounds,
not throughput or disk-space measurements.

The highest-name rule cannot detect removal of all evidence of a newer generation
or external rollback to an older directory copy. Preserve the no-external-rollback,
single-placement assumption and independently managed configuration. Local generation
is not a distributed fence, remote head revision or temporal cutoff.

## Install exact transitions

For independently prepared `target`, `binding` and complete `candidateImage`:

```java
try (var local = LocalS3PublicationState.create(directory, target, binding)) {
    var pending = local.prepare(candidateImage);
    var exactBytes = pending.pending().orElseThrow().image();
    var originalPrecondition = pending.pending().orElseThrow().precondition();
    // A complete publisher must verify the graph and use these exact request values.
}
```

Use `open` on later process starts. `state()` returns immutable selected metadata.
`prepare(candidateImage)` applies the pure transition checks, then synchronizes
the exact pending image before returning. `recordSelected(observation)` likewise
installs only pure recognition of the exact pending candidate. The complete
publisher must independently establish same-response observation provenance,
complete graph/actual-journal agreement and remote selection before using that
operation as reconciliation. The local API does not supply those facts.

Each installation revalidates the current selected image against the owner's
state, checks capacity, writes a unique create-only candidate, forces/closes it,
attempts atomic rename, forces the directory, then exposes the new immutable state.
The owner serializes transitions and close; reentrant operations are rejected.
Invalid pure transitions fail before I/O and leave a healthy owner usable.

A missing/old remote head or error has no local reset/adopt/clear/rebase path.
An unresolved pending candidate prevents another `prepare`; replacement retains
the original quoted ETag and creation retains literal unquoted `*`.

## Failures and recovery limits

Storage failures poison the owner; close it and reopen before another operation.
After atomic rename is attempted, I/O or runtime failure raises
`UncertainInstallationException`, because the new selected name may already be
visible. Errors also poison before propagation. Reopen validates and resynchronizes
selected state; it neither repairs damage nor deletes evidence. Close invalidates
the owner before cleanup and installs nothing. Previously returned immutable
metadata remains a value, not a new durable-operation receipt.

Crash before the first selected generation leaves an incomplete initialization
that both normal open and fresh creation reject. Recovery requires inspecting
that evidence and the independent remote namespace state; this primitive provides
no automatic repair. Crash before a later rename retains the old selected state
plus the unselected candidate. Crash after rename exposes the exact new state to
validated reopen. These local outcomes must remain separate from any remote
request that a future complete publisher may have attempted.

The 86 local cases comprise 34 filesystem/API, 31 fault and 21 process invocations.
Real process tests cover initialization, pending creation/replacement and exact
observation installation at candidate-force, rename and directory-force boundaries.
Additional tests cover competing processes, stale/damaged state, exact bytes/token,
short I/O, before/after-effect failures, capacity, aliases and cleanup. Run both
builds in the [S3 build guide](s3-objects.md#build-and-verify).
[ADR 0027](../decisions/0027-durable-local-s3-publication-state.md) records the
decision and primary evidence.
