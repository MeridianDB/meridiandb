---
title: Internal local I/O counters decision
description: Observe completed caller-thread local FileChannel work with fixed internal counters, preserving native failures, ownership and acknowledgement while keeping workload attribution separate.
---

# ADR 0045: Internal local I/O counters

- **Date:** November 7, 2026
- **Status:** Counter foundation, in-process attribution and serialized reporting implemented; evaluation pending
- **Scope:** Internal caller-thread observation of local adapter transfers and force

## Context

Whole-process I/O includes validation, runtime and maintenance activity. Final
file lengths also hide repeated reads and writes. Neither observation attributes
work to a commit, recovery or checkpoint operation. Counting requested lengths
would additionally misreport short or failed transfers.

## Decision

Add a fixed internal counter layer at the concrete `FileChannel` boundary of the
local adapters. Count actual returned positive bytes, zero/EOF and attempts/
returns/failures separately. Count successful force only after it returns. A call
that throws can have unknown effects; retain earlier completed work without
inventing the additional bytes or replacing the original exception.

Use package-private caller-thread capture activation, fixed roles/counters,
nonoverlapping scopes and bounded active captures. Resolve the current scope on
every transfer because an owner can outlive the scope that opened it. Primitive
counter updates have no callback or growing event buffer. Saturation invalidates
telemetry rather than failing or poisoning storage. Scope checks and immutable
snapshot allocation happen outside native calls.

The [counter guide](../development/local-io-counters.md) owns exact meanings,
coverage, bounds and verification commands. Cover both checkpoint and standalone
segment I/O, actual directory probes, reader controls and diagnostic tail writes
without counting outer and inner operations twice.

## Consequences

The foundation adds no runtime dependency, public activation API, format change
or altered force/ownership/acknowledgement behavior. It observes adapter-completed
bytes, not actual device traffic, metadata bytes or cold-cache behavior.

Native tests can independently verify exact work. A separate
[attributed lifecycle core](../development/attributed-local-workloads.md) labels
engine/setup/validation/teardown scopes and exports logical denominators. The
[evidence path](../development/attributed-workload-evidence.md) adds source binding,
serialized raw-counter admission and checked named-ratio reporting. Instrumented
runs do not silently replace original latency observations.
Integrated baseline and production qualification remain pending.
