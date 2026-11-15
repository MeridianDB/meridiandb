---
title: Independent recovery image admission decision
description: Decode bounded recovery comparison images independently before later complete worker and supervised evidence admission.
---

# ADR 0053: Independent recovery image admission

- **Date:** November 14, 2026
- **Status:** Structural image checks implemented; complete worker admission pending
- **Scope:** Developer evidence consumer; no production or persistence-format change

## Context

The [bounded worker](0052-bounded-recovery-worker.md) exports original history and
initial recovery fixtures. Producer success and internally consistent hashes do
not establish that those images encode the expected original transaction ranges,
lineage, selected prefix or retained compaction history. A separate consumer needs
to derive those facts before interpreting result metadata.

## Decision

Implement a pure standard-library Python consumer with independent CRC32C,
journal, segment and manifest decoders. Bind exact original frame bytes, nested
headers, source and compacted ranges, complete object IDs and reconstructed prefix
identities. Validate all four recorded inventories and the exact fresh continuation.
Preflight aggregate raw bytes and decode work before binary decoding, then enforce
caller graph limits before dependency decoding. Reused exact segments decode once;
every supplied copy still counts toward raw-image admission.

Return fresh derived facts only after complete structural success. Keep the
caller-provided expected request separate from exported declarations. The
[image guide](../development/recovery-images.md) defines inputs, limits, error
behavior and the fixed Linux Java compatibility smoke.

## Consequences

Independent literal builders, checksums, goldens and adversarial re-signed objects
exercise the expected-history binding without relying on Java round trips alone.
A Linux smoke checks the real worker's two modes and maximum history size while
retaining bounded raw evidence and known failure receipts.

Structural consistency does not certify the seed's generated values, schedule or
oracle. [Independent seeded reconstruction](0054-independent-seeded-corpus-admission.md)
adds those input bindings separately. Complete outer JSON/request/result/matrix/counter admission, actual
source/classes/process/filesystem bindings and independently checked saved reports
remain required. This consumer reads no native files and proves no prior force,
acknowledgement, device durability, memory advantage or timing gain. General
journal inputs exceed this intentionally narrow numeric worker corpus. Existing
wire schemas, datasets, shipped archives and default recovery behavior remain
unchanged.
