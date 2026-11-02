---
title: Deterministic workload corpus decision
description: Separate bounded reproducible temporal inputs and independently prepared outcomes from engine timing, physical I/O accounting and Linux baseline evaluation.
---

# ADR 0041: Deterministic workload corpus

- **Date:** November 2, 2026
- **Status:** Test-only corpus implemented; measurement runner and evaluation pending
- **Scope:** Local workload inputs, independent answers and reproducible identity

## Context

The canonical temporal histories establish exact behavior but are too small to
serve as performance datasets. A larger generated history can also mislead:
configured correction probabilities do not establish how often visible state
changed, and retrying old request IDs can measure lookup instead of new commits.
Generating answers during a timed operation would mix reference-model work into
the engine measurement.

## Decision

Prepare a bounded versioned specification and immutable ordered action stream in
test support. Provide small append, correction, retraction and hot-entity defaults,
separate setup from mixed actions and store each read's explicit available system
cutoff. Preserve original transaction identity, new no-op commits and exact retries
as distinct cases. Keep canonical fixtures and production temporal semantics intact.

Use separate deterministic random streams for writes, reads and retries. Record
the full specification and effective 48-bit random states. Bind the schedule with
an explicit binary content fingerprint and bind original write history separately
to its exact journal header/frames. Preserve UTF-16 identities, numeric extremes,
interval representation and typed queries without locale or string-rendering
dependencies. The [corpus guide](../development/workload-corpus.md) owns exact
parameters, bounds, fingerprints and reproduction commands.

Prepare all expected outcomes through the independent reference model before
execution. Validate declared sequences, retry results and cutoff availability
before returning a prepared result. Record actual effect, overlap, entity-touch,
query and hit/miss counts with explicit denominators. Validate seed stage/intent
agreement and the initial seed-submission boundary for caller-constructed corpora
as well. Effect-intent labels remain metadata, not generator provenance or proof
of a resolved correction/removal effect.

Check generated cases against a separate forward dense representation, actual
memory execution and real local writable/reopen execution. Include literal
expected answers and fingerprint vectors rather than relying only on repeated
execution of the generator. Extend the complete Linux qualification inventory
from a successful full test run. Keep all workload helpers outside production
and the attached contract-support classifier; add no dependency or clock.

## Consequences and limits

V1 deliberately admits small finite windows and one-operation requests. Dimension,
aggregate action/encoding, query-fanout and reference-work limits keep preparation
bounded. The work estimate is an admission policy rather than a hard memory/time
guarantee. Larger histories, unbounded windows, multi-operation requests or a new
generator/encoding need an explicit compatibility and resource-bound decision.

The corpus provides inputs and correctness evidence, not a benchmark result.
Generation and reference preparation belong outside later timed loops. Repeated
measurements need fresh stores, equivalent acknowledgement, explicit warmup/cache
conditions and scoped timing/check overhead. Logical encoded bytes cannot stand
in for allocated blocks, device I/O or amplification.

Linux measurement and production qualification remain distinct. Hosted CI verifies
correctness on its observed filesystem and does not qualify a device, mount policy
or performance comparison. No replacement file format, native runtime, S3 work or
replication protocol is selected by this decision.
