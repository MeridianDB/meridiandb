---
title: Local transaction journal
description: Create, append, force and replay MeridianDB's experimental local journal, with exclusive writer access, bounded recovery and explicit evidence-preserving tail repair.
---

# Local transaction journal

`LocalJournal` in `org.meridiandb.storage.journal` stores the
[versioned transaction format](../architecture/journal-format.md) in a regular local
file. It preserves lineage, ordered transactions and exact request identity across
reopen. It is a storage primitive; `InMemoryEngine` still loses its state on close
or crash. There is no durable engine API or compatibility promise yet.

## Create, append and force

Use an existing local directory. `create(path, databaseId)` atomically creates a
new path, writes the complete header and forces the file. It never overwrites an
existing file. Initialization failure retains the new file for inspection; a
partial header is not eligible for tail repair. `open(path)` opens an existing
valid journal and acquires the same exclusive writer lock before scanning it.

```java
try (var journal = LocalJournal.create(path, new DatabaseId("example"))) {
    var transaction = new Transaction(new RequestId("request-1"), List.of(
            new WriteOperation.Put(new EntityId("salary"),
                    new ValidTimeInterval(10L, 20L), 100L)));
    journal.append(new JournalRecord(1, transaction));
    // Appended sequence is 1; forced sequence is still 0.
    long forcedSequence = journal.force();
}

try (var journal = LocalJournal.open(path)) {
    journal.replay(record -> consume(record));
}
```

Imports are `java.util.List`, `org.meridiandb.temporal.*` and
`org.meridiandb.storage.journal.*`. `path` is a `java.nio.file.Path`; `consume` is
application replay logic. The runnable example includes complete setup:

```bash
./mvnw -B verify
```

```bash
java --class-path target/classes examples/LocalJournalExample.java
```

Without an argument it uses and removes a temporary file. An optional path creates
a retained journal in its existing parent directory; that path must not exist.

Appends require the next contiguous sequence and a request ID not already in the
journal. Validation and format limits are checked before writing, without reserving
an ID. Identical request retries belong in the engine's request-resolution layer;
they must not append another frame. Logical no-ops with a new ID remain records.

## Visibility and force cutoffs

`appendedSequence()` and `appendedBytes()` describe the complete validated prefix,
including recovered records. `force()` calls `FileChannel.force(true)` and, only after success,
advances `forcedSequence()` and `forcedBytes()` to cover that prefix. A single force
can cover several complete transactions. Append and close do not implicitly force.
These counters are storage observations, not query visibility or client acknowledgement.

A clean reopen retains all complete records, including a possibly unacknowledged
suffix. It starts the force counters at zero: surviving bytes do not reveal which
previous force calls succeeded. An explicit new force establishes the current
instance's cutoff. Later engine integration must resolve uncertain outcomes by
original request identity before acknowledging or retrying transactions.

Short reads/writes loop to completion. Sixteen consecutive zero-progress operations
fail instead of spinning indefinitely. An I/O error, replay corruption or unexpected
file size makes the instance unusable for append, force and replay. Close it and
inspect/recover the file; counters retain the last complete/successful positions,
but bytes after them can be uncertain. Close releases resources without truncation;
a release error can be retried by calling close again.

## Sequential replay and repair

Recovery validates the fixed header and frame prefixes before allocating their
bounded bodies. It reads at most one frame at a time, not the whole file. Frame
buffers are bounded by the 8 MiB payload limit; the exact request-ID set grows with
the number and length of retained identities. There is no journal rotation or
history reclamation yet.

`replay(consumer)` delivers original records in order while holding the instance's
serialized access. Consumers must not call append, force, replay or close on that
journal. A consumer exception stops replay; previously delivered records are not
rolled back. Publish derived state only after the required replay completes.

Default open rejects an incomplete candidate tail with `IncompleteTailException`,
including its validated prefix and original byte size. It changes no journal bytes.
After inspection, an explicit repair can be selected:

```java
try (var journal = LocalJournal.open(path,
        LocalJournal.TailPolicy.PRESERVE_AND_TRUNCATE)) {
    var repair = journal.repair();
}
```

Repair copies the exact candidate suffix into a new sibling file named
`<journal>.tail-at-<offset>-<uuid>.bin`, forces and closes that evidence file, then
truncates the journal to its validated prefix and forces it. `repair()` reports
the evidence path, original size and suffix offset. Evidence is retained on later
truncate/force failure. A failed evidence copy leaves the original untouched and
may leave a partial evidence file for inspection.

Only an incomplete candidate is eligible. It can also be arbitrary short garbage;
repair is a deliberate loss decision, not proof that no acknowledged transaction
was damaged. Complete corruption, unsupported versions, invalid history and partial
headers fail closed without repair or resynchronization. Failed repaired open
returns no usable journal. Successful repaired open reports the newly forced
retained prefix.

## Verify an owned journal prefix

`prefix(throughSequence)` reads and validates the exact header and transaction
frames through an already appended sequence, returning immutable `Prefix` metadata:
sequence, byte length and lowercase SHA-256. Sequence zero binds only the lineage
header. Earlier cutoffs stop before later frames. It hashes stored bytes through
the owner's channel, without re-encoding records, opening a second handle or
forcing data. Appended/forced counters remain unchanged. Invalid cutoffs are
rejected before I/O; I/O, corruption and observed size changes poison the journal.
Validation keeps a request-ID set for the prefix and bounded per-frame buffers.

`path()` exposes the canonical real-file path for cooperating adapters; injected
storage has no path. The [local checkpoint store](local-checkpoints.md) requires
this owner at its designated journal path and compares the actual prefix with
complete immutable history before publication. Prefix metadata alone is not a
force or publication receipt.

## Local filesystem assumptions

Use a regular file with working local locks and force semantics. A canonical-path
JVM reservation, including existing hard-link aliases, precedes channel creation.
One channel then holds the exclusive OS lock. This avoids the
[JDK's same-file channel-close hazard](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html).
Final-component symbolic links are rejected. Keep the path and its parent stable;
external writes, unlink/rename, uncooperative handles and independent class-loader
copies of the adapter are unsupported. Network filesystems and object stores have
no durability or exclusion guarantee from this implementation.

File force covers content and the file's metadata under the
[local-device JDK contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html).
It does not establish durable creation of the journal or evidence file's directory
entry. The containing namespace must already be durably established through a
platform-specific protocol before relying on it after power loss. There is no
portable parent-directory sync implementation here, so create/repair alone does
not promise power-loss-safe namespace publication. The separate
[checkpoint store](local-checkpoints.md) adds ordered dependency and generation
directory forces where supported; its containing directory must already be
durably named. Tail-repair evidence has its own creation boundary.

Tests cover short/failed I/O, force cutoffs, repair ordering, corruption and actual
separate-JVM lock exclusion and abrupt process exit after force. Local macOS APFS
checks and Linux/Windows CI provide platform evidence; process termination tests
do not simulate storage-controller caches, lost directory entries or sudden power
loss. See [ADR 0008](../decisions/0008-local-journal.md) for the design rationale.
