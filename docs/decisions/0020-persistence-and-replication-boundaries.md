---
title: Persistence and replication boundaries decision
description: Keep MeridianDB's bitemporal engine independently usable with local persistence, narrow optional storage integrations and a separately specified replication protocol.
---

# ADR 0020: Persistence and replication boundaries

- **Date:** October 31, 2026
- **Status:** Architecture direction adopted; additional adapters and replication planned
- **Scope:** Embedded library positioning and persistence responsibilities

## Context

The local engine now combines an ordered forced journal, immutable history
segments, verified checkpoints and protected physical reclamation. An object
adapter needs different publication and coordination mechanisms. Treating every
persistence operation as interchangeable would hide those differences and make
historical correctness depend on accidental backend behavior.

Time-based search, storage snapshots and native bitemporal history solve different
problems. MeridianDB's core contract preserves valid intervals and committed
knowledge, including retroactive corrections and original retry identity. Its
current knowledge coordinate is commit order, with no calendar-time mapping.

## Decision

Keep MeridianDB an embeddable Java bitemporal database with a complete local
persistence implementation. Local use requires neither a cloud SDK nor a service
framework. Reuse maintained provider libraries for optional integrations rather
than implementing service clients from scratch.

Separate commit authority and ordered journal history from immutable-object
access, checkpoint selection, derived indexes and reader-safe reclamation. Add
narrow interfaces when concrete adapters need them. Preserve the local writer's
force-before-query-publication acknowledgement and explicit uncertain outcomes.
The [persistence boundary specification](../architecture/persistence-boundaries.md)
owns the detailed responsibilities and required extension evidence.

Develop optional object storage alongside the existing local journal first.
Verify immutable dependencies and their actual journal-prefix binding before
backend-specific checkpoint selection. Require exact recovery, capability and
failure tests. Conditional metadata updates do not authorize a second writer or
turn local durability into replicated durability. Keep remote reclamation gated
by a protocol covering every supported reader, publisher and retained root.

Treat replication as a separate future protocol. Start its design with one writer
and read-only followers preserving original transaction identity and order.
Specify checkpoint-plus-tail bootstrap, progress and acknowledgement modes before
network implementation. Automatic failover, quorum commitment and multiwriter
behavior require additional coordination decisions. Evaluate maintained consensus
implementations before adopting distributed leadership.

## Evidence and inference

Primary sources checked October 31, 2026:

- XTDB exposes transaction logs and object storage as separate pluggable
  components and supports local configurations. This supports separating those
  responsibilities; it does not select XTDB's interfaces or semantics for
  MeridianDB. [XTDB configuration](https://docs.xtdb.com/ops/config.html).
- Amazon S3 documents strong object consistency and conditional creation/update.
  Its consistency model does not provide atomic updates across keys. Depend on
  tested operations of a chosen provider, rather than assuming identical
  semantics for every compatible endpoint.
  [S3 consistency](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html#ConsistencyModel),
  [conditional writes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html).
- Raft addresses leader election, replicated-log agreement and safety together.
  This motivates a distinct replication protocol rather than a storage-provider
  switch. No consensus library is selected.
  [Raft paper](https://raft.github.io/raft.pdf).

The boundaries, single-local-writer remote-storage mode and staged replication
direction are MeridianDB design choices. They establish no performance result or
new implemented capability.

## Alternatives and consequences

A mandatory external log or database could delegate parts of durability and
coordination, but would prevent standalone local embedding. A single broad backend
interface would simplify configuration while obscuring different ownership,
publication and failure guarantees. A full distributed engine now would require
network lifecycle, membership and failover contracts before they are needed by
the local prototype. Preserve explicit extension points without adding that
implementation burden to local use.

Optional adapter packaging must keep cloud integrations out of the local runtime
requirements. Decide module/dependency structure when the first provider adapter
is implemented, with fresh Maven version and compatibility research. Existing
formats, temporal fixtures, local protocols and artifact layout are unchanged by
this decision. Revisit with concrete integration needs, measured limitations or a
separately specified distributed-consistency requirement.
