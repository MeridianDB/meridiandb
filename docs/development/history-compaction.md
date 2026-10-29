---
title: History-preserving compaction
description: Repack verified checkpoint history into bounded immutable segments while preserving every transaction, temporal cutoff and request identity, with explicit publication and reclamation limits.
---

# History-preserving compaction

`HistoryCompactor` produces a pure proposal for a complete checkpoint prefix.
It verifies the supplied graph and repacks its **original ordered transactions**
into new immutable history segments. Corrections, overwritten values, retractions,
no-op commits and request payloads remain intact. This preserves the logical
history rather than keeping only the latest value for each entity.

The implemented compactor creates no files, changes no selected generation and
deletes nothing. A source resolver supplies complete existing byte images. Actual
journal verification, replacement installation, durable publication and reader-safe
physical reclamation need separate local integration. A plan is neither a force
receipt nor permission to remove old objects.

## Retention and physical layout

| Object | Current rule |
| --- | --- |
| Logical transactions and request identities | Retain every original commit and payload; every earlier cutoff remains supported |
| Journal | Preserve the complete journal and exact prefix binding; no truncation |
| Compaction output | Group or split only between whole transaction frames; keep original sequence and operation order |
| Physical generation | Propose source generation plus one while retaining the same temporal cutoff and journal prefix |
| Old segments, manifests and failed candidates | Remain retained; pure compaction cannot grant reclamation permission |
| Open views and heap segment readers | Existing immutable values/lifetimes remain unchanged |
| Lazy checkpoint readers | May still require their old files after the store closes; no physical deletion is currently safe on the basis of store close alone |

Default logical retention is unchanged. Deleting old versions of an entity or
expiring request IDs would require a separate explicit retention decision and
compatibility contract. Fewer physical segments do not reduce the number of
logical commits, and a new generation does not advance database knowledge.

## Construct a plan

Given a `CheckpointManifest` and a stable `CheckpointVerifier.SegmentSource`:

```java
var plan = HistoryCompactor.compact(manifest, source, HistoryCompactor.Limits.DEFAULT);
var replacement = plan.replacementManifest();
var outputSegments = plan.segments();
```

The types are in `org.meridiandb.storage.manifest`; output elements are
`org.meridiandb.storage.segment.ImmutableSegment`. A source returns one complete
image for a referenced `SegmentId`, or null when missing. Keep its bytes stable
during verification. Each referenced image is requested once, without a separate
resolve-again pass that could substitute different bytes.

The plan contains immutable source metadata, its manifest ID, replacement metadata,
ordered immutable output segments and encoded input/output segment-byte totals.
Returned values do not alias the supplied image arrays. All work remains private
until verification of the complete source succeeds. A malformed late object or
incorrect final prefix hash prevents any plan from being returned.

## Validation and packing

1. Validate source metadata against manifest format limits. Reject exhausted
   physical generation, excessive declared aggregate input bytes or too many
   history records before calling the source.
2. Verify every expected segment ID, complete image, byte length, lineage and
   range. Require ordered complete coverage from sequence one through the declared
   cutoff, globally unique stored request IDs, and the exact reconstructed journal
   prefix length/hash. This supplies consistency evidence for the supplied graph;
   it does not compare a separately stored journal.
3. Pack original records greedily in order. Add a whole record to the current
   segment when its complete encoded size and record count fit; otherwise finish
   that group and start another. Input segment boundaries do not dictate output
   boundaries. Never split a transaction, remove a no-op or normalize retry fields.
4. Check each output allocation against its aggregate byte budget and manifest
   reference-count limit. Compute each full-object ID from the encoded segment.
5. Return a replacement at source generation plus one with exactly the same
   database ID and journal prefix. Genesis produces no segments and preserves
   cutoff zero. Validate replacement metadata before the plan escapes.

The target is a **hard whole-image limit**, including lineage/header/footer bytes.
An indivisible transaction that cannot fit causes `LIMIT_EXCEEDED`; the compactor
never silently exceeds the target. `SegmentCodec.encodedBytes(segment)` computes
the exact complete v1 size with encoding validation, without allocating an output
image. Grouping uses encoded frame sizes rather than repeatedly encoding a growing
prefix. The existing v1 segment and manifest byte formats remain unchanged.

## Resource limits and failure

| Limit | Default | Hard maximum |
| --- | --- | --- |
| Target bytes per output segment | 32 MiB | 32 MiB |
| Aggregate encoded input segment bytes | 64 MiB | 64 MiB |
| Aggregate encoded output segment bytes | 64 MiB | 64 MiB |
| Total history records | 262,144 | 262,144 |
| Records per segment | 65,536 | 65,536 |
| Output segment references | 4,096 | 4,096 |

Defaults bound the initial whole-checkpoint implementation; they are not tuned
throughput settings. `Limits(targetSegmentBytes, maxInputBytes, maxOutputBytes,
maxRecords)` can reduce budgets. The target must be positive; other budgets may be zero, which permits a
valid genesis but no nonempty history. Invalid limits are constructor errors.
Resource exhaustion and generation exhaustion use
`ManifestFormatException.Code.LIMIT_EXCEEDED`. Existing graph/codec corruption
errors remain explicit, and source `IOException` propagates. There is no partial
plan or automatic fallback to a smaller history prefix.

These budgets count serialized segment bytes and records, **not exact JVM heap
bytes**. A source may allocate its own image; the verifier decodes one input at a
time, keeps the request-ID set and retains all output record values. Output encoding
also needs temporary arrays. Metadata, decoded object overhead, nested operations,
resolver memory and result lists add costs. This is a bounded whole-checkpoint
prototype, not streaming compaction for arbitrarily large databases.

## Space accounting and deterministic results

`inputBytes()` sums referenced encoded segment lengths; `outputBytes()` sums
replacement encoded lengths. Both exclude manifest, journal, lock files,
filesystem allocation and any retained old objects. A smaller target can increase
segment count and encoded overhead. No disk space is reclaimed by this calculation.

The same history and target produce the same segment bytes/IDs regardless of input
grouping or physical generation. Repacking an already matching layout can reuse
all content IDs while proposing another generation; local integration must verify
existing candidates before reuse and may choose not to publish an unchanged layout.
A corrupt file bearing an expected content name is never a valid reusable object.

## Publication and reclamation boundaries

The [local checkpoint protocol](local-checkpoints.md) already verifies the actual
owned journal, forces dependencies and names, then selects a complete immutable
generation by atomic rename and final directory force. Local compaction still needs
to install or verify/reuse its outputs under that ownership and recheck the source
generation before publication. Existing `writeSegment` is create-only: an existing
path is not an implicit successful reuse. All prior files remain retained.

Physical reclamation needs a separate protection protocol. Lazy reader admission
must select and pin its generation under cross-process coordination that excludes
deletion. A reader's protection must outlive the store, and release must serialize
with its in-progress lazy opens. Selected/retained generations, active lazy-reader
pins and explicit backup roots determine which dependency graphs remain live.
Heap-owned child readers do not need their old file names to keep returned values.

Timers, PID observations and a process-local registry alone cannot establish that
an old generation is unused. An uncertain pin or removal outcome requires retention.
Obsolete metadata must be safely retired before its now-unreferenced dependencies;
newest selected state must remain verifiable after interruption. No such deletion
protocol is implemented by the pure compactor.

## Verify and run the example

```bash
./mvnw -B -Dtest=HistoryCompactorTest,CompactionConformanceTest,SegmentCodecTest,CheckpointVerifierTest test
```

```bash
java --class-path target/classes examples/CompactionExample.java
```

The example merges two one-record images in memory, checks exact history and prefix
preservation, verifies the replacement graph and prints encoded byte accounting.
It creates, publishes and deletes no files. Its checked output is:

```text
Source/repacked segments: 2 / 1
Encoded segment bytes: 430 / 302
Proposed generation: 8; unchanged temporal cutoff: 2
```

The 128-byte difference is eliminated repeated segment overhead in this small
example, not reclaimed filesystem space. The full verification command remains
`./mvnw -B verify`.

Boundary checks cover whole-image exact fits, the record-count cap, aggregate
budgets, generation exhaustion, corrupted/missing/late input and immutable ownership.
All eight original/query fixture histories execute their 174 literal expectations
before and after repacking. Rebuilt execution consumes actual compactor output;
independent reference and dense seeded checks cover historical queries and retries.
Reproduce a reported seed with `-Dtest=CompactionConformanceTest -Dmeridiandb.seed=42`.
These checks establish pure history preservation, not local compaction publication,
reclamation, crash safety of deletion or a query-performance improvement.
