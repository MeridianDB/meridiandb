---
title: Linux qualification evidence decision
description: Bind Linux storage conformance to fresh run-owned filesystem fixtures, exact test inventory and explicit environment evidence without claiming production or power-loss qualification.
---

# ADR 0040: Linux qualification evidence

- **Date:** November 2, 2026
- **Status:** Development harness implemented; production qualification remains open
- **Scope:** Local-core verification, test placement and evidence provenance

## Context

An ordinary CI pass does not identify which native tests ran or which filesystem
held their fixtures. A capability skip can leave a required durable path
unexercised. Recording metadata for one directory while tests use another would
misrepresent the result. Device/cache facts are also often hidden behind hosted
runners, containers and virtual block devices.

## Decision

Use a Linux-only development driver with a fresh private child under an explicit
trusted scratch parent. Observe that path's mount/device and Java filesystem
properties. An opt-in test-only JUnit factory and fork-startup temporary path bind
all current core fixtures to the same root. Reuse the complete local-core suite,
including ordinary-I/O/capability injections and actual process interruption,
rather than substituting a small happy-path demonstration.

Require the reviewed exact suite/invocation inventory, complete fresh reports,
matching run/root/runtime evidence and zero required skips or failures. Disable
test retries and reject retry-masked XML outcomes. Preserve failed evidence and
bound subprocess execution and process-group cleanup. The
[harness guide](../development/linux-storage-qualification.md) owns the schema,
commands, evidence handling and current scope.

Keep the native probe and temporary-directory factory in test sources. Core
production code, formats and acknowledgement remain unchanged; the attached
contract-support classifier excludes these helpers. The only build configuration
extension selects an explicit report directory while preserving its ordinary
default. Linux CI runs the harness and retains test evidence. Windows retains
basic compatibility coverage and its existing broader regression build.

## Consequences and limits

Label a passing result as observed correctness/capability evidence for that exact
placement, with `productionQualified: false`. Record kernel, architecture, JDK,
recognized mount settings and exposed queue facts, preserving unknown backing
device/controller/cache behavior. Mount options are filtered to avoid exposing
unrelated source paths; omitted options remain an explicit limitation.

A JVM halt leaves the kernel and device operating. File force, directory force,
locks, rename and process recovery tests do not establish a universal power-loss
guarantee. Production qualification still needs concrete deployment profiles,
resource/operational limits and failure evidence. No benchmark result, native
library, replacement format, S3 continuation or replication protocol is selected.

Run identifiers and fresh directories detect accidental evidence reuse within a
trusted workspace. They do not authenticate source, reports or a hostile machine.
Retained CI artifacts have a finite lifetime; durable summaries identify the
source and run rather than promising permanent downloadable artifacts.
