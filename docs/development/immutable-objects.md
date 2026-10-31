---
title: Bounded immutable-object access
description: Use MeridianDB's immutable byte-object interface and local adapter with exact identity, bounded complete reads, verified heap ranges and explicit reuse and lifetime guarantees.
---

# Bounded immutable-object access

`ImmutableObjectStore` separates immutable byte access from the journal and
checkpoint publisher. The JDK-only `LocalObjectStore` implements it in an existing
trusted directory. `SegmentObjects` is a concrete consumer of that interface: it
encodes or decodes the existing history-segment format without changing its bytes.
The interfaces remain experimental.

## Types and operations

The byte-access types live in `org.meridiandb.storage.object`:

| Type or operation | Contract |
| --- | --- |
| `ObjectKey` | One flat name of 1–128 lowercase ASCII letters, digits, dots, underscores or hyphens, starting and ending in a letter or digit; Windows device basenames are rejected |
| `ObjectId` | Lowercase hexadecimal SHA-256 of the entire encoded image, independent of filenames and provider ETags |
| `ObjectReference` | Independent expected content ID and exact length, between zero and 32 MiB |
| `createNew(key, image)` | Create only; never replace an existing path or repair a candidate |
| `read(key, expected)` | Return a `VerifiedObject` only after complete length/hash verification and native-resource cleanup |
| `createOrVerify(key, image)` | Create, or fully verify identical existing content; report whether creation occurred |
| `readRange(offset, length)` | Return an independent exact range from a `VerifiedObject` heap snapshot |

Keys are names within a separately configured namespace, not paths or URLs.
Namespace directory selection belongs to the application. There is no mutable
head, rename, journal append, listing or delete operation in this interface.

Input arrays are copied before local creation. Callers must not mutate an input
concurrently with that initial copy. Returned byte arrays are independent copies;
changing them cannot change a reader or stored object. `ObjectReference.forBytes`
computes metadata from its input but does not retain the array.

## Creation, conflicts and uncertainty

Construct `LocalObjectStore` with an existing stable local directory. The adapter
resolves its real path, uses atomic create-only/no-follow file opening, writes all
bytes, verifies the resulting length, forces file data/metadata and closes before
returning successful creation. Bounds and input validation precede path creation.
Short writes are completed; sixteen consecutive zero-progress results fail.

An existing regular-file name raises `ObjectAlreadyExistsException` on the tested
local providers. `createOrVerify` catches only that conflict and reads the complete
expected image. A different length or
hash raises `ObjectIntegrityException` with `LENGTH_MISMATCH` or `ID_MISMATCH`.
Missing local objects use `NoSuchFileException`; permission, ordinary I/O and
non-regular-input failures remain failures rather than absence or a successful
reuse. Creating over a directory may report a name conflict or access denied,
depending on the provider; neither permits reuse or replacement of that directory.
Unsupported filesystem operations are not silently emulated.

After a failed creation, partial or complete candidate bytes remain. A force or
close failure can follow completed writes, so an exception is not proof of an
absent file. A later explicit identical reuse proves verified bytes only: it
does not prove an earlier force, acknowledgement or published checkpoint. A
checkpoint publisher must still establish its own durability barriers.

## Complete reads and ranges

The reader checks exact expected length before allocating. It completes bounded
reads, probes EOF, checks length again, verifies the full SHA-256 and closes the
native channel before returning a snapshot. Failed close exposes no reader;
cleanup failures are suppressed onto an existing primary failure without replacing
it. Shared local ownership reservations are released only after known successful
native close; uncertain cleanup retains the existing process-restart requirement.

`VerifiedObject` holds an immutable private heap copy. `reference()`, `bytes()`
and `readRange()` serialize with close and reject access after close. Close is
idempotent and releases the reader's copy; arrays or immutable decoded history
already returned remain usable independently.

A range is `[offset, offset + length)`. Negative offsets/lengths, overflow-sized
offsets and ranges beyond EOF fail before allocation. An empty range at EOF is
valid. These are **heap ranges after complete verification**, not physical
filesystem or HTTP range reads. An adapter must read and verify a whole bounded
object before exposing these slices. The 32 MiB encoded limit does not bound aggregate
heap, caller-retained copies or decoded transaction memory.

## History-segment consumer

`SegmentObjects.createOrVerify(store, key, segment)` encodes the complete existing
segment format and delegates immutable creation/reuse. `SegmentObjects.read`
accepts an independently expected `SegmentId` and encoded length, verifies the
byte object, then performs complete segment decoding before returning immutable
history. A correct content hash for malformed bytes is not a valid segment.

The original `LocalSegmentFile` and checkpoint/engine APIs remain unchanged.
Tests check byte compatibility in both directions between that path and the new
consumer. The checkpoint publisher has not been migrated to the new interface;
this keeps its ownership, force/rename, reader protection and reclamation protocol
explicit while another backend is developed.

## Ownership and publication boundary

Use trusted stable directory components and cooperate with the existing local
ownership and reader-protection protocols. The adapter rejects final symlinks and
non-regular read inputs before opening, and uses the shared identity reservations
to reject active control/journal aliases before another same-file channel opens.
This protects participating adapters within one loaded implementation per JVM;
it is not cross-host writer admission or protection against uncooperative mutation.

File force is separate from durable directory entries. This adapter establishes
no selected checkpoint, transaction acknowledgement, lazy-reader admission,
remote durability or deletion authority. A heap snapshot needs no live file;
future lazy consumers must acquire the applicable protection before access.
[ADR 0021](../decisions/0021-bounded-immutable-object-access.md) records the design
and refreshed JDK evidence. The [persistence boundaries](../architecture/persistence-boundaries.md)
keep optional provider integration and replication separate.

## Example and verification

After Maven verification, run:

```bash
java --class-path target/classes examples/ObjectStoreExample.java
```

The example stores two original transactions, verifies identical reuse, reopens
the exact history and retains header bytes after reader close. It removes only
its own successful temporary output. Supply an existing directory as one argument
to retain output; failed candidates remain available for inspection.

The reusable byte-store conformance suite runs against actual local files, with
separate type/range/lifetime, injected I/O/cleanup and segment-consumer checks.
The full Java suite retains independent temporal/reference, recovery, process
crash and compaction/reclamation verification. No object-service SDK, remote
checkpoint or performance result is introduced.
