---
title: Pinned checkpoint engine recovery
description: Recover read-only temporal queries from an explicitly pinned object checkpoint and the complete owned local journal, with frozen coverage, strict cleanup and optional minimum-sequence admission.
---

# Pinned checkpoint engine recovery

`LocalDatabase.openFromObjectCheckpoint` reconstructs a read-only temporal engine
from one explicitly supplied object checkpoint and the complete local journal.
It loads original checkpoint records within its own storage ownership, verifies
their actual journal prefix, restores them once and applies only the contiguous
journal suffix. Historical queries and original request lookup become available
only after startup finishes completely.

This JDK-only core mode is experimental. It requires an existing matching local
journal, including the checkpoint prefix. Managed S3 current-head admission and
writable recovery using object checkpoints remain planned. A pinned declaration
is an explicit caller choice; it is not evidence of the provider's current head.
[ADR 0031](../decisions/0031-pinned-checkpoint-engine-recovery.md) records the decision.

## Open and query

For independently configured namespace/publisher IDs, a supplied pinned head and
an already installed complete object graph:

```java
try (var database = LocalDatabase.openFromObjectCheckpoint(
        directory, objects, namespace, publisher, head,
        ObjectCheckpointGraphs.DEFAULT_LIMITS)) {
    var recoveredHead = database.head();
    var checkpoint = database.checkpointPosition();
    var originalRequest = database.lookupRequest(requestId);
    try (var view = database.openView(ReadCutoff.Head.INSTANCE)) {
        var result = view.query(query);
    }
}
```

The factory is in `org.meridiandb.engine.LocalDatabase`; graph/head types are in
`org.meridiandb.storage.manifest`. `objects` implements the core
`ImmutableObjectStore`, `directory` is the existing local checkpoint directory,
and the remaining query/identity types are in `org.meridiandb.temporal`.
No provider implementation is initialized by ordinary local open modes.

The database owns the newly acquired checkpoint-store and designated
`transactions.journal` owners until close. The object adapter remains caller-owned
on success and failure. Keep it usable through startup; after recovery the engine
uses heap state and performs no lazy object reads. Closing the adapter afterwards
does not invalidate recovered queries. The directory must satisfy the existing
[local ownership and directory-sync capabilities](local-database.md#ownership-and-checkpoints),
including trusted stable paths and writable lock handles. Logical read-only access
can probe/synchronize directory metadata or create its missing ownership file.

## Pinned authority and recovered coverage

This explicit factory uses the supplied object head as its checkpoint authority.
It does not inspect or select local checkpoint manifests, discover newer remote
heads, traverse predecessor history or fall back to another graph. Unrelated local
checkpoint files remain untouched. Ordinary `open` and `openWritable` retain their
highest-selected-local-checkpoint protocol and fail-closed behavior.

| Value | Meaning |
| --- | --- |
| `checkpointPosition()` | The pinned checkpoint's verified temporal cutoff C, including zero for genesis |
| `head()` | The actual local journal head H frozen during startup; H must be at least C |
| Historical view at sequence S | Original temporal answers for any available cutoff from zero through H |
| `lookupRequest(id)` | Original position of a stored request, including a no-op commit; no retry acknowledgement |

A valid older pinned checkpoint can recover all available history when its exact
prefix and complete local suffix match. Head revision, manifest generation, C and
H remain independent coordinates. The factory preserves every original request
payload, operation order, correction, retraction and historical cutoff. Transactions
are rejected on this read-only database, including identical application retries.

The overload with a final `long minimumJournalSequence` requires an independently
known coverage floor:

```java
var database = LocalDatabase.openFromObjectCheckpoint(
        directory, objects, namespace, publisher, head, limits, requiredSequence);
```

Negative minima are argument errors before acquisition. An observed H below the
minimum fails before any object fetch. The default minimum is zero. A minimum is
a sequence floor, not a content hash or proof of acknowledgement. If later local
records were completely lost at a valid frame boundary, neither an older checkpoint
nor the remaining journal reveals their former existence. Supply a known minimum
to reject insufficient coverage; this mode cannot invent or recover those records.

## Startup, failures and lifetime

1. Validate null, non-nil identity and minimum arguments before storage acquisition.
2. Acquire store and journal ownership. Strict journal open validates its complete
   framing/order and refuses incomplete tails without automatic repair. Capture H
   once and require the independent minimum before object reads.
3. Call the [pinned history loader](object-checkpoint-graphs.md#load-pinned-history)
   with that same actual owner. Verify independent namespace/publisher/lineage,
   complete immutable dependencies, ranges, request identities and exact prefix.
4. Seed a private temporal engine with the stored lineage and restore checkpoint
   transactions once. Validate the full journal from sequence one through frozen H,
   applying only records after C. Reused stored request IDs are invalid history,
   including identical payloads crossing the checkpoint boundary.
5. Expose the read-only database only after all reconstruction succeeds. Failure
   discards private state and independently closes journal/store, retaining primary
   and suppressed cleanup failures. The supplied object adapter is never closed.

Binding, graph/codec, journal and source failures retain their existing types.
Missing, corrupt or divergent evidence never permits repair, an older-graph fallback
or a partial database. A failed startup retains all files/objects for inspection.
Native cleanup uncertainty keeps the existing quarantine/restart requirements.

Both local owners stay held even though queries use heap state. Other cooperating writers, checkpoint publishers and removers cannot acquire
ownership until this database closes. Closing
invalidates read views before releasing owners; returned immutable query results
remain ordinary values. There is no new force-before-write acknowledgement, remote
publication, reader lease on another host or reclamation permission.

## Costs and verification

Graph limits cover the loader's complete graph reads and actual-prefix comparison,
including prefix record cardinality and encoded bytes. They do not cap the exact
heap occupied by retained `History` records, initial journal-open scanning, the
later full journal replay, reconstructed snapshots or total engine heap/CPU.
Recovery performs multiple validation passes and retains all logical history.
It is neither a journal-truncation boundary nor a measured faster-reopen path.

Focused checks cover factory admission, resource ownership, missing/corrupt/divergent
evidence, minimum coverage and view lifetimes. Independent conformance reuses the
canonical temporal/query fixtures, all historical cutoffs, original request lookup
and persisted seeded dense histories through genesis, split and full pinned shapes.

```bash
./mvnw -B -Dtest=ObjectCheckpointRecoveryTest,ObjectCheckpointRecoveryConformanceTest test
```

These tests establish core pinned recovery on supported local placement. S3-managed
startup, writable retry acknowledgement, replication and local-storage-loss recovery
require their separate contracts; fixture storage does not qualify a live provider.
