---
title: Temporal storage research
description: MeridianDB explores an embeddable Java temporal storage engine. Read the concepts, architecture, research plan and project decisions.
---

# MeridianDB

Explore data through two dimensions of time.

MeridianDB is an experimental storage engine for preserving when facts apply and
when the database learned about them. Java and in-process JVM embedding are the
working direction, with a Java 25/Maven build foundation now available.

[Understand temporal data](concepts/temporal-data.md) ·
[Explore the architecture](architecture/overview.md) ·
[Contribute](contributing/index.md)

## Project status

MeridianDB is at the project-foundation stage. This website documents the research
direction, the memory-only engine and the local database's recovery and forced-write
protocol, with [process crash/retry conformance](development/durability-conformance.md).
A published library and a stable API remain planned.

The [initial temporal contract](architecture/temporal-contract.md) now specifies
corrections, retractions, committed views and retries, with exact workload fixtures.
The [Java/Maven build](development/build.md) includes immutable temporal types and
a [memory-only embedded engine](development/embedded-engine.md) with a runnable
Java example. Both engine and [test reference](development/reference-model.md)
pass all 84 original canonical expectations and independently checked seeded histories.
[Typed temporal queries](development/temporal-queries.md) add numeric predicates,
ordered entity selection and filtered intervals, verified with a separate exact
query corpus and seeded memory/writable-reopen checks.
Focused tests cover serialized writing, atomic visibility and read-view lifetimes.
A [pure journal codec](architecture/journal-format.md) now validates versioned
transaction frames. [Comparable-system research](architecture/storage-research.md)
guides storage choices. The separate [local journal](development/local-journal.md)
now adds exclusive writer access, append/force, bounded replay and explicit tail
repair. The [immutable history codec](architecture/immutable-segment-format.md)
preserves exact transaction ranges and verifies complete object identifiers.
The [local segment adapter](development/local-segments.md) adds create-only file
writes and bounded reopen with immutable heap-reader lifetimes. The
[manifest codec and verifier](architecture/checkpoint-manifest-format.md) check
referenced history against an exact reconstructed journal prefix. The
[local checkpoint store](development/local-checkpoints.md) verifies the actual
owned journal prefix, publishes immutable generations and reopens complete history
under explicit filesystem requirements. [Local database recovery](development/local-database.md)
restores historical queries and request lookup from that checkpoint plus the journal
tail. Writable mode forces each new journal record before publishing the complete
query state and acknowledging success. Process-exit and seeded writable-reopen
checks preserve exact history and retry identity. The
[pure history compactor](development/history-compaction.md) now repacks complete
checkpoint history under aggregate/per-object limits, retaining all cutoffs and
request payloads. Local compaction publication and physical reclamation remain
planned; layout performance experiments need their own evidence.
SQL, replication and integrations remain open design questions.

## Why keep both times?

A correction received in March may change the salary effective in February.
Preserving both times lets us distinguish the February salary as known then from
the February salary as understood after the correction. The same distinction
matters for inventory, configuration history, audit records and financial events.

[Work through the example](concepts/temporal-data.md).

## Correctness first

Develop explicit semantics and an independent reference model before optimizing
storage, indexes or compaction. The [architecture overview](architecture/overview.md)
sets out the research milestones and the persistence questions they must resolve.

## Decisions in the open

Record assumptions, alternatives and evidence. Java is the working default;
implementation choices remain open to useful experiments. Read the
[decision records](decisions/index.md) for the reasoning and conditions for
revisiting those choices.
