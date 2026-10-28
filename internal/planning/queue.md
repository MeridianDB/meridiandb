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

- Active executable item: **none**. MDB-0017's source outcome is complete.
- Public documentation now uses direct technical prose. Execution records and site
  administration live under `internal/`, outside website input; the public roadmap
  describes capabilities. Agent guidance and both documentation workflows enforce
  the shared source/rendered QA procedure.
- **MDB-0016 is ready** after verifying MDB-0017's completion on remote `main` and
  its relevant CI/publication outcome. No local journal I/O implementation has begun.
- MDB-0015 completion `f9fd25d` was verified on remote `main`, with successful Java
  Linux/Windows/example, documentation checks and Pages publication before MDB-0017.
- MDB-0006 remains expanded; both original children and its acceptance criteria are
  preserved. Resume MDB-0016 as the next bounded implementation step.
- Follow the [documentation quality standard](../documentation-quality.md),
  [progress receipt](progress.md)
  and [site operations](../site-operations.md) for verification and delivery.

## Ordered work

| ID | Work item | Parent | State | Depends on |
| --- | --- | --- | --- | --- |
| [MDB-0001](roadmap.md#mdb-0001-planning-workflow) | Establish the plan and iteration workflow | — | done | — |
| [MDB-0002](roadmap.md#mdb-0002-temporal-contract) | Define the initial temporal contract and workloads | — | done | MDB-0001 |
| [MDB-0003](roadmap.md#mdb-0003-java-and-maven-foundation) | Establish the Java and Maven build foundation | — | done | MDB-0002 |
| [MDB-0004](roadmap.md#mdb-0004-temporal-types-and-reference-model) | Implement temporal types and the reference model | — | done | MDB-0003 |
| [MDB-0005](roadmap.md#mdb-0005-embedded-in-memory-engine) | Build an embedded in-memory engine | — | done | MDB-0004 |
| [MDB-0006](roadmap.md#mdb-0006-local-storage-and-journal) | Define local storage capabilities and a commit journal | — | expanded | MDB-0005 |
| [MDB-0015](roadmap.md#mdb-0015-journal-format-and-codec) | Specify and implement the bounded journal format and codec | MDB-0006 | done | MDB-0005 |
| [MDB-0017](roadmap.md#mdb-0017-public-documentation-quality) | Establish and apply public documentation QA | — | done | MDB-0015 |
| [MDB-0016](roadmap.md#mdb-0016-local-journal-io-and-recovery) | Implement local journal append, sync, ownership and recovery | MDB-0006 | ready | MDB-0005, MDB-0015, MDB-0017 |
| [MDB-0007](roadmap.md#mdb-0007-immutable-segments-and-manifests) | Implement versioned immutable segments and manifests | — | queued | MDB-0006 |
| [MDB-0008](roadmap.md#mdb-0008-durable-engine-and-recovery) | Integrate durable commits, reopen and recovery | — | queued | MDB-0007 |
| [MDB-0009](roadmap.md#mdb-0009-temporal-query-operators) | Implement temporal query operators and interval scans | — | queued | MDB-0008 |
| [MDB-0010](roadmap.md#mdb-0010-compaction-and-retention) | Add history-preserving compaction and reclamation | — | queued | MDB-0009 |
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
