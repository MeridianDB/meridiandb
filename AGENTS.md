# MeridianDB agent rules

These rules apply throughout this repository. Maintain them as the project evolves
so a new session can work correctly without relying on earlier conversations.

## Start with the current repository

- Read `README.md`, the decision records relevant to the task, and any more specific
  `AGENTS.md` before editing. Inspect Git status and preserve unrelated changes.
- Keep changes scoped to MeridianDB and the requested task.
- The repository has a Java 25/Maven memory-only embedded engine, immutable
  temporal types and an independent test reference. Follow the
  [Java build guide](docs/development/build.md), [engine guide](docs/development/embedded-engine.md)
  and use `./mvnw -B verify`. The journal codec and
  [local adapter](docs/development/local-journal.md) implement append/force,
  exclusive ownership and sequential replay. The [immutable history segment codec](docs/architecture/immutable-segment-format.md)
  validates exact transaction ranges and whole-object identity. The
  [local segment adapter](docs/development/local-segments.md) provides create-only
  writes and verified heap readers. The [manifest codec and verifier](docs/architecture/checkpoint-manifest-format.md)
  check supplied segment history against a reconstructed journal-prefix binding;
  the [local checkpoint store](docs/development/local-checkpoints.md) verifies the
  actual owned-journal prefix and publishes immutable generations under explicit
  filesystem capabilities. The [local recovery API](docs/development/local-database.md)
  restores read-only temporal state and request lookup from complete checkpoint
  history plus the journal suffix. Explicit create/writable modes now force before
  atomic commit publication. Process crash/retry and canonical/seeded writable-reopen
  conformance exercise this protocol; see the [durability guide](docs/development/durability-conformance.md).
  The optional [S3 byte adapter](docs/development/s3-objects.md) is a separate Maven
  artifact: verify/install core, then verify `adapters/s3/pom.xml`; CI runs both.
  The independent Zensical docs build uses Python 3.13, `.venv/` and pinned requirements.
- Continue authorized, reversible work using reasonable engineering judgment.
  Resolve material ambiguity about behavior or scope with the owner; routine
  implementation choices do not need another approval.

## Iterative implementation queue

- Follow the [implementation workflow](internal/planning/index.md),
  [live queue](internal/planning/queue.md) and selected
  [roadmap card](internal/planning/roadmap.md). A start/continue request executes one
  bounded item, resuming unfinished work first. Planning-only requests prepare the
  plan without automatically starting the next engine step.
- Owner instruction, October 28, 2026: each completed queue step includes relevant
  project documentation, queue and progress updates in its source commit, then a
  normal push to `origin/main` and one concrete next-step recommendation. This
  standing source-push authorization also covers the planning setup. Apply the
  workflow's completion trailers, delivery checks and retry procedure.
- Preserve IDs and acceptance criteria when inserting prerequisites or expanding
  an item into children. Keep one active executable item and one coordinator for
  shared queue/Git writes. The queue owns current state; dated receipts preserve
  evidence. Reconcile dependencies and the next eligible item before handoff.
- Documentation changes pushed to `main` invoke automatic GitHub Pages publication
  under the owner's October 28, 2026 instruction. Follow the
  [website guide](internal/site-operations.md#initial-hosting-and-publication)
  and report actual checks/deployment state. Release tags, library artifact uploads,
  other infrastructure changes and purchases retain their separate authorization.

## Language and architectural decisions

- Owner direction, November 2, 2026: prioritize production-oriented **Linux local
  filesystem storage** under [ADR 0039](docs/decisions/0039-linux-local-storage-priority.md)
  and the [Linux storage design](docs/architecture/linux-storage.md). Windows needs
  basic logical/build/local smoke coverage, not native feature or durability parity.
  Preserve existing S3 code/tests and defer its unfinished work; Kafka and other
  persistence options remain later work. Local qualification, benchmarks and the
  experimental candidate have no remote-backend prerequisite. Existing broader
  Windows CI may remain as regression coverage without creating a support promise.
- For Linux layouts and native I/O, establish named deployment/capability profiles
  and a reproducible local baseline first. Evaluate purpose-built temporal blocks
  against Parquet and simpler layouts under equivalent semantics, retention and
  acknowledgement. Preserve exact v1 history and formats until a versioned successor
  has independent correctness, integrity, recovery and compatibility evidence.
  Native format means the project's on-disk representation, not a required native
  language or runtime. Adopt a narrow native binding only for an evidenced need;
  copy/reflink acceleration supplies neither node replication nor durable publication.
  Record workload-specific gains and regressions; production readiness and universal
  performance superiority require evidence and cannot be inferred from design.
- For Linux evidence, follow [ADR 0040](docs/decisions/0040-linux-qualification-evidence.md)
  and the [qualification harness](docs/development/linux-storage-qualification.md).
  Run the complete reviewed suite inventory on the actual profiled scratch
  filesystem using the opt-in test-only factory. Require fresh run/root/runtime
  bindings, exact coherent counts and zero required skips, failures or retry-masked
  outcomes. Preserve failed evidence and bounded process-group cleanup. Reconcile
  `scripts/storage/required-suites.json` with an evidenced complete run whenever
  core-test scope changes; record additions/removals rather than reducing counts
  to hide failures. Keep probe/factory out of production and the attached classifier.
  Report actual kernel/filesystem/JDK and unknown device/cache limits; passing CI
  remains process/capability evidence, not production or power-loss qualification.
- Follow [the language strategy](docs/decisions/0001-language-strategy.md): use Java
  for initial production code and the reference model. Other languages remain open
  to scoped experiments and evidence-based adoption under that decision.
- Prefer code the maintainer can inspect and explain. Use straightforward types,
  explicit resource lifetimes and small cohesive components. Add abstractions when
  a concrete use or experiment needs them.
- Keep the initial engine usable without a mandatory service framework, cloud SDK,
  language wrapper or native runtime dependency. Put integrations behind narrow
  boundaries when they are actually needed.
- Follow the [Java build decision](docs/decisions/0004-java-build-foundation.md):
  use Java 25, one initial Maven artifact, the checksum-pinned Wrapper and exact
  plugin/dependency versions. Keep the Python documentation build independent.
  Introduce preview features or native code only with a documented need and verification.
- Owner instruction, October 28, 2026: always research latest dependency artifact
  versions for Maven work. Follow the [version research procedure](docs/development/build.md#research-maven-versions-for-every-maven-change)
  on each Maven change, using current Central metadata and official compatibility
  evidence. Record date, coordinates, latest stable/selected versions and any reason
  for a different choice. Pin exact versions after researching; a cached build or
  prerelease metadata entry is not evidence of the latest stable compatible release.
- Separate temporal semantics, logical query operations, physical layout and
  backend capabilities. Delay public syntax and broad integration commitments
  while using explicit queries to evaluate storage decisions. Follow the
  [temporal geometry guide](docs/architecture/temporal-geometry.md) when evaluating
  multidimensional indexes; its Lucene/Solr candidates do not select a dependency
  or change the existing temporal contract.
- Follow [ADR 0020](docs/decisions/0020-persistence-and-replication-boundaries.md)
  and the [persistence boundaries](docs/architecture/persistence-boundaries.md)
  for backend work: keep local Java embedding independently usable, introduce
  narrow adapters with concrete consumers, and separate journal/commit authority,
  immutable objects, checkpoint selection, indexes and reclamation. Optional SDK
  packaging must preserve local use without a cloud runtime requirement. Preserve
  the temporal contract and existing acknowledgement; verify actual journal
  lineage/prefix and backend-specific failure/capability behavior. An object-store
  head update provides no distributed writer authority or replicated durability.
  Replication follows its separately admitted task, with original transaction
  order/identity, explicit progress and acknowledgement modes; it adds no implicit
  prerequisite to the current single-node prototype. Keep unimplemented adapter,
  network and near-real-time capabilities clearly identified in public copy.
- Record material decisions and alternatives in `docs/decisions/`. Distinguish a
  working hypothesis from a measured result or an owner requirement. Use primary
  project documentation for current external technology claims and date the check.

## Temporal correctness and persistence

- Follow the [initial temporal contract](docs/architecture/temporal-contract.md)
  and its linked conformance fixtures. Implement and test those expected answers
  before optimizing. A material semantic change updates the contract, relevant
  decision record and fixtures together; storage work preserves their behavior.
- Follow [typed temporal queries](docs/development/temporal-queries.md) and
  [ADR 0015](docs/decisions/0015-temporal-query-operators.md): resolve one pinned
  temporal snapshot before evaluating numeric predicates. Absence matches no
  predicate, including NE; filtering must not revive older puts or bridge omitted
  intervals. Preserve exact UTF-16 entity ordering, clipping, immutable eager results
  and existing view/owner close semantics. Extend the separate query fixture corpus
  and independent reference/dense comparisons for new operators; preserve original
  canonical histories and seed reproduction. Check memory and real writable reopen,
  keeping capability-dependent skips separate from memory/reference coverage.
- Keep valid time, database system time, event timestamps and physical snapshots
  distinct. Specify how committed transaction order maps to historical queries;
  wall-clock readings alone are not an ordering contract.
- Preserve the [embedded engine's](docs/development/embedded-engine.md) serialized
  writer, immutable view, atomic publication and close semantics. Test engine
  results against the independent reference and canonical expectations, with
  lifecycle/concurrency checks for changed behavior. State guarantees per backend;
  memory-only acknowledgement is not durability. Make uncertain outcomes explicit.
- Owner direction, October 28, 2026: consider comparable immutable append-only
  and realtime-search designs for storage/index work. Use the
  [primary-source research](docs/architecture/storage-research.md) and refresh
  relevant external claims when making new decisions. Keep durable append, immutable
  checkpoint publication and query-visible cutoffs distinct; preserve transaction
  identity and bounded work while evaluating batching and throughput.
- Follow the [journal specification](docs/architecture/journal-format.md) for exact
  encoding, allocation limits and fail-closed recovery. Pure decoding proves no
  force/acknowledgement. Follow the [local adapter contract](docs/development/local-journal.md)
  for separate appended/forced positions, failure poisoning, stable-path ownership
  and explicit evidence-before-truncation repair. For caller-budgeted actual-prefix
  checks use `prefix(sequence, maxBytes)` and the [prefix limits](docs/development/local-journal.md#bound-prefix-verification-by-encoded-bytes):
  include the header, reject before framing/body reads or payload allocation, keep
  policy exhaustion separate from I/O/corruption poisoning, and test exact native
  read bounds plus subsequent owner usability. Budget aggregate records/identities
  and repeated calls separately; this cap does not bound initial open recovery.
  Reserve same-JVM ownership before
  opening another same-file channel. Use the shared identity reservation across
  journal/store/reader control owners and transient checkpoint/segment read/force
  handles; reject cross-store aliases before open and release a reservation only
  after known successful channel close. A thrown native close remains quarantined
  even when Java reports `isOpen() == false`; a no-op retry is not cleanup evidence.
  Retain reservations and require process restart on that uncertainty. Resolve the real parent before final name handling,
  preserving the provider's symlink/`..` identity. Path fixtures derive expected
  identities from that provider's real-parent resolution; POSIX and Windows may
  resolve the same lexical pattern differently. Keep both control/read exclusion
  and create/reopen checks without assuming one platform's sibling. Distinguish file force from directory-entry
  durability. Preserve incomplete/corrupt evidence and test changed I/O boundaries.
  In journal tests, inspect raw file bytes after closing the owning adapter; use
  replay/counters while open. A second read handle can be blocked by Windows locks
  and its close can release the owning JVM's POSIX locks.
- Follow the [immutable segment specification](docs/architecture/immutable-segment-format.md):
  preserve original temporal history and identity, validate the complete object,
  distinguish the integrity footer from the full-object content ID, and reject all
  incomplete/corrupt objects without returning a prefix. Enforce nested and aggregate
  limits before allocating. Local readers bind expected IDs; manifest verification
  binds lineage, ranges and the reconstructed journal prefix. Codec success is not
  publication.
- Follow the [local segment contract](docs/development/local-segments.md): encode
  before creating a path, use create-only/no-follow writes, force and close before
  returning success, and retain failed candidates. Require an independent expected
  ID and complete bounded validation before returning a reader. Reject non-regular
  input before a read-only open so pipes cannot block acquisition. Keep paths in a
  trusted stable directory; no-follow on a final component does not freeze parents.
  Readers own heap snapshots with no open channel; close releases their reference
  but preserves already returned immutable values. File creation/force and reader
  close do not select a manifest or authorize reclamation.
- Follow the [manifest contract](docs/architecture/checkpoint-manifest-format.md):
  distinguish physical generation from temporal cutoff; require complete ordered
  coverage, exact IDs/lengths/lineage/ranges and cross-segment request uniqueness.
  Separate bounded metadata decoding from complete dependency verification and
  actual stored-journal verification. Reconstruct prefix length/hash incrementally,
  retaining the journal header and exact original frames; account for the growing
  request-ID set. No consistency result is a force/publication receipt or permits
  reclamation. Local publication must verify stored bytes and namespace guarantees.
- For aggregate record-budgeted compaction and inventory/removal, follow
  [bounded consumer admission](docs/architecture/checkpoint-manifest-format.md#bounded-consumer-range-admission):
  reserve manifest record/byte work before retrieval and use expected header ranges
  before body hashing or record allocation/decoding. Fetch once per graph and keep
  results private through full verification/cleanup. Test CRC-valid range conflicts
  with body/footer traps, exact limits and no removal effects. Preserve the public
  raw verifier and ordinary publication/load error order; document bounded-path
  `INVALID_HISTORY` precedence rather than silently broadening it.
- Follow the [local checkpoint protocol](docs/development/local-checkpoints.md):
  require an existing trusted stable durably named directory, probe directory
  open/force before creating files, and reserve capacity before publication effects.
  Select the highest uniquely named generation and fail closed on damaged newest
  state; pending candidates remain unselected evidence. Verify actual prefix bytes
  through the journal owner; reject objects aliasing the journal/store lock and a
  lock aliasing the journal before opening another same-file channel. Force journal, segments and dependency names,
  then force/close a create-only candidate, atomically rename it and force the
  directory. Mark uncertainty before attempting rename, poison after storage
  failure and reopen to establish selected state. Preserve all old objects/readers;
  publication and reader close grant no reclamation or engine-acknowledgement claim.
  Test unsupported capability before mutation, same-file lock preservation,
  before/after-rename faults, capacity limits and exact process reopen.
- Follow the [local recovery contract](docs/development/local-database.md): acquire
  existing store/journal ownership, verify the complete selected graph and actual
  prefix, seed the stored lineage, replay the checkpoint exactly once and apply
  only the journal suffix. Validate full journal order/cardinality and preserve
  every historical cutoff and original request identity, including no-ops. Reused
  stored identities are invalid history, not retries. Expose no database until all
  reconstruction succeeds; discard private state and close acquired resources on
  failure with cleanup errors suppressed. Keep directory-probe/write-permission
  requirements explicit despite logical read-only access. Recovered bytes do not
  prove earlier force/acknowledgement. Test unchanged canonical expectations through
  journal-only, genesis and checkpoint-plus-tail reopen; run writable canonical/seeded
  and process-exit conformance for changed durability boundaries and document full-history replay/heap costs.
- Follow [forced local commit publication](docs/decisions/0014-durable-commit-publication.md):
  create only in a fresh durably named directory, select genesis through the
  checkpoint protocol, and synchronize the owner journal plus namespace before
  exposing a writable recovered instance. Prepare complete immutable state/result
  and preflight encoding before append. Serialize append/force/publication with
  close; publish only the prepared state and return its existing result after
  force. Exact retries may skip I/O only after startup or prior acknowledged force.
  Treat post-append-attempt I/O/runtime failure as uncertain, poison the instance
  and views, retain locks/evidence until close and resolve by reopen. Errors also
  poison before propagation. Keep read-only open and writer/checkpoint exclusion
  explicit; no failure or close authorizes automatic repair/reclamation. Test
  startup barriers, preparation invisibility, before/after-effect failures,
  pinned views and concurrent writers/readers. Follow the [durability conformance](docs/development/durability-conformance.md)
  matrix: use real child-JVM exits at production boundaries, keep per-instance probes
  package-private, logs outside fresh store directories and child lifetimes bounded.
  Preserve canonical fixtures/seeds and reproduce through actual writable reopen;
  distinguish injected torn images, process exit and device power loss. Skip only
  explicit unsupported directory-sync capability; retain provider/device force assumptions.
- Require persistence adapters to declare their guarantees, including range reads,
  conditional updates where used and durable publication. Reuse provider libraries
  without pretending local files and object stores have identical behavior.
- Follow [ADR 0021](docs/decisions/0021-bounded-immutable-object-access.md) and the
  [immutable-object contract](docs/development/immutable-objects.md) for byte
  adapters. Preserve bounded flat keys, complete SHA-256/exact length, preflight
  before path effects, create-only writes and explicit verified reuse. Return heap
  snapshots only after complete validation and native cleanup; range copies are
  slices of verified whole objects, not physical range proofs. Reuse shared local
  ownership reservations, retain failed candidates and separate verified reuse
  from force/publication receipts. Run the reusable object contract, local/fault
  and segment compatibility cases for changes. Keep legacy checkpoint/journal/
  reader protection explicit; another adapter alone establishes no new engine
  guarantee. Add bounded physical range access only with a concrete integrity and
  resource-lifetime contract, rather than exposing partial segment history.
  For invalid non-regular destinations, assert I/O rejection and unchanged
  evidence across providers; a directory conflict may be access denied on Windows.
  Keep exact existing-object conflict checks for regular files, and never translate
  an arbitrary permission failure into absence or verified reuse.
- Follow [ADR 0022](docs/decisions/0022-optional-s3-object-access.md) and the
  [S3 adapter guide](docs/development/s3-objects.md) for SDK changes. Keep cloud
  dependencies in the optional artifact, test the shared contract through the real
  SDK/HTTP fixture and preserve response-body, timeout, cleanup and credential
  ownership checks. SDK close must leave caller-owned credential providers usable.
  Scope fixture evidence precisely; protocol checks establish no live-provider
  qualification, checkpoint selection, distributed ownership or stronger local
  acknowledgement. Run core and adapter verification on both CI platforms.
- Follow [ADR 0023](docs/decisions/0023-object-checkpoint-head-protocol.md) and the
  [object checkpoint protocol](docs/architecture/object-checkpoint-protocol.md)
  for remote selection work. Preserve exact head/predecessor/manifest identity,
  independent revision/generation/cutoff and the full canonical lineage domain.
  Distinguish pure metadata checks from actual graph/owner-journal verification
  and conditional selection. Preserve exact intent/preconditions across uncertain
  replies; an old-head observation alone proves no completed failure. Require
  durable expected/pending state and explicit single-publisher placement before
  publication. Copied IDs and metadata CAS supply no distributed fencing; reject
  observed conflicts and retain uncertain objects without cleanup.
- Follow [ADR 0024](docs/decisions/0024-conditional-s3-head-access.md) and the
  [conditional S3 head guide](docs/development/s3-checkpoint-heads.md) for provider
  head primitives. Pair complete bytes/token from one GET, preserve exact scope
  and original intent/precondition, and run real SDK/changing-token plus native
  cleanup cases alongside unchanged immutable-object conformance. Validate the
  bounded strong ASCII tag as opaque data; report SDK-visible header checks and
  native normalization limits precisely. Keep durable reconciliation and actual
  graph/owned-journal publication in their separate required step.
- Follow [ADR 0025](docs/decisions/0025-object-checkpoint-graph-verification.md) and
  [object graph verification](docs/development/object-checkpoint-graphs.md) for
  complete object-history checks. Reserve both graphs' object/prefix/record work,
  count repeated dependencies, bind segment header ranges before record decoding
  and use exact declared caps through the journal owner. Keep results private until
  complete verification/cleanup; preserve original codec paths and error behavior.
  Test aggregate limits, copied divergent histories and native actual-read bounds
  as well as local/SDK consumers. Verification supplies no selected-head token,
  force barrier, durable intent, lease or cleanup authority.
- Follow [ADR 0030](docs/decisions/0030-bounded-pinned-object-history.md) and
  [pinned history loading](docs/development/object-checkpoint-graphs.md#load-pinned-history)
  when supplying original records for recovery. Collect privately during the same
  bounded graph/actual-owner verification pass and expose immutable records only
  after all checks and reader cleanup. Keep metadata verification free of retained record lists;
  charge repeated invocations separately and document retained-list heap costs.
  Test exact original records, cutoff/suffix separation, late failure privacy,
  immutable lifetime and actual local/SDK consumers. Load within the engine
  factory's owned startup scope; a History value grants no journal lease, current
  selection, tail recovery, acknowledgement or permission to substitute another owner.
- Follow [ADR 0031](docs/decisions/0031-pinned-checkpoint-engine-recovery.md) and
  [pinned engine recovery](docs/development/object-checkpoint-recovery.md) for
  explicit read-only object-checkpoint opens. Preflight arguments, acquire/retain concrete
  local store/journal owners and freeze H before loading history. Admit an optional
  independent minimum before object I/O; restore the pinned prefix once, validate
  the complete journal through H and apply only its suffix. Keep the adapter
  caller-owned, reject transactions, release every owned resource on failure and
  invalidate views before close. Test canonical/seeded historical/reference/dense
  answers, original request lookup, strict evidence, coverage loss and actual owner
  cleanup. Preserve ordinary local selection; explicit pinned authority grants no
  current S3 selection, writable acknowledgement or missing-tail reconstruction.
- Follow [ADR 0033](docs/decisions/0033-pinned-checkpoint-writes.md) and
  [pinned checkpoint writes](docs/development/object-checkpoint-writes.md) for the
  explicit core writable factory. Complete owned prefix/tail reconstruction before
  synchronizing the actual journal/name and exposing any new/retry acknowledgement.
  Reuse existing prepare/append/force/publication, poisoning and close protocols;
  keep read-only routes without a writer and caller object adapters unowned.
  Test unchanged canonical/query and seeded reference/dense histories through actual
  writable pinned reopen, original retry identity, startup/failure/owner lifetimes
  and real process interruption. Preserve complete journals, minimum-coverage and
  explicit-pin limits; managed S3 writing uses its separate admission contract.
- Follow [ADR 0032](docs/decisions/0032-managed-s3-read-recovery.md) and
  [managed S3 recovery](docs/development/s3-database.md) for optional online read-only startup.
  Own existing publication metadata through wrapper close; reject pending/absent
  expected state before remote reads and admit actual client scope before GET.
  Require exact fresh expected bytes/token, then privately acquire core recovery
  with the independent journal minimum. Preserve caller clients/credentials and
  all failed evidence; close every acquired owner on startup failure and invalidate
  views before retryable cleanup. Test real SDK/state/pending reconciliation,
  local coverage, ownership and process reopen. State has no durable path binding;
  historical startup observation supplies no continuing lease, fallback, automatic
  reconciliation, writable acknowledgement or complete rollback detection.
- Follow [ADR 0034](docs/decisions/0034-managed-s3-writable-recovery.md) and
  [managed S3 writes](docs/development/s3-database-writes.md) for explicit writable
  managed startup. Reuse exact expected/client/head admission and the concrete core
  writable factory; retain metadata/store/journal ownership through uncertainty.
  Delegate transaction acknowledgement/poisoning to core and perform no remote or
  publication-state mutation per commit. Serialize write/close on the private core
  owner, reject reentrant close before invalidation and keep query capture outside
  the write monitor. Test real SDK admission, actual core boundary/process failures,
  old-state capture, write/close serialization and unchanged metadata/remote evidence.
  Keep fault openers package-private, fault-injection support outside production/classifier,
  and preserve the separate [remote writable conformance](docs/development/s3-recovery-conformance.md).
- Follow [ADR 0035](docs/decisions/0035-remote-writable-recovery-conformance.md)
  for remote writable conformance. Reuse unchanged canonical/query fixtures,
  reference classes and persisted seeds through the narrow test-only classifier;
  keep Jackson test-only and production core JDK-only. Exercise actual SDK
  publication/admission for each new/retry/read workflow, exact raw frames after
  owner close and all historical cutoffs against reference/dense/local baselines.
  Cross late/interrupted publication with pending refusal, explicit reconciliation
  and subsequent forced writes; verify minimum/default-zero, missing-journal and
  online-failure limits without fallback or data invention. Retain explicit fixture
  caps, named capability skips and separate live-provider/power-loss/reclamation
  limits. Keep actual tests, fault bridges and optional helpers outside the classifier.
- Follow [ADR 0037](docs/decisions/0037-bounded-s3-coordination-state.md) and the
  [bounded coordination state format](docs/architecture/s3-coordination-state.md)
  for root/attempt metadata. Preserve exact lexical target and independent binding/
  placement, complete head/manifest identity, typed dependencies, tombstones and
  aggregate limits before allocation. Keep original compound attempt identity,
  content and conditions; freeze catalog/new attempts while pending, reserve both
  intent/completion generations and settle only the exact original record. Public
  declarations and package-private transitions provide no selected-root authority,
  complete liveness, native lease, durability, rollback detection or actual terminal
  provenance. Future concrete owners must retain immutable receipt history and
  qualify the same attempt's actual response before durable settlement; a decoded
  watermark or status cannot establish those facts.
- Follow [ADR 0038](docs/decisions/0038-permanent-s3-coordination-guard.md) and the
  [permanent guard guide](docs/development/s3-coordination-guard.md) for internal
  native leases on an existing control file. Preserve canonical parent/default-
  provider file identity, one common-owned channel, actual shared-mode validation,
  bounded references/placements and no raw delegate. Recheck original identity
  around admission/force; poison native health/force/name uncertainty. Invalidate
  before final independent release/close, preserve primary/suppressed failures and
  quarantine every thrown close even when the channel reports closed. Unexpected
  overlap retains the duplicate channel/reservation until process restart. Keep
  fresh creation/directory force, exact enrollment, durable receipt generations
  and SDK participation separate; the guard supplies none of those permissions. Keep
  injected registry/fault identity independent of provider file-key availability;
  use controlled identities for those checks and explicit capability preflights
  for native checks. Missing native identity skips no logical fault coverage.
- For coordinated remote reclamation, follow [ADR 0036](docs/decisions/0036-coordinated-remote-reclamation.md)
  and the [accepted protocol](docs/architecture/remote-reclamation.md); these are
  design requirements until their implementation checks pass. Admit a fresh
  cooperating namespace in one canonical local placement; keep raw/legacy clients
  outside that mode and make no cross-host fencing claim. Register every root and
  dependency set before remote use and refuse uncertain catalog coverage. Retain
  shared reader/publisher protection or exclusive maintenance through cleanup.
  Force distinct intent before every possible PUT/DELETE, reserve completion
  capacity and settle only from that attempt's validated terminal evidence.
  Unknown effects persist across close/restart; absence, later success and logical
  reconciliation cannot clear them or permit key resurrection. Require same-GET
  verified body/tag, never-versioned bucket admission, bounded complete roots and
  head-metadata/manifests-before-segments ordering. Preserve failed/unknown root
  dependencies, full journal/history and diagnostic evidence. A plan, preview or
  delete primitive alone does not satisfy protected-removal conformance.
- Follow [ADR 0026](docs/decisions/0026-bounded-s3-publication-state.md) and the
  [S3 publication-state format](docs/development/s3-publication-state.md) for exact
  expected/pending snapshots. Preserve lexical endpoint spelling, explicit signing
  region, bindings, complete original candidate and original conditional
  precondition. Bound before copying/variable allocation and test independent
  vectors/nested failures.
  Recognize only the exact pending candidate observation in pure transitions;
  old/missing heads and errors grant no reset/adopt/clear/rebase. Keep local
  generations independent and distinguish decoded declarations from observation,
  graph, ownership, force, durable installation and remote selection evidence.
- Follow [ADR 0027](docs/decisions/0027-durable-local-s3-publication-state.md) and
  [durable local S3 state](docs/development/local-s3-publication-state.md) for its
  dedicated metadata directory. Separate empty create from existing-state open;
  bind exact managed configuration, validate highest unique state without fallback,
  and synchronize on reopen. Reserve shared file identity before native opens;
  force the lock through its owner. Install create-only/force/close/atomic-move/
  directory-force generations with capacity reserved first; poison failures and
  retain evidence. Treat default Windows placement as explicitly unsupported;
  ordinary directory I/O elsewhere must fail rather than become capability skips.
  Native uncertain close retains reservations until restart; isolate deliberate
  quarantine fixtures in child JVMs and exit before temporary path cleanup. Verify fault and real
  process/ownership cases; local metadata supplies no remote/graph/engine receipt.
- Follow [ADR 0028](docs/decisions/0028-verified-s3-publication-preparation.md) and
  [verified S3 preparation](docs/development/s3-publication-preparation.md) when
  composing client/graph/local state. Check actual client lexical endpoint, region,
  bucket and head binding against managed local state and actual journal lineage/
  canonical checkpoint path. Refuse pending, preflight capacity, compare original
  head bytes/token, verify both graphs under shared limits, synchronize owner/name,
  create/verify exact immutable intent, then force local pending. Preserve fixed
  metadata/local-scan/force accounting outside graph limits and resource monitor
  order (local state, checkpoint store, journal, head client, object client).
  Preparation supplies no selection PUT, pending clearing, lease or acknowledgement;
  retain all failed evidence and require separately implemented reconciliation.
- Follow [ADR 0029](docs/decisions/0029-verified-s3-checkpoint-publication.md) and
  [verified S3 publication](docs/development/s3-checkpoint-publication.md) for exact
  submission/reconciliation. Require existing immutable intent, original pending
  bytes/token, actual client/owner admission, local result capacity and nonexhausted
  generation before effects. Reserve both complete graph passes under one total
  limit before force/PUT. Attempt one explicit original-precondition write; resolve
  only a fresh exact candidate after complete original graph/actual-prefix checks,
  owner/name barriers and durable local result. Old/missing/error states never clear
  or rebase pending. On result-installation uncertainty inspect validated reopen;
  a failed call may already have installed expected state. Verify actual SDK late
  effects across reopen, process exits, native result failures and summed budgets;
  keep single-placement assumptions and remote engine/reclamation scope explicit.
- Follow [history-preserving compaction](docs/development/history-compaction.md) and
  [ADR 0016](docs/decisions/0016-history-preserving-compaction.md). Retain exact
  original transactions, all historical cutoffs and request identities; never turn
  physical repacking into latest-value or history-pruning behavior. Validate the
  complete graph/prefix and enforce aggregate input/output/record budgets as well
  as per-object limits before returning a pure plan. Preserve deterministic whole-
  transaction groups and report encoded bytes separately from reclaimed disk space.
- Keep pure compaction, actual owner-journal verification, replacement installation,
  durable publication and physical deletion as distinct stages. For local installation,
  use `publishCompaction(plan, journal)`: require the exact selected source ID and
  actual owner prefix, verify every existing complete output before reuse, reserve
  all missing segments plus one candidate before writes, then use the shared
  force/rename protocol including reused files. An unchanged layout still publishes
  the next physical generation; test stale plans, capacity, failure/uncertainty and
  real process reopen. Ordinary `writeSegment` remains create-only. Verify/reuse existing
  identical content IDs; an existing path is not proof of valid reuse. Retain the
  complete journal under the current contract. Retain old/failed physical objects
  until an implemented reclamation protocol and inspected diagnostic policy establish
  permission. Follow [live reader protection](docs/development/reader-protection.md)
  and [ADR 0017](docs/decisions/0017-live-reader-protection.md): shared store-wide
  protection belongs to lazy readers, outlives store close and serializes release
  with lazy opens. Keep the guard permanent, reserve its absent slot during object
  creation/publication, share one JVM channel, require actual shared-lock support
  and retain uncertain cleanup reservations. Reader close invalidates access first
  and retries release; database cleanup must still attempt every owner. Physical
  removal holds both store ownership and exclusive protection through checks/removal/sync;
  any live reader defers all deletion. All participating readers use the protocol
  and one loaded adapter copy per JVM; quiesce legacy unprotected readers first.
  Reader protection must cover cross-process admission versus deletion;
  timers/PID checks or a process-local registry alone are insufficient. Preserve
  selected/retained/reader/backup roots and fail closed when liveness is uncertain.
  Change logical history retention only through a separate explicit decision.
- Follow [verified reclamation inventory](docs/development/reclamation-inventory.md)
  and [ADR 0018](docs/decisions/0018-reclamation-inventory.md). Inspect under store
  ownership and exclusive reader protection; known contention defers without a
  partial inventory. Always retain selected state and resolve every explicit
  retained/backup ID. Verify all published graphs against the actual owner journal,
  bound repeated graph bytes/records/identity work and reject aliases before opens.
  Limit metadata reads to remaining budget and segment reads to declared exact
  lengths, including corrupt inputs; distinguish owner-journal validation costs.
  Candidate segments come only from verified obsolete roots minus live dependencies;
  preserve unknown/orphan/diagnostic objects and refuse pending candidates. Return
  immutable evidence only after successful protection cleanup. A preview grants no
  later deletion authority or freed-space claim. Follow
  [protected physical removal](docs/development/physical-reclamation.md) and
  [ADR 0019](docs/decisions/0019-protected-physical-reclamation.md): `reclaim`
  recomputes under both owners; force actual journal, retained manifest images,
  retained dependencies and directory before the first removal. Retire/synchronize
  obsolete metadata before unused dependencies, then sync again. Recheck aliases
  and reserve common identity before strict delete without opening a native handle.
  Mark uncertainty before delete attempts, poison failures and establish remaining
  names by reopen. Preserve orphaned dependencies after interrupted retirement;
  no stale preview, filename sweep or automatic intent replay grants permission.
  Test per-object/boundary interruption, cross-process admission/reader deferral,
  cleanup and capacity. Follow [integrated conformance](docs/development/compaction-conformance.md)
  when changing compaction/reclamation: preserve canonical literal outcomes and
  original Head/named-view cutoffs, run real publication/removal with historical
  reference/dense and retry checks, and cover representative process boundaries.
  Reconcile exact remaining names/encoded lengths against removal receipts. Use
  metadata-only attributes for retained-reader stages; capture raw journal/control
  images only after all owners close. Distinguish named bytes from freed blocks/performance.
  Keep pending candidates and interrupted orphans within their inspected policy.

## Verification and experiments

- Keep the [reference model](docs/development/reference-model.md) in test support,
  independent of production engine algorithms. Replay the canonical fixture source
  through Maven test resources; preserve independent dense-state checks and failing
  seeds. Sharing immutable types is acceptable; duplicating the same algorithm and
  assumptions does not provide an independent correctness check.
- For behavioral changes, run focused tests of observable results. Temporal and
  storage work needs boundary cases and, as relevant, randomized histories,
  crash/restart injection, retry tests and concurrent-writer tests.
- In capability-specific tests, distinguish an explicitly unsupported required
  capability from ordinary I/O or permission failure. Directory-sync-dependent
  recovery tests skip only that unavailable capability; link-specific cases may
  separately report an explicit unsupported link operation. Ordinary storage or
  permission failures must fail the check. Verify retained crash candidates by
  complete expected bytes/IDs, not filename existence alone.
- Preserve failing seeds and minimal reproductions when fixing a defect. Test the
  contract rather than mirroring implementation details.
- Compare performance under equivalent semantics, durability and workloads.
  Record revision, runtime, hardware, dataset/seed, configuration, warmup and cache
  conditions. Include resource use and latency distributions when relevant; label
  estimates and microbenchmarks so they are not presented as engine results.
- Use the narrowest checks justified by the change. Install documentation
  dependencies from the hash-checked `requirements-docs.txt` when their inputs
  change, following the website guide, then run
  `.venv/bin/python scripts/build_docs.py`. Inspect affected pages through a local
  static server or `.venv/bin/zensical serve --dev-addr 127.0.0.1:8000`; include
  navigation/search when touched. Run commands from the repository root and check
  links, terminology and diffs. Report primitive/unit tests separately from full
  reference-model, engine, concurrency and durability verification.
- Parallelize independent research or review when useful; assign clear ownership
  and serialize overlapping edits and Git operations.

## Public documentation quality

- Owner correction, October 28, 2026: public copy explains the system and technical
  evidence; it does not narrate this chat, owner requests, agent instructions,
  authorization or execution task state. Follow the
  [public documentation quality standard](internal/documentation-quality.md).
- Keep queues, progress receipts, delivery authority and site administration under
  `internal/`, outside the website's `docs_dir`. Use the public technical roadmap
  for capabilities, not task IDs or live workflow state. Audit the full affected
  page and current callers, not just a reported sentence.
- Run `.venv/bin/python scripts/build_docs.py` for public documentation changes.
  This checks source and rendered content as well as strict generation. CI and
  publication use the same command. Check generated search/sitemap and live content
  after routing/publication changes; a raw generator success is insufficient.

## Documentation website

- Follow the [toolchain decision](docs/decisions/0002-documentation-toolchain.md)
  and [website guide](docs/contributing/documentation.md), with administrative
  details in [site operations](internal/site-operations.md). Use Zensical with
  `mkdocs.yml` and the classic theme. Pin dependency changes in
  `requirements-docs.in` and regenerate the hash-checked `requirements-docs.txt`.
- Keep public content in `docs/`, configuration in root `mkdocs.yml` and assets in
  `docs/assets/`. Add navigable pages to the config. Keep root `README.md` as the
  repository entry point and root `AGENTS.md` as working guidance.
- Write portable Markdown with source-relative `.md` links and simple YAML
  metadata. Keep generator/theme layout out of page content. A later Hugo change
  needs explicit URL, link, navigation, asset and metadata validation.
- Verify each new plugin/setting against the pinned Zensical version: MkDocs
  configuration support does not imply arbitrary plugin or hook execution.
  Introduce versioned publication when releases need it, with a documented URL,
  alias, canonical and retained-version policy before changing deployment.
- Give public pages specific descriptions. Use `site_url`/`DOCS_SITE_URL` for
  canonical and sitemap URLs. Check both `https://meridiandb.org/` and
  `https://meridiandb.github.io/meridiandb/` after routing or publication changes.
  Add `hreflang` alternates only with actual translated or regional content.
- The docs-checks workflow validates only. The docs-publish workflow builds and
  publishes documentation changes pushed to `main`; it also retains manual dispatch
  from `main` for a rebuild. Build the HTTPS URL from Pages host/base-path metadata
  and publish only after its strict build succeeds. GitHub Pages is the initial
  host; changing the generator and changing the host are separate decisions.
  Cloudflare deployments remain owner-performed; automatic GitHub Pages publication
  does not change that policy.
- Keep `.venv/`, `.cache/`, `site/`, Python caches and `.local-qa/` untracked.
  Store durable review evidence in tracked documentation when it adds lasting value.
- Follow [the diagram procedure](docs/contributing/documentation.md#tooling-and-diagrams).
  Owner clarification, October 31, 2026: direct SVG is permitted alongside
  PlantUML/Mermaid sources. Commit hand-authored SVG masters under
  `docs/assets/illustrations/` with accessible text and inspect mobile/light/dark
  readability. Introducing PlantUML or Mermaid sources must add a pinned SVG
  renderer to the shared site build used locally and by both workflows. The
  current illustration is direct SVG; no generated-diagram renderer is installed.
- Follow [brand artwork maintenance](internal/brand.md) for logos: edit the SVG
  masters, regenerate committed PNG/dark variants with the pinned exporter, and
  verify small icons, circular crop, README and light/dark/mobile website use.
  Preserve source attribution; distinguish prepared avatar artwork from an actual
  GitHub account upload.

## Project sponsorship

- Follow [the sponsorship procedure](internal/sponsorship.md) when changing
  funding configuration or support copy. `.github/FUNDING.yml` owns the verified
  recipient; keep README links consistent and describe voluntary support for the
  experimental project. Verify the active destination and live repository button
  after delivery. Treat central account/profile, tier and payout changes as their
  own scoped work, rather than inferring them from a repository-link update.

## Maintain the guidance

- Update these rules in the same task when an owner instruction, established
  command or confirmed recurring failure changes how future agents should work.
- State the affected object, required action and completion evidence. Keep rules
  concise and actionable; move detailed rationale and dated findings to linked
  decision records instead of accumulating reminders here.
- Keep one authoritative location per decision. When changing a rule, reconcile
  its active README, decision-record and nested-instruction references. Mark
  superseded decisions as historical and retain the reason for the change.
- Update implemented-status claims and documented commands alongside the code that
  makes them true. Describe proposed features and benchmark hypotheses as such.

## Finish the task

- Validate the changed material, inspect the working tree and staged diff, and
  commit only task-owned paths on the current branch unless the owner asks to leave
  them uncommitted. Preserve unrelated edits and staged work.
- Report what changed, checks actually run, limitations and the commit. State the
  actual push/release state and recommend one concrete next task.
- For implementation queue work, finish source delivery with the authorized push
  and remote verification in the workflow above. For other scoped work, report its
  actual commit/push state under the owner's applicable authorization. Automatic
  GitHub Pages publication follows the website guide; library releases, other
  deployments and paid infrastructure remain separately authorized actions.
