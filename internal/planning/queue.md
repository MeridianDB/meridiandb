---
title: Implementation queue
description: The authoritative order, dependencies and current state of MeridianDB's iterative Java and Maven implementation work.
---

# Implementation queue

This file owns current execution state. Follow the [workflow](index.md); read the
selected [roadmap card](roadmap.md) for its outcome and acceptance criteria.
The initial rows are high-level work packages. Expand a broad row into bounded
children before implementation rather than treating a whole milestone as one patch.

## Current checkpoint

- Linux local storage remains the production target; Windows keeps basic compatibility.
  Twelve unfinished S3 rows and planned replication remain deferred with original
  IDs, criteria and completed history. No format or acknowledgement change.
- **MDB-0094 — Attributed local lifecycle core** is complete. Explicit test-only
  execution shares the checked local lifecycle, retains ordered operation/auxiliary
  raw counters and exports original-history denominators in a separate instrumented
  result. Excluded external hashing/inventory coverage stays explicit. Default
  execution activates no capture and retains its original result schema.
- Source `91ae1bc4acedc86708cc7663814260b607ac06ed` is on origin/main.
  Exact-source Java **42382641654** passes all **1,413 core cases /79 suites** and
  **135 Python guards** on Linux with zero skips/failures, including 34 new bridge,
  fault and native lifecycle cases. Inventory SHA256
  `a7fa250e9aa2f561c26a22f55c02c7d98813779888393e49059ad290048592e0`
  preserves every prior suite/count. Both platforms pass 762 optional invocations
  and fourteen examples; Windows retains 394 core /381 optional capability skips.
  Default/scaled supervised Linux smoke and saved-report checks pass. Core remains
  160 classes/JDK-only and classifier ten; new developer helpers remain excluded.
- Exact-source docs **42382641640** and automatic Pages **42382641651** pass.
  Eleven affected live HTTPS page/canonical checks, 101 unique sitemap URLs and
  new guide/ADR search entries pass. Desktop light/mobile dark containment,
  navigation/search and published browser inspection pass. Normal light/default
  viewport is restored; local preview server is stopped.
- MDB-0091 and MDB-0089/0087 remain complete. The original ten-case observations
  and immutable `evidence/linux-observations/2026-11-07/` dataset are unchanged.
  Historical findings, hashes and delivery receipts stay in progress history and
  the [baseline audit](baseline-audit.md). Device/controller/cache, cold-cache and
  production qualification gaps remain MDB-0080.
- Fresh November 7 Maven research keeps twelve current non-AWS stable pins;
  latest AWS 2.57.11 versus retained 2.57.8 remains explicitly deferred. POM unchanged.
- **MDB-0092** retains both required children: completed MDB-0094 Java attribution
  and **MDB-0095 — Attributed serialized evidence integration**, the active
  executable item, owned by the primary coordinator. MDB-0095 owns source-
  bound worker/supervisor/saved-reader admission and recomputable named ratios;
  MDB-0093 retains evaluated Linux runs, durable data and original baseline audit.
  All 95 stable IDs remain. MDB-0092/0090/0083/0012 stay expanded.
  Implement explicit schema-three source-bound attributed evidence and independently
  checked counters/denominators/ratios; preserve ordinary schemas and original data.
  Actual evaluated runs, layout/native acceleration, S3 and releases remain separate.
- MDB-0095 local acceptance passes: explicit schema-three worker/supervisor/saved
  reports, independent source-history/counter/ratio checks and bounded failed
  evidence. All 1,422 core cases /80 suites pass rooted macOS verification without
  skips/failures; 183 Python guards pass (five Linux-only local skips), alongside
  42 docs fixtures and both strict 103-page URL builds. Inventory SHA256
  `dc6edb3b4ce83e2f74f577f3037f92c2b8287c89da803d32e7cf4d05d5191fb9`.
  Keep MDB-0095 active and MDB-0092 expanded until exact-source Linux ordinary+
  attributed/max smoke/readback and automatic Pages/live acceptance pass.

## Ordered work

| ID | Work item | Parent | State | Depends on |
| --- | --- | --- | --- | --- |
| [MDB-0001](roadmap.md#mdb-0001-planning-workflow) | Establish the plan and iteration workflow | — | done | — |
| [MDB-0002](roadmap.md#mdb-0002-temporal-contract) | Define the initial temporal contract and workloads | — | done | MDB-0001 |
| [MDB-0003](roadmap.md#mdb-0003-java-and-maven-foundation) | Establish the Java and Maven build foundation | — | done | MDB-0002 |
| [MDB-0004](roadmap.md#mdb-0004-temporal-types-and-reference-model) | Implement temporal types and the reference model | — | done | MDB-0003 |
| [MDB-0005](roadmap.md#mdb-0005-embedded-in-memory-engine) | Build an embedded in-memory engine | — | done | MDB-0004 |
| [MDB-0006](roadmap.md#mdb-0006-local-storage-and-journal) | Define local storage capabilities and a commit journal | — | done | MDB-0005 |
| [MDB-0015](roadmap.md#mdb-0015-journal-format-and-codec) | Specify and implement the bounded journal format and codec | MDB-0006 | done | MDB-0005 |
| [MDB-0017](roadmap.md#mdb-0017-public-documentation-quality) | Establish and apply public documentation QA | — | done | MDB-0015 |
| [MDB-0016](roadmap.md#mdb-0016-local-journal-io-and-recovery) | Implement local journal append, sync, ownership and recovery | MDB-0006 | done | MDB-0005, MDB-0015, MDB-0017 |
| [MDB-0007](roadmap.md#mdb-0007-immutable-segments-and-manifests) | Implement versioned immutable segments and manifests | — | done | MDB-0006 |
| [MDB-0018](roadmap.md#mdb-0018-immutable-segment-format-and-codec) | Specify and implement immutable history segment format and codec | MDB-0007 | done | MDB-0006 |
| [MDB-0019](roadmap.md#mdb-0019-local-immutable-segment-io) | Implement local immutable segment I/O and reader lifetimes | MDB-0007 | done | MDB-0006, MDB-0018 |
| [MDB-0020](roadmap.md#mdb-0020-checkpoint-manifest-and-publication) | Implement checkpoint manifests and local publication | MDB-0007 | done | MDB-0006, MDB-0019 |
| [MDB-0021](roadmap.md#mdb-0021-manifest-format-and-checkpoint-consistency) | Implement manifest format and checkpoint consistency | MDB-0020 | done | MDB-0006, MDB-0019 |
| [MDB-0022](roadmap.md#mdb-0022-local-checkpoint-publication) | Implement local checkpoint publication and reopen | MDB-0020 | done | MDB-0006, MDB-0019, MDB-0021 |
| [MDB-0008](roadmap.md#mdb-0008-durable-engine-and-recovery) | Integrate durable commits, reopen and recovery | — | done | MDB-0007 |
| [MDB-0023](roadmap.md#mdb-0023-local-database-recovery) | Restore local database reads from checkpoints and journal tails | MDB-0008 | done | MDB-0007 |
| [MDB-0024](roadmap.md#mdb-0024-durable-commit-publication) | Add forced commit publication and acknowledgement | MDB-0008 | done | MDB-0007, MDB-0023 |
| [MDB-0025](roadmap.md#mdb-0025-durable-crash-and-retry-conformance) | Verify durable crash, retry and temporal conformance | MDB-0008 | done | MDB-0007, MDB-0024 |
| [MDB-0009](roadmap.md#mdb-0009-temporal-query-operators) | Implement temporal query operators and interval scans | — | done | MDB-0008 |
| [MDB-0010](roadmap.md#mdb-0010-compaction-and-retention) | Add history-preserving compaction and reclamation | — | done | MDB-0009 |
| [MDB-0026](roadmap.md#mdb-0026-bounded-history-compaction) | Define retention and implement bounded pure history compaction | MDB-0010 | done | MDB-0009 |
| [MDB-0027](roadmap.md#mdb-0027-local-compaction-publication) | Install and publish verified local compaction replacements | MDB-0010 | done | MDB-0009, MDB-0026 |
| [MDB-0028](roadmap.md#mdb-0028-reader-safe-physical-reclamation) | Protect readers and reclaim obsolete physical objects safely | MDB-0010 | done | MDB-0009, MDB-0027 |
| [MDB-0030](roadmap.md#mdb-0030-cross-process-reader-protection) | Protect lazy readers across store lifetimes and processes | MDB-0028 | done | MDB-0009, MDB-0027 |
| [MDB-0031](roadmap.md#mdb-0031-protected-physical-reclamation) | Reclaim obsolete objects under exclusive reader protection | MDB-0028 | done | MDB-0009, MDB-0027, MDB-0030 |
| [MDB-0032](roadmap.md#mdb-0032-verified-reclamation-inventory) | Inspect retained roots and verified obsolete objects | MDB-0031 | done | MDB-0009, MDB-0027, MDB-0030 |
| [MDB-0033](roadmap.md#mdb-0033-ordered-protected-removal) | Remove verified obsolete objects with ordered synchronization | MDB-0031 | done | MDB-0009, MDB-0027, MDB-0030, MDB-0032 |
| [MDB-0029](roadmap.md#mdb-0029-compaction-and-reclamation-conformance) | Verify compaction, reclamation, crash and temporal conformance | MDB-0010 | done | MDB-0009, MDB-0026, MDB-0027, MDB-0028 |
| [MDB-0078](roadmap.md#mdb-0078-linux-local-storage-priority-and-format-research) | Adopt Linux priority and research temporal layouts and native I/O | — | done | MDB-0010, MDB-0034 |
| [MDB-0079](roadmap.md#mdb-0079-linux-storage-contract-and-qualification-harness) | Define and exercise named Linux storage capability profiles | — | done | MDB-0010, MDB-0078 |
| [MDB-0012](roadmap.md#mdb-0012-reproducible-workload-baseline) | Establish reproducible workload measurements | — | expanded | MDB-0010, MDB-0079 |
| [MDB-0081](roadmap.md#mdb-0081-deterministic-temporal-workload-corpus) | Build bounded deterministic temporal actions and independent expected outcomes | MDB-0012 | done | MDB-0010, MDB-0079 |
| [MDB-0082](roadmap.md#mdb-0082-local-workload-measurement-runner) | Measure local execution with explicit setup, timing and resource semantics | MDB-0012 | done | MDB-0010, MDB-0079, MDB-0081 |
| [MDB-0084](roadmap.md#mdb-0084-local-operation-measurement-core) | Measure and check bounded local operation lifecycles on fresh stores | MDB-0082 | done | MDB-0010, MDB-0079, MDB-0081 |
| [MDB-0085](roadmap.md#mdb-0085-supervised-linux-workload-evidence) | Add supervised execution, result/provenance validation and Linux storage accounting | MDB-0082 | done | MDB-0010, MDB-0079, MDB-0081, MDB-0084 |
| [MDB-0083](roadmap.md#mdb-0083-linux-baseline-evaluation-and-conformance) | Evaluate reproducible Linux workloads and complete baseline acceptance | MDB-0012 | expanded | MDB-0010, MDB-0079, MDB-0081, MDB-0082 |
| [MDB-0086](roadmap.md#mdb-0086-configurable-supervised-workload-dimensions) | Admit independently scaled dimensions with exact evidence bindings | MDB-0083 | done | MDB-0010, MDB-0079, MDB-0081, MDB-0082 |
| [MDB-0087](roadmap.md#mdb-0087-linux-baseline-runs-and-integration-audit) | Evaluate repeatable Linux runs and audit all baseline criteria | MDB-0083 | done | MDB-0010, MDB-0079, MDB-0081, MDB-0082, MDB-0086 |
| [MDB-0088](roadmap.md#mdb-0088-checked-saved-workload-reports) | Recompute and render recorded workload evidence after original files disappear | MDB-0087 | done | MDB-0010, MDB-0079, MDB-0081, MDB-0082, MDB-0086 |
| [MDB-0089](roadmap.md#mdb-0089-linux-evaluation-runs-and-baseline-acceptance) | Run and evaluate reproducible Linux workloads and complete baseline integration | MDB-0087 | done | MDB-0010, MDB-0079, MDB-0081, MDB-0082, MDB-0086, MDB-0088 |
| [MDB-0090](roadmap.md#mdb-0090-operation-attributed-file-work-accounting) | Measure scoped completed file I/O and recomputable amplification denominators | MDB-0083 | expanded | MDB-0010, MDB-0079, MDB-0081, MDB-0082, MDB-0086, MDB-0088, MDB-0089 |
| [MDB-0091](roadmap.md#mdb-0091-internal-local-io-counters) | Count completed native file work without changing storage behavior | MDB-0090 | done | MDB-0010, MDB-0079, MDB-0081, MDB-0082, MDB-0086, MDB-0088, MDB-0089 |
| [MDB-0092](roadmap.md#mdb-0092-attributed-workload-and-result-integration) | Bind operation scopes, raw counters and named ratios into separate workload results | MDB-0090 | expanded | MDB-0010, MDB-0079, MDB-0081, MDB-0082, MDB-0086, MDB-0088, MDB-0089, MDB-0091 |
| [MDB-0094](roadmap.md#mdb-0094-attributed-local-lifecycle-core) | Capture typed operation and auxiliary file work on separate local lifecycles | MDB-0092 | done | MDB-0010, MDB-0079, MDB-0081, MDB-0082, MDB-0086, MDB-0088, MDB-0089, MDB-0091 |
| [MDB-0095](roadmap.md#mdb-0095-attributed-serialized-evidence-integration) | Admit and report bounded attributed worker/supervisor evidence | MDB-0092 | active | MDB-0010, MDB-0079, MDB-0081, MDB-0082, MDB-0086, MDB-0088, MDB-0089, MDB-0091, MDB-0094 |
| [MDB-0093](roadmap.md#mdb-0093-linux-attributed-work-and-baseline-acceptance) | Evaluate checked attributed Linux runs and complete original baseline audit | MDB-0090 | queued | MDB-0010, MDB-0079, MDB-0081, MDB-0082, MDB-0086, MDB-0088, MDB-0089, MDB-0091, MDB-0092 |
| [MDB-0013](roadmap.md#mdb-0013-measured-layout-and-index-experiments) | Evaluate layouts and indexes against the baseline | — | queued | MDB-0012 |
| [MDB-0080](roadmap.md#mdb-0080-linux-operational-hardening-and-qualification-gaps) | Close evidenced Linux resource, lifecycle and operational gaps | — | queued | MDB-0079, MDB-0013 |
| [MDB-0014](roadmap.md#mdb-0014-prototype-release-candidate) | Prepare a reproducible prototype release candidate | — | queued | MDB-0013, MDB-0080 |
| [MDB-0011](roadmap.md#mdb-0011-object-storage-backend) | Add an object-storage backend and conformance checks | — | deferred | MDB-0010 |
| [MDB-0034](roadmap.md#mdb-0034-bitemporal-persistence-boundaries) | Define bitemporal persistence boundaries and staged backend work | MDB-0011 | done | MDB-0010 |
| [MDB-0035](roadmap.md#mdb-0035-bounded-immutable-object-access) | Implement bounded immutable-object access and local conformance | MDB-0011 | done | MDB-0010, MDB-0034 |
| [MDB-0042](roadmap.md#mdb-0042-repository-sponsorship) | Enable repository sponsorship using the existing verified recipient | — | done | — |
| [MDB-0043](roadmap.md#mdb-0043-sponsors-profile-description) | Refresh the central Sponsors bio and introduction | — | done | MDB-0042 |
| [MDB-0044](roadmap.md#mdb-0044-meridiandb-visual-identity) | Create SVG identity and GitHub/website PNG assets | — | done | — |
| [MDB-0045](roadmap.md#mdb-0045-dedicated-meridiandb-sponsorship) | Configure the approved MeridianDB Sponsors profile and migrate funding links | — | done | MDB-0044 |
| [MDB-0046](roadmap.md#mdb-0046-temporal-geometry-and-index-research) | Document temporal geometry and reusable Lucene/Solr indexing ideas | — | done | MDB-0009 |
| [MDB-0036](roadmap.md#mdb-0036-optional-s3-compatible-object-access) | Add optional S3-compatible object access and provider checks | MDB-0011 | done | MDB-0010, MDB-0035 |
| [MDB-0037](roadmap.md#mdb-0037-conditional-object-checkpoint-publication) | Implement conditional object checkpoint publication | MDB-0011 | done | MDB-0010, MDB-0036 |
| [MDB-0047](roadmap.md#mdb-0047-bounded-object-checkpoint-head) | Specify and encode bounded object checkpoint heads | MDB-0037 | done | MDB-0010, MDB-0036 |
| [MDB-0048](roadmap.md#mdb-0048-conditional-s3-head-access) | Implement bounded S3 head reads and conditional replacement | MDB-0037 | done | MDB-0010, MDB-0036, MDB-0047 |
| [MDB-0049](roadmap.md#mdb-0049-verified-object-checkpoint-publication) | Publish verified object checkpoints against the actual owned journal | MDB-0037 | done | MDB-0010, MDB-0036, MDB-0048 |
| [MDB-0050](roadmap.md#mdb-0050-bounded-owned-journal-prefix) | Bound actual owned-journal prefix reads before allocation | MDB-0049 | done | MDB-0010, MDB-0036, MDB-0048 |
| [MDB-0051](roadmap.md#mdb-0051-verified-object-checkpoint-graphs) | Verify bounded object checkpoint graphs against the owned journal | MDB-0049 | done | MDB-0010, MDB-0036, MDB-0048, MDB-0050 |
| [MDB-0052](roadmap.md#mdb-0052-durable-object-publication-intent) | Persist expected head and exact pending publication intent | MDB-0049 | done | MDB-0010, MDB-0036, MDB-0048, MDB-0051 |
| [MDB-0055](roadmap.md#mdb-0055-bounded-s3-publication-state) | Encode bounded S3 publication state and exact pending intent | MDB-0052 | done | MDB-0010, MDB-0036, MDB-0048, MDB-0051 |
| [MDB-0056](roadmap.md#mdb-0056-durable-local-publication-state) | Install local publication state under durable exclusive ownership | MDB-0052 | done | MDB-0010, MDB-0036, MDB-0048, MDB-0051, MDB-0055 |
| [MDB-0053](roadmap.md#mdb-0053-verified-conditional-publisher) | Integrate verified conditional publication and reconciliation | MDB-0049 | done | MDB-0010, MDB-0036, MDB-0048, MDB-0051, MDB-0052 |
| [MDB-0057](roadmap.md#mdb-0057-verified-durable-s3-preparation) | Prepare verified exact S3 intent durably without selecting it | MDB-0053 | done | MDB-0010, MDB-0036, MDB-0048, MDB-0051, MDB-0052 |
| [MDB-0058](roadmap.md#mdb-0058-conditional-submission-and-reconciliation) | Submit/reconcile exact pending intent and complete publisher conformance | MDB-0053 | done | MDB-0010, MDB-0036, MDB-0048, MDB-0051, MDB-0052, MDB-0057 |
| [MDB-0054](roadmap.md#mdb-0054-legacy-bounded-segment-preflight) | Apply expected-range preflight to legacy bounded consumers | — | done | MDB-0010, MDB-0051 |
| [MDB-0038](roadmap.md#mdb-0038-remote-checkpoint-engine-recovery) | Integrate remote checkpoints with local journal and engine recovery | MDB-0011 | done | MDB-0010, MDB-0037 |
| [MDB-0059](roadmap.md#mdb-0059-bounded-pinned-object-history) | Load immutable history from one fully verified pinned object graph | MDB-0038 | done | MDB-0010, MDB-0037 |
| [MDB-0060](roadmap.md#mdb-0060-core-pinned-checkpoint-recovery) | Recover a read-only temporal engine from pinned history and the actual local tail | MDB-0038 | done | MDB-0010, MDB-0037, MDB-0059 |
| [MDB-0061](roadmap.md#mdb-0061-managed-s3-recovery-admission) | Bind S3 recovery startup to actual scope and durable publication state | MDB-0038 | done | MDB-0010, MDB-0037, MDB-0060 |
| [MDB-0062](roadmap.md#mdb-0062-writable-remote-recovery-conformance) | Preserve forced writes after remote recovery and complete parent conformance | MDB-0038 | done | MDB-0010, MDB-0037, MDB-0061 |
| [MDB-0063](roadmap.md#mdb-0063-core-pinned-checkpoint-writes) | Enable forced local writes after explicit pinned checkpoint recovery | MDB-0062 | done | MDB-0010, MDB-0037, MDB-0061 |
| [MDB-0064](roadmap.md#mdb-0064-managed-s3-writable-recovery) | Admit writable managed S3 recovery with retained ownership | MDB-0062 | done | MDB-0010, MDB-0037, MDB-0061, MDB-0063 |
| [MDB-0065](roadmap.md#mdb-0065-remote-writable-recovery-conformance) | Complete remote writable canonical, seeded and interruption conformance | MDB-0062 | done | MDB-0010, MDB-0037, MDB-0061, MDB-0064 |
| [MDB-0039](roadmap.md#mdb-0039-reader-safe-object-reclamation) | Protect remote readers and reclaim proven obsolete objects | MDB-0011 | deferred | MDB-0010, MDB-0038 |
| [MDB-0066](roadmap.md#mdb-0066-remote-reclamation-protocol-and-staged-plan) | Specify coordinated remote reclamation and staged acceptance | MDB-0039 | done | MDB-0010, MDB-0038 |
| [MDB-0067](roadmap.md#mdb-0067-coordinated-s3-namespace-admission) | Admit coordinated remote activity with durable mutation hazards | MDB-0039 | deferred | MDB-0010, MDB-0038, MDB-0066 |
| [MDB-0071](roadmap.md#mdb-0071-bounded-s3-coordination-state-and-codec) | Encode bounded namespace, root and exact-attempt declarations | MDB-0067 | done | MDB-0010, MDB-0038, MDB-0066 |
| [MDB-0072](roadmap.md#mdb-0072-local-coordination-guard-and-durable-ledger) | Retain shared/exclusive local coordination and durable hazard evidence | MDB-0067 | deferred | MDB-0010, MDB-0038, MDB-0066, MDB-0071 |
| [MDB-0075](roadmap.md#mdb-0075-permanent-local-s3-coordination-guard) | Protect existing permanent native guard with bounded shared/exclusive leases | MDB-0072 | done | MDB-0010, MDB-0038, MDB-0066, MDB-0071 |
| [MDB-0076](roadmap.md#mdb-0076-durable-s3-coordination-generations) | Install and reopen exact forced coordination/receipt generations | MDB-0072 | deferred | MDB-0010, MDB-0038, MDB-0066, MDB-0071, MDB-0075 |
| [MDB-0077](roadmap.md#mdb-0077-coordination-guard-and-ledger-conformance) | Complete native/ledger creation, ownership, failure and process conformance | MDB-0072 | deferred | MDB-0010, MDB-0038, MDB-0066, MDB-0071, MDB-0075, MDB-0076 |
| [MDB-0073](roadmap.md#mdb-0073-participating-s3-sessions-and-root-registration) | Compose actual guarded SDK activity and complete root registration | MDB-0067 | deferred | MDB-0010, MDB-0038, MDB-0066, MDB-0071, MDB-0072 |
| [MDB-0074](roadmap.md#mdb-0074-coordinated-admission-and-hazard-conformance) | Complete process, ownership, SDK and uncertainty admission conformance | MDB-0067 | deferred | MDB-0010, MDB-0038, MDB-0066, MDB-0071, MDB-0072, MDB-0073 |
| [MDB-0068](roadmap.md#mdb-0068-verified-remote-reclamation-inventory) | Verify bounded remote roots and exact obsolete-object inventory | MDB-0039 | deferred | MDB-0010, MDB-0038, MDB-0067 |
| [MDB-0069](roadmap.md#mdb-0069-guarded-conditional-s3-deletion) | Implement exact guarded conditional deletion through the SDK | MDB-0039 | deferred | MDB-0010, MDB-0038, MDB-0067, MDB-0068 |
| [MDB-0070](roadmap.md#mdb-0070-ordered-remote-reclamation-conformance) | Retire obsolete metadata and dependencies with full reclamation conformance | MDB-0039 | deferred | MDB-0010, MDB-0038, MDB-0067, MDB-0068, MDB-0069 |
| [MDB-0040](roadmap.md#mdb-0040-local-and-object-backend-conformance) | Verify common local and object backend conformance | MDB-0011 | deferred | MDB-0010, MDB-0038, MDB-0039 |
| [MDB-0041](roadmap.md#mdb-0041-replication-protocol-and-staged-plan) | Specify optional replication protocol and staged work | — | deferred | MDB-0008, MDB-0034 |

Selection is top to bottom among eligible executable rows. The initial dependency
chain keeps the core design coherent; split tasks may expose genuinely independent
work without changing the parent outcomes. Add rows and dependencies as evidence
demands. Keep blocked rows in place with an explicit checkpoint and unblock action.

## State meanings

| State | Meaning |
| --- | --- |
| queued | Planned outcome; prerequisites or refinement remain |
| ready | Source prerequisites met; confirm remote delivery and CI before claiming |
| active | The one executable item currently owned by an invocation |
| blocked | Unfinished; checkpoint names the concrete dependency and unblock condition |
| expanded | Non-executable parent whose required child steps are tracked separately |
| done | Source acceptance criteria met; verify its completion commit on remote main before advancing |
| deferred | Useful possible work outside the selected near-term path |
| cancelled | Retained identity with a documented owner-approved reason or superseding task |

Update the current checkpoint and table together at each iteration. When committing
completion, the next row may be prepared as `ready` in the same commit; its
execution remains conditional on that completion commit reaching remote `main`
and on resolution of any relevant CI failure. See the [progress history](progress.md)
for dated evidence, not for live selection.

## Deferred extensions

Unfinished S3 parents retain their original expansion/required-child metadata in
[the roadmap](roadmap.md). Every unfinished S3 row is deferred under the November 2
Linux priority; completed children remain done. Re-admission requires a deliberate
priority decision and the original completion criteria. No local benchmark,
optimization, qualification or candidate depends on finishing those parents.
Kafka-topic persistence and other providers have no admitted implementation task.

MDB-0041 records the optional one-writer/read-only-follower replication direction
and its planning acceptance criteria. It remains deferred, separate from storage
adapters and outside MDB-0014's dependency chain. SQL or another public query
language, network services, distributed leadership, multiple writers, language
wrappers and native internals are further options. Admit a concrete executable
task when an owner requirement or measured limitation supplies its scope; recording
an extension direction does not implement or start it.
