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

- No executable item is active. **MDB-0035's source criteria pass**: bounded
  immutable-object types/interface, a JDK-only local adapter, verified heap ranges,
  explicit identical reuse and a segment-codec consumer are implemented.
  Existing segment/checkpoint/journal APIs, formats and engine acknowledgement
  remain unchanged. No provider SDK, physical range I/O or remote publication is added.
- **970 local Java tests and fourteen examples pass**, with no failures/errors/skips.
  The 84 new invocations are eight type cases, nine reusable contract cases,
  ten local cases, 53 injected-I/O cases and four segment compatibility cases.
  The new example also passes against the packaged JAR; `jdeps -s` reports only
  `java.base`. The JAR has 137 Java 25 classes and no test/reference fixture content.
- Both public URL layouts pass source/HTML/link/search/sitemap QA for **49 pages**,
  and all 41 documentation QA tests pass. Light/dark, narrow table, new ADR,
  navigation and `createOrVerify` search preview checks pass. The production site
  build is restored after the project-path check.
- Fresh Central/official compatibility research on October 31 at 13:38:43 EDT /
  17:38:43 UTC confirms all twelve Maven pins remain latest stable. No POM,
  wrapper, dependency, fixture or runtime-library change is needed.
- **MDB-0011 remains expanded**. Next source-ready item: **MDB-0036 — Add optional
  S3-compatible object access and provider checks**. Verify this completion on
  remote main and Java/docs/automatic Pages before claiming it. Refine provider
  packaging, maintained-library selection and controlled SDK/HTTP fixture before
  implementation; preserve standalone local use and separate checkpoint/engine gates.
- MDB-0041 remains deferred; no replication/failover work is started. Prior
  `24df9b0871bd208fe3c0bd436ece8185852f0663` matches origin/main at iteration start;
  docs 41222060256 and automatic Pages 41222060285 succeeded. Git/workflows own
  this completion's later delivery evidence.

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
| [MDB-0036](roadmap.md#mdb-0036-optional-s3-compatible-object-access) | Add optional S3-compatible object access and provider checks | MDB-0011 | ready | MDB-0010, MDB-0035 |
| [MDB-0037](roadmap.md#mdb-0037-conditional-object-checkpoint-publication) | Implement conditional object checkpoint publication | MDB-0011 | queued | MDB-0010, MDB-0036 |
| [MDB-0038](roadmap.md#mdb-0038-remote-checkpoint-engine-recovery) | Integrate remote checkpoints with local journal and engine recovery | MDB-0011 | queued | MDB-0010, MDB-0037 |
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
