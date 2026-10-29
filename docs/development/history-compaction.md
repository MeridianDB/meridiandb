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

The pure compactor creates no files and changes no selected generation. A source
resolver supplies complete existing byte images. `LocalCheckpointStore.publishCompaction`
now installs a plan after rechecking the selected source and actual owned journal,
then publishes its replacement. Both APIs retain all old files and the complete
journal. Physical reclamation still requires reader protection; a plan alone is
neither a force receipt nor permission to remove old objects.

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
all content IDs while proposing another generation. The local publisher verifies
existing candidates and explicitly publishes the next generation even when every
segment reference is unchanged. It does not automatically schedule compaction;
callers should consider generation capacity before requesting repeated repacks.
A corrupt file bearing an expected content name is never a valid reusable object.

## Install and publish locally

Close the `LocalDatabase` owner before acquiring the store and its journal.
Compaction uses their existing exclusive ownership, so a cooperating engine writer
cannot run concurrently. A plan may be prepared earlier, but publication accepts
only the exact currently selected source manifest ID.

```java
try (var store = LocalCheckpointStore.open(directory);
     var journal = LocalJournal.open(store.journalPath())) {
    var publishedId = store.publishCompaction(plan, journal);
}
```

`LocalJournal` is in `org.meridiandb.storage.journal`. The publisher:

1. Discovers the highest generation and rejects a stale source ID before reading
   an unrelated graph. Verifies the selected source's complete stored graph and
   exact prefix through the actual journal owner at `transactions.journal`.
2. Checks every already present replacement object against its complete expected
   encoded bytes. Reuse requires a regular, nonsymlink file that aliases neither
   the journal nor the store lock. A corrupt, truncated or wrong same-name image
   fails closed, even if later installation could otherwise succeed.
3. Reserves capacity for **all missing segments plus one manifest candidate** and
   one retained generation before creating the first output. Existing source,
   unrelated and failed files all count toward the directory limit.
4. Creates only missing objects, fully writing, forcing and closing each. Verifies
   the complete replacement graph and actual owner prefix again.
5. Uses the shared [checkpoint publication protocol](local-checkpoints.md#publication-order):
   force journal, force every referenced segment including reused objects, force
   dependency names, force/close a new manifest candidate, atomically rename it,
   then force the directory. The checkpoint cutoff and journal contents are unchanged.

Success returns the replacement `ManifestId`. Genesis and unchanged layouts also
publish source generation plus one. An existing output is never overwritten or
repaired. Storage-discovered failures poison the store until close/reopen; once
atomic rename is attempted, I/O/runtime failure is `UncertainPublicationException`.
Reopen to discover the highest complete valid generation, without falling back
from a damaged newest graph or promoting an unselected candidate. Failure retains
all old files and any partially installed output for inspection. Ordinary
`writeSegment` remains create-only and still rejects an existing path.

Source verification, replacement verification and publication perform repeated
bounded reads/encoding; the plan's byte totals are not total I/O or peak heap use.
This is an explicit whole-checkpoint operation, with no automatic scheduling,
journal truncation, fast-recovery or throughput claim. The filesystem/provider and
force assumptions of local checkpoint publication apply equally to reused objects.

## Physical reclamation boundaries

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
protocol is implemented by either compaction API.

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
These pure checks establish history preservation independently of local publication.
They do not establish reclamation, crash safety of deletion or query performance.

Run the local installation/reuse/reopen example after verification:

```bash
java --class-path target/classes examples/LocalCompactionExample.java
```

It publishes a two-to-one replacement at cutoff two, verifies unchanged-layout
reuse in generation three, then reopens exact history. It reports unsupported
directory open/force separately, removes only its successful temporary output
after all handles close, and retains failed-run evidence. Neither storage API
reclaims objects.

```bash
./mvnw -B -Dtest=LocalCompactionPublicationTest,LocalCompactionIntegrationTest,LocalCompactionCrashTest test
```

The 51 local checks (34 injected protocol, 13 real integration and four child-JVM
exit cases) cover verified reuse, corrupt same-name candidates, source/journal
binding, whole-installation capacity, fault poisoning and uncertain rename outcomes.
Real-file checks retain old lazy readers, exclude engine writers and reopen exact
history. Child JVMs halt at the four dependency/candidate/rename/directory boundaries
and verify the selected old or new complete graph with journal-tail recovery and
original request retries. These process exits do not simulate device power loss.
Full canonical/seeded publication with protected physical removal and retained-space
accounting still requires a reclamation implementation.
