---
title: Attributed local lifecycle decision
description: Share the checked local lifecycle through explicit test-only operation and auxiliary capture scopes while keeping instrumented results and original byte denominators separate from latency baselines.
---

# ADR 0046: Attributed local lifecycle

- **Date:** November 7, 2026
- **Status:** In-process attribution and serialized reporting implemented; bounded Linux observations available
- **Scope:** Test-only local workload execution and typed raw evidence

## Context

Native counters alone have no operation identity. Capturing an entire lifecycle
would combine commit/recovery work with validation, maintenance acquisition and
external hashing. Duplicating the engine lifecycle in a new runner would make
correctness and ownership behavior easier to diverge.

## Decision

Share the existing checked local lifecycle through optional test-only scope hooks.
Ordinary execution activates no capture and retains its existing result schema.
An explicit attributed entry point returns a separate, visibly instrumented result
with ordered operation and auxiliary scopes, raw counter outcomes and original
logical-history denominators. Capture setup/export stays outside service clocks.

Keep engine-required integrity reads within their operation. Attribute lazy
validation, maintenance owner/prefix/reader acquisition and teardown separately.
Mark raw-journal hashing and metadata inventory as excluded coverage, preserving
the distinction between zero adapter bytes and unavailable filesystem coverage.

Admit the complete bounded trace before namespace creation. Preserve incomplete
snapshots, missing observations and primary/suppressed failures. Observation
failure after acquisition must release the newly returned owner; unavailable
teardown capture cannot prevent an existing owner's close.

The [attributed workload guide](../development/attributed-local-workloads.md)
owns exact scope, bounds, denominator meanings and verification commands.

## Consequences

This adds no production activation API, dependency, data format, acknowledgement
change or reclamation. Typed in-process evidence is useful for independent literal
native tests. The separate [serialized evidence path](../development/attributed-workload-evidence.md)
now adds source-bound supervision and checked ratios. The separate
[Linux evaluation](../development/linux-attributed-observations.md) retains
bounded amplification findings and durable raw evidence. Controlled-device and
production qualification remain separate requirements.
