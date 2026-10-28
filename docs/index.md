---
description: MeridianDB explores an embeddable Java temporal storage engine. Read the concepts, architecture, research plan and project decisions.
layout: home
title: 'MeridianDB: An experimental temporal storage engine'
titleTemplate: false
hero:
  name: MeridianDB
  text: Explore data through two dimensions of time.
  tagline: An experimental storage engine for preserving when facts apply and when the database learned about them. Starting with Java and the JVM.
  actions:
    - theme: brand
      text: Understand temporal data
      link: /concepts/temporal-data
    - theme: alt
      text: Explore the architecture
      link: /architecture/overview
features:
  - title: Facts and knowledge
    details: Distinguish what applies at a given time from what was known at an earlier moment. Work through a concrete correction example.
    link: /concepts/temporal-data
    linkText: Read the concepts
  - title: Correctness first
    details: Develop explicit semantics and an independent reference model before optimizing storage, indexes or compaction.
    link: /architecture/overview
    linkText: Follow the research plan
  - title: Decisions in the open
    details: Record assumptions, alternatives and evidence. Java is the working default; implementation choices remain open to useful experiments.
    link: /decisions/
    linkText: Read the decisions
---

## Project status

MeridianDB is at the project-foundation stage. This website documents the research
direction; there is no runnable database, published library or stable API yet.
Java and in-process JVM embedding are the initial working direction, with Maven
selected for the future Java build.

The first implementation will establish a small temporal contract and a simple
reference model. Persistent storage, recovery and layout experiments follow that
foundation. SQL, replication and integrations remain open design questions.

## Why keep both times?

A correction received in March may change the salary effective in February.
Preserving both times lets us distinguish the February salary as known then from
the February salary as understood after the correction. The same distinction
matters for inventory, configuration history, audit records and financial events.

[Work through the example](/concepts/temporal-data) or
[help shape the project](/contributing/).
