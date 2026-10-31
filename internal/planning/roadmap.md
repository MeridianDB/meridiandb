---
title: Java and Maven implementation roadmap
description: Build MeridianDB from explicit bitemporal semantics through a tested Java reference model, durable storage, query execution and measured prototype readiness.
---

# Java and Maven implementation roadmap

This is the technical plan for the first embedded, single-node prototype. Temporal
types, the test reference, memory-only engine, local journal and immutable segment
I/O, manifest consistency, checkpoint publication, recovery and forced local writes
are implemented; process crash conformance and later outcomes remain planned. The
[live queue](queue.md) owns
current order and state; the [workflow](index.md) explains how an agent expands,
executes, commits and pushes one bounded outcome.

## Proposed implementation structure

Begin with one Maven artifact and clear package boundaries. Add modules when an
actual second deliverable or independently useful boundary needs one. Keep the
initial engine independent of a mandatory application server or service framework.

| Area | Responsibility |
| --- | --- |
| API and value types | Entity/value identity, valid intervals, commit positions, commands, queries and explicit lifetimes |
| Temporal semantics | Corrections, retractions, interval splitting and historical visibility |
| Engine | Transaction ordering, commit visibility, read views and lifecycle |
| Storage | Journal, immutable segments, manifests, checksums and backend capabilities |
| Query execution | Logical operations, temporal filtering and later physical access paths |
| Test reference model | A simpler independent interpreter and reusable histories/expected answers |
| Experiments | Reproducible workloads and evidence for layout/index decisions |

These responsibilities do not freeze module boundaries or a public API. The
single artifact contains temporal types and the [memory-only engine](../../docs/development/embedded-engine.md),
with reference support in tests under the [build foundation](../../docs/development/build.md).
The journal and immutable segment codecs have local file adapters. Manifest
metadata, referenced-object consistency, local publication, recovery and forced
write publication are implemented; crash conformance and measurements remain planned.
Read the [architecture overview](../../docs/architecture/overview.md) and
[language strategy](../../docs/decisions/0001-language-strategy.md) before changing them.

## MDB-0001 Planning workflow

**Outcome:** a durable implementation plan and expandable queue that a fresh agent
can follow without remembered conversation.

**Complete when:** the workflow, live queue, roadmap and progress receipt exist;
README, agent rules and site navigation agree with them; documentation and queue
consistency checks pass; the completion commit is pushed under the owner's source
handoff instruction. Actual delivery evidence comes from Git and the final handoff.

## MDB-0002 Temporal contract

**Outcome:** define the smallest useful temporal behavior before choosing storage.
Create `docs/architecture/temporal-contract.md` and a table or small fixture set of
histories with exact expected answers. Record material decisions in an ADR.

Cover entity/value semantics, valid-time precision and interval boundaries,
unbounded intervals, overlapping assertions, partial corrections, retractions,
transaction atomicity, commit ordering, read cutoffs and retry/idempotency behavior.
State a single-writer initial scope and how readers observe committed knowledge.
Resolve ambiguity explicitly or split off a prerequisite decision; do not silently
turn the existing salary illustration into a full conflict-resolution policy.

**Complete when:** worked histories cover insertion, a partial retroactive change,
a boundary lookup, retraction, duplicate request and historical-read stability.
Include current-state, as-of entity, interval and correction-sensitive aggregation
workloads. Each has explicit inputs and expected outputs suitable for later tests.
No executable Java implementation is required for this specification step.

**Specification artifacts:** [temporal contract](../../docs/architecture/temporal-contract.md),
[exact histories](../../docs/architecture/fixtures/temporal-contract-v0.json) and
[ADR 0003](../../docs/decisions/0003-initial-temporal-semantics.md). Reuse these fixtures in
the reference/engine checks rather than maintaining conflicting copies.

## MDB-0003 Java and Maven foundation

**Outcome:** introduce a reproducible Java build with the smallest useful project
structure. Choose and document the JDK baseline for intended JVM consumers; pin
Maven, build/test plugins and dependencies. Add Maven Wrapper scripts and verified
distribution checksums, Java source/test layout and read-only CI verification.

Start with one artifact. Establish package/coordinate conventions, compiler release,
test conventions and the root `./mvnw -B verify` command. Add generated-output
ignores and a meaningful executable check drawn from the temporal contract. Keep
the Python documentation toolchain independent.

**Complete when:** a clean checkout can run the wrapper verification on the selected
JDK, CI is configured to run the same verification, README/contributor commands are
accurate and the toolchain decision records versions and rationale. Publish no
library artifact as part of this foundation step.

**Build artifacts:** [Java/Maven guide](../../docs/development/build.md) and
[ADR 0004](../../docs/decisions/0004-java-build-foundation.md). The foundation introduces a
small interval primitive for meaningful boundary checks; MDB-0004 reuses it.
Research current Maven artifact versions under the build guide on every Maven change.

References: [Maven's project model](https://maven.apache.org/what-is-maven.html) and
[Wrapper setup and checksums](https://maven.apache.org/wrapper/).

## MDB-0004 Temporal types and reference model

**Outcome:** reuse the interval primitive from MDB-0003, complete the selected
command, query, interval and commit-position types, and implement a straightforward
reference interpreter for the contract. Keep the
oracle in test support or a separate test artifact when needed. Build reusable
history fixtures and exact expected answers without borrowing future optimized
storage/index algorithms.

**Complete when:** the contract cases execute, invalid inputs and boundaries are
covered, and seeded generated histories can be checked against independently
reasoned properties and fixtures. Preserve failing seeds. Language/API names remain
experimental; avoid promising broad public query syntax.

**Produced artifacts:** [reference guide](../../docs/development/reference-model.md),
[ADR 0005](../../docs/decisions/0005-reference-model.md), immutable `.temporal` types,
test-only interpreter, canonical fixture replay and persisted seed corpus. See
[progress history](progress.md) for exact verification counts and delivery evidence.

## MDB-0005 Embedded in-memory engine

**Outcome:** expose a small in-process Java entry point with explicit transaction,
read-view and close semantics. Implement an initial engine separate from the test
oracle, using simple algorithms before introducing physical optimizations.
Reuse the immutable temporal types and canonical expectations; keep the reference
interpreter in test support and choose an independently reasoned engine algorithm.

**Complete when:** consumers can insert, correct, retract and query through the
experimental Java API; atomic commit visibility, stable historical/read views,
serialized writer behavior, retries and lifecycle failures match the contract.
Compare observed results with the reference fixtures and seeded histories. Include
one runnable embedding example; state that this implementation is memory-only.

**Produced artifacts:** [engine guide](../../docs/development/embedded-engine.md),
[ADR 0006](../../docs/decisions/0006-in-memory-engine.md), `org.meridiandb.engine`, canonical
and seeded comparisons, lifecycle/concurrency tests and `examples/EmbeddedExample.java`.
See [progress history](progress.md) for verification and delivery evidence.

## MDB-0006 Local storage and journal

**Outcome:** define only the storage capabilities actually needed by the local
engine and implement a versioned append-only transaction journal. Specify the
record envelope, checksum, commit boundary, sync/acknowledgement behavior and retry
identity. Keep filesystem assumptions explicit rather than claiming object-store
semantics from a generic interface.

**Complete when:** journal records round-trip, corruption and partial/torn tails
have defined handling, durable publication boundaries are tested and unsupported
format versions fail clearly. Document tested filesystem/platform assumptions.
Expand this package if adapter and journal outcomes need separate commits.

**Required children:** MDB-0015 defines and verifies the pure journal representation;
MDB-0016 implements local ownership, append/sync and recovery using that format.
The split separates malformed-byte correctness from filesystem failure behavior.
Keep this parent expanded until both children and all original acceptance criteria
pass. The durable embedded engine remains MDB-0008.

## MDB-0015 Journal format and codec

**Outcome:** research relevant immutable append-only database/search designs and
record the local storage contract, durability/visibility boundaries and journal v1
layout. Implement a bounded pure Java codec for lineage headers and complete
ordered transaction records, including exact identity, positions and checksums.

**Complete when:** typed records round-trip without changing retry payloads;
independent known bytes fix the format; malformed/corrupt/unsupported input is
rejected; every truncated frame exposes only the prior complete prefix; sequence
and duplicate request identity rules are checked; size, lossless identity and
boundary checks pass.
No local append/fsync or durable-engine guarantee is claimed by this child.

**Produced artifacts:** [storage research](../../docs/architecture/storage-research.md),
[journal specification](../../docs/architecture/journal-format.md),
[ADR 0007](../../docs/decisions/0007-journal-format.md), `org.meridiandb.storage.journal`
and independent codec fixtures/corruption checks.

## MDB-0017 Public documentation quality

**Outcome:** correct the public narrative, move execution queues/receipts and site
administration outside the published source tree, retain a reader-facing technical
roadmap, and make public documentation QA a durable agent rule and CI build gate.

**Complete when:** published pages contain no chat/owner/agent/task-status narrative;
repository workflow/history remains available outside `docs/`; navigation, search,
sitemap and generated artifacts contain only public material; source/rendered QA
has meaningful passing/failing fixtures and runs in both documentation workflows;
current instructions, links and publication evidence are reconciled.

## MDB-0016 Local journal I/O and recovery

**Outcome:** implement the minimum local-file capabilities using the verified v1
codec: exclusive writer ownership, complete append loops, explicit durable force,
sequential bounded recovery and an explicit incomplete-tail repair policy. Fail
closed after uncertain writes/sync errors; record tested filesystem assumptions.
Keep visibility and durable acknowledgement distinct and preserve diagnostic bytes.

**Complete when:** reopen returns exact ordered records and identity, a second
writer is rejected, corrupt/unsupported records fail closed, incomplete tails have
explicit tested repair, short/failed writes and force boundaries have injected
checks, and local platform behavior is documented. Close MDB-0006 only after all
its parent criteria pass; engine integration/crash recovery remains MDB-0008.

**Produced artifacts:** [local journal guide](../../docs/development/local-journal.md),
[ADR 0008](../../docs/decisions/0008-local-journal.md), `LocalJournal`, sequential
`JournalRecovery`, injected-I/O tests, separate-JVM checks and a runnable local
journal example. Namespace persistence is documented separately from file force;
there is no portable parent-directory publication protocol or durable engine yet.

## MDB-0007 Immutable segments and manifests

**Outcome:** write and read a simple versioned immutable segment format and a
manifest describing a consistent segment set. Select a minimal initial format in
an ADR; Parquet and MergeTree-like organization remain independent candidates.
Define identifiers, checksums and atomic/durable manifest publication on the local
backend, including how the journal and manifest relate.

**Complete when:** reopened readers return exact records/intervals, invalid or
unsupported files are detected, publication failures do not expose an invalid
manifest, and immutable data ownership/lifetimes are explicit. Preserve a simple
scan path usable as a correctness baseline.

**Required children:** MDB-0018 establishes exact immutable history bytes and
identifiers; MDB-0019 implements local file I/O and read lifetimes; MDB-0020 adds
manifest consistency and atomic/durable publication under explicit local platform
assumptions. Keep the parent expanded until all children and original integration
criteria pass. A pure codec is not a durable checkpoint.

**Integration complete:** MDB-0022 closes this parent after all required children:
exact immutable history reopen, complete invalid-object rejection, verified actual
journal binding, exclusive publication, ordered namespace forces, uncertain-failure
handling and retained reader/object lifetimes. See its artifacts and the dated receipt.

## MDB-0018 Immutable segment format and codec

**Outcome:** choose a minimal versioned history-segment representation in an ADR.
Preserve lineage and original ordered transaction frames, contiguous first/last
sequences and request identity; retain a simple scan baseline without flattening
away valid/system history. Implement immutable Java types, bounded pure encoding
and strict whole-object decoding, checksums and verified content identifiers.

**Complete when:** exact records/intervals/identities round-trip, independent golden
bytes fix the format, all truncated or corrupted objects fail closed without a
partial prefix, unsupported versions/history/resource violations are rejected,
expected-ID mismatches are detected and input/output ownership is explicit.
No local file publication, manifest, durable engine or performance claim is supplied.

**Produced artifacts:** [segment format](../../docs/architecture/immutable-segment-format.md),
[ADR 0009](../../docs/decisions/0009-immutable-segment-format.md), immutable segment/ID
values, bounded pure `SegmentCodec`, independent golden/corruption/history/resource
checks and a runnable byte-codec example. Ownership is heap-based; local file and
manifest publication remain required children.

## MDB-0019 Local immutable segment I/O

**Outcome:** write and reopen versioned immutable segment files using MDB-0018,
with bounded I/O, exact expected identifier validation and explicit reader/owner
lifetimes. Preserve a simple sequential scan path and retain referenced objects;
never modify a published segment in place.

**Complete when:** file readers recover exact records and intervals, short/failed
I/O and malformed/missing/changed objects are checked, identifiers bind complete
contents and close/error behavior is tested. State local filesystem and namespace
assumptions. Manifest publication remains MDB-0020.

**Produced artifacts:** [local segment guide](../../docs/development/local-segments.md),
[ADR 0010](../../docs/decisions/0010-local-segment-io.md), `LocalSegmentFile`,
heap-owned `LocalSegmentReader`, real-file and injected-I/O checks, and
`examples/LocalSegmentExample.java`. Creation never overwrites candidates; open
binds an independently expected ID and closes the channel before exposing values.
Namespace durability, manifests and durable engine integration remain separate.

## MDB-0020 Checkpoint manifest and publication

**Outcome:** define a versioned manifest naming a consistent immutable segment set,
lineage and exact included journal prefix. Implement local atomic/durable
publication with explicit namespace assumptions and failure handling. Preserve
older readers/referenced segments; retain journal history until an explicit
recovery/retention policy permits reclamation.

**Complete when:** reopened manifests resolve only complete verified objects,
identity/range/prefix consistency is checked, publication failures expose only
permitted complete generations, sync and namespace boundaries have focused failure
checks, and ownership/lifetimes are explicit. Complete all MDB-0007 criteria before
closing it; durable engine acknowledgement/recovery remains MDB-0008.

**Required children:** MDB-0021 defines bounded manifest bytes and verifies the
referenced immutable history against its exact journal-prefix binding. MDB-0022
implements actual journal-prefix verification, local selection/publication, reopen
and namespace/platform failure boundaries. Keep this parent and MDB-0007 expanded
until both children and all original criteria pass. This separates malformed or
inconsistent object graphs from filesystem publication without reducing scope.

**Integration complete:** both children pass. The local store verifies stored bytes,
publishes immutable generations and reopens the complete selected graph under
explicit capabilities. MDB-0022 supplies the original publication/reopen and
reader-lifetime evidence; MDB-0007 also closes. Engine recovery remains MDB-0008.

## MDB-0021 Manifest format and checkpoint consistency

**Outcome:** implement immutable manifest metadata, generation identity distinct
from temporal cutoff, exact segment IDs/lengths/ordered ranges and an included
journal-prefix sequence/byte count/SHA-256. Specify bounded versioned bytes with
complete integrity and expected manifest identity. Verify loaded segment contents,
lineage/range/length, global request identity and reconstructed exact journal-prefix
bytes/hash before accepting a consistent object graph.

**Complete when:** independent golden bytes, round-trip/ownership and malformed,
truncated, unsupported, bounded-resource and identifier cases pass. Whole-set checks
cover empty genesis, gaps/overlaps, missing/changed/wrong-lineage objects, cross-
segment identity reuse and prefix mismatches. Return no partial successful graph.
No actual journal-file check, namespace publication or durability receipt is claimed.

**Produced artifacts:** [manifest specification](../../docs/architecture/checkpoint-manifest-format.md),
[ADR 0011](../../docs/decisions/0011-checkpoint-manifest-format.md), immutable
`CheckpointManifest`/`ManifestId`, bounded `ManifestCodec`, incremental
`CheckpointVerifier`, independent codec/whole-set tests and `examples/ManifestExample.java`.
Metadata, complete supplied-object validation and actual stored-journal/publication
receipts remain distinct. Local publication is now supplied by MDB-0022.

## MDB-0022 Local checkpoint publication

**Outcome:** use the verified manifest/object contract with a local publication
protocol. Verify the actual included journal prefix and referenced files, enforce
writer ownership and generation selection, and distinguish file force, namespace
sync, atomic visibility and uncertain outcomes. Reopen only permitted complete
verified generations and preserve old readers/objects and diagnostic candidates.

**Complete when:** MDB-0020's original publication/reopen/platform criteria and
MDB-0007's original integration criteria pass with focused short/failed I/O,
publication-boundary and reader-lifetime checks. Record supported platforms and
fail unsupported capabilities explicitly. Keep engine acknowledgement/recovery in
MDB-0008; retain journal and referenced objects until explicit retention permits
reclamation.

**Produced artifacts:** [local checkpoint guide](../../docs/development/local-checkpoints.md),
[ADR 0012](../../docs/decisions/0012-local-checkpoint-publication.md),
`LocalCheckpointStore`, `LocalCheckpointReader`, owner-channel `LocalJournal.prefix`,
39 publication/process and 19 prefix tests, plus `examples/LocalCheckpointExample.java`.
Highest immutable generation selection avoids a mutable head pointer. Namespace
capability is probed before mutation; all dependencies share the durably named
root. Publication retains pending evidence, old generations and all history.
Darwin arm64 local tests exercise actual directory force, atomic rename and process
reopen; Linux/Windows CI separately reports capability-dependent cases. No power-loss
or durable-engine result is inferred. All original MDB-0020/MDB-0007 criteria pass.

## MDB-0008 Durable engine and recovery

**Outcome:** integrate the journal, segments and manifests with the embedded engine.
Define commit publication, reopen/replay and uncertain commit outcomes as one
observable protocol. Document what an acknowledgement guarantees.

**Complete when:** crash/restart injection across publication boundaries preserves
acknowledged commits, exposes only permitted atomic outcomes for unacknowledged
work and handles retries without duplicate effects. Read views remain consistent
while writers commit. Re-run the temporal/reference checks through reopen and
recovery, retaining deterministic failure reproductions.

**Required children:** MDB-0023 restores a read-only local database from verified
checkpoint history plus the journal tail. MDB-0024 defines and implements forced
commit acknowledgement and atomic query publication. MDB-0025 exercises process
crash/reopen, uncertain outcomes, retry identity and temporal/reference conformance.
Keep this parent expanded until all children and its original integration criteria
pass. Read recovery alone does not establish an engine acknowledgement guarantee.

**Integration complete:** MDB-0025 closes this parent after real process exits at
commit/startup/initialization/checkpoint boundaries, lost-acknowledgement retries,
unchanged canonical expectations and seeded writable histories. Earlier preparation,
fault and concurrency checks remain passing. Actual force/provider/device assumptions
remain explicit; no device power-loss simulation, reclamation or throughput claim.

## MDB-0023 Local database recovery

**Outcome:** open an existing trusted local store with exclusive journal/store
ownership; verify the highest selected checkpoint against actual journal bytes,
restore exact checkpoint history and replay only the journal suffix into hidden
engine state. Preserve database lineage, commit sequences, all historical views
and request lookup. Support journal-only and genesis-checkpoint recovery. Expose
reads only after full success; offer no engine transaction API in this child.

**Complete when:** canonical temporal expectations execute through real reopen;
checkpoint/tail and journal-only results agree with exact historical answers;
corrupt/missing/incomplete history fails closed with no partial database, automatic
repair or fallback; source failures release ownership and preserve evidence;
close, concurrent reads and resource cleanup have focused tests. Document actual
platform capabilities, replay/memory costs and the absence of durable commit
acknowledgement. Keep the reference oracle test-only.

**Produced artifacts:** [local recovery guide](../../docs/development/local-database.md),
[ADR 0013](../../docs/decisions/0013-local-database-recovery.md), `LocalDatabase`,
package-private seeded `InMemoryEngine` construction/exact restore, 22 focused
recovery/lifecycle/fault checks and 15 canonical reopen cases (five histories in
three checkpoint shapes), plus `examples/LocalRecoveryExample.java`.
All 252 canonical expectations across these shapes and every stored historical
cutoff/request sweep pass. Source ownership includes the existing directory-force
probe and writable lock permissions. This child supplies read reconstruction only;
MDB-0024 now supplies forced write publication; MDB-0025 and all original
MDB-0008 crash/retry criteria remain required.

## MDB-0024 Durable commit publication

**Outcome:** define engine creation and transaction acknowledgement over the owned
local journal and recovered state. Prepare complete state privately, append and
force an exact record, then atomically publish values/history/request identity
before success. Specify uncertain outcomes, retry after reopen, initialization
namespace force and checkpoint coordination without weakening existing contracts.

**Complete when:** successful writes remain queryable/recoverable with original
identity; identical/conflicting retries, pinned views, concurrent readers/writers,
force/publication/close failures and capability boundaries have observable tests.
No write success may precede its promised storage boundary. Record the selected
protocol and remaining crash-conformance checks for MDB-0025.

**Produced artifacts:** [ADR 0014](../../docs/decisions/0014-durable-commit-publication.md),
expanded [local database guide](../../docs/development/local-database.md),
`InMemoryEngine.prepare/publish`, `LocalDatabase.create/openWritable/transact`,
explicit uncertain-commit metadata, store genesis initialization/startup journal
synchronization, 49 preparation/initialization/commit tests and an eighth runnable
example. Genesis creation uses selected generation one at cutoff zero; writable
startup forces the recovered journal and directory before retry acknowledgements.
All 390 local tests and eight examples pass. Checkpoints do not auto-advance, all
history is retained, and ownership excludes external checkpoint writers while open.
The forced-write protocol is experimental: MDB-0025 still verifies required process
crash/retry and canonical/seeded durable conformance before MDB-0008 closes.

## MDB-0025 Durable crash and retry conformance

**Outcome:** exercise process termination and injected faults at each durable
creation/append/force/state-publication/checkpoint/reopen boundary. Apply canonical
and seeded temporal/reference histories through recovered execution; preserve
minimal failing evidence and exact retry outcomes.

**Complete when:** acknowledged commits survive tested restarts; unacknowledged
work has only documented complete outcomes; lost acknowledgement and conflicting
retries never duplicate effects; historical queries and pinned views remain
consistent. State filesystem/device assumptions and distinguish process faults
from power loss. Close MDB-0008 only after its full original criteria pass.

**Produced artifacts:** [durability conformance guide](../../docs/development/durability-conformance.md),
package-private per-instance production boundary observers and five new test classes:
11 engine process/torn-image cases, nine checkpoint process cases, four observer
order/cleanup/uncertainty cases, ten canonical writable histories (168 literal
expectations) and 16 persisted seeded histories (512 new durable commits). All
440 default tests and eight examples pass locally with no skips. The 19 real
child-JVM exits preserve exact histories, lineage, request IDs and retry positions;
one separately injected torn image is rejected without automatic repair. Canonical
source and seed corpus are unchanged. MDB-0008's original criteria are now met;
provider/device assumptions and the absence of power-loss simulation remain stated.

## MDB-0009 Temporal query operators

**Outcome:** implement the logical operators required by the selected workloads:
entity lookup with two time cutoffs, interval selection, predicates and a small
correction-sensitive aggregation. Define result ordering, bounds and resource
lifetimes. Begin with correct scan execution; defer broad SQL/planning machinery.

**Complete when:** in-memory and durable execution agree with the reference model
for boundaries, overlaps, empty results, corrections and retractions; snapshots
remain stable during execution; the embedding examples demonstrate the actual
supported operations. API and documentation describe any remaining limits.

**Implementation refinement:** preserve the original outcome. Point lookup,
coalesced interval scan and exact correction-sensitive sum already exist. Complete
this cohesive query extension with six signed numeric comparison predicates,
explicit-entity point selection and filtered interval scans. Resolve values at the
pinned cutoff before filtering; exclude absence, define deterministic UTF-16 entity
ordering and retain gaps. Materialize immutable results with existing close
semantics. Extend independent fixtures/reference/dense comparisons and real local
reopen, keeping existing histories and storage bytes unchanged. No SQL, index,
callback predicate, pagination or total-memory bound is implied.

**Produced artifacts:** [ADR 0015](../../docs/decisions/0015-temporal-query-operators.md),
[query guide](../../docs/development/temporal-queries.md), additive query fixture
corpus, `ValuePredicate`, `EntityValue`, typed `Select`/`FilterScan`/`Rows` and a
ninth checked embedding example run by Linux/Windows CI. Original Get/Scan/Sum,
canonical histories, persisted seeds and storage formats are unchanged. Eight type,
three lifecycle/concurrency, 12 exact-fixture and 32 seeded cases add 55 tests;
all 495 tests and nine examples pass locally with no skips. The three new histories
supply 90 expectations across four reference/memory/durable shapes (360 assertions).
Each seeded memory and durable target executes 512 new transactions, with checkpoint
tails and writable reopen after each durable new write. Scope, eager allocation,
linear scans and absent query-wide budgets remain explicit. Original completion
criteria pass without adding a separate prerequisite or weakening the outcome.

## MDB-0010 Compaction and retention

**Outcome:** compact segments while preserving the temporal contract, then reclaim
obsolete physical objects safely. Keep all supported history by default; any
history-pruning mode needs a separate explicit retention decision. Account for
active readers, interrupted writes and concurrent commit publication.

**Complete when:** supported query results remain equal before/after compaction
and recovery, active views retain needed data, crashes do not publish partial
replacement sets, and reclamation cannot remove live/referenced objects. Measure
space effects without claiming that compaction alone proves query speedups.

**Required children:** MDB-0026 defines logical retention and bounded pure history
repacking; MDB-0027 installs/verifies replacement objects and publishes the next
physical generation at the same cutoff; MDB-0028 protects lazy readers across owner
lifetimes and implements reachability-based physical reclamation; MDB-0029 verifies
all original temporal/recovery/crash/space criteria together. Preserve the original
criteria and keep this parent expanded until all children and integration pass.
Pure plans and durable replacement publication grant no deletion permission.

## MDB-0026 Bounded history compaction

**Outcome:** distinguish logical history retention from physical object retention,
record publication/reader/reclamation obligations, and implement a pure deterministic
checkpoint-history compactor. Verify the complete input graph and exact prefix;
repack whole original transactions under explicit aggregate input/output bytes,
record count and per-segment bounds. Preserve lineage, sequences, request payloads,
all cutoffs and retry identities; propose only the next physical generation with
the same journal prefix. Use existing formats and add no storage mutation.

**Complete when:** exact history and prefix identities survive grouping/splitting;
all canonical/query expectations and historical/retry checks agree after repacking;
invalid/missing/corrupt/late input fails without a partial plan; aggregate budgets,
indivisible oversize records, generation/count limits, deterministic IDs and result
ownership have boundary tests. Report encoded segment space effects separately from
actual filesystem space. Document that publication, reader protection and deletion
remain required children; preserve the complete journal and default all-history promise.

**Produced artifacts:** [ADR 0016](../../docs/decisions/0016-history-preserving-compaction.md),
[compaction guide](../../docs/development/history-compaction.md), pure
`HistoryCompactor`/private-constructed immutable `Plan` and explicit `Limits`, exact
`SegmentCodec.encodedBytes` preflight and an internal verifier collector retaining
one source read per reference. Thirty-three boundary/format/resource/ownership
cases plus eight original/query fixture histories and 16 persisted seeds add 57
tests. All 552 tests and ten examples pass locally with no skips. All 174 literal
expectations execute before/after repacking; rebuilding consumes actual output.
The example changes two segment images / 430 encoded bytes to one / 302, preserving
cutoff two and proposing generation eight. This is encoded accounting only; no
filesystem installation, durable compaction publication, reader pin or reclamation
is implemented here. MDB-0010 remains expanded with original criteria intact.

## MDB-0027 Local compaction publication

**Outcome:** install a validated replacement under store/journal ownership. Verify
source generation and actual journal prefix; verify/reuse identical existing content
IDs instead of overwriting, create/force new objects, then select the next immutable
generation through the existing force/atomic-rename protocol. Keep cutoff/history
and full journal unchanged; retain all old and failed candidates. Exclude concurrent
engine writers through existing ownership.

**Complete when:** exact local reopen chooses only a complete valid generation;
reuse verifies bytes/identity, damaged existing candidates fail closed, new segments
and namespace precede selection, failures poison/mark uncertainty appropriately,
and no old object is deleted. Test same-cutoff publication, unchanged-layout plans,
capacity and actual owner-journal binding. No fast-recovery or throughput claim.

## MDB-0028 Reader-safe physical reclamation

**Outcome:** define and implement explicit cooperating reader protection independent
of store lifetime, including cross-process acquisition-versus-deletion coordination.
Compute live roots and retain all objects required by selected/retained generations
or active lazy readers. Reclaim only proven obsolete physical metadata/segments;
retain logical history, journal, unknown files and diagnostic candidates unless an
explicit inspected policy establishes permission. Expand into prerequisites if needed.

**Complete when:** active readers retain lazy access after store close and new
publication, reader acquisition cannot race deletion, cross-process protection and
release/restart behavior have tests, and deletion never removes live/referenced
objects or lock/journal aliases. Order/synchronize obsolete metadata removal before
unreferenced dependencies, preserve fail-closed newest selection, and document
interruption/cleanup/capacity behavior. Timer-only grace or a process-local registry
is insufficient. No logical history pruning or journal truncation is authorized.

**Required children:** MDB-0030 introduces cross-process reader protection with
no deletion; MDB-0031 uses that protection for conservative physical reclamation.
Keep this parent expanded until both children and its original criteria pass.

**Completion evidence:** MDB-0030's cross-process reader protocol plus MDB-0031's
verified inventory/ordered removal now satisfy the original reader, alias, retained-
root, interruption, cleanup and capacity criteria. All 840 tests and thirteen examples
pass locally. MDB-0029 still owns full semantic/crash/space integration for MDB-0010.

## MDB-0030 Cross-process reader protection

**Outcome:** add a permanent store-wide shared reader barrier acquired under store
ownership before returning a verified lazy reader. A single JVM channel/reference
registry and OS file lock preserve protection across store close and independent
processes. Synchronize lazy opens with release; returned heap children survive close.
Prepare an internal nonblocking exclusive barrier for future reclamation; any live
reader conservatively defers all deletion. Introduce no physical removal.

**Complete when:** multiple same-JVM readers share one channel, readers survive
store close/new publication, another process cannot acquire exclusive protection
until all readers close or their process exits, and close serializes with lazy opens.
Test release/close failures and retry, fail-closed uncertain/invalid protection,
unsupported shared-lock capability, pre-open journal/store-lock/guard aliases,
capacity and admission cleanup. Preserve ordinary I/O failures and do not substitute
PID/timer/in-memory-only liveness. Document coarse protection, class-loader/provider
boundaries and required explicit close. Update engine cleanup for failing reader release.

## MDB-0031 Protected physical reclamation

**Outcome:** under exclusive store ownership and a nonblocking exclusive reader
barrier, verify live selected/retained/explicit backup roots and reclaim only proven
obsolete complete metadata and unreferenced immutable segments. While any reader is
live, defer all removal. Retain journal, ownership/barrier files, unknown and diagnostic
objects absent an inspected explicit policy. Preserve complete logical history.

**Complete when:** original MDB-0028 removal/interruption/capacity criteria pass,
reader admission cannot race deletion, aliases are rejected before open/delete,
obsolete metadata removal is forced before unreferenced dependencies, newest state
fails closed and every stop/failure has documented safe reopen behavior. Test actual
cross-process contention, retained roots, interrupted removal and no dangling graph.
Keep MDB-0029's full semantic/crash/space integration separate and required.

**Required children:** MDB-0032 establishes verified retention/candidate inventory;
MDB-0033 performs protected ordered removal and interruption checks. Preserve every
original acceptance criterion above and keep this parent expanded until both pass.

**Completion evidence:** MDB-0032 and MDB-0033 are complete. Actual guarded removal,
per-object/boundary halts and safe reopen preserve every live graph; explicit roots,
full journal and unknown/diagnostic objects remain retained. Original criteria pass
without introducing a persistent intent or weakening the integrated MDB-0029 gate.

## MDB-0032 Verified reclamation inventory

**Parent:** MDB-0031. **Outcome:** inspect published generations under store
ownership and nonblocking exclusive reader protection. Always retain the selected
root and explicit retained/backup IDs. Verify every published manifest, dependency
and actual owner-journal prefix before exposing immutable deterministic candidate
metadata/byte counts. Include only dependencies of verified obsolete roots that
no live root uses; preserve unclassified/orphan objects and refuse pending evidence.
Check aliases before opens and bound aggregate repeated bytes/records/identity work.

**Complete when:** tests cover retained/shared/genesis roots, missing IDs, bad old
and newest state, actual prefix mismatch, immutable outputs, bounded work, pending
and unknown evidence, alias/control preservation, cross-process reader deferral and
cleanup failures. Preview creates at most permanent guard metadata; it removes no
objects, grants no later deletion authority and makes no freed-space claim. Keep
MDB-0033 and original parent criteria required.

**Produced artifacts:** `ReclamationInventory`, `LocalCheckpointStore.inspectReclamation`,
[retention/inventory guide](../../docs/development/reclamation-inventory.md),
[ADR 0018](../../docs/decisions/0018-reclamation-inventory.md) and a twelfth checked
example. Fifty-one unit/fault and 18 real filesystem/process invocations cover
complete roots, exact budgets, corrupt/diagnostic evidence, alias lock preservation,
reader lifetime and cleanup. All 747 local tests and twelve examples pass without
skips. No objects are removed; MDB-0033 and original parents/integration remain required.

## MDB-0033 Ordered protected removal

**Parent:** MDB-0031. **Outcome:** recompute the verified inventory while retaining
store ownership and exclusive reader protection, retire only obsolete complete
metadata, force the directory, then remove eligible dependencies and force again.
Retain selected/explicit roots, full journal, permanent controls, unknown/orphan
and diagnostic evidence. Refuse aliases before delete and preserve all reader
admission/cleanup guarantees.

**Complete when:** original MDB-0031/MDB-0028 removal, capacity and interruption
criteria pass, including actual process contention, no dangling graph at each
stop/failure, fail-closed newest state and documented reopen/retry behavior. Add a
persistent intent only if evidence requires it; otherwise explicitly preserve
orphaned dependencies after interrupted retirement. Integrate/retest MDB-0032's
inventory policy and close MDB-0031/MDB-0028 only when original criteria pass.
Keep MDB-0029's whole-history/crash/space integration separate and required.

**Produced artifacts:** `reclaim`, immutable `ReclamationResult`, explicit uncertain
removal, retained-file/journal/namespace barriers and per-delete alias/control
reservation, [removal guide](../../docs/development/physical-reclamation.md),
[ADR 0019](../../docs/decisions/0019-protected-physical-reclamation.md) and a thirteenth
checked example. Seventy-one unit/fault, 15 real integration and seven process-halt
cases pass; all 840 local tests and thirteen examples pass without skips. Original
MDB-0031/MDB-0028 criteria pass, closing both parents. MDB-0029 remains required
before MDB-0010 closes; orphan retention, coarse-reader/capacity limits and full
logical-history retention remain explicit.

## MDB-0029 Compaction and reclamation conformance

**Outcome:** integrate actual compaction publication, protected readers and physical
reclamation with canonical/seeded temporal and retry checks, bounded process/fault
injection and measured physical space accounting.

**Complete when:** all MDB-0010 original criteria pass through local reopen before/
after replacement and safe deletion; acknowledged commits/history/IDs persist,
active views/lazy readers remain valid, interrupted publication/removal has only
specified complete outcomes and no dangling selected graph. Compare actual retained
bytes/counts to encoded plan figures without claiming query speedups. Preserve
failing seeds and provider/device assumptions; close MDB-0010 only with this evidence.

## MDB-0011 Object-storage backend

**Outcome:** implement an S3-compatible backend against explicit range-read,
immutable-write and conditional/publication requirements. Reuse a maintained
provider library and run the common storage/engine conformance suite against a
local emulator or controlled test fixture.

**Complete when:** visibility, retries, uncertain outcomes, capability differences,
reopen and reclamation have tested contracts and failure cases. Local and object
backends agree on promised engine semantics. Keep real-cloud spending, credentials
and infrastructure deployment outside this step unless separately authorized.

## MDB-0012 Reproducible workload baseline

**Outcome:** turn the selected workload descriptions into a repeatable measurement
harness using correctness-checked data and the durable engine. State workloads,
seeds, sizes, runtime, hardware, revision, warmup, cache conditions and durability.

**Complete when:** someone else can repeat the runs and obtain reported throughput,
latency distributions, memory use, read/write amplification and recovery behavior
with their limitations. Separate microbenchmarks from engine measurements. Use a
small default fixture; larger or paid runs need an explicit environment/budget.

## MDB-0013 Measured layout and index experiments

**Outcome:** select one demonstrated bottleneck and compare a narrowly scoped
physical index or layout change against MDB-0012 under equivalent semantics and
durability. Candidate formats and organizations require independent evidence.

**Complete when:** correctness/reference/recovery/compaction checks still pass,
measurements include resource and regression tradeoffs, and an ADR adopts or
rejects the experiment. A well-evidenced rejection is a valid outcome; do not add
an optimization merely to satisfy a feature checklist. Split multiple independent
experiments into separate queue items.

## MDB-0014 Prototype release candidate

**Outcome:** prepare a reproducible candidate for an experimental embedded library.
Review supported semantics, API limits, file-format compatibility, recovery,
retention and backend claims. Provide accurate getting-started, embedding and
operational examples, Javadoc and locally verifiable packaging.

**Complete when:** a clean consumer fixture can use the candidate, relevant checks
and measured evidence are recorded, known limits and compatibility policy are
explicit, and release/versioned-documentation tasks are prepared for owner review.
Queue any concrete missing prerequisite. Creating tags and publishing to Maven
Central or GitHub Releases require their own applicable authorization. Ordinary
documentation changes publish automatically under the
[website workflow](../site-operations.md#initial-hosting-and-publication);
release-versioned documentation needs its own prepared policy and implementation.
This milestone does not promise production readiness.
