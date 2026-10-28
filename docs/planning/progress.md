---
title: Implementation progress history
description: Dated implementation receipts for MeridianDB, with actual checks, decisions and source-delivery evidence separate from the live queue.
---

# Implementation progress history

This is an append-only history of meaningful outcomes and checkpoints. Current
selection comes from the [live queue](queue.md). Follow the
[workflow](index.md#completion-and-git-evidence) for completion-commit and push
proof; a receipt does not invent its own containing commit SHA. Publication notes
in earlier receipts describe their then-current policy; current procedure comes
from the [website guide](../contributing/documentation.md#initial-hosting-and-publication).

## 2026-10-28 MDB-0001 — Planning workflow

**Outcome:** establish the Java/Maven implementation roadmap, an ordered expandable
queue, one-step agent procedure and reusable start prompt. Reconcile root agent
rules, README, contributor/architecture guidance and documentation navigation.

**Scope:** planning setup. No Java engine, Maven project or runtime artifact was
introduced. The next implementation outcome remains unstarted.

**Project evidence:** the owner reported first publication at
[meridiandb.org](https://meridiandb.org/); an independent HTTPS fetch returned 200
and the expected MeridianDB documentation title on October 28, 2026. Later source
pushes still require manual website publication.

**Validation:** strict Zensical builds and generated HTML checks pass for both
custom-domain and GitHub project-path variants: 12 content pages with canonical
URLs matching the sitemap, descriptions, assets and page/fragment links. The four
planning pages return HTTP 200 through a local static preview. Queue checks pass
for 14 unique IDs, valid state/reference/dependency/parent graphs, no cycles,
roadmap coverage and MDB-0002 as the single ready item. Agent, README, contributor
and architecture callers agree with the canonical workflow and push authority;
Git whitespace/diff checks pass. No engine tests were run because no Java build
exists yet. Remote delivery and CI are reported in the task handoff.

**Delivery evidence:** the completion commit carries `Step-ID: MDB-0001` and
`Step-Result: completed`. Use Git to resolve that SHA and its remote ancestry.
The final task handoff reports the actual push and CI state. A source push is not
a documentation deployment.

**Discovered work:** the queue's initial implementation packages are deliberately
high-level. Expand a package before coding when it cannot yield one cohesive,
reviewable result. JDK/version selection belongs to MDB-0003; no unverified engine
build instructions are presented as current commands.

**Next recommendation at this checkpoint:** MDB-0002 — define the temporal contract
and workloads. This is historical context; later sessions select from the live queue.

## 2026-10-28 MDB-0002 — Initial temporal contract

**Outcome:** adopt the [initial temporal contract](../architecture/temporal-contract.md)
and [ADR 0003](../decisions/0003-initial-temporal-semantics.md), with
[five JSON histories](../architecture/fixtures/temporal-contract-v0.json) containing
84 exact expectations. Reconcile README, agent rules, conceptual/architecture and
contributor guidance, decision index, roadmap and site navigation.

**Decisions:** whole signed-integer conformance values; UTC microsecond coordinates;
half-open intervals with explicit infinity; range replacement and retraction in
commit/operation order; atomic serialized commits and pinned read views; retained
request identities for safe retries. Specify point lookups, coalesced interval
scans and exact aggregates. Define memory-only versus future durable acknowledgement,
uncertain outcomes and finite-coordinate boundary behavior. ADR 0003 records
alternatives and conditions for revisiting this working specification.

**Prerequisite delivery:** MDB-0001 commit
`c0df7258f83dd904fa7680b4834b8f51668d8b5a` was verified on remote `main`; its
[Documentation checks](https://github.com/MeridianDB/meridiandb/actions/runs/40734555585)
completed successfully before selection.

**Validation:** all 84 fixture expectations pass a one-off independent check using
backward operation lookup at a point and partitioning at interval boundaries,
without a mutable interval-splitting algorithm. Cases cover correction, retraction,
old/open views, multi-entity transaction rejection, rejected-ID retry, duplicate-ID
replay/conflict, no-op commits, gaps/coalescing, signed values, exact sums beyond
64 bits and finite time limits. The fixture SHA-256 is
`ae96845882f07cea56a5f7edff42a2575efe36a0792533ce16ed2342582720ef`.
This check validates the specified examples; it is not a Java engine or a
concurrency/durability test.

Strict Zensical builds pass for `https://meridiandb.org/` and
`https://meridiandb.github.io/meridiandb/`: 14 content pages with matching canonical
URLs/sitemap, descriptions, assets and page/fragment links. A local static preview
returns HTTP 200 for the contract, ADR, concept and queue pages, confirms both new
navigation/search entries, and serves fixture bytes matching source. Queue graph,
caller/terminology and Git whitespace/diff checks pass. No Maven/Java tests were
run because the Java foundation remains the next unstarted item.

**Scope and remaining work:** no new/split queue IDs were needed for this cohesive
specification step. MDB-0003 introduces the JDK/Maven foundation and a meaningful
executable check; MDB-0004 will consume the fixtures with the Java reference model.
Actual engine concurrency, lifecycle and crash behavior remain implementation work.
The initial retention promise is complete committed history and request identities;
a later removal policy needs an explicit contract revision.

**Delivery evidence:** the completion commit carries `Step-ID: MDB-0002` and
`Step-Result: completed`; resolve its SHA and remote ancestry through Git. The final
handoff reports actual push and CI state. Source changes do not publish the site;
a later manual Pages publication remains separate.

**Next recommendation at this checkpoint:** MDB-0003 — establish the Java and Maven
build foundation. This recommendation does not start it.

## 2026-10-28 — Automatic GitHub Pages publication

**Owner request and outcome:** make website publication automatic. Add filtered
pushes to `main` to **Publish documentation**, retaining manual rebuilds, its strict
production build, serialized deployment and existing scoped Pages permissions.
Documentation content/configuration/dependency/workflow changes publish;
pull requests, other branches and Java-only source changes do not publish.

**Policy reconciliation:** update README, root agent rules, ADR 0002, contributor
and website guides, queue handoff, roadmap and reusable queue prompt. The earlier
manual-only Pages policy is historical. The website guide owns current input
filters and publication checks. Library releases and Cloudflare deployments keep
their existing separate scope. The engine queue remains at MDB-0003 ready; this
website maintenance request does not execute or advance an engine item.

**Validation:** YAML parsing and ten branch/path cases check automatic trigger
selection, optional dispatch, main-only jobs, strict-build dependency, pinned
actions, deployment serialization and scoped permissions. Strict documentation
builds and generated URL/link checks pass for both supported hosting paths with
14 content pages. Review affected pages through a local preview, scan active
policy callers and inspect the Git diff. Actual GitHub workflow/deployment results
and live-content verification are reported in the task handoff after pushing.

**Next recommendation:** MDB-0003 — establish the Java and Maven build foundation.

## 2026-10-28 — HTTPS publication URL repair

**Finding:** automatic publication was activated in commit `b598b22`. Both
Documentation checks and the first push-triggered
[Publish documentation run](https://github.com/MeridianDB/meridiandb/actions/runs/40739465632)
succeeded, and new content was live. A subsequent live metadata check found
`http://meridiandb.org/...` canonical URLs despite successful HTTPS serving.

**Repair:** construct `DOCS_SITE_URL` from explicit HTTPS plus the pinned
configure-pages action's verified `host` and `base_path` outputs. Preserve the
configured host/project path, reconcile agent/website guidance and ADR 0002, and
retain live URL/sitemap checks as completion evidence. This is follow-through on
the same automatic-publication request; the engine queue remains unchanged.

**Validation:** four URL-construction cases cover HTTP/HTTPS origin metadata for
custom-domain and project-path layouts. Strict builds and generated URL/link
checks pass for both layouts with HTTPS canonical/sitemap URLs on all 14 content
pages. The repair's automatic deployment and live metadata checks are reported in
the final handoff. No repository domain setting or DNS change is needed.

**Next recommendation:** MDB-0003 — establish the Java and Maven build foundation.

## 2026-10-28 MDB-0003 — Java and Maven foundation

**Outcome:** introduce the single experimental artifact
`org.meridiandb:meridiandb-core:0.1.0-SNAPSHOT`, standard Java source/test layouts,
Java 25 compiler baseline, Apache-generated Maven Wrapper, pinned plugins and
JUnit Jupiter, ignored local build output and Linux/Windows read-only verification
CI. The first production primitive is `ValidTimeInterval`, with explicit unbounded
bounds and no endpoint arithmetic. No engine or library release is introduced.

**Decisions and owner direction:** [ADR 0004](../decisions/0004-java-build-foundation.md)
records the JDK, one-artifact structure and independent docs build. The owner requires
research of latest Maven dependency artifact versions for every Maven change;
root agent rules and the [build guide](../development/build.md) now own that procedure.
Research on October 28 selected Maven 3.10.0, Wrapper 3.3.4, JUnit 6.1.3 and the
latest stable compatible versions of every pinned lifecycle plugin. The guide
records exact coordinates, current stable versus metadata prerelease entries and
primary-source links. Maven 4 RC/plugin betas were researched and deliberately
not selected. Install/deploy versions are managed but neither goal runs in verify.

**Prerequisite delivery:** MDB-0002 completion `ecd07c9` was verified on remote
`main`. Latest delivery `f28155e` had successful Documentation checks and Pages
publication before this item was claimed.

**Validation:** `./mvnw -B verify` succeeds on macOS arm64 with Oracle JDK 25.0.2
and pinned Maven 3.10.0: 18 tests, zero failures/errors/skips. Salary fixture
coordinates agree with the Java boundary inputs. A clean index/source checkout
without `.git` or generated output, in a path containing spaces, passes the same
command and produces a byte-identical JAR (SHA-256
`8481ce322984a9294c0174446021ca9d0810a9a39c92db879188c47713a2af7b`).
The artifact contains only the production primitive and metadata, with Java 25
bytecode and no bundled JUnit/runtime library.

The Maven archive matches Apache's published SHA-512; its derived SHA-256 is pinned
in wrapper properties. A fresh wrapper cache rejects a deliberately incorrect
SHA-256 before Maven starts. Static checks reconcile all nine POM dependency/plugin
pins with researched stable versions, the JDK file/compiler release, wrapper
properties and both CI launcher commands. Actions are pinned and repository
permissions are read-only. Actual Linux/Windows CI outcomes are reported after push.

Strict Zensical builds and generated URL/link checks pass for both supported
hosting paths with 16 content pages. Local preview checks cover the new guide/ADR,
updated setup pages and navigation/search. Current-policy callers and the queue
are reconciled, Git whitespace/staged-scope checks pass, and scratch logs/builds
stay in ignored `.local-qa/mdb-0003/`. Windows execution and Pages publication are
verified through their actual workflow results in the final handoff.

**Scope and next work:** this is one cohesive build-foundation step; no new/split
IDs were required. Its interval primitive supplies meaningful executable checks
and will be reused by MDB-0004. The 84-expectation history suite, additional temporal
types, reference interpreter and seeded histories remain next; these 18 primitive
checks are not engine/concurrency/durability evidence.

**Delivery evidence:** the completion commit carries `Step-ID: MDB-0003` and
`Step-Result: completed`; resolve its SHA and remote ancestry through Git. The final
handoff reports actual push, Java/docs CI and automatic Pages publication state.
No Java library is uploaded or released.

**Next recommendation at this checkpoint:** MDB-0004 — implement temporal types
and the independent reference model, reusing the interval primitive and history fixtures.

## Receipt template

Append a dated entry for the selected ID with: outcome and affected artifacts;
acceptance evidence and actual commands/results; decisions and remaining limits;
new/split/reordered IDs with rationale; blocker/unblock evidence when relevant;
completion or checkpoint commit trailers; and the next recommendation at that time.
Keep current status and active ownership in the queue rather than duplicating it here.
