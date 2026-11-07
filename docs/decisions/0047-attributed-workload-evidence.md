---
title: Attributed workload evidence decision
description: Bind separately versioned instrumented workers to source and native admission while independently decoding original history and recomputing raw file-work ratios in live and saved evidence.
---

# ADR 0047: Attributed workload evidence

- **Date:** November 7, 2026
- **Status:** Serialized attribution and reporting implemented; bounded Linux observations available
- **Scope:** Worker, Linux supervisor and saved-reader evidence contracts

## Context

Typed native counters need source-bound export and independent recomputation before
they can support operation-attributed reports. Existing latency observations must
retain their original schemas and instrumentation scope. Producer-declared
lengths alone would not independently establish logical byte denominators.

## Decision

Select attributed execution explicitly with schema three and a fixed instrumented
mode on request, worker and receipt. Preserve ordinary schema-one/two contracts.
Reuse the existing supervisor's source/class, native file/process and bounded
cleanup checks; keep process telemetry outside operation scopes.

Export the exact original journal header and non-retry frames alongside raw scope
outcomes. Independently decode framing, identities and CRC32C, recompute the full
history SHA-256/length and derive seed/mixed/original-history denominators. Reject
missing, unknown, failed, overflowing or incoherent counters before ratios.
Retain exact integer numerator/denominator pairs with explicit zero-denominator
N/A and separate auxiliary/excluded/uncaptured coverage.

Recompute the same attribution in saved reporting without reopening original
paths. Bind the derived report to exact worker bytes, source, request, mode and
recorded producer facts. This checks consistency, not producer authentication or
a new observation of the original machine. Preserve failed raw evidence without
promoting it into a successful result.

The [evidence guide](../development/attributed-workload-evidence.md) owns commands,
schemas, coverage, metric definitions and bounds.

## Consequences

No production engine configuration, data format, dependency or acknowledgement
changes. Original latency data remain immutable. Literal/parser/failure and
maximum-input checks complement actual Linux supervisor/readback smoke. The
separate [Linux evaluation](../development/linux-attributed-observations.md)
now supplies bounded profile/dimension contrasts and durable raw data for choosing
a next experiment. It establishes no optimization gain, controlled-device result
or production qualification.
