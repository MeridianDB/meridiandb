# MeridianDB agent rules

These rules apply throughout this repository. Maintain them as the project evolves
so a new session can work correctly without relying on earlier conversations.

## Start with the current repository

- Read `README.md`, the decision records relevant to the task, and any more specific
  `AGENTS.md` before editing. Inspect Git status and preserve unrelated changes.
- Keep changes scoped to MeridianDB and the requested task.
- The repository has a VitePress documentation site; the Java engine is not yet
  implemented. Use Node.js 24 and the checked-in npm lockfile for the site. Discover
  actual tooling before running engine commands.
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
  Java implementation. Keep the npm documentation build independent. Introduce
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
- Use the narrowest checks justified by the change. For website changes, run
  `npm ci` when dependency inputs change, then `npm run docs:build`; check affected
  pages through `npm run docs:preview`, including navigation/search when touched.
  Local development uses `npm run docs:dev`. Run commands from the repository root.
  Check links, terminology and diffs without claiming engine tests were run.
- Parallelize independent research or review when useful; assign clear ownership
  and serialize overlapping edits and Git operations.

## Documentation website

- Keep public content in `docs/`, website configuration in `docs/.vitepress/`,
  and assets in `docs/public/`. Root `README.md` is the repository entry point;
  root `AGENTS.md` remains working guidance. Add navigable pages to the site config.
- Follow [the website guide](docs/contributing/documentation.md) for dependency
  decisions and hosting setup. Maintain the exact stable VitePress pin and scoped
  Vite override until an evidenced upgrade supersedes them; inspect the lockfile,
  advisory status and local behavior when changing either.
- The docs-checks workflow only validates. The docs-publish workflow publishes
  only on manual dispatch from `main`. Preserve that separation unless the owner
  explicitly changes the publication policy.
- Read Pages metadata during manual publication to derive the base path and site
  origin. Local/custom-domain builds use `/`; project-hosted builds may use
  `/meridiandb/`. Check both paths when changing routing or publication config.
- Give public pages specific descriptions. Generate canonical URLs centrally from
  the publication origin, base path and clean route; keep them aligned with the
  sitemap and check generated HTML after routing changes. Add `hreflang` alternates
  only with real translated or regional content. See the website guide for details.
- Keep `node_modules/`, site cache/output and `.local-qa/` untracked. Store durable
  review evidence in tracked documentation when it adds lasting value.
- Mermaid and PlantUML are permitted for useful architecture diagrams. Follow the
  website guide's source and SVG-generation conventions. The first diagram change
  must add and verify its renderer in the shared docs build; the initial scaffold
  has no renderer. Keep editable sources and check generated labels and both themes.

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
