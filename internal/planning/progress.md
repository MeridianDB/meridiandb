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
from the [website guide](../site-operations.md#initial-hosting-and-publication).

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

**Outcome:** adopt the [initial temporal contract](../../docs/architecture/temporal-contract.md)
and [ADR 0003](../../docs/decisions/0003-initial-temporal-semantics.md), with
[five JSON histories](../../docs/architecture/fixtures/temporal-contract-v0.json) containing
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

**Decisions and owner direction:** [ADR 0004](../../docs/decisions/0004-java-build-foundation.md)
records the JDK, one-artifact structure and independent docs build. The owner requires
research of latest Maven dependency artifact versions for every Maven change;
root agent rules and the [build guide](../../docs/development/build.md) now own that procedure.
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

## 2026-10-28 MDB-0004 — Temporal types and reference model

**Outcome:** add immutable experimental entity/request/database identities, ordered
transactions, writes, logical queries/results, commit positions, cutoffs and
acknowledgements under `org.meridiandb.temporal`, reusing the interval primitive.
Implement a sequential test-only `ReferenceModel` with backwards history lookup,
arbitrary-precision scan boundary partitioning, exact aggregates and committed
request identity. No production engine is introduced.

**Independent evidence:** execute the unchanged canonical fixture source through
Maven test resources: five histories, 84 exact step expectations. A strict test
adapter validates the complete request before applying it and rejects coercion,
missing bounds and out-of-range numbers. A separate forward dense-state model
checks 16 persisted seeds, each with 32 new transactions (512 new commits total),
plus retries and conflicting ID reuse. It checks point/scan/aggregate answers,
head stability and every saved historical prefix without using history search,
boundary partitioning or the production interval membership helper. No failing
semantic seed was found; future defects retain seeds and minimized reproductions.

**Decisions and research:** [ADR 0005](../../docs/decisions/0005-reference-model.md) keeps
the oracle and fixture parser in test support while sharing only immutable logical
types with the future engine. All existing Maven pins were researched again on
October 28 and remain the latest stable compatible selections. Jackson
`tools.jackson.core:jackson-databind:3.2.4` is the researched current stable test
parser. The [build guide](../../docs/development/build.md) records coordinates, Central
metadata and official project evidence. No production runtime dependency was added.

**Prerequisite delivery:** MDB-0003 completion `e15961f` was verified on remote
`main`, with successful Java Linux/Windows checks, Documentation checks and
[Pages publication](https://github.com/MeridianDB/meridiandb/actions/runs/40744870880)
before this item was selected.

**Validation:** `./mvnw -B -ntp verify` passes on macOS arm64, Oracle JDK 25.0.2,
Maven 3.10.0: 57 JUnit test invocations, zero failures/errors/skips. These comprise
18 interval checks, eight type checks, ten reference/decoding checks, five history
tests and 16 seed tests. A fresh source snapshot without Git or generated output
passes the same suite, loads the canonical fixture and seed resources, and produces
a byte-identical JAR (SHA-256
`2803f469a885cd63f47cb59f87e0716c4e3368f6856a4516956e5df5018b07a5`).
The JAR contains 23 Java 25 temporal classes and metadata, with no test support,
fixtures, seeds, JUnit or Jackson bundled. Static checks reconcile all ten POM
artifact pins, two test scopes and zero runtime dependencies against the research.

Java CI now also runs for canonical fixture/contract edits. Linux/Windows launcher
commands, read-only permissions and pinned actions pass static checks. Independent
read-only code review found no actionable semantic defects. Strict Zensical builds
and generated canonical/sitemap, link, fragment, asset and metadata checks pass for
both supported hosting paths with 18 content pages. Local HTTP previews verify the
new guide/ADR, setup and planning pages, navigation and search. Current-status
callers are reconciled; the earlier build decision/receipts retain their historical
scope. Queue IDs/dependencies, Git whitespace and staged scope are checked before
commit. Ignored scratch evidence stays under `.local-qa/mdb-0004/`.

**Scope and limits:** one cohesive reference-model outcome; no split or new queue
IDs were required. The seed grid is bounded, and the oracle is sequential, holds
all history in memory and has no resource lifecycle. Passing these checks does
not establish engine concurrency, atomic publication, crash recovery, durability
or performance. Those require the actual engine and later storage steps.

**Delivery evidence:** the completion commit carries `Step-ID: MDB-0004` and
`Step-Result: completed`; Git resolves its SHA and remote ancestry. The final
handoff reports actual push, Java/docs CI, automatic Pages publication and live
content checks. No library artifact is uploaded or released.

**Next recommendation at this checkpoint:** MDB-0005 — build the embedded
in-memory engine, independently of the test oracle, with explicit read views,
transaction publication, lifecycle checks and one runnable embedding example.

## 2026-10-28 MDB-0005 — Embedded in-memory engine

**Outcome:** add `org.meridiandb.engine.InMemoryEngine` and semantic engine failures
in the existing artifact, with a fresh lineage per instance. The engine implements
ordered interval replacement/retraction, exact point/scan/aggregate answers,
committed request identity and every historical cutoff in memory. Writers and
engine close serialize on one monitor; a single volatile publication exposes
immutable value/history/request state. Pinned views can be shared by readers and
have explicit idempotent close behavior. No durable acknowledgement is promised.

**Algorithm and independence:** production splits/replaces/coalesces per-entity
interval state and reads immutable snapshots. The test oracle continues to search
whole transaction history backwards and partition boundaries using arbitrary
precision. A small test-only facade runs the same canonical notation against each;
it captures named views as positions and reopens their cutoff for queries. Direct
engine tests separately hold actual view objects across writes and concurrent
reads. Neither production code nor the packaged artifact uses the test oracle.
[ADR 0006](../../docs/decisions/0006-in-memory-engine.md) records publication and lifecycle
choices; the [engine guide](../../docs/development/embedded-engine.md) owns API behavior,
example execution and limitations.

**Verification:** `./mvnw -B -ntp verify` passes with 71 JUnit invocations and zero
failures/errors/skips on macOS arm64, Oracle JDK 25.0.2 and Maven 3.10.0. All five
canonical histories execute unchanged: 84 exact expected steps per implementation.
The existing 16 seeds apply 32 new transactions each to both engine and reference,
checking each against independent forward dense snapshots (512 new transactions
per implementation), with retries, conflicts and every historical prefix.
Existing type/boundary/decoding checks remain. No semantic failing seed was found.

Nine focused lifecycle/concurrency tests cover lineages/cutoffs, pinned views,
independent view close, engine close invalidation, 12 simultaneous identical
callers, 12 conflicting callers, 16 distinct concurrent callers with contiguous
commits, 32 cross-entity publication rounds with four readers, and six shared-view
readers. They use barriers and bounded waits without sleeps. Independent static
review found no actionable defects; it does not claim exhaustive schedules or
separate close-race stress coverage.

A fresh source snapshot without Git or generated output passes the same 71 tests
and runs `java --class-path target/classes examples/EmbeddedExample.java` with
exact expected output: salary `100` before correction, `120` after, and identical
retry at commit `2` with replay set. Its JAR is byte-identical to the local artifact
(SHA-256 `9ab98fabf25ecd007e5ec145c34bd20b8231b037ae0bdf34a1806b66d10e35da`),
containing 30 Java 25 temporal/engine classes and metadata. Test support, seeds,
fixtures, JUnit, Jackson and the source-launch example are not bundled. Java CI
now runs the example on both Linux and Windows and includes example-source changes
in its trigger paths. Runtime dependencies remain zero.

**Research and prerequisite delivery:** MDB-0004 completion `2f5f57d` was verified
on remote `main`, with successful Java Linux/Windows, documentation checks and
[Pages publication](https://github.com/MeridianDB/meridiandb/actions/runs/40755104739)
before selection. Fresh October 28 Central metadata research checked all twelve
existing artifact coordinates; official Maven/JUnit/Jackson documentation confirms
the retained stable choices and compatibility. All ten POM pins still match the
researched stable releases; POM, canonical fixture answers and persisted seed corpus
are unchanged. The [build guide](../../docs/development/build.md) records the refreshed
check; no new dependency or execution plugin is needed.

**Documentation and planning:** strict Zensical builds and generated canonical,
sitemap, link, fragment, asset and metadata checks pass for both hosting paths
with 20 content pages. Local HTTP preview covers the engine guide/ADR, current
status, build instructions, roadmap and navigation/search. README, agent rules,
architecture, contributing, reference guidance and active status callers are
reconciled; earlier dated decisions/receipts retain historical scope. Queue IDs and
dependencies, working-tree/staged scope and whitespace are checked before commit.
Scratch verification stays in ignored `.local-qa/mdb-0005/`.

**Scope and limits:** this is one cohesive engine/conformance/example outcome;
no split or new IDs were needed. Every cutoff and request identity is retained,
with map copying and changed interval lists on commits. Memory and write cost grow
with history. Closing loses the database; already captured operations may finish
during a concurrent close. Persistence, recovery, retention, multi-process writer
coordination, indexes and measured performance remain future outcomes.

**Delivery evidence:** the completion commit carries `Step-ID: MDB-0005` and
`Step-Result: completed`; Git supplies its SHA and remote ancestry. The final
handoff reports actual push, Java/example/docs CI, automatic Pages publication and
live content checks. No Java library is uploaded or released.

**Next recommendation at this checkpoint:** MDB-0006 — define local storage
capabilities and the versioned commit journal, with exact record, checksum,
publication and incomplete/corrupt-tail behavior before durable engine integration.

## 2026-10-28 MDB-0015 — Journal format, codec and storage research

**Outcome and decomposition:** retain MDB-0006's original local-storage/journal
acceptance criteria and expand it into required children MDB-0015 (format/codec)
and MDB-0016 (local append, force, ownership and recovery). MDB-0015 inherits
MDB-0005; MDB-0016 inherits MDB-0005 and depends on MDB-0015. MDB-0007 continues to
depend on the parent. This separates byte representation from filesystem failure
handling, completing one bounded executable child in this invocation. MDB-0006
remains expanded; the next child is source-ready, not implemented.

**Owner direction and research:** the owner asked for best practices from similar
immutable append-only, high-throughput realtime-search databases. The
[dated primary-source comparison](../../docs/architecture/storage-research.md) covers XTDB,
Kafka 4.4, RocksDB, Lucene/Elasticsearch, Quickwit 0.9 and ClickHouse. It distinguishes
observed mechanisms from MeridianDB design inference. The selected approach keeps
durable append, immutable checkpoint publication and searchable cutoff separate;
allows later physical group commit without merging logical transaction identity;
avoids one tiny immutable part per request; bounds work and retains the journal
until recovery/retention proves it reclaimable. No throughput/latency claim or
new service dependency is introduced.

**Implementation:** add pure `JournalCodec`, `JournalRecord`, `JournalScan` and
format failures under `org.meridiandb.storage.journal`. Versioned lineage headers
and complete transaction frames use protected length framing, prefix/payload
CRC32C and exact ordered payloads. Identifiers encode lossless Java UTF-16 code
units, including lone surrogates; finite bounds retain distinct tags from infinity.
Size/count bounds precede allocation. Scans require sequences contiguous from 1
and unique request IDs, reporting only a valid prefix for incomplete candidates.
Corruption/unsupported/malformed input fails closed without resynchronization or
repair. [ADR 0007](../../docs/decisions/0007-journal-format.md) explains the choices and
[journal specification](../../docs/architecture/journal-format.md) owns bytes/limits and
the next local capability contract.

**Validation:** `./mvnw -B -ntp verify` passes on macOS arm64, Oracle JDK 25.0.2 and
Maven 3.10.0: 100 JUnit invocations, zero failures/errors/skips. The 29 new codec
tests cover independently calculated hardcoded golden bytes; every truncation
byte offset; single-bit mutation at every byte of the golden header/frame;
checksum-valid malformed fields, versions and history; identity/operation limits;
exact 8 MiB payload and the next-code-unit rejection; all bound modes, numeric
extremes, exact Unicode and input buffer position/order/limit invariants. The
existing 71 engine/reference/type checks, 84 expectations per implementation,
16 seed histories and lifecycle/concurrency coverage remain unchanged. A focused
codec run and independent static review found no production defect. Initial test
fixtures for field-limit errors were padded to reach the field rather than fail
the frame's minimum-size guard; no contract answer was changed.

A fresh source snapshot without Git or generated output passes all 100 tests and
the original embedding example. It produces a byte-identical JAR (SHA-256
`17225c2b6cd5dbc7b76f53cadfadca555657e4c0344562e1c3918d53d80a825d`), containing
35 Java 25 production classes and metadata, with no bundled test support, fixtures,
seeds, JUnit or Jackson. Fresh October 28 Central metadata research rechecked all
twelve coordinates; the ten POM pins remain the latest stable researched choices.
No dependency changed; only the POM description was corrected to reflect current
implementation. Original contract fixtures and seed corpus are unchanged.

**Prerequisite delivery:** MDB-0005 completion `836eb5c` was verified on remote
`main`, with successful Java Linux/Windows/example, Documentation checks and
[Pages publication](https://github.com/MeridianDB/meridiandb/actions/runs/40758298971)
before this child was selected.

**Documentation and planning:** update README, agent rules, architecture, build,
reference/engine guides, decision index and expanded queue together. The owner's
comparable-system guidance is durable and linked to primary-source evidence.
Strict Zensical builds and generated URL/link/fragment/asset/metadata checks pass
for both hosting paths with 23 content pages. Local HTTP previews cover new research,
journal specification/ADR, current status, queue and navigation/search. Java CI
also triggers when the journal specification changes. Queue identity/parent/
dependency checks, staged scope and whitespace are checked before commit. Scratch
outputs remain ignored under `.local-qa/mdb-0015/`.

**Limits and delivery:** codec success proves byte representation and prefix
validation, not a successful append, force or durable acknowledgement. The image
scanner materializes its input/results; a future file reader must recover
sequentially with bounded frame buffers. No file I/O, writer lock, automatic tail
truncation, device durability, durable engine, compression, fragmentation or
performance result is implemented here. The completion commit uses
`Step-ID: MDB-0015` and `Step-Result: completed`; Git supplies its SHA/remote
ancestry. The final handoff reports actual push, CI, automatic Pages and live
content checks. No Java library is uploaded or released.

**Next recommendation at this checkpoint:** MDB-0016 — implement the local
append/force and exclusive writer protocol with short/failed-I/O injection,
bounded sequential recovery and explicit evidence-preserving tail repair.

## 2026-10-28 MDB-0017 — Public documentation quality

**Owner correction and outcome:** public documentation must explain the system,
not narrate owner requests, this conversation or agent execution. Insert MDB-0017
as a prerequisite of MDB-0016 and complete this bounded correction first. The
local journal implementation has not begun; MDB-0006 remains expanded with both
original children and downstream dependencies intact.

**Public editorial changes:** audit README and all public Markdown. Replace
conversational attribution, personal language-choice discussion, task IDs and
execution-status narration with direct technical explanations. Retain technical
reasoning, alternatives, dated primary evidence and actual implementation limits.
Refresh research, architecture, journal/temporal specifications, development and
contributor guides and ADRs. Correct the fixture's stale descriptive `status` to
reflect its use by the reference model and memory engine; its histories, inputs
and expected answers are unchanged after excluding that field.

Move the four implementation workflow/queue/roadmap/progress records from
`docs/planning/` to `internal/planning/`, preserving IDs, original acceptance
criteria and dated history. Retain site administration/DNS procedures in
`internal/site-operations.md`. These are repository records outside the public
build input. Add `docs/roadmap.md` with implemented capabilities, planned storage
work and correctness/performance requirements, without live execution state.
Reconcile AGENTS, navigation, public links and internal callers after relocation.

**Persistent QA:** establish `internal/documentation-quality.md` as the editorial
and publication standard and bind it from root agent rules. Add the shared
`scripts/build_docs.py` command with source and rendered checks. Both documentation
workflows run QA fixtures and this same build before validation or site upload.
The checker verifies public source/nav coverage, one H1, useful unique descriptions,
clear internal-narrative markers, local-path/internal-route leaks, exact rendered
page/canonical/description sets, local links/anchors/assets, public search coverage
and valid search routes/anchors, plus exact sitemap coverage. Automated checks
remain guardrails alongside full-page editorial review.

**Verification:** 41 positive/negative documentation QA tests pass. Checked strict
builds pass for `https://meridiandb.org/` and
`https://meridiandb.github.io/meridiandb/`, each with 20 public content pages and no
planning/operations routes in HTML, search or sitemap. Local HTTP/browser inspection
covers the corrected research article, roadmap layout/navigation and working
search results; removed planning routes return 404. No theme/assets changed; no
new mobile or dark-mode result is claimed. All 106 local links in the eight
repository/internal guides resolve, including anchors. Queue validation confirms
17 unique IDs, valid parent/dependency references, no cycles, preserved MDB-0006
criteria and MDB-0016 as the next ready item.

Independent review found and resolved remaining README language-choice process
copy, a stale fixture status and insufficient search-index structural/coverage QA.
The focused `ReferenceFixtureTest,EngineFixtureTest` Maven run passes all 10 history
invocations after the descriptive JSON edit. No Java implementation, POM, artifact
pin or fixture expectation changed, and this is not a new full engine/durability
verification. Git whitespace and staged scope are checked before commit. Scratch
outputs stay ignored under `.local-qa/mdb-0017/`.

**Prerequisite and delivery evidence:** MDB-0015 completion
`f9fd25d6f40a4fea5797a220f5220d79d1b5c803` was verified on remote `main` with all
relevant workflows successful before this correction. The completion commit uses
`Step-ID: MDB-0017` and `Step-Result: completed`; Git supplies its SHA and remote
ancestry. The final handoff reports actual push, CI, automatic Pages publication
and live content/search/sitemap checks. No library release or infrastructure
change is part of this correction.

**Next recommendation at this checkpoint:** MDB-0016 — implement local journal
append/sync, exclusive writer ownership and bounded evidence-preserving recovery.

## Receipt template

Append a dated entry for the selected ID with: outcome and affected artifacts;
acceptance evidence and actual commands/results; decisions and remaining limits;
new/split/reordered IDs with rationale; blocker/unblock evidence when relevant;
completion or checkpoint commit trailers; and the next recommendation at that time.
Keep current status and active ownership in the queue rather than duplicating it here.
