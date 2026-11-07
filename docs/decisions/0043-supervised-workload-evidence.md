---
title: Supervised workload evidence decision
description: Admit Linux workload observations only after bounded process execution, fresh source/class/runtime bindings, strict result checks and independently observed file accounting.
---

# ADR 0043: Supervised workload evidence

- **Date:** November 3, 2026
- **Status:** Supervised harness and bounded ordinary/attributed observations available
- **Scope:** Linux execution, result admission and storage/process accounting

## Context

In-process operation clocks do not stop a blocked native call, bind an observation
to freshly compiled code or prove that a serialized file is complete. Logical
storage lengths also differ from allocated file blocks and process I/O accounting.
Treating these as interchangeable would make later layout comparisons unreliable.

## Decision

Wrap the existing test-only measurement core in a Linux supervisor and JDK-only
worker. Use a fresh private run tree, one overall deadline, bounded logs/output
and a fixed worker heap. Compile from clean outputs, fingerprint source/classpath
inputs before and after execution, and remove ambient option injection. Preserve
the original forced acknowledgement and directory-name synchronization contracts.

Publish a bounded versioned worker envelope, then admit it only after the process
group settles, exit status agrees and every binding/count/summary/path/storage
check passes. Preserve failed, unsupported and incomplete evidence without
converting it into a successful result. The
[supervised workload guide](../development/supervised-workloads.md) owns commands,
schemas, budgets, artifact handling and observation scope.

Observe process I/O outside operation clocks around each complete iteration,
including validation and maintenance. Retain all seven raw counters, exact deltas
or explicit unavailability, separate warmups and keep cancellation distinct.
Reobserve named files, journal identity and Linux attributed allocated blocks after
all worker owners/processes settle. Name ratio denominators explicitly.

**Dimension extension, November 6, 2026:** expose entity, commit, read and interval
width choices under existing corpus limits and conservative aggregate-action
admission before scratch mutation. Bind the complete dimension object in a
version-two request and worker envelope, including failed/unsupported outcomes.
Keep legacy eight-argument/version-one admission at its original defaults; preserve
the inner result/corpus version and default fingerprints. Derive operation,
validation and file inventories from requested inputs instead of fixed smoke sizes.

## Consequences and limits

The core remains usable without a supervisor, cloud SDK or native binding. This
developer harness adds no production dependency, format change or distributed
acknowledgement. A successful CI smoke is observed correctness/execution evidence,
not a controlled comparison or production deployment qualification.

Process counters are non-atomic runtime-wide accounting, not device/durability
receipts. Attributed blocks do not establish exclusive physical or reclaimable
space, especially with shared extents. Source/result consistency checks are not
attestation against a hostile checkout or machine.

Cleanup covers cooperating children in the dedicated group; it is not arbitrary
descendant containment or a security sandbox. Keep actual elapsed time, unknown
hardware/cache facts, parent-operation limits and remaining controlled-evaluation
gaps explicit. Separate [ordinary](../development/linux-observations.md) and
[attributed](../development/linux-attributed-observations.md) evaluations now retain
durable baseline data. Production-device failure evidence remains separate.
