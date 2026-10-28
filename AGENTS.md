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
  and use `./mvnw -B verify`. Durable storage and recovery remain planned.
  The independent Zensical docs build uses Python 3.13, `.venv/` and pinned requirements.
- Continue authorized, reversible work using reasonable engineering judgment.
  Resolve material ambiguity about behavior or scope with the owner; routine
  implementation choices do not need another approval.

## Iterative implementation queue

- Follow the [implementation workflow](docs/planning/index.md),
  [live queue](docs/planning/queue.md) and selected
  [roadmap card](docs/planning/roadmap.md). A start/continue request executes one
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
  [website guide](docs/contributing/documentation.md#initial-hosting-and-publication)
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
- Record material decisions and alternatives in `docs/decisions/`. Distinguish a
  working hypothesis from a measured result or an owner requirement. Use primary
  project documentation for current external technology claims and date the check.

## Temporal correctness and persistence

- Follow the [initial temporal contract](docs/architecture/temporal-contract.md)
  and its linked conformance fixtures. Implement and test those expected answers
  before optimizing. A material semantic change updates the contract, relevant
  decision record and fixtures together; storage work preserves their behavior.
- Keep valid time, database system time, event timestamps and physical snapshots
  distinct. Specify how committed transaction order maps to historical queries;
  wall-clock readings alone are not an ordering contract.
- Preserve the [embedded engine's](docs/development/embedded-engine.md) serialized
  writer, immutable view, atomic publication and close semantics. Test engine
  results against the independent reference and canonical expectations, with
  lifecycle/concurrency checks for changed behavior. State guarantees per backend;
  memory-only acknowledgement is not durability. Make uncertain outcomes explicit.
- Require persistence adapters to declare their guarantees, including range reads,
  conditional updates where used and durable publication. Reuse provider libraries
  without pretending local files and object stores have identical behavior.
- Preserve every supported temporal query result across recovery and compaction.
  Base history removal and object reclamation on an explicit retention contract
  that accounts for active readers and in-flight commits.

## Verification and experiments

- Keep the [reference model](docs/development/reference-model.md) in test support,
  independent of production engine algorithms. Replay the canonical fixture source
  through Maven test resources; preserve independent dense-state checks and failing
  seeds. Sharing immutable types is acceptable; duplicating the same algorithm and
  assumptions does not provide an independent correctness check.
- For behavioral changes, run focused tests of observable results. Temporal and
  storage work needs boundary cases and, as relevant, randomized histories,
  crash/restart injection, retry tests and concurrent-writer tests.
- Preserve failing seeds and minimal reproductions when fixing a defect. Test the
  contract rather than mirroring implementation details.
- Compare performance under equivalent semantics, durability and workloads.
  Record revision, runtime, hardware, dataset/seed, configuration, warmup and cache
  conditions. Include resource use and latency distributions when relevant; label
  estimates and microbenchmarks so they are not presented as engine results.
- Use the narrowest checks justified by the change. Install documentation
  dependencies from the hash-checked `requirements-docs.txt` when their inputs
  change, following the website guide, then run
  `.venv/bin/zensical build --strict`. Inspect affected pages through a local
  static server or `.venv/bin/zensical serve --dev-addr 127.0.0.1:8000`; include
  navigation/search when touched. Run commands from the repository root and check
  links, terminology and diffs. Report primitive/unit tests separately from full
  reference-model, engine, concurrency and durability verification.
- Parallelize independent research or review when useful; assign clear ownership
  and serialize overlapping edits and Git operations.

## Documentation website

- Follow the [toolchain decision](docs/decisions/0002-documentation-toolchain.md)
  and [website guide](docs/contributing/documentation.md). Use Zensical with
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
