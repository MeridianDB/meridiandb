---
title: Recorded workload reports decision
description: Recompute saved workload evidence separately from live source, process and filesystem reobservation, preserving raw provenance, per-iteration distributions and measurement limits.
---

# ADR 0044: Recorded workload reports

- **Date:** November 7, 2026
- **Status:** Saved-record reporting implemented; baseline evaluation pending
- **Scope:** Offline consistency and presentation of supervised workload evidence

## Context

The workload supervisor checks source, processes and files on the actual Linux
placement. Those resources can disappear while its JSON remains useful. Requiring
the original files prevents later analysis; trusting a copied summary without
checking its raw samples permits mixed or contradictory observations.

## Decision

Extract pure recorded-worker validation from the live validator. Recompute exact
schema/request/runtime bindings, operation inventories, raw-sample summaries,
clock equations and declared file/process accounting using the recorded context.
The live wrapper continues to perform all native directory, device, inode,
allocation and journal-hash checks before original admission.

Add a bounded [saved-report command](../development/workload-reports.md) that binds
request/report/worker documents and exact worker bytes, checks the recorded
producer receipt and renders JSON or Markdown. Preserve source/class identities,
input hashes, unknown context and explicit unavailable counters. Keep each warmup
and measured iteration separate with its own distributions and denominators.

## Consequences

Saved evidence can be inspected after the original directories disappear. Its
consistency is separate from a new observation of files, processes or source;
recorded metadata does not authenticate the producer or qualify hardware. The
command executes no recorded code and leaves input files unchanged.

Raw results remain authoritative inputs for recomputation; generated reports are
bounded views of that evidence. Actual Linux evaluation, controlled comparisons
and production qualification remain separate outcomes. This change adds no engine
dependency or storage-format/acknowledgement change.
