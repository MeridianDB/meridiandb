---
title: Local checkpoint publication
description: Publish and reopen immutable local checkpoint generations with actual journal-prefix verification, ordered forces, exclusive ownership and explicit failure handling.
---

# Local checkpoint publication

`LocalCheckpointStore` publishes a verified manifest and its immutable history
segments in one local directory. It compares their complete history with the
actual bytes read through the owning `LocalJournal`, then orders file forces,
atomic rename and directory forces before returning success. This is an
experimental local storage primitive. The separate [local recovery API](local-database.md)
reconstructs temporal queries from checkpoint plus journal tail. Its explicit
writable mode adds a separate forced commit protocol; checkpoint publication
alone is not an engine commit acknowledgement.

[ADR 0012](../decisions/0012-local-checkpoint-publication.md) explains the protocol
and primary evidence. The [manifest specification](../architecture/checkpoint-manifest-format.md)
owns the byte format and complete-set consistency rules.

## Directory and ownership requirements

Pass an existing, trusted, stable directory whose own name is already durably
established by its creator. The store rejects a final directory symlink and
resolves parent aliases. Keep directory entries stable and use only cooperating
writers. File locks and final-component no-follow checks do not protect against
external mutation, malicious path replacement or every network filesystem.

Opening probes a read-only directory channel and `force(true)` before creating
any lock or candidate file. A provider that cannot perform this operation raises
`DirectorySyncUnsupportedException` and leaves the directory contents unchanged.
The probe is a capability check, not proof of device or power-loss behavior.
Atomic rename is separately required during publication; there is no weaker
fallback. Support depends on the actual JDK, provider and filesystem. Windows can
run the Java build and pure/injected tests even when this local capability is
unavailable.

One `.meridiandb-checkpoints.lock` excludes cooperating store publishers. A
same-JVM reservation precedes opening another lock handle, preserving process
locks on platforms where closing a second same-file handle can release them.
The journal has its own exclusive owner and must occupy the canonical
`store.journalPath()`, named `transactions.journal`, in the same directory.
Read and force operations reject checkpoint objects hard-linked to either owned
file or the reader barrier before opening another channel. The store lock itself cannot alias the
journal. Avoid independently opening the journal
while its adapter owns it; use `prefix`, replay and counters on that adapter.

## Files and selection

| Entry | Meaning |
| --- | --- |
| `transactions.journal` | Exact transaction history, owned by `LocalJournal` |
| `<64-hex-segment-id>.segment` | Immutable content-addressed history segment |
| `pending-<generation>-<UUID>.manifest` | Unselected candidate; ignored during discovery and retained after failure |
| `checkpoint-<19-digit-generation>-<64-hex-manifest-id>.manifest` | Selected immutable generation |
| `.meridiandb-checkpoints.lock` | Cooperating store ownership |
| `.meridiandb-readers.lock` | Permanent shared protection for live lazy readers, independent of store ownership |

Discovery selects the highest positive generation. Every selected filename must
be well formed, and generation numbers must be unique. The selected manifest's
full-object ID, embedded generation, referenced segments and actual journal
prefix must all verify. A damaged newest generation fails closed; discovery does
not silently fall back to an older checkpoint or adopt a pending candidate.
An empty selected namespace returns `Optional.empty()`.

There are at most 16,384 total directory entries and 1,024 selected generations.
These prototype limits bound discovery, alongside the existing manifest and
segment limits. Publication reserves room before forcing dependencies or creating
a candidate, including a reserved slot for the permanent reader barrier when absent.
Store acquisition and segment/compaction creation preserve that slot too, so successful
publication cannot consume the capacity needed for first reader admission.
Reaching a limit requires a separately designed retention or
migration procedure; deleting old files by inspection is not a supported remedy.

## Initialize and synchronize a journal

`initialize(databaseId)` creates an owned `transactions.journal` and publishes
selected genesis generation one at cutoff zero. The existing directory may contain
only this store's lock file. Invalid lineage metadata is rejected before journal
creation; storage failures retain candidates, close the new journal owner and
require reopening the store. The returned journal is owned by the caller and
must be closed separately.

`synchronizeJournal(journal)` validates that owner's canonical path, forces it
through its existing channel, then forces the directory. It establishes the file
and name boundary used before enabling writable database acknowledgements; failure
poisons the store. It does not select a new manifest or replace complete graph
verification. The [local database](local-database.md) recovers and verifies history
before invoking this startup barrier. Use its `create` and `openWritable` APIs for
engine writes rather than treating a primitive force as an engine acknowledgement.

## Publication order

Call `writeSegment` for each new immutable segment. It encodes before path creation,
uses `CREATE_NEW`, completes all writes and forces/closes the file. An existing
path, including an identical object or failed candidate, is never overwritten.
Existing verified segment references can be reused by later manifests.

For `publish(manifest, journal)`, the store:

1. Validates the proposed metadata and verifies the existing highest generation.
2. Requires generation `previous + 1` (initially `1`) and a nondecreasing temporal
   cutoff, with capacity available for the new generation.
3. Verifies every referenced segment and reconstructs the exact journal prefix.
   It compares sequence, byte length and SHA-256 with `journal.prefix(cutoff)`.
4. Forces the journal through its owner, forces each referenced segment, then
   forces the directory to establish dependency names.
5. Creates a unique pending manifest, writes it completely, forces it and closes
   its channel successfully.
6. Atomically renames that candidate to its selected generation name, then forces
   the directory again before returning the `ManifestId`.

Physical generations may increase without advancing the temporal cutoff.
The journal may already contain later transactions; they are outside the selected
checkpoint. `prefix` validates only through the requested committed sequence and
does not force data. Publication's explicit journal force can include a later
appended suffix. No history or request identity is removed.

## Failures and retry

Storage failures poison the store. Close and reopen before another operation;
close itself never publishes or forces. Invalid pure metadata is rejected before
storage work. Generation/cutoff failures discovered while examining storage also
require reopening. A failed candidate and all older files remain available for
inspection.

Once atomic rename has been attempted, an I/O or runtime failure raises
`UncertainPublicationException`: the rename may already have taken effect.
Reopen and verify the highest selected generation to establish visible state.
A visible valid generation after such a failure does not establish that the failed
call completed its final directory force. Do not roll back, overwrite or retry
blindly with a new generation. A failed final force cannot be interpreted as an
unpublished transaction.

The protocol relies on the provider's atomic-move and force behavior and the
filesystem/device honoring it. Tests with process termination exercise reopen
behavior; they do not simulate power loss or establish guarantees for remote
filesystems. [Durability conformance](durability-conformance.md) also exercises
initialization and checkpoint publication with actual database recovery.

The [history compactor](history-compaction.md) proposes the next generation at the
same cutoff with exact original transactions regrouped. `publishCompaction(plan,
journal)` rechecks the selected source ID and actual owner journal, verifies every
existing output byte for byte, reserves all missing-object/candidate capacity and
create-only installs missing segments before using this same publication protocol.
Reused objects are forced too. An unchanged layout still selects the next generation;
all old files and the complete journal remain retained. Physical deletion still
needs reader protection and a separate safety proof. Ordinary `writeSegment` keeps
its existing-path rejection behavior.

## Readers and example

`readLatest(journal)` verifies the complete selected graph before returning a
`LocalCheckpointReader`. Its manifest metadata is immutable. `openSegment(index)`
loads and verifies one referenced segment again and returns a separate heap-owned
reader. The child retains no channel. The parent holds independent shared
[reader protection](reader-protection.md), so it can outlive the store and new
publications. Closing it serializes with lazy opens and can raise `IOException`;
retry close after cleanup failure. Already returned metadata and heap child readers
remain valid. The permanent guard needs one free entry on first admission and actual
shared-lock support; empty selected state creates no guard. Reader close grants no
reclamation permission, and a future remover must hold both exclusive store ownership
and exclusive reader protection before any removal.

Run from the repository root with JDK 25:

```bash
./mvnw -B verify
```

```bash
java --class-path target/classes examples/LocalCheckpointExample.java
```

The example appends two transactions, publishes generation one, closes all owners,
then reopens and compares exact history. It removes only its successful temporary
output. If the initial directory capability is unavailable, it reports that
specific limitation and exits without creating store files. Other failures remain
errors and retain diagnostic files.

Focused checks:

```bash
./mvnw -B -Dtest=LocalCheckpointStoreTest,LocalCheckpointProcessTest,LocalJournalPrefixTest test
```

Coverage includes actual owner-prefix bytes, generation discovery, complete
dependency verification, short/stalled I/O, force/close failures, rename outcomes,
capacity limits, reader lifetimes, hard-link lock preservation and independent
process writer exclusion. A separate process halts immediately after successful
publication and another reopens exact history. Capability-dependent real-file
cases skip only when their required capability is unavailable; injected protocol
checks still run. The same verification and example run in Linux/Windows CI.

Local adapters share file-identity reservations across journal, store and reader
controls and transient data channels. If native channel close throws, its cleanup
remains quarantined even when Java reports the channel closed; a retry cannot prove
native cleanup. Restart the process before reusing that identity. The
[reader-protection guide](reader-protection.md#failure-and-close) explains this
conservative close boundary and its distinction from a retryable release failure.

## Inspect physical retention

Use [reclamation inventory](reclamation-inventory.md) with this store's owned journal
to verify all published generations and explicit retained/backup roots. The newest
root is always retained. Known live-reader contention defers inspection; complete
validation returns immutable obsolete-object metadata without deleting any files.
Candidate byte counts are not a filesystem space receipt. A later remover must
recompute under store ownership and exclusive reader protection.
