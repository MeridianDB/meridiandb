---
title: Object checkpoint graph verification
description: Verify complete namespace-scoped checkpoint graphs against the actual owned journal, with shared object, prefix and record-work limits.
---

# Object checkpoint graph verification

`ObjectCheckpointGraphs` is a read-only JDK-only consumer of
[`ImmutableObjectStore`](immutable-objects.md) and an existing, exclusively owned
[`LocalJournal`](local-journal.md). It verifies complete manifest/segment history
and compares its reconstructed prefix with the actual journal bytes. It can
verify one supplied head or an adjacent predecessor/candidate pair under one
shared operation budget.

This is verification evidence. It does not observe or select a remote head,
authenticate its provenance, establish writer admission, force data, persist a
publication intent or permit reclamation. The separate [complete checkpoint publisher](s3-checkpoint-publication.md) now
composes these checks with durable intent and exact-candidate reconciliation. [ADR 0025](../decisions/0025-object-checkpoint-graph-verification.md)
records the boundary.

## Configuration and operations

The class lives in `org.meridiandb.storage.manifest`. Construct it with the object
adapter, owned journal and independently configured non-nil namespace/publisher
UUIDs. Expected lineage comes from the journal's canonical database header,
preserving the existing exact UTF-16 identity domain. Neither supplied resource
becomes verifier-owned; the caller manages both lifetimes and supported placement.

| Operation | Evidence |
| --- | --- |
| `verify(head, limits)` | One complete graph and its actual journal-prefix comparison; no ancestry or selection proof |
| `verifySuccessor(previous, candidate, limits)` | Exact adjacent metadata transition plus both complete graphs and actual prefix comparisons, in predecessor order |
| `loadHistory(head, limits)` | Original immutable records from one completely verified pinned graph and actual prefix; no current-selection or engine proof |
| `manifestKey(namespace, objectId)` | Reserved flat `ns-N-manifest-H.bin` name |
| `segmentKey(namespace, segmentId)` | Reserved flat `ns-N-segment-H.bin` name |

`N` is the namespace's 32 lowercase hexadecimal digits; `H` is the complete
object's SHA-256. The [head protocol](../architecture/object-checkpoint-protocol.md)
owns naming and transition rules. Manifest generation, head revision and temporal
cutoff remain distinct. An unchanged cutoff may use a different physical layout.

For independently configured variables and an already installed graph:

```java
var verifier = new ObjectCheckpointGraphs(objects, journal, namespace, publisher);
var checked = verifier.verifySuccessor(previous, candidate,
        ObjectCheckpointGraphs.DEFAULT_LIMITS);
var candidateGraph = checked.graphs().getLast();
var actualPrefix = candidateGraph.actualPrefix();
var usage = checked.usage();
```

The `Verification` and `Graph` constructors are private. Results contain immutable
head/manifest metadata, the complete head reference and actual prefix observations.
They retain no segment records, object image arrays or open object readers. The
graph list is immutable and remains readable after resource close. It is evidence
from that operation, without a continuing lease, force guarantee or current-head
token. Physical endpoint/bucket scope and durable expected/pending state belong
to [preparation](s3-publication-preparation.md) and the complete publisher, rather
than being inferred from this generic byte adapter.

## Load pinned history

`loadHistory` materializes original transactions for a supplied pinned head during
the **same complete verification pass**. It collects privately, closes each object
reader, verifies all graph/history checks and compares the actual owned prefix
before returning. There is no verification-then-refetch gap or public replay visitor.

```java
var history = verifier.loadHistory(head, ObjectCheckpointGraphs.DEFAULT_LIMITS);
var originalRecords = history.records();
var checkedGraph = history.graph();
var usage = history.usage();
```

The private-constructed `History` contains an immutable list of original ordered
`JournalRecord` values and the same `Graph`/`Usage` evidence as one `verify` call.
Original request identities, operation order, no-op transactions and exact UTF-16
values remain intact. Genesis returns an empty list. A valid older pinned head can
be loaded against a complete matching journal, but the result contains no records
after its checkpoint cutoff. The loader neither reads the remote current-head key
nor establishes that the supplied head is current.

Records are retained heap values, independent of source arrays and resource close.
The loader owns and closes none of the supplied adapters or journal. Existing
per-operation limits and typed failures apply: every graph record and actual-prefix
record is charged, and each manifest/segment is fetched and decoded once per graph.
Missing/corrupt late dependencies, divergent actual history or adapter cleanup
failure return no `History` and no usable partial list. No force, write or repair
is performed. Metadata-only `verify` and `verifySuccessor` retain no record list.

Materialization retains all checkpoint records and a defensive list copy in
addition to verification's request-ID set and decoded objects. The record and
encoded-byte limits bound work/cardinality, not exact heap, CPU or total recovery
costs. Initial journal opening, full tail replay and eventual engine state remain
outside this operation. The result is evidence about the actual owner at load
time, without a continuing lease or proof for another reopened journal.

The [core read-only recovery factory](object-checkpoint-recovery.md) now loads
within owned startup, validates complete journal ordering and applies only the
contiguous suffix. [Managed S3 read recovery](s3-database.md) is available separately; managed S3 writable recovery remains planned.
A checkpoint alone cannot detect or reconstruct fully lost uncheckpointed local
transactions. See [ADR 0030](../decisions/0030-bounded-pinned-object-history.md).

## Complete verification sequence

1. Validate independent bindings and requested cutoffs. For a pair, require the
   exact predecessor image, next revision and noncontradictory prefix coordinates.
   Reserve both heads' manifest fetches/bytes, declared actual-prefix bytes and
   record work before the first object read.
2. Fetch both complete manifest images, close their heap readers and bind their
   exact identity, length, lineage and prefix declarations. Reserve every segment's
   declared bytes and logical fetch before reading any segment or actual prefix.
3. Verify each complete graph, predecessor first. Fetch each segment once for that
   graph, close its reader, then check its expected header range/count before codec body
   hashing or record allocation/decoding. Reuse the existing whole-history verifier
   for lineage/ranges, cross-segment request uniqueness and original-prefix
   reconstruction. There is no second fetch or decode pass.
4. Call `journal.prefix(cutoff, declaredPrefixBytes)` through the existing owner.
   Compare sequence, exact length and SHA-256. A header/frame that would exceed
   the remaining prefix cap is stopped before its body is allocated/read. Other
   valid but different histories fail the final comparison.
5. Return only after all requested graphs, journal comparisons and reader cleanup
   succeed. Missing/corrupt dependencies never cause fallback to an older graph.

A pair reserves and verifies shared dependencies twice. There is no cache that
silently exempts repeated work, no LIST-based discovery and no ancestry traversal.
The selected graph's failure prevents candidate segment reads and exposes no
partial verification. Both bounded manifest images may already have been fetched.

## Operation limits and usage

`Limits` accepts zero through each hard maximum below. `DEFAULT_LIMITS` uses these
maxima. Smaller policies are useful for a caller's workload and available memory.

| Field | Default and hard maximum | Accounting |
| --- | ---: | --- |
| `maxObjectBytes` | 256 MiB | Complete manifest and segment images fetched, including repeats |
| `maxJournalBytes` | 256 MiB | Sum of actual-prefix byte caps, including each lineage header |
| `maxRecordWork` | 1,000,000 | Each graph record plus each actual-journal record: `2N` per graph |
| `maxObjectReads` | 8,194 | Logical immutable fetches: one manifest plus its segments per graph |

These prototype policy maxima are independent of the wider wire-format limits;
they are not throughput measurements. Subtraction-based reservations reject an
operation before it exceeds the remaining aggregate budget. A segment's exact
expected range prevents a header claiming many records from spending a smaller
manifest-declared record reservation.

Successful `usage()` reports `objectBytes`, `journalBytes`, `recordWork`,
`objectReads` and `journalReads`. The last is one or two complete prefix operations,
not a count of native short reads. Record work bounds history/request-set
cardinality; encoded bytes also bound the supplied identity text. It is not an
exact count of internal validation loops, CPU instructions or JVM heap bytes.

The counters exclude initial journal-open recovery, caller-retained data, supplied
head images/serialization, byte-buffer copies, SDK resources and retries in a
different invocation. Existing individual codec/adapter limits still apply.
Callers must separately manage aggregate work across repeated invocations and
resource lifetimes. A budget rejection proves nothing about unread history.

## Failures and remaining boundaries

`ObjectCheckpointGraphException.code()` distinguishes aggregate `LIMIT_EXCEEDED`,
complete-graph `INVALID_GRAPH` and `JOURNAL_MISMATCH`. Codec failures wrapped as
invalid graphs retain their cause. Pure head binding/transition checks retain
`ObjectCheckpointHeadException`; invalid configuration and null arguments use
ordinary Java argument/null exceptions. Adapter I/O errors propagate as failures,
including missing/denied objects and cleanup failures; they are never absence or
permission to reuse another graph.

An actual prefix exceeding its declaration becomes `JOURNAL_MISMATCH` with the
original `LocalJournal.PrefixLimitException` cause. That policy rejection leaves
a healthy journal usable. Observed journal corruption/I/O keeps the existing
poisoning behavior and failure type. A valid but different history fails without
claiming that the journal itself is damaged.

Equal lineage alone cannot validate copied stores with divergent histories.
Comparing both graphs with the same owner rejects observed divergence, including
a predecessor that disagrees even when the candidate matches that local journal.
This does not detect every unseen copied writer, external rollback or deletion,
and supplies no distributed fencing. The [publication protocol](../architecture/object-checkpoint-protocol.md#failure-and-retry-outcomes)
still requires durable expected/pending intent and reconciliation before selection.
The optional [state codec](s3-publication-state.md) now represents that metadata
separately from [durable local installation](local-s3-publication-state.md).
[Verified preparation](s3-publication-preparation.md) now composes those boundaries
without conditional submission or reconciliation. The separate
[verified publisher](s3-checkpoint-publication.md) now implements those stages.

The 34 graph/actual-prefix cases use independent histories, bounded fake/native-read
seams and actual local journal/object adapters. Sixteen checked-range codec cases
verify preflight and complete-decoding behavior. Four optional SDK tests exercise
the same graph consumer over controlled HTTP object access. They qualify neither a live cloud provider nor
remote durability, recovery, replication or throughput. Run the core and optional
builds as described in the [build guide](build.md) and [S3 guide](s3-objects.md).

The history loader adds 22 core cases and seven SDK/HTTP cases: exact original
records and pinned cutoff, immutable lifetime, one fetch per dependency, exact
budgets, foreign/divergent/short actual history, late object/cleanup failures and
early header-range rejection. These verify history materialization, rather than
engine reconstruction, writable retries or live-provider qualification.

```bash
./mvnw -B -Dtest=ObjectCheckpointHistoryTest,ObjectCheckpointGraphsTest test
```
