---
title: Managed S3 writable recovery
description: Admit exact durable S3 selection, synchronize the complete local journal and preserve forced local transactions, request retries and snapshot lifetimes.
---

# Managed S3 writable recovery

`S3Database.openWritable` combines [managed S3 admission](s3-database.md) with
[core pinned writable startup](object-checkpoint-writes.md). It requires existing
clean expected publication state, exact actual client scope, a fresh matching head
image/token and the complete matching local journal. Full reconstruction and local
journal/name synchronization finish before the database is returned.

The optional `meridiandb-s3` mode is experimental. [ADR 0034](../decisions/0034-managed-s3-writable-recovery.md)
records the composition. Focused SDK, ownership, interruption and concurrency checks
exercise it; [remote writable conformance](s3-recovery-conformance.md) now adds
the full unchanged corpus and cross-publication checks. Ordinary
local embedding retains only the JDK-only core dependency.

## Choose a mode and transact

| Factory | Transactions |
| --- | --- |
| `S3Database.open(...)` | Read-only; `transact` rejects new requests and exact retries |
| `S3Database.openWritable(...)` | Synchronize complete local history, then enable forced local commits and original retries |

Both factories use the same directory, target, binding, client, graph-limit and
optional minimum arguments. The overload without a minimum uses zero. For existing
independently configured directories and clients:

```java
try (var database = S3Database.openWritable(
        databaseDirectory, stateDirectory, target, binding,
        objects, heads, ObjectCheckpointGraphs.DEFAULT_LIMITS,
        minimumJournalSequence)) {
    var checkpoint = database.checkpointPosition();
    var startupObservation = database.observation();
    var retry = database.transact(originalRequest);
    var committed = database.transact(newRequest);
    try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
        var result = view.query(query);
    }
}
```

`originalRequest` is an identical previously committed `Transaction`; `newRequest`
uses a new identity. The result is the existing core `CommitResult`. The
[managed read guide](s3-database.md#open-and-query) identifies remaining types and
independent configuration. `openView` returns `LocalDatabase.ReadView`.

A successful new request prepares complete state, appends one exact local journal
record, forces it and atomically publishes temporal state and request identity.
A new no-op still advances commit order. An identical retry returns its original
position with `replayed() == true` without another append/force, after the required
startup or prior-commit barrier. A changed payload for a known identity fails before
storage effects. These are the existing [local acknowledgement guarantees](local-database.md#acknowledgement-and-uncertain-outcomes).

## Startup authority and coverage

The [same managed admission rules](s3-database.md#admission-and-ownership) apply:
refuse pending or absent expected state before remote reads; admit the actual
lexical endpoint, signing region, bucket and head binding; require a fresh exact
expected image and original token. Current remote errors, changed tokens or missing
history never cause adoption, rebase, repair or fallback. Resolve pending through
explicit [publication reconciliation](s3-checkpoint-publication.md) using separate
owners before retrying startup.

The core then acquires the actual store/journal, freezes H, checks the independent
minimum before graph reads, restores the verified prefix once and applies only the
contiguous suffix while validating the full log. Only after complete reconstruction
does writable startup synchronize that journal and its directory. A startup failure
returns no database and independently releases acquired resources.

`checkpointPosition()` remains the verified checkpoint cutoff C. `head()` advances
with later local commits, including no-ops. `observation()` remains the original
historical startup image/token and does not refresh after writes. No transaction
performs a remote GET/PUT/DELETE or installs a new local publication-state generation.
An object checkpoint covers C; newer acknowledged transactions depend on the local
journal until a separately executed publication covers them. They gain no remote
or replicated durability from this mode.

A valid older expected checkpoint can recover a complete matching local tail.
The minimum is an independent sequence floor, not a content hash or acknowledgement
certificate. Default zero cannot reveal an unknown cleanly lost suffix. Complete
matching local journal history, including the prefix, remains mandatory. No missing
records are reconstructed. Every managed reopen is online at startup; explicit
local or pinned modes are separate caller choices rather than automatic fallbacks.

## Writers, readers and close

The wrapper owns publication metadata and its private core database until close;
the core retains the actual store and journal. Cooperating publishers and local
writers cannot acquire those owners while the database is open. Supplied clients
and credential providers remain caller-owned. Keep clients usable through startup;
they may be closed afterwards while local queries and writes continue.

Writes and close serialize on the private core owner. Committed-state capture
stays outside that write monitor: a query or new view can observe the previous
committed state while append/force is in progress. Already opened views retain their
pinned cutoffs. Atomic publication makes a later head view see the whole new commit.
There is no intermediate per-operation visibility or dirty read.

Close waits for another thread's transaction to finish, then invalidates views
before independently attempting core and metadata cleanup. A reentrant transaction
or close from an active write callback is rejected before close invalidates anything.
Primary/suppressed cleanup failures and cleanup retry retain the existing contracts.
No underlying core, metadata owner or arbitrary backend callback is exposed publicly.

## Uncertainty and limits

The core's post-attempt `UncertainCommitException` propagates unchanged. Its request
identity and proposed position identify the unresolved local transaction. Errors
retain the core's poisoning behavior. After poisoning, wrapper queries, request
lookup, checkpoint/head/observation access, further transactions and existing views
fail. Already returned immutable results and observations remain values. The three
local owners remain held until close; failure does not release them for another writer.

Close, reopen managed writable mode with the original configuration, complete all
admission and synchronization, then resolve the original request. A surviving complete
record may represent a commit whose caller never received a reply. An exact retry
returns its original position; a missing request may be submitted under that same
identity after valid recovery. An incomplete tail remains an error without automatic
repair. Provider/device force assumptions and native-close quarantine still apply;
process exit is not a device-power-loss test.

Startup graph limits cover one complete history load and actual-prefix comparison.
The successful fixed head body, transport attempts, metadata reopen/force, initial
journal scan, full replay, writable startup synchronization and retained heap remain
separate costs. Each transaction has its existing encoding and local I/O work.
Neither this composition nor its checks establish throughput, group commit, a
continuing remote lease, distributed fencing, replication or reclamation permission.
Default Windows metadata placement remains explicitly unsupported.

The 74 focused invocations comprise 49 writable API/admission cases and 25 lifecycle/
interruption/concurrency cases using real SDK/loopback HTTP, actual local owners
and the real core boundary probes. They cover exact/invalid admission, retries/new writes, unchanged
remote/metadata evidence, startup/write uncertainty, process reopen and concurrent
snapshot capture/close. Fifty additional [remote writable conformance cases](s3-recovery-conformance.md)
now exercise the unchanged canonical/seeded corpus, late/interrupted publication,
subsequent forced writes, exact reopen and coverage-loss limits.

```bash
./mvnw -B -f adapters/s3/pom.xml -Dtest=S3DatabaseWritableTest,S3DatabaseWritableLifecycleTest test
```
