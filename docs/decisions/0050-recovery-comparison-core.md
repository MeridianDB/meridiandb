---
title: Recovery comparison core decision
description: Keep equivalent recovery experiments separate from existing workload protocols, with fresh image-bound cohorts, raw operation scopes and explicit measurement limits.
---

# ADR 0050: Recovery comparison core

- **Date:** November 7, 2026
- **Status:** Test-only synchronous core implemented; supervised evidence and evaluation pending
- **Scope:** Equivalent local recovery infrastructure; no production default change

## Context

[Explicit history recovery](0049-explicit-checkpoint-history-recovery.md) removes
one selected-segment pass and continuing reader admission while preserving full
journal verification and writable startup. Literal native counters establish this
work difference. They do not decide latency, allocation or memory tradeoffs.

The existing workload lifecycle reopens genesis and a full compacted checkpoint.
It does not supply equivalent journal-only/partial-checkpoint controls or explicit
recovery-strategy binding. Changing those historical schemas would complicate
comparison with the preserved ordinary and attributed observations.

## Decision

Add a separate test-only `LocalRecoveryComparison` core with fresh equivalent
journal-only, genesis, checkpoint-tail and compacted-tail fixtures. Cross ordinary
and history-reuse strategies with read-only/writable access. Prepare oracle and
images before filesystem effects, admit whole-matrix work, verify pairwise initial
file hashes and retain original corpus identities. Record actual warmup/measured
AB/BA order and raw operation samples without pooling distinct cohorts.

Keep open, original retry, forced continuation and continuation retry separate.
Preserve public factory verification/ownership/acknowledgement and independently
validate history, request identity and final journal bytes outside service clocks.
Close acquired owners on post-call instrumentation/export failure. Preserve failed
roots and expose no partial successful result. The
[comparison guide](../development/recovery-comparison.md) owns limits, scopes and
usage. Keep helpers out of shipped artifacts and existing runners/protocols intact.

## Consequences and remaining evidence

Fresh image-equivalent fixtures reduce semantic/layout differences, while actual
reader-admission costs remain part of the comparison. Warmups do not establish cold
cache; order balancing is exact only for even measured repetition counts. Fixed
shape/access ordering and JVM/cache effects remain recorded limitations.

Thread allocation and sampled used heap have different scopes. Sampled heap cannot
stand in for peak or retained memory. Separate bounded diagnostics must establish
those scopes, with diagnostic work outside latency runs. A supervised source-bound
protocol, independently recomputed saved reports, durable Linux data and an explicit
adoption/rejection decision remain required. This core supplies no performance gain
or production qualification claim.
