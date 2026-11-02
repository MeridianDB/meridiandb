---
title: Linux local storage priority decision
description: Prioritize Linux local persistence, workload-specific temporal file layouts and measured I/O improvements while retaining optional integrations as deferred experiments.
---

# ADR 0039: Linux local storage priority

- **Date:** November 2, 2026
- **Status:** Adopted direction; qualification and optimized layouts planned
- **Scope:** Platform support, storage sequencing and performance evidence

## Context

MeridianDB has a Java local journal, immutable history segments, checkpoint
publication, temporal recovery and protected reclamation. Its optional S3
integration has experimental publication and recovery paths; coordinated remote
reclamation is incomplete. Neither path has production qualification or measured
throughput evidence. The current history segments retain journal frames and the
engine reconstructs full history into heap snapshots.

Completing another backend is unnecessary before measuring and improving the
local engine. Windows native filesystem parity would also divert effort from
qualifying a concrete Linux storage environment.

## Decision

Make Linux local filesystems the production deployment target. Keep Java 25 and
Maven, using the existing Java I/O implementation as the correctness and
measurement baseline. Windows receives basic build, logical and local smoke
coverage where capabilities permit; equivalent native features and durable
production operation are outside the current target. Existing broader CI
coverage is useful regression evidence, not a platform support promise.

Retain S3 source, formats, tests and documented limits. Defer its incomplete
coordination/reclamation work and further provider expansion. Keep S3, Kafka-topic
storage, other persistence adapters and ingestion connectors outside the local
qualification, benchmark and experimental release dependency chain. Kafka is not
a required journal or service.

Qualify explicit Linux filesystem/kernel/JDK/device profiles and establish local
workload measurements first. Then evaluate a purpose-built, versioned temporal
layout and narrow Linux I/O optimizations against that baseline. Preserve exact
transactions, retry identity, all supported historical cutoffs and the existing
force-before-publication acknowledgement. Production use remains a target until
the relevant qualification evidence and operational limits exist.

The [Linux storage design](../architecture/linux-storage.md) owns the candidate
layouts, primary-source comparison and experiment gates. The existing v1 formats
remain implemented; this decision selects neither replacement bytes nor a native
library. New block-level integrity, lazy reads, encoding or materialized indexes
require an explicit version/compatibility decision and independent conformance.

Keep future node replication separate from local copy/reflink acceleration. Each
node can own local durable history, while the replication protocol must establish
ordering, catch-up, progress, acknowledgement and any failover guarantees.

## Alternatives and consequences

Parquet remains an important comparison and a possible analytical export format.
Its column chunks and optional page indexes provide useful layout techniques;
it does not by itself define MeridianDB's correction, transaction or retry rules.
A custom format may improve selected temporal workloads but also adds parser,
compatibility, recovery and maintenance costs. Retaining an established format is
a valid experiment result.

Native calls may reduce copying, allocation stalls or cache pollution, but their
benefit depends on workload and deployment. Evaluate Java facilities first and
introduce a narrow Linux native binding only with measured benefit, explicit
capability/error handling and crash/cleanup verification. Completion of an I/O or
copy operation does not replace the durability and checkpoint protocol.

This decision changes the sequencing and platform target in [ADR 0020](0020-persistence-and-replication-boundaries.md).
Its separation of commit authority, immutable objects, checkpoint selection,
indexes and replication remains applicable. Earlier S3 decisions retain their
technical contracts and experimental status; they create no requirement to finish
remote storage before improving local operation.
