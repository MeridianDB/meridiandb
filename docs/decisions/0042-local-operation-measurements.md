---
title: Local operation measurements decision
description: Measure checked local operation calls on fresh stores while preserving acknowledgement, separating validation overhead and reporting scoped optional JVM telemetry.
---

# ADR 0042: Local operation measurements

- **Date:** November 2, 2026
- **Status:** In-process core implemented; supervised evidence and evaluation pending
- **Scope:** Developer timing, resource observations and local storage lifecycles

## Context

A deterministic workload is necessary for repeatable measurements but does not
define timing boundaries. Reusing stores turns request IDs into retries; computing
oracle answers during an operation mixes reference work with engine service.
Logical file lengths also cannot establish physical storage or I/O behavior.

## Decision

Add a bounded test-only in-process runner over the existing workload corpus.
Prepare independent answers and source segment images before operation timers.
Use distinct fresh namespaces for warmups and measured repetitions, preserving
all output and the existing forced local acknowledgement contract.
Establish new directory names through untimed child-to-parent directory forces
before timed database creation. Use the existing production capability/control
preflight and disclose that initial control-file creation is outside create latency.

Retain raw per-phase/kind wall samples and descriptive distributions. Time direct
calls separately from validation and record complete action-loop wall time with
its service, validation and remaining instrumentation/dispatch components. Keep
service rate distinct from loop throughput, offered-load and arrival latency.
Never subtract a guessed calibration or pool warmups into measured observations.

Measure current-platform-thread CPU/allocation only when already available and
enabled; record missing counters explicitly. Retain sampled process-heap gauges
without interpreting them as retained heap, RSS or exact peaks. Counter snapshots
have their own scope around the direct call. The
[measurement guide](../development/local-measurements.md) owns commands, parameters,
operation boundaries, resource interpretation and current gaps.

Exercise actual forced writes and pinned queries, journal writable reopen,
split checkpoint publication, pure compaction planning and native publication
with an old reader pinned, then checkpoint writable reopen. Verify original
request positions, every prepared query and exact old/new history outside clocks.
Respect existing native ownership, read raw journal bytes only after close and
retain obsolete generations/segments without reclamation.

## Consequences and limits

The core remains outside production and the attached classifier, introduces no
dependency or storage-format change, and supplies typed versioned observations.
Successful results require the whole checked lifecycle. Post-open instrumentation
failure must release acquired owners and preserve primary/cleanup evidence.

Admitted iterations/actions/samples/file inventory are bounded, but in-process
checks cannot interrupt a blocked native call or authenticate serialized output.
A separate supervised layer must add process cleanup/deadlines, source and Linux
environment provenance, versioned JSON validation and scoped allocated/process-I/O
accounting. Actual baseline evaluation remains a further outcome.

Cache state is uncontrolled; warmups exercise the JVM on fresh stores. No forced
GC or privileged cache operation is introduced. Named file lengths, logical
journal bytes, sampled heap and service rates do not become physical amplification,
production readiness or comparative performance claims. Preserve these gaps until
the required measurements and deployment evidence exist.
