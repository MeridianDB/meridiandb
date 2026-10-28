---
title: Temporal storage research
description: MeridianDB explores an embeddable Java temporal storage engine. Read the concepts, architecture, research plan and project decisions.
---

# MeridianDB

Explore data through two dimensions of time.

MeridianDB is an experimental storage engine for preserving when facts apply and
when the database learned about them. Java and in-process JVM embedding are the
initial working direction, with Maven selected for the future Java build.

[Understand temporal data](concepts/temporal-data.md) ·
[Explore the architecture](architecture/overview.md) ·
[Contribute](contributing/index.md)

## Project status

MeridianDB is at the project-foundation stage. This website documents the research
direction; there is no runnable database, published library or stable API yet.

The [initial temporal contract](architecture/temporal-contract.md) now specifies
corrections, retractions, committed views and retries, with exact workload fixtures.
The next foundation is the Java/Maven build, followed by a simple reference model.
Persistent storage, recovery and layout experiments follow. SQL, replication and
integrations remain open design questions.

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
