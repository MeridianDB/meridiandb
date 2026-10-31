---
title: Protected local physical reclamation
description: Remove verified obsolete checkpoint manifests and unused dependencies under exclusive reader protection, with ordered synchronization and conservative interruption recovery.
---

# Protected local physical reclamation

`LocalCheckpointStore.reclaim` removes verified obsolete published manifests and
their unused segment dependencies. It preserves the selected checkpoint, every
explicitly retained root, the complete journal and all logical transaction history.
An open lazy reader in any participating process defers the whole operation.

Close a `LocalDatabase` owner before acquiring the store and journal. Supply
additional published `ManifestId` values for physical retention or an in-progress
backup; the selected newest generation is always retained implicitly:

```java
try (var store = LocalCheckpointStore.open(directory);
     var journal = LocalJournal.open(store.journalPath())) {
    var result = store.reclaim(java.util.Set.of(), journal);
    if (result.status() == ReclamationResult.Status.COMPLETED) {
        var retiredMetadata = result.removedManifests();
        var removedSegments = result.removedSegments();
        long encodedBytesRemoved = result.removedBytes();
    }
}
```

Imports are `org.meridiandb.storage.manifest.LocalCheckpointStore`,
`org.meridiandb.storage.manifest.ReclamationResult` and
`org.meridiandb.storage.journal.LocalJournal`; `directory` is a `java.nio.file.Path`.
Use `Set.of(retainedId)` or a larger set when a backup/retention task needs older
published roots. Missing requested roots fail closed. This is a per-invocation
choice, not a durable pin: repeat all required roots on every removal call.

## Verified scope and ordered effects

The remover recomputes the [inventory](reclamation-inventory.md) while holding
exclusive store ownership and a nonblocking exclusive [reader barrier](reader-protection.md).
It accepts no previously returned preview or caller-supplied candidate list.
Every published graph, including proposed obsolete metadata, must verify against
the actual owned journal. Unknown files, unreferenced segments and diagnostic
objects stay outside the candidate set. Any `pending-` name refuses reclamation
until separate explicit inspection resolves its role.

When candidates exist, the protocol is:

1. Force the actual owned journal and every retained manifest image and segment.
   Force the directory to establish their names before retiring any older root.
   This also establishes a complete visible root left by an uncertain publication.
2. Delete only the verified obsolete manifests. Force the directory after all
   those removals and before deleting any segment dependency.
3. Delete only verified obsolete dependencies absent from every live root.
   Force the directory again.
4. Check protection validity and release it successfully before returning a
   completed receipt. Store ownership remains held until the caller closes it.

Immediately before each deletion, require a regular nonsymlink object, reject
same-file aliases to controls or other remaining directory entries, and reserve
its identity against other loaded adapters' control/data handles. This reservation
opens no native channel. Use `Files.delete`, so an unexpectedly missing name is a
failure rather than a false removal receipt. Trusted stable paths and cooperating
writers/readers remain prerequisites; this is not protection against hostile
filesystem mutation.

The journal, `.meridiandb-checkpoints.lock` and `.meridiandb-readers.lock` are never
removal candidates. All historical cutoffs and original request identities remain
recoverable through retained checkpoint history plus the unchanged full journal.
This is physical representation cleanup, with no logical history pruning.

## Contention, failures and reopen

| Outcome or stopping point | Effect and next operation |
| --- | --- |
| Live participating reader | `DEFERRED_READERS`, empty receipt, no inventory reads or deletion; close readers and retry |
| Verified inventory has no candidates | Completed empty receipt after protection cleanup; no removal/force sequence is needed |
| Verification, alias, budget or live-root force failure before first deletion attempt | No deletion is attempted; store is poisoned, so close and reopen before retry |
| During obsolete manifest removal | Some obsolete metadata may remain; all old dependencies remain available |
| After metadata retirement sync, during segment removal | All remaining published roots are complete; unremoved old segments can become preserved orphans |
| Final sync or protection cleanup failure | No success receipt; preserve evidence, close and reopen to discover actual remaining names |

Before attempting the first delete, the store records that effects may occur.
An I/O or runtime failure from then onward throws `UncertainReclamationException`,
including failures reported after an effect or during final cleanup. Errors also
poison the store and propagate. An exception does not prove rollback. A successful
receipt describes synchronized name removal under the stated provider/storage
assumptions; it does not simulate device power loss.

Reopen reselects the highest generation and fails closed if it is damaged. A fresh
reclaim recomputes the graph; it never replays a stale preview or receipt. No segment
is deleted until obsolete metadata removal has been synchronized. If interruption
leaves a segment without any published reference, that orphan is retained, even
when a previous preview mentioned it. This conservative leak needs a separate
inspected policy; no persistent reclamation intent or automatic orphan sweep exists.

Failed barrier release remains reachable for close retry, and publisher cleanup
is attempted independently. A thrown native channel close remains quarantined
until process restart, as specified by the [reader cleanup contract](reader-protection.md#failure-and-close).
All readers must participate in that protocol, with one loaded adapter copy per
JVM. Quiesce legacy unprotected readers before enabling physical removal.

## Limits and receipts

Inventory preflight uses the same graph-byte, record and identity-comparison
limits as the preview.
The overload accepting `ReclamationInventory.Limits` permits smaller byte/record
budgets. They are graph inspection bounds, not a total journal acquisition or I/O
limit. Per-delete alias checks add bounded namespace identity work, up to another
inventory-sized comparison budget; the 1,000,000 cap applies to inventory preflight,
not the entire removal operation. No extra
manifest, intent file or temporary candidate is created; first barrier admission
may create/synchronize the permanent guard whose slot publication reserves.

Cleanup can restore publication entry/generation headroom when inspection fits
its budgets. It cannot guarantee progress in every full namespace: too many
retained roots, diagnostic objects, reader contention or an exceeded comparison
budget can still prevent removal. Reclaim before reaching those limits and close
long-lived readers deliberately. There is no background scheduler.

`removedManifests` and `removedSegments` contain immutable, deterministically ordered
name/encoded-length descriptors. `removedBytes` counts those encoded names once;
it is not allocated blocks or guaranteed free filesystem space. A deferred receipt
has no selected root or removal totals. A completed empty receipt is a successful
no-op. No stable public API, query speedup or throughput result is implied.

## Example and verification

After `./mvnw -B verify`, run:

```bash
java --class-path target/classes examples/ReclamationExample.java
```

The example demonstrates reader deferral, explicit retained roots, actual name
removal and selected-history reopen. Focused tests:

```bash
./mvnw -B -Dtest=LocalReclamationRemovalTest,LocalReclamationRemovalIntegrationTest,LocalReclamationRemovalCrashTest test
```

Unit faults check before/after effects, ordering, root forces, poisoning and cleanup.
Real process tests check cooperating reader exclusion and termination at removal
boundaries, preserving complete selected graphs and safe retry. These cases cover
the local removal protocol; full canonical/seeded compaction-removal conformance and
space measurements remain separate from this evidence. See
[ADR 0019](../decisions/0019-protected-physical-reclamation.md).
