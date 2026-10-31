---
title: Compaction and reclamation conformance
description: Verify complete temporal histories, request identities, process recovery and named-file space accounting through actual checkpoint repacking, publication and removal.
---

# Compaction and reclamation conformance

The local pipeline combines [history-preserving repacking](history-compaction.md),
[checkpoint publication](local-checkpoints.md), [reader protection](reader-protection.md)
and [physical reclamation](physical-reclamation.md). Its conformance tests use
actual durable databases and files, then compare recovered answers with the
independent test reference. No storage format, logical retention policy or public
API is changed by this verification layer.

## Evidence layers

| Layer | What it checks |
| --- | --- |
| Original temporal and query fixtures | Literal accepted/rejected operations, corrections, retractions, no-ops, retries, extreme coordinates and pinned cutoffs |
| Persisted seeded histories | Actual writes plus independent backward-history reference and forward dense-state expectations |
| Real maintenance pipeline | Complete source checkpoint, pure plan, installed/published replacement, retained roots, reader deferral and actual obsolete-object removal |
| Read-only and writable reopen | Stored lineage, every historical cutoff, query operators, original request positions and retry behavior |
| Process exits | Complete selected/surviving graphs, original history and retry identity at publication and removal boundaries |
| Space accounting | Exact remaining names and encoded file lengths across publication, retention and removal |

The original five temporal histories provide 84 literal step expectations; the
three query histories add 90. The 16 persisted seeds each produce 32 new transactions.
Named views and `Head` queries resolve at their original step's cutoff. Maintenance
must not make an earlier query observe the final head, or turn an original new
transaction into a fabricated retry result. Later historical sweeps independently
compare the recovered database to the stored original history.

The dense-state oracle applies ordered writes forward to small finite cells;
the independent reference searches history backward. Both remain test-only and
separate from production interval/query implementation. Shared immutable values
and exact fixture decoding do not replace independent expected answers.

## Maintenance and recovery stages

Create and acknowledge real writes before installing a source checkpoint. Preserve
both complete-checkpoint and checkpoint-plus-journal-tail cases. Repack exact
original transaction frames, publish the replacement through the actual store,
and keep an old lazy reader alive across store close where the scenario requires it.
Any such reader defers removal for the entire store. Explicit retained/backup IDs
preserve all of their dependencies until they are omitted from a later fresh call.

After closing the reader and releasing backup retention, reclaim only the verified
obsolete set. Reopen in read-only and writable modes and check temporal queries,
lineage, head and request lookup. Writable identical retries return original positions;
conflicting reuse leaves the head unchanged. Ingestion no-ops remain distinct
commits with their original identities.

A running `LocalDatabase` owns the store and excludes external maintenance. Its
views keep the existing lifetime rules: closing their owning database invalidates
the views. Captured commit positions and already returned immutable values can be
checked again after reopen. Heap segment snapshots can outlive their lazy parent.
These are distinct lifetimes, not a concurrent compactor inside the database.

## Process interruption matrix

The richer-history process matrix complements the earlier primitive fault tests.
It terminates a child JVM while the operating system remains running, then checks
actual remaining files and recovered answers in a new owner.

| Boundary group | Required interpretation |
| --- | --- |
| Replacement dependencies or candidate synchronized, before rename | Old complete checkpoint stays selected; installed objects or a pending candidate remain evidence |
| Replacement rename or final directory synchronization | New complete checkpoint is selected; the full journal preserves acknowledged history |
| Live roots synchronized, before obsolete metadata removal | Original obsolete names remain, with durable retained roots established |
| During metadata retirement, before its directory barrier | Surviving metadata still has every dependency; no segment has been removed |
| After metadata retirement sync, during segment removal | All remaining published roots are complete; unremoved old segments may become preserved orphans |
| Final removal directory synchronization | Completed name removal still requires successful API cleanup for a receipt |

A pending candidate remains unselected evidence and prevents automatic reclamation.
A dependency orphaned by interrupted retirement is retained by fresh inspection;
a prior preview is never reused as deletion authority. Reopen does not repair,
truncate or adopt diagnostic output. The original strict newest-selection and
uncertain-outcome contracts remain in force.

These checks establish process-exit recovery under the required filesystem
capabilities. They do not simulate loss of kernel caches, device power loss,
uncooperative writers or hostile path mutation. File/directory force still depends
on the provider and storage device. Unsupported required directory-sync capability
is reported separately from ordinary I/O or permission failure.

## Named-file space accounting

Measure complete regular-file lengths and exact filename sets at controlled stages.
Metadata-only attribute reads can observe a retained-reader stage without opening
file data channels. Capture whole-byte snapshots only after all owners close.
Keep journal bytes, control bytes and diagnostic evidence separate from
manifest and segment totals. Do not open raw journal or guard handles while their
adapters/readers still own them: closing another same-file channel can compromise
JVM-wide process locks.

Publication can temporarily increase retained bytes because both representations
exist. Retaining a backup root can prevent segment deletion. After successful
removal, the exact reduction in summed encoded lengths must match
`ReclamationResult.removedBytes`, and the missing names must match the immutable
receipt. Repeat reclamation to check that it is a fresh empty operation rather
than another deletion of an old list.

These lengths are not allocated filesystem blocks or guaranteed freed capacity.
No throughput, latency, query-speed or recovery-speed result follows from a smaller
encoded representation. The complete journal and every logical transaction remain
retained. Namespace, inspection and long-lived-reader limits remain as specified
by the component guides.

## Measured fixture result

Checked October 31, 2026 with Java 25 on macOS/arm64. Dataset `pipeline-12-v1`
contains 12 durable transactions, checkpoint cutoff eight and four journal-tail
transactions. It includes corrections, retractions, no-ops, extreme signed values
and request retries. Three layouts use the same logical history:

| Layout | Source → replacement segments | Input segment bytes | Output segment bytes |
| --- | --- | ---: | ---: |
| Combine, default target | 8 → 1 | 2,022 | 1,126 |
| Split, 512-byte target | 1 → 3 | 1,126 | 1,382 |
| Unchanged, default target | 1 → 1 | 1,126 | 1,126 |

The following cells show **total regular-file bytes / number of files**. They
include the unchanged 1,518-byte journal, two empty control files, a 33-byte note
and a deliberately unreferenced three-byte diagnostic segment. The diagnostic
object is preserved throughout and is counted in segment-shaped filenames.

| Stage | Combine | Split | Unchanged |
| --- | ---: | ---: | ---: |
| Baseline, durable journal and genesis | 1,714 / 6 | 1,714 / 6 | 1,714 / 6 |
| Source checkpoint installed | 4,344 / 15 | 3,056 / 8 | 3,056 / 8 |
| Replacement published; old files retained | 5,686 / 17 | 4,766 / 12 | 3,272 / 9 |
| Cleanup with source retained for backup | 5,526 / 16 | 4,606 / 11 | 3,112 / 8 |
| Cleanup after releasing backup retention | 2,896 / 7 | 3,264 / 9 | 2,896 / 7 |

Backup-retained cleanup removes only the 160-byte genesis manifest in each layout.
Final cleanup receipts remove 2,630, 1,342 and 216 encoded bytes respectively,
matching the exact differences between the last two stages. The unchanged layout
reuses its complete segment and removes only obsolete metadata. The split layout
has more envelope overhead, so its final encoded footprint exceeds the combined
one despite preserving identical answers.

The [structured category totals](evidence/compaction-space-v1.json) retain all five
stages and receipt byte totals. The test emits `SPACE_ACCOUNTING_JSON` rows for direct
reproduction. These are deterministic fixture lengths, with no general compression,
throughput or freed-block claim.

## Executed matrix

The integrated suite adds **46 invocations**:

- **16 literal-history cases:** all eight histories in full-checkpoint and
  checkpoint-tail shapes; 348 literal steps through real storage and the same
  348 steps through the independent reference. Forty-eight actual new writes each
  complete a source-publication, replacement-publication and removal cycle.
- **16 seeded cases:** 512 new durable writes and 32 maintenance cycles. At head
  16, checkpoint cutoff eight is repacked with a 1,024-byte target; at head 32, the
  complete checkpoint uses the default target. All available historical cutoffs
  are checked at each stage, including every cutoff zero through 32 at the end,
  with read-only and writable reopen, dense/reference queries and request identity.
- **Three layout cases:** exact five-stage names, lengths and receipts, nonempty
  source-reader deferral, explicit backup retention, returned heap snapshots and
  complete selected graphs.
- **Eleven child-process exits:** four publication boundaries and seven removal
  stops, including first/last object removals. The richer 12-transaction history
  checks 259 query cases at each of 13 cutoffs, original request positions and
  retries, exact output/pending images and IDs, and diagnostic retention.

The original fixture/seed corpora, storage formats, production code and runtime
dependencies remain unchanged. Together with the component fault/lifetime tests,
this matrix supplies the local compaction/reclamation correctness evidence. It is
bounded conformance coverage, not a proof for every history or storage device.

## Reproduce

Use Java 25 and the pinned Maven Wrapper from the repository root:

```bash
./mvnw -B -Dtest=LocalCompactionFixtureConformanceTest,LocalCompactionPropertiesConformanceTest,LocalCompactionPipelineConformanceTest,LocalCompactionPipelineCrashTest test
```

The default verification command remains:

```bash
./mvnw -B verify
```

The source fixture and persisted seed corpus remain authoritative; reproduction
must keep their IDs and original expectations. The existing thirteen embedding
examples exercise the component APIs; the integrated test suite supplies broader
semantic and failure evidence without adding a new production API.
