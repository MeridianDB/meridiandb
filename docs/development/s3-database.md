---
title: Managed S3 read recovery
description: Recover read-only temporal queries using exact durable S3 expected state, a fresh scoped head observation and the complete owned local journal.
---

# Managed S3 read recovery

`org.meridiandb.storage.s3.S3Database` opens an experimental read-only database
using the optional `meridiandb-s3` artifact. It admits the actual clients and an
existing durable expected selection, requires a fresh exact head observation,
then invokes [core pinned recovery](object-checkpoint-recovery.md) with privately
acquired local storage. The complete matching local journal is required, including
the checkpoint prefix. [ADR 0032](../decisions/0032-managed-s3-read-recovery.md)
records the decision.

This mode is online at startup. It adds no transaction API, automatic publication
reconciliation, remote deletion or replicated durability. Writable recovery using
object checkpoints remains planned. Ordinary local modes remain JDK-only.

## Open and query

For independently configured existing database and publication-state directories,
`target`, `binding`, actual object/head clients and a known coverage floor:

```java
try (var database = S3Database.open(
        databaseDirectory, stateDirectory, target, binding,
        objects, heads, ObjectCheckpointGraphs.DEFAULT_LIMITS,
        minimumJournalSequence)) {
    var head = database.head();
    var checkpoint = database.checkpointPosition();
    var originalRequest = database.lookupRequest(requestId);
    var startupObservation = database.observation();
    try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
        var result = view.query(query);
    }
}
```

The overload without a final minimum uses zero. Types are `S3PublicationState.Target`,
`S3CheckpointHeads.Binding`, `S3ObjectStore`, `S3CheckpointHeads` and the existing
core graph/temporal types. `openView` returns `LocalDatabase.ReadView`; immutable
results and the complete startup observation retain their ordinary value lifetimes.
No underlying database or mutable metadata owner is exposed.

The factory owns its newly acquired `LocalS3PublicationState` and core database
until close. Both supplied S3 clients and their credential providers remain
caller-owned on success and failure. Keep clients usable during startup; they may
be closed by their owner afterwards without invalidating reconstructed queries.
No lazy S3 reads occur after successful recovery.

Use separate trusted, stable, durably named directories for database history and
publication metadata. Canonically identical directories are rejected before
metadata acquisition. Existing [local metadata capabilities](local-s3-publication-state.md)
and [local database ownership](local-database.md#ownership-and-checkpoints) apply,
including write permissions, exclusive locks and directory synchronization.
Default Windows filesystem placement is explicitly unsupported by the local metadata adapter. Logical
read-only recovery still resynchronizes selected metadata/name on open and may
create missing ownership files; it is not a filesystem read-only operation.

## Admission and ownership

Startup validates arguments and the nonnegative minimum before path access. It
then opens and holds existing metadata under independently supplied target/binding:

1. Require no pending intent and a durable expected observation. Empty, missing,
   corrupt, foreign or uncertain state never initializes or adopts a selection.
2. Require actual object/head client lexical endpoint, signing region and bucket
   to match the managed target; require the head client's exact binding too.
3. Read the current head through the actual SDK client. Require complete bytes
   and the original opaque token to match the durable expected observation.
4. Invoke core pinned recovery. It acquires the local store/journal, freezes H,
   checks the minimum before graph-object reads, verifies original checkpoint
   history against the actual journal and applies only the contiguous suffix.
5. Return the wrapper only after complete reconstruction. Retain all three local
   owners until close, excluding cooperating publication and local modification.

The state target stores endpoint, region and bucket; its binding stores namespace,
publisher and lineage. It contains no database-directory path certificate. The
caller configures that path independently, and actual lineage/prefix validation
binds it to the selected history. Preserve trusted single-placement and
no-external-rollback assumptions; copied matching history is not unique ownership.

Startup holds private metadata, head-client and object-client monitors in that
order. Core store/journal owners are newly acquired privately with fail-fast
contention checks. This differs from the publisher's order over borrowed local
owners. Client close waits during startup; credential callbacks must not reenter
storage operations. Wrapper close invalidates views, independently closes the
core database and metadata, preserves primary/suppressed cleanup failures and
allows cleanup retry. Supplied clients are never closed by the wrapper.

## Expected and pending selection

| Evidence | Startup behavior |
| --- | --- |
| Exact expected bytes and original token | Verify full graph and actual journal before exposing queries |
| Same bytes with a different token | Reject; do not refresh durable expected state |
| Missing, stale, foreign, corrupt or changed head | Fail without another graph, version listing or fallback |
| Access denied or transport error | Fail; neither proves absence |
| Pending intent, including an already visible candidate | Refuse before any head GET; require explicit publication reconciliation |
| No durable expected selection | Refuse before any head GET; no adoption or automatic genesis |

Resolve pending state through the existing [publisher's explicit reconciliation](s3-checkpoint-publication.md)
with its own actual owners, graph budgets and synchronization. Close those owners
before retrying managed startup. A reconciliation may fail or require a separate
explicit original-intent submission; startup makes no such decision and never
clears, rebases, repairs or deletes evidence. Managed startup invokes one head
read and the immutable graph reads, with no PUT or DELETE. Native transport may
retry a failed read; graph limits do not bound its wire attempts.

`observation()` returns the complete historical startup observation. A later head
change does not refresh the recovered database. Exact bytes/token do not establish
a continuing lease, cross-host fencing or complete rollback detection: an external
rewrite may restore both, and an ETag is neither a unique version ID nor the graph's
SHA-256 identity. Remote reader protection remains a separate requirement.

## Coverage, offline use and costs

Checkpoint C and recovered local head H remain distinct. A valid older expected
checkpoint can recover a complete matching suffix through H. The optional minimum
is an independent sequence floor; insufficient H fails after the head GET but
before any graph-object fetch. Neither zero nor a checkpoint can reveal an unknown
cleanly lost suffix. This API reconstructs no missing local records and issues no
new transaction acknowledgement.

Managed startup has no offline fallback. Explicit core pinned recovery can use a
separately chosen supplied graph and adapter, including local objects, while
retaining its complete-journal and explicit-authority contract. Ordinary local
`open` uses its own selected local checkpoint. Choosing either mode is a separate
caller decision, not a retry policy inside `S3Database`.

Graph limits cover one complete history load and actual-prefix check. Successful
managed admission adds one fixed 256-byte head response body outside those graph
limits. HTTP headers/errors, local metadata discovery/read/force, initial journal
scan, complete replay, reconstructed history and total heap/CPU remain separate
costs. Repeated starts and provider failures require their own caller accounting.

The 63 focused cases use the real SDK against loopback HTTP with actual local ownership,
exact state/head matching, retained pending evidence, explicit reconciliation,
coverage and cleanup failures, historical answers and process reopen. They qualify
this implementation on supported placement, not a live service or device power loss.

```bash
./mvnw -B -f adapters/s3/pom.xml -Dtest=S3DatabaseTest,S3DatabaseStartupTest test
```
