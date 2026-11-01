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

- MDB-0058 source `9e07766de3ae79bbcd52af048e72099e86c7840d` is verified on
  origin/main with matching tips/ancestry. Java **41259110096**, docs **41259110074**
  and automatic Pages **41259110065** pass. Live guide/ADR/current callers,
  canonical URLs/search and the **67-page** sitemap are verified. Ubuntu runs
  all cases; Windows reports 228 core and 185 optional capability skips.
- **MDB-0058 — Conditional submission and reconciliation** is done. Exact durable
  pending intent, immutable evidence, actual client/owner admission, total two-pass
  graph reservation, one original-precondition attempt and fresh-candidate durable
  reconciliation now compose the full experimental publisher. All 78 new cases,
  1,092 core / 402 optional cases, fourteen examples, artifact inspection and both
  strict 67-page builds pass locally without skips. No active claim remains.
- Close **MDB-0053**, **MDB-0049** and **MDB-0037** with all required children and
  original SDK/local integration criteria complete: lost replies, competing writes,
  stale reads, late effects across reopen, actual divergent history, shared budgets,
  process stops, native result failures and retained evidence. MDB-0011 stays
  expanded: remote engine recovery, reader-safe reclamation and common backend
  conformance remain required. Local acknowledgement and placement limits persist.
- **MDB-0054 — Legacy bounded segment preflight** is done from clean main/origin
  `809f669728646d911351dc43949e70996f3ff0a9`. Pure compaction and protected local
  inventory/removal bind reserved ranges before body hashing/record allocation/
  decoding, retaining public raw verifier and ordinary publication/load behavior.
  All 23 new cases, 1,115 core / 402 optional cases, fourteen packaged-core examples,
  artifact inspection and both strict 67-page builds pass locally without skips.
  Completion commit/push, remote tips/ancestry, CI/Pages and live readback remain
  the delivery gate. No active claim remains and no new IDs were allocated.
- **MDB-0038 — Remote checkpoint engine recovery** is source-ready, conditional
  on verified delivery above. Refine its integration/recovery acceptance before
  implementation; preserve actual-journal, temporal/retry and local acknowledgement
  contracts. Remote reader-safe reclamation and common conformance remain separate.

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
| [MDB-0011](roadmap.md#mdb-0011-object-storage-backend) | Add an object-storage backend and conformance checks | — | expanded | MDB-0010 |
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
| [MDB-0038](roadmap.md#mdb-0038-remote-checkpoint-engine-recovery) | Integrate remote checkpoints with local journal and engine recovery | MDB-0011 | ready | MDB-0010, MDB-0037 |
| [MDB-0039](roadmap.md#mdb-0039-reader-safe-object-reclamation) | Protect remote readers and reclaim proven obsolete objects | MDB-0011 | queued | MDB-0010, MDB-0038 |
| [MDB-0040](roadmap.md#mdb-0040-local-and-object-backend-conformance) | Verify common local and object backend conformance | MDB-0011 | queued | MDB-0010, MDB-0038, MDB-0039 |
| [MDB-0012](roadmap.md#mdb-0012-reproducible-workload-baseline) | Establish reproducible workload measurements | — | queued | MDB-0011 |
| [MDB-0013](roadmap.md#mdb-0013-measured-layout-and-index-experiments) | Evaluate layouts and indexes against the baseline | — | queued | MDB-0012 |
| [MDB-0014](roadmap.md#mdb-0014-prototype-release-candidate) | Prepare a reproducible prototype release candidate | — | queued | MDB-0013 |
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

## Ideas beyond this prototype

MDB-0041 records the optional one-writer/read-only-follower replication direction
and its planning acceptance criteria. It remains deferred, separate from storage
adapters and outside MDB-0014's dependency chain. SQL or another public query
language, network services, distributed leadership, multiple writers, language
wrappers and native internals are further options. Admit a concrete executable
task when an owner requirement or measured limitation supplies its scope; recording
an extension direction does not implement or start it.
