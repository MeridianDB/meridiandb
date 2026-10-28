---
title: Java 25 and Maven build foundation
description: Explain MeridianDB's Java 25 baseline, stable Maven 3 toolchain, one-artifact structure, dependency research policy and contract-derived verification.
---

# ADR 0004: Java 25 and a reproducible Maven foundation

- **Date:** October 28, 2026
- **Status:** adopted for the initial experimental Java artifact
- **Scope:** MDB-0003; local build and verification, without library publication

## Context

Java and Maven were already selected. The temporal contract and history fixtures
are specified. A clean checkout now needs a small executable build before the
full reference model is implemented. The owner additionally requires research of
latest Maven dependency artifact versions on every Maven change.

## Decision

Use Java 25 as the initial JDK/compiler baseline and one Maven artifact,
`org.meridiandb:meridiandb-core:0.1.0-SNAPSHOT`. Keep packages under
`org.meridiandb` with standard source/test layouts. Publish no library or stable API
as part of this foundation.

Use the latest stable Maven 3 runtime and compatible stable plugin lines researched
for this change. Pin the runtime in Apache's generated `only-script` Wrapper with
its verified distribution SHA-256. Pin POM plugins and test dependencies, using
JUnit Jupiter and Surefire. The
[build guide](../development/build.md) owns current setup, the dated artifact
research and the update procedure. Do not rely on metadata's prerelease `latest`
entry or old locally installed Maven as the version decision.

Implement only one small temporal primitive in this step: half-open valid-time
membership and interval validation, including explicit unbounded endpoints and
finite coordinate limits. Its tests provide meaningful Java/Maven verification
without adding a JSON parser or prematurely implementing the history interpreter.
MDB-0004 reuses this primitive and completes temporal types and reference replay.

Run the same wrapper verification on Linux and Windows CI using read-only repository
permissions. Use Java 25 Temurin in CI, resolving current patches within that line;
record actual local/CI runtime evidence. Keep the Python documentation environment
independent. Pin archive timestamps for repeatable local packaging.

## Rationale and alternatives

**Java 25 rather than a feature release.** Oracle's
[support roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)
identifies 25 as the latest LTS line and 27 as the current non-LTS feature release
on the research date. An LTS baseline gives the new prototype a longer maintenance
window without preview features. Java 21 would support older embedding hosts, but
there is no confirmed older-runtime consumer requirement yet. Java 25 was also
available locally. This choice intentionally excludes Java 21 consumers; revisit
it against real embedding requirements before stabilizing the API.

**Stable Maven 3 rather than Maven 4 RC.** Apache recommends the stable Maven 3
release while the Maven 4 line remains preview. Several plugins publish 4.x beta
metadata alongside their stable Maven 3 lines. Research both availability and
compatibility, then pin exact stable choices. The initial dated versions and
primary evidence are in the build guide, avoiding another live version table here.

**One artifact and one test dependency.** There is one deliverable and no reason
for a multi-module build, service framework, dependency injection framework or
native engine dependency. A test-only JUnit aggregate provides the parameterized
boundary checks. The actual engine and reference model remain separate later work.

**Generated wrapper and platform checks.** Tracked launchers make a globally
installed Maven unnecessary. Verified checksums protect a fresh distribution
bootstrap. Linux and Windows jobs exercise both official launchers; local macOS
verification supplies another environment. Pinning does not replace reading new
release and compatibility information when the build changes.

## Consequences

Build setup and dependency research become durable agent instructions. Current
README, contributor, architecture and planning guidance now link real commands.
The local JAR contains an experimental primitive, not a runnable database. The
18 boundary invocations do not establish the full temporal contract, transaction
isolation, concurrency, recovery or durability.

Revisit the JDK baseline for a concrete consumer requirement; add modules, plugins
or dependencies only for a demonstrated need. A future library release needs
namespace, compatibility, packaging and publication work under its own authority.
