---
title: Bounded local checkpoint history
description: Acquire immutable original checkpoint records during one bounded local graph verification, with actual owner-journal prefix checks and explicit ownership, memory and failure limits.
---

# Bounded local checkpoint history

`LocalCheckpointStore.loadLatestHistory(journal, limits)` returns the original
checkpoint records retained during complete graph verification. It fetches and
decodes each selected segment once, then verifies the actual owned-journal prefix
before exposing an immutable heap value. The experimental API prepares a bounded
recovery comparison; existing `LocalDatabase` opens and lazy checkpoint readers
continue to use their current paths.

The [attributed Linux baseline](linux-attributed-observations.md) found that
checkpoint writable reopen reads each selected segment during verification and
again during checkpoint replay. Reusing already verified records can avoid the
second fetch/decode. Whether the extra retained list improves end-to-end resource
use still requires a separate recovery integration and measured comparison.
[ADR 0048](../decisions/0048-bounded-local-checkpoint-history.md) records that scope.

## Acquire a heap-owned history

Use the existing [local store's directory and ownership requirements](local-checkpoints.md#directory-and-ownership-requirements).
The supplied journal must be the real owner of this store's `transactions.journal`
path. Acquire and close both owners explicitly:

```java
try (var store = LocalCheckpointStore.open(directory);
     var journal = LocalJournal.open(store.journalPath())) {
    var limits = new LocalCheckpointHistory.Limits(
            8L * 1024 * 1024, 8L * 1024 * 1024, 32_768);
    var selected = store.loadLatestHistory(journal, limits);
    if (selected.isPresent()) {
        var history = selected.orElseThrow();
        System.out.println(history.manifestId());
        System.out.println(history.records().size());
        System.out.println(history.usage());
    }
}
```

No selected generation returns `Optional.empty()`. A selected genesis returns a
history value with an empty record list and a verified journal-header prefix.
An older checkpoint may precede the journal head: the result covers that selected
checkpoint only, not the uncheckpointed tail or an already recovered engine.

The result exposes the selected manifest ID/metadata, actual prefix, original
`JournalRecord` list and successful encoded-work usage. It preserves request IDs,
new no-op transactions, corrections and retractions. It introduces no replacement
encoding or rewritten journal frames.

## Admission and integrity

The method selects the same highest uniquely named generation as the ordinary
reader. A damaged newest manifest or dependency fails closed, without fallback to
an older generation or adoption of a pending candidate.

`Limits(maxObjectBytes, maxJournalBytes, maxRecordWork)` uses these three budgets:

| Limit | Charged work | Maximum |
| --- | --- | --- |
| Object bytes | Selected manifest bytes plus every declared segment image | 64 MiB |
| Journal bytes | Actual prefix scan through the supplied journal owner | 64 MiB |
| Record work | Checkpoint records plus actual prefix records, reserving twice the selected cutoff | 262,144 |

Zero is valid for each limit. `Limits.DEFAULT` selects the stated maxima; callers
must still choose a limit explicitly. A nonempty selected manifest cannot fit a
zero object budget. The existing directory/generation, manifest/segment and codec
limits also apply. Logical object reads are bounded by the manifest's segment
count; they are not native `FileChannel.read` call counts.

Metadata image buffers are capped by the remaining object budget before allocation.
After metadata decoding, lineage and all declared segment bytes, journal bytes
and record work are admitted before the first dependency read. Each dependency
body buffer is capped at its exact declared length. The existing one-byte EOF
probe still checks for file growth: a failing growth probe may return one extra
byte per attempted object before rejection. Object limits and successful usage
describe encoded images, not a strict total-native-byte cap on failed reads.
Range-aware decoding checks the
expected first/last sequence before body hashing or record allocation, while
retaining whole-object identity, checksums and complete decoding.

Complete graph verification checks lineage/ranges, cross-segment request identity
and the reconstructed journal-prefix length/hash. Original records stay private
until this completes and the actual owner-journal prefix matches. That actual
scan uses the already admitted declared prefix length as its own byte cap, so a
different longer journal image cannot silently consume the caller's spare budget.

`Usage.objectBytes` includes the manifest and dependencies once;
`journalBytes` reports the successful prefix length, `recordWork` twice its cutoff
and `objectReads` one manifest plus the selected segment count. These values
describe successful logical encoded work. They exclude the earlier
`LocalJournal.open` scan and later engine rebuilding, tail replay or synchronization.

## Lifetimes and failures

Store ownership excludes participating reclamation throughout acquisition. Reads
retain the existing no-follow, regular-file, control-alias and native identity
reservation checks. Every transient file handle must close successfully before
verification returns. The caller keeps ownership of its journal and store.

The successful value owns no channels, shared reader protection or native memory.
Its immutable records survive owner closure and later removal of the source
objects. It is a point-in-time heap value, not evidence that files still exist or
permission to publish, retain or reclaim them. Fresh calls reselect and reverify
stored bytes; ordinary lazy readers still reread and validate their dependencies.

Acquisition errors, including an admitted-call budget rejection, poison the
checkpoint store under its existing fail-closed policy. Close and reopen the
store before another operation. Invalid/null arguments are rejected before
acquisition; a bounded-prefix policy rejection does not by itself poison the
separately owned journal. No partial record list is returned on a late graph,
prefix, I/O, runtime or cleanup failure.

Encoded-work bounds are not an exact heap budget. Retained records, identity sets,
decoded strings and list references have additional costs; separate calls and
escaped history values accumulate separately. A future recovery consumer should
release this transient list after replay. The current engine still reconstructs
full historical state and copies maps during new-commit preparation.

The heap-only path needs no continuing reader barrier, so recovery integration
would also change reader-admission force counts compared with a lazy reader.
Compare that effect explicitly while preserving writable startup journal/name
synchronization. Correct acquisition alone establishes no latency, allocation,
device-I/O or production improvement.

## Verification

From the repository root:

```bash
./mvnw -B '-Dtest=LocalCheckpointHistoryTest,LocalCheckpointHistoryIntegrationTest' test
```

The 38 checks include 27 platform-neutral schema/budget/failure cases and eleven
native acquisition/lifetime cases. Native cases require the store's directory
capabilities; Windows reports typed unsupported directory synchronization
separately. Ordinary storage or permission failures are test failures.

A literal four-record/two-segment fixture has a 266-byte manifest, two 278-byte
segments and a 354-byte journal prefix. With owners opened outside capture,
acquisition records exactly 266 manifest, 556 segment and 354 journal read bytes,
zero writes/forces, and `Usage(822, 354, 8, 3)`. Ordinary reader verification plus
lazy replay still reads 1,112 segment bytes. These are checked correctness
vectors, not recovery performance results.

Tests cover exact and one-below limits, zero/genesis/absent selection, corrupt
newest state, range/payload traps, cross-segment request reuse, late prefix and
close failures, bounded EOF growth probes, native journal aliases and caller
ownership. The heap value survives actual protected reclamation of old files;
fresh acquisition and ordinary lazy readers continue to observe changed storage.
