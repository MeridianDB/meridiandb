# MeridianDB agent rules

These rules apply throughout this repository. Maintain them as the project evolves
so a new session can work correctly without relying on earlier conversations.

## Start with the current repository

- Read `README.md`, the decision records relevant to the task, and any more specific
  `AGENTS.md` before editing. Inspect Git status and preserve unrelated changes.
- Keep changes scoped to MeridianDB and the requested task.
- The repository has a Zensical documentation site using MkDocs configuration;
  the Java engine is not yet implemented. Use Python 3.13, `.venv/` and the pinned
  documentation requirements. Discover actual tooling before engine commands.
- Continue authorized, reversible work using reasonable engineering judgment.
  Resolve material ambiguity about behavior or scope with the owner; routine
  implementation choices do not need another approval.

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
- Maven is the chosen Java build tool, per the owner's October 28, 2026 direction.
  Establish the JDK baseline, Maven Wrapper and dependency versions with the first
  Java implementation. Keep the Python documentation build independent. Introduce
  preview features or native code only with a documented need and verification.
- Separate temporal semantics, logical query operations, physical layout and
  backend capabilities. Delay public syntax and broad integration commitments
  while using explicit queries to evaluate storage decisions.
- Record material decisions and alternatives in `docs/decisions/`. Distinguish a
  working hypothesis from a measured result or an owner requirement. Use primary
  project documentation for current external technology claims and date the check.

## Temporal correctness and persistence

- Write expected behavior before optimizing it. Define interval boundaries,
  overlapping and partial corrections, retractions, duplicate requests and
  transaction ordering in executable examples.
- Keep valid time, database system time, event timestamps and physical snapshots
  distinct. Specify how committed transaction order maps to historical queries;
  wall-clock readings alone are not an ordering contract.
- State writer concurrency, isolation, commit visibility and durability guarantees
  for each implementation. Make retries and uncertain commit outcomes explicit.
- Require persistence adapters to declare their guarantees, including range reads,
  conditional updates where used and durable publication. Reuse provider libraries
  without pretending local files and object stores have identical behavior.
- Preserve every supported temporal query result across recovery and compaction.
  Base history removal and object reclamation on an explicit retention contract
  that accounts for active readers and in-flight commits.

## Verification and experiments

- Keep a simple reference model independent of the optimized algorithms. Sharing
  the implementation language is acceptable; duplicating the same algorithm and
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
  links, terminology and diffs without claiming engine tests were run.
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
- The docs-checks workflow validates only. The docs-publish workflow publishes
  only on manual dispatch from `main`, deriving the URL from Pages metadata.
  GitHub Pages is the initial host; changing the generator and changing the host
  are separate decisions. Cloudflare deployments are owner-performed under the
  owner's standing policy; prepare source and hand off any hosting change.
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
- A local source commit is the normal handoff. Pushes, releases, deployments and
  paid infrastructure require the owner's applicable authorization; documentation
  and local implementation work do not supply it.
