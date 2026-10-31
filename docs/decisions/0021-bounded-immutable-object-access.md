---
title: Bounded immutable-object access decision
description: Adopt a small JDK-only byte-object contract with complete identity and length checks, local creation and verified heap ranges while retaining explicit checkpoint and journal protocols.
---

# ADR 0021: Bounded immutable-object access

- **Date:** October 31, 2026
- **Status:** Byte interface, local adapter and segment consumer implemented; experimental
- **Scope:** Immutable encoded images, not commit authority or checkpoint selection

## Decision

Introduce `ImmutableObjectStore` with create-only writes, complete expected-object
reads and explicit identical-content reuse. Use flat portable keys and a complete
SHA-256 plus exact encoded length. Bound each image to 32 MiB before cloning or
allocation. Zero-length generic objects are valid; a format decoder applies its
own stricter validity rules.

Provide a JDK-only `LocalObjectStore`, with force and known successful native
close before reporting new creation. Reuse the cross-adapter local identity
reservation, stable-directory assumptions and conservative failed-candidate policy.
Return `VerifiedObject` heap snapshots only after complete verification and channel
cleanup. Exact range operations copy from that verified image; no partial remote
or file range proof is implied. The [object-access guide](../development/immutable-objects.md)
owns the API, limits, error/lifetime and conformance details.

Use `SegmentObjects` as a concrete consumer of this byte interface. Preserve the
existing segment codec and require its full decoding before exposing history.
Retain `LocalSegmentFile` and the current checkpoint/engine path unchanged. Tests
establish byte compatibility in both directions, while the new consumer provides
a bounded integration boundary for another adapter. Avoid refactoring the proven
force/rename and reader/reclamation protocols into a generic byte-store API.

Keep file/directory synchronization, checkpoint selection, transaction publication,
reader admission and deletion separate. Verified identical reuse does not prove
an earlier force or acknowledgement. Provider tokens are separate from content
identity, and another adapter must declare/test its capabilities and uncertainty.
[ADR 0020](0020-persistence-and-replication-boundaries.md) remains authoritative for
the wider local-first and optional-replication direction.

## Primary evidence and inference

Checked October 31, 2026:

- JDK `CREATE_NEW` makes the existence check and name creation atomic. It does
  not make the entire image write atomic.
  [StandardOpenOption](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/StandardOpenOption.html).
- FileChannel supports partial/zero-progress I/O and explicit local force; its
  consistency across other processes depends on the platform.
  [FileChannel](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html).
- Java locks are JVM-wide and cooperative; on some systems closing another
  channel to the same file can release the JVM's locks. Reuse the existing
  pre-open ownership reservations.
  [FileLock](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html).

The flat-key policy, byte budget, complete heap snapshot, sixteen-attempt stall
bound and create-versus-verified-reuse distinction are MeridianDB design choices.
They establish no universal power-loss or throughput claim.

## Alternatives and consequences

A physical range API would need per-block integrity or whole-image verification
before records could escape the current segment format. Start with complete
bounded reads and safe heap slices; revisit remote transfer efficiency with a
concrete consumer and format evidence. Streaming or mapped readers would need
different resource and reclamation lifetimes.

Migrating all local persistence through this interface now would combine byte
access with publication and ownership changes. Keep that integration bounded and
separately tested. No new runtime dependency, artifact, wire/file format or temporal
semantic change is required by this decision.
