---
title: Local reclamation inventory
description: Inspect verified obsolete checkpoint objects under exclusive reader protection, preserving selected and explicit retained roots without deleting files.
---

# Local reclamation inventory

`LocalCheckpointStore.inspectReclamation` reports which complete published
manifests and associated segments are obsolete under an explicit retention choice.
It acquires exclusive reader protection, verifies every published graph against
the actual owned journal, and returns immutable metadata. **It deletes no files.**
The result is an inspection snapshot, with no authority to delete objects later.

Close an existing `LocalDatabase` owner before opening the store and its journal.
Use an empty retained set to retain only the newest published generation, or supply
additional published `ManifestId` values for physical retention or an in-progress
backup. The newest generation is always retained automatically:

```java
try (var store = LocalCheckpointStore.open(directory);
     var journal = LocalJournal.open(store.journalPath())) {
    var inventory = store.inspectReclamation(java.util.Set.of(), journal);
    if (inventory.status() == ReclamationInventory.Status.VERIFIED) {
        var metadata = inventory.obsoleteManifests();
        var segments = inventory.obsoleteSegments();
        long encodedCandidateBytes = inventory.candidateBytes();
    }
}
```

Imports are `org.meridiandb.storage.manifest.LocalCheckpointStore`,
`org.meridiandb.storage.manifest.ReclamationInventory` and
`org.meridiandb.storage.journal.LocalJournal`; `directory` is a `java.nio.file.Path`.
The IDs can be saved from `publish` or `publishCompaction`, or obtained from an
admitted reader before closing it. Retained IDs must resolve to existing published
manifests. Supplying an ID creates no durable pin or backup: repeat every required
retained root on each future inspection or removal operation.

## Roots and candidate policy

| Object or condition | Inventory treatment |
| --- | --- |
| Highest uniquely named generation | Always a live root; complete graph and actual journal prefix must verify |
| Explicit retained/backup manifest ID | Additional live root; a missing or invalid root fails inspection |
| Other published manifest | Candidate only after its complete graph and actual owner prefix verify |
| Segment used by any live root | Preserved even if an obsolete generation also uses it |
| Segment used only by verified obsolete roots | Candidate with its exact encoded byte length |
| Unreferenced segment, unknown file or subdirectory | Preserved; filename shape or apparent age alone grants no candidate status |
| Any `pending-` name | Inspection fails pending explicit diagnostic review; no candidate is adopted or discarded |
| Journal, store ownership file and permanent reader guard | Preserved |
| Live lazy reader in this or another process | `DEFERRED_READERS`, with no verified inventory or candidate totals |
| Aliased metadata or referenced segment | Fail before another object channel opens; preserve the alias/evidence |

Every published graph is checked, including old generations proposed for retirement.
A damaged newest generation never falls back to an older one. A damaged old graph
also prevents a complete inventory; this conservative policy avoids guessing which
objects belong to failed or inconsistent history. Generation-number gaps are legal.

Candidate segments are the dependencies of verified obsolete manifests minus the
dependencies of all live roots. Unrelated orphan files stay outside that set.
Unknown metadata does not implicitly designate a root: declare any backup's required
published IDs explicitly. Unknown/diagnostic names remain untouched, and a physical
alias to an inspected object causes refusal. Dangling or inaccessible names that
prevent a same-file identity check also fail closed.

## Protection, limits and failures

The store keeps exclusive publisher ownership and a nonblocking exclusive
[reader barrier](reader-protection.md) through discovery and verification. A
participating reader can outlive store close, so any such reader defers the whole
inspection. Known contention leaves the store usable; retry after readers close.
The preview releases exclusive protection before returning. First admission can
create/force the permanent empty guard and synchronize its directory entry;
inspection is therefore not a promise of an unchanged namespace.

The adapter bounds directory entries and published generations as described in
[local checkpoints](local-checkpoints.md). Inventory additionally limits work:

| Budget | Default and hard maximum | Accounting |
| --- | --- | --- |
| Verified encoded bytes | 64 MiB | All manifest images plus referenced segment lengths, counting repeated references again for each root |
| Verified history records | 262,144 | Sum of each published root's transaction cutoff |
| Same-file identity comparisons | 1,000,000 | Distinct inspected metadata/segment names times other directory entries |

Use the overload with `new ReclamationInventory.Limits(maxBytes, maxRecords)` for
smaller byte/record budgets. Preflight record/reference totals before segment
loads; bound metadata reads by the remaining byte budget and segment reads by
their exact declared lengths, so oversized corrupt files cannot bypass it. These are serialized
work bounds, not exact heap or elapsed-time limits. Complete verification still
replays repeated history and hashes actual owner-journal prefixes for every root.
The byte budget counts inspected graph images; journal acquisition/replay and actual
prefix validation use the journal adapter's own frame/cutoff bounds. A mismatching
actual prefix may require more journal bytes than the graph declares.
The current same-file checks can be costly in a large directory; exceeding a
budget fails inspection without partial results.

Read, validation, identity, protection or cleanup failure poisons the store.
Close and reopen before another operation. If protection release fails, store
close retries it and independently attempts publisher cleanup. Uncertain native
channel close remains quarantined until process restart under the
[shared ownership rules](reader-protection.md#failure-and-close).
No successful inventory escapes after a cleanup error. No failure authorizes
repair, deletion, automatic adoption or logical history pruning.

`obsoleteManifests`, `obsoleteSegments` and `preservedNames` have deterministic
name ordering; returned collections are immutable. `retainedManifestIds` includes
the selected root, with no set-iteration-order promise. `verifiedBytes` and
`verifiedRecords` report the accounting above. Candidate bytes count each eligible
name once. They describe encoded files, with no promise of freed blocks, completed
reclamation or faster queries. The separate [remover](physical-reclamation.md) recomputes under both forms of
ownership, establishes durable live roots, then retires/synchronizes obsolete
metadata before dependencies. A preview is never an input to that API.

## Example and verification

After `./mvnw -B verify`, run:

```bash
java --class-path target/classes examples/ReclamationInventoryExample.java
```

The example publishes a repacked generation, demonstrates reader deferral, then
compares selected-only and explicit-root inventories. It retains every storage
object until its successful temporary-example cleanup after all handles close.

Focused tests:

```bash
./mvnw -B -Dtest=LocalReclamationInventoryTest,LocalReclamationInventoryIntegrationTest test
```

The tests cover roots and shared dependencies, exact bounds, invalid old/new state,
actual journal binding, immutable results, diagnostic evidence, alias rejection,
protection cleanup and cross-process reader lifetime. They establish inventory
behavior. Actual removal and interrupted deletion have their own
[protocol checks](physical-reclamation.md); full integrated temporal/crash/space
conformance remains separate. See [ADR 0018](../decisions/0018-reclamation-inventory.md).
