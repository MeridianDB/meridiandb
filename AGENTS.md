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
  while using explicit queries to evaluate storage decisions.
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
  and explicit evidence-before-truncation repair. Reserve same-JVM ownership before
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
- Mermaid and PlantUML are permitted for useful diagrams. Preserve editable
  sources and follow the website guide's SVG conventions. The first actual
  diagram must add and verify a pinned renderer in a shared build command used
  locally and by both workflows; the current site has no diagram renderer.

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
