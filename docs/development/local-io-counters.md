---
title: Internal local I/O counters
description: Understand bounded caller-thread counters for completed local FileChannel reads, writes and force calls, with explicit failure, coverage and instrumentation limits.
---

# Internal local I/O counters

The local adapters have an inactive-by-default internal counter layer around their
actual `FileChannel` transfers and force calls. It records completed file work
without changing the existing append, ownership, verification or acknowledgement
protocols. [ADR 0045](../decisions/0045-local-io-counters.md) defines the boundary.

Capture activation is package-private and currently exercised by developer tests.
There is no public engine configuration switch or attributed workload report yet.
The existing [Linux observations](linux-observations.md) retain their original
source and metric definitions. Operation labels, named amplification denominators
and evaluated attributed results require separate workload integration.

## What is counted

| Counter | Meaning |
| --- | --- |
| Read attempts / returns / failures | Calls attempted, calls that returned a count and calls that threw |
| Read bytes | Sum of positive returned byte counts, including short completions and positive EOF probes |
| Read zero / EOF | Zero-progress returns and `-1` returns, separately |
| Write attempts / returns / failures | Positional or relative writes attempted, returned or throwing |
| Write bytes / zero | Positive returned counts and zero-progress returns, separately |
| Force attempts / successes / failures | Force calls attempted, successfully returned or throwing; no invented metadata bytes |

Each completed transfer is counted once at the concrete adapter's channel boundary.
Repeated prefix, integrity and recovery reads remain counted. Requested buffer
length, final file size, encoded length and channel position are not substitutes
for returned byte counts. Reads/writes that throw may already have effects; their
additional bytes are unknown. Earlier successfully returned short transfers remain
in the snapshot. A successful force remains recorded if later cleanup fails; it
does not make the whole operation successful or establish device durability.

Roles distinguish journals, segments, manifests, reader controls, directories,
tail-repair evidence and local immutable objects. Unknown roles are retained as
unknown and invalidate telemetry. Both standalone segment access and the checkpoint
store's independent segment path are covered. Pending manifest files have the
manifest role before rename. Directory acquisition probes use the same actual
force hook as later directory synchronization; arbitrary injected test callbacks
are not counted as native force calls.

## Scope and limits

One capture belongs to one caller thread. It can span operations on owners opened
earlier: the adapter retains its file role, while every transfer resolves the
currently active capture. Overlapping captures on one thread are rejected before
storage begins. Other threads do not inherit a capture. There are at most 64
active captures in a process, with eight fixed roles and fourteen scalar counters
per role; no event log, path list or retained callback grows with file work.

Closing a capture removes its owner-thread registry binding. Only its owner can close it
or take a snapshot, and snapshots are available after close. Repeated owner close
is harmless. Immutable snapshots use schema version one. Counter saturation sets
an overflow flag instead of throwing from a storage call; invalid native return
values likewise invalidate telemetry. Such a snapshot cannot support exact sums
or amplification. These bounds cover retained counters, not total process memory.

The inactive fast path reads an empty registry. During capture, a bounded array
lookup resolves the caller without initializing thread-local state, including on
uncaptured threads. Counter updates use primitive fields and fixed arrays, with no observer callback, logging, path
lookup or counter allocation. Capture setup, teardown and snapshot allocation
are outside storage calls. Instrumented runs still have overhead and must be kept
separate from the original timing baseline.

This is caller-thread adapter accounting, not a process-wide or system-call trace.
It excludes locks, opens, closes, metadata lookups, rename, delete and truncation
effects; force calls receive counts rather than estimated metadata byte totals.
Raw-file hashing, oracle work, temporary-directory setup, process telemetry and JSON
export performed outside these adapters are separate work. Cloud adapters and
injected non-native `FileAccess` seams are not observed by the native hooks.
Zero within a captured scope does not establish zero work outside its coverage.

## Verify the foundation

From the repository root with JDK 25 and the Maven Wrapper:

```bash
./mvnw -B '-Dtest=LocalIoTest,LocalIoIntegrationTest' test
```

Literal tests distinguish partial/zero/EOF results, successful versus uncertain
calls, unchanged primary exceptions, saturation and thread/scope lifetime. Native
checks cover actual create/commit/retry/query/reopen and checkpoint/segment/control
paths, retaining independent encoded-byte expectations. The complete
[Linux qualification inventory](linux-storage-qualification.md) remains the
regression gate; no file-work or production performance result follows from these
foundation tests alone.
