---
title: Writes after pinned checkpoint recovery
description: Recover an explicitly pinned object checkpoint, synchronize the complete local journal and preserve forced commit, retry and historical-view behavior.
---

# Writes after pinned checkpoint recovery

`LocalDatabase.openWritableFromObjectCheckpoint` recovers from an explicitly
supplied object checkpoint plus the complete matching local journal, then enables
forced local writes. It uses the JDK-only core artifact. Complete private
reconstruction and actual journal/name synchronization finish before a database
is returned or any recovered retry can be acknowledged.

[ADR 0033](../decisions/0033-pinned-checkpoint-writes.md) records the decision.
[Read-only pinned recovery](object-checkpoint-recovery.md) remains available.
[Managed S3 writable recovery](s3-database-writes.md) now composes managed admission
with this core mode; [remote writable conformance](s3-recovery-conformance.md)
now exercises the unchanged corpus and publication boundaries through the SDK.
`S3Database.open` retains its read-only contract.

## Recover and transact

For an existing trusted database directory, supplied object adapter, independent
namespace/publisher IDs and exact pinned head:

```java
try (var database = LocalDatabase.openWritableFromObjectCheckpoint(
        directory, objects, namespace, publisher, head,
        ObjectCheckpointGraphs.DEFAULT_LIMITS, minimumJournalSequence)) {
    var checkpoint = database.checkpointPosition();
    var original = database.lookupRequest(originalRequest.requestId());
    var retry = database.transact(originalRequest);
    var committed = database.transact(newRequest);
    try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
        var result = view.query(query);
    }
}
```

The overload without the final minimum uses zero. Types and independent inputs
are the same as [pinned read recovery](object-checkpoint-recovery.md#open-and-query),
with `Transaction` and `CommitResult` from `org.meridiandb.temporal`.
`originalRequest` must be an identical previously committed request for a retry;
`newRequest` uses a new identity. A valid new no-op still advances commit order.

The database owns its acquired checkpoint store and actual journal until close.
The supplied object adapter remains caller-owned on success and failure. Keep it
usable during startup; it can be closed afterwards while queries and local
transactions continue. New transactions append to the existing local journal;
they do not upload objects, change a remote head or publish a local checkpoint.
`checkpointPosition()` remains the supplied checkpoint cutoff while `head()`
advances on new commits.

## Startup and acknowledgement

Startup preserves the explicit pinned authority and all validation from the
read-only mode:

1. Validate pure arguments, acquire store/journal ownership and freeze the observed
   journal head H. Reject H below an independently known minimum before object reads.
2. Load the complete graph within that same ownership and verify its original
   prefix against the actual local journal. Retain original lineage, identities
   and transaction payloads, including no-ops.
3. Restore checkpoint history once, validate the complete journal through H and
   apply only records after checkpoint cutoff C. Keep reconstructed state private.
4. Synchronize the complete actual journal and its directory through their existing
   owners, then expose a writable database. Failure exposes no database and closes
   all acquired resources with primary/suppressed errors preserved.

The new mode reuses [forced local commit publication](local-database.md#acknowledgement-and-uncertain-outcomes).
A new transaction prepares complete immutable state and encoded bounds, appends
one exact record, forces the owner journal and atomically publishes state/request
identity before returning success. A later head view sees the whole commit;
earlier views retain their original answers. Concurrent writers and close use
the existing serialized transaction path.

An identical retry returns its original position with `replayed() == true` and
performs no new append/force. This is permitted only after writable startup has
established its journal/name boundary or the same instance has acknowledged the
commit. A conflicting payload for a stored request ID fails before storage changes.
Raw recovered bytes or the object checkpoint alone do not establish earlier force
or acknowledgement. Supported local filesystem/device force behavior remains a
required assumption, not a device-power-loss guarantee.

## Uncertain outcomes and ownership

Post-attempt I/O/runtime failure keeps the existing `UncertainCommitException`
contract. Errors also poison the instance before propagation. Queries, views and
further transactions become unusable; retain the owners and evidence until close.
Reopen the same explicit mode, complete startup synchronization and resolve the
original request identity. Do not invent a different request ID to retry an
uncertain transaction. A complete surviving record may exist even when no reply
was returned; an incomplete tail is rejected without automatic repair.

Closing invalidates views and independently attempts every owned resource, with
cleanup retry and native-close quarantine following the existing local contract.
Previously returned immutable results remain values. Neither failure, close nor
a successful write authorizes object reclamation or remote publication.

The pinned mode does not inspect newer local manifests, discover a provider's
current head or fall back to another graph. Ordinary local create/open/writable
selection retains its separate contract. Use trusted stable paths and the existing
[local ownership/capability requirements](local-database.md#ownership-and-checkpoints).
Logical authority to write follows this explicit mode and supported single-writer
placement; it supplies no cross-host fencing or managed S3 expected-state admission.

## Coverage, costs and verification

The complete matching local journal, including the checkpoint prefix, remains
required. An older valid pin can recover a complete suffix. An independent minimum
is a sequence floor, not a content hash or an acknowledgement certificate. The
default zero cannot reveal an unknown cleanly lost suffix, and no missing records
are reconstructed. An explicit local object adapter can support offline pinned
startup; there is no automatic offline choice inside a managed API.

Graph limits bound the same complete history load and actual-prefix comparison.
Initial journal scanning, complete replay, startup force, retained logical history,
new writes and total heap/CPU remain separate costs. Frozen H bounds reconstruction
only; later writes append and force through the actual live journal. The factory
adds no truncation boundary, batching or measured throughput claim.

The 66 new core invocations comprise 26 focused ownership/startup/write/failure/
process cases and 40 canonical/query/seeded cases. They cover exact retries, new
writes, immutable views, failure poisoning and real process stops/reopen. Unchanged canonical/query
fixtures and persisted seeded histories exercise writable pinned recovery at
genesis, split and full checkpoints against independent reference/dense and local
baselines. Separate [managed S3 conformance](s3-recovery-conformance.md) supplies SDK
and publication uncertainty checks; these core tests alone do not qualify that
integration or a live provider.

```bash
./mvnw -B -Dtest=ObjectCheckpointWritableTest,ObjectCheckpointWritableConformanceTest test
```
