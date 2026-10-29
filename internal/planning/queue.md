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

- No executable item is active. **MDB-0030's source criteria pass**: lazy readers
  hold a shared cross-process barrier independently of store ownership, with one
  reference-counted channel per loaded JVM copy and synchronized lazy access/release.
- **MDB-0028 remains expanded** into required MDB-0030 (reader protection) and
  MDB-0031 (protected physical reclamation). Its original criteria and MDB-0010's
  required integration remain intact. No physical deletion is implemented here.
- Next source-ready item: **MDB-0031 — Reclaim obsolete objects under exclusive reader protection**.
  Verify this completion on remote main and inspect Java/documentation CI and
  automatic Pages before claiming it. Hold store ownership and exclusive reader
  protection through root selection/removal/sync; any live reader defers all removal.
- All **678 local tests** and **eleven examples** pass with no test skips. Seventy
  new reader/identity/cleanup cases and four permanent-capacity regressions supplement
  the existing suite, plus one real-parent journal path regression. Both URL layouts pass documentation QA with **40 pages**.
- Cross-adapter identity reservations protect controls and transient data channels,
  including aliases across stores. Creation/publication reserves an absent guard
  slot. A thrown native close remains quarantined despite Java reporting closed;
  no-op retries cannot clear that uncertainty, so process restart is required.
- The full journal and every old/failed object remain retained. All participating
  readers must use the protocol and one loaded library copy per JVM; legacy readers
  cannot be retroactively protected. Long-lived readers may indefinitely block cleanup.
- Delivery repair: `6fb7b60c0e435db13d4dec913365885944199fff` passed Linux Java,
  docs/Pages and 40-page live QA; Windows exposed two POSIX-specific path-fixture
  assumptions. Use provider-real-parent expected files without weakening identity
  checks or adding skips. Verify the repair commit's Windows CI before advancing.
- Previous completion `798dfce47f57279ee25336884766ff4cbf8da47e` is on remote main;
  Java, documentation checks and automatic Pages all passed. Its handoff verified
  38 live pages, search and sitemap. Use Git/workflows for actual delivery status.

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
| [MDB-0010](roadmap.md#mdb-0010-compaction-and-retention) | Add history-preserving compaction and reclamation | — | expanded | MDB-0009 |
| [MDB-0026](roadmap.md#mdb-0026-bounded-history-compaction) | Define retention and implement bounded pure history compaction | MDB-0010 | done | MDB-0009 |
| [MDB-0027](roadmap.md#mdb-0027-local-compaction-publication) | Install and publish verified local compaction replacements | MDB-0010 | done | MDB-0009, MDB-0026 |
| [MDB-0028](roadmap.md#mdb-0028-reader-safe-physical-reclamation) | Protect readers and reclaim obsolete physical objects safely | MDB-0010 | expanded | MDB-0009, MDB-0027 |
| [MDB-0030](roadmap.md#mdb-0030-cross-process-reader-protection) | Protect lazy readers across store lifetimes and processes | MDB-0028 | done | MDB-0009, MDB-0027 |
| [MDB-0031](roadmap.md#mdb-0031-protected-physical-reclamation) | Reclaim obsolete objects under exclusive reader protection | MDB-0028 | ready | MDB-0009, MDB-0027, MDB-0030 |
| [MDB-0029](roadmap.md#mdb-0029-compaction-and-reclamation-conformance) | Verify compaction, reclamation, crash and temporal conformance | MDB-0010 | queued | MDB-0009, MDB-0026, MDB-0027, MDB-0028 |
| [MDB-0011](roadmap.md#mdb-0011-object-storage-backend) | Add an object-storage backend and conformance checks | — | queued | MDB-0010 |
| [MDB-0012](roadmap.md#mdb-0012-reproducible-workload-baseline) | Establish reproducible workload measurements | — | queued | MDB-0011 |
| [MDB-0013](roadmap.md#mdb-0013-measured-layout-and-index-experiments) | Evaluate layouts and indexes against the baseline | — | queued | MDB-0012 |
| [MDB-0014](roadmap.md#mdb-0014-prototype-release-candidate) | Prepare a reproducible prototype release candidate | — | queued | MDB-0013 |

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

SQL or another public query language, network services, replication, multiple
writers, language wrappers and native internals are future options. They are not
implicit prerequisites of MDB-0014. Admit a concrete task when an owner requirement
or measured limitation supplies its outcome and completion criteria.
