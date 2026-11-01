---
title: "ADR 0033: Writes after pinned checkpoint recovery"
description: Synchronize actual owned local history after complete pinned reconstruction, then reuse forced commit publication and original retry identity.
---

# ADR 0033: Writes after pinned checkpoint recovery

Date: November 1, 2026. Status: implemented, experimental JDK-only core mode;
managed S3 writable admission and complete remote writable conformance remain planned.

## Context and decision

A verified object prefix and reconstructed temporal engine do not by themselves
authorize durable retry acknowledgements or new writes. Surviving journal bytes
must pass the existing writable startup synchronization under actual ownership.
Read-only pinned recovery deliberately supplies no such promise.

Add `LocalDatabase.openWritableFromObjectCheckpoint` alongside the
[read-only pinned mode](0031-pinned-checkpoint-engine-recovery.md). Share concrete
argument admission, store/journal acquisition, frozen-H prefix/tail reconstruction
and failure cleanup. Enable the existing private write-source path only for the
explicit writable factory. After full reconstruction, synchronize the actual
journal and its directory before exposing any database or acknowledging a retry.
Read-only routes continue with no writer and no new data-force acknowledgement.

The private object source delegates append/force and startup synchronization to
existing concrete owners. New writes reuse [ADR 0014](0014-durable-commit-publication.md)'s
private preparation, encoding preflight, append/force, atomic publication and
uncertain-outcome behavior. Exact retries retain their original positions without
new append/force after successful startup. Caller object adapters remain borrowed;
there are no public restore/backend callbacks or lazy object reads after startup.
The [write guide](../development/object-checkpoint-writes.md) owns API, lifetime,
coverage, force assumptions and verification details.

## Authority and scope

The supplied pin remains explicit authority. It does not select a provider's current
head, validate managed expected/pending state or publish new checkpoints. All local
history is retained, and the complete matching journal is required. Minimum coverage
can reject known missing history but cannot infer unknown clean tail loss.

Ordinary local selection and read-only factories keep their contracts. Optional
`S3Database.open` remains read-only; its managed scope/token/metadata admission must
be composed separately with writable startup. Full remote canonical/seeded and
publication-interruption conformance remains required before qualifying that mode.
No remote durability, lease, replication or reclamation claim follows from core writes.

## Evidence and alternatives

This decision applies the existing local durability contract to a different verified
reconstruction source; it changes no journal or object wire format, provider protocol
or external database claim. It reuses the dated primary evidence and force limitations
recorded in ADRs 0014, 0031 and [0032](0032-managed-s3-read-recovery.md). The latter's
AWS sources were checked November 1 at 13:35:18 UTC /08:35:18 EST; they establish no
local transaction force guarantee. These project choices are checked through actual
native owners, unchanged canonical/query fixtures, independent seeded reference/dense
comparisons and bounded process interruption.

Acknowledging exact retries before startup force would treat readable surviving bytes
as durable evidence. Reimplementing commit publication per checkpoint source would
split the acknowledgement contract. Accepting a borrowed journal or external History
as completed startup authority would detach replay from ownership. Reusing the owned
factory and existing private write protocol avoids those differences while retaining
full replay/history/heap costs and supported placement assumptions.
