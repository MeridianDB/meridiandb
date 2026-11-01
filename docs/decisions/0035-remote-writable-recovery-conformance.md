---
title: "ADR 0035: Remote writable recovery conformance"
description: Reuse authoritative temporal oracles and actual SDK publication to verify managed writable recovery across historical, interruption and coverage boundaries.
---

# ADR 0035: Remote writable recovery conformance

Date: November 1, 2026. Status: implemented, experimental verification of the
supported managed writable mode. Live-provider, device-power-loss, replication
and remote-reclamation qualification remain separate.

## Decision

Exercise [managed writable recovery](0034-managed-s3-writable-recovery.md) through
the unchanged canonical/query corpus and persisted-seed reference/dense histories.
Use actual SDK immutable uploads, verified preparation and conditional publication,
then reopen the managed factory for writes, retries and reads. Compare every
historical cutoff with independent oracles and ordinary local recovery.

Extend the existing test-only classifier narrowly with unchanged `ContractFixtures`
and `ReferenceModel` families and authoritative contract/seed resources. Keep
Jackson test-only in the adapter. Exclude actual test classes and fault bridges
from that classifier and all test support from production archives. Sharing the
authoritative oracles avoids drifting copies; adding a public production backend
or replay API solely for conformance would broaden the implementation contract.

Cross late and interrupted publication with pending refusal, explicit reconciliation,
managed startup and subsequent original/new transaction acknowledgement. Run actual
publisher process exits at completed remote write, verified candidate and synchronized
local result boundaries. Verify clean-tail loss, missing journals, online failure
and historical head observations without fallback or invented history.

The [conformance guide](../development/s3-recovery-conformance.md) owns exact case
counts, commands, coverage matrix, fixture bounds and limitations. Reuse existing
focused admission, ownership, startup/write/process and concurrency evidence;
the new corpus does not substitute for those tests.

## Evidence and limits

Forty corpus invocations preserve all 174 literal steps per checkpoint shape and
16 persisted seeds through three 32-commit workflows. Ten integration invocations
cross publication with actual engine continuation and coverage failures. HTTP
fixture budgets are explicit and bounded; native owners and child lifetimes are
real. No production protocol, wire format, SDK setting or temporal rule changes.

This applies existing project contracts, reusing the dated primary evidence in
ADRs 0032–0034. The existing AWS source check remains November 1 at 13:35:18 UTC /
08:35:18 EST. No new external-provider guarantee is asserted. Maven metadata and
official compatibility are freshly checked for the test-packaging change.

A full matching local journal remains required. Later local acknowledgements gain
no remote coverage until separately published; default minimum zero cannot detect
unknown clean suffix loss. Process exits do not simulate device power loss, and
a loopback service does not certify a live provider. Remote reader admission/
reclamation, general backend qualification, replication and performance measurement
remain separate work.
