---
title: Local immutable segment I/O
description: Define create-only local history files, full expected-ID verification and heap-owned reader lifetimes separately from manifest and directory durability.
---

# ADR 0010: Local immutable segment I/O

- **Date:** October 28, 2026
- **Status:** implemented; experimental
- **Scope:** Local creation and reopening of one verified immutable history file

## Context

The immutable codec preserves exact transaction ranges but cannot establish file
ownership, successful I/O or reader lifetimes. A later checkpoint must refer only
to complete objects. Its publication protocol needs a clear distinction between a
written file, a verified object and a selected durable generation.

## Decision

Use `LocalSegmentFile.writeNew` for a caller-selected new path in an existing,
trusted, stable local directory. Preflight and encode before opening; use
`CREATE_NEW` and final-component no-follow behavior, complete short writes, check
size, force file data/metadata and close before returning the full-object ID.
Never overwrite or automatically delete a failed candidate. A failure can leave
partial or complete bytes, so successful creation is an explicit result rather
than an assumption made from a filename.

Use `LocalSegmentFile.open` with an independently expected ID. Reject non-regular
inputs before opening to avoid blocking on objects such as pipes. Bound size
before allocation; read completely, verify EOF/size, decode all bytes and bind the
expected ID. Close the channel before exposing any records, including when close
itself fails. Preserve the primary failure with a suppressed close exception.

The initial `LocalSegmentReader` retains an immutable heap snapshot. Its
synchronized accessors reject use after idempotent close; immutable values already
returned remain valid. No file handle, memory mapping or cleanup callback escapes.
Keep every referenced object and journal source until a future explicit retention
protocol permits reclamation. This adapter itself has no deletion operation.

This adapter supplies no manifest selection, directory sync, engine integration
or throughput claim. The separate [manifest representation](0011-checkpoint-manifest-format.md)
now defines complete-set consistency before local publication.
The existing 32 MiB byte limit does not bound decoded or aggregate heap memory.

## Primary evidence and design inference

Sources checked October 28, 2026:

- Java's `CREATE_NEW` atomically checks and creates a name, rejecting an existing
  file or symbolic link. This does not atomically write the entire image.
  [Files open options](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#newByteChannel(java.nio.file.Path,java.util.Set,java.nio.file.attribute.FileAttribute...)).
- FileChannel I/O can be partial or make zero progress; cross-process consistency
  depends on the system. `force(true)` supplies the local file boundary, not a
  portable parent-directory persistence protocol.
  [FileChannel](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html).
- Lucene distinguishes file synchronization from directory metadata sync and
  requires new output names to reject existing objects.
  [Directory](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/store/Directory.html).
- Quickwit separates immutable splits from atomic metadata/checkpoint publication.
  [Indexing](https://quickwit.io/docs/overview/concepts/indexing).

The heap snapshot, sixteen-call no-progress bound and diagnostic-candidate policy
are MeridianDB design choices. Full-object verification provides a simple baseline;
it does not freeze a mutable filesystem or establish a selected checkpoint.

## Alternatives and consequences

Memory mapping and lazy streaming can reduce copies for larger layouts but require
additional lifetime, mutation and resource contracts. They are unnecessary for the
current bounded scan baseline. Heap readers cost image allocation plus decoded
objects during open and retain decoded history while callers hold references.

Writing a temporary file and renaming it is a possible future publication mechanism.
Java's `ATOMIC_MOVE` ignores other move options and leaves replacement of an
existing target implementation-specific. It cannot alone promise atomic
no-overwrite behavior. [Files.move](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)).
Define the actual manifest namespace protocol and platform capability checks
separately rather than infer them from a completed data file.

`NOFOLLOW_LINKS` protects the final component at open; ancestor resolution remains
under the stable trusted-directory assumption. A stronger hostile-path contract
would require provider capabilities such as
[SecureDirectoryStream](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/SecureDirectoryStream.html).
There is no advisory lock, overwrite, auto-repair or reclamation here. The
[adapter guide](../development/local-segments.md) owns API usage, failures and
lifetime details; the [format specification](../architecture/immutable-segment-format.md)
continues to own the exact bytes.
