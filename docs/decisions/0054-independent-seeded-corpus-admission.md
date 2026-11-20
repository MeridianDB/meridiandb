---
title: Independent seeded corpus admission decision
description: Bind recorded recovery history, binary schedule fingerprints and realized workload summaries to independently reconstructed version-one seeded inputs.
---

# ADR 0054: Independent seeded corpus admission

- **Date:** November 15, 2026
- **Status:** Pure input reconstruction implemented
- **Scope:** Developer evidence inputs; no production or persistence-format change

## Context

[Independent image checks](0053-independent-recovery-image-admission.md) establish
recorded binary structure and graph identity. A consistently changed history can
still have valid checksums, ranges and references. Recovery evidence also needs
to establish that original bytes and workload declarations match the expected
seeded input, including retries, scheduled queries and realized summary counts.

## Decision

Reconstruct the bounded v1 corpus independently in standard-library Python. Use
Java-compatible random streams and canonical binary encodings to derive original
journal images and schedule/history fingerprints. Compute realized summary facts
through an independent history-search oracle with finite-window comparisons,
without retaining every temporal snapshot or executing producer code.

Validate exact typed source and metadata against a caller-provided expected spec.
Preflight work and byte limits, preserve input ownership and expose fresh derived
facts only after complete agreement. The [seeded input guide](../development/recovery-corpus.md)
owns the API, admitted scope and opt-in Linux compatibility command.

## Consequences

Literal fingerprints and independent semantic/adversarial checks complement all
profile/extreme-seed and maximum-history Java compatibility. Full seed identity
remains distinct from effective 48-bit random state. Configured intent remains
distinct from realized changes, presence and query outcomes.

Input reconstruction does not verify a complete outer request, result matrix,
native counter scope, measured engine execution, live files or historical source
provenance. [Recorded worker admission](0055-recorded-recovery-worker-admission.md)
adds complete recorded result checks. Live provenance and saved-report
admission, memory diagnostics and evaluated recovery adoption remain separate. Existing worker
wire schemas, durable datasets, production APIs, formats and recovery defaults
remain unchanged.
