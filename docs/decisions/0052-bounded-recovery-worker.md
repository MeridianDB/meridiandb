---
title: Bounded recovery worker decision
description: Export explicit recovery modes through a separate bounded developer protocol while preserving raw histories, fixture images, failure evidence and source-verification boundaries.
---

# ADR 0052: Bounded recovery comparison worker

- **Date:** November 14, 2026
- **Status:** Test-only worker implemented; supervision and independent saved admission pending
- **Scope:** Recovery comparison producer; no production behavior or format change

## Context

The [comparison core](0050-recovery-comparison-core.md) and
[uncaptured controls](0051-uninstrumented-recovery-controls.md) produce typed Java
observations. Cross-process experiments need explicit request/result bindings and
raw inputs that a separate reader can check. Existing workload schemas and recorded
datasets already have consumers; changing them would confuse distinct experiments.

## Decision

Add a separate JDK-only test-support `RecoveryComparisonWorker` with protocol
`MERIDIANDB_RECOVERY_COMPARISON`, envelope version one and explicit mode identifiers.
Admit bounded arguments, dimensions and conservative whole-matrix work before
path inspection. Use a canonical real scratch directory and fresh sibling output.
Reuse the existing bounded JSON writer and create-only publication procedure;
preserve partial files and treat export failure as failure.

Export the exact request, original history header/frames, one initial nonjournal
image set per shape, the actual continuation frame and complete ordered raw
observations. Map optional checkpoint sequences to number/null and native coverage
explicitly: record-component reflection alone would lose those distinctions.
Before success, bind the result to the request, actual runtime, independent oracle,
matrix order and owned directory layout. Reobserve retained initial images and
closed journals after all database owners close. Validate the continuation's fresh
identity, sequence and state-changing put. Keep raw clocks and cohorts separate.
The [worker guide](../development/recovery-worker.md) defines invocation and bounds.

## Consequences

Worker fields are producer declarations. A supplied source SHA, PID, file hash or
exit zero does not verify source/classes, process identity or independently admitted
evidence. Linux supervision, independent original-format decoding and saved-report
readback remain separate requirements. This producer adds no deadline, process
containment, controlled-cache condition, peak/retained-memory result or performance
adoption decision. Export validation and raw reads remain outside operation clocks.

Failures carry bounded diagnostics and a retained run root when it is known.
Incomplete results are never exported as successful observations. Errors can
propagate without a final envelope; later consumers must require both successful
process completion and complete admitted output. Existing worker protocols,
production APIs, persistence bytes and default recovery behavior remain unchanged.
Keep this worker and all comparison support outside both shipped archives.
