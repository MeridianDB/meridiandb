---
title: Recorded recovery worker admission decision
description: Bind complete recorded recovery comparison results to independent requests, image facts and scoped counter expectations.
---

# ADR 0055: Recorded recovery worker admission

- **Date:** November 20, 2026
- **Status:** Recorded worker admission implemented
- **Scope:** Developer comparison evidence

## Context

The Java worker exports complete comparison cohorts. Image structure and seeded
corpus reconstruction validate its inputs. A later reader also needs to check
that every recorded operation and observation belongs to the requested run
before using timing or native counter evidence.

## Decision

Use a dedicated bounded version-one decoder with image-specific string limits.
Match the complete request, observed PID and caller-supplied runtime facts.
Recompute the ordered observation matrix, expected paths and the initial and
closed byte identities from independent image and corpus facts. Derive logical
denominators per observation. Check the exact operations, mode coverage, raw
counter arithmetic and deterministic storage work while retaining variable
transfer counts and separate warmup and measured order.

The pure consumer returns the complete admitted result and a receipt of the
runtime fields compared. The Linux smoke supplies observed placement and PID
expectations. Its opt-in mode keeps the existing image and corpus receipts usable.
See the [recorded evidence guide](../development/recovery-evidence.md) for the API
and compatibility command.

## Consequences

The admitted result records consistency with the caller's expectations. Run
ownership, source and class provenance, native file inspection and offline saved
report admission still belong to the supervisor and report consumers. Performance
and memory conclusions require their own measurements.
