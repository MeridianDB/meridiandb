---
title: Uninstrumented recovery controls decision
description: Add explicit recovery controls without active caller-thread native capture, preserving comparable histories, absence-aware coverage, ownership and honest timing scopes.
---

# ADR 0051: Uninstrumented recovery controls

- **Date:** November 13, 2026
- **Status:** Explicit test-only controls implemented; supervision and evaluation pending
- **Scope:** Recovery comparison modes and a read-only internal capture guard

## Context

The [comparison core](0050-recovery-comparison-core.md) captures native work around
each operation. Avoiding segment reads can also avoid capture-hook work; captured
timings alone cannot establish a production latency improvement. Equivalent
controls need native capture disabled while preserving the same original images,
oracle, acknowledgement, operation order and cleanup.

Skipping explicit activation is insufficient if the caller already owns a capture.
The existing internal registry owns caller identity; a second registry, reflection
or temporary capture would weaken admission or add unnecessary allocations.

## Decision

Keep existing comparison overloads instrumented. Add an explicit measurement-mode
overload with mode before limits, preserving nullable probe calls. Control mode
uses `RECOVERY_UNINSTRUMENTED`; the existing captured identifier remains unchanged.
Represent absent counters with `null` and derived `NOT_CAPTURED` coverage. Captured
complete snapshots have `ADAPTERS_ONLY` coverage. Reject unknown identifiers and
mixed or mismatched mode/coverage; absent counters prove no zero-I/O claim.

Add the smallest package-private read-only `LocalIo` guard over its existing
caller lookup, exposed through test support. It neither creates nor changes a
capture. Controls reject an active caller capture before filesystem effects and
around operations, preserving caller ownership and other threads. A call-boundary
check also catches capture activation by an injected pre-call probe. Keep returned
owners within the shared cleanup envelope if later checks or instrumentation fail.
The [comparison guide](../development/recovery-comparison.md) owns API and scopes.

## Consequences

The internal guard adds no capture activation API or change to transfer/force
paths, storage bytes, startup barriers or acknowledgement. The shipped internal
class gains a package-private check; the mode runner remains test-only. Controls
create no native capture/export but retain inactive hooks, recorder and JVM probes.
The bounded call-boundary guard is included in service time. Neither mode promises
zero observation overhead, peak/retained memory, controlled cache or faster recovery.

Keep supervised source-bound protocol/readback, isolated memory diagnostics and
evaluated adoption separate. Preserve existing workload wire schemas and original
datasets; keep modes, warmups and recovery cohorts distinct.
