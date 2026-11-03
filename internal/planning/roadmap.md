---
title: Java and Maven implementation roadmap
description: Build MeridianDB from explicit bitemporal semantics through a tested Java reference model, durable storage, query execution and measured prototype readiness.
---

# Java and Maven implementation roadmap

This is the technical plan for the first embedded, single-node prototype. Temporal
types, the test reference, memory-only engine, local journal and immutable segment
I/O, manifest consistency, checkpoint publication, recovery, forced local writes
and integrated crash/compaction/reclamation conformance are implemented. Optional
S3 byte/checkpoint/recovery paths are experimental, with incomplete coordination/
reclamation deferred. Linux local qualification, workloads and file-layout work
now have priority; Windows has a basic compatibility role. Replication remains planned. The
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
| Optional replication | Original transaction transport, follower progress and separately specified commit/leadership guarantees |
| Query execution | Logical operations, temporal filtering and later physical access paths |
| Test reference model | A simpler independent interpreter and reusable histories/expected answers |
| Experiments | Reproducible workloads and evidence for layout/index decisions |

These responsibilities do not freeze module boundaries or a public API. The
single artifact contains temporal types and the [memory-only engine](../../docs/development/embedded-engine.md),
with reference support in tests under the [build foundation](../../docs/development/build.md).
The journal and immutable segment codecs have local file adapters. Manifest
metadata, referenced-object consistency, local publication, recovery, forced
write publication and integrated local crash/compaction/reclamation conformance
are implemented; optional experimental S3 access/publication/recovery is also
implemented separately. Further S3 work and replication are deferred, while Linux
qualification and local measurements are the selected path.
Read the [architecture overview](../../docs/architecture/overview.md) and
[language strategy](../../docs/decisions/0001-language-strategy.md) before changing them.
[ADR 0020](../../docs/decisions/0020-persistence-and-replication-boundaries.md) owns
the local-first bitemporal library direction; the
[boundary specification](../../docs/architecture/persistence-boundaries.md) separates
commit authority, ordered journal, immutable objects, checkpoint selection and
derived indexes. Optional replication remains outside the initial single-node
prototype's required dependency chain. [ADR 0039](../../docs/decisions/0039-linux-local-storage-priority.md)
and the [Linux storage design](../../docs/architecture/linux-storage.md) supersede
object-backend-first sequencing: local qualification, benchmarks, layout experiments
and operational hardening do not require MDB-0011 completion.

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

**Completion evidence:** all required children now pass. MDB-0029 connects the
unchanged literal/seed corpora to actual durable publication, protected retention,
physical removal and both reopen modes, with richer process stops and exact
named-file lengths/counts. All 886 tests and thirteen examples pass locally; original
query/recovery/reader/crash/space criteria are satisfied within stated provider/
process-failure limits. No history pruning, performance or freed-block claim follows.

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

**Produced artifacts:** six new test-only files (four suites/two helpers),
[integrated conformance guide](../../docs/development/compaction-conformance.md) and
[structured space evidence](../../docs/development/evidence/compaction-space-v1.json).
Sixteen literal cases execute 348 real steps/48 maintenance cycles; sixteen seeds
supply 512 actual new writes/32 cycles with every historical cutoff, read-only and
writable reopen, independent reference/dense queries and request identity. Three
layouts reconcile five-stage exact names/lengths; eleven process exits verify
richer history and complete object evidence. All 886 local tests and thirteen
examples pass without skips. No production/format/dependency changes. Original
MDB-0010 criteria now pass; close that parent and make MDB-0011 source-ready after
remote delivery/CI, preserving provider/device and non-performance limits.

## MDB-0011 Object-storage backend

**Priority state, November 2, 2026:** deferred. Preserve the original outcome,
expansion metadata, all required-child criteria and completed evidence below.
The Linux local path proceeds independently; re-admission does not waive any
unfinished requirement.

**Outcome:** implement an S3-compatible backend against explicit range-read,
immutable-write and conditional/publication requirements. Reuse a maintained
provider library and run the common storage/engine conformance suite against a
local emulator or controlled test fixture.

**Complete when:** visibility, retries, uncertain outcomes, capability differences,
reopen and reclamation have tested contracts and failure cases. Local and object
backends agree on promised engine semantics. Keep real-cloud spending, credentials
and infrastructure deployment outside this step unless separately authorized.

**Required children:** MDB-0034 records the persistence/replication boundary and
refined plan; MDB-0035 implements bounded immutable-object access with local
conformance; MDB-0036 adds the optional provider adapter; MDB-0037 implements
conditional checkpoint publication; MDB-0038 integrates local-journal/remote-
checkpoint recovery and acknowledgement; MDB-0039 adds protected remote
reclamation; MDB-0040 closes the original common engine/storage conformance gate.
Keep this parent expanded until every child and all original criteria pass.

**Scope reconciliation, October 31, 2026:** remote objects first complement the
existing exclusively owned local journal. Preserve original formats, temporal
history, retry identity and local force-before-publication acknowledgement.
Conditional object metadata is not database leadership or a replicated commit.
Provider integration stays optional for local consumers. Record unsupported
cross-host writer/reader modes and required capability failures explicitly.
MDB-0041 tracks a separate deferred replication protocol; it is not a child or an
implicit prototype-release prerequisite. Safe remote reclamation remains required
here; listing candidates or deferring every cleanup case cannot close the parent.

## MDB-0034 Bitemporal persistence boundaries

**Parent:** MDB-0011. **Outcome:** establish the local-first embeddable bitemporal
direction, separate persistence responsibilities and expand the backend milestone
without weakening its original acceptance criteria. Record optional replication
as a staged protocol separate from storage adapters.

**Complete when:** a public ADR and architecture specification distinguish valid
time, committed knowledge, physical generations and search freshness; make local
standalone use and adapter guarantees explicit; address journal/prefix alignment,
remote-tail loss, immutable identity, checkpoint uncertainty, reader-safe cleanup
and follower identity/order. Reconcile README, architecture, public roadmap,
research, navigation, agent rules and queue with dated primary evidence. Validate
public source/rendered/link/search/sitemap quality and queue IDs/dependencies/DAG.
This decision step changes no production code, Maven input, temporal fixture or
implemented guarantee; it leaves the first interface implementation ready.

**Produced artifacts:** [ADR 0020](../../docs/decisions/0020-persistence-and-replication-boundaries.md),
[persistence boundaries](../../docs/architecture/persistence-boundaries.md), refreshed
ClickHouse/Hive/Elasticsearch/XTDB comparison, reconciled public entry points and
agent rules, and required child/deferred replication cards. Both site layouts
pass the shared strict build for 47 pages, all 41 documentation QA tests pass and
41 queue IDs/parents/dependencies form a valid DAG. Independent review added the
same-lineage/divergent-tail clone case and the distinction between enforceable
namespace checks and exclusive-placement assumptions. No Java behavior or Maven
inputs changed; MDB-0035 is next after remote delivery and documentation CI.

## MDB-0035 Bounded immutable-object access

**Parent:** MDB-0011. **Outcome:** define and implement the smallest immutable-object
access interface needed by the checkpoint work, backed by a local implementation
and reusable contract tests. Retain explicit journal ownership and backend-specific
checkpoint publication; introduce no provider SDK or network protocol. Document
the proposed operations, capabilities, budgets and lifetime/error types before
refactoring concrete consumers, preserving existing local APIs and formats.

**Complete when:** create-only writes, verified identical reuse, expected length/
content ID, missing/conflicting/corrupt objects, exact bounded reads and range
boundaries have independent tests through actual local I/O. Preserve complete-
object validation before returning history records; range bytes alone grant no
whole-object integrity claim. Check allocation/overflow/short-read bounds,
immutable returned data, closed-reader behavior, ownership/alias safety and cleanup
failures. Reuse existing local force/namespace contracts without claiming a
portable publication protocol. Run affected local/engine/reference and full Maven
verification, fresh artifact research under AGENTS, documentation and a runnable
consumer example. Expand further if extracting byte access and migrating a consumer
cannot remain one bounded change; keep MDB-0036 and later gates required.

**Produced artifacts:** nine production sources: `ImmutableObjectStore`, bounded
key/ID/reference and error types, `VerifiedObject`, `LocalObjectStore` and the
`SegmentObjects` codec consumer. Add a reusable backend conformance base, local/
type/fault/segment suites, [object-access guide](../../docs/development/immutable-objects.md),
[ADR 0021](../../docs/decisions/0021-bounded-immutable-object-access.md) and a fourteenth
CI-checked example. All 970 local tests (84 new) and fourteen examples pass without
skips; the example also consumes the JAR, whose only module dependency is java.base.
Whole images are limited to 32 MiB; ranges are verified heap slices, not physical
range I/O. Existing local checkpoint/journal/engine protocols and file formats are
unchanged. Both documentation layouts pass for 49 pages; all 41 QA tests and 41-ID
queue checks pass. MDB-0036 and the original parent protocol/integration gates remain
required after remote delivery/CI.

## MDB-0042 Repository sponsorship

**Outcome:** implement the owner's scoped sponsorship request with a repository
Sponsor button and a concise README link, reusing an existing verified profile.
This independent task does not advance the engine queue or change payment details.

**Complete when:** `.github/FUNDING.yml` names the verified recipient, README support
copy matches and retains experimental status, the maintenance procedure/agent rule
and progress/queue agree, and YAML/source/strict-doc checks pass. Commit/push the
source and verify the live repository's Sponsor panel and destination. Report any
actual account/setup dependency explicitly; do not claim that a redirect to a
regular profile accepts sponsorship. No new tiers, feature promises, payout
changes, engine work or website deployment are required by this task.

**Produced artifacts:** minimal FUNDING YAML for MasteryEducation, a concise README
support section identifying Tokenizer Inc., the internal sponsorship procedure
and linked agent rule. Recipient is recovered from earlier configured funding and
verified as an active Sponsors page, with monthly/one-time custom support. YAML,
README consistency, source/rendered docs and the 42-ID queue checks pass. Source
closure leaves remote button/readback and documentation CI as delivery checks;
engine selection remains MDB-0036 and central profile/tiers/payouts are unchanged.

## MDB-0043 Sponsors profile description

**Outcome:** replace the central Sponsors listing's outdated education-portfolio
claims with the owner-approved current-work bio and introduction, preserving the
existing account/recipient and financial settings.

**Complete when:** exact short bio/introduction source is retained in
`internal/sponsorship-profile.md`, both fields are saved through authenticated
Sponsors settings, and independent API/public readback matches. Keep AI-assisted
workflow, experimental status and support use factual, with no unsupported public
source, production or delivery claims. Record any required platform authentication
as a pending checkpoint, never a successful update. Update the current sponsorship
procedure, queue and receipt and commit/push the task-owned source. Do not change
tiers, payout/account identity, featured work or engine selection.

**Produced artifacts:** exact [published profile source](../sponsorship-profile.md)
and the updated sponsorship procedure. GitHub confirms the dashboard save; both
GraphQL description fields match exactly, and public browser readback shows the
rendered introduction and MeridianDB link. The short bio is 139/250 characters.
Replace the obsolete 80+ websites claim with current experimental work and factual
AI-assisted development/testing wording. Other profile settings and the ready
MDB-0036 engine selection are preserved; no site deployment is required.

These initial MasteryEducation setup/copy outcomes are historical. MDB-0045 owns
the later dedicated MeridianDB recipient and current profile source; prior exact
copy remains in Git and progress history.

## MDB-0044 MeridianDB visual identity

**Outcome:** create precise SVG master artwork for MeridianDB and export PNGs for
GitHub and the public website. Integrate the mark with the repository and existing
documentation theme, preserving readable navigation and both color schemes.

**Complete when:** self-contained editable SVGs and derived PNGs are committed,
dimensions/backgrounds/use cases are documented, conversion can be repeated, and
light/dark/mobile and small-icon rendering are inspected. Keep source attribution
and applicable licenses with the assets. Verify README/site links, strict docs,
both URL layouts, queue integrity and actual automatic publication after push.
Provide a GitHub-upload-ready image without changing account identity, funding
settings or the engine cursor. This visual task introduces no Maven dependency.

**Produced artifacts:** two SVG masters, a dark wordmark variant, six PNG exports
and an updated compatibility favicon. Add a pinned local exporter, font attribution,
public download guide and internal brand maintenance procedure linked from AGENTS.
README selects light/dark SVGs; the website header/favicon use derived PNGs.
Exports are repeatable and self-contained; 1024-pixel GitHub artwork is 32,746 bytes.
Both strict site layouts pass for 50 pages; desktop/mobile light/dark, circular
crop, small icons, navigation and search are inspected. Source completion leaves
remote CI/automatic Pages/live readback as delivery checks; the organization avatar
and ready MDB-0036 engine selection remain unchanged.

## MDB-0045 Dedicated MeridianDB sponsorship

**Outcome:** complete the owner's requested setup after approval of the dedicated
MeridianDB Sponsors account. Publish project-specific bio/introduction, feature
the public repository and upload the existing logo to the organization profile.
Align organization/repository descriptions and website links with the current
experimental Java bitemporal identity.
Switch repository funding/README links only after verifying the live new listing.

**Complete when:** profile source matches saved API/public copy, the organization
avatar displays the prepared logo, and the actual repository Sponsor button opens
the MeridianDB listing. Reconcile current sponsorship/brand guidance and retain
earlier recipients/copy as dated history. Validate funding YAML, README/link
consistency, strict docs, queue/card integrity and task-owned diffs; commit/push
with CI and remote readback. Bank/tax/payout settings, new tiers/financial promises
and deletion of the older organization are separate from this public setup.
Preserve the engine cursor and experimental status.

**Produced artifacts:** exact project profile/identity source, MeridianDB FUNDING
and README links, current migration/avatar procedures and scoped receipts. GitHub
saves the bio/introduction and featured repository; API readback matches both
descriptions (109-character bio). Upload the existing 1024-pixel logo, review its
crop and verify the new public avatar. Organization/repository descriptions and
website links match the experimental project. Funding/metadata/strict-doc and
45-ID queue checks pass. Remote source delivery, docs CI and the actual repository
Sponsor link remain delivery checks; no new tier, account deletion or engine work.

## MDB-0046 Temporal geometry and index research

**Outcome:** explain bitemporal state as two-dimensional geometry with asymmetric
temporal roles, distinct from geospatial topology and distance. Research reusable
Lucene/Solr primitives without treating a point/range index as temporal semantics.

**Complete when:** a navigable public architecture note and precise direct SVG
explain points versus regions/four endpoints, raw assertions versus resolved state,
overlap/retraction/predicate correctness, query-visible prefix and coordinate limits.
Include dated primary evidence and candidate experiments in the existing measured
index item. Reconcile README/concept/architecture/research links and SVG versus
generated PlantUML/Mermaid rules in active callers. Validate source/rendered docs,
both site layouts, SVG and light/dark/mobile use, queue/DAG and diff; commit/push
and verify CI/automatic Pages/live content. This task changes no engine behavior,
Maven dependency, temporal promise or implementation cursor.

**Produced artifacts:** [temporal geometry/indexing note](../../docs/architecture/temporal-geometry.md)
and directly authored SVG. Explain asymmetric axes without global precedence,
two logical dimensions/four bounds, original assertions versus derived regions,
retraction/predicate suppression and index publication. Record current primary
research and future experiment criteria; reconcile diagram-format guidance and
public callers/navigation. The illustrated history passes 24 literal existing-
engine checks; both URL builds pass for 51 pages and rendered narrow/theme use is
reviewed. No index/dependency adoption, temporal change or new benchmark follows.
Remote CI/Pages/live content remain delivery checks in source completion.

## MDB-0036 Optional S3-compatible object access

**Parent:** MDB-0011. **Outcome:** implement immutable object creation and bounded
reads with a maintained provider library, using MDB-0035's contract. Decide optional
adapter packaging in a focused ADR so local embedding has no mandatory cloud SDK;
research current artifact versions and compatibility before every Maven change.
Use a pinned local emulator or controlled protocol fixture, with no real-cloud
credentials, provisioning, deployment or spending.

**Complete when:** the same object-access cases pass locally and against the
provider implementation, including conditional create races, conflict/absence
classification, verified same-content retry after a lost response, changed objects,
opaque ETags distinct from SHA-256, short/unexpected range responses, transport
failure and response/resource limits. A controlled fixture must exercise the real
SDK and HTTP requests; an in-memory fake is not provider conformance. Document the
exact verified capabilities and fixture limits. This step publishes no selected
checkpoint and changes no acknowledgement guarantee.

**Produced artifacts:** separate optional `adapters/s3` Maven artifact using AWS SDK
2.57.8 and URLConnection, conditional signed fixed-length writes, whole-image
length/hash reads, strict conflict/absence and uncertain-result handling, bounded
control/error bodies, explicit timeouts/one attempt, and observed response cleanup
with caller-owned credentials. Core attaches only its reusable abstract test
contract and gains no runtime dependency. ADR 0022/S3 guide/build/status/agent
callers document exact configuration, limits and fixture scope. Clean verification
passes 970 core and 46 optional cases; fourteen core-JAR examples, dependency/archive
checks, 42 documentation QA tests and both 53-page URL builds pass. Linux/Windows
CI checks both builds; source push, CI/Pages and live readback succeed, with
exact delivery evidence in progress history. No remote selection/engine/deletion
guarantee or live-service qualification is introduced.

## MDB-0037 Conditional object checkpoint publication

**Parent:** MDB-0011. **Outcome:** specify a versioned remote selection protocol,
including immutable manifest identity, predecessor binding and conditional head
selection, then implement it over the provider adapter. Verify the full dependency
graph and actual exclusively owned local journal prefix before selection. Declare
the supported single-writer namespace binding and reject unsupported modes;
metadata CAS is not distributed writer ownership.

**Complete when:** genesis, competing/stale selections, dependency absence/corruption,
lost responses before/after effects, retries, token/ABA hazards and corrupt selected
metadata have exact outcomes. Never fall back silently from a damaged selected
graph. Bound metadata/object/identity work and retain old/pending/uncertain objects.
Test the real SDK/fixture protocol and reuse existing manifest/prefix fixtures.
Include copied stores with equal lineage but divergent journal tails: lineage
alone is not ownership. Specify which checks are enforced, which exclusive-placement
conditions are deployment assumptions and how observed predecessor/prefix conflicts
refuse publication and later cleanup. Do not imply detection of every unseen clone
without a distributed admission protocol.
Document uncertain-result reconciliation and separate remote selection from local
commit acknowledgement; installation selects only verified complete history.

**Required children:** MDB-0047 defines the protocol, pure bounded head codec and
metadata/transition checks; MDB-0048 implements actual SDK conditional head access;
MDB-0049 implements verified publication, actual owned-journal prefix/binding,
durable uncertain-intent reconciliation and parent integration/failure evidence.
The split preserves this complete acceptance contract. Each child's external
prerequisites include MDB-0010/MDB-0036; later work may be refined again while
retaining outcomes. MDB-0038 continues to depend on the complete parent.

**Completion evidence:** MDB-0058 completes MDB-0037's original integration and
required children through the actual SDK/combined fixture and supported local
owners. Exact original preconditions, complete graph/actual-prefix checks, shared
budgets, conflicts/loss/late effects across reopen, process stops and retained
native result evidence pass. No remote engine recovery or reclamation is inferred;
the source/delivery receipt records exact counts and remaining deployment limits.

## MDB-0047 Bounded object checkpoint head

**Parent:** MDB-0037. **Outcome:** specify remote selection and implement its pure
bounded head representation, codec and metadata/adjacent-transition checks. Keep
selection revision, manifest generation and temporal cutoff distinct. Bind exact
canonical journal lineage by digest, preserving the full existing UTF-16 domain;
namespace/publisher/publication IDs are identity checks, not distributed ownership.

**Complete when:** a navigable protocol/ADR defines exact wire bytes, predecessor
identity, genesis/successor rules, naming, bounded work, single-writer deployment
assumptions, copied-writer/prefix limitations and uncertain-response handling.
Independent golden bytes/full IDs, strict version/flags/footer/reference and
length/domain cases pass. Pure checks reject changed bindings, stale/overflowed
revisions, wrong predecessors, reused adjacent publication identities and regressing
or changed same-cutoff prefixes. Manifest binding verifies exact encoded identity/
length/lineage/prefix without claiming dependency or actual-journal validation;
cover different manifest generations and large/unpaired-UTF-16 lineage. Preserve
existing codecs/temporal behavior and JDK-only core; run core/optional builds,
docs/rendered/caller/queue checks and deliver normally. Actual conditional I/O,
graph publication, engine recovery and deletion remain unimplemented by this child.

**Produced artifacts:** JDK-only ObjectCheckpointHead record, 256-byte v1 codec,
typed pure failures and verifier for independent binding, exact predecessor/
adjacent revision and complete manifest metadata. Canonical lineage digest
preserves the full existing UTF-16 domain; remote revision remains independent
of manifest generation and cutoff. Protocol/ADR 0023 specify namespace names,
future conditional selection, durable intent, full actual-journal/graph checks,
lost-reply handling and copied-writer/ABA limits without claiming implemented I/O.
Independent vectors and 46 focused checks pass; full core/optional builds pass
1,016/46 tests and 14 core-JAR examples. Both 55-page URL builds and rendered/
caller/queue checks pass. Source delivery, Linux/Windows CI, automatic Pages and
live readback succeed; exact evidence is in progress history. Parent MDB-0037
remains expanded.

## MDB-0048 Conditional S3 head access

**Parent:** MDB-0037. **Outcome:** add a narrow conditional mutable-head boundary
over the selected maintained SDK, keeping immutable byte access separate. Fetch
complete bounded head bytes and an opaque CAS token from the same GET response;
validate response/cleanup before return, preserving explicit absence/error classes.
Implement create-if-absent and replace-if-observed-token with exact persisted-intent
bytes and bounded responses; no unconditional replacement or silent retry/rebase.

**Complete when:** real SDK/HTTP cases cover conditional genesis, races/stale tokens,
409/412/404/permission distinctions, lost replies before/after effects, token/ABA
faults, malformed/missing tokens/head bytes, limits/timeouts/cleanup and changing
head ETags. Preserve the existing constant-ETag immutable-object cases separately.
Research current artifacts and API semantics, preserve optional packaging/core
independence, document exact fixture/capability limits and deliver normally. These
primitives grant no graph/prefix validation, distributed fencing, engine recovery
or removal authority. Full publication stays in MDB-0049.

**Produced artifacts:** optional `S3CheckpointHeads`, bounded opaque `S3HeadToken`,
immutable scoped `S3HeadObservation` and typed `S3HeadWriteException`; private
client/body owners shared with unchanged immutable access. Separate atomic
changing-token HTTP fixture, 43 SDK/API and 27 type/native-stream cases include
races, exact saved intent/preconditions, lost replies, a gated post-timeout late
write and explicit rollback/ABA limits. Local final clean builds pass 1,016 core /
116 optional tests; fourteen packaged-core examples and archive/dependency checks
pass. ADR 0024, the head guide, public callers, navigation and agent rules describe
implemented primitives and remaining publisher duties; both strict URL builds
pass 57 pages and desktop/mobile light/dark/search review. All fourteen existing
Maven selections are freshly verified. Source `ed4ba02baf9be8834721870959d80c6515d87ee4`
is on origin/main; Java 41246798775, docs 41246798793 and automatic Pages
41246798787 pass. Live guide/ADR/callers, canonical/search and 57-page sitemap
readback are verified. No cloud provider is qualified and no
verified publisher/engine integration or removal is added.

## MDB-0049 Verified object checkpoint publication

**Parent:** MDB-0037. **Outcome:** compose head access with immutable manifest/
segment verification and actual exclusively owned local journal evidence. Specify
and enforce supported namespace/local-publisher placement, durable expected-head/
pending-intent state and failure/uncertainty poisoning/reopen reconciliation before
any conditional selection. Verify selected graph and original prefix before
advancing; install/reuse complete candidate dependencies before head mutation.

**Complete when:** all MDB-0037 criteria pass through real SDK/fixture publication,
including no fallback from a damaged selected graph, bounded metadata/object/
identity work, exact predecessor/manifest/prefix checks, genesis/stale/competing
selections and lost-response/retry outcomes. Test equal-lineage copied stores with
divergent tails: reject observed predecessor/prefix conflicts and preserve pending/
old/uncertain objects; state which unseen clones/rollback scenarios remain deployment
assumptions. An old-head read alone never resolves a still-in-flight write. Retain
local force-before-publication acknowledgement and complete logical history.
No cleanup follows stale previews or uncertain selection. Reuse existing manifest/
actual-prefix fixtures, finish parent integration/docs/delivery and mark MDB-0037
done only when all required children and parent criteria pass. Engine recovery
and reader-safe remote reclamation remain the later separate tasks.

**Required children:** MDB-0050 bounds reads of the actual owner journal before
remote declarations can cause unbudgeted frame allocation/I/O. MDB-0051 composes
complete object graph checks with actual owner-prefix validation and aggregate
budgets. MDB-0052 persists durable expected-head and exact pending-intent state
with explicit ownership, failures and reopen. MDB-0053 integrates those results
with real SDK conditional operations and uncertain-result reconciliation, and
finishes every original parent integration/failure criterion. An old-head GET
alone never establishes completed failure. All children are required; the split
does not implement remote publication or weaken the original acceptance criteria.

**Completion evidence:** MDB-0058 completes MDB-0049's original integration and
required children through the actual SDK/combined fixture and supported local
owners. Exact original preconditions, complete graph/actual-prefix checks, shared
budgets, conflicts/loss/late effects across reopen, process stops and retained
native result evidence pass. No remote engine recovery or reclamation is inferred;
the source/delivery receipt records exact counts and remaining deployment limits.

## MDB-0050 Bounded owned journal prefix

**Parent:** MDB-0049. **Outcome:** add an explicit encoded-byte cap to the existing
owned `LocalJournal.prefix` validation path. Enforce the cap before reading a
framing prefix, allocating/reading a declared header or full frame, and processing
request identities. Preserve exact original bytes/hash, the zero-prefix header,
earlier-cutoff behavior and the existing unlimited entry point.

**Complete when:** typed policy-limit failure occurs without reads beyond the
caller cap or allocation for an oversized declared frame; a healthy owner remains
usable after that failure. Invalid bounds/cutoffs reject before I/O. Actual
corruption/I/O failure still poisons the owner. Observe native fake-file offsets,
short/stalled reads, header/frame boundaries and cumulative limits, including a
same-lineage actual history larger than a remote declaration. Real file tests
retain exclusive ownership, prove force counters unchanged and compare raw bytes
only after owner close. Research Maven selections, run focused/core/optional
checks and reconcile guide/contract/agent/queue documentation before normal
delivery. The cap bounds encoded bytes, not a universal JVM-memory or CPU limit;
callers must separately budget records/identities across repeated graph work.
No graph/publication, force barrier, engine change or cloud qualification follows.

**Produced artifacts:** `LocalJournal.prefix(sequence, maxBytes)` and typed
`PrefixLimitException`, with remaining-byte checks in the owned recovery path
before framing reads or header/frame-body allocation/I/O. The old overload keeps
its behavior. Twenty-six focused cases observe exact read requests, independent
raw-byte hashes, policy versus corruption/I/O outcomes, healthy reuse/append,
real-file exclusion and unchanged force evidence. The runnable journal example
uses a 64 KiB budget. Final local core/optional checks pass 1,042/116 tests and
all fourteen packaged-core examples; core has 144 production classes and no
external dependency beyond java.base. Fresh research keeps all fourteen pins.
Guide/spec/ADR/README/build/S3 counts and agent rules are reconciled; both 57-page
URL builds and rendered/search checks pass. Source
`28eec44b952e854a383f4ae11c8560eec1ce31f8` reaches origin/main; Java 41247796505,
docs 41247796460 and automatic Pages 41247796542 pass. Live guide/spec/ADR/
protocol/build copy, canonical/search and 57-page sitemap are verified. No remote
publisher or startup-recovery byte cap is implemented by this child.

## MDB-0051 Verified object checkpoint graphs

**Parent:** MDB-0049. **Outcome:** resolve namespace-scoped complete manifest and
segment images through immutable object access; verify exact head/manifest/graph
identity, lineage, ranges, request uniqueness and reconstructed prefix. Compare
both predecessor and candidate against the actual exclusively owned journal
using bounded reads. Preflight and share aggregate byte/record/identity/read work
budgets across the entire operation, including repeated dependencies. No fallback
from a damaged selected graph and no unbounded ancestry walk/listing.

**Complete when:** genesis and adjacent graph verification cover absent/corrupt/
wrong-ID dependencies, mismatched metadata and actual prefixes, equal-lineage
copied divergent stores, resource limits before expensive work, cleanup and
immutable result scope. Reuse existing manifest/actual-prefix fixtures and run
real local/object checks. The result is verification evidence, not a selected
head, force receipt or cleanup authority; durable intent and publication remain
required. Preserve original parent acceptance and independent local embedding.

**Produced artifacts:** JDK-only `ObjectCheckpointGraphs`, typed graph failures,
private-constructed immutable Graph/Verification results and explicit Limits/Usage.
Single/pair operations preflight both head-known budgets, bind complete manifests,
reserve all segment work, then verify graphs and bounded actual owner prefixes in
predecessor order. Repeated dependencies count twice; no fallback/ancestry/LIST or
partial result occurs. Checked SegmentCodec range decoding rejects unexpected
header counts before record work; a private decoded source reuses the existing
whole-history algorithm without a second fetch/decode or legacy error change.
Twenty-eight core graph, six actual-read/cleanup and sixteen checked-range cases
pass, plus four actual-SDK graph-consumer cases. Final clean builds pass 1,092 core /
120 optional tests and fourteen packaged-core examples; 153 production core
classes depend only on java.base. All fourteen Maven pins are freshly confirmed.
ADR 0025, a new guide, public callers/counts/navigation and agent rules are
reconciled; both 59-page URL builds and rendered/search checks pass. Source
`5d0255b2f2a6ebef468f26f7366f4ae0d2ac65ec` reaches origin/main; Java 41249949891,
docs 41249949945 and automatic Pages 41249949841 pass. New guide/ADR/caller,
canonical/search and 59-page sitemap live readback are verified.
Durable intent, actual conditional publication/reconciliation and engine recovery
remain separate required stages; parent acceptance is retained.

## MDB-0052 Durable object publication intent

**Parent:** MDB-0049. **Outcome:** persist bounded exact configured target/bindings,
expected-head observation and one pending candidate/operation/precondition under
exclusive supported local publisher placement. Specify trusted durable paths,
capabilities, staged write/force/selection ordering, poisoning and fail-closed
reopen. Preserve original intent identity and bytes through process exit.

**Complete when:** fresh/initialized/missing/damaged state, stale/competing local
owners, before/after-effect I/O, native cleanup and process-exit/reopen cases have
exact outcomes. A missing head never authorizes genesis in initialized state;
no old-head read alone resolves a pending request. Preserve evidence, original
precondition and single-placement assumptions without distributed-fencing claims.
No automatic graph cleanup or engine recovery follows. Finish documentation and
normal delivery without weakening MDB-0049's durable-state criteria.

**Required children:** MDB-0055 implements a bounded exact S3-specific state/
intent representation and pure legal transitions without I/O. MDB-0056 installs
that state with supported local ownership/capabilities, force ordering, poisoning,
fail-closed reopen and process-exit evidence. All original durable-state acceptance
remains required; format success is neither a force nor a remote selection receipt.
MDB-0053 continues to depend on the complete MDB-0052 parent.

**Completion evidence:** MDB-0055 and MDB-0056 now supply the exact pure snapshot
plus durable local installation, supported ownership and failure/process evidence.
Both children preserve original bytes/preconditions, reject missing/damaged newest
state and retain uncertain candidates. No remote publisher or engine integration
is inferred; MDB-0053 retains those criteria. Delivery identities follow its receipt.

## MDB-0055 Bounded S3 publication state

**Parent:** MDB-0052. **Outcome:** specify and encode a bounded versioned S3 target/
binding, independent local state generation, exact expected observation and one
pending create/replace intent in the optional artifact. Preserve original endpoint
URI spelling, signing-region ID, bucket, namespace/publisher/lineage, complete
head images and quoted opaque token without credentials or normalization.

**Complete when:** pure fresh/prepare/exact-selected-candidate metadata transitions
preserve original intent/precondition, reject another prepare while pending, wrong
scope/bindings/images and generation overflow, and expose immutable values/copies.
No reset/adopt/clear/rebase or old-head/absence/error transition clears pending.
Independent golden vectors, every truncation, malformed length/version/flags/
checksum/nested state and expected full-reference checks pass; cap before variable
allocation and preserve token/UTF-16-lineage domain policies. Research Maven pins,
reconcile public format/API/agent/queue docs and run core/optional/website checks
before normal delivery. Pure decoded state proves no authenticated observation,
verified graph, supported ownership, local force, durable installation or selection.

**Produced artifacts:** optional `S3PublicationState`, `S3PublicationStateCodec`
and typed failures; 36 state/transition plus 29 independent-vector codec cases;
public format guide/ADR 0026, reconciled callers/navigation/agent rules and the
completion receipt. Version 1 has a 4,096-byte ceiling (3,923 maximum legal image),
exact lexical target/configuration and original conditional preconditions, positive
independent generation, complete references and no pending-clear/rebase operation.
Final local verification passes 1,092 core / 185 optional cases without skips,
all fourteen packaged-core examples, unchanged core artifact inspection and both
strict 61-page site builds with rendered desktop/mobile/search/decision review.
Maven research freshly reconfirms all fourteen pins. Source
`742d52ed8cc4f6de31db4c7c5fc6d7fef5a68c54`, Java 41252084742, docs 41252084800
and Pages 41252084803 pass remote/live verification in the delivery receipt;
durable installation remains MDB-0056.

## MDB-0056 Durable local publication state

**Parent:** MDB-0052. **Outcome:** install the exact bounded publication-state image
under exclusive supported local publisher placement and trusted durably named
paths. Specify create/open/admission, stable file identity and cross-adapter alias
checks, directory capabilities, staged write/force/atomic selection ordering,
capacity/size limits, poisoning and failure/cleanup/reopen behavior.

**Complete when:** every original MDB-0052 criterion passes with the actual state
codec, including fresh/initialized/missing/damaged newest state, stale/competing
local owners, before/after-effect I/O, uncertain close and real process-exit/reopen.
Persist exact expected/pending bytes and original preconditions before a later
conditional request may be attempted. Reopen neither silently adopts missing/old
remote state nor clears pending intent. Preserve candidates/evidence and explicit
single-placement/no-external-rollback assumptions without distributed fencing.
No automatic cleanup, remote publication or engine recovery follows. Complete
local durable-state integration and documentation/delivery before marking MDB-0052
done; MDB-0053 retains full graph/force/conditional reconciliation criteria.

**Produced artifacts:** optional `LocalS3PublicationState`, dedicated empty create/
existing open, shared control/data identity reservation, immutable selected names,
bounded discovery/read/capacity and write/force/close/atomic-move/directory-force.
Open validates exact configuration and highest state, then resynchronizes. Storage
failures poison; attempted rename is uncertain; cleanup quarantine retains ownership
until process restart. Default Windows placement is explicitly unsupported; ordinary
directory I/O remains an actual failure. The original state codec/core remain unchanged.
34 real API, 31 fault and 21 process invocations pass, plus clean 1,092 core /
271 optional tests and fourteen packaged-core examples. Public guide/ADR 0027,
callers/agent rules and strict 63-page builds are reconciled. Local capacity and
no-external-rollback/single-placement assumptions remain explicit. Git/CI/Pages/
live delivery is verified for source `f20939b8e73ce36e230cb6fbb85456d08f849c02`:
Java 41253545589, docs 41253545552 and Pages 41253545496 pass. Windows explicitly
skips 55 unsupported local-placement optional cases; Ubuntu executes all cases.
The delivery receipt records live canonical/search/63-page sitemap verification.

## MDB-0053 Verified conditional publisher

**Parent:** MDB-0049. **Outcome:** compose graph/actual-prefix verification, ordered
local force barriers, verified immutable dependency/intent installation and durable
expected/pending state with the optional SDK head API. Reconcile exact success,
conflict and uncertainty against the original intent before another publication.

**Complete when:** every original MDB-0049/MDB-0037 criterion passes through the
actual SDK/HTTP fixture and supported local persistence, including genesis,
competing/stale selection, damaged selected graph without fallback, copied divergent
histories, lost replies before/after effects, a still-in-flight late effect,
exact-intent retry/reopen and retained old/pending/uncertain objects. Keep bounded
aggregate work and explicit deployment limits. Preserve local acknowledgement;
remote engine recovery and protected reclamation remain later separate tasks.
Mark both expanded publication parents done only after all children and original
integration acceptance pass, and deliver documentation/CI/live evidence normally.

**Required children:** MDB-0057 composes verified durable preparation without a
mutable selection request. MDB-0058 performs exact pending submission/reconciliation
and all original SDK/HTTP, crash/late-effect and parent integration acceptance.
Neither child alone weakens or closes MDB-0053/MDB-0049/MDB-0037; downstream remote
recovery continues to depend on complete publication parents.

**Completion evidence:** MDB-0058 completes MDB-0053's original integration and
required children through the actual SDK/combined fixture and supported local
owners. Exact original preconditions, complete graph/actual-prefix checks, shared
budgets, conflicts/loss/late effects across reopen, process stops and retained
native result evidence pass. No remote engine recovery or reclamation is inferred;
the source/delivery receipt records exact counts and remaining deployment limits.

## MDB-0057 Verified durable S3 preparation

**Parent:** MDB-0053. **Outcome:** bind actual object/head clients to exact durable
target and independently managed binding/actual journal lineage; require the real
owned journal in its owned checkpoint directory. Refuse unresolved pending before
requests. Preflight local capacity and candidate transition, compare one same-GET
head/opaque token with original expected state, verify complete candidate or both
adjacent graphs against the owner under one budget, force journal/name, install/
verify immutable exact intent and durably store pending. No selection PUT follows.

**Complete when:** actual SDK plus supported local storage cover genesis/replacement,
changed/missing/competing expected state and token, damaged selected graph without
fallback, copied equal-lineage divergence, graph/capacity limits before effects,
immutable intent creation/reuse/conflict/failure, local preparation boundaries and
exact pending reopen. Serialize cooperating resource operations with fixed lock
order, retain caller ownership and all old/failed objects; return only immutable
bounded evidence, not selection/lease/engine acknowledgement. Research pins,
reconcile docs/rules/queue and verify normal delivery. Preserve complete parent
submission/reconciliation/late-effect acceptance for MDB-0058.

**Produced artifacts:** optional S3PublicationPreparer with private immutable result,
actual-client scope checks, shared owned-journal graph verification, ordered local
barriers, immutable intent creation/verified reuse and forced exact pending state.
The local owner adds a package capacity preflight, reused during final installation.
No mutable head PUT or clear/rebase path is exposed. 36 API, 15 boundary/immutable-
reply/process and two concurrency cases pass, plus clean 1,092 core / 324 optional
cases, fourteen packaged-core examples, unchanged core archive/jdeps and both strict
65-page builds. ADR 0028/guide/callers/agent rules and planning are reconciled;
current fourteen-coordinate research retains all pins. Source/CI/Pages/live delivery
is verified for source `2c2341420f0d54a25606156d19151d30f4c963b8`:
Java 41257367765, docs 41257367845 and Pages 41257367777 pass. Ubuntu runs all
cases; Windows has 107 explicit local-placement optional skips. Live canonical/
search/65-page sitemap readback passes. Full conditional submission/reconciliation
stays in MDB-0058; no publication parent is closed by this child.

## MDB-0058 Conditional submission and reconciliation

**Parent:** MDB-0053. **Outcome:** compose MDB-0057 with exact durable pending
submission, original preconditions, fresh bounded head/graph/actual-owner checks
and durable exact-candidate reconciliation. Explicitly handle uncertainty without
old-head clearing, rebase, fallback, cleanup or a new publication identity.

**Complete when:** all original MDB-0053/MDB-0049/MDB-0037 criteria pass with actual
SDK/HTTP and supported local persistence, including lost before/after-effect replies,
still-in-flight late effects, conflicts, missing/damaged/competing state, exact retry/
process reopen and retained evidence. Validate aggregate work and native failure
boundaries, preserve local acknowledgement and remote-recovery/reclamation scope,
then close publication parents only with full integration/docs/CI/live delivery.

**Produced artifacts:** optional S3CheckpointPublisher and typed publication failure,
shared actual-resource admission with the preparer and a bounded combined changing-
ETag fixture. Submission preserves original bytes/token and pre-reserves both full
graph passes plus local capacity/nonexhausted generation; reconciliation sends no
remote PUT but installs local expected state after exact observation/graph/force.
39 API, 30 loss/race/late-effect/process and nine native-result/concurrency cases
pass; clean builds pass 1,092 core / 402 optional tests without local skips.
Fourteen packaged-core examples, unchanged core archive/jdeps and strict 67-page
site builds pass. ADR 0029/guide/current callers/rules/planning are reconciled.
All publication parents close with original acceptance retained. Source
`9e07766de3ae79bbcd52af048e72099e86c7840d` is verified on remote main; Java
41259110096, docs 41259110074 and Pages 41259110065 pass, with live guide/ADR/
callers/canonical/search and the 67-page sitemap verified. Windows explicitly
skips 228 core and 185 optional capability-dependent cases; Ubuntu runs all.
Independent MDB-0054 is next.

## MDB-0054 Legacy bounded segment preflight

**Outcome:** use independently expected segment ranges before record allocation/
decoding in existing bounded consumers. Start with HistoryCompactor's manifest
record reservation, then audit inventory/reclamation and related bounded callers.
The raw verifier currently discovers a segment/manifest range mismatch only after
full decoding, so malformed references can spend more record work than reserved.
Per-object format ceilings still apply and no invalid result is returned.

**Complete when:** a one-record manifest reservation with a larger CRC-valid
segment header and nested malformed-body trap is rejected before record decoding.
Reuse the checked range path where appropriate, preserve valid compaction/history/
reference results and ordinary raw codec behavior, and document intentional error-
precedence changes in bounded callers. Verify aggregate resource and cleanup
contracts for affected callers; do not silently change formats, retention,
publication or local acknowledgement. Research current Maven selections, run
focused/core/optional checks and deliver docs/planning/CI normally. This independent
maintenance step is not a missing prerequisite or acceptance relaxation for the
new object graph verifier or expanded publication parents.

**Produced artifacts:** package-private bounded verification shares exact image
admission and the existing complete-history algorithm, using the already checked
segment-range overload before body work. HistoryCompactor and protected inventory/
removal select it; public raw verification, ordinary local publication/load and
standalone segment readers retain their previous behavior. The object graph path
already performs early range checks. No format, API, acknowledgement or retention
change is introduced. Deliberate bounded-path INVALID_HISTORY precedence is public.
Fifteen independent pure cases and eight local boundary/native cases pass, including
one-record reservation versus a CRC-valid larger range with payload/footer traps,
exact byte limits, no force/delete, protection release, poisoning and retained
reopen evidence. Clean 1,115 core / 402 optional tests, fourteen packaged-core
examples, archive/jdeps and both strict 67-page builds pass locally without skips.
Current fourteen-coordinate research leaves all pins unchanged. Source
`d2a30b950fad629ef38cb4bce4d367cb277b2dd3` is verified on remote main; Java
41261721808, docs 41261721797 and automatic Pages 41261721814 pass. Seven changed
pages, canonical URLs, new search heading and the 67-page sitemap are verified.
Windows reports 230 core and 185 optional capability skips, including the two new
native cases; Ubuntu runs all. MDB-0038 is next.

## MDB-0038 Remote checkpoint engine recovery

**Parent:** MDB-0011. **Outcome:** compose the remote checkpoint implementation
with the existing local journal and temporal engine, preserving local durability,
lineage, original request identity, atomic publication and supported historical
cutoffs. Keep all-local operation available without initializing provider code.

**Complete when:** canonical and seeded histories recover through verified remote
checkpoints plus exact contiguous local tails; queries and retries agree with the
independent reference and local backend. Cover foreign/stale/missing/corrupt prefix
and suffix evidence, startup failure cleanup, uncertain remote selection and lost
acknowledgements. Tests demonstrate the actual coverage of a remote checkpoint
and explicitly handle a missing uncheckpointed local tail without inventing data
or claiming replicated durability. Preserve existing force-before-query-publication
acknowledgement, explicit ownership and full journal/history retention. Document
supported access modes, offline behavior and local-storage-loss limits.

**Expansion:** required children MDB-0059, MDB-0060, MDB-0061 and MDB-0062 separate
same-pass verified history materialization, core engine reconstruction, managed
provider startup and writable/full conformance. All original criteria above remain
required; a loaded history is not an engine or remote selection receipt.


**Integrated source acceptance:** required MDB-0059/0060/0061/0062 and the latter's
MDB-0063/0064/0065 children now supply verified history, private recovery, exact
managed admission, forced writes and complete canonical/seeded SDK conformance.
MDB-0065 adds 40 corpus and ten publication/coverage cases and audits all original
criteria above against existing focused admission/fault/owner/process evidence.
Clean 1,272 core /596 optional checks, fourteen packaged examples, archive/jdeps
and strict 78-page docs pass locally. Source `c41b73b6488a2dbd612bcc26f11b32061d561376`
is verified on remote main; Java 41327286700, docs 41327286721 and Pages 41327286708
pass. Fifteen live readbacks/search/sitemap pass; source and delivery are complete. Complete local journal,
online startup and local-only newer acknowledgements remain explicit. Remote
reclamation and general backend qualification remain MDB-0039/0040; MDB-0011 stays
expanded.

## MDB-0059 Bounded pinned object history

**Parent:** MDB-0038. **Outcome:** load immutable ordered checkpoint records from
one supplied pinned head through the same complete graph/actual-owned-prefix
verification pass. Keep independent namespace/publisher/actual-lineage binding,
existing limits/failures and metadata-only verify behavior. This concrete payload
serves MDB-0060's private engine recovery without a public arbitrary replay visitor.

**Complete when:** private records escape only after complete graph identity,
lineage/ranges/request uniqueness/reconstructed-prefix and actual owned-prefix
checks plus object-reader cleanup. Fetch/decode each dependency once under the
existing shared graph budgets; reserve record work before allocation. Immutable
results outlive caller resources and own no handles. Test genesis, exact ordered
original frames/no-ops/identity extremes, suffix exclusion, exact limits, missing/
corrupt/foreign/divergent/late failures and cleanup, through actual local and SDK
consumers. Preserve wire formats and acknowledgement. Document retained heap costs,
caller ownership and the absence of current-head, engine, remote-tail or durability
proof. Research current Maven pins and deliver docs/rules/planning/CI normally.

**Produced artifacts:** ObjectCheckpointGraphs.loadHistory returns private-constructed
History with immutable original records plus Graph/Usage only after same-pass complete
graph/actual-prefix verification. Package-private decoded-source collection supplies
no public visitor; metadata-only verify paths retain no history list. Twenty-two
core and seven real SDK/HTTP cases cover original values/order/no-ops, pinned cutoff,
immutable lifetime, exact budgets/one dependency fetch, foreign/divergent/short
actual histories, late failures and cleanup without publication effects. Clean
1,137 core / 409 optional cases, fourteen packaged-core examples, archive/jdeps and
strict 68-page production/project-path builds pass locally without skips. ADR 0030,
guide/callers/rules and current Maven/recovery research document retained-memory
and ownership limits. Source `c8d3778f495f971f1a4073e69d0cdb69ddab3133` is verified
on remote main; Java 41263069120, docs 41263069123 and Pages 41263069096 pass.
Ten public pages/canonical URLs, new search entries and the 68-page sitemap are
verified. All 29 new cases run on both platforms; Windows retains 230 core and
185 optional capability skips, while Ubuntu runs all. MDB-0038 remains expanded
and MDB-0060 is next.

## MDB-0060 Core pinned checkpoint recovery

**Parent:** MDB-0038. **Outcome:** add a concrete JDK-only read-only engine recovery
entry point using MDB-0059's verified immutable prefix and the same actual owned
local journal. Reuse private LocalDatabase reconstruction; keep backend callbacks
and InMemoryEngine restore outside the public API. Specify factory ownership and
cleanup before implementation; all-local use initializes no provider code.

**Complete when:** canonical and seeded histories recover checkpoint prefix once
and only the contiguous suffix to a frozen observed journal head; every historical
query, no-op and original request lookup agrees with independent reference/local
recovery. Validate full journal order/cardinality and reject foreign/missing/corrupt
prefix/suffix evidence without exposure or fallback. Cover startup/cleanup failures,
immutable views and resource lifetimes. A valid older explicit checkpoint may recover
with a complete matching journal; current-selection provenance belongs to MDB-0061.
Document that a cleanly missing uncheckpointed suffix cannot be inferred without an
independent minimum/expected head. Preserve full journal/history and existing local
API behavior; writable acknowledgement remains MDB-0062's required work.

**Produced artifacts:** LocalDatabase.openFromObjectCheckpoint owns concrete local
startup through close, uses the same private reconstruction path and preserves
caller-owned immutable adapters. Explicit pins bypass local manifest/current-head
selection without changing ordinary open modes. Frozen H and optional independent
minimum distinguish available coverage from unknown lost tails. Twenty-nine focused
cases plus 24 unchanged canonical/query shape cases and 16 persisted-seed cases
verify private startup, all historical cutoffs, original identities, no-ops,
strict evidence, actual owner cleanup and post-adapter-close queries. Full clean
1,206 core /409 optional checks pass locally without skips; fourteen packaged-core
examples, 155-class JDK-only archive/jdeps, 42 docs-rule fixtures and strict 70-page
production/project-path builds pass. ADR 0031, the new guide and current callers/rules
record explicit authority, complete-journal requirements and retained heap/replay
costs. Source `7912f2ffa64cfb7469b1a4663f131b77d3127e3f` is verified on remote
main; Java 41266850347, docs 41266850387 and Pages 41266850349 pass. Ubuntu runs
all 1,206 core /409 optional cases; Windows has 298 core /185 optional capability
skips (68 new native cases). Fourteen examples pass per platform. Live guide/ADR,
fourteen current pages/canonicals, new search entries and the 70-page sitemap pass.
MDB-0061 is ready; MDB-0038 remains expanded and writable recovery remains MDB-0062.

## MDB-0061 Managed S3 recovery admission

**Parent:** MDB-0038. **Outcome:** compose actual S3 client scope, independent binding,
durable local expected/pending state, exact fresh head observation and core read-only
recovery. Use narrow implemented provider APIs; do not infer startup authority from
copied IDs, a metadata Verification value or a token-only response.

**Complete when:** real SDK/HTTP plus supported local placement covers exact expected
selection, fresh token/body scope, absent/foreign/stale/corrupt state, pending/uncertain
selection and explicit existing reconciliation before recovery. Preserve all pending
and failed evidence; never clear/rebase/adopt or silently select another graph. Cover
acquisition/cleanup/credential ownership and process reopen, local-tail coverage and
missing-tail limits. Document online/offline access modes and unsupported placement;
no cross-host fence, missing-data reconstruction or replicated durability is claimed.

**Produced artifacts:** optional S3Database owns existing metadata and private core
recovery until close, admits actual configured clients, requires exact fresh
expected body/token and refuses pending without network access or adoption.
Independent path/lineage/prefix and optional minimum preserve complete-journal
coverage; no mutable owner/delegate escapes and no remote mutation is introduced.
Fifty SDK/actual-local cases plus thirteen startup/preflight/lifetime/concurrency/
process cases cover exact/corrupt/stale/missing/foreign evidence, explicit pending
reconciliation, cleanup and caller resources. Clean 1,206 core /472 optional cases
pass locally without skips; fourteen packaged-core examples, 155-class JDK-only
core/49-class optional archives, 42 docs-rule fixtures and strict 72-page builds
pass. ADR 0032, guide/callers/rules and fresh Maven/AWS research document online
admission, historical observation, no durable path certificate, missing-tail limits
and default Windows metadata placement. Source
`fc366509873a6a4f48eac27595f6017d6344f4d7` is verified on remote main; Java
41313228037, docs 41313228027 and Pages 41313228113 pass. Ubuntu runs all
1,206 core /472 optional cases; Windows reports 298 core /245 optional explicit
capability skips, including 60 new native cases. Fourteen examples pass on each.
Live guide/ADR, fourteen current pages/canonicals, new search entries and the
72-page sitemap pass. MDB-0062 is ready; MDB-0038 retains all original criteria.

## MDB-0062 Writable remote recovery conformance

**Parent:** MDB-0038. **Outcome:** enable supported writable recovery after MDB-0061
while preserving private prepare, owner append/force, atomic query publication and
existing local acknowledgement. Complete every original MDB-0038 integration gate.

**Complete when:** actual remote checkpoint plus exact local tail recovers canonical/
seeded historical queries and request retry behavior, then new and duplicate writes
match the local backend/reference. Exercise startup barriers, append/force/publication
faults, lost acknowledgement, pending/late remote selection, process stops and exact
reopen without fallback, repair or history loss. Account for verification/replay costs
and retain full journal/evidence. Document actual checkpoint versus tail coverage,
local-storage-loss and offline behavior. Close MDB-0038 only after all children and
its original SDK/engine/temporal/cleanup/conformance criteria pass and delivery is
verified; remote reader-safe reclamation remains MDB-0039's separate requirement.

**Expansion:** required MDB-0063 core pinned writable ownership/barriers, MDB-0064
managed S3 writable admission and MDB-0065 full remote writable conformance separate
independently testable outcomes. Preserve every original criterion above and in
MDB-0038. Close both parents only after all required children and integration gates
pass; a core write factory alone satisfies neither parent.


**Integrated source acceptance:** MDB-0063/0064/0065 are source-complete. The actual
managed SDK corpus now reproduces unchanged canonical/query and persisted-seed
histories, historical reads, new/retry/conflict/no-op writes and ordinary local
baselines. Ten new integration cases add late/pending selection, publisher process
exits, reconciliation then managed forced-write continuation, clean-tail-loss/minimum,
missing complete journal and online/no-fallback limits. Existing actual core and
managed native/process/ownership tests supply startup, append/force/publication,
poisoning and lost acknowledgement evidence. The MDB-0065 progress matrix audits
every parent criterion. Source and delivery are complete alongside MDB-0038:
`c41b73b6488a2dbd612bcc26f11b32061d561376` is verified on remote main, Java/docs/
Pages pass and fifteen live readbacks/search/sitemap pass. All remaining remote
reclamation/backend work stays separate.

## MDB-0063 Core pinned checkpoint writes

**Parent:** MDB-0062. **Outcome:** add a concrete JDK-only writable counterpart to
explicit pinned checkpoint recovery. Keep all acquisition, binding, full journal,
minimum coverage and caller-adapter contracts. After complete private reconstruction,
synchronize the actual owned journal and its name before exposing a writable engine
or acknowledging any retry. Reuse existing prepare/append/force/publication and
uncertain-outcome handling without public arbitrary restore/backend callbacks.

**Complete when:** original canonical/query fixtures and seeded dense histories
exercise new and duplicate writes through actual pinned writable reopen at genesis,
split and full checkpoints. Historical answers, no-ops, original identity and request
lookup agree with independent reference/local baselines; exact retries append nothing.
Cover startup barriers and failure cleanup, old views versus hidden/prepared writes,
post-attempt poisoning, actual process stops/reopen, owners and caller adapter
lifetimes. Preserve readonly/local APIs, full journal/history and all failed evidence.
Document local force assumptions, explicit authority, costs and missing-tail limits.
Research current Maven pins and deliver source/docs/rules/planning/CI normally.
Managed S3 writable admission and full remote parent qualification remain required.

**Produced artifacts:** LocalDatabase.openWritableFromObjectCheckpoint shares owned
pinned startup with read-only mode, then invokes the actual journal/name barrier
before returning a writer. Private ObjectSource delegates synchronization/append/
force to existing concrete owners; transaction preparation/publication/poisoning is
unchanged and read-only routes retain a null writer. Twenty-six focused cases plus
40 unchanged canonical/query/seeded invocations cover retries, new/no-op commits,
original identities, old views, owner/adapter lifetimes, failure cleanup and five
real process halt/reopen points. Default seeded workflows make 1,536 actual new
commits through the new factory. Clean 1,272 core /472 optional checks, fourteen
packaged-core examples, unchanged 155-class JDK-only archive/jdeps, 42 docs-rule
fixtures and strict 74-page production/project-path builds pass locally without
skips. ADR 0033, write guide, callers/rules and fresh Maven research document explicit
pin authority, startup barriers, full journal/costs and managed-writable limits.
Source `88b81ab705eef51586f6fb1793d277c4c8beec8b` is verified on remote main;
Java 41317159326, docs 41317159285 and Pages 41317159286 pass. Ubuntu runs all
1,272 core /472 optional cases; Windows reports 363 core /245 optional capability
skips (65 new native cases). Fourteen examples pass per platform. Fifteen live
pages/canonicals, new guide/ADR search entries and the 74-page sitemap pass.
MDB-0064 is ready; MDB-0062/0038 retain all original criteria and required children.

## MDB-0064 Managed S3 writable recovery

**Parent:** MDB-0062. **Outcome:** compose existing exact managed expected-state/client
admission with core pinned writable startup, while retaining all local metadata/store/
journal owners through close. Delegate new/exact retry transactions only after actual
startup synchronization. Keep S3 read-only APIs and caller client/credential ownership.

**Complete when:** real SDK/HTTP plus supported local placement exercises exact expected
selection and pending refusal/reconciliation, complete graph/actual tail validation,
startup/write failure cleanup, poisoned views, retry lookup/reopen and explicit force
acknowledgement. Local transactions do not mutate S3 expected/head selection or supply
remote durability. Preserve original scope/token/body/uncertainty evidence and reject
fallback/adoption/repair. Document supported modes, original checkpoint cutoff versus
later local head and online/offline/placement limits. Full corpus/cross-boundary parent
conformance remains MDB-0065; no earlier child alone closes the parent.

**Produced artifacts:** S3Database.openWritable shares exact metadata/client/body/
token admission, then invokes the concrete core writable factory. Public transact
reuses actual forced local commits and original retries. Read-only opens reject all
transactions. Query capture stays outside the private core write/close monitor;
reentrant close rejects before invalidation, poisoning propagates to every access
and the three local owners remain held until explicit cleanup. Package-private
fault wiring exercises actual core recovery; no public backend callback or core
escape is added. Forty-nine SDK/API/admission and 25 lifecycle/interruption/
concurrency invocations exercise successful writes/retries, refusals, cleanup,
uncertainty, real child exits and nonblocking snapshots. Clean 1,272 core /546
optional checks, fourteen packaged examples, unchanged JDK-only core and test
classifier, 50-class optional archive, 42 docs-rule fixtures and strict 76-page
production/project-path builds pass locally without skips. ADR 0034, managed-write
guide, current callers/rules and fresh unchanged Maven pins document local-only
acknowledgement and remaining full remote corpus/cross-publication qualification.
Local guide/ADR/search and desktop-light/mobile-dark rendering pass without page
overflow; seventeen page/canonical readbacks, search entries and sitemap pass.
Source `4a3e14884c8cf11be3c0757b18592624ff279ff7` and test-fixture follow-through
`7e666b3af237c9d1e6ffe6dfa048e3df19769677` are verified on remote main. Final Java
41325115865, docs 41324730429 and Pages 41324730394 pass. Ubuntu runs every core/
optional case without skips; Windows has 363 core /318 optional capability skips,
including 73 new native cases, while the pure argument invocation now runs without
durable placement. Both platforms pass fourteen examples; seventeen live readbacks,
new search entries and the 76-page sitemap pass. MDB-0065 is ready;
MDB-0062/0038 remain expanded with every original criterion.

## MDB-0065 Remote writable recovery conformance

**Parent:** MDB-0062. **Outcome:** complete every original MDB-0062 and MDB-0038 criterion
through actual managed SDK recovery plus local forced writes, without changing the
temporal contract or local acknowledgement protocol.

**Complete when:** unchanged canonical/query fixtures, persisted-seed reference/dense
histories and local backend comparisons cover complete remote checkpoint plus exact
local tail, new/duplicate/conflicting requests, all historical cutoffs and no-ops.
Exercise pending/late remote selection, lost replies/acknowledgements, startup and
append/force/publication boundaries, actual child-process stops and exact reopen.
Reject foreign/stale/missing/corrupt/divergent evidence, no fallback/repair/data invention;
qualify checkpoint/tail/minimum coverage and local-storage-loss/offline behavior.
Check caller/native ownership, costs/limits and optional packaging. Audit all parent
criteria and close MDB-0062/0038 only with integration and delivery evidence; leave
remote reclamation in MDB-0039 and general backend qualification in MDB-0040.


**Produced artifacts:** S3DatabaseWritableConformanceTest preserves every original
174 literal step per shape (522 total), all sixteen seeds and generator/draw order
through three 32-commit workflows: 1,536 actual seeded new commits. All writes,
retries, conflicts and observed reads use actual managed writable opens after
SDK immutable uploads/preparation/conditional publication. Check every historical
cutoff against unchanged reference/dense and ordinary local baselines, original
request positions, no-ops, pinned views, exact journal frames and unchanged remote/
metadata transaction evidence. S3DatabasePublicationConformanceTest adds ten cases:
a real timed-out late conditional PUT, three publisher child halts, three clean-loss
cutoffs, missing journal, failed online startup and captured-head/no-fence behavior;
resolved publication continues with temporal answers, original/new retries and
forced writes. Explicit bounded corpus fixture and narrow test-classifier oracle/
resource reuse keep production packages unchanged; Jackson remains test-only.
Independent generator/lifetime/coverage and packaging reviews find no actionable
issue. Clean 1,272 core /596 optional checks, fourteen examples, JDK-only 155-class
core, 50-class optional archive, narrow 10-class/three-resource classifier, 42 docs
fixtures and 78-page production/project builds pass locally without skips. ADR 0035,
conformance guide, current callers/rules and fresh unchanged Maven research capture
coverage/costs/placement limits. Fifteen local readbacks, search and responsive QA
pass. Mark MDB-0065 and parents MDB-0062/0038 source-done after auditing every original
criterion. Source `c41b73b6488a2dbd612bcc26f11b32061d561376` is verified on remote
main; Java 41327286700, docs 41327286721 and Pages 41327286708 pass. Ubuntu runs all
1,272 core /596 optional cases; Windows has 363 core /368 optional explicit capability
skips (all 50 new native cases). Fourteen examples pass per platform. Fifteen live
page/canonical readbacks, both new search entries and the 78-page sitemap pass.
MDB-0065 and parents MDB-0062/0038 are complete; MDB-0039 is next and MDB-0011 stays
expanded.

## MDB-0039 Reader-safe object reclamation

**Priority state, November 2, 2026:** deferred. Preserve the original outcome,
expansion metadata, all required-child criteria and completed evidence below.
The Linux local path proceeds independently; re-admission does not waive any
unfinished requirement.

**Parent:** MDB-0011. **Outcome:** define and implement a supported remote cleanup
protocol covering reader admission, checkpoint publishers, selected and explicit
retained/backup roots. Preserve all logical history, full journal and unknown or
diagnostic evidence. Retain objects when coverage or liveness is uncertain; local
file locks do not establish protection for readers on another host.

**Complete when:** at least the documented supported mode safely removes proven
obsolete remote metadata/dependencies while actual live readers and concurrent
publication are protected. Exercise admission/removal races, stale plans, retained
roots, interruption before/after each effect, uncertain deletes and complete safe
reopen with real SDK/fixture calls. Recompute selection/roots under the protocol;
order metadata retirement before unused dependencies and bound verification work.
Keep unsupported cross-host admission/leadership modes explicit. A preview or
universal no-op deferral alone does not satisfy the original reclamation gate.

**Expansion:** required MDB-0066 protocol/staged plan, MDB-0067 coordinated admission/
durable hazards, MDB-0068 bounded verified inventory, MDB-0069 exact conditional
SDK delete and MDB-0070 ordered removal/integration implement separate boundaries.
Every original criterion above remains required. None of protocol, guard, inventory
or a raw delete primitive alone closes this parent. Downstream MDB-0040 still waits
for actual protected removal and integration, not a universal no-op or manual sweep.

## MDB-0066 Remote reclamation protocol and staged plan

**Parent:** MDB-0039. **Outcome:** adopt a concrete bounded supported-mode protocol
and child acceptance plan before adding destructive provider operations. Research
current primary S3 delete/versioning/condition/uncertainty evidence and relevant
Maven pins; preserve existing temporal/commit/publication formats and raw APIs.

**Complete when:** public protocol and ADR distinguish proposed guarded access from
current no-delete APIs; bind one canonical local placement, fresh coordinated
namespace bootstrap, participating readers/publishers and explicit retained roots.
Specify shared versus exclusive admission, durable per-attempt remote hazards and
uncertainty surviving close/restart, same-GET body/tag evidence, unversioned supported
bucket mode, bounded inventory and metadata-before-dependency effects. Resolve late
request/key-resurrection races without claiming GET, fresh ETag, retry success, client
close or process restart proves earlier requests drained. Record fail-closed behavior,
legacy/cross-host/versioning limits, concrete future tests and all parent criteria
across required children. Independent review and strict docs/navigation/search/
responsive QA pass; commit/push and verify automatic docs/Pages normally. No remote
API, Java behavior, deletion or parent implementation completion is claimed.

**Produced artifacts:** ADR 0036 and the remote-reclamation protocol accept a fresh
cooperating single-placement/never-versioned mode, permanent guard identity, complete
root catalog before remote use, shared versus exclusive admission, per-attempt durable
hazard/completion evidence and sticky unknown outcomes. Same-GET identity/tag evidence,
bounded root verification and head→manifest→segment ordering precede actual deletion.
Each terminal result must settle durably before the next mutation. Unknown-root bytes
alone do not protect shared dependencies; every nonretired/failed root must contribute
its dependencies or cleanup defers. Existing unguarded APIs and legacy/cross-host/
versioned modes remain separate. Fresh primary sources and independent design/public
reviews pass; 42 docs fixtures, strict 80-page production/project builds, nine local
readbacks/search/sitemap and light/dark/mobile QA pass. Current callers/rules and
Maven research evidence are reconciled, including stale public roadmap wording about
already completed writable conformance. Source
`b883d420923a164c2545980322447abe43253b55` is verified on remote main. Docs
41341579887 and Pages 41341579894 pass; nine live pages/canonicals, both new search
entries and the 80-page sitemap pass. No Java/POM/runtime deletion change occurs
and no Java workflow is triggered. MDB-0066 is complete; MDB-0067 is ready and
MDB-0039 remains expanded until all four implementation children and its original
criteria pass.

## MDB-0067 Coordinated S3 namespace admission

**Priority state, November 2, 2026:** deferred. Preserve the original outcome,
expansion metadata, all required-child criteria and completed evidence below.
The Linux local path proceeds independently; re-admission does not waive any
unfinished requirement.

**Parent:** MDB-0039. **Outcome:** implement opt-in coordinated single-placement
namespace binding, shared activity/exclusive maintenance and durable per-attempt
remote mutation accounting under the accepted protocol. Keep legacy/raw APIs
separate and production core independently JDK-only.

**Complete when:** fresh explicit create and validated existing open bind exact
actual target, namespace/publisher/lineage and permanent local control identity;
missing/corrupt/copied/rolled-back evidence never silently initializes an existing
namespace. One JVM native channel per guard identity and shared cross-process
admission protect all participating reader/publisher/upload lifetimes; exclusive
maintenance defers against any live activity and blocks new activity. Guard inode
is never renamed/replaced; durable binding/hazard generations live beside it.
Register every managed root and dependency set durably before remote upload/use;
pending and failed candidates retain dependency protection until explicit validated
retirement. Fresh enrollment and all root-writing paths establish complete catalog
authority; unknown/unregistered roots or uncertain coverage refuse reclamation.
Before every possible remote mutation, durably record a distinct attempt and its
exact key/condition/identity; clear only that attempt on a proven terminal outcome
and successful durable settlement. Unknown effects and local settlement uncertainty
retain quarantine across processes and prohibit automatic clearing through observation,
logical publication reconciliation, later retry success, close or restart. No mutable
client/delegate escapes participation; acquire in one documented order and preserve
caller credential ownership, invalidation/cleanup/suppression and native-close
quarantine. Real SDK, local I/O, owner/alias, process and admission-race tests pass;
preserve existing unguarded behavior and explicitly unsupported legacy/cross-host
modes. Pin bounded state images/capacities and independent vectors before I/O.

**Expansion:** required MDB-0071 pure bounded state/codec, MDB-0072 permanent guard/
durable ledger, MDB-0073 participating SDK sessions/root registration and MDB-0074
integrated admission/uncertainty conformance separate wire declarations, native
ownership and effect provenance. Preserve every original criterion above; neither
pure metadata nor a native lease alone closes MDB-0067 or authorizes cleanup.

## MDB-0071 Bounded S3 coordination state and codec

**Parent:** MDB-0067. **Outcome:** implement immutable exact namespace/placement,
root/dependency and individual remote-attempt declarations plus a bounded versioned
canonical codec before native or SDK integration.

**Complete when:** exact lexical target and independent namespace/publisher/lineage/
placement identity survive encoding; root head/manifest metadata bind exact identity,
lineage and prefix and expose immutable dependency declarations. Bound root images,
counts and aggregate reference work before allocation; reject conflicting shared-key
references, duplicate/reused retired roots and noncanonical ordering. Track one
unresolved attempt with compound placement/contiguous ordinal/nonce identity, exact
operation/key/content/condition and exact last terminal declaration. Reject gaps,
overflow, foreign or changed-attempt settlement, arbitrary status/condition refresh
and catalog changes while unresolved. Retain retirement tombstones and capacities.
No decoded state, pure transition or caller-supplied completion proves actual remote
provenance, force, complete root liveness, rollback detection, admission or deletion.
Independent byte/hash vectors, malformed/truncated/nested/limit/immutability and
transition checks pass. Research latest Maven pins; deliver code/tests/docs/rules/
planning/CI normally, keeping all native/SDK and parent qualification pending.

**Source outcome, November 1:** pure immutable model/codec and independent literal
vectors implemented; 108 new checks (50 state/58 codec) and the 704-case optional
suite pass, alongside 1,272 core checks and fourteen packaged examples. Format,
ADR 0037, current public callers, rules and planning are reconciled; 42 docs fixtures
and both 82-page builds pass. Native/SDK provenance and parent integration remain
pending. Source `86310645e84a82004c7b2d8f0da5dd8e27671abf` is delivered; Java
41345469509, docs 41345469586 and automatic Pages 41345469623 pass, including actual
deployment. All 108 pure cases execute on Linux/Windows; existing native skips
remain separate. Thirteen live canonical/content routes, both search entries and
82-page sitemap pass. Progress history retains full delivery evidence.

## MDB-0072 Local coordination guard and durable ledger

**Priority state, November 2, 2026:** deferred. Preserve the original outcome,
expansion metadata, all required-child criteria and completed evidence below.
The Linux local path proceeds independently; re-admission does not waive any
unfinished requirement.

**Parent:** MDB-0067. **Outcome:** install and reopen the bounded coordination
state through permanent native guard identity and durable immutable generations.

**Complete when:** explicit fresh create versus existing open bind independent
actual configuration and canonical placement, retain one registered native channel
per JVM identity and shared cross-process activity versus exclusive maintenance.
Guard inode is never replaced; state lives beside it. Reserve identity/capacity
before effects, force actual files/names, fail closed on newest damage/missing
state, preserve unknown evidence and uncertain native-close quarantine. Acquire
in the accepted monitor order, invalidate before independent cleanup and test
cross-JVM admission/alias/close/startup/failure/process boundaries. Persist intent
before any future remote mutation and validate exact matching durable terminal
records on reopen; no observation/restart/reset clears an unresolved attempt.
Document state/history/force costs and unsupported placement; no SDK activity or
root liveness provenance follows from the ledger alone.

**Expansion:** required MDB-0075 permanent native guard, MDB-0076 durable generations
and MDB-0077 native/ledger integration preserve every original criterion above.
The guard alone operates on an existing empty control file; fresh enrollment,
canonical bound state and durable receipts remain ledger/integration obligations.

## MDB-0075 Permanent local S3 coordination guard

**Parent:** MDB-0072. **Outcome:** implement bounded shared/exclusive native leases
on one existing permanent guard in a trusted stable local placement, before the
ledger or participating SDK sessions exist.

**Complete when:** use one registered common-ownership channel per canonical guard
in a loaded adapter copy/JVM; share only actual shared locks, defer exclusive/reader
conflicts without another local channel and protect cooperating process admission.
Check original regular/nonsymlink/empty file identity before opens and while leased;
reject aliases, missing/replaced paths and unsupported identity/shared capabilities.
Bound placements and references. Expose no raw channel, lock or delegate; force
through the sole owner. Close invalidates first, attempts release and close,
preserves primary/suppressed errors and retains uncertain close/overlap quarantine
even if Java reports closed. Test injected failure/invalidity/capacity, native
cross-adapter aliases, real child-process exclusion/shared admission and process
exit without claiming directory durability or remote fencing. Fresh Maven/JDK
research, code/tests/docs/rules/planning and exact-source CI/Pages pass. No creation,
enrollment, durable state, terminal provenance or SDK/reclamation authority follows.

**Source outcome, November 1:** package-private native guard and 58 focused checks
(43 registry/fault, 15 native/process) implemented. Full 1,272 core /762 optional
suites, fourteen packaged examples, 42 docs fixtures and both 84-page builds pass.
ADR 0038/guide/current callers/rules preserve the existing-file-only boundary;
no durable ledger or enrollment follows. Fresh research and independent review
are complete. Repair `dfdc24d28b0558a93aa4ce8c141a9fc304b828fd` is delivered. Java
41388769784, docs 41388769816 and Pages 41388769799 pass, including deployment.
Linux runs all 58 guard checks; Windows executes all 43 injected cases and reports
13 native identity-capability skips. Production identity stays strict. Fourteen
examples pass per platform; fifteen live canonical/content routes, search and
84-page sitemap pass. Progress history preserves initial failure and repair evidence.

## MDB-0076 Durable S3 coordination generations

**Parent:** MDB-0072. **Outcome:** compose permanent guard ownership with explicit
fresh creation/existing open, exact bound state and full immutable receipt history.

**Complete when:** independently bind actual lexical target, namespace/publisher/
lineage/placement and canonical local location; preserve the permanent guard name
and force it through its owner. Probe directory capability before mutation; reserve
identity, intent/completion file/name/generation capacities before effects. Retain
all immutable intent/completion generations, serialize generation writers across
processes separately from shared activity admission without an implicit lock
upgrade, validate contiguous exact history and
highest unique state without fallback, preserve damaged/missing/unknown candidates
and synchronize actual files/names before admission. Pending remains quarantined
across reopen; no reset/observation clears it. Inject storage/force/rename/close
failures, argument/capacity preflight and exact state/reopen cases. Pure state bytes
or local ownership supplies no actual terminal-response or remote-liveness proof.

## MDB-0077 Coordination guard and ledger conformance

**Parent:** MDB-0072. **Outcome:** verify every original native/ledger criterion
through concrete integrated creation, ownership, durable transitions and processes.

**Complete when:** fresh/open identity, alias and native quarantine behavior retain
all original evidence and cleanup ordering; exercise process exits across guard,
intent/install/force/completion boundaries with exact reopen outcomes. Race shared
admission/exclusive maintenance across JVMs with ledger ownership; reserve both
completion and intent capacity, recognize only matching valid durable terminal
records and never fall back past damaged newest state. Audit all original MDB-0072
criteria, preserve SDK/raw/local behavior and document costs, supported placement
and uncertainty. Close MDB-0072 only with all required children and integration/
source/CI/docs delivery complete. MDB-0073/0074 retain actual SDK participation,
root registration and full admission/hazard conformance.


## MDB-0073 Participating S3 sessions and root registration

**Parent:** MDB-0067. **Outcome:** compose concrete namespace-owned SDK read/upload/
publication sessions and complete root registration with the actual ledger/leases.

**Complete when:** all participating root-writing paths register complete metadata
before remote use and all mutating wire attempts force exact individual intent
before dispatch, with original condition and capacity reserved. Only that exact
request's qualified terminal response can construct completion; transport/body/
timeout/Error/local uncertainty retains durable quarantine. Actual client scope,
shared/exclusive lifetime, caller credential ownership and no unguarded delegate
escape hold through cleanup. Existing raw APIs remain outside coordinated mode.
Real SDK/HTTP checks cover complete/failed roots, shared dependencies, no-effects
preflight, exact attempts, conditions and unknown outcomes. Logical publication
reconciliation cannot manufacture older physical completion. Keep current metadata/
actual journal/temporal protocols coherent and independently usable; no deletion
or native lease alone claims full admission conformance.

## MDB-0074 Coordinated admission and hazard conformance

**Parent:** MDB-0067. **Outcome:** verify all original coordinated-namespace criteria
through integrated actual native ownership and participating SDK operations.

**Complete when:** fresh/create/open identity, complete roots, actual shared readers/
uploaders/publishers and exclusive maintenance races work across processes; stale/
foreign/copied/missing/corrupt state and native failure preserve evidence. Exercise
intent/dispatch/terminal/settlement/close process boundaries, late held PUTs, unresolved
attempts across restart, same-key reuse refusal and exact-known durable completion
recognition. Preserve reference/temporal/local client behavior, budgets, lifetime/
cleanup and unsupported-host/placement limits. Audit every original MDB-0067 criterion
and close it only with all required children plus source/CI/docs delivery; MDB-0039
still requires verified inventory, conditional delete and ordered removal/conformance.


## MDB-0068 Verified remote reclamation inventory

**Parent:** MDB-0039. **Outcome:** compute an immutable bounded inventory only under
exclusive coordinated admission and clean durable activity evidence, without delete.

**Complete when:** admit the documented never-versioned general-purpose mode and
actual client scope; freshly validate exact mutable selection plus durable local
expected/pending state. Pending/unknown activity or inconsistent selection refuses
cleanup. Protect current selection/immutable intent, selected graph and explicit
retained/backup roots; validate every supplied obsolete root against the actual
owned full journal and complete graphs. Include all nonretired catalog roots and
protect failed/unknown roots' dependencies; candidate lists alone do not prove root
completeness. Refuse uncertain catalog coverage. Require explicit bounded candidate identity,
no listing sweep or unbounded ancestry walk, and retain unknown/orphan/diagnostic
keys. Same-GET full body/SHA-256/length and opaque tag evidence bind exact candidates;
reserve aggregate roots/fetches/bytes/records/identities before requests/allocation.
Deduplicate dependency liveness correctly across roots and order head-intent metadata,
obsolete manifests then unused segments. Preserve all logical history, actual full
journal and costs outside graph budgets. Real SDK fixtures verify shared dependencies,
retained roots, stale/foreign/missing/corrupt evidence, limits and no effects; preview
has no later deletion authority. Existing retention-state binding is durable and
updated under the same coordination, with retired-root admission explicitly defined.

## MDB-0069 Guarded conditional S3 deletion

**Parent:** MDB-0039. **Outcome:** add the narrow exact conditional-delete effect
needed by the coordinated executor, without exposing an unguarded sweeping API.

**Complete when:** actual bounded SDK DELETE uses the same verified candidate key/
original opaque If-Match condition after namespace/scope/capacity admission and
forced per-attempt intent. Only documented, fully validated terminal outcomes settle
that same attempt; classify conflict/absence/auth/transport/body/timeout outcomes
precisely with no silent condition refresh, wildcard or automatic retry. Unknown
or uncertain native settlement durably blocks reuse/publication/reclamation even
if a later GET is absent. Versioned/suspended/directory modes remain unsupported;
no delete markers or arbitrary version deletion count as physical removal. Real
HTTP tests cover exact request/signature, zero-effect preflight, condition races,
known results, before/after-effect dropped or delayed replies, bounded cleanup,
client ownership and same-key resurrection refusal across reopen. No raw delete
primitive alone closes MDB-0039 or authorizes a live bucket operation.

## MDB-0070 Ordered remote reclamation conformance

**Parent:** MDB-0039. **Outcome:** recompute and remove proven obsolete remote
metadata/dependencies while all participating readers and publishers are protected.

**Complete when:** hold exclusive namespace maintenance and actual required local
owners through fresh selection/retained-root/graph verification and all ordered
remote effects. Retire obsolete immutable head metadata before obsolete manifests,
then unused segments; retain live/shared/unknown/pending/diagnostic evidence and
all logical history. Bound complete work, preserve exact per-attempt receipts and
stop on uncertainty with durable quarantine before releasing admission. Reopen
establishes validated state without automatic repair/retry. Exercise actual readers
and concurrent publication/admission/removal races across JVMs, stale previews,
retained/backup roots, interrupted/uncertain removal and delayed effects, plus real
canonical/seeded recovery/retry/new-write histories after successful cleanup. Inspect
remaining exact names/identities/encoded bytes, not promised freed blocks. Demonstrate
normal supported-mode removal (not universal deferral), audit every original MDB-0039
criterion, and close it only with integration/source/CI/docs delivery. Preserve
unsupported host/versioning modes and leave general MDB-0011 backend qualification
to MDB-0040.


## MDB-0040 Local and object backend conformance

**Parent:** MDB-0011. **Outcome:** close the original backend milestone with common
storage/engine conformance through local and S3-compatible implementations.

**Complete when:** original MDB-0011 visibility, retries, uncertainty, capabilities,
reopen, reclamation and promised engine semantics criteria pass through actual
provider calls and local files. Reuse independent canonical/seeded query and request
expectations through publication, recovery and cleanup; cover protocol interruption
and lost responses without overstating device or real-provider evidence. Check
optional packaging with a local consumer fixture, bounded default execution,
clean reproducibility and complete documentation. Close MDB-0011 only after all
required children pass; retain cloud-spending, local-tail and supported-mode limits.

## MDB-0078 Linux local storage priority and format research

**Outcome:** persist the November 2, 2026 Linux-first direction in agent rules,
public architecture/README/roadmap and the executable queue. Keep Windows basic,
retain S3 unchanged and deferred, and separate later Kafka/other providers and
replication. Research Parquet, specialized temporal blocks and Linux/Hadoop I/O
from current primary sources; label design inference and unmeasured performance.

**Complete when:** ADR 0039, the Linux storage guide and active callers agree;
all old IDs/criteria/history are retained, unfinished S3 rows are deferred,
MDB-0079 is next and local benchmarks/candidate have no remote dependency.
Source/rendered docs, navigation/search/canonical/sitemap, both URL layouts and
queue IDs/cards/dependency graph pass. Deliver the planning source normally and
stop; no Linux implementation, new format/native binding or next item starts.

**Source acceptance:** ADR 0039, the Linux storage guide and active README/home/
architecture/build/contribution callers now agree. All 77 original IDs/history and
parent criteria remain; twelve unfinished S3 rows are deferred. New MDB-0079 is
ready and MDB-0080 closes later operational gaps, yielding 80 stable IDs. Forty-two
docs fixtures, both strict 86-page URL builds, fifteen local content/canonical
readbacks, new search/sitemap and light/dark/mobile browser checks pass. Independent
review has no findings. No code, format, Maven dependency or CI behavior changed;
source `4ba55629490c8fd49e5d351af3b9c43cbdf9aa35` is verified on origin/main.
Java 41454600048, docs 41454600128 and automatic Pages 41454600658 pass. Linux runs
1,272 core /762 optional cases without failures/errors/skips; Windows retains
363 core /381 optional capability skips. Fourteen examples pass per platform.
Fifteen live content/canonical checks, both new search entries, the 86-URL sitemap
and published roadmap browser verification pass. The local server is stopped.
MDB-0079 is ready; no subsequent implementation starts.

## MDB-0079 Linux storage contract and qualification harness

**Outcome:** define required local filesystem capabilities and produce a bounded,
repeatable Linux profile/probe/conformance harness over existing local APIs.
Record actual kernel, CPU architecture, JDK, filesystem/mount, device/cache and
lock/identity/rename/file-force/directory-force behavior. Select the first named
profile from evidence; ext4/XFS are candidates, not universal support promises.

**Complete when:** actual Linux runs exercise create/forced commit, crash/reopen,
checkpoint and protected reclamation, alongside explicit unsupported-capability
and ordinary-I/O failure cases. Report every required native case and skip for the
named profile; any unavailable environment is an explicit qualification gap for
MDB-0080, not an assumed pass. Preserve existing temporal fixtures and default
small resource limits. Document local/container/CI versus device evidence, required
capabilities, known gaps and basic Windows smoke expectations without pursuing
Windows native parity. Align CI scope only where justified and research Maven
versions for any Maven work. This harness does not establish power-loss behavior
or production readiness. No cloud spend or privileged environment is presumed.

**Accepted evidence, November 2, 2026:** checkpoint
`520b86da523fbea3cf82b83c70e716d05ca8f664` is verified on origin/main. Java
41470158485 Linux job 125361299119 passes the new driver with source clean,
run 2b00a33982fa4038b7a671108b81d51e, exact 68-suite/1,273-case inventory and all
eight native observations. Actual profile: ext4, kernel 7.0.0-1017-azure, x86_64,
Temurin 25.0.5+8-LTS; required failures/errors/skips are zero. Forty Python guards,
762 optional cases and fourteen examples per platform also pass. Windows retains
364 core /381 optional capability skips. Docs 41470158494 and Pages 41470158499
pass; guide/ADR 0040/callers/rules/nav, 42 docs fixtures, both 88-page builds,
fourteen live readbacks/search/sitemap and light/dark/mobile browser checks pass.

The driver/probe, exact fresh XML/JSON/runtime/capability guards, test-only fixture
factory, bounded failed-process cleanup and POM report-directory binding are
implemented. Local ordinary and rooted tests plus archive/jdeps confirm production
and classifier isolation. Test artifact 13155005925 retains exact per-case JSON/XML/
logs for 14 days, with its digest and expiry in the durable progress receipt.

This is a CI correctness profile, not production qualification: `data=writeback`,
`nobarrier` and `commit=30` are observed mount settings; backing device/controller
and queue/cache facts remain unknown. Preserve these as MDB-0080 gaps. Step
acceptance supplies no production/power-loss or performance claim. Completion
`17a2c0edb37c8dfd723450b53934430aa0ceb073` carries Step-Result completed and is
verified on origin/main. Final docs 41471550359 /Pages 41471550376 and fourteen live
readbacks/search/sitemap pass, including the cleanup clarification. Java runtime
source remains 520b86d with its exact passing Linux evidence above. MDB-0012 is
ready; no workload implementation starts in this invocation.

## MDB-0080 Linux operational hardening and qualification gaps

**Carried qualification gap:** MDB-0079's first hosted CI profile is ext4 with
`data=writeback`, `nobarrier`, `commit=30` and unknown device/controller/cache facts.
Its successful process/fault checks do not select a production mount or establish
force/power-loss behavior. Qualify reviewed deployment settings and backing storage
explicitly before a production claim; preserve this CI profile as correctness evidence.

**Outcome:** use MDB-0079 profiles and MDB-0012/0013 measurements to identify and
close concrete Linux local-library production gaps before an experimental
candidate. Expand into bounded children with explicit criteria before changing
independent concerns. Preserve exact temporal/transaction identity and durability.

**Complete when:** the selected profile has a reviewed gap ledger with implemented
or explicitly scoped outcomes for aggregate memory/cache/recovery bounds,
checkpoint/compaction scheduling and reader lifetimes, error/space diagnostics,
format compatibility/rebuild, backup/restore and operational examples. Accepted
production claims require actual supporting qualification; document remaining
restrictions instead of silently broadening them. Validate adopted changes with
reference, failure/process recovery, resource and workload regression evidence.
Reject unhelpful format/native optimizations when measurements support rejection.
Retain S3 tests/contracts, basic Windows scope and separately planned replication;
no remote backend or cluster implementation becomes a hidden prerequisite.

## MDB-0041 Replication protocol and staged plan

**State:** deferred, outside the single-node prototype and MDB-0011. Admit as an
executable iteration separately; no implementation or library selection follows
from recording this direction. **Outcome:** specify one authoritative writer and
read-only followers with original identity/order, verified checkpoint bootstrap,
contiguous tail replay, bounded lag/backpressure and explicit durable/query progress.

**Complete when admitted:** an ADR and executable conformance plan define duplicate,
gap/conflict, reconnect and uncertain acknowledgement behavior; distinguish local,
asynchronous and quorum commitment; state failure/promotion/data-loss boundaries;
and split transport, follower recovery and any leadership work into bounded steps.
Evaluate maintained consensus implementations with current primary evidence before
quorum/failover adoption. Preserve the existing temporal contract or record an
explicit semantic revision with fixtures. This planning outcome alone implements
no replication and provides no automatic failover or multiwriter guarantee.

## MDB-0012 Reproducible workload baseline

**Prerequisites:** completed local correctness MDB-0010 and Linux profile/harness
MDB-0079. The former MDB-0011 prerequisite is removed under the November 2 priority;
S3, other providers and replication are not required.

**Outcome:** turn the temporal workload descriptions into independently scaled,
repeatable local-engine measurements using correctness-checked data. Keep the
small canonical fixtures as exact correctness gates, not throughput datasets.
State seeds, sizes, entity skew/history depth/overlap/correction and retraction
rates, runtime, hardware, filesystem/mount/device profile, revision, warmup,
cache conditions, memory limits and exact acknowledgement/durability mode.

**Complete when:** another person can repeat the runs and obtain latency
distributions, throughput, CPU/allocation/heap, encoded/allocated storage, bytes
read, read/write amplification, replay and checkpoint/compaction behavior with
limits. Include point/history/interval/aggregate and mixed ingestion/query
workloads; preserve all historical/retry answers. Separate microbenchmarks and
hosted CI correctness from controlled Linux-device measurements. Use a bounded
small default; larger or paid runs need an explicit environment/budget. Record
bottlenecks and candidate experiment decisions without inventing a required gain.

**Required children:** MDB-0081 builds the deterministic bounded action corpus and
independent answers/realized distributions; MDB-0082 implements the local runner,
measurement semantics, resource counters, resets/repeats and result schema;
MDB-0083 evaluates actual Linux runs, failure/evidence integrity and every original
criterion above. The parent remains expanded until all children and integrated
latency/throughput/resource/amplification/recovery/checkpoint criteria pass. A pure
corpus or CI timing smoke alone supplies no complete baseline or optimization claim.

## MDB-0081 Deterministic temporal workload corpus

**Parent:** MDB-0012. **Outcome:** define a versioned bounded workload specification,
deterministic ordered setup/mixed action stream, explicit historical cutoffs and
independent expected outcomes before timing. Separate request attempts, unique
commits, retries, new no-op commits and write operations. Preserve original IDs,
queries and numerical temporal semantics; canonical correctness fixtures stay unchanged.

**Complete when:** small named append/correction/retraction/hot-entity profiles
have stable seeded generation and canonical content/write fingerprints, independent
reference/dense expected-answer checks and actual memory/local-reopen conformance.
Record configured versus observed distributions, entity touches/history depth,
geometric overlap versus resolved correction/retraction effects, query types and
hit/miss/cutoff denominators. Preflight aggregate action/encoding/answer/work
bounds before materialization; preserve exact UTF-16 and bound representation in
fingerprints. Include literal golden behavior, extremes, invalid limits and
isolation/retry/no-op cases. Keep workload support test-only and outside production/
classifier artifacts. Update qualification inventory from complete evidenced tests.
No clock, latency, throughput, device I/O/amplification or production claim follows.

**Source result, November 2, 2026:** v1 corpus/oracle and 36 focused workload checks
meet these criteria. Four defaults have deterministic actions, literal vectors,
observed effect/entity/query counts, independent backward/forward expectations,
actual memory execution and repeated native local reopen. Full macOS rooted core
run passes 1,309 cases /71 suites with zero skips; all previous inventory counts
are preserved. Qualification inventory adds only the three new suites. Optional
762 regressions, fourteen examples, Python 40/docs 42, artifact/jdeps and both 90-page
URL builds pass. Guide/ADR 0041 and rules preserve bounded finite single-operation
scope, seed-stage validation, Random 48 aliases and logical-versus-physical bytes.
Completion `1b41bd397dbe28de4db8fa7f712d3b097f4cdd5b` is pushed; Java 41485888694,
docs 41485888464 and automatic Pages 41485888491 pass. Actual Linux run
`9d224b6cdf9a405580814d93ca473c67` passes all 1,309 cases /71 suites with zero
skips and the exact reviewed inventory/source binding; Windows pure checks pass
with four new explicit native skips. Both platforms retain 762 optional invocations
and 14 examples. Live 12-page/canonical, search and 90-URL sitemap verification passes.
Hosted Linux remains correctness-only with unknown device/cache and known mount
limits. Keep the parent expanded; MDB-0082 is the next recommendation.

## MDB-0082 Local workload measurement runner

**Parent:** MDB-0012. **Outcome:** consume MDB-0081's already generated and validated
actions/answers outside timed loops in a separate developer runner. Define setup,
load, warmup, measured iterations and fresh-store reset so repeated IDs do not turn
new-write measurements into retry lookup. Record exact source/workload/runtime,
profile/durability/cache/memory conditions and unsupported/failed outcomes.

**Complete when:** correctness-checked local writes/queries/recovery/checkpoint/
compaction produce versioned result evidence with per-kind counts, closed-loop
service latency distributions and throughput, CPU/allocation/heap, encoded versus
allocated storage and accurately scoped bytes/I/O/amplification counters. Distinguish
arrival/end-to-end latency and measurement/check overhead, pinned readers, lag and
merge backlog. Bound default work, subprocess lifetimes and retained output; keep
larger/paid runs within explicit environment/budget. Avoid hidden GC/cache-reset or
weak acknowledgement changes. Document unavailable counters as gaps, not zeros;
MDB-0083 owns integrated acceptance and actual Linux evaluation.

**Required children:** MDB-0084 implements bounded in-process measurements and
correctness-checked local operation lifecycles; MDB-0085 adds supervised CLI,
versioned serialized evidence/provenance, Linux allocated/process-I/O accounting,
timeouts and partial/failed outcome admission. Preserve all original criteria.
The parent stays expanded until both children and the integrated runner pass.

## MDB-0084 Local operation measurement core

**Parent:** MDB-0082. **Outcome:** consume prepared corpus outcomes in a bounded
test-only in-process runner with fresh warmup/measured namespaces. Measure forced
commits/retries, pinned queries, journal/checkpoint writable reopen, segment writes,
checkpoint publication, pure compaction planning and publication with an old reader
pinned. Check exact answers/history/identity outside operation clocks.

**Complete when:** typed versioned per-iteration evidence binds corpus fingerprints,
configuration and runtime; records per-phase/kind service latency distributions,
service rate versus loop wall rate and validation/recording boundaries; reports
optional current-platform-thread CPU/allocation and sampled heap with precise
limits. Preserve raw samples, fresh-store resets, explicit unavailable counters,
retained named-file lengths and fixed synchronous/pinned-reader semantics.
Literal metric/boundary/failure tests and actual native lifecycle tests pass;
qualification inventory is reconciled from a complete successful core run. Keep
helpers outside production/classifier artifacts. No cold-cache, sustained arrival,
physical I/O, production performance or completed supervised-runner claim follows.

**Source result, November 2, 2026:** the in-process core and 26 new checks meet
this child scope. Actual four-profile and fresh-repeat lifecycles pass, including
pinned old/new history, startup synchronization and post-close exact journal
identity. Final complete rooted macOS verification passes 1,335 cases /73 suites,
zero skips, with unchanged prior counts and exact new 9/17 inventory. Optional
762 regressions, fourteen examples, Python 40/docs 42, artifact/jdeps and both 92-page
URL builds pass. Guide/ADR 0042 and agent rules preserve measurement scope and
remaining supervised/physical-accounting gaps. Completion `742ea991b6b5298c78ebca31b8b208bd8ca4ef88`
is pushed. Exact-source Java 41545388930, docs 41545388908 and Pages 41545389042
pass. Linux run `f5061c7e00e54d94b7531912978eada5` verifies all 1,335 cases
/73 suites with zero skips and exact inventory/source bindings; Windows passes
with 374 core capability skips. Both platforms retain 762 optional invocations and
fourteen examples. Live fourteen-page/canonical/search and 92-URL sitemap checks
pass. Hosted Linux remains correctness-only; keep MDB-0082 and MDB-0012 expanded.
MDB-0085 is the next recommendation, with no further implementation started here.

## MDB-0085 Supervised Linux workload evidence

**Parent:** MDB-0082. **Outcome:** wrap MDB-0084 with bounded process execution,
versioned JSON/provenance and actual Linux file/process accounting. Record exact
source/workload/runtime/filesystem/mount/cache/heap/profile and telemetry scopes;
validate fresh result binding, counts and successful/unsupported/failed outcomes.

**Complete when:** a repeatable documented CLI retains bounded fresh output,
handles deadlines/child-process cleanup and rejects partial/stale/malformed evidence.
Distinguish journal/named bytes, allocated blocks and accurately scoped process I/O
with defined denominators and explicit unavailable gaps. Source, environmental
assumptions and measurement/check overhead are visible. Exercise valid, unsupported,
failed and interrupted paths and integrate actual CI smoke without comparative
performance claims. Audit every original MDB-0082 criterion before closing it;
MDB-0083 remains required for baseline evaluation. No paid environment, privileged
cache/device action, remote backend or native binding is presumed.

**Checkpoint, November 3, 2026:** JDK-only worker/export, strict Python admission,
Linux process supervisor, source/class bindings, scoped process-I/O and allocated
file accounting are implemented. CLI/docs/ADR 0043 and continuous Linux smoke are
prepared. Full macOS rooted install passes 1,347 cases /74 suites without skips;
inventory adds twelve worker invocations without reducing previous counts. Python
guards pass 86 (five native Linux cases await CI); optional 762, fourteen examples,
archive/jdeps and strict 94-page builds pass. Review fixes include create-only
JSON publication, cleanup despite process-enumeration failure, ambient Maven option
removal and refusal of success after observed deadline exhaustion. Keep MDB-0085
active and MDB-0082/0012 expanded until actual Linux smoke and remote delivery pass.

## MDB-0083 Linux baseline evaluation and conformance

**Parent:** MDB-0012. **Outcome:** run reproducible local baseline workloads in actual
Linux with complete dataset/profile/runtime/seed evidence, warm/cold definitions,
independent correctness and explicit measurement limits. Preserve the known hosted
CI profile as correctness-only; it supplies no production-device durability or
controlled performance comparison. Reuse existing authorization; no paid environment
or privileged cache/device operation is presumed.

**Complete when:** saved runs/results can be repeated, failure/partial/stale result
handling is checked, all original MDB-0012 criteria are audited and latency,
throughput, CPU/heap, encoded/allocated bytes, actual I/O/amplification, replay and
checkpoint/compaction evidence is explained with limits. Name gaps and justified
next experiments from measurements, not a mandatory speed gain. Separate CI smoke,
observational local runs, controlled device measurements and microbenchmarks. Close
MDB-0012 only when the required runner/evaluation and original integration criteria
pass; preserve all child evidence and hardware/production gaps in later work.

## MDB-0013 Measured layout and index experiments

**Outcome:** select one demonstrated bottleneck and compare a narrowly scoped
physical index or layout change against MDB-0012 under equivalent semantics and
durability. Candidate formats and organizations require independent evidence.

The Linux local baseline is the prerequisite; S3 completion is not. Use the
[Linux storage design](../../docs/architecture/linux-storage.md) for candidate
entity-first temporal blocks, column projection/encoding, block pruning/cache and
Parquet comparisons. Account for full-history heap reconstruction and per-commit
copying; a byte codec alone does not solve them. Define version/compatibility and
block-integrity contracts before successor bytes or lazy reads. Evaluate a Linux
I/O primitive only after profiling demonstrates a relevant limit, with a narrow
binding/lifetime/capability decision and equivalent force/acknowledgement behavior.
Reflink/copy is local snapshot acceleration, not node replication. No native library
or format is selected in advance.

Use [temporal geometry and indexing](../../docs/architecture/temporal-geometry.md)
as candidate research, including generic Lucene numeric points/ranges/BKD and Solr
region/date-range lessons. Compare an entity-first ordered/interval structure with
a multidimensional candidate only when the selected workload warrants it. Check
coordinate precision/unbounded bounds, assertion identity, overwrites/retractions,
predicate visibility and exact indexed prefix before measuring. This is experiment
guidance, not an admitted dependency or another mandatory optimization.

**Complete when:** correctness/reference/recovery/compaction checks still pass,
measurements include resource and regression tradeoffs, and an ADR adopts or
rejects the experiment. A well-evidenced rejection is a valid outcome; do not add
an optimization merely to satisfy a feature checklist. Split multiple independent
experiments into separate queue items.

## MDB-0014 Prototype release candidate

**Prerequisites:** MDB-0013's measured adoption/rejection and MDB-0080's scoped
Linux hardening/qualification evidence. S3 completion is no longer required through
an indirect benchmark dependency. The candidate remains experimental; documented
qualification gaps do not become production guarantees.

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
