---
title: Local database
description: Create and reopen MeridianDB's local temporal database, force writes before atomic query publication, preserve historical views and resolve uncertain commit outcomes explicitly.
---

# Local database

`LocalDatabase` reconstructs temporal queries from existing local history and
provides an explicit writable mode. New writes prepare their complete state,
append and force an exact journal record, then publish values, historical cutoffs
and request identity together before returning success. The API and local protocol
are experimental, with [process crash/retry and temporal conformance](durability-conformance.md)
through real writable reopen.

[ADR 0013](../decisions/0013-local-database-recovery.md) explains read reconstruction;
[ADR 0014](../decisions/0014-durable-commit-publication.md) defines creation, writable
startup, acknowledgement and uncertainty. The separate `InMemoryEngine` retains
its memory-only write contract.

## Choose an open mode

| Entry point | Behavior |
| --- | --- |
| `create(directory, databaseId)` | Initialize a fresh existing directory, select genesis generation one and return a writable database |
| `open(directory)` | Recover existing history for queries and request lookup; reject transactions |
| `openWritable(directory)` | Recover existing history, synchronize journal and namespace, then enable transactions |

The directory must be trusted, stable and already durably named by its creator.
Creation allows only an existing checkpoint-store lock file; it refuses a journal,
manifest, pending file or unrelated content rather than overwriting it. Initial
lineage/format validation precedes store mutation. Creation failures retain files
for inspection; inspect or reopen valid existing history instead of retrying
creation blindly. Incomplete journals require the separately documented explicit
repair policy; these APIs never repair automatically.

## Create and write

```java
try (var database = LocalDatabase.create(directory, new DatabaseId("example"))) {
    var request = new Transaction(new RequestId("request-1"), List.of(
        new WriteOperation.Put(new EntityId("salary"),
            new ValidTimeInterval(10L, null), 100)));
    var committed = database.transact(request);
    try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
        var query = new TemporalQuery.Get(new EntityId("salary"), 25);
        var result = view.query(query);
    }
}
```

Imports are `java.util.List`, `org.meridiandb.engine.LocalDatabase` and
`org.meridiandb.temporal.*`; `directory` is a `java.nio.file.Path`.
Use `openWritable(directory)` for an existing database. A new valid request gets
the next contiguous commit, including a no-op. An identical retry returns its
original position with `replayed() == true` and performs no new append/force.
Conflicting payloads for the same request ID fail with
`EngineException(REQUEST_ID_REUSED)` before storage changes. Invalid pure request
or record encoding also leaves the instance usable.

Writers and close serialize. Readers can capture the old committed state while a
write appends or waits for force. Once publication completes, a new head view sees
the whole transaction. Views opened earlier keep their original answers. There is
no dirty read or intermediate per-operation state. No group commit, arrival-order
promise or measured throughput claim is provided.

## Query recovered history

`open(directory)` exposes the same queries without a write API effect:

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

`checkpointPosition()` returns an optional temporal cutoff. It can precede the
head because later journal transactions are replayed too; it does not advance on
each new write. Use `new ReadCutoff.At(new CommitPosition(databaseId, sequence))`
for an earlier commit in the stored lineage. Foreign or unavailable cutoffs fail
with `EngineException(INVALID_CUTOFF)`. Point, clipped interval scans and exact sums
follow the [temporal contract](../architecture/temporal-contract.md).

## Recovery and writable startup

1. Acquire the checkpoint store and designated journal owner. Validate the full
   journal and reject incomplete tails without automatic repair.
2. Verify the highest selected checkpoint, complete dependency graph and actual
   journal-prefix binding. Damaged newest state or missing dependencies fail closed
   without falling back to an older generation. Journal-only and genesis histories
   are supported.
3. Build private engine state with the stored lineage. Replay checkpoint transactions
   exactly once and only the journal suffix after its cutoff. Require contiguous
   complete history and unique stored request identities; these are original
   commits, not retry calls. Retain every historical cutoff and exact request payload.
4. For writable mode, force the complete owner journal and then its directory before
   enabling acknowledgements. No database or view is exposed before recovery and
   this required synchronization succeed. Failure discards private state and closes
   every acquired owner, preserving primary and suppressed cleanup errors.

Complete recovered bytes do not prove earlier force or acknowledgement. Writable
startup re-establishes the boundary under the actual provider/OS/device's force
behavior, including its treatment of previously dirty data on a reopened file.
An identical retry can then acknowledge the recovered position without additional
I/O. Read-only open performs no journal/segment data force and makes no such write
acknowledgement promise.

## Acknowledgement and uncertain outcomes

A new write's success follows complete state preparation, encoding preflight,
owner-journal append, successful force and one atomic state publication. It relies
on the supported local filesystem and device honoring force; it is not a universal
power-loss guarantee. Initial journal/genesis publication supplies the namespace
boundary. Appending to that established journal requires no per-transaction rename.

After an append attempt, an I/O or runtime failure throws
`LocalDatabase.UncertainCommitException`. It carries `requestId()`,
`proposedPosition()` and its cause. The proposed position is diagnostic; it does
not establish that the request committed. The instance and existing views become
unusable, while storage locks remain held until close. Errors also invalidate the
instance before propagating. Already captured operations may finish under the
usual concurrent-close semantics.

Close the failed database, then reopen and inspect `lookupRequest(requestId)` or
retry the exact original transaction through a writable instance. A complete
record may be recovered even when its earlier acknowledgement was lost. A partial
record fails strict reopen; inspect the retained evidence and explicit journal
repair procedure. Never infer rollback, continue using the failed instance or
silently truncate its tail. Close itself never forces or revokes a successful
write acknowledgement; retry cleanup if releasing resources fails.

## Ownership and checkpoints

All modes retain exclusive cooperating store/journal ownership. Logical read-only
access still requires writable journal/lock handles for their locks. Store open
probes directory open and `force(true)` before creating a lock file; it can perform
metadata I/O. An absent lock file may be created. Unsupported providers report
`DirectorySyncUnsupportedException` instead of receiving a weaker protocol.

Use trusted stable paths and working local locks. Verification, append and force
use the journal owner's channel; a second same-file handle can interfere with
platform locks. Other cooperating owners cannot publish checkpoints concurrently.
There is no automatic checkpoint advancement or reclamation. To use the separate
[checkpoint primitives](local-checkpoints.md), close the database first, then
acquire the store and journal owners. Retain all old files and history.

Read views support concurrent readers. Closing one leaves others usable; database
close invalidates all views before releasing storage resources. A query that
already captured open state may finish. Returned immutable results and commit
positions remain ordinary values without permission to delete storage.

## Cost and verification

Recovery reconstructs full history into interval snapshots and exact request maps,
with multiple sequential validation/replay passes. Every new transaction copies
maps while preparing its immutable candidate. Per-object bounds do not bound total
heap, retained history or replay work. Checkpoints are not yet fast materialized
snapshots or journal-truncation boundaries.

Run verification and the writable example:

```bash
./mvnw -B verify
```

```bash
java --class-path target/classes examples/LocalDatabaseExample.java
```

The example forces two commits, preserves an earlier view, reopens and confirms
an identical retry returns commit two without another append. The separate
recovery example prepares checkpoint-plus-tail storage and opens read-only queries:

```bash
java --class-path target/classes examples/LocalRecoveryExample.java
```

Examples remove only their successful temporary output. An unsupported initial
directory capability is reported explicitly; other failures remain errors and
retain diagnostics.

Focused publication checks:

```bash
./mvnw -B -Dtest=LocalCommitTest,PreparedCommitTest,LocalInitializationTest test
```

Recovery checks:

```bash
./mvnw -B -Dtest=LocalDatabaseTest,LocalRecoveryFixtureTest test
```

Tests cover preparation invisibility, exact identities, startup synchronization,
append/force errors before or after an effect, uncertainty/poisoning, pinned views,
concurrent readers/writers and resource lifetimes. Canonical histories retain their
journal-only, genesis and checkpoint-plus-tail reopen coverage.
[Durability conformance](durability-conformance.md) adds real child-JVM exits at
creation, append, force, publication, acknowledgement, checkpoint and startup
boundaries, 168 writable canonical expectations and 512 seeded durable commits.
These checks and injected storage faults do not simulate device power loss.
