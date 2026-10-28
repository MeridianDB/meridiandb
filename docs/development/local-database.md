---
title: Local database recovery
description: Reopen an existing MeridianDB journal and checkpoint as a read-only temporal database, preserving lineage, historical queries and request identity after complete verification.
---

# Local database recovery

`LocalDatabase.open(directory)` restores a read-only temporal database from an
existing local journal and, when present, the highest selected immutable
checkpoint. It preserves the stored database lineage, every historical commit
and request lookup. No database or read view becomes available until all storage
verification and replay succeeds.

The API is experimental. It exposes queries and request lookup; durable engine
writes and acknowledgement remain planned. The separate `InMemoryEngine` still
creates an empty, memory-only database with a fresh lineage. [ADR 0013](../decisions/0013-local-database-recovery.md)
explains their shared interval-state implementation and recovery boundary.

## Open and query existing history

The directory must contain `transactions.journal` created by the
[local journal](local-journal.md). A checkpoint must have been selected using the
[local checkpoint protocol](local-checkpoints.md). Without a selected manifest,
recovery uses the complete journal; a sequence-zero checkpoint is also valid.

```java
try (var database = LocalDatabase.open(directory)) {
    var recoveredHead = database.head();
    var checkpoint = database.checkpointPosition();
    var request = database.lookupRequest(new RequestId("request-1"));
    try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
        var query = new TemporalQuery.Get(new EntityId("salary"), 25);
        var result = view.query(query);
    }
}
```

Imports are `org.meridiandb.engine.LocalDatabase` and
`org.meridiandb.temporal.*`; `directory` is a `java.nio.file.Path`.
`checkpointPosition()` returns an optional temporal cutoff. It can precede the
recovered head because later journal transactions are replayed as well.
Use `new ReadCutoff.At(new CommitPosition(databaseId, sequence))` for an earlier
commit in that stored lineage. Foreign or unavailable cutoffs fail with
`EngineException(INVALID_CUTOFF)`. Point, clipped interval scans and exact sums
follow the existing [temporal contract](../architecture/temporal-contract.md).

## Recovery sequence

1. Acquire the checkpoint store and its designated journal owner. Reject incomplete
   journal tails without automatic repair. The journal validates the full stored
   history, including transactions after any checkpoint.
2. Discover the highest selected checkpoint and verify its full manifest, complete
   dependency graph and actual journal-prefix binding. A damaged newest generation
   or missing dependency fails closed, without falling back to an older checkpoint.
3. Build private engine state using the stored database ID. Replay checkpoint
   transactions exactly once through its declared cutoff, retaining original
   request payloads, sequences and historical snapshots.
4. Scan the complete owned journal and apply only transactions strictly after that
   cutoff. Require contiguous complete history ending at the observed journal head;
   reject reused request identities instead of treating them as retry calls.
5. Expose the database only after all checks finish. On failure, discard private
   state, close every acquired owner and preserve storage evidence. Cleanup errors
   remain attached to the primary failure.

Recovered state includes complete stored records whose earlier acknowledgement
status may be unknown. Successful reopen cannot prove that a prior writer forced
or acknowledged each recovered transaction. Durable commit publication needs its
own protocol; a checkpoint cutoff is also distinct from the recovered head.

## Ownership, permissions and close

Recovery retains both cooperating storage locks until database close. It requires
the same trusted, stable directory and provider capabilities as the checkpoint
store. Logical read-only access still opens the journal and lock file with write
permission to hold their exclusive locks. Another cooperating journal or checkpoint
owner cannot run concurrently. Avoid a separate handle to the owned journal;
verification and replay use its existing channel.

Store acquisition probes directory open and `force(true)` before creating a lock
file. This can perform metadata I/O. Recovery does not append or truncate the
journal, force journal/segment data, publish a checkpoint or remove objects.
An absent store lock file may be created. `DirectorySyncUnsupportedException`
reports the inherited capability boundary; unsupported providers receive no weaker
implicit recovery protocol.

Read views pin immutable recovered state and can be shared by readers. Closing one
view leaves others usable. Database close rejects later operations and invalidates
existing views before releasing storage resources. A query that already captured
open state may finish during concurrent close. Close is idempotent; retry it after
a cleanup failure to release remaining resources. Previously returned immutable
results and commit positions remain ordinary values, without permission to delete
storage files.

## Cost and verification

This is full-history reconstruction into the existing interval engine. It retains
snapshots and original requests for every commit, copying maps while rebuilding.
Storage validation and replay perform several sequential passes, including the
journal prefix. Segment reads are individually bounded, but total heap and replay
time grow with retained history. A checkpoint is not yet a fast materialized-state
snapshot, journal-truncation boundary or performance improvement.

Run the complete Java checks, then the example:

```bash
./mvnw -B verify
```

```bash
java --class-path target/classes examples/LocalRecoveryExample.java
```

The example prepares a two-transaction checkpoint and a later retraction with
storage primitives, releases those owners, then recovers historical and current
queries at head three. It removes only its successful temporary output. If directory
capability is unavailable, it reports that boundary; other errors fail the run and
retain diagnostic files.

Focused recovery checks:

```bash
./mvnw -B -Dtest=LocalDatabaseTest,LocalRecoveryFixtureTest test
```

The unchanged canonical histories execute through journal-only, genesis-checkpoint
and checkpoint-plus-tail reopen. Focused checks cover exact point/scan/sum results,
lineage, request positions, complete failure before exposure, source cleanup,
ownership and view lifetimes. Existing storage tests retain corruption, short I/O,
publication uncertainty and process-lock coverage. These are recovery correctness
checks; durable engine commit/crash conformance and power-loss behavior need their
own evidence.
