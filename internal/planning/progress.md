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

## 2026-10-28 MDB-0016 — Local journal I/O and recovery

**Outcome:** implement `LocalJournal` over a single owning local `FileChannel`,
using the existing exact v1 codec. Appends loop over short writes, require the next
sequence and a new request ID, and keep append cutoffs separate from successfully
forced cutoffs. Validate size/history before writing without reserving an ID.
An I/O failure poisons the instance for append/force/replay; close releases it
without implicit force or truncation. A fresh create forces its complete lineage
header; a clean reopen does not infer earlier force/acknowledgement evidence.

Reserve canonical-path/existing-file identity in the JVM before opening another
channel, then acquire an exclusive OS file lock. This addresses the JDK-documented
POSIX same-file close hazard; same-path and hard-link aliases cannot release a
cooperating writer's lock through a failed second open. Use stable paths, local
regular files and one adapter class-loader copy. Final symbolic links are rejected;
external writes, rename/unlink and uncooperative handles remain unsupported.

**Recovery and explicit repair:** factor validated fixed-prefix lengths from the
codec and reuse them in sequential `JournalRecovery`. At most one bounded frame is
read at a time; exact request-ID memory still grows with retained history. Default
open refuses incomplete tails with the validated byte boundary. Explicit repair
copies the exact suffix to a new sibling evidence file, forces/closes it, then
truncates and forces the retained journal. Corruption, unknown formats, malformed
history and partial headers fail closed without repair or resynchronization.
Repair failures return no usable journal and retain evidence; backup failure
leaves original journal bytes untouched. The protocol establishes file-force
ordering, not portable directory-entry durability or a proof of harmless loss.

**Research:** fresh October 28 reads of all twelve Central metadata sources and
Apache Maven/plugin, JUnit and Jackson compatibility documentation confirm every
existing selected stable pin unchanged. Maven 4 RC/plugin 4 betas remain preview;
Java 25 fits the selected Java baselines. No dependency was added; the POM's
artifact description now includes the local journal. The public build guide
retains exact coordinates, latest-versus-selected versions and primary links.

Fresh JDK25 FileChannel/FileLock, OpenJDK macOS/Windows implementations, Linux fsync,
Microsoft directory-handle and SQLite locking/recovery evidence informed the
protocol. `force(true)` covers local file content/metadata, not durable publication
of a newly created journal/evidence name. There is no portable directory sync;
namespace durability needs a platform-specific protocol. Process termination is
not sudden power loss. The public local guide and ADR 0008 record these limits
and sources without task/owner narration.

**Validation:** `./mvnw -B -ntp verify` passes 138 JUnit invocations, zero failures,
errors or skips: 35 local adapter cases, three separate-JVM tests and the existing
100 codec/temporal/engine/reference checks. New checks cover exact IDs/operation
order/lineage after reopen, force positions, short/zero/failed I/O, bounded-prefix
allocation/read behavior, default refusal and suffix-preserving repair, failed
backup/truncate/force/close, corruption/history/version rejection, aliases,
callback reentrancy and failure isolation. Separate JVMs demonstrate writer
exclusion, preservation of the OS lock after a rejected same-JVM opener, and exact
reopen after `Runtime.halt` following successful force. Existing 84 expectations
per engine/reference and 16 seed histories remain intact.

Independent review identified a consumer-thrown `JournalFormatException` being
mistaken for journal corruption. Isolate consumer failures with a private wrapper
and add the reproducer: a callback decodes bad unrelated input, propagates that
format failure, then the valid journal still accepts append/replay/force. Review
of the repair and public force/namespace claims found no remaining material issue.

Verification ran on macOS arm64, Oracle JDK 25.0.2 and local APFS. Both the existing
memory embedding example and new `LocalJournalExample.java` run successfully.
The local JAR has 45 Java25 production classes and no bundled test support,
fixtures, seeds, JUnit or Jackson. SHA-256 is
`2516ea1de772452f1d6892c2015c84ceaee21baf8debd4b45ff3eeb8146f63a9`.
Java CI now also runs the journal example and triggers on its public contract guide.
Linux/Windows results are confirmed in the actual workflow handoff, not inferred
from local execution.

**Documentation and planning:** update README, agent rules, home, architecture,
research, journal specification, development guides, ADRs/index and technical
roadmap with implemented local adapter versus planned durable engine behavior.
Both checked documentation URL layouts pass with 22 content pages; source,
rendered links/anchors/assets, canonicals/descriptions, public search and sitemap
pass. Local browser/HTTP preview checks the new guide, API/code, navigation and
search. Theme/assets are unchanged. Reconcile current callers and local links;
Git whitespace and staged scope are checked before commit. Scratch evidence stays
under ignored `.local-qa/mdb-0016/`.

**Parent and delivery:** close MDB-0006 because both original required children and
the scoped journal acceptance criteria now pass; preserve original IDs/criteria
and MDB-0007's parent dependency. Mark MDB-0007 ready, without beginning segment
work. MDB-0017 completion `81724d6fc8316f7ef085a9fb7fae10d7e769670a` was verified
on remote `main` with Java/docs/Pages success before this step. The completion
commit uses `Step-ID: MDB-0016`, `Step-Result: completed` and `Closes-Step: MDB-0006`;
Git supplies its SHA and ancestry. The final handoff reports actual push, CI,
automatic Pages and live-content evidence. No library release or other deployment.

**Limits and next recommendation:** the engine remains memory-only; no immutable
segments, manifests, compaction, rotation, object backend or performance claim is
implemented. MDB-0007 — implement versioned immutable segments and manifests,
including explicit namespace publication and referenced journal-prefix rules.

## 2026-10-28 MDB-0016 — Windows verification repair

Initial completion `bdf86a039b1d2c01941500a70397c770d1a731ef` reached remote `main`.
Linux Java/example checks, both documentation URL layouts and Pages deployment
succeeded. Windows passed the process/codec/engine checks but failed one local
repair test because that test opened a second raw read handle while the journal
held its exclusive lock. The Windows log reports the byte-range lock violation
at `LocalJournalTest.explicitRepairPreservesEveryCandidateByteBeforeTruncating`.
This was an invalid test access pattern, not a failed repair operation. Closing
such a second handle also risks releasing the original OS lock on POSIX systems.

Keep the owning instance's replay/counter assertions while open. Move exact raw
byte inspection after close and compare the entire retained prefix plus appended
frame, preserving the original evidence/prefix assertions. Persist this testing
rule in AGENTS. No production behavior, codec format, dependencies or public page
content changes. Current-step Maven metadata research remains applicable; all pins
are unchanged. The focused local suite passes 35 cases after the correction;
repeat Linux/Windows verification in CI and report actual results before advancing.

The repair commit retains `Step-ID: MDB-0016` and `Step-Result: completed`, with
`Repairs-Commit: bdf86a0`. Queue source state and MDB-0007's ready dependency remain
unchanged; finish this attributable delivery repair before starting it. The prior
successful Pages deployment still serves identical public documentation.

## 2026-10-28 MDB-0018 — Immutable segment format and codec

**Decomposition and outcome:** retain MDB-0007's original segment/manifest outcome
and criteria; expand it into three required children: MDB-0018 pure history format
and codec, MDB-0019 local immutable segment I/O/lifetimes, and MDB-0020 manifest
consistency/publication. Each inherits MDB-0006; I/O depends on the format and
manifest publication follows I/O. MDB-0008 continues to depend on the entire
parent. Complete only MDB-0018 in this invocation; MDB-0007 remains expanded.

Implement `ImmutableSegment`, `SegmentId`, typed `SegmentFormatException` and pure
`SegmentCodec`. Preserve a nonempty internally contiguous range beginning at any
positive sequence, exact lineage, original request/operation identity and valid-time
bounds. The immutable list gives a simple ordered scan baseline. No flattening,
compaction, query-index selection or global cross-segment uniqueness is implied.

The v1 envelope reuses exact journal lineage headers and transaction frames,
adding first/last/count/body length, header CRC32C and a SHA-256 integrity footer
over header+body. The external ID separately hashes the entire image including
that footer. Strict decoding verifies complete bytes, nested formats and history
before exposing a value; no partial prefix or incomplete-tail repair exists for
finalized segments. An independently expected ID must match. Byte/count/nested
limits are checked before allocations from them. The 32 MiB budget bounds encoding,
not total heap; decoded operations/identities and temporary buffers add memory.

Factor `JournalCodec.encodedRecordBytes` from its existing size validation so
aggregate segment preflight allocates neither each frame body nor the whole image
before rejecting excess. Existing encoding bytes, framing limits and decoder
behavior remain unchanged; the 29 journal codec checks pass.

**Primary research:** fresh October 28 reads of all twelve Central metadata sources
and official Maven/plugin, JUnit and Jackson compatibility information confirm
existing stable pins unchanged. Preview Maven/plugin 4.x entries remain unselected.
There is no new dependency; JDK SHA-256/CRC32C supply integrity/identity. The POM's
description now includes the codec. Current artifact coordinates/latest-versus-
selected versions and sources remain in the public build guide.

Lucene CodecUtil/SegmentInfos, Quickwit split/checkpoint publication and XTDB
finalized-file/log alignment sources informed the separation of complete-object
integrity, expected identity and checkpoint publication. ADR 0009 dates these
facts and distinguishes MeridianDB's SHA-256 representation choice from another
product's algorithms. Manifest publication and namespace durability remain future
work; neither bytes nor a digest establish an acknowledged durable database.

**Validation:** `./mvnw -B -ntp verify` passes 165 JUnit invocations with zero
failures, errors or skips: 27 independent segment cases plus the previous 138
codec/local-I/O/process/temporal/reference/engine cases. The new suite fixes a
158-byte independently constructed golden image, footer and full-object ID;
checks every truncation and single-byte mutation; re-signs malformed nested
versions/fields/checksums; checks identity/order/range/count/history mismatches,
exact Unicode/lone surrogates, finite/infinite distinctions and numeric extremes;
round-trips 65,536 actual records and rejects the next; checks declared size
boundaries and aggregate encode preflight using shared near-limit transactions;
and verifies input/output/list ownership and external ID binding.

The first focused run exposed classification disagreement for complete-envelope
missing/trailing frame bytes. Classify those structural failures as CORRUPT,
consistent with partial nested items inside a complete object. Sequence/request/
range failures retain INVALID_HISTORY. All 27 cases and full verification then
pass. Independent production and public-spec/ADR review found no remaining
material issue. Contract fixture answers, seeds and prior storage behavior are
unchanged; this is representation evidence, not durable engine conformance.

All three Java examples run: memory temporal queries/retry, local journal
append/force/reopen, and exact segment round-trip with expected full-object ID.
CI runs each as an independent step on Linux/Windows, and Java checks trigger for
the new format contract. The local JAR contains 52 Java25 production classes,
including compiler-generated support, with no bundled test reference, fixtures,
seeds, JUnit or Jackson. SHA-256 is
`6556be7eec49a0065e258d351d1ce9e60d23eb902035c75754b79f4dee8e69f2`.

**Documentation and handoff:** update README, agent rules, public home/architecture,
research, development/reference/engine guides, decision index and technical roadmap
with pure segment codec versus planned local files/manifests. Remove inherited
personal-language rationale from the affected architecture page. Publish the
exact byte specification and ADR with portable Markdown; private decomposition,
receipts and execution state stay under `internal/`. Checked builds pass for both
custom-domain and project-path URLs, with 24 public content pages and complete
source/metadata/link/search/sitemap QA. Local HTTP/browser inspection covers the
new page, layout/tables/code, navigation and search. Theme/assets are unchanged.
Queue IDs, references, parent children and dependency cycles plus repository links
and staged scope are checked before commit. Scratch outputs remain ignored under
`.local-qa/mdb-0018/`.

**Prerequisite and delivery:** `490f8abc97b68f4d3f3dc1c13f751197d802dbd0` was
verified on remote `main` with Java Linux/Windows/example success. Its initial
implementation `bdf86a039b1d2c01941500a70397c770d1a731ef` supplied the unchanged
successful documentation checks/Pages publication. This step's completion commit
uses `Step-ID: MDB-0018` and `Step-Result: completed`; Git supplies SHA and remote
ancestry. The final handoff reports actual push, CI, automatic Pages and live
content/search/sitemap evidence. No library artifact is uploaded or released.

**Next recommendation at this checkpoint:** MDB-0019 — implement local immutable
segment I/O, exact expected-ID verification and explicit reader lifetimes. Keep
MDB-0007 expanded until this child, MDB-0020 and original parent criteria pass.

## 2026-10-28 MDB-0019 — Local immutable segment I/O and reader lifetimes

**Outcome:** complete only MDB-0019, preserving MDB-0007's original acceptance
criteria and expanded state. Implement `LocalSegmentFile` and `LocalSegmentReader`
using the exact v1 codec. The caller selects a new filename in an existing trusted,
stable local directory. Encode/preflight before opening; CREATE_NEW/no-follow
writes complete with bounded zero-progress handling, size validation, file force
and successful close before returning the full-image ID. Never overwrite, truncate
or delete a candidate; failed/uncertain bytes remain for inspection.

Require an independent expected ID before input open. Reject non-regular inputs
before a read-only open, use no-follow on the actual open and recheck regular-file
status with cleanup. Bound size before image allocation, complete short reads,
probe EOF/recheck size, then validate the complete encoding and expected full-object
ID. No reader escapes a failed decode or close. Preserve primary exceptions and
suppressed close errors. The narrow package-private positional I/O seam exercises
these same paths with deterministic failures.

Readers hold immutable heap history with no open file/mapping. Synchronized
accessors reject use after idempotent close clears their reference. Previously
returned values remain valid; independent readers have independent lifetimes and
later on-disk changes cannot alter their snapshots. No deletion or retention
permission is inferred. Limits bound bytes, not decoded/aggregate heap.

**Research and review:** all twelve Maven Central metadata sources and official
Maven/plugin, JUnit and Jackson compatibility sources were freshly rechecked on
October 28. Selected stable versions remain unchanged; Maven/plugin 4.x preview
entries remain unselected. POM changes only its description; no new dependency or
plugin is introduced. JDK25 Files/FileChannel/NOFOLLOW_LINKS semantics, Lucene
Directory file-sync/metadata-sync separation and Quickwit split/checkpoint
publication informed ADR 0010. CREATE_NEW is atomic name creation, not whole-image
publication. File force does not establish portable directory durability.
ATOMIC_MOVE alone cannot promise no-overwrite replacement behavior. Final-component
no-follow does not freeze ancestors; the supported boundary is a trusted stable
directory, not hostile-path race protection. Independent production and public
copy/ADR/caller reviews found no remaining actionable defects.

**Validation:** focused `./mvnw -B -ntp -Dtest=LocalSegmentFileTest test` passes
36 tests. The initial compilation caught an explicit-close-inside-resource warning
under `-Werror`; keep the intentional close/lifetime assertion and use explicit
finally cleanup for that reader. No behavior assertion was weakened. Full
`./mvnw -B -ntp verify` passes 201 tests with zero failures, errors or skips:
36 new local segment cases plus the previous 165 temporal/reference/engine,
journal codec/local-I/O/process and segment codec cases. The new suite covers
exact Unicode/lone-surrogate lineage/request/operation history, raw bytes and full
ID versus footer; existing complete/empty/failed candidates; encode failure before
path creation; missing/non-regular/symlink input; incomplete/corrupt/suffixed files;
wrong expected ID; oversized sparse files before reading; post-open disk changes;
independent readers, close/access and retained immutable values; short/transient-
zero/stalled/failed reads/writes; EOF probe, growth/shrink/size changes; force and
close failures, suppression and close-before-return ordering. The symlink case may
skip only when a platform cannot create it; it ran locally. No power-loss or
durable-engine conformance claim follows. Canonical fixtures and persisted seed
corpus remain unchanged.

All four examples run successfully: embedded queries/retry, local journal
append/force/reopen, pure segment codec, and the new local segment create/reopen
with retained records after reader close. CI runs each as a separate step on both
Linux and Windows and triggers for the local segment guide. The local JAR has
56 Java25 production classes, no bundled oracle/fixtures/seeds/JUnit/Jackson, and
SHA-256 `511582fb02e25754fb69ef653b5235cfb669d7b766220a9f7f357e2b9a7c12ca`.
This is local artifact evidence, not a fresh reproducibility or performance test.

**Documentation and handoff:** add the local segment guide and ADR 0010 with
portable Markdown, source-relative links, public descriptions and navigation.
Reconcile README, home, architecture, research, roadmap, build/reference/engine
and contribution guides, format/ADR0009, decision index and agent rules. Public
copy describes behavior and limits; execution state remains under `internal/`.
Checked builds pass for both `https://meridiandb.org/` and the GitHub project path,
with 26 intended public pages and complete source/HTML/link/search/sitemap QA.
Local HTTP/browser preview verifies guide/code/navigation/search and discovers
the guide and new ADR through “heap snapshot”. Theme/assets are unchanged; no
new dark/mobile visual assertion is made. Check repository links, queue unique
IDs/references/acyclic dependencies and staged scope before commit. Scratch logs
and live QA receipts stay ignored under `.local-qa/mdb-0019/`.

**Prerequisite and delivery:** prerequisite completion
`5715c5eaf0fae828e4dc0bb1cc7bd97381f810c2` is on remote main with successful Java
Linux/Windows/examples, documentation checks and Pages publication. This completion
uses `Step-ID: MDB-0019` and `Step-Result: completed`; Git supplies its SHA and remote
ancestry. Final handoff reports actual push, CI, automatic Pages and live public
content/search/sitemap evidence. No artifact upload, release or other deployment.

**Next recommendation:** MDB-0020 — checkpoint manifest consistency and local
publication, including exact journal prefix, referenced IDs/ranges/lineage and
explicit namespace/platform durability. Keep MDB-0007 expanded until this child
and every original parent criterion pass. No new step or ID is added here.

## 2026-10-28 MDB-0021 — Manifest format and checkpoint consistency

**Decomposition and outcome:** preserve MDB-0020's original outcome/criteria and
expand it into two required children, MDB-0021 pure representation/consistency and
MDB-0022 actual local publication/reopen. Each inherits MDB-0006 and MDB-0019;
publication also depends on this child. MDB-0020 and MDB-0007 remain expanded;
MDB-0008 retains its dependency on the whole MDB-0007 milestone. No parent is
closed by this format result and no local manifest generation is selected here.

Implement immutable `CheckpointManifest`, nested prefix/reference values,
`ManifestId`, typed `ManifestFormatException`, `ManifestCodec` and
`CheckpointVerifier`. Keep positive physical generation distinct from temporal
sequence. Require unique segment IDs covering exactly the full `1..cutoff` range;
empty genesis binds the lineage header with no segments. Preserve exact segment
IDs, encoded lengths, ranges and a journal-prefix sequence/byte length/SHA-256.

The v1 manifest reuses the exact journal lineage header, adds generation/prefix/
count fields and header CRC32C, stores fixed 56-byte references, appends SHA-256
of header+references, and separately hashes the complete image including that
footer for ManifestId. Decode exactly one bounded complete object with no partial
result; enforce 1 MiB, 4,096 refs, existing per-segment size/count limits and a
conservative derived long prefix-byte bound. Constructors enforce immutable list,
coverage and identity shape; the codec enforces representation/resource limits.

Whole-set verification resolves one complete image at a time, verifies exact
length/ID/lineage/range, rejects request-ID reuse across segment boundaries, and
hashes the exact reconstructed journal header/original frames incrementally.
Compare final byte length and prefix SHA-256. Retain the global request-ID set;
state that heap/throughput and resolver resource use are not bounded by byte limits.
Missing/consistency failures are manifest errors, corrupt/mismatched dependencies
propagate segment errors, and checked source I/O errors propagate unchanged.
The supplied graph and reconstructed prefix are verified; an actual journal source,
force receipt, namespace publication and reclamation permission are not supplied.

**Primary research and review:** freshly recheck all twelve Central metadata
sources and official Maven/plugin, JUnit and Jackson compatibility on October 28.
Existing stable pins remain latest compatible selections; Maven/plugin 4.x preview
entries remain unselected. POM changes only its description. Standard JDK SHA-256,
CRC32C and collection/buffer primitives add no dependency. Refresh Lucene
SegmentInfos/Directory, Quickwit checkpoint publication and XTDB finalized/log
alignment evidence. ADR 0011 distinguishes those primary facts from MeridianDB's
field/hash/format choices. Future publication still needs explicit writer ownership,
actual stored-prefix verification, atomic visibility and namespace/platform evidence.
Independent design/production review found no actionable defect. Public review
requested explicit stable manifest-input ownership and updated suite totals; both
are reconciled in source Javadoc, public specification and all current callers.

**Validation:** focused `./mvnw -B -ntp -Dtest=ManifestCodecTest,CheckpointVerifierTest test`
passes 45 cases on its first run: 25 codec and 20 whole-set cases. Full
`./mvnw -B -ntp verify` passes 246 tests with zero failures, errors or skips,
preserving the previous 201 temporal/reference/engine/journal/segment cases.
Independent literal nonempty/genesis goldens are 192/136 bytes, with separate
prefix/footer/full-object hashes. Check every truncated prefix (328 cases across
the two fixtures) and every byte mutation in the nonempty golden, re-signed nested
versions/framing/fields, gaps/overlaps/reordering/duplicate refs, actual 4,096-ref
round-trip and the next count, lineage/prefix/per-object/whole-image bounds,
overflow/generation extremes and input/output/list ownership. Whole-set tests use
an independent journal-prefix writer, exact Unicode/lone surrogates, operation
order, no-ops, finite/unbounded bounds, complete golden dependency, sequential
resolution, missing/changed/wrong-lineage/range/length objects, identical/conflicting
cross-segment request reuse, wrong prefix length/hash, genesis, no source calls for
invalid metadata and unchanged propagation/stopping after I/O/segment failures.
Canonical temporal fixtures and seed corpus remain unchanged. This is no stored-
journal, filesystem publication, power-loss or durable-engine conformance result.

All five Java examples pass, including the new in-memory two-segment manifest:
generation 7 covers prefix sequence 2, independently of temporal commit numbering.
CI runs each example as an independent Linux/Windows step and triggers on the
manifest specification. The local JAR has 67 Java25 production classes with no
bundled oracle/fixtures/seeds/JUnit/Jackson. SHA-256 is
`2b229e7a985629fc198d315e694c3969025ffa8cb4e82378a49054ca746d1c84`.
This is local artifact evidence, not a fresh reproducibility or performance test.

**Documentation and source handoff:** add the public manifest specification and
ADR 0011; reconcile README, home/architecture/research/technical roadmap,
build/reference/engine guides, existing segment spec/ADRs/adapter guide, decision
index, navigation, CI and agent rules. Public copy contains behavior/technical
evidence; task decomposition and receipts stay under `internal/`. Both checked
URL-layout builds pass with 28 public pages and source/HTML/link/search/sitemap QA.
Local HTTP/browser preview verifies the page, layout table, navigation and search;
“reconstructed prefix” returns the manifest fixture section and affected public
callers. Theme/assets are unchanged; no new dark/mobile claim is made. Validate
queue IDs, parent/dependency references/cycles and repository links; inspect staged
scope before commit. Scratch outputs remain ignored under `.local-qa/mdb-0021/`.

**Prerequisite and delivery:** `9c5ae4b3d065495edb7a5b6a411382921981f08a` is on
remote main with Java Linux/Windows/four examples, documentation checks and Pages
success. This completion uses `Step-ID: MDB-0021` and `Step-Result: completed`;
Git supplies SHA/remote ancestry. The final handoff reports actual push, CI,
automatic Pages and live content/search/sitemap verification. No library artifact
is uploaded or released, and no other infrastructure is deployed.

**Next recommendation:** MDB-0022 — local checkpoint publication and reopen,
including actual stored-journal-prefix verification, writer/generation ownership,
file and namespace force, atomic selection and failure/lifetime checks. Preserve
all original MDB-0020/MDB-0007 criteria before closing either parent.

## 2026-10-28 — MDB-0022 Local checkpoint publication and reopen

**Outcome:** complete MDB-0022 and the original MDB-0020/MDB-0007 integration
criteria. Add `LocalCheckpointStore`, `LocalCheckpointReader`, actual owner-channel
`LocalJournal.prefix` and a sixth runnable example. Publish immutable generation
names rather than a mutable head pointer: select the highest unique generation,
verify complete manifest/dependencies and actual journal bytes, then force journal,
segments and dependency names, force/close a create-only pending manifest, atomically
rename and force the directory before success. No non-atomic fallback. Capability
probing precedes store-file mutation; exact next generation, monotonic cutoff and
capacity checks precede publication effects. Keep pending evidence, old generations,
segments and journal history. Read-only metadata/heap readers have explicit lifetimes.

**Research and review:** freshly check all twelve Maven Central metadata sources and
official Maven/plugin/JUnit/Jackson compatibility on October 28; existing stable
pins remain latest compatible selections. POM changes only its description. JDK
file/channel/digest primitives add no dependency. Refresh JDK force/move/lock,
Linux directory fsync and Lucene SegmentInfos/Directory primary evidence; ADR 0012
distinguishes these facts from MeridianDB's selection/protocol choices. Independent
review found and resolved capacity reservation before pending creation and two
same-file lock hazards: reject segment/manifest aliases of the owned journal or
store lock before reading/forcing, and reject a preexisting lock name hard-linked
to the journal before opening its channel. Separate-process tests verify owner
locks survive both rejected paths. Final code review reports no other concrete
finding; independent public-documentation review reports no actionable finding.

**Validation:** the initial focused compilation exposed a conditional `assertThrows`
generic-type mismatch; explicit exact-type branches fixed the test without weakening
its assertions. Nineteen prefix cases pass; the next integrated run passes 57 cases.
After the additional lock-acquisition regression, final `./mvnw -B -ntp verify`
passes **304 tests, zero failures/errors/skips**, preserving all previous 246 cases:
19 new owner-prefix and 39 publication/process cases (34 store, five process).
Coverage includes exact raw prefix hashes and earlier/genesis cutoffs without a
second handle or force; corrupt/short/stalled/changed input and prefix bounds;
strict highest-generation selection, no fallback/adoption, actual prefix and complete
dependency mismatches; before/after-rename failures, final directory-force uncertainty,
poison/reopen, capacity boundaries, close/reentrant operations and reader/child
lifetimes. Deterministic capability failure leaves directory contents unchanged.
Real process checks cover competing owners, rejected same-JVM acquisitions,
hard-link alias rejection and `Runtime.halt` immediately after successful publication
followed by exact reopen. Canonical fixtures and the seed corpus remain unchanged.

All six examples pass individually. The checkpoint example publishes generation one
through transaction two and reopens exact history. Local Darwin arm64 provider
accepts directory-channel force and atomic rename; all real-file tests run without
skips. Unsupported providers explicitly fail the initial directory gate; Linux and
Windows CI report their actual capability-dependent outcomes. This is no power-loss,
remote-filesystem, performance or durable-engine conformance claim. The local JAR
has 81 Java25 production classes with no bundled oracle, fixtures, seeds, JUnit or
Jackson; SHA-256 `6afe8a234b6346cbcde99a3371b1319361ec24571dd80d3a012720021e92ac29`.
An initial scratch artifact-name scan matched the legitimate `SegmentReference`
class; the corrected package/path audit passes. No artifact is uploaded or released.

**Documentation and handoff:** add the local checkpoint guide and ADR 0012; reconcile
README, home/architecture/research/roadmap, build/reference/engine/journal/segment/
manifest guides, current ADR statuses, decision index, navigation, CI and agent rules.
Keep execution history under `internal/`. Both apex and GitHub project-path shared
builds pass source, rendered HTML, links, search and sitemap checks with **30 public
pages**. Local HTTP/browser QA verifies navigation, the filename table and search:
`UncertainPublicationException` returns the guide's failures section. Theme/assets
are unchanged; there is no new dark/narrow-viewport claim. Check unique queue IDs,
references/cycles, completed-parent criteria, local links and staged task scope.
Scratch logs and reports remain ignored under `.local-qa/mdb-0022/`.

**Prerequisite and delivery:** prior completion
`8317ea8b06273d3d3cb12ea079f7400a8d27bf92` is verified on remote main with Java
Linux/Windows/five examples, documentation checks and automatic Pages success.
This completion uses `Step-ID: MDB-0022` and `Step-Result: completed`; Git supplies
its SHA/remote ancestry. The final handoff reports actual push, CI, Pages and live
content/search/sitemap checks. No Cloudflare or other infrastructure deployment.

**Next recommendation:** MDB-0008 — durable engine commits, reopen and recovery.
Refine its broad scope into bounded children while preserving acknowledged-commit,
uncertain-outcome, retry identity and temporal/reference recovery acceptance criteria.
The embedded engine remains memory-only until that integration is verified.

## 2026-10-28 — MDB-0023 Local database recovery

**Outcome and decomposition:** expand MDB-0008 without reducing its original
criteria: MDB-0023 restores local temporal reads; MDB-0024 adds forced commit
acknowledgement and atomic query publication; MDB-0025 verifies crash/retry and
canonical/seeded durable conformance. Complete only MDB-0023 in this iteration.
Add `LocalDatabase.open`, private seeded `InMemoryEngine` construction and exact
original-record restore. Verify the existing highest selected checkpoint against
actual owned journal bytes, replay its prefix once, then apply only the journal
suffix into private interval state. Retain stored lineage, all historical cutoffs,
original requests and request positions, including no-ops. Expose head, optional
checkpoint cutoff, request lookup, views and close only after complete success.
No engine transaction API, automatic repair or reclamation is added.

**Research and review:** freshly fetch all twelve Central metadata sources and
recheck official Maven/JUnit/Jackson compatibility on October 28 at 23:20 UTC.
Existing exact stable pins remain current compatible selections; preview Maven/
plugin 4.x metadata stays unselected. POM changes only its description; JDK file,
collection and engine primitives add no dependency. Refresh XTDB finalized-block/
log alignment and out-of-sync refusal, Lucene selected-generation/commit metadata,
and JDK file-lock/force evidence. ADR 0013 separates primary facts from MeridianDB
replay/API choices. Review caught an important wording boundary: `Store.open`
retains its directory-force capability probe and writable journal/lock handles.
Logical read-only recovery promises no journal/segment data force, append, truncate,
publication or deletion; it is not a read-only-filesystem API. Surviving suffix
bytes establish no earlier force or acknowledgement. Production review found no
concrete replay, ownership, cleanup or partial-exposure defect. Public review found
one stale research-table status; qualify read integration versus planned durable
writes at that existing caller. A missing-journal test setup issue was fixed before
Maven validation. Browser QA prompted wrapping the usage example's long query line.

**Validation:** `./mvnw -B -ntp -Dtest=LocalDatabaseTest,LocalRecoveryFixtureTest test`
passes all 37 new cases on its first run: 22 focused and 15 dynamic fixture cases.
Final `./mvnw -B -ntp verify` passes **341 tests, zero failures/errors/skips**,
preserving the previous 304 cases. Each of five unchanged canonical histories runs
through journal-only, genesis-checkpoint and first-commit-checkpoint plus journal
tail, retaining every intermediate head/future-cutoff expectation: 84 per shape,
252 exact expectations total, plus complete final historical query/request sweeps.
Test-only ReferenceModel decides fixture write acceptance; observed reads always
release writer handles and reopen production `LocalDatabase`. The oracle, original
fixtures and seed corpus remain unchanged and test-only.

Focused cases cover checkpoint-two/tail-four and journal-only exact point/scan/sum
answers, Unicode lineage, genesis/no-op identity, checkpoint cutoff versus recovered
head, unchanged storage/pending evidence, both ownership locks, absent journal,
incomplete tail rejection without repair, corrupt selected manifest, missing segment,
actual prefix mismatch and failure ownership release. Injected source cases cover
prefix/suffix ordering/cardinality/cutoff bounds, duplicate stored identity instead
of retries, partial replay I/O, primary/suppressed cleanup failure, ownership until
close, view invalidation before close I/O and retryable resource cleanup. Eight
bounded concurrent readers share a recovered view over 64 rounds with explicit
point/scan/aggregate answers. No new failing semantic seed or production defect.

All seven examples pass independently. The new recovery example prepares checkpoint
two plus a later retraction, then reopens historical/current reads at head three.
Local Darwin arm64 supports the inherited directory probe; real-file cases run
without skips. Linux/Windows CI separately reports capability-dependent outcomes.
These checks do not establish engine write acknowledgement, crash/retry conformance,
power-loss or performance guarantees. Recovery retains all interval snapshots and
requests, copies maps and performs multiple bounded-object sequential passes;
total heap/replay work grows with history. The local JAR has 85 Java25 production
classes with no bundled oracle, fixtures, seeds, JUnit or Jackson. SHA-256 is
`20ddfa8d3df9c08389b3a9dcb72d09efdbc12c4e4ab5bab73f2f9c930a11e55d`.
No artifact is uploaded or released.

**Documentation and handoff:** add the local recovery guide and ADR 0013; reconcile
README, home, architecture/research/roadmap, current build/reference/engine/journal/
checkpoint/format guidance, relevant ADR callers, decision index, navigation, CI
and agent rules. Keep execution narration in `internal/`. Both apex and GitHub
project-path shared builds pass source, HTML, links, search and sitemap checks
with **32 public pages**. Local HTTP/browser QA verifies the usage example, navigation
and `checkpointPosition` search result in the new guide. Theme/assets are unchanged;
there is no new dark/narrow-viewport claim. Validate queue IDs, parent/dependency
references/cycles, completed-parent criteria, source-ready successor and repository
Markdown links; inspect only task-owned staged scope. Scratch outputs stay ignored
under `.local-qa/mdb-0023/`.

**Prerequisite and delivery:** prior `9a297c2d5253e5b89fee73d807920baa340048f0`
is verified on remote main with Java Linux/Windows/six examples, documentation
checks and automatic Pages success; its live QA covered 30 pages/search/sitemap.
This completion uses `Step-ID: MDB-0023` and `Step-Result: completed`; Git supplies
its exact SHA/remote ancestry. Final handoff reports actual push, CI, automatic
Pages and live content/search/sitemap results. No Cloudflare or other infrastructure
deployment. MDB-0008 remains expanded until both remaining children and its full
acknowledgement/recovery criteria pass.

**Next recommendation:** MDB-0024 — forced commit publication and acknowledgement.
Define and implement creation plus prepare/append/force/atomic-publication ordering,
explicit uncertain outcomes and pinned-view/retry behavior over recovered state.
Keep crash/retry conformance as required MDB-0025 rather than closing the parent early.

## 2026-10-28 — MDB-0024 Forced commit publication and acknowledgement

**Outcome:** complete the selected forced-write protocol without closing MDB-0008.
Add `LocalDatabase.create`, `openWritable` and `transact`; retain plain `open` as
logically read-only. Split memory-engine commits into private immutable preparation
and guarded one-reference publication. Prepare values/history/request metadata,
position and result and preflight encoding before the append attempt. Append and
force through the owner journal before publishing, then return the existing result.
Serialize writers and close while readers keep the previous committed state during
force. Exact retries require no new I/O only after writable startup synchronization
or an earlier acknowledged commit. Conflicting IDs and pure encoding failures leave
storage/head unchanged. No-op requests still commit.

Store `initialize` accepts only a fresh namespace (own lock file allowed), creates
the owned journal and publishes genesis generation one at cutoff zero through the
existing force/atomic-rename/directory protocol. Creation validates lineage before
store mutation, retains failed candidates and closes acquired owners. Store
`synchronizeJournal` forces the actual owned journal then its directory. Writable
reopen performs complete hidden recovery and this barrier before exposing any
writer, re-establishing force for recovered complete history without inventing an
earlier acknowledgement. Per-transaction appends need no new namespace rename.
Checkpoint cutoff does not auto-advance; exclusive ownership blocks external
checkpoint publication until close. No repair, truncation or reclamation is added.

Mark uncertainty before append: I/O/runtime failure from append, force or publication
poisons the instance/views, retains source locks and reports request/proposed-position
metadata in `UncertainCommitException`. Errors also poison before propagating.
Close/reopen resolves complete stored request identity; exceptions do not establish
rollback. Resource cleanup remains retryable and never implicitly forces or revokes
a previously successful acknowledgement. Valid publication allocates no new state
or result after force. Journal adapter byte-offset/size guards remain inside the
conservatively uncertain append call; record-format preflight precedes that call.

**Research and review:** freshly recheck all twelve Central metadata sources and
Maven/JUnit/Jackson compatibility on October 28 at 23:37 UTC. Existing exact stable
pins remain latest compatible selections; preview Maven/plugin 4.x entries remain
unselected. POM changes only its description; no dependency added. Refresh JDK
force/locks, Linux file-versus-directory fsync, Lucene flush/commit and device
qualifications, Elasticsearch request translog synchronization and RocksDB synchronous
WAL evidence. ADR 0014 distinguishes those facts from MeridianDB's design choices.
Explicitly qualify forcing previously dirty data on a reopened file by actual
provider/OS/device behavior; surviving bytes prove no earlier force/acknowledgement.
Static production review found no actionable preparation/publication, ownership,
cleanup or partial-visibility defect. The initial strict compilation rejected
nonserializable exception metadata fields; store strings/sequence and reconstruct
immutable accessor values, preserving `-Xlint:all -Werror` without suppression.

**Validation:** focused `./mvnw -B -ntp -Dtest=LocalCommitTest,PreparedCommitTest,LocalInitializationTest test`
passes all 49 cases on its first test run: 24 commit, five preparation and 20
initialization cases. Final `./mvnw -B -ntp verify` passes **390 tests with zero
failures/errors/skips**, preserving all prior 341 cases, original canonical fixture
and seed sources. Five preparation checks prove values/history/request IDs stay
hidden, future cutoff rejection, same allocated result at publication, stale/foreign
holders, exact replay/conflict behavior and closed-engine rejection. Initialization
covers real genesis/reopen, invalid metadata before candidate creation, every
nonfresh entry rejection, own-lock allowance, before/after-directory/rename failures,
partial write/runtime/error evidence, released journal ownership, owner-path guard,
force-before-directory ordering and failed force/sync poisoning.

Commit checks cover real forced creation/write/reopen, preserved lineage and
request positions, no overwrite, read-only rejection, no-op identity and pinned
views. Injected sources cover hidden startup sync/cleanup/suppression, no-I/O exact
retries, pure encoding rejection, append failures before and after an effect,
force failures, runtime/error poisoning, retained ownership, close retry, reopen
resolution and rejected reentrant transact/close. Bounded concurrency demonstrates
old state while force waits, whole cross-entity state after publication, serialized
close, simultaneous identical retries and contiguous distinct-writer commits.
All eight examples pass individually; the new example forces commits one/two,
retains an older salary view, reopens and returns retry commit two with unchanged
head. Darwin arm64 real-file cases run without skips. Linux/Windows CI separately
reports actual capabilities; unsupported directory force remains explicit.

The local JAR has 88 Java25 production classes, with no bundled oracle, fixtures,
seeds, JUnit or Jackson. SHA-256:
`f24ee1310faa470e041fa4afe952caa7c82ef2633bb5f3f0b4eb064acea58839`.
This is local artifact evidence, not a release or measured performance result.
No new process crash/retry or seeded durable conformance is claimed; MDB-0025
retains those required checks and MDB-0008 stays expanded. Device power loss,
remote-filesystem guarantees, group commit and automatic checkpoint scheduling
remain outside current evidence.

**Documentation and handoff:** add ADR 0014, rewrite the local database guide with
explicit modes and acknowledgement/uncertainty/lifetime costs, add store initialization
and startup synchronization guidance and the eighth example/CI step. Reconcile
README, home/technical roadmap, architecture/research, concepts/contributing,
build/reference/engine/journal/checkpoint/format guides, current ADR statuses,
index/navigation, workflow and agent rules. Public review found accurate new pages
but stale older future/absence claims; update their existing callers, preserving
historical receipts and separating implemented forced writes from pending crash
conformance. Both apex and project-path shared builds pass source, HTML, links,
search and sitemap checks with **33 pages**. Local HTTP/browser QA verifies the
mode table, usage example/navigation and `UncertainCommitException` results in the
guide and ADR. Theme/assets unchanged; no new dark/narrow-viewport claim. Validate
25 queue IDs, references/cycles, completed-parent criteria, only MDB-0025 ready
and repository Markdown links; inspect task-owned staged scope. Scratch evidence
stays ignored under `.local-qa/mdb-0024/`.

**Prerequisite and delivery:** prior `eddaa238f86c80ba89b0ab7651e5749742bd1cf2`
is verified on remote main with Java Linux/Windows/seven examples, documentation
checks and automatic Pages success; its handoff verified 32 live pages/search/sitemap.
This completion uses `Step-ID: MDB-0024` and `Step-Result: completed`; Git supplies
the SHA and remote ancestry. Final handoff reports actual push, CI, Pages and live
content/search/sitemap checks. No library release/upload, Cloudflare or other
infrastructure deployment.

**Next recommendation:** MDB-0025 — durable crash, retry and temporal conformance.
Exercise process termination at creation/append/force/publication/acknowledgement/
checkpoint/reopen boundaries and canonical/seeded histories with exact retry
outcomes. Preserve deterministic failures and provider assumptions; close MDB-0008
only after this required child and all original criteria pass.

## 2026-10-28 — MDB-0025 Durable crash, retry and temporal conformance

**Outcome:** complete MDB-0025 and parent MDB-0008 without changing their original
criteria. Add package-private per-instance boundary observers to the real local
database and checkpoint publisher; public factories install no observer. There is
no global fault state, new public API or dependency. Cleanup/operating guards and
uncertain-outcome behavior remain intact. Independent review verified resource
lifetime, null-before-acquire validation, reentrancy and publication ordering.

**Acceptance evidence:** five new test classes add 50 invocations:

- `LocalDatabaseCrashTest`: ten real child-JVM exits across before append, after
  append, after force, after publication, acknowledged return, writable/read-only
  reconstruction, startup synchronization and genesis exposure; plus one separately
  injected strict partial-frame image after owner close. Parent checks exact
  journal bytes/lineage/history, multi-entity point/scan/sum answers, original request
  positions, exact retries/conflicts and no duplicate effects. Both strict open
  modes reject/preserve the torn image; restoration is fixture-only.
- `LocalCheckpointCrashTest`: five initialization and four checkpoint publication
  exits after actual journal creation/dependency sync/candidate sync/rename/final
  directory sync. Selection stays absent/genesis before rename and complete after
  rename; pending evidence is never adopted. A nonempty checkpoint covers commit
  one with acknowledged commit two retained in the journal tail. Original bytes,
  history, prefix bindings, requests/retries and rejected recreation remain exact.
- `LocalBoundaryTest`: four observer-order, hidden startup cleanup and postpublication
  lost-acknowledgement/poison/reopen checks. Exact retries invoke no write boundary.
- `LocalDurableFixtureTest`: ten cases apply the unchanged five canonical histories
  through actual transactions and writable reopen after every accepted new write
  and retry. Genesis-only and checkpoint-after-commit-one shapes execute all 168
  literal expectations; four histories have nonempty suffixes, while the one-commit
  history has none. Final sweeps cover every historical cutoff/query/request.
- `LocalDurablePropertiesTest`: all 16 persisted seeds, 32 new transactions per seed
  (**512 new durable commits**) plus retries/conflicts, reopen after every successful
  new/replayed write, and checkpoint 1..8 after head16. Forward dense cells and the
  separate reference check point/clipped scan/exact sum answers, unbounded scans,
  extreme coordinates/values, no-ops, pinned views and complete final history.
  Assertions retain seed/history; canonical fixtures and seed corpus are unchanged.

Child logs stay outside fresh database namespaces; process timeouts and cleanup
are bounded. The 19 abrupt exits keep the OS alive; they do not simulate device
power loss, reordered/lost unsynchronized writes or remote storage guarantees.
Only explicit `DirectorySyncUnsupportedException` permits capability-dependent
skips. This macOS/arm64 run supported the capabilities and had **zero skips**.
Earlier fault-injection/concurrency checks still pass, including immutable pinned
views during concurrent writes. The original MDB-0008 criteria are satisfied.

**Research:** freshly fetch all twelve Central metadata sources on October 28
Toronto / October 29 00:23:56 UTC and reopen official Maven/JUnit/Jackson compatibility
sources. Every selected stable version remains latest stable and Java25-compatible;
Maven4/plugin4 RC/beta metadata is deliberately not selected. No POM/wrapper change.
Refresh JDK force/lock, RocksDB synchronous-write and SQLite crash/atomic-commit
sources; the new public guide distinguishes actual process exits from SQLite-style
filesystem fault simulation, which MeridianDB does not implement.

**Commands and results:** focused canonical/seeded run passes 26 tests; focused five-class
conformance run passes 50; `./mvnw -B -ntp verify` passes **440 tests**, zero failures,
errors or skips. All eight existing source-file examples pass. The production JAR
contains **92 Java25 classes** (major69), excluding test-only reference/oracle,
fixtures, seeds, JUnit and Jackson. SHA-256:
`bc961589e956b8f1c50ed46d37f04e1c79ce37b3b237c4113f1674f053da6fa6`.
This is local artifact evidence, with no release/upload or performance claim.

**Documentation and rules:** add the navigable durability-conformance guide with
boundary tables, reproduction commands, exact coverage and provider/device limits.
Reconcile README, home/roadmap, architecture/research/format guides, concepts,
contributing, build/reference/engine/local guides, current ADRs and index. Independent
review found stale future-tense callers and an overbroad checkpoint-tail description;
correct them at their original locations. Keep historical receipts unchanged.
Persist concrete crash-test, child-log, capability and writable-reopen procedures
in AGENTS. Both apex/project-path shared documentation builds pass source, HTML,
links, search and sitemap with **34 pages**. Local HTTP/browser QA verifies readable
tables/navigation and successful search-result anchor navigation for lost
acknowledgement. Theme/assets unchanged; no new dark/mobile claim. Validate queue
IDs/references/DAG, all completed-parent children, no active row and MDB-0009 as the
single ready item; inspect repository links and staged scope. Scratch evidence
stays ignored under `.local-qa/mdb-0025/`.

**Prerequisite and delivery:** prior `231d98800d7be53a49b3e188224edc0fe145e47c`
is verified on remote main with successful Java Linux/Windows/eight examples,
documentation checks and automatic Pages; its handoff verified 33 live pages,
search and sitemap. This completion uses `Step-ID: MDB-0025` and
`Step-Result: completed`; Git supplies its SHA and remote ancestry. Final handoff
reports actual push/CI/Pages and live verification. No library release, Cloudflare
or other infrastructure deployment. No new queue IDs are required.

**Next recommendation:** MDB-0009 — temporal query operators and interval scans.
Refine its remaining predicates/selection and ordering/bounds/resource contract
against the existing point/scan/sum baseline, then implement one bounded child if
needed. Preserve canonical/reopen conformance and research current Maven artifacts.

## 2026-10-29 — MDB-0009 Typed temporal query operators

**Outcome:** complete the original query-operator card as one cohesive additive
extension. Existing Get/Scan/Sum already supply entity lookup, interval scans and
correction-sensitive exact aggregation. Add six signed numeric comparison operators
as immutable `ValuePredicate`, explicit-candidate `Select`, `FilterScan`, `EntityValue`
and immutable `Rows`. Capture one snapshot before resolving values and evaluating
predicates; absence fails every operator, including NE. Sort entity rows by exact
UTF-16 String.compareTo order, independent of input/hash/locale/normalization. Keep
excluded/absent scan ranges as gaps, preserving clipping/coalescing/coordinate limits.
No callback predicates, index, SQL, pagination or total query-memory/work budget.
No child IDs or changed parent outcomes are needed for this bounded extension.

Production reuses the ordinary scan clipping loop and interval state. Query additions
change no journal, segment, manifest or retry bytes and retain the original five
canonical histories and seed corpus. Results are eager immutable heap values with
no file/cursor handle; existing view/owner close semantics remain intact. Independent
production review found no correctness/resource issue. New public docs and the
updated temporal contract own exact semantics, scope and costs.

**Acceptance evidence:** 55 new invocations across four classes:

- `QueryTypesTest`: six signed comparison truth tables with min/max operands plus
  two validation/ownership checks. Candidate lists/results are copied, duplicates
  and null arguments reject, and returned rows are immutable.
- `QueryLifecycleTest`: three focused checks for correction/retraction precedence,
  NE absence, no revived earlier matches, immutable results after close, filtered
  gaps and finite/unbounded extremes. A 64-round barrier-coordinated writer/reader
  check verifies Select's atomic multi-entity answers and FilterScan at the same
  pinned cutoff while preserving an old view. Timed waits bound the test; this is
  real overlap evidence rather than exhaustive schedule exploration.
- `QueryFixtureTest`: three new literal histories / **90 expectations** in the
  separately versioned query corpus, each executed by reference, memory, durable
  genesis and durable nonempty-checkpoint-plus-tail (12 cases / **360 assertions**).
  All three histories retain later writes after checkpoint one. Actual database
  writes determine acceptance and every successful write/retry closes/reopens the
  writer. Comparisons, absence, corrections/retractions/no-ops/retries, clipping,
  ordering and malformed predicates/operands are exact expected results.
- `QueryPropertiesTest`: separate 16-seed memory and 16-seed durable methods,
  **512 new transactions per target** plus retries. Forward dense cells provide
  independent selected rows and clipped scan runs; the separate backward-history
  reference uses BigInteger comparison, without production predicate evaluation.
  All six operators, five thresholds, finite-grid/extreme coordinates and values,
  exact UTF-16 identities (including zero/lone surrogates), historical cutoffs and
  unbounded windows are exercised. Every durable new write closes/reopens; after
  head16, checkpoint1..8 is published with suffix9..16 retained. Pinned views remain
  correct during writes and invalidate at owner close. Failing diagnostics retain
  seed/history; `meridiandb.seed` overrides both target methods. Local capability
  skips affect only durable cases, preserving memory/reference evidence.

No failed semantic seed was found. Focused 55-case query verification and full
`./mvnw -B -ntp verify` pass: **495 tests**, zero failures/errors/skips on this
macOS/arm64 provider. All **nine examples** pass, including `QueryExample` checking
selection, filtered interval and exact aggregate answers before/after a correction
and retraction. Linux/Windows Java CI now invokes that ninth example. Production
JAR inspection finds **98 Java25 classes**, major69, with no reference/oracle,
fixtures, seeds, JUnit or Jackson. SHA-256:
`60ad52f8f6607029936db56dd16721336a6b6b1993d558ec4fcf4f4dfedba2c9`.
This is local artifact evidence, not a release or performance claim.

**Fresh research:** all twelve Central metadata sources fetched no-cache October 29,
08:31 Toronto / 12:31 UTC, with current official Maven/JUnit/Jackson compatibility.
Selected versions remain latest stable and Java 25 compatible; Maven/plugin 4 RC/beta
entries remain unselected. POM adds only the exact new test-resource filename,
with no dependency or wrapper version change. Refresh XTDB explicit valid/system
basis and clipped validity, Datomic immutable values, and JDK signed comparison/
String ordering sources for ADR 0015; distinguish design inference from inherited
semantics or guarantees.

**Documentation and planning:** add ADR 0015, query guide, fixture download and
navigation. Reconcile README, home/roadmap, temporal contract, contributing,
build/reference/engine/local guides, decision index/current temporal decision and
AGENTS. Persist resolved-before-filter, absence/order/gap/resource/independent-test
rules at the authoritative query guide. Final review found one blank line breaking
the decision table; correct it at its original location and rebuild both layouts.
Both shared apex/project-path builds pass source, rendered HTML, links, search and
sitemap with **36 pages**. Local browser QA confirms the operator/predicate tables,
Java example/navigation, FilterScan search-result anchor and ADR 0015 as an actual
row in the decision table. Theme/assets unchanged; no dark/mobile claim. MDB-0009
is done, MDB-0010 ready, with all 25 IDs/parents/dependencies/DAG and completed-child
criteria validated; repository Markdown paths and staged scope checked. Scratch
logs/metrics/live-verification preparation stay ignored under `.local-qa/mdb-0009/`.

**Prerequisite and delivery:** previous `943dff4bd1d0efc05da96048402e586eeea18a43`
is verified on remote main with successful Java Linux/Windows/eight examples,
documentation checks and automatic Pages; its handoff verified 34 live pages/search/
sitemap. This completion uses `Step-ID: MDB-0009` and `Step-Result: completed`; Git
supplies its SHA/remote ancestry. Final handoff reports actual source push, CI,
automatic Pages and exact live content/search/sitemap. No library release/upload,
Cloudflare or other infrastructure change.

**Next recommendation:** MDB-0010 — history-preserving compaction and reclamation.
Refine its retention/reader-lifetime and safe publication/reclamation requirements
into bounded children before implementation. Preserve all historical query results,
request identities and durable recovery; deleting logical history needs a separately
defined retention contract. A recommendation does not begin that work.

## 2026-10-29 — MDB-0026 Bounded pure history compaction

**Refinement and scope:** expand MDB-0010 without changing its original outcome or
completion criteria. Add required children MDB-0026 (retention contract and pure
bounded repacking), MDB-0027 (actual local installation/publication), MDB-0028
(cross-process reader protection and physical reclamation) and MDB-0029 (integrated
crash/temporal/space conformance). Preserve external prerequisite MDB-0009 and
existing MDB-0011 dependency on the parent. Complete only MDB-0026 in this invocation;
MDB-0010 remains expanded. Retain every logical transaction, cutoff, request identity
and the complete journal. Default physical retention also remains unchanged.

**Implementation:** add pure `HistoryCompactor` with explicit aggregate input/output
segment-byte and record budgets, hard complete-output targets, deterministic greedy
whole-record packing and immutable private-constructed proposals. Defaults/hard caps
are 32 MiB output target, 64 MiB input, 64 MiB output and 262,144 history records;
existing per-segment 65,536 record / 32 MiB and 4,096 manifest-reference limits remain.
Check declared aggregate input/record bounds and generation exhaustion before any
source read. One internal collector shares complete checkpoint verification rather
than resolving mutable sources twice. All collected work is private; no Plan escapes
before every expected ID/length/lineage/range, global request uniqueness and final
reconstructed prefix length/hash pass. Each source reference is read once.

Preserve exact original record fields and frames, including no-ops, retractions,
operation order, UTF-16 identities and finite/unbounded endpoints. Split only between
whole transactions; an indivisible target overflow fails explicitly. Output encoding
checks budget before image allocation. Plan metadata proposes source generation +1
with the identical lineage/prefix/cutoff. Genesis remains empty. Add exact codec
`encodedBytes` validation without output-image allocation; existing v1 bytes remain
unchanged. Plan output owns immutable values without aliasing source arrays and
reports encoded segment totals, excluding metadata/journal/filesystem/retained files.
Serialized-byte limits are not exact JVM heap guarantees. No file creation, actual
journal verification, publication, deletion or new external dependency is added.

**Research/review:** freshly fetch all twelve Central metadata sources October 29,
08:54–08:55 Toronto / 12:54–12:55 UTC, retrying one transient Surefire DNS failure.
All selected versions remain latest stable and Java 25 compatible after official
Maven/JUnit/Jackson checks; Maven/plugin 4 RC/beta entries remain unselected. POM and
wrapper are unchanged. Refresh primary Lucene deletion/snapshot policy, RocksDB
snapshot, Kafka compaction and XTDB history/recovery evidence. Record why latest-key
retention is incompatible, and why process-local pins/timers cannot protect a lazy
reader after store close. Later physical reclamation must close cross-process reader
admission versus deletion, preserve live roots and serialize pin release with lazy
opens. Existing content-name reuse must verify complete bytes; current create-only
`writeSegment` is not a reuse operation. Independent production review found no
correctness/resource defect in compactor, verifier sharing or codec extraction.

**Verification:** focused `HistoryCompactorTest,CompactionConformanceTest,SegmentCodecTest,CheckpointVerifierTest`
passes **104 tests**. Full `./mvnw -B -ntp verify` passes **552 tests**, zero failures,
errors or skips on this macOS/arm64 run. New 57 invocations comprise:

- 33 boundary/format/budget/ownership cases: independently known 176/232 and UTF-16
  166/222 exact sizes, one-byte-under targets, maximal groups, 65,536 record split,
  4,096 output limit, zero-budget genesis, generation exhaustion, input/record
  preflight before source calls, output budgets, indivisible records, complete graph
  and late/missing/corrupt/source failure, deterministic/reused IDs and array/list
  ownership. Errors return no accepted partial plan.
- Eight original/query fixture histories execute all **174 literal expectations
  before and after** repacking (**348 assertions**), then sweep every valid fixture
  query at every stored cutoff and check retry/lookup identities. Rebuilt new commits
  consume compacted output records; fixture requests are compared against that output,
  while retry/conflict calls exercise the rebuilt engine's identity map.
- Sixteen persisted seeds provide 32 new transactions each (**512 original commits**),
  repack single-record source objects at a 1,024-byte target and rebuild solely from
  output. Forward dense snapshots and independent reference compare point/exact-sum/
  selection/filtered-scan results across sampled historical cutoffs, with exact flattened
  records/prefix bytes, request retries, bounds and aggregate encoded accounting.
  Original fixtures and seed corpus are unchanged; seed overrides remain reproducible.

All **ten examples** pass. `CompactionExample` verifies replacement graph/exact history,
merges two in-memory segments / **430 bytes** into one / **302 bytes**, and proposes
physical generation eight at unchanged cutoff two. The 128-byte difference is repeated
segment overhead, not reclaimed disk space. CI invokes this tenth example on both
Linux and Windows. Production JAR inspection finds **102 Java 25 classes**, major69,
with no oracle/reference, fixture, seed, JUnit or Jackson content. SHA-256:
`7298a556f58d5eeb6240674d2fa464655f86e67aaf69a4fdaaba1d7c0405774e`.
No artifact release/upload or performance claim.

**Documentation and planning:** add ADR 0016 and the compaction guide, navigate both,
and reconcile README, home/roadmap, architecture/research/segment-format, build,
reference/engine and local checkpoint/segment guides plus decision index and AGENTS.
Persist exact-history/budget/stage/reader obligations, distinguishing complete-journal
retention from conditional obsolete-object removal. Public review corrected reversed
retention-tradeoff wording and count punctuation; no chat/task narrative is published.
Both URL-layout shared builds pass source/HTML/links/search/sitemap with **38 pages**.
Local browser QA confirms retention/resource tables, example/navigation, compactor
search-result anchor and ADR 0016 as an actual decision-table row. Theme/assets remain
unchanged; no new dark/mobile claim. Validate all 29 IDs/parents/dependencies/DAG,
completed-parent children, only MDB-0027 ready and no executable active row; repository
Markdown paths and staged task ownership pass. Scratch logs/metrics remain ignored
under `.local-qa/mdb-0026/`.

**Prerequisite and delivery:** prior `ea28984daa07ecd710eaaf5e442b42f16aac2433` is
verified on remote main with Linux/Windows Java/nine examples, documentation checks
and automatic Pages; its handoff verified 36 live pages/search/sitemap. This completion
uses `Step-ID: MDB-0026` / `Step-Result: completed`; Git supplies its SHA and remote
ancestry. Final handoff reports actual push/CI/Pages and exact live verification.
No library release, Cloudflare or other infrastructure deployment.

**Next recommendation:** MDB-0027 — install and publish verified local compaction
replacements. Recheck source generation and actual owned journal, verify/reuse
identical existing content or create-only install, then force/publish the same-cutoff
next generation. Retain full journal and all old/failed objects; reader-safe physical
reclamation and integration evidence remain MDB-0028/MDB-0029.

## 2026-10-29 — MDB-0027 Local compaction installation and publication

**Outcome:** implement `LocalCheckpointStore.publishCompaction(Plan, LocalJournal)`
for the exact currently selected source. Keep MDB-0010 expanded; complete only its
MDB-0027 child. No storage format, logical history, journal truncation, reader pin
or physical removal change. No automatic compaction scheduling or performance claim.

**Implementation:** gate source manifest ID during generation discovery before
reading an unrelated stale graph. Completely verify the matching stored source and
actual prefix through the canonical journal owner. The immutable private-constructed
plan binds its bounded history and proposed source+1 generation at the same cutoff.
Verify every existing replacement path against the exact complete encoded image
before any output write, using the namespace's regular/no-follow/non-alias checks.
Reject corrupt/truncated/wrong objects without repair. Reserve every missing segment
plus one manifest candidate and the retained-generation slot before the first create.
Create only missing objects through full-write/force/close, then reverify the complete
replacement graph and actual owner prefix.

Extract the existing verified dependency/manifest force-and-rename protocol into a
private shared method. Force the journal through its owning adapter, force all
replacement segments including reused files, force dependency names, force/close a
create-only candidate, attempt atomic rename, then force the directory. Both callers
retain the operating guard and poison after I/O/runtime/Error; attempted rename
I/O/runtime failure remains `UncertainPublicationException`. All source/old/failed
objects and the full journal are retained. Genesis and unchanged layouts explicitly
publish the next generation; repeated unchanged publication consumes generation
capacity. Store/journal ownership excludes cooperating engine writers. Existing
`writeSegment` remains create-only and rejects an existing name.

**Research/review:** freshly fetch all twelve Maven Central metadata sources on
October 29 at **10:36:14 Toronto / 14:36:14 UTC**, and recheck official Maven/JUnit/
Jackson compatibility. Selected versions remain latest stable and compatible with
Java 25; Maven/plugin 4 RC/beta versions remain unselected. No POM, wrapper or new
dependency change. Refresh Lucene Directory/SegmentInfos and JDK 25 Files/FileChannel
primary evidence in ADR 0016: separate file sync, namespace sync and generation
selection, retain exclusive ownership/destination checks for atomic rename, and
state provider/device force assumptions. Verified exact-image content reuse is a
MeridianDB design inference, not an inherited Lucene CAS guarantee.

Independent production review finds no actionable correctness/resource defect.
Documentation review corrects checkpoint cutoff versus journal forced position:
publication preserves contents but can advance the owner's force counters. Independent
test review improves crash evidence from filename existence to complete replacement
bytes/expected ID and exact pending manifest bytes/ID; link creation now fails on
ordinary permission/I/O errors instead of treating all FileSystemException cases as
unsupported. Persist those capability/crash-evidence standards in AGENTS.

**Verification:** initial focused compaction/checkpoint protocol/crash command
`./mvnw -B -ntp -Dtest=LocalCompactionPublicationTest,LocalCompactionIntegrationTest,LocalCompactionCrashTest,LocalCheckpointStoreTest,LocalCheckpointCrashTest test`
passes **94 tests**. Final `./mvnw -B -ntp verify`, after stronger evidence assertions,
passes **603 tests**, zero failures/errors/skips on macOS/arm64. The 51 new invocations:

- **34 injected protocol cases:** new/mixed/all-reused/genesis outputs, exact bytes
  and ordered forces, stale/missing source, owner lineage/prefix mismatch, damaged
  source, corrupt later reuse checked before earlier missing output creation,
  exact/insufficient directory capacity and generation exhaustion, before/after-effect
  installation/verification/force/candidate/rename faults, retained evidence, reopen
  selection, poison/Error/uncertainty, reentrancy, null/path prevalidation and close retry.
- **13 real integration cases:** two-to-one publication, old lazy readers after
  store close, unchanged-layout and preinstalled valid reuse, genesis, stale source
  ID/generation, wrong owner path/actual journal history, engine exclusion, corrupt/
  directory/symlink candidates and journal/store-lock hard-link aliases. Four bounded
  contender JVMs verify lock preservation after alias rejection. Journal raw bytes
  are inspected only after owners close.
- **Four real process exits:** halt after dependencies sync, candidate sync,
  manifest rename and final directory sync. Reopen selects only the old/new complete
  graph as appropriate, verifies installed segment expected bytes/ID and exact pending
  metadata when present, retains old manifests/segments, and preserves the complete
  journal plus uncheckpointed tail. Writable reopen checks historical/current queries,
  request lookup, identical retry and conflicting identity rejection. This is process
  termination, not power-loss simulation.

All **eleven examples** pass. New `LocalCompactionExample` publishes two segments
as one at cutoff two, verifies unchanged-layout reuse in generation three and reopens
exact two-transaction history. It retains failed-run evidence and removes only its
successful temporary output after every handle closes. Linux/Windows CI invokes the
new example. Production JAR inspection confirms **102 Java 25 classes**, major69,
with no test oracle/reference, fixture, seed, JUnit or Jackson content. SHA-256:
`8a11bf7c431711efad2749e44a282f18a4b33db3c4b9fe75849234f5780e53cf`.

**Documentation/planning:** reconcile README, home/roadmap, architecture/research,
ADR 0016/index, build/reference/engine and compaction/checkpoint guides plus AGENTS.
Explain explicit ownership, verified reuse, all-output capacity, unchanged-layout
generation cost, full retention, uncertainty and remaining reclamation/integration
work. Both URL-layout shared builds pass source/HTML/links/search/sitemap for **38
pages**. Local browser QA confirms the rendered publication section/code/list,
navigation and `publishCompaction` search result opening its exact anchor. No theme/
asset change or new dark/mobile claim. Validate all 29 queue IDs/references/DAG and
completed-parent rules, no active executable row and only MDB-0028 ready; repository
Markdown paths and diff checks pass. Scratch logs/evidence remain ignored in
`.local-qa/mdb-0027/`.

**Prerequisite/delivery:** prior `6d419787425717a15ca50acc29f3c0eeb40cc801` is on
remote main with successful Java (**40880212348**), documentation (**40880212242**)
and Pages (**40880212362**) workflows; its prior handoff verified 38 live pages,
search and sitemap. This completion uses `Step-ID: MDB-0027` / `Step-Result: completed`;
Git supplies its SHA/remote ancestry. Final handoff reports actual push, CI/Pages
and exact live content/search/sitemap verification. No library artifact release/upload,
Cloudflare or other infrastructure deployment.

**Next recommendation:** MDB-0028 — protect readers and reclaim obsolete physical
objects safely. Refine its cross-process admission/deletion and reader-lifetime work
into bounded prerequisites if needed. Preserve selected/retained/reader/backup roots,
full journal, unknown/diagnostic evidence and fail-closed newest selection. Integrated
canonical/seeded publication/removal, interrupted cleanup and physical-space evidence
remain MDB-0029; the parent remains expanded.

## 2026-10-29 — MDB-0030 Cross-process lazy-reader protection

**Refinement/outcome:** expand MDB-0028 into required MDB-0030 (live-reader
protection, this iteration) and MDB-0031 (protected physical reclamation). Retain
its original acceptance criteria, dependency chain and MDB-0029 integration gate;
MDB-0028 and MDB-0010 remain expanded. Complete only MDB-0030. Implement no deletion,
logical pruning, journal truncation, backup policy or automatic compaction scheduling.

**Protection:** add permanent empty `.meridiandb-readers.lock` and package-private
shared/exclusive barrier machinery. The store verifies the selected graph/actual
journal while holding exclusive ownership, then acquires independent shared protection
before returning a lazy reader. A future remover must acquire that store ownership
first, so it cannot race verification/admission. Empty/corrupt selection creates
no reader guard; internal publication verification manufactures no reader lease.
One canonical-path/identity registry holds one channel and reference count per
loaded JVM library copy; independent processes use shared OS locks. Require actual
shared lock support, nonblocking region(0,1) acquisition and no in-place lock upgrade.
Any live reader conservatively excludes all future reclamation. New publication
remains possible after store close while old lazy readers retain original access.

Reader close serializes with complete lazy loading, invalidates access first and
retains retryable release state. Heap children and returned metadata survive close.
Database cleanup attempts reader/journal/store independently, suppresses later
errors and preserves its retry path. Admission and cleanup failures retain evidence;
known contention differs from I/O/unsupported/uncertain outcomes. Unexpected JVM
overlap quarantines the duplicate instead of closing a potentially lock-dropping handle.

**Shared ownership and capacity:** consolidate pre-open identity reservations for
journal, store and reader controls with transient segment/checkpoint read, force,
create-only and journal-tail evidence channels. Control reservations conflict with
any same-file handle, including cross-store aliases; data/data channels may coexist.
Keep reservations through known successful channel close. Resolve real parents
before final-name handling; correct the journal's older lexical normalization of
symlink/`..` input and add an actual create/reopen regression. Reserve an absent
permanent guard slot in store acquisition, segment writes, manifest publication and
whole-compaction installation so success cannot exhaust first-reader capacity.
Existing fake namespaces explicitly model established control metadata; exact crash/
recovery snapshots now admit only the expected new empty guard while comparing every
prior byte and excluding unrelated files.

**Native-close finding:** JDK `AbstractInterruptibleChannel.close` marks a channel
closed before native cleanup and invokes native cleanup at most once. Therefore a
thrown close remains uncertain even with `isOpen() == false`; a later no-op does not
prove cleanup. Central `OwnedChannel` wrappers retain reservations and sticky
quarantine after IOException/runtime/Error or unexpected external raw close. Real
native-close uncertainty requires process restart. Exposed-reader lock-release
failure with known successful channel cleanup remains a distinct retryable logical
release. Partial admission with uncertain cleanup can leave no returned retry owner.
Persist these positive procedures in AGENTS and the linked guide/ADR.

**Research/review:** freshly fetch all twelve Central metadata sources on October 29
at **11:24:54 Toronto / 15:24:54 UTC**, and recheck official Maven/JUnit/Jackson
compatibility. All selected versions remain latest stable and Java 25 compatible;
Maven 4 RC/plugin 4 betas remain unselected. No dependency/POM/wrapper changes. Refresh
JDK 25 FileLock/FileChannel/AbstractInterruptibleChannel, Linux any-close locking,
Windows process-exit release delay and Lucene snapshot/deletion primary sources.
Record one loaded adapter copy, participating-reader rollout, legacy-reader quiescence,
permanent-name and provider/stable-path boundaries. Independent production/test review
identified and drove the cross-store alias, absent-slot, preallocation/reference,
real-parent and native-close fixes; final shared-wrapper/lock-order/lifetime review
found no additional material issue. No inherited Lucene deletion or power-loss claim.

**Verification:** initial focused reader/ownership/cleanup and adapter command
`./mvnw -B -ntp -Dtest=LocalReaderBarrierTest,LocalCheckpointReaderProtectionTest,LocalReaderProtectionIntegrationTest,LocalFileOwnershipTest,LocalResourceCleanupTest,LocalCheckpointStoreTest,LocalCompactionPublicationTest,LocalJournalTest,LocalSegmentFileTest test`
passes **209 tests** before the later native-close/path cases. Final
`./mvnw -B -ntp verify` passes **678 tests**, zero failures/errors/skips on macOS/arm64.
The **75 new invocations** comprise:

- **27 barrier protocol cases:** shared refs/exclusive deferral, distinct paths,
  hard-link alias rejection before open, shared-to-exclusive conversion rejection,
  invalid handles, acquire/release/close faults, IO/runtime/Error suppression, retained
  quarantine, retry and actual-close versus still-open fake outcomes.
- **Seven reader lifetime/concurrency cases:** logically closed state before retry,
  invalid protection before path access, corrupt segment pin retention, latches
  proving close waits for lazy load, and heap child/value survival.
- **18 shared identity/native-close cases:** canonical/hard-link/parent aliases,
  symlink/`..`, read/control conflicts, read coexistence, exact-token idempotence,
  pre-create reservation and eight-caller admission. Actual AbstractInterruptibleChannel
  fake cleanup proves Java closed state after IO/runtime/Error is insufficient;
  repeat close never retries native cleanup or releases ownership. Test-only fake
  reservations are explicitly disposed because they own no OS descriptor.
- **14 real filesystem/process cases:** same-JVM final-reference gating, retained
  reader/store/publication lifetimes, empty selection, permanent exclusive guard,
  failed graph adoption, invalid guard inputs, local/cross-store/reverse journal
  aliases, independent JVM contenders preserving prior locks, cross-process shared
  admission/new publication, explicit release and forced child exit. Sixteen bounded
  lazy-open/close races keep child snapshots valid. Logs stay outside store homes;
  no raw guard/alias channel is opened while protection is held. Unsupported required
  capabilities remain distinct from ordinary permission/I/O failures.
- **Four database cleanup cases**, **four compaction permanent-capacity regressions**
  and **one actual journal real-parent create/reopen regression**.

All **eleven examples** pass against the final code. Production JAR inspection
confirms **114 Java 25 classes**, major69, with no test oracle/reference, fixture,
seed, JUnit or Jackson content. SHA-256:
`8135facbb525f66c658801c8b835b46992e880390e8f05be7bf4a7e0ba31fd75`.
No library artifact is uploaded or released; CI retains the existing eleven example
steps on Linux/Windows. Process exit and injected cleanup are not device power loss.

**Documentation/planning:** add reader guide and ADR 0017 with actual decision-table
row/navigation, reconcile README/home/roadmap/architecture/research, build/reference/
engine, compaction/checkpoint/database/journal/segment guides and ADR 0016. Public
copy explains behavior and limitations without workflow narrative. Both URL-layout
shared builds pass source/HTML/links/search/sitemap for **40 pages**. Local browser
QA confirms capabilities table, code/navigation, exact reader-barrier search anchor
and ADR 0017 in the decision table. Theme/assets remain unchanged; no new dark/mobile
claim. Validate 31 unique queue IDs, references/DAG/parents/completed children, no
active executable row and only MDB-0031 ready; repository Markdown paths and staged
ownership/diff checks pass. Scratch logs/evidence remain ignored under `.local-qa/mdb-0030/`.

**Prerequisite/delivery:** prior `798dfce47f57279ee25336884766ff4cbf8da47e` is on
remote main with successful Java (**40895161300**), documentation (**40895161218**)
and Pages (**40895161479**) workflows; its handoff verified 38 live pages/search/sitemap.
This completion uses `Step-ID: MDB-0030` / `Step-Result: completed`; Git supplies its
SHA/remote ancestry. Final handoff reports actual push, CI/Pages and exact live
content/search/sitemap verification. No Cloudflare or other infrastructure deployment.

**Next recommendation:** MDB-0031 — reclaim obsolete objects under exclusive reader
protection. Preserve selected/retained/explicit backup roots, full journal,
ownership/barrier files and unknown/diagnostic evidence. Any live reader defers all
removal. Keep both store ownership and exclusive protection through verified root
selection, metadata retirement before unreferenced dependency removal, and directory
sync. Retain MDB-0029's integrated canonical/seeded/crash/space gate and both expanded
parents until their original criteria pass.

## 2026-10-29 — MDB-0030 Provider-aware CI repair

Initial completion `6fb7b60c0e435db13d4dec913365885944199fff` reached remote main.
Linux Java, documentation and Pages passed, and exact live QA verified 40 public
pages/search/sitemap. Windows Java run **40907411566** reported 678 invocations,
one failure, one error and 142 capability skips. Both failures were new fixtures
assuming POSIX symlink/`..` resolution: journal expected the physical sibling and
the identity fixture queried a nonexistent POSIX-selected target on Windows.

Repair only those expected paths: derive the existing target from the actual
provider's real-parent resolution, then retain same-file, control/read exclusion,
create/reopen/history and conditional distinct-neighbor checks. No new skips,
production changes, dependency updates or weakened identity guarantees. Preserve
the original source commit and native-close/capacity safeguards. Add the observed
provider distinction to AGENTS and the public reader guide, plus a delivery hold
in the live queue until repair CI passes.

Focused `./mvnw -B -ntp -Dtest=LocalFileOwnershipTest,LocalJournalTest test` passes
**54 tests**, zero failures/errors/skips locally. Full local 678/eleven-example and
JAR evidence remains the prior final-code result; production bytes are unchanged.
Both URL-layout shared docs builds pass 40-page source/HTML/link/search/sitemap QA.
Inspect the repair's Linux/Windows CI and Pages, then verify exact live content.
This repair retains `Step-ID: MDB-0030` / `Step-Result: completed`; Git supplies its
SHA and actual remote state. MDB-0031 remains the next recommendation, conditional
on resolved delivery; neither expanded parent is closed.

## 2026-10-29 — MDB-0032 Verified reclamation inventory

**Outcome and refinement:** reconcile clean main and previous repair
`37ac5b63f3db4b0f38c5047bca1c75613a7a5af9` against origin/main and successful Java
40908240454, documentation 40908240419 and Pages 40908240392. Split MDB-0031 into
MDB-0032 (this bounded inventory/retention outcome) and required MDB-0033 (ordered
protected removal/interruption). Preserve the original MDB-0031/MDB-0028 criteria
and MDB-0029's integrated semantic/crash/space gate; all three parents remain expanded.

Implement `LocalCheckpointStore.inspectReclamation` with private-constructed immutable
`ReclamationInventory`, explicit limits, exact names/lengths, selected/retained IDs,
preserved names and repeated-graph/candidate accounting. Acquire nonblocking exclusive
reader protection under store ownership; known contention returns an empty deferred
status without poisoning. Freeze caller roots, retain newest implicitly and reject
missing retained IDs. Verify every published graph and actual owned-journal prefix,
including obsolete generations, without newest fallback. Preserve legal generation gaps.
Candidates are verified obsolete metadata plus dependencies absent from every live
root. Unknown/orphan objects remain unclassified/preserved; any pending name refuses
inventory. No physical object is removed and no persistent pin is created.

Preflight aliases before metadata/segment opens, including same-directory unknown
hard links; shared identity reservations retain cross-store control exclusion. Bound
metadata reads, repeated graph bytes (64 MiB), records (262,144) and namespace
identity comparisons (1,000,000). Independent review found generic verification's
32 MiB read bound could exceed a smaller declared segment limit for corrupt input;
use an inspection-specific source capped at each declared exact length and add an
oversized-file regression. No outstanding material review finding remains. Clarify
that graph budgets exclude journal acquisition and actual prefix I/O, which retain
journal frame/cutoff bounds. Keep protection reachable after failed close, suppress
cleanup failures, attempt publisher cleanup independently and retain native-close
quarantine rules. Reuse strict generation discovery with existing selection.

**Research:** refresh all twelve no-cache Central metadata sources on October 29,
2026 at **15:18:57 Toronto / 19:18:57 UTC**, plus official compatibility. All existing
pins remain latest stable: Maven 3.10.0 (4.0.0-rc-7 metadata), wrapper distribution/
plugin 3.3.4, clean/resources 3.5.0, compiler 3.16.0, surefire 3.6.1, jar 3.5.1,
install/deploy 3.2.0, JUnit 6.1.3 and Jackson 3.2.4. Maven/plugin 4 entries remain
prereleases. Java 25 is compatible with documented Maven/JUnit/Jackson baselines.
No dependency/POM/wrapper change. Refresh primary Lucene commit-file/retention/snapshot,
JDK deletion/locking/force and namespace-sync evidence in ADR 0018. Explicit roots,
conservative diagnostics and metadata-before-dependency removal are MeridianDB
inferences, with no inherited throughput, deletion or device guarantee.

**Verification:** focused
`./mvnw -B -ntp -Dtest=LocalReclamationInventoryTest,LocalReclamationInventoryIntegrationTest,LocalCheckpointStoreTest,LocalCompactionPublicationTest,LocalInitializationTest test`
passes **159 tests** before generation-gap/read-bound additions. Initial full verify
passes 746 tests; final post-review `./mvnw -B -ntp verify` passes **747 tests**, zero
failures/errors/skips on macOS/arm64. New coverage comprises **51 unit/fault cases**
for roots/shared dependencies, genesis/empty/gaps, immutable results, bad old/new
metadata/history/prefix, pending/unknown evidence, exact and huge declared bounds,
corrupt oversized reads, malformed/duplicate/capacity names, alias budgets,
contention/reentry, validity, before/after effects and IO/runtime/Error cleanup;
plus **18 real filesystem/process cases** for exact repeated accounting, retained
roots, orphan/diagnostic bytes, wrong journal, symlinks/hard links, independent
contenders preserving both owner locks, cross-store controls, same-JVM and child-JVM
reader lifetime, fresh admission and exact budget reopening. No existing fixture,
seed or logical format changes; original writable history/retry verification passes.
Tests inspect raw owner bytes only after close. Capability-dependent skips remain
explicit unsupported-directory-sync/link boundaries rather than ordinary I/O failures.

All **twelve examples** pass locally; the new inventory example demonstrates live
reader deferral, selected-only candidates and explicit retained roots without deletion.
Linux/Windows CI runs that same twelfth example. Production JAR inspection confirms
**118 Java 25 classes**, major 69, no reference oracle, fixtures, seeds, JUnit or
Jackson content. SHA-256:
`3e9c68253a8f0433435da777f97fb3210ce415a678a8fba30535259c037cbcce`.
No artifact release/upload. Process contention is not deletion-crash or device
power-loss conformance; actual freed-space evidence still requires the remover.

**Documentation/planning:** add inventory guide and ADR 0018 with navigation/decision
table, reconcile README/home/roadmap/architecture/research, checkpoint/reader/
compaction and build/reference/engine status. Persist exact read-bound, root,
diagnostic and no-stale-deletion rules in AGENTS. Public copy explains technical
behavior without internal workflow narration. Both URL layouts use shared
source/HTML/link/search/sitemap QA for **42 pages**. Local browser inspection confirms
policy table, code/navigation, inventory search/section anchor and ADR navigation;
assets/theme unchanged, with no new dark/mobile claim. Validate 33 unique queue IDs,
references/DAG/completed children, no active item and only MDB-0033 ready; inspect
repository Markdown links and task-owned staged diff. Scratch evidence stays ignored
under `.local-qa/mdb-0032/`.

**Delivery:** commit with `Step-ID: MDB-0032` / `Step-Result: completed`, normal push
to origin/main and remote ancestry/tip checks. Git provides this commit's SHA; final
handoff records actual Java/docs/Pages outcomes and exact live content/search/sitemap
verification. No Cloudflare or other infrastructure deployment.

**Next recommendation:** MDB-0033 — remove verified obsolete objects with ordered
synchronization. Recompute under both owners, reject aliases before delete, retire
and force metadata before unreachable dependencies, preserve full journal and
unknown/diagnostic objects, and prove safe interruption/reopen/capacity behavior.
A preview is not a deletion capability. Keep MDB-0031/MDB-0028 and integrated
MDB-0029/MDB-0010 criteria open until their actual evidence passes.

## 2026-10-30 — MDB-0033 Ordered protected physical removal

**Outcome:** verify prior `2ffdfef3101b5cbd1c322a5863162ba5300c6cd0` matches remote
main with successful Java 40941178083, docs 40941178045 and Pages 40941177915.
Implement explicit `LocalCheckpointStore.reclaim` over fresh inventory under
exclusive store ownership plus nonblocking exclusive reader protection. Always
retain selected state and explicit retained/backup IDs; verify all published graphs
against the actual owned journal. Create immutable `ReclamationResult` before any
deletion effect. Known reader contention returns deferred with no partial inventory;
empty candidates return a completed empty receipt without the removal force sequence.

Force the actual journal, each retained manifest image and segment, then directory
names before first deletion, including a visible root from uncertain publication.
Strictly delete obsolete complete manifests and force the directory before any
unused dependency; remove eligible segments and sync again. Recheck protection
and require successful close before receipt. Per-delete regular/no-follow and
same-file alias checks plus a common control reservation exclude active control/data
identities without opening another native handle. Missing names fail closed; no
`deleteIfExists` or stale preview list is accepted. Preserve journal, permanent
controls, unknown/orphan/diagnostic objects; pending candidates refuse removal.

Mark uncertainty before attempting the first delete. IO/runtime failure afterward
throws `UncertainReclamationException`; Error poisons and retains its type. Preserve
failed protection for close retry and independently attempt publisher cleanup;
existing native-close quarantine remains. Reopen discovers current names and
recomputes. Interrupted retirement may orphan old dependencies, which remain
preserved without automatic sweep or persistent intent. Existing logical bytes,
history/request identities, canonical fixtures and seeds are unchanged.

**Research/review:** fresh no-cache fetch of all twelve pinned Central metadata
sources at **October 30, 23:00:40 Toronto / October 31, 03:00:40 UTC**, plus official
Maven/JUnit/Jackson compatibility. All pins remain latest stable: Maven 3.10.0
(metadata 4.0.0-rc-7), wrapper distribution/plugin 3.3.4, clean/resources 3.5.0,
compiler 3.16.0, surefire 3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3,
Jackson 3.2.4; newer Maven/plugin 4 entries remain prereleases. No POM/wrapper/
dependency change. Refresh primary JDK strict-delete/FileLock, Lucene retention/
snapshot/namespace-sync and Linux directory-sync sources. Record live-root forcing,
ordered retirement and conservative orphans as MeridianDB inferences. Independent
production review finds no material correctness issue; clarify that the inventory
comparison cap excludes additional bounded per-delete rechecks and journal I/O.

**Verification:** initial focused
`./mvnw -B -ntp -Dtest=LocalReclamationRemovalTest,LocalReclamationRemovalIntegrationTest,LocalReclamationRemovalCrashTest,LocalReclamationInventoryTest,LocalCheckpointStoreTest,LocalCompactionPublicationTest,LocalInitializationTest test`
runs **236 cases**, with one new fixture assertion omitting required barrier cleanup.
Correct only that assertion to require acquire+close and released protection with
no object access. Focused `./mvnw -B -ntp -Dtest=LocalReclamationRemovalTest test`
then passes **71 cases**. Final `./mvnw -B -ntp verify` passes **840 tests**, zero
failures/errors/skips locally on macOS/arm64. New removal coverage comprises:

- **71 unit/fault cases:** exact retained/shared roots, immutable receipts, empty/
  genesis/gaps/idempotence, stale-preview recomputation, full-entry capacity,
  corruption/alias/budget/prefix/pending/missing-root refusal, journal/retained-
  manifest/segment force, all three namespace barriers and before/after metadata/
  segment deletion faults. IO/runtime/Error observer and cleanup faults preserve
  complete surviving graphs, explicit uncertainty and independently retryable cleanup.
- **15 real integration cases:** actual names/encoded bytes, explicit backup roots,
  shared layouts, genesis/empty/unknown/orphan/pending evidence, damaged newest
  state, fresh selected layout, heap children and same-JVM/child-JVM old readers,
  both exclusive owners blocking admission during actual removal, late diagnostic
  hard-link refusal and a held data reservation blocking delete without a raw handle.
  A full 16,384-entry namespace restores publication headroom without creating intent
  or temporary objects; comparison/retention pressure remains an explicit limit.
- **Seven child-JVM halts:** live-root sync, each of two manifest removals, metadata
  retirement sync, each of two segment removals and final directory sync. Check
  complete surviving graphs, exact selected/journal/control bytes, writable history/
  retries and fresh cleanup preserving already orphaned segments. Bounded children
  and logs outside store homes; raw owner bytes are inspected only after close.

All **thirteen examples** pass, including actual reader deferral, explicit retention,
removal and selected-history reopen. Linux/Windows CI runs the thirteenth example.
JAR inspection confirms **124 Java 25 classes**, major 69, no test oracle, fixture,
seed, JUnit or Jackson content. SHA-256:
`6e222a32fae1aaaf00846f70b96f34a99b43d0cf7d3f45c7fa77b96d05ad5a35`.
No artifact release/upload. Process exit and injected namespace failures are not
power-loss simulation or full canonical/seeded reclamation conformance.

**Documentation/planning:** add physical reclamation guide and ADR 0019, navigation
and decision-table row; reconcile README/home/architecture/research/roadmap,
compaction/checkpoint/reader/inventory and earlier decision status, build/reference/
engine counts and permanent AGENTS procedures. Both URL-layout shared builds pass
source/HTML/link/search/sitemap QA for **44 pages**. Browser inspection confirms
ordered protocol, code, failure table, exact search anchor and ADR 0019 listing;
assets/theme unchanged with no new dark/mobile claim. Close MDB-0033, MDB-0031 and
MDB-0028 against original criteria; preserve expanded MDB-0010 and admit only
MDB-0029 ready. Validate 33 unique IDs, dependencies/parents/DAG/completed children,
no active row and repository Markdown paths; inspect task-owned staged diff.
Scratch receipts remain ignored in `.local-qa/mdb-0033/`.

**Delivery:** completion trailers `Step-ID: MDB-0033` / `Step-Result: completed`,
normal push to origin/main and remote ancestry/tip/clean-tree verification. Git
supplies the source SHA; final handoff reports actual Java/docs/automatic Pages
outcomes and exact live-page/search/sitemap QA. No Cloudflare/infrastructure or
artifact deployment beyond authorized automatic documentation publication.

**Next recommendation:** MDB-0029 — verify compaction, reclamation, crash and temporal
conformance. Exercise actual publication/removal through canonical/seeded reference
and query histories, original retry identities, interruption/reopen and measured
retained-space evidence. Keep MDB-0010 open until all original integration criteria
pass; no object-store or performance work starts from this recommendation.

## 2026-10-31 — MDB-0029 Integrated compaction/reclamation conformance

**Outcome:** reconcile clean main with remote
`9607a5b78c526aaa071f69b11b1507251846d0f5`; Java 41163288077, documentation
41163288024 and Pages 41163288011 passed, with prior exact 44-page live QA.
Add six test-only files joining actual durable writes/checkpoint source installation,
pure compaction, verified replacement publication, source-reader/backup retention,
physical removal and read-only/writable reopen. Preserve all original canonical
fixtures, seed values/generator draw order, formats, logical history and request IDs.
No production code, runtime dependency, POM or wrapper change is needed.

**Semantic matrix:** `LocalCompactionFixtureConformanceTest` runs all eight unchanged
histories (five temporal/84 steps plus three query/90 steps) in full-checkpoint and
checkpoint-tail shapes: **16 dynamic cases, 348 honest real literal steps and the
same 348 independent-reference steps**. Every one of 48 actual accepted new writes
completes maintenance before later literal steps. Capture original Head/named-view
positions; never manufacture replay flags by replaying literals into recovered
state. Additional every-cutoff query and original request sweeps run at each stage.

`LocalCompactionPropertiesConformanceTest` preserves all **16 persisted seeds**,
32 new writes per seed (**512 total**) and historical/retry random draw order.
Each seed has two maintenance cycles: head16/checkpoint8/target1024, then head32/
checkpoint32/default target (**32 cycles**). Compare every available historical
cutoff, including all 33 final cutoffs, against independent backward ReferenceModel
and forward dense cells for Get/Scan/Sum/Select/FilterScan and signed predicates.
Check pinned engine-view behavior, actual writable reopen between new writes,
read-only/writable query/lookup sweeps, identical/conflicting retries and unchanged
head. `LocalCompactionConformanceSupport` verifies/reuses existing whole content IDs,
pins a nonempty just-published source across replacement/owner close, proves deferral
and lazy access, applies explicit source retention, then performs actual unretained
removal. Journal images are read only after every owner/source pin closes.

**Richer storage/process matrix:** `LocalCompactionPipelineHistory` supplies
`pipeline-12-v1`, 12 acknowledged records, checkpoint8+tail4, corrections/retractions,
no-ops, Unicode/extreme values and original retries. Its independent ReferenceModel
checks **259 query cases at each of 13 cutoffs**, plus literal anchors and every
original request/retry/conflict. Three layout cases combine8→1, split1→3 at512bytes,
and verify unchanged1→1 reuse. Old source readers, explicit backup roots and returned
heap snapshots remain valid through real publication/removal.

`LocalCompactionPipelineCrashTest` adds **11 bounded child-JVM halts**: four actual
publication boundaries and seven removal stops (including first/last manifest and
segment effects). Verify exact complete new segment images/IDs even when unselected,
exact candidate manifest bytes/ID, remaining filename sets, all surviving graphs
and actual owner prefix, acknowledged historical results and retries. A pending
candidate refuses reclamation with bytes unchanged; interrupted retirement preserves
new orphans without stale-list deletion. Logs stay outside store homes. Capability
skips are only explicit unsupported directory sync, not ordinary I/O/permissions;
process exit while the OS runs is distinct from device power loss.

**Space evidence:** compare exact names and regular-file lengths at baseline,
source, replacement, backup-retained cleanup and final cleanup. Metadata-only
attributes may observe retained-reader stages; raw snapshots wait for all OS-backed
owners to close. The unchanged 1,518-byte journal, two empty controls, 33-byte note
and three-byte diagnostic segment are preserved. Named-byte totals are:

| Layout | Baseline | Source | Published | Backup retained | Reclaimed | Final receipt bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Combine | 1,714 | 4,344 | 5,686 | 5,526 | 2,896 | 2,630 |
| Split | 1,714 | 3,056 | 4,766 | 4,606 | 3,264 | 1,342 |
| Unchanged | 1,714 | 3,056 | 3,272 | 3,112 | 2,896 | 216 |

Each backup cleanup removes 160 genesis bytes; final differences exactly match
receipts. Prefix encoded input/output is 2,022/1,126 (combine), 1,126/1,382 (split),
1,126/1,126 (unchanged). Split overhead is explicitly larger, so no universal
space-saving claim. The committed public JSON records all 15 layout/stage category
measurements and receipt byte totals; focused and full-run outputs agree exactly.
These are file lengths, not allocated/freed blocks or throughput/latency evidence.

**Research/review:** fresh no-cache fetch of all twelve Central metadata sources at
**October 31, 10:11:30 Toronto / 14:11:30 UTC**, plus official Maven/plugin/JUnit/Jackson
compatibility. Existing pins remain latest stable: Maven 3.10.0 (metadata 4.0.0-rc-7),
wrapper distribution/plugin 3.3.4, clean/resources 3.5.0, compiler 3.16.0,
surefire 3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3, Jackson 3.2.4;
Maven/plugin 4 entries remain prereleases. Record exact coordinates/URLs in build.md.
No new external durability decision is needed. Independent review checks all six
Java files and the guide, driving explicit nonempty-source pin admission, both reopen
modes and metadata-only-versus-raw observation precision. No material finding remains.

**Verification:** focused
`./mvnw -B -ntp -Dtest=LocalCompactionFixtureConformanceTest,LocalCompactionPropertiesConformanceTest,LocalCompactionPipelineConformanceTest,LocalCompactionPipelineCrashTest test`
passes **46 tests**, zero failures/errors/skips. Full `./mvnw -B -ntp verify` passes
**886 tests**, zero failures/errors/skips locally on macOS/arm64. All **13 examples**
pass. JAR inspection retains **124 Java 25 classes**, major 69, with no test oracle,
fixture, seed, JUnit or Jackson contents; unchanged production SHA-256:
`6e222a32fae1aaaf00846f70b96f34a99b43d0cf7d3f45c7fa77b96d05ad5a35`.
No library release/upload. CI retains its existing 13 example steps on both platforms.

**Documentation/planning:** add central conformance guide, measured tables/static
JSON and navigation; replace pending integration statements at existing README/home/
architecture/research/roadmap, build/engine/reference/durability, compaction/removal/
inventory guides and ADR 0016–0019/index callers. Persist fixture/cutoff/oracle/raw-
handle/space evidence standards in AGENTS. Public copy contains technical behavior,
not workflow narration. Both URL layouts pass shared source/HTML/links/search/
sitemap QA for **45 pages**. Browser inspection verifies tables, exact measured-
fixture search anchor and navigation; theme/assets unchanged with no new dark/mobile
claim. Validate 33 unique IDs, dependencies/parents/DAG, completed children, no active
row and only MDB-0011 ready; reconcile Markdown paths and task-owned staged diff.
Scratch receipts remain ignored in `.local-qa/mdb-0029/`.

**Closure/delivery:** complete MDB-0029 and close MDB-0010 against every original
parent criterion; all required children are done. Use completion trailers
`Step-ID: MDB-0029` / `Step-Result: completed`, normal push to origin/main and
remote ancestry/tip/clean-tree verification. Git supplies this commit's SHA; final
handoff reports actual Java/docs/automatic Pages and exact live-page/search/sitemap/
structured-evidence checks. No Cloudflare/infrastructure or artifact deployment.

**Next recommendation:** MDB-0011 — add the object-storage backend and conformance
checks. Refine that broad card into bounded protocol/library/emulator steps before
implementation; keep real-cloud spending, credentials and infrastructure changes
outside implicit source-work authority. This recommendation starts no backend work.


## 2026-10-31 — MDB-0034 Bitemporal persistence boundaries

**Outcome:** adopt the embedded Java bitemporal library direction with built-in
local persistence, optional narrow adapters and a separate future replication
protocol. Add ADR 0020 and the public persistence boundary specification. Preserve
all temporal cutoffs, request identity, existing formats and local forced
acknowledgement. The second temporal dimension remains commit order; calendar-time
mapping, remote adapters, replication and search-latency claims remain unimplemented.

**Architecture and scope:** separate commit authority/ordered journal, immutable
objects, checkpoint selection, derived indexes and protected reclamation. Remote
checkpoints first complement the exclusively owned local journal. Specify bounded
range/whole-object identity, opaque provider tokens, uncertain write/selection
reconciliation, actual journal-prefix recovery and local-tail loss. Checkpoint CAS
is neither distributed writer authority nor replicated durability. Cover reader/
publisher/backup protection before remote cleanup. One-writer/read-only-follower
replication preserves original identity/order; local, asynchronous and quorum
acknowledgements, catch-up, fencing and failover require their own protocol.

**Planning:** expand MDB-0011 without changing its original outcome/acceptance
criteria into seven required children, MDB-0034–MDB-0040: boundary decision,
local immutable-object contract, optional S3-compatible adapter, conditional
checkpoint publication, engine recovery, protected object reclamation and common
conformance. Complete only MDB-0034 and leave MDB-0035 ready. Add MDB-0041 as a
separate deferred replication-planning task outside the single-node prototype
chain. Preserve parent criteria, including real provider/HTTP fixture evidence,
optional SDK packaging, exact engine answers and a supported safe remote-removal
path rather than a preview/universal no-op. No cloud credentials, spending or
infrastructure work is started.

**Research/review:** refresh primary evidence on October 31 for ClickHouse Kafka
consumption/native storage, Hive formats/metastore/dataset separation, Elasticsearch
ranges/refresh/temporary PIT, XTDB bitemporality/log-object separation, S3 single-key
consistency/conditional writes and the Raft paper. Date the new evidence separately
from earlier research. Compare responsibilities without claiming performance or
feature parity. Independent read-only review finds no blocking design issue and
adds a concrete cloned-store case: equal lineage does not rule out divergent
local tails. Require observed prefix/predecessor conflicts to fail closed and
separate enforceable checks from exclusive-placement assumptions; do not promise
unseen-clone detection without distributed admission.

**Documentation QA:** reconcile current callers in README, homepage, architecture,
public roadmap, research, ADR index, navigation, AGENTS and internal roadmap/queue.
Replace stale current roadmap statements that process conformance remains planned;
retain dated receipts. Public content describes technical behavior and plans,
with no chat/owner/agent/task narration. Run:

- `.venv/bin/python -m unittest discover -s scripts/tests -p 'test_*.py'`: all
  **41 existing QA tests pass**.
- `.venv/bin/python scripts/build_docs.py` and the project-path variant using
  `DOCS_SITE_URL=https://meridiandb.github.io/meridiandb/`: shared strict source,
  rendered HTML, links, search and exact sitemap checks pass for **47 pages**.
  Restore and recheck the production build after the project-path build.
- Queue/card/reference/parent/dependency/eligibility checks: **41 unique IDs**,
  no cycles, completed parents' children done, no active executable row and only
  MDB-0035 ready; MDB-0011 expanded and MDB-0041 deferred. Changed Markdown local
  target checks and `git diff --check` pass.
- Local HTTP/browser preview verifies the responsibility and comparison tables,
  desktop light/dark rendering, 390-pixel table scrolling, navigation, the new ADR,
  search results for `quorum` and final cloned-store/local-tail wording. Reset the
  temporary viewport and close the temporary preview at handoff.

**Verification boundary:** this is documentation/planning work. No production,
Maven/POM/wrapper/dependency, fixture, test implementation or storage-format change
is made. No Maven research/build or rerun of the 886-test Java baseline is needed;
its prior completion remains evidenced independently. The initial queue checker
scratch script shadowed a path variable; correct the ignored script and rerun all
queue checks successfully. Scratch work stays in `.local-qa/mdb-0034/`.

**Delivery:** starting tree is clean main at
`4c255e2689870c45bddfea1496f3a43e5983d09f`, matching fetched origin/main. Its Java
41210455116, docs 41210455122 and Pages 41210455167 runs are all successful.
Commit this source outcome with `Step-ID: MDB-0034` / `Step-Result: completed`,
normal-push origin/main, verify remote ancestry/tips and inspect triggered docs/
automatic Pages plus exact live content/search/sitemap. No Java workflow is expected
for these inputs. Git and the final handoff supply this completion SHA and actual
publication outcome; this receipt makes no preemptive deployment claim.

**Next recommendation:** MDB-0035 — implement bounded immutable-object access and
local conformance. Define the smallest concrete consumer boundary with local
ownership/integrity/budget tests and fresh Maven artifact research; preserve the
separate journal, checkpoint publication and future provider/replication gates.
This recommendation starts no next-step implementation.


## 2026-10-31 — MDB-0035 Bounded immutable-object access

**Outcome:** implement the small immutable byte-store boundary with a real local
adapter and segment-codec consumer. Add `ObjectKey`, `ObjectId`, `ObjectReference`,
`ObjectAlreadyExistsException`, `ObjectIntegrityException`, `VerifiedObject`,
`ImmutableObjectStore`, `LocalObjectStore` and `SegmentObjects`. Keys are flat,
portable and bounded; references carry full-image SHA-256 and exact length capped
at 32 MiB. Create-only writing preflights/copies before path effects, completes
bounded I/O, forces and closes before success. Explicit identical reuse verifies
bytes and reports creation separately, without inferring an earlier force/ack.

**Correctness boundaries:** complete expected-length reads, bounded allocation,
EOF/final-size check and full hash validation precede heap-reader return. Verified
heap ranges check negative/overflow/out-of-range input; they are not physical file
or HTTP ranges. Reader copies survive close and reject new access after close.
Pre-open shared ownership reservations reject control/journal aliases and retain
uncertain native-close reservations. Failed candidates remain; no repair/deletion,
mutable head, namespace durability, checkpoint selection, transaction authority or
replicated acknowledgement is added. SegmentObjects decodes all history bytes and
keeps the exact existing codec, including rejection of well-hashed malformed data.
Existing local segment/checkpoint/journal/engine production files are unchanged.

**Independent checks:** two focused helpers author only the assigned tests, and
an independent read-only review of production/ownership finds no blocking issue.
The new **84 test invocations** comprise 8 type/range/lifetime, 9 reusable byte-store
contract, 10 real local, 53 injected I/O and 4 segment-consumer checks. Real local
cases check no-follow/sparse/regular paths, absent/conflicting/corrupt evidence,
copy/lifetime behavior and separate-JVM native-lock preservation after rejected
control/hard-link/journal alias operations. Capability-specific link skips retain
ordinary I/O failures. Fault tests cover short/stalled/negative/inconsistent I/O,
length before allocation, EOF/suffix/size drift, force/close ordering, partial
candidate retention, IOException/runtime/Error and primary/cleanup suppression,
including the same throwable. Consumer tests prove both directions of legacy-byte
compatibility, exact original transactions, reuse and invalid ID/length/format.

**Actual verification:**

- Initial `./mvnw -B -DskipTests package` compiles under Java 25 with -Xlint:all /
  -Werror; the focused `-Dtest=LocalObjectStoreIoTest,SegmentObjectsTest test`
  passes 57 invocations. No failing Maven run occurs.
- `./mvnw -B verify` passes **970 tests**, zero failures/errors/skips, including
  all existing independent temporal/reference, durability/process and integrated
  compaction/reclamation checks. No fixture, seed, semantic or format revision.
- All **fourteen examples** pass against target/classes. The new ObjectStoreExample
  also runs against `target/meridiandb-core-0.1.0-SNAPSHOT.jar`, reopens two original
  transactions, verifies identical reuse and retains header bytes after close.
  Java CI runs this example on Linux and Windows without extra provider setup.
- Packaged artifact: **137 classes**, class-file major **69**, no test/reference/
  canonical-fixture entries. `jdeps -s` reports only **java.base**. SHA-256:
  `ced17b1ade2b863779e6b52ed0ac5031d87278f350c617b2b0206349010f8b47`.
  This is a local experimental artifact, not a library release. The first jdeps
  attempt used unsupported --summary; use the documented -s option successfully.
- All **41 documentation QA tests** pass. The shared strict build succeeds for
  the production and GitHub project-path layouts, then restores production:
  **49 pages**, source/HTML/local-link/search/sitemap checks pass. Preview the new
  API table in light/dark and at 390-pixel width, new ADR/navigation and searchable
  createOrVerify content; reset temporary viewport/theme and close the preview.
- Queue/card/reference/parent/dependency/eligibility checks retain **41 unique IDs**
  and no cycles, completed children consistent, no active item and only MDB-0036
  ready. Changed Markdown local targets, public caller consistency and staged diff
  receive review. Temporary logs/metadata remain under `.local-qa/mdb-0035/`.

**Research and documentation:** Central metadata plus official Maven/plugin/JUnit/
Jackson compatibility sources are freshly checked October 31 at **13:38:43 EDT /
17:38:43 UTC**. All twelve selected versions remain latest stable (Maven 3.10.0,
wrapper distribution/plugin 3.3.4, clean/resources 3.5.0, compiler 3.16.0, Surefire
3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3, Jackson 3.2.4). Maven/plugin
4.x entries remain preview/beta. No new runtime dependency or POM/wrapper change.
Refresh JDK 25 create-new, FileChannel and FileLock evidence in ADR 0021. Add the
object guide and new example, and reconcile README/home, architecture/boundaries/
research, public roadmap, build/local-segment guides, ADR index, navigation and
agent rules. Public copy describes implemented technical behavior and remaining
limits, without owner/agent/task narration.

**Closure/delivery:** complete MDB-0035 without expanding it; a separate segment
consumer provides the concrete interface use while the established publisher
remains unchanged. Keep MDB-0011 expanded and all remaining provider/publication/
engine/reclamation/conformance gates required. Prior clean main at
`24df9b0871bd208fe3c0bd436ece8185852f0663` matches fetched origin/main, with docs
41222060256 and Pages 41222060285 successful. Commit with `Step-ID: MDB-0035` /
`Step-Result: completed`, push normally, verify remote ancestry/tips and inspect
Java/docs/automatic Pages and exact live pages/search/sitemap. Git supplies this
commit's SHA and final handoff reports actual delivery; no release, Cloudflare or
real-cloud provisioning/spending is performed.

**Next recommendation:** MDB-0036 — add optional S3-compatible object access and
provider checks. First refine maintained-library/optional packaging and the
controlled SDK/HTTP fixture, with fresh Maven compatibility/version research.
Preserve local use without a cloud runtime requirement and keep remote checkpoint,
engine integration, reclamation and replication separate. No next item starts.


## 2026-10-31 — MDB-0035 Windows directory-rejection assertion repair

**Evidence:** source completion `ba0a97f86ad29cd274c9b952b4dd0d541f4af785`
is pushed and matches origin/main. Java run 41226645494 passes Ubuntu and fails
one Windows assertion in LocalObjectStoreConformanceTest: opening an existing
directory with CREATE_NEW reports AccessDeniedException on that provider instead
of the expected ObjectAlreadyExistsException. Windows observes 970 tests with
one failure, zero errors and 228 explicit existing capability skips. Other new
object/type/fault/segment tests pass. Docs 41226645890 and automatic Pages
41226645586 succeed; exact initial live 49-page/search/sitemap verification passes.

**Repair:** assert IOException and preserved directory evidence for this invalid
non-regular destination. Keep all strict inherited regular-file conflict/reuse
checks. Clarify provider-specific directory rejection in the public object guide
and AGENTS; do not change production code or classify arbitrary access denial as
absence, conflict or successful reuse. This is a portable expectation correction,
not a capability skip or weakened object-integrity guarantee.

**Verification/delivery:** rerun the focused LocalObjectStoreConformanceTest via
`./mvnw -B -Dtest=LocalObjectStoreConformanceTest verify`: 19 cases pass locally.
Both public site layouts and the restored production build pass for 49 pages;
queue/DAG and diff checks pass. Production classes, Maven pins and the local JAR
remain unchanged; the earlier full 970-test/14-example local baseline is retained.
Commit the repair with MDB-0035 completion trailers, push normally and verify
new Java/docs/automatic Pages and exact updated live content before handoff.
The final report distinguishes this initial CI failure from the repaired result.
MDB-0036 remains next and starts only after successful delivery checks.


## 2026-10-31 — MDB-0042 Repository sponsorship

**Outcome:** implement the owner's scoped request for the repository Sponsor button
and a short README link. Add minimal `.github/FUNDING.yml` with
`github: [MasteryEducation]`. README identifies Tokenizer Inc. as recipient and
supports MeridianDB development, testing and documentation with explicit
experimental/voluntary wording. Add `internal/sponsorship.md` as the maintenance
procedure and link it from AGENTS. Keep the engine's MDB-0036 selection unchanged.

**Recipient evidence:** the public personal FuadEfendi Sponsors URL redirects to
its regular profile. Earlier SoftwarePatternsLexicon.com funding commit
`b45e8312b13388f28957491e9ab2d15529266f86` supplies MasteryEducation as its GitHub
recipient. Browser verification on October 31 confirms the active
`https://github.com/sponsors/MasteryEducation` page identifies **Mastery Education
by Tokenizer Inc.**, Toronto, founded by Fuad Efendi, with monthly/one-time custom
support. Use that verified existing destination only; do not copy the old unused
funding platform placeholders or other payment links. Central profile wording
still concerns education; no profile, tier, bank/payout or other-repository change
is performed, and no donation transaction is initiated.

**Verification:** parse the YAML and assert its exact recipient; confirm the README
has one matching active funding link and honest support wording. Local referenced
paths, `git diff --check`, source/rendered strict docs, links, search and sitemap
pass for the unchanged **49-page** website. The queue validator checks **42 unique
IDs**, roadmap cards/anchors, dependency/parent references, eligibility and DAG;
MDB-0042 is done, no item active and only MDB-0036 ready. No production/Maven or
site-content input changes require Java verification, dependency research, new
unit tests or a Pages deployment. The configured README path triggers docs-checks.

**Delivery:** start clean main at `d5dbbc37c2311c9c0d7304183d7cfd5e438bf887`,
matching fetched origin/main. Its Java 41226869828, docs 41226869905 and Pages
41226869896 runs succeeded. Commit with MDB-0042 completion trailers, normal-push
origin/main, verify ancestry/tips and documentation CI, then inspect the live
repository Sponsor panel and its intended destination. Git and the final handoff
supply this completion's actual SHA/readback; source files make no preemptive
claim of deployed repository UI. No Cloudflare, artifact release or site dispatch.

**Next recommendation:** update the central Sponsors profile introduction to
mention MeridianDB alongside the existing portfolio, if the owner wants that
separate account-copy change. Engine work remains MDB-0036; neither starts here.


## 2026-10-31 — MDB-0042 Sponsorship feature enablement and readback

Source commit `d06f42243457cfa0891befe8043ff3f98006e91f` is pushed, matches
origin/main and passes documentation run **41230965024**. Its README support link
renders correctly. GraphQL readback recognizes the sole GITHUB funding recipient
`https://github.com/MasteryEducation`, but reports hasSponsorshipsEnabled=false.

Under the explicit enable-sponsorship request, read repository ID R_kgDOVarwyA
and set only `hasSponsorshipsEnabled: true` with updateRepository through the
already authenticated gh CLI, since the connector lacks this setting and the
in-app browser is signed out. Independent GraphQL readback confirms true and the
same single recipient. Browser reload/hydration then shows the enabled **Sponsor
MeridianDB/meridiandb** link and **Sponsor this project** section. Clicking the
actual header link opens `https://github.com/sponsors/MasteryEducation`, headed
**Become a sponsor to Mastery Education by Tokenizer Inc.**, with monthly/one-time
custom support. No payment is initiated. All requested button/link criteria pass.

Persist this external feature receipt and update the maintenance procedure/queue
in a normal source commit/push; no source/public-site or funding-recipient change
is needed. Record this receipt's actual commit and matching remote tip at final
handoff. Engine work stays MDB-0036, and central profile copy remains its own
optional next task.

## 2026-10-31 — MDB-0043 Sponsors profile description

**Outcome:** fulfill the owner's approved central-profile refresh. Retain the exact
short bio and introduction in `internal/sponsorship-profile.md` and save only those
two fields at `https://github.com/sponsors/MasteryEducation`. The published copy
identifies Fuad Efendi/Tokenizer Inc., experimental embeddable Java bitemporal
MeridianDB, AI-assisted workflows with architectural contracts, independent
reference models and automated tests, and development/testing/documentation support.
Preserve account identity, funding recipient, tiers, payout settings and featured
work. No other repository, sponsor message or financial transaction is involved.

**Historical baseline:** both prior description fields claimed free, open-source
educational books on 80+ websites, with the introduction also describing interactive
quizzes in programming, finance, accounting, certifications and trading. The owner
reports those earlier source repositories are now removed/private. Replace the
superseded broad public-source claim without implying private sources are public.
The listing remains **Mastery Education by Tokenizer Inc.**; no account rename.

**Publication evidence:** inspect the public GraphQL schema and GitHub's documented
Profile details procedure on October 31. SponsorsListing exposes description reads
but no existing-listing update mutation; use the authenticated Chrome dashboard.
GitHub initially requires Confirm access; the owner completes their authentication
and replies done. Verify both prepared form values and unchanged profile-option
values, then select Update profile. GitHub reports **Your profile has been updated**.
Independent `gh api graphql` readback of shortDescription and fullDescription
matches the retained source exactly; the short bio is **139/250 characters**, and
neither field contains the old 80+ claim. Public browser readback renders all four
introduction paragraphs and the actual MeridianDB repository link. Capture proof
at `.local-qa/mdb-0043/sponsors-profile-published.png` and retain the public tab as
the result. No credentials or payment information are recorded in the receipt.

**Source verification/delivery:** update the sponsorship maintenance procedure,
roadmap card and live queue; the root AGENTS sponsorship rule already links that
procedure and retains the same scoped-profile authority. Validate Markdown targets,
the exact profile source and the **43-ID** queue/card/parent/dependency DAG, then
inspect task-owned working/staged diffs and `git diff --check`. No public site,
Maven, dependency or runtime input changes require build/tests, version research or
Pages deployment. The configured Java/docs/Pages path filters exclude these
internal-only changes. Starting main is `a70bbc47b2d552678d5e99072927c3eb30d32acb`,
matching fetched origin/main. Commit with `Step-ID: MDB-0043` and
`Step-Result: completed`, push normally and verify remote ancestry/tips and actual
workflow state. Git and the final handoff supply this completion's SHA and push
readback; no release, site dispatch or Cloudflare action is performed.

**Next recommendation:** MDB-0036 — optional S3-compatible object access and provider
checks. Begin with optional packaging and controlled SDK/HTTP fixture refinement,
using fresh Maven version/compatibility research. This task starts no engine work.

## 2026-10-31 — MDB-0044 MeridianDB visual identity

**Outcome:** fulfill the SVG-first logo request with a compact lowercase m formed
from two adjoining meridian-like arches. Use deep navy, soft white and mint for the
square mark; light-surface wordmark DB lettering uses darker teal for contrast.
Retain exact symbol geometry and outlined Inter 4.1 SemiBold lettering as two SVG
masters. Derive the dark wordmark SVG, four square PNGs (32/180/512/1024 pixels) and
two transparent 2148 × 512 wordmark PNGs. The GitHub avatar PNG is **32,746 bytes**.
All SVGs are self-contained with accessible titles/descriptions and no external
fonts, scripts or raster references. Preserve the font's OFL notice and release/
OTF hashes in the maintenance record; downloaded fonts and exploratory work stay
under ignored `.local-qa/mdb-0044/`.

**Integration and guidance:** README uses portable picture markup for light/dark
SVG wordmarks. The existing Zensical theme uses the 180-pixel header PNG and
32-pixel favicon. Keep the old public assets/favicon.svg URL as a generated copy
of the new symbol for cached pages. Add navigable public Logos and colors downloads,
palette/use guidance and a contributor link. `internal/brand.md` owns master/export
maintenance, exact installed librsvg **2.61.1** conversion, source attribution and
checks; the concise AGENTS brand rule links it. The exporter uses Python standard
library plus local rsvg-convert, keeps icon geometry synchronized and verifies PNG
signatures/dimensions. Committed exports need no CI renderer or font download.
No Maven/POM/runtime dependency, account avatar or funding setting is changed.

**Verification:** run the exporter repeatedly and compare complete asset hashes:
the second export changes no bytes, including the compatibility favicon. Parse
SVGs, reject external/resource/script/text dependencies, verify every PNG's full
decode, dimensions, RGBA/transparency and GitHub upload size. One scratch validation
attempt calls Pillow verify after pixel access; correct the checker to verify a
freshly opened file, then pass the complete checks. No artwork defect is found.
Review light/dark wordmarks, 16/24/32/48/64-pixel symbols and circular avatar crop.
Both strict documentation URL layouts pass, then restore the production build:
**50 pages**, with source/HTML/local links/search/sitemap valid. Confirm the rendered
PNG header/favicon references. Inspect the download page in desktop light/dark
and **390 × 844** light/dark views with no page-wide overflow. The first radio AX
click selects the current theme; inspect the real label targets and use them to
verify both themes. Search returns the Logos and colors page and its two sections.
Reset temporary viewport/theme; retain ignored visual evidence locally. Final
44-ID queue/card/parent/dependency DAG and working/staged diff checks must pass.

**Delivery:** prior scoped Sponsors completion
`d8b2d82af4ea66f1c6ef3b509b2e26ddd9a5d51f` is on origin/main with matching tips
and no path-triggered workflows. Commit the logo source/docs/planning with
`Step-ID: MDB-0044` and `Step-Result: completed`, push normally, verify remote
ancestry/tips, inspect docs CI and automatic Pages, then compare live assets and
check the public page, navigation, search and sitemap. Git and the final handoff
supply the actual completion SHA and deployment outcome. No release, manual site
dispatch, Cloudflare or paid infrastructure action is performed. The PNG is ready
for a separately requested GitHub organization-avatar upload.

**Next recommendation:** MDB-0036 — optional S3-compatible object access and provider
checks, beginning with maintained-library packaging, real SDK/HTTP fixture design
and fresh Maven artifact/compatibility research. No engine step starts here.

## 2026-10-31 — MDB-0044 Publication readback

Source completion `16a1fe5fe6839738f8f1fffdb5b3a8c270929304` reaches origin/main
with matching tips and a clean checkout. Documentation checks **41234117182** and
automatic Pages **41234117111** both finish successfully. The GitHub README loads
the outlined SVG at its intended 537-pixel display width. The public
`https://meridiandb.org/contributing/brand/` page visibly renders the symbol and
download links, with the new header logo and favicon. Compare all **11 published
asset files**, including font notice and compatibility favicon, against source:
every byte matches. Search contains the brand page/sections and the sitemap has
**50** HTTPS canonical page URLs. Save published browser proof locally at
`.local-qa/mdb-0044/site-published.png`; restore preview preferences/viewport and
stop the temporary HTTP server. No GitHub avatar upload occurs.

**Separate hosting observation:** the default GitHub project-path brand URL redirects
to `http://meridiandb.org/contributing/brand/`; the page canonical is HTTPS and
direct HTTPS serves the expected content and exact assets. Read-only Pages API
confirms `html_url: http://meridiandb.org/`, `https_enforced: false` and certificate
state `dns_changed`. These hosting settings are outside the logo change; no DNS or
HTTPS setting is changed. A scoped hosting follow-up should recheck DNS/certificate
readiness and restore HTTPS enforcement when available. Keep that actual host
state distinct from the successful logo publication and canonical build checks.

Persist the delivery receipt/queue in an internal-only commit, push normally and
verify remote ancestry/tips. No new docs/Pages workflow is expected from that
receipt. Engine selection remains MDB-0036 ready; no implementation step starts.

## 2026-10-31 — MDB-0045 Dedicated MeridianDB sponsorship

**Outcome:** complete the owner's remaining setup after they enroll the MeridianDB
organization and report GitHub approval. Dashboard readback confirms the listing
is approved/public and active, while the existing repository funding link still
points to MasteryEducation. Replace that dependency with the dedicated project
profile; retain prior setup/copy as historical rather than an active fallback.
No deletion or financial/account transaction is performed.

**Public profile and identity:** the connector exposes no profile/avatar mutation,
so use authenticated Chrome for the existing settings. Replace the placeholder
short/full description with the project copy approved in the conversation and
retain exact strings in `internal/sponsorship-profile.md`. GitHub confirms saves;
independent GraphQL readback matches both fields exactly (**109/250** bio). Feature
the actual public MeridianDB/meridiandb repository. Upload the existing committed
1024-pixel PNG, inspect the complete crop, select Set new profile picture and
observe GitHub's success message. Save organization display name MeridianDB,
experimental-project description and https://meridiandb.org/ website. The public
repository's older description implies streaming work; align only its description
and homepage with the experimental embeddable Java bitemporal/local-persistence
status. Verify both public metadata sets independently through REST.

**Rendered proof:** the public Sponsors page displays the exact introduction,
featured repository/card description and monthly/one-time custom support. GitHub
warns avatar propagation may take minutes. A normal CDN avatar URL already serves
the new navy/mint symbol while Chrome retains the identicon; a one-time cache-
bypassing page reload verifies the actual new image without changing page content
or leaving any browser configuration in place. Save the final public proof at
`.local-qa/mdb-0045/meridiandb-sponsors-published.png`. No settings/private financial
screenshot is retained in tracked evidence. Preserve existing optional visibility
choices, bank/tax/payout settings, goals and pricing/reward commitments; add no
newsletter or promotional message. MasteryEducation remains unchanged.

**Repository and rules:** set `.github/FUNDING.yml` to github: [MeridianDB] and
point README's matching support label/link to the new Sponsors page. The existing
Sponsorships feature is already enabled. Reconcile the current recipient, exact
profile source and migration/avatar procedure; root AGENTS already links that
authority, and brand maintenance now points to the completed upload evidence.
Mark earlier roadmap recipient/copy outcomes historical. Preserve original dated
progress and Git history. No website input, logo source, Java or Maven change.

**Verification/delivery:** exact funding YAML, one matching README link, absence
of its old recipient, experimental/voluntary wording and public metadata checks
pass. The shared strict documentation command passes source/HTML/local links/
search/sitemap for the unchanged **50-page** production site. Validate changed
Markdown paths/anchors, 45 unique queue IDs/cards/dependencies/parents/DAG and
task-owned working/staged diffs. Starting clean main is
`ac70cfd2833f7680d31cacae81fade095523a830`, matching fetched origin/main. Commit
with `Step-ID: MDB-0045` and `Step-Result: completed`, normal-push origin/main,
verify ancestry/tips and README-triggered documentation CI, then independently
read the repository funding recipient and click its actual Sponsor link. These
paths require no Pages publication, version research, Maven tests or release.
Git/final handoff supply the actual completion SHA and delivery readback.

**Next recommendation:** MDB-0036 — optional S3-compatible object access and provider
checks, starting with optional packaging, a controlled real SDK/HTTP fixture and
fresh Maven artifact/compatibility research. The engine cursor remains ready;
this scoped setup starts no implementation work.

## 2026-10-31 — MDB-0045 Funding cutover readback

Source completion `6027538b5d97fe1da3ca61a95bfce52e7977689e` is pushed to
origin/main with matching tips and a clean checkout. Documentation check
**41236877168** completes successfully. No Pages/Java workflow is triggered for
these README/FUNDING/internal-only paths. Independent repository GraphQL readback
confirms hasSponsorshipsEnabled=true and the sole GITHUB funding recipient
`https://github.com/MeridianDB`.

Open the actual repository page, allow its initially disabled server-rendered
Sponsor control to hydrate, then click its enabled **Sponsor MeridianDB/meridiandb**
link. It opens `https://github.com/sponsors/MeridianDB`, headed **Become a sponsor
to MeridianDB**, with the new logo, published project introduction and featured
repository/card description. The README's **MeridianDB on GitHub Sponsors** link
renders the same URL, and the Sponsor this project section names @MeridianDB.
Overwrite the ignored public screenshot proof with this actual-click destination.
Close the duplicate agent-created tab and retain the main public Sponsors result.
No payment is initiated and the older organization is untouched.

Persist this receipt/queue with a normal internal-only commit/push and verify its
remote ancestry/tips. That record triggers no additional configured workflows.
MDB-0036 remains ready; it starts only on a later invocation.

## 2026-10-31 — MDB-0046 Temporal geometry and index research

**Outcome:** fulfill the architecture-documentation request without starting an
index implementation. Add `docs/architecture/temporal-geometry.md`, linked from
navigation, concepts, architecture, storage research and README. Explain that
bitemporal and geographic models specialize generic geometry with different
semantics; knowledge order resolves only assertions applicable to the requested
entity/valid instant and cutoff. Distinguish a query point from effective regions,
two dimensions from four endpoints, raw assertions from materialized visibility,
and query geometry from index-cell shape. Retain the existing commit-position
axis, half-open microsecond domain, operation order and retraction behavior.
Cover safe predicate placement, coordinate correlation, stable identity, exact
index prefix/reader publication and separate persistence/retention obligations.

**Reuse research:** inspect Lucene LongPoint/LongRange/BKD, Solr spatial bounds/
prefix trees/date ranges and XTDB bitemporal resolution using dated primary
sources. Central metadata identifies **org.apache.lucene:lucene-core 10.6.0** as
latest stable on October 31. Inspect its source artifact's numeric point/range
contracts, including inclusive numeric range queries. Source archive SHA-256:
`8823917dc8bf648570bebb282d18e46ad93186ff15878532862d9fd766a34f8c`.
Retain exact metadata/source evidence under ignored `.local-qa/mdb-0046/`.
The current-release Javadoc URLs are unavailable through the lookup tool; the
note labels its published 9.12.2 API references explicitly and cross-checks the
numeric classes against current source. Solr's retrieved latest guide identifies
10.1. These are structure/semantic references, not dependency pins. No POM,
Wrapper, runtime dependency or Maven verification input changes. Add candidate
comparators/semantic checks to the existing MDB-0013 card, preserving its baseline,
adopt/reject gate and queue order; add no mandatory optimization or index subtask.

**Diagram choice and rule reconciliation:** the owner explicitly permits direct
SVG and reminds that PlantUML can convert to SVG during a site build. Use direct
SVG for the temporal plane, with a 640 × 400 viewBox, nine labeled state regions,
hatching/empty-set notation for absence, accessible title/description/alt text
and a self-contained contrasting background. The SVG is its own committed master
under `docs/assets/illustrations/`. Make the public documentation guide the
authoritative diagram procedure and reconcile root AGENTS/site operations:
direct masters are copied assets; first Mermaid/PlantUML source must introduce a
pinned renderer in the shared build before link validation/generation, with explicit
hash/bootstrap/runtime requirements. Generated outputs belong in their separate
ignored directory when introduced. No generated renderer or such source is added
here, and no conversion pipeline is claimed to exist. A caller scan finds no
remaining conflicting first-diagram rule among active guidance/ADR entry points.

**Verification:** the example uses C1 put 100 on [Jan 1, Feb 1), C2 put 200 on
[Jan 10, Jan 20), and C3 retract [Jan 14, Jan 17), all UTC in 2026. Run an ignored
JDK 25 consumer against existing compiled production classes: **24 literal checks**
cover three retained cutoffs, boundaries/outside dates and suppression of older
matching values at January 15. This validates the documentation example; it is
not a new full engine/reference/durability run. Parse/check the SVG's nine region
labels, title/description and lack of scripts/external resources. Rasterize only
for ignored visual QA with the already installed rsvg-convert, not as a new site
dependency. The production build copies the exact source SVG bytes.

Both strict documentation layouts pass, then restore production output:
**51 pages**, source/HTML/local-link/search/sitemap checks succeed. Preview the
figure/caption/events in desktop light and 390-pixel dark views with no page-wide
overflow, inspect navigation and find the new title through search. Restore
theme/viewport after inspection. The separate conversation-only explainer stays
outside the repository in the thread visualization directory; verify its four
cutoff/correction/retraction/outside-date interactions and 736/360-pixel light/dark
layout. Correct a query-marker/value-label overlap before final preview. Native
select value setting works for the sandboxed controls after DOM frame selectors
miss them; initial preview invocation used an unsupported destination+serve pair,
then the documented serve mode works. These are preview tooling corrections.

**Source completion/delivery:** local Markdown targets/anchors, the 46-ID
queue/card/parent/dependency DAG and task-owned working/staged diffs receive final
review. Starting clean main `f42c1157018f5a7c40185354cdd755dcd4b6e10c` matches
fetched origin/main; prior sponsorship source/docs CI and public readback pass.
Commit source/docs/guidance/planning with `Step-ID: MDB-0046` and
`Step-Result: completed`, push normally, verify ancestry/tips and documentation
CI/automatic Pages, then check the exact live note/SVG, canonical, search and
51-page sitemap. Git/final handoff provide the actual SHA/publication outcome.
No release, Cloudflare, paid infrastructure, engine change or indexing adoption.

**Next recommendation:** MDB-0036 — optional S3-compatible object access and provider
checks, with fresh Maven version/compatibility research and optional packaging/
controlled real SDK-HTTP fixture design. Index experiments remain in MDB-0013 after
the existing workload baseline; neither starts here.

## 2026-10-31 — MDB-0046 Publication readback

Source completion `aa5f1ee6427151ef76d601cb423be942ad5aba44` reaches origin/main
with matching local/remote tips and a clean checkout. Documentation checks
**41239726551** and automatic Pages **41239726446** both finish successfully.
The live HTTPS architecture/temporal-geometry page contains the final note and
source references, with the new navigation entry and SVG. Its diagram loads at
the expected natural width of 640; complete live SVG bytes match committed source.
The new page appears in search and the **51-page** sitemap has HTTPS canonical
URLs. The live documentation guide publishes the direct-SVG/generated-renderer
distinction. Save browser proof at `.local-qa/mdb-0046/site-published.png` and
retain the public note tab as the result. Temporary theme/viewport changes are
restored, the conversation preview tab/server is closed, and the site preview
server is stopped after switching to the published page.

Persist this readback/queue with a normal internal-only commit/push and remote
verification. These receipt paths trigger no additional configured workflows.
No full Java suite, benchmark, new index, runtime dependency, release or other hosting
change is implied by documentation publication. MDB-0036 remains ready.

## 2026-10-31 — MDB-0036 Optional S3-compatible object access

**Outcome:** implement the bounded byte adapter in a separate experimental
`org.meridiandb:meridiandb-s3:0.1.0-SNAPSHOT` artifact under `adapters/s3/`.
Root core packaging/build paths remain intact; its attached test classifier
contains only the shared abstract object contract. The optional POM depends on
core and that test-only classifier; ordinary embedding remains JDK-only. ADR 0022
records the standalone-POM/local-install choice, alternatives and revisit criteria.

**Provider behavior:** use the real maintained AWS v2 SDK and explicit URLConnection
transport, excluding default Apache 5/Netty and optional CRT. Use signed conditional
PUT, exact binary length and precalculated SHA-256 transfer checksum; disable
chunked AWS framing. Strict existing-object classification requires 412 plus
PreconditionFailed; 409, permission, bucket and transport failures remain errors.
GET absence requires 404 plus NoSuchKey. Lost responses retain uncertainty and
explicit createOrVerify retries perform complete expected-content readback.

GET validates status/range/length before bounded allocation, consumes the whole
image inside the SDK operation/attempt timer, explicitly observes cleanup and
then verifies SHA-256. ETags remain opaque. Heap ranges prove no physical partial
object. A transport guard caps other/error bodies at 64 KiB before XML parsing,
rejects every nonempty successful PUT body and preserves cleanup failures. Calls
serialize with close; a non-closeable resolution delegate preserves caller-owned
credential-provider lifetime. Explicit SDK one-attempt and operation/attempt/
connection/socket timeouts replace defaults. No bucket setup/list/delete/mutable
head or new engine acknowledgement is added.

**Fresh artifact research:** at **20:44:49 UTC / 16:44:49 EDT**, current Central
metadata plus official SDK/Maven/JUnit/Jackson compatibility/release information
confirm AWS s3 and url-connection-client **2.57.8**, released October 30, and all
12 existing selected root pins unchanged. Direct upstream service POMs identify
apache5-client/netty-nio-client exclusions; exact SDK source confirms conditional
fields, transformer wrapping, timeout/quiet-cleanup and credential-close behavior.
Metadata SHA-256: s3 `a821c5586fd7e2ee72668bc3def61e27d88dc84449b0d39ec27b7b28448e23b6`,
url-connection-client `211308639aacbf2f4a204a22ddc187b61aa19bb5bf432ca5f9c07cb330fd3b15`.
Actual s3 2.57.8 source JAR SHA-256
`8b031931d0247e905e91fd1356ae2380bd521f60ef81e2938a177ca8c11819da`.
The public build guide owns current coordinates/version/source links; ignored
`.local-qa/mdb-0036/` retains working raw evidence and logs. Java 25 compatibility
is project-tested, not described as a vendor certification.

**Verification:** final `./mvnw -B clean install` passes **970 core tests**;
`./mvnw -B -f adapters/s3/pom.xml clean verify` passes **46 optional tests**, with
zero failures/errors/skips locally. Of those, **nine** are the exact shared core
contract, **29** execute real SDK serialization/signing/HTTP/unmarshalling, and
**eight** inject native stream/guard failures. Cover two-client create races,
lost replies before/after effects, conflict/hash/length changes with unchanged
ETags, precise absence/error classes, short/truncated/oversized/chunked/unexpected
range bodies, oversized error/PUT-success responses, one-attempt handling, body
operation timeout with a longer socket timeout, 32 MiB transfer/preflight bounds,
reader lifetime, shared credential ownership and exact segment history.
Fourteen examples pass using only the packaged core JAR. Core still contains
**137 production classes** and `jdeps --recursive` reports only java.base; test
support contains exactly one abstract class. The resolved adapter classpath uses
URLConnection 2.57.8 without Apache/Netty/CRT. Java CI now watches adapters and
both POMs and runs verified core local install then adapter checks on both OSes.

**Review/corrections:** independent research and fixture helpers have bounded
ownership; the coordinator serializes shared planning/Git writes. Real SDK checks
exposed AWS upload framing despite an explicit transfer checksum; disable it and
verify literal signed binary requests. Independent SDK source review and a
reproduction show closing a client could close a caller-owned credential provider
twice; the delegate and regression now preserve ownership. Reject unexpected 200
PUT bodies rather than allowing SDK body-ignore behavior. Explicitly abort after
native close failure, preserving primary/suppressed failures. Final clean builds
verify the corrected source. The fixture checks signature shape, not authentication;
no TLS/live authorization/provider durability/throughput/service qualification is
claimed. Underlying header allocation and JVM-managed connection pooling remain
explicit limits, as do namespace immutability assumptions after external deletion.

**Documentation QA:** update README, public homepage/roadmap/architecture/build/
object-access/ADR indexes, new S3 guide/ADR 0022, active older-decision callers and
root agent rules. Fix the concrete checker false positive on technical HTTP
Authorization/provider authorization wording by matching execution-approval
narration in context, retaining negative tests and a positive source/rendered
fixture. Reconcile its internal editorial procedure. **42 documentation QA tests**
pass. Both strict production and project-path builds pass source/HTML/local-link/
search/sitemap checks for **53 pages**, restoring production output. Rendered
review covers desktop light, 390-pixel dark text/code/table with no page-wide
overflow, the decision/nav and actual S3 search results. Restore theme/viewport.
No new diagram renderer or visual artifact is needed for this byte adapter.

**Source completion/delivery:** start from clean main
`b4e7ad16fa80a258d1ffbeb27b8cc569a1d365c5`, matching fetched origin/main.
Update queue/card/progress together; validate 46 unique IDs and references, DAG,
eligibility/completed children, task-owned diff, POM/workflow syntax and staged
scope. Commit with Step-ID MDB-0036 / Step-Result completed, push normally and
verify remote ancestry/tips, Java/docs CI and automatic Pages. Verify exact live
S3/decision/build/status content, canonical, search and 53-page sitemap before
recording delivery readback. Git/final handoff supplies actual source SHA/results.
No release/tag/Maven Central upload, paid infrastructure or account/DNS change.

**Next recommendation:** MDB-0037 — conditional object checkpoint publication,
including verified actual journal-prefix binding and the separately specified
single-writer/uncertain-selection protocol. This task starts none of that work.

## 2026-10-31 — MDB-0036 Delivery and publication readback

Source completion **`b16c9d160eb25bfbe928512bb28fff09e5411b66`** reaches
origin/main with matching tips and a clean source checkout. Java checks
**41241692651**, documentation checks **41241692527** and automatic Pages
**41241692511** all complete successfully. Ubuntu runs **970 core/46 optional**
tests with zero failures/errors/skips. Windows runs **970 core/46 optional** with
zero failures/errors; its **228 core skips** are the documented prior filesystem
capability cases, while **all 46 optional tests run without skips**. All fourteen
example steps finish on both CI OSes. No platform-specific S3 bypass is added.

The live HTTPS S3 guide, ADR 0022, build version evidence, public roadmap and
homepage contain the final capability/limit copy with canonical HTTPS URLs.
The public search index includes both new page routes/titles; the sitemap contains
**53 HTTPS canonical pages** including the S3 guide. The published guide is
inspected in the browser; proof is saved at `.local-qa/mdb-0036/site-published.png`
and embedded in the delivery update. Temporary viewport/theme settings are
restored, the preview tab now shows the live deliverable and the owned localhost
site server is stopped.

The queue/card record this readback. The internal-only receipt uses a normal
commit/push; these paths trigger no additional configured workflows. Git provides
its own SHA and remote ancestry/tip evidence. MDB-0037 remains
ready; no checkpoint publisher, live-cloud validation, release or subsequent
implementation step starts during this delivery receipt.

## 2026-10-31 — MDB-0047 Bounded object checkpoint head

**Selection/refinement:** start clean on main
`cafefdec67b8cf68663377dc4233e39016832485`, matching fetched origin/main and the
prior delivered S3 source `b16c9d160eb25bfbe928512bb28fff09e5411b66` with successful
Java/docs/Pages checks. Refine broad MDB-0037 into required children **MDB-0047**
(protocol/pure head metadata), **MDB-0048** (real SDK conditional head access) and
**MDB-0049** (verified graph/actual-owner-prefix publication, durable intent and
reconciliation/integration). Preserve the parent's original acceptance, all
external prerequisites and downstream references. Complete only the first child;
parent MDB-0037/MDB-0011 remain expanded. No later implementation begins here.

**Outcome:** add four core production sources for an immutable head declaration,
fixed v1 codec, typed failures and pure metadata verifier. The exact 256-byte
big-endian image contains namespace/publisher/publication UUIDs, canonical journal
header SHA-256, remote selection revision, exact manifest reference, original
journal-prefix metadata, exact complete predecessor reference and payload footer.
Complete-image identity includes the footer; it is not the footer or provider
ETag. Revision 1 has canonical absent predecessor; later revisions identify the
whole 256-byte predecessor and advance exactly once. Reject overflow, changed
bindings, adjacent publication identity reuse, prefix regression and changed
same-cutoff evidence. Bind complete manifest bytes/identity/length/lineage/prefix,
including exact empty-header length, without fetching dependencies or journal I/O.
Manifest generation and temporal cutoff remain independent from remote revision.

**Protocol/decisions:** public object-checkpoint protocol and ADR 0023 define the
wire offsets, namespaced flat keys, bounds, three-coordinate distinction, expected
binding provenance and required future graph/publication sequence. Hash exact
JournalCodec header bytes to retain the existing **32,768 UTF-16 code units** /
**65,556-byte maximum header**, including unpaired surrogates, rather than narrowing
lineage to fit a small text head. The pure type is declaration, not selected state.
Actual graph/owned-journal agreement, local durable expected/pending intent,
conditional SDK I/O, reconciliation, engine recovery and removal remain separate.
A lost-reply read of the predecessor alone cannot resolve a request still in
flight; preserve candidate bytes, publication identity and original precondition.
No automatic rebase, fall back to older graphs or cleanup follows uncertainty.
Copied bindings/local locks and metadata CAS supply no distributed admission;
observed prefix/predecessor conflicts must fail closed, while unseen clones and
external rollback/deletion remain explicit deployment assumptions. Future CAS
fixtures must pair GET bytes/token and model changing strong ETags, preserving
existing immutable-object constant-ETag cases separately.

**Research:** fresh Central metadata at **21:18:58 UTC / 17:18:58 EDT** reconfirms
all fourteen existing runtime/wrapper/plugin/direct selections as latest stable;
SDK s3/URLConnection remain **2.57.8**. No POM/dependency/format migration occurs.
Official S3 conditional-write/consistency/GET/ETag/versioning documentation and
RFC 9110 support the dated protocol facts; separate application design choices
from provider guarantees. Actual SDK source confirms both conditional PUT fields.
S3 metadata SHA-256
`a821c5586fd7e2ee72668bc3def61e27d88dc84449b0d39ec27b7b28448e23b6`,
URLConnection metadata
`211308639aacbf2f4a204a22ddc187b61aa19bb5bf432ca5f9c07cb330fd3b15`;
actual s3 2.57.8 source JAR
`8b031931d0247e905e91fd1356ae2380bd521f60ef81e2938a177ca8c11819da`.
The public build guide owns current pins and primary links; working raw evidence
is under ignored `.local-qa/mdb-0047/research-helper/`.

**Verification:** an independent helper builds Python struct/hashlib golden images
and literal Java expectations; no shared production algorithm generates expected
bytes. Revision-1 complete ID
`51d39de57bc442d01e3bd6ffdb69e42b47405f7e69a00de5e680b8db019fc66b`;
revision-2 ID
`105b3638d6ca7fbda71139d696bba5157e48cb38543cdc91e6b368c78f9891e8`.
**28 codec tests** cover every shorter length, payload/footer corruption, exact
version/flags/reserved/predecessor forms, expected references, numeric/domain
limits, null/nil IDs and copy lifetime. **18 verifier tests** cover independent
bindings, adjacent transitions/stale/overflow, exact manifest wire identity and
metadata, independent manifest generation, empty/large/unpaired lineage, missing
segments and a pair of divergent self-consistent same-lineage graphs that proves
coordinate-only checks cannot establish extension. This boundary is intentionally
visible rather than presented as copied-writer fencing.

Final `./mvnw -B clean install` passes **1,016 core tests**;
`./mvnw -B -f adapters/s3/pom.xml clean verify` passes **46 optional tests**, with
zero failures/errors/skips locally. Fourteen examples run using only the packaged
core JAR. Archive/`jdeps` inspection confirms **143 production classes**, only
java.base dependency, no SDK/test classes, and unchanged one-class shared test
classifier. These are pure metadata additions; no head GET/CAS or publication is
exercised, and no new crash/temporal guarantee is inferred from format checks.
The existing full suite retains its prior temporal/reference/local failure/
process/recovery checks; the existing SDK suite still tests immutable byte access.

**Documentation/caller QA:** reconcile README, public homepage/roadmap/build/S3/
architecture/persistence callers, decision index/navigation and root agent rules.
Both strict production and project-path builds pass source/HTML/local-link/search/
sitemap checks for **55 pages**; restore production output. Rendered review covers
protocol copy and tables in desktop light and 390-pixel dark with no page-wide
overflow, actual new protocol search results and navigation. Existing diagram
rules remain unchanged; exact field tables need no generated renderer. Independent
research/production/protocol review finds no blocker or unsupported capability
claim. Coordinator owns all production/shared planning/Git writes; helpers own
only research and their codec-test file. Preserve immutable historical receipts.

**Source completion/delivery:** reconcile queue/card/progress and validate **49
unique IDs**, references/parent/DAG, eligibility and completed children. Inspect
working/staged diff and explicit task paths, commit with Step-ID MDB-0047 /
Step-Result completed, push normally and verify remote ancestry/tips, Java/docs
CI and automatic Pages. Verify exact live protocol/ADR/build/status, canonical,
search and 55-page sitemap. Git/final handoff supplies actual source SHA/results;
a later internal receipt may cite that SHA. No real-cloud use, account/DNS change,
release/tag/artifact upload or subsequent child implementation is included.

**Next recommendation:** MDB-0048 — conditional S3 head access and real-SDK
changing-token/failure fixture cases. MDB-0049 still owns complete verified
publication, durable intent and all remaining parent integration acceptance.

## 2026-10-31 — MDB-0047 Delivery and publication readback

Source completion **`4049a2a52331979f633940bf542cfaa242a34b20`** reaches
origin/main with matching tips and clean source checkout. Java checks
**41244438456**, documentation checks **41244438603** and automatic Pages
**41244438583** finish successfully. Ubuntu runs **1,016 core/46 optional** tests
without failures/errors/skips; Windows runs the same counts without failures/
errors and retains **228 pre-existing core filesystem capability skips**.
Both OSes execute all **28 new codec/18 new verifier** and **46 optional** cases
without skips, and all fourteen example steps complete. No new capability bypass
or provider qualification is inferred.

The live HTTPS object-checkpoint protocol, ADR 0023, build evidence, public
roadmap and homepage contain the final pure-versus-planned capability copy and
canonical URLs. Search includes both new routes/titles; the sitemap contains
**55 HTTPS canonical pages**. Browser readback confirms the new protocol and
seven sections. Proof is saved at `.local-qa/mdb-0047/site-published.png` and
embedded in the delivery update. Viewport/theme changes were restored; the preview tab now shows
the live deliverable, and the owned localhost static server is stopped.

The queue/card record this readback. The internal-only receipt uses a normal
commit/push, with its own identity/remote ancestry supplied by Git. These paths
trigger no additional configured workflows. MDB-0037 remains expanded with
MDB-0047 done and MDB-0048 ready; no conditional head I/O or later implementation
starts during this receipt. No release, real-cloud/account/DNS operation or
hosting-setting change occurs.

## 2026-10-31 — MDB-0048 Conditional S3 head access

**Outcome:** implement a separate optional `S3CheckpointHeads` API with one complete
256-byte GET paired with its strong opaque ETag, revision-1 create-if-absent and
exact adjacent replacement using the original scoped observation. The API checks
independently configured namespace/publisher/lineage before I/O or observation
return. Immutable `S3HeadObservation` owns bounded heap bytes and full endpoint/
bucket/derived-key scope; `restore()` declares saved metadata and proves no prior
GET. `S3HeadToken` preserves one quoted ASCII tag up to 1,024 bytes (including
empty, comma, quoted star and literal backslash); `S3HeadWriteException` records
409/412/missing-key/service/uncertain observations without claiming resolution of
an earlier request. There is one SDK attempt and no refresh/rebase/reconciliation.

Extract private client ownership and exact-body cleanup for reuse by both APIs.
Each adapter owns its own SDK/URLConnection lifecycle and serializes close/calls;
shared credentials remain caller-owned. Fixed binary PUTs carry SHA-256 transfer
checksums with AWS chunk framing disabled. GET cleanup completes inside SDK timers;
exact SDK-visible length/token checks precede complete codec/binding validation.
The existing bounded error/control-body guard remains in force. Core production,
format, acknowledgement, local recovery and POMs are unchanged.

**Research:** fresh Central metadata at **21:47:41 UTC / 17:47:41 EDT** reconfirms
all fourteen existing selections as latest stable, including SDK s3 and
URLConnection **2.57.8**. No new dependency is needed. s3 metadata SHA-256
`a821c5586fd7e2ee72668bc3def61e27d88dc84449b0d39ec27b7b28448e23b6`;
URLConnection metadata
`211308639aacbf2f4a204a22ddc187b61aa19bb5bf432ca5f9c07cb330fd3b15`;
s3 source JAR
`8b031931d0247e905e91fd1356ae2380bd521f60ef81e2938a177ca8c11819da`.
Official PutObject/conditional-write/GetObject and RFC 9110 evidence distinguish
provider semantics from the narrower token/application policy. Actual SDK source
and a JDK 25/SDK probe show differently cased duplicate raw headers can collapse
when copied into the SDK's case-insensitive map. The adapter rejects exposed
multi-value lists, but establishes SDK-visible validation rather than universal
raw-wire duplicate detection. Native header allocation/framing remain transport
limits. Raw working evidence is ignored under `.local-qa/mdb-0048/research-helper/`.

**Verification:** the separate bounded JDK HTTP fixture models atomic changes and
changing opaque tokens through actual SDK serialization/signing/transport/XML
handling. **43 head SDK/API cases** cover genesis/replacement races, stale tokens,
captured bytes/token/absence, saved exact precondition retries, lost replies before/
after effects, status distinctions, response/token/head faults, limits/timeouts,
scope/binding/transition preflight and lifetime/credentials. A deterministic held
PUT times out; a GET still reads the predecessor; release then allows the original
request to take effect without retry. A deliberate external A → B → A rollback
restores bytes/token and permits stale CAS: this is an explicit unprevented
primitive limit. **27 type/observation/native-stream cases** cover grammar/bounds,
heap ownership, declared restore provenance, exact visible headers and close/
abort/stall/suppression. All **46 original immutable-object cases** remain unchanged
and pass. The fixture's duplicate-length fault combines duplicate raw fields with
chunked framing because JDK HttpServer overwrites fixed-body lengths; focused
stream/header checks provide additional isolated evidence.

Final `./mvnw -B clean install` passes **1,016 core tests** and
`./mvnw -B -f adapters/s3/pom.xml clean verify` passes **116 optional tests**, all
without failures/errors/skips locally. All fourteen examples run against the
packaged core JAR. Archive/`jdeps` inspection confirms **143 core production
classes**, no SDK/test classes, java.base-only external dependency and the unchanged
one-class shared test classifier. Optional resolved transport is URLConnection
2.57.8 with no Apache/Netty/CRT client. No real cloud, TLS/signature authentication,
durable provider, replication or throughput qualification is inferred.

**Documentation/caller QA:** add ADR 0024 and the conditional-head guide; reconcile
README, homepage, public roadmap, architecture/protocol/persistence, prior head
ADR, S3/build guides, decision index/navigation and root agent rules. Independent
review corrects two descriptions: head representation/predecessor binding are
already implemented, and same-GET provenance applies to `read()` observations,
not arbitrary `restore()`. Both strict production and project-path builds pass
source/HTML/link/search/sitemap checks for **57 pages**; restore production output.
Rendered review covers desktop light, 390-pixel dark operation tables without
page-wide overflow, navigation and actual search results for both new pages.
No diagram or renderer change is needed. Coordinator owns shared production/docs/
planning/Git; independent helpers own only their bounded fixture/test and research/
review work. Preserve prior historical receipts.

**Source completion/delivery:** mark MDB-0048 done and MDB-0049 ready, leave parent
MDB-0037 expanded and validate queue IDs/cards/dependencies/eligibility. Inspect
explicit task-owned paths/staged diff; commit with Step-ID MDB-0048 and Step-Result
completed, push normally, verify remote ancestry/tips, Java/docs CI and automatic
Pages/live readback. Git/final delivery receipt supplies actual SHA/results after
publication. Full graph/actual-owned-journal agreement, durable expected/pending
intent, uncertain-result reconciliation, engine recovery and removal remain outside
this primitive. No release/tag/artifact upload, real-cloud/account/DNS change or
subsequent child implementation is included.

**Next recommendation:** MDB-0049 — verified object checkpoint publication against
the actual owned journal, refining it into bounded children before implementation
if its complete parent acceptance requires more than one reviewable change.

## 2026-10-31 — MDB-0048 Delivery and publication readback

Source completion **`ed4ba02baf9be8834721870959d80c6515d87ee4`** reaches origin/main
with matching tips, verified ancestry and clean source checkout. Java checks
**41246798775**, documentation checks **41246798793** and automatic Pages
**41246798787** all finish successfully. Ubuntu runs **1,016 core / 116 optional**
cases without failures/errors/skips; Windows runs the same totals without failures/
errors and retains **228 existing core filesystem capability skips**. Both OSes
execute all **43 head SDK/API, 27 type/native-stream and 46 original optional**
cases without skips. Every one of the fourteen example steps succeeds on both
OSes. No additional provider/durability guarantee follows from this evidence.

Live HTTPS readback verifies the new conditional-head guide and ADR 0024, protocol,
build research timestamp, roadmap and homepage capability copy. Their canonical
URLs are correct. Search includes both new titles/routes and the sitemap has
**57 HTTPS canonical pages**. Browser readback verifies the complete guide. The
saved `.local-qa/mdb-0048/site-published.png` proof is embedded in the delivery
update; the tab shows the live deliverable. Temporary viewport/theme changes are
restored and the owned loopback preview server is stopped.

The queue/card now record verified delivery. This internal-only receipt receives
a normal commit/push with its own identity and remote verification supplied by
Git; these paths trigger no additional configured workflows. MDB-0048 is done,
MDB-0049 is ready and MDB-0037 remains expanded. No further implementation, release,
real-cloud/account/DNS action or hosting setting change occurs here.

**Next recommendation:** MDB-0049 — verified object checkpoint publication,
refining its graph/journal verification and durable intent/reconciliation stages
into bounded children before implementation if needed.

## 2026-10-31 — MDB-0050 Bounded owned-journal prefix verification

**Selection and refinement:** start from clean main/origin
`f239da980264601b24f8c96f1c2c39732818e69f`; verify preceding source
`ed4ba02baf9be8834721870959d80c6515d87ee4` and Java/docs/Pages success before
advancing. Expand MDB-0049 into four required children while preserving all
original MDB-0049/MDB-0037 acceptance: MDB-0050 actual-prefix byte bounds,
MDB-0051 bounded graph/actual-prefix verification, MDB-0052 durable expected/pending
state and MDB-0053 conditional publisher/reconciliation integration. A short,
self-consistent remote declaration cannot bound a different actual owner record;
therefore actual journal read preflight is a necessary first prerequisite, not
proof of the parent outcome. Parent publication and later engine/reclamation
work remain incomplete.

**Outcome:** add `LocalJournal.prefix(throughSequence, maxBytes)` and nested typed
`PrefixLimitException.maxBytes()`. Include the complete header and all requested
frames in the encoded-byte cap, check the remaining bytes before every 16-byte
framing read and before header/frame payload allocation/read, and use subtraction
to avoid overflow. Exact caps fit; negative arguments/cutoffs reject before I/O.
Policy exhaustion returns no prefix but preserves a healthy owner, counters,
identities, locks and bytes. Actual observed corruption, I/O and size changes
retain poisoning. The unlimited overload delegates with Long.MAX_VALUE. Existing
open/recovery scanning, acknowledgement, force/repair/close and wire formats are
unchanged. The public runnable journal example exercises a 64 KiB cap.

**Research:** all fourteen Maven selections are freshly checked at **22:11:41 UTC /
18:11:41 EDT**, with separate official compatibility snapshots at 22:11:59 UTC.
Selected stable versions remain Maven 3.10.0, wrapper 3.3.4, clean/resources 3.5.0,
compiler 3.16.0, Surefire 3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3,
Jackson 3.2.4 and optional SDK s3/URLConnection 2.57.8. No POM/dependency change
is needed. Metadata summary SHA-256
`0de3b959ea49cbdb4a76b8fd831d5186caa4b30e92fb783977ea78c18fef9fd8`;
research report
`cc0a34f7bf162ded79e4639a6399fb2bf58ac978140d5b8ee911a3d29622d68b`.
Ignored raw evidence is under `.local-qa/mdb-0050/research-helper/`; the public
build guide owns current dated coordinates and primary links. Preserve the
project-tested Java 25 description rather than implying provider certification.

**Verification:** an independent helper contributes 25 focused invocations;
coordinator adds the complete valid shorter-versus-larger same-lineage/same-cutoff
case. The final **26 bounded-prefix tests** cover exact/Long.MAX/unlimited hashes,
zero/tiny/header/max-lineage/frame/cumulative caps, exact native read request
bounds, short/zero/stalled/failed I/O, large valid framing without payload access,
format-limit/corruption versus policy classification, late unread faults, owner
reuse/append/identity/counter preservation, invalid/closed/replay states and real
locked-file exclusion. Raw real-file bytes are read only after owner close.
Observed size change remains a poisoned failure even with a zero budget. The
caller cap does not certify unread bytes, bound initial open recovery, provide a
universal heap/CPU limit or replace aggregate record/identity/repeated-call budgets.

Existing focused journal regression passes **58 cases**. Clean core and optional
builds pass; after adding the final explicit divergence case, final
`./mvnw -B install` passes **1,042 core tests**, and the unchanged optional
implementation's `./mvnw -B -f adapters/s3/pom.xml clean verify` passes **116 tests**.
All have zero failures/errors/skips locally. All fourteen examples run against
the packaged core JAR, including the changed journal example. Archive/`jdeps`
inspection finds **144 core production classes**, only java.base, no SDK/test
classes and the unchanged one-class shared conformance classifier. No changed
force boundary, remote graph guarantee or cloud qualification is inferred.

**Documentation and review:** update the journal guide/spec, ADR 0008, object-head
protocol, README, build evidence/counts, current S3 test-count callers and agent
rules. Independent production and document/planning review finds no actionable
issue. Both strict production and project-path builds pass source/HTML/links/
search/sitemap for **57 pages**, then restore production output. Rendered review
covers desktop light, 390-pixel dark and actual search results for the new section;
there is no page-wide overflow. No new route, diagram, dependency or renderer is
needed. Helpers own only independent research/review and their bounded test file;
coordinator owns production/docs/shared planning/Git writes.

**Source completion/delivery:** mark MDB-0050 done and MDB-0051 ready; leave
MDB-0049 and MDB-0037 expanded. Validate **53 unique IDs**, cards, references,
dependencies/parents/DAG and eligibility. Inspect explicit task-owned paths/staged
diff, commit with Step-ID MDB-0050 / Step-Result completed, push normally and
verify remote ancestry/tips, Java/docs CI, automatic Pages and live guide/build/
protocol/canonical/search/sitemap readback. Git and the delivery receipt supply
actual commit/check identities. No subsequent child, release/tag/artifact upload,
real-cloud/account/DNS or other hosting change is included.

**Next recommendation:** MDB-0051 — verified object checkpoint graphs against the
actual owned journal, sharing bounded object/prefix/record/identity work budgets
before durable intent and conditional publisher integration.

## 2026-10-31 — MDB-0050 Delivery and publication readback

Source completion **`28eec44b952e854a383f4ae11c8560eec1ce31f8`** reaches origin/main
with matching tips, verified ancestry and clean source checkout. Java checks
**41247796505**, docs checks **41247796460** and automatic Pages **41247796542**
all finish successfully. Ubuntu runs **1,042 core / 116 optional tests** without
failures/errors/skips; Windows runs the same totals without failures/errors and
retains **228 pre-existing core filesystem capability skips**. All **26 new
bounded-prefix** and **116 optional** cases run without skips on both platforms;
every one of the fourteen example steps succeeds on each. The new real-file
ownership case introduces no capability bypass.

Live HTTPS readback verifies the journal guide/new bounded-prefix section,
journal format, ADR 0008, object-head protocol, current build research/test counts
and conditional-head guide. Canonicals are correct, search includes the section
heading/content/anchor and the sitemap remains **57 HTTPS canonical pages**.
Browser readback confirms the live section and example. Screenshot
`.local-qa/mdb-0050/site-published.png` is embedded in the delivery update. Temporary
viewport/theme changes are restored; the preview tab now shows the live deliverable
and the owned loopback static server is stopped.

Queue/card delivery state is reconciled. This internal-only receipt receives a
normal commit/push with its own identity/remote verification supplied by Git; these
paths trigger no additional configured workflows. MDB-0050 is done and MDB-0051
ready. Expanded MDB-0049/MDB-0037 still require complete graph verification, durable
expected/pending state and conditional publisher/reconciliation integration.
No further implementation, release, real-cloud/account/DNS or hosting-setting
operation occurs in this receipt.

**Next recommendation:** MDB-0051 — verified object checkpoint graphs against the
actual owned journal, with shared aggregate object/prefix/record/identity budgets.

## 2026-10-31 — MDB-0051 Verified object checkpoint graphs

**Selection:** start from clean main/origin
`6322e93bac85efcddd2ee0e8716f5df5e410e97a`; verify preceding MDB-0050 source
`28eec44b952e854a383f4ae11c8560eec1ce31f8`, Java/docs/Pages and live delivery
before advancing. Execute this one required child; keep MDB-0049/MDB-0037 expanded
and preserve every original graph/publication/intent/reconciliation criterion.

**Outcome:** add JDK-only `ObjectCheckpointGraphs` over caller-owned immutable byte
access and an actual LocalJournal, independently configured namespace/publisher
UUIDs and actual canonical journal lineage. Single or adjacent-pair operations
reserve all head-known manifest fetches/bytes, actual prefix bytes and 2N graph/
journal record work before the first fetch. Bind/close complete manifests, then
reserve every dependency's bytes/fetch before segment or actual-prefix work.
Verify both graphs in predecessor order, exact header range/count, complete IDs/
lengths/lineage/ranges, cross-segment request uniqueness and reconstructed original
prefix; compare sequence/length/SHA-256 through `journal.prefix(sequence, declaredBytes)`.
Count repeated dependencies and same-cutoff comparisons twice. No listing,
ancestry traversal, implicit reuse exemption, force, writes, fallback or partial
verification result is introduced. Reserved namespace key helpers follow the
existing protocol.

Private Graph/Verification constructors expose immutable metadata, actual-prefix
observations and successful Usage only after complete verification/cleanup. They
retain no object arrays/readers or segment records and supply no current-head
scope/token, lease, force receipt or cleanup authority. Typed checked failures
separate aggregate limits, invalid graphs and journal mismatch; pure head checks
keep existing errors and adapter I/O propagates. Actual-prefix cap exhaustion
retains its cause and a healthy owner; actual journal corruption/I/O retains
poisoning. Limits are per operation: object/journal encoded bytes, record/request
cardinality and logical fetches, not exact heap/CPU/native short-read calls, initial
open recovery or arbitrary later invocations.

Add a checked SegmentCodec decode overload binding expected header first/last/
count before codec payload hashing, record-list allocation or transaction decoding.
The immutable adapter has already verified the complete object identity; no
partial-object integrity claim follows. A package-private decoded segment source
shares the existing CheckpointVerifier algorithm without a second fetch/decode;
the legacy raw-source path and failure behavior remain unchanged. No runtime
artifact, wire format, engine acknowledgement or recovery behavior changes.

**Research:** fresh fourteen-coordinate Central metadata at **22:33:53 UTC /
18:33:53 EDT**, with official compatibility evidence at 22:33:54 UTC, reconfirms
all current selections as latest stable: Maven 3.10.0, wrapper 3.3.4,
clean/resources 3.5.0, compiler 3.16.0, Surefire 3.6.1, jar 3.5.1,
install/deploy 3.2.0, JUnit 6.1.3, Jackson 3.2.4 and SDK s3/URLConnection 2.57.8.
No POM change is needed. Metadata summary SHA-256
`223832852976ae9526ddc0f83ede0ac5e76e70218fdef4a367d6bb936d3b6905`;
research report
`090579498d2b4ee9a865deffd8cc294bd0ab38c72e528463991ca2e21fdb129d`.
Raw working evidence is ignored under `.local-qa/mdb-0051/research-helper/`;
the public build guide owns dated coordinates and primary compatibility links.
Retain project-tested Java 25 wording rather than provider certification.

**Verification:** 28 independent graph invocations cover empty/nonempty/multiple/
repacked/unchanged graphs, exact Usage/read order, prefetch and aggregate pair
budgets, independent bindings, transition errors, no fallback, copied same-lineage
divergence despite candidate agreement, shorter/longer actual histories, damaged/
missing/wrong-ID/lineage/range metadata, duplicate requests, immutable lifetime and
actual LocalObjectStore/owned-journal integration. Six focused owner-read/cleanup
cases observe exact native requested boundaries, actual cap exhaustion without
poisoning, actual I/O poisoning, oversized segment count preflight, rejected wrong
adapter references and propagated cleanup failure. Sixteen independent checked-
range codec cases use literal golden vectors and re-signed header faults to prove
preflight before payload decoding, exact/Long.MAX bounds and complete ID/footer/
record validation when the range matches. Existing codec/shared verifier focused
regression passes 47 prior cases alongside the 16 new cases. All 50 new core
cases pass together.

Four actual SDK/HTTP consumer cases verify genesis, repeated shared dependencies
and exact pair usage, missing/corrupt/forbidden dependency failures and valid but
divergent actual journal history. Graph operations issue immutable GETs only;
fixture setup uploads are distinct, and original constant-ETag byte plus changing-
token head suites remain unchanged. Fixtures establish no live-provider, TLS/
signature authentication, remote durability, replication or throughput claim.

Final `./mvnw -B clean install` passes **1,092 core tests**;
`./mvnw -B -f adapters/s3/pom.xml clean verify` passes **120 optional tests**,
all zero failures/errors/skips locally. All fourteen examples run against only
the packaged core JAR. Archive/`jdeps` inspection finds **153 core production
classes**, java.base-only external modules, no SDK/test classes and the unchanged
one-class shared conformance classifier. Preserve all existing temporal/reference,
local process/recovery, reader/reclamation, native cleanup and ownership evidence.

**Documentation and independent review:** add graph guide/ADR 0025; reconcile
README, homepage/roadmap/build/S3 guides, architecture/persistence/head protocol,
decision index/nav and root agent rules. Review confirms production caps, cleanup,
complete-result scope and legacy paths; doc review corrects one precision issue:
only a header/frame exceeding the remaining total prefix cap is rejected before
its body, while other valid divergence fails the final comparison. Both strict
project-path and production builds pass source/HTML/links/search/sitemap for
**59 pages**, restoring production output. Rendered review covers desktop light,
390-pixel dark operations/budget tables with no page-wide overflow, actual search
results/new routes and decision navigation. No diagram/renderer or dependency
change is needed. Helpers own only bounded independent research/review and their
assigned test files; coordinator owns production/docs/shared planning/Git.

**Discovered follow-up:** admit independent MDB-0054 after publisher integration,
starting with early expected-range validation in HistoryCompactor and auditing
other legacy bounded consumers. Code review confirms its manifest-based record
reservation currently precedes raw decoding whose range comparison occurs only
after records are materialized. Malformed input may exceed that caller reservation,
although format ceilings still apply and no invalid plan escapes. The checked
object consumer already prevents this; preserving existing raw-path errors here
keeps the maintenance work separate without weakening this child's acceptance.
Require an independently malformed-body/range regression and unaffected valid
compaction/reference outcomes in that future step. No fix starts during this one.

**Source completion/delivery:** mark MDB-0051 done and MDB-0052 ready, retaining
expanded publication parents. Validate **54 unique IDs**, cards, references,
parent/dependency DAG, eligibility and completed children. Inspect explicit owned
paths/staged diff, commit with Step-ID MDB-0051 / Step-Result completed, push
normally and verify remote ancestry/tips, Java/docs CI, automatic Pages and live
new guide/ADR/callers/canonical/search/sitemap. Git and the delivery receipt supply
actual SHA/check results. No later implementation, release/tag/artifact upload,
real-cloud/account/DNS or other hosting action is included.

**Next recommendation:** MDB-0052 — durable object publication intent, preserving
exact expected/pending observations and original preconditions through local
failure/process exit before conditional publisher/reconciliation integration.

## 2026-10-31 — MDB-0051 Delivery and publication readback

Source completion **`5d0255b2f2a6ebef468f26f7366f4ae0d2ac65ec`** reaches origin/main
with matching tips, verified ancestry and clean source checkout. Java checks
**41249949891**, documentation checks **41249949945** and automatic Pages
**41249949841** all finish successfully. Ubuntu runs **1,092 core / 120 optional**
tests without failures/errors/skips; Windows runs the same totals without failures/
errors and retains **228 pre-existing core filesystem capability skips**. Both
platforms run all **28 graph, six owner-read/cleanup, sixteen checked-range and
four SDK graph-consumer** cases without skips. Every one of the fourteen example
steps succeeds on both platforms. No live-provider or stronger durability claim
is inferred from these results.

Live HTTPS readback verifies the new graph guide/ADR 0025, head protocol, current
build research/test counts, conditional-head guide, roadmap and homepage capability
copy. Canonical URLs are correct, search contains both new routes/titles and the
sitemap has **59 HTTPS canonical pages**. Browser readback confirms the live guide;
`.local-qa/mdb-0051/site-published.png` is embedded in the delivery update. Temporary
viewport/theme changes are restored. The preview tab now shows the live deliverable
and the owned loopback static server is stopped.

Queue/card delivery state is reconciled. The internal-only receipt uses a normal
commit/push, with its own identity/remote verification supplied by Git; these paths
trigger no additional configured workflows. MDB-0051 is done and MDB-0052 ready;
MDB-0049/MDB-0037 remain expanded. Independent MDB-0054 retains the legacy bounded-
consumer range-preflight follow-up outside the publication parents. No later
implementation, release, real-cloud/account/DNS or hosting-setting change occurs.

**Next recommendation:** MDB-0052 — durable object publication intent, preserving
exact expected/pending observations and original preconditions across process exit
before conditional publisher/reconciliation integration.

## 2026-10-31 — MDB-0055 Bounded S3 publication state

**Selection and refinement:** start from clean main/origin
`422f7a73995b4ac0abfca113b8329a1e3b5b1a2f`; verify preceding source
`5d0255b2f2a6ebef468f26f7366f4ae0d2ac65ec`, Java/docs/Pages and live delivery.
Expand MDB-0052 into required MDB-0055 (pure format/transitions) and MDB-0056
(durable supported local placement). Preserve the original parent acceptance and
MDB-0053's dependency on complete MDB-0052. Independent MDB-0054 remains separate.

**Outcome:** add immutable S3PublicationState, S3PublicationStateCodec and typed
failures in the optional artifact. Preserve exact endpoint URI spelling, explicit
region ID, bucket, namespace/publisher/lineage, original expected image/opaque
quoted token and at most one exact pending candidate. Creation derives literal
unquoted `*`; replacement derives the original quoted expected ETag. No credentials,
clients, filesystem/network operations or core/dependency changes are introduced.
Pure fresh/prepare/exact-candidate recognition increments an independent positive
local generation and rejects overflow, wrong scope/binding, nonadjacent candidates
and another prepare while pending. Old/missing heads or errors supply no clearing,
adoption, cancellation or rebase API. Recognition proves no GET provenance, complete
graph, force, durable installation or selection. Decoding restores declarations,
not authenticated transition history; generation has no revision/parity coupling.

Version 1 is big-endian with magic MRDNSPST, exact total length, flags/reserved,
independent generation, raw bindings, length-prefixed bounded ASCII target/token,
optional complete 256-byte expected/pending images and explicit conditional operation.
SHA-256 protects all preceding bytes; the complete reference also includes that
footer. Enforce 4,096 total bytes before copying and per-field caps before allocation;
all legal fields together fit 3,923 bytes. Reject extra/truncated/corrupt/unsupported
or contradictory state and preserve nested causes. Keep full UTF-16 lineage identity
through its canonical digest. Durable local ownership/force/poisoning/reopen and
conditional publisher/reconciliation remain required future work.

**Research:** current Central metadata at **23:00:33 UTC / 19:00:33 EDT**, official
compatibility refreshed at 23:00:34 UTC, reconfirms all fourteen pins as latest
stable: Maven 3.10.0, wrapper 3.3.4, clean/resources 3.5.0, compiler 3.16.0,
Surefire 3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3, Jackson 3.2.4 and
optional SDK s3/URLConnection 2.57.8. No POM edit is needed. Metadata summary SHA-256
`fe00fe114a7489c2f020bf0aa17c0440d6ac834fc9bd03bfefa32a13804f5a15`;
compatibility summary `8c7440bc629af48783d0b5cdca7497a5364c81739f9b7508c181e1b0018c1a8f`;
research report `67de9b5d4a89d2dabdbb68d69b1a7a696c3db27a39eadd96ef6e3469fe7b575a`.
Ignored raw evidence lives under `.local-qa/mdb-0055/research-helper/`; public build
copy owns dated coordinates/primary links and retains project-tested Java 25 wording.

**Verification:** 36 state/transition invocations cover four states, original tokens,
exact lexical scope/configuration, bindings/images, copies, caps, lineage and overflow.
29 codec cases include four independent Python struct/hashlib literal golden vectors
(176/436/443/703 bytes), all 1,758 shorter images and all 1,758 single-byte mutations,
re-signed framing/field/nested faults, full reference versus footer, maximum image
and ownership. Existing 70 head regression cases pass. Final serial
`./mvnw -B clean install` and `./mvnw -B -f adapters/s3/pom.xml clean verify`
pass **1,092 core / 185 optional tests**, zero local failures/errors/skips.
All fourteen examples run using only the packaged core JAR. Archive/jdeps inspection
retains 153 core production classes, java.base-only external modules, no SDK/test
classes and the unchanged one-class conformance test classifier.

**Documentation and review:** add the format guide and ADR 0026; reconcile README,
homepage, roadmap, build/S3/graph guides, architecture/persistence/head protocol,
nav/decision index and root rules. Independent production review finds no actionable
issue; doc review corrects the precondition wording so only replacement is quoted.
Both strict project-path and production builds pass source/rendered/links/search/
sitemap checks for **61 pages**, restoring production output. Rendered desktop
light and 390-pixel dark tables have no page-wide overflow; search finds both new
routes and decision navigation works. No diagram/renderer is needed. Helpers own
bounded research/review and assigned test files; coordinator owns production,
documentation, shared planning and Git.

**Source completion/delivery:** mark MDB-0055 done, clear active ownership and mark
MDB-0056 ready, subject to normal delivery. Validate 56 unique IDs, cards, references,
parent/dependency DAG and eligibility; inspect explicit task-owned staged paths.
Commit with Step-ID MDB-0055 / Step-Result completed, push normally, verify remote
ancestry/tips, Java/docs CI, automatic Pages and live guide/ADR/callers/canonical/
search/sitemap. Git and the later delivery receipt supply actual SHA/check identities.
No next implementation, release/tag/artifact upload, real-cloud/account/DNS or other
hosting action is included.

**Next recommendation:** MDB-0056 — durable local publication state, preserving exact
expected/pending bytes and preconditions through supported exclusive placement,
ordered force/atomic installation, poisoning and real process-exit/reopen.

## 2026-10-31 — MDB-0055 Delivery and publication readback

Source completion **`742d52ed8cc4f6de31db4c7c5fc6d7fef5a68c54`** reaches origin/main
with matching tips, verified ancestry and clean source checkout. Java checks
**41252084742**, documentation checks **41252084800** and automatic Pages
**41252084803** all finish successfully. Ubuntu runs **1,092 core / 185 optional**
tests without failures/errors/skips; Windows runs the same totals without failures/
errors and retains **228 pre-existing core filesystem capability skips**. All
**36 new state/transition and 29 codec cases**, and all other optional cases, run
without skips on both platforms. All fourteen example steps succeed on both.
Both docs URL builds also pass the unchanged 42 QA-rule fixture tests.

Live HTTPS readback verifies the new state guide/ADR 0026, head protocol, build
research timestamp/test counts, conditional-head guide, roadmap and homepage.
Canonicals are correct, search contains both new routes/titles and sitemap has
**61 unique HTTPS canonical pages**. Browser readback confirms the live guide;
`.local-qa/mdb-0055/site-published.png` is saved and embedded in the delivery update.
Temporary viewport/theme changes are restored; the preview tab shows the live
user-facing guide and the owned loopback static server is stopped.

Queue/card delivery state is reconciled. This internal-only receipt receives a
normal commit/push with its identity and remote verification supplied by Git;
these paths trigger no further configured workflows. MDB-0055 is done and MDB-0056
ready. MDB-0052/MDB-0049/MDB-0037 remain expanded with every original durability/
publisher criterion preserved; MDB-0053 still depends on complete MDB-0052.
Independent MDB-0054 remains queued outside those parents. No subsequent
implementation, release, real-cloud/account/DNS or hosting-setting change occurs.

**Next recommendation:** MDB-0056 — durable local publication state, preserving
exact expected/pending bytes and original preconditions under supported exclusive
placement, staged force/atomic installation, poisoning and process-exit/reopen.

## 2026-10-31 — MDB-0056 Durable local publication state

**Selection:** start from clean main/origin `8df2b69e4179a12d0639efd230a755d6550f8ead`;
verify MDB-0055 source `742d52ed8cc4f6de31db4c7c5fc6d7fef5a68c54`, successful Java/
docs/Pages and live delivery. Execute this required child, preserving all MDB-0052/
MDB-0049/MDB-0037 criteria and the independent MDB-0054 follow-up.

**Outcome:** add LocalS3PublicationState in the optional artifact over the unchanged
state codec. A dedicated existing trusted, stable, durably named directory supports
separate empty create and existing-state open. Create-only permanent ownership,
shared same-JVM file reservations and native locking exclude cooperating control/
data aliases before another native open. The lock is forced through its owner.
Bound discovery to 16,384 entries and 1,024 selected generations; cap state size
before allocation, complete short I/O and inspect EOF/unchanged size. Validate the
highest unique selected name, complete identity/generation and managed target/
bindings; reject malformed/duplicate/missing/damaged newest state without fallback
or pending adoption. Open resynchronizes lock/selected file/directory before use.

Prepare and exact-candidate recognition preflight pure transitions, revalidate
current selected metadata and reserve capacity, then create/write/force/close a
unique pending image, atomically rename it and force the directory before exposing
new state. Preserve exact original candidate/expected bytes and preconditions;
there is no old-head/absence/error clear/reset/rebase path. Storage failures poison;
mark uncertainty before rename, retain old/pending evidence and reopen to establish
selected state. Close invalidates access before cleanup and installs nothing.
Uncertain reserved native cleanup remains quarantined until process exit. No
remote request, graph verification, engine acknowledgement, deletion, distributed
fence or external-rollback/copy detection is added. Default Windows filesystem
placement is explicitly unsupported by this adapter; ordinary directory I/O on
other placements propagates rather than masquerading as a capability skip.

**Research:** all fourteen Central coordinates rechecked **23:29:10 UTC /
19:29:10 EDT**, with official compatibility refreshed 23:29:11 UTC, remain latest
stable at the selected pins: Maven 3.10.0, wrapper 3.3.4, clean/resources 3.5.0,
compiler 3.16.0, Surefire 3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3,
Jackson 3.2.4 and optional SDK s3/URLConnection 2.57.8. No POM change is required.
Metadata summary SHA-256 `a82d780b1be5d9aad8d066bd6494ad7f0c6f263a4a837efbd005eeb4f1623a51`;
compatibility summary `22c88e2f416f4e1aaefd12cbb64c0212c0f2322fc3d431b2233cfb8aabec84c5`;
report `eae2a9d60b4d6a925a84d5617b1238898f168ccc36180dc43bbda899a36ab7f3`.
Fresh JDK 25 force/lock/move and Lucene 10.5.2 primary evidence at **23:33:55 UTC**
has summary `006b8bdbe7c5a01a8eaf817a6c417d054f46908eda5fcb7501b9d34d7c7da6ff`.
Preserve the distinctions: JDK atomic target replacement is implementation-specific;
Lucene's ordering analogy permits dual names and different failure cleanup, while
this protocol requires atomic move and retains evidence. Ignored raw material lives
under `.local-qa/mdb-0056/research-helper/`; public ADR/build retain primary links.

**Verification:** 34 real filesystem/API cases cover exact four-state reopen flows,
pure-invalid healthy owner, ownership/close, wrong configuration, missing/bad/
partial/oversized/duplicate newest state, whole IDs/generation names, nofollow links,
shared LocalJournal/LocalCheckpointStore control and selected-data aliases, retained
unknown/pending evidence and exact/over capacity. 31 fault invocations cover explicit
write/force/close/move ordering, before/after-effect I/O, runtime/Error poisoning,
reentrancy, stale/corrupt/capacity rejection before candidate effects, bounded short/
zero/invalid progress, changed sizes, force/close suppression, unsupported-before-
mutation and ordinary I/O identity. Native cleanup quarantine runs in a child JVM.

21 process invocations halt at candidate-force, rename and directory-force during
initial creation, pending create/replacement and both observation installations;
verify exact selected/candidate bytes, original quoted token, independent generation,
repeated pre-rename candidates and no missing-state initialization. Competing JVMs,
graceful/abrupt owner exit, same-JVM rejection preserving the process lock and
damaged-newest reopen rejection pass. Child deadlines/logs are bounded/outside store
directories. Process termination is not a device-power-loss simulation.

An initial combined run exposed a fixture isolation error: deliberately quarantined
ownership lived beyond temporary-path deletion and contaminated later cases. Move
that intentional failure into a child process so its required restart precedes
path cleanup; persist this procedure in agent rules. Subsequent combined 52 fault/
process cases and all 34 API cases pass. Independent review also corrects broad
IOException-to-unsupported classification and qualifies reserved-channel cleanup;
probe cleanup suppresses runtime/Error failures as well as IOException.

Final serial `./mvnw -B clean install` and
`./mvnw -B -f adapters/s3/pom.xml clean verify` pass **1,092 core / 271 optional**
cases with zero local failures/errors/skips. All fourteen examples run against only
the packaged core JAR. Archive/jdeps inspection retains 153 production classes,
java.base-only external modules, no SDK/test classes and unchanged one-class shared
conformance classifier. No core source, format, dependency, temporal or acknowledgement
contract changes are made.

**Documentation and completion:** add local state guide/ADR 0027; reconcile pure
state ADR/guide, README, homepage, architecture/persistence/head protocol, build,
S3/graph guides, public roadmap, nav/index and agent rules. Both strict project-path
and production builds pass source/rendered/links/search/sitemap for **63 pages**,
restoring production output. Desktop light and 390-pixel dark tables have no
page-wide overflow; search finds both new routes and decision navigation works.
No diagram/renderer is needed. Helpers own bounded research/review and assigned
test files; coordinator owns production, shared docs/planning and Git.

Mark MDB-0056 and its complete MDB-0052 parent done, clear active ownership and
mark MDB-0053 ready, subject to remote delivery/CI. MDB-0049/MDB-0037 remain expanded.
Validate all 56 IDs/cards/dependencies/eligibility, inspect explicit staged paths,
commit with Step-ID MDB-0056 / Step-Result completed, push normally and verify
remote ancestry/tips, Java/docs CI, automatic Pages and live guide/ADR/callers/
canonical/search/sitemap. Git and the delivery receipt supply actual identities.
No later implementation, release, cloud/account/DNS or other hosting action starts.

**Next recommendation:** MDB-0053 — verified conditional publisher, composing actual
graph/owned-prefix checks, forced exact local intent and conditional requests with
original-precondition reconciliation after uncertain outcomes.

## 2026-10-31 — MDB-0056 Delivery and publication readback

Source completion **`f20939b8e73ce36e230cb6fbb85456d08f849c02`** reaches origin/main
with matching tips, verified ancestry and clean source checkout. Java checks
**41253545589**, documentation checks **41253545552** and automatic Pages
**41253545496** all finish successfully. Ubuntu runs **1,092 core / 271 optional**
tests with zero failures/errors/skips, including every **34 API / 31 fault /
21 process** invocation. Windows runs the same totals with zero failures/errors,
**228 pre-existing core skips** and **55 explicit unsupported-local-placement
optional skips** (32 API, two native fault, 21 process); its 185 pre-existing
optional cases and remaining pure/injected checks run without skips. These results
supply no Windows local-placement durability claim. All fourteen example steps
succeed on both platforms. Both docs URL builds pass the unchanged 42 QA fixtures.

Live HTTPS readback verifies the local state guide/ADR 0027, pure format guide,
head protocol, current build research timestamp, conditional-head counts, roadmap
and homepage. Canonical URLs are correct, search includes both new routes/titles
and sitemap has **63 unique HTTPS canonical pages**. Browser readback confirms the
live guide; `.local-qa/mdb-0056/site-published.png` is saved and embedded in the
user-facing delivery update. Temporary theme/viewport overrides are restored.
The preview tab now displays the live deliverable; the owned loopback static server
is stopped.

Reconcile queue/card delivery state and commit/push this internal-only receipt
normally; Git supplies its identity/remote verification, and these paths trigger
no further configured workflows. MDB-0056 and parent MDB-0052 are done; MDB-0053
is ready. MDB-0049/MDB-0037 remain expanded and retain full verified conditional
publisher/reconciliation criteria. MDB-0054 remains separately queued. No later
implementation, release/artifact upload, cloud/account/DNS or hosting-setting
operation occurs in this receipt.

**Next recommendation:** MDB-0053 — verified conditional publisher, composing
actual graph/owned-prefix checks, forced exact local intent and conditional
requests with original-precondition reconciliation after uncertain outcomes.

## 2026-10-31 — MDB-0057 Verified durable S3 preparation

**Selection/refinement:** start from clean main/origin
`52217a78a4fdc057f0b9ba39369e429902b4a0c8`; verify preceding MDB-0056 source
`f20939b8e73ce36e230cb6fbb85456d08f849c02`, Java/docs/Pages and live readback.
Expand MDB-0053 into required MDB-0057 preparation and MDB-0058 submission/
reconciliation plus all original parent integration acceptance. Preserve expanded
MDB-0049/MDB-0037 and independent MDB-0054; allocate only new IDs 57/58.

**Outcome:** add optional S3PublicationPreparer over caller-owned concrete object/
head clients and local state/checkpoint/journal owners. Snapshot the exact candidate
through existing pure state checks; refuse pending/invalid transitions first.
Validate actual SDK lexical endpoint, region, bucket and head binding against
managed local state, plus actual journal lineage/canonical checkpoint path. Add
narrow package client checks and a local capacity preflight sharing existing slot
validation. No caller-supplied target can mask a differently configured client.

Hold monitors in local-state/checkpoint/journal/head/object order through preparation.
Preflight local capacity, issue one bounded current-head GET and compare original
expected bytes AND token; only independently fresh local state accepts absence.
Verify the candidate or both adjacent graphs with one shared graph budget and
actual owned prefixes. Synchronize the actual journal/name through its owners,
create or fully verify/reuse exact ns-N-head-H.bin, then force the same pending
image/precondition locally. Recheck local capacity at installation. Return a private-
constructed immutable Preparation containing pending state, complete graph evidence,
intent identity/creation and observed forced sequence. Close no caller resources,
perform no mutable selection PUT, and expose no pending clearing or adoption.

Graph usage stays distinct from fixed metadata allowance: at most one 256-byte head
GET, one 256-byte immutable PUT and one 256-byte reuse GET (three logical SDK calls /
768 normal body bytes), excluding existing protocol/error-body caps/transport
retransmission. Local preflight/installation scans and codec copies are separately
bounded. File force may flush an appended suffix and is not a prefix-budget/device-
write-byte or aggregate wall-clock claim. Retain old/pending/failed immutable
objects; no selection, lease, engine acknowledgement or deletion follows.

**Research:** Central metadata freshly checked **November 1, 00:22:56 UTC /
October 31, 20:22:56 EDT**, compatibility at 00:22:57 UTC, reconfirms all fourteen
selected pins as latest stable: Maven 3.10.0, wrapper 3.3.4, clean/resources 3.5.0,
compiler 3.16.0, Surefire 3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3,
Jackson 3.2.4 and SDK s3/URLConnection 2.57.8. No POM change. Metadata summary
SHA-256 `423b68b732e21d530a5aade56b39459665141f2cbb7d6e04683b272b8c9313c7`;
compatibility `3fb00c99609af4e9e690f4b9c45d26fdb81344c79a409d5145978a436365caef`;
report `666a40a98e155bed6adb180069c392a028748e4b2eb83e78b41f276b2d3d1c6a`.
Six AWS primary pages checked **November 1, 00:24:54 UTC / October 31, 20:24:54 EDT**
have summary `2dfcb12420a729ddd92a8e893dbba116d9ffe415f5396b987425fbfce32d4456`.
They preserve completed-write single-key consistency, no multi-key transaction,
opaque ETag/delete-marker/permission limits and no old-head proof of a finished
in-flight request. Ignored raw evidence lives under `.local-qa/mdb-0057/research-helper/`;
public build/ADR retain dated primary links and tested-Java-25 wording.

**Verification:** 36 actual SDK/local API cases cover empty/nonempty genesis,
replacement/repacking with pair Usage, exact pending reopen/token/immutable reuse,
nine actual-client scope/binding variants, actual lineage/path, healthy pure errors
and early pending rejection. Remote absence/unexpected/changed bytes/token fail
closed; damaged selected graph has no fallback, and equal-lineage copied divergent
actual history fails despite candidate agreement. Four head-known budgets and a
combined pair budget reject before force/intent/pending effects; intent keys match
the specified exact namespace/full identity.

15 boundary cases cover all four ordered probes for creation and replacement,
exact force/immutable/local effects, caller resource usability, verified identical
reuse, corrupt intent conflict, lost immutable replies before/after effect with
explicit same-byte retry, and real 1,024-generation capacity before requests/force/
upload. Two child-JVM halts leave exact immutable intent and only durably installed
pending with original token; before-state halt resumes by explicit verified reuse.
Logs are outside store directories and child lifetimes bounded. Two concurrency
cases prove all five cooperating closes block until preparation completes and
reentrant preparation issues no extra request. Every test asserts no mutable head PUT.

Initial complete adapter run found one incorrect test expectation for a 255-byte
candidate: the existing snapshot guard uses IllegalArgumentException, not a codec
INCOMPLETE exception. Correct the fixture to the existing contract. Clean recheck
passes **324 optional cases**, zero failures/errors/skips; clean core install passes
**1,092 cases**, also no local skips. All fourteen examples run against only the
packaged core JAR. Archive/jdeps retain 153 production classes, java.base-only
external modules, no SDK/test classes and unchanged one-class conformance classifier.
No core source, wire format, dependency or acknowledgement contract changes.

**Review/docs/completion:** independent production review finds no actionable issue;
doc review corrects error classification for journal placement versus SDK/lineage
configuration. Add guide/ADR 0028 and reconcile README/homepage, architecture,
head protocol, local/graph/S3 guides, build, roadmap/nav/index and root rules.
Both strict project-path and production builds pass source/rendered/links/search/
sitemap for **65 pages**, restoring production output. Desktop light and 390-pixel
dark limits copy have no page overflow; search/new routes and ADR navigation work.
No diagram/renderer change is needed. Helpers own bounded research/review and
assigned test files; coordinator owns production/shared docs/planning/Git.

Mark MDB-0057 done, clear active claim and mark MDB-0058 ready subject to delivery.
Validate 58 IDs/cards/references/DAG/eligibility; inspect explicit owned staged paths,
commit Step-ID MDB-0057 / Step-Result completed and push normally. Verify remote
ancestry/tips, Java/docs CI, automatic Pages and live guide/ADR/callers/canonical/
search/sitemap. Git and the delivery receipt supply actual identities. No later
implementation, release, real-cloud/account/DNS or hosting-setting action starts.

**Next recommendation:** MDB-0058 — conditional submission and reconciliation,
resuming exact durable pending intent and completing all original publisher parent
criteria for uncertainty, late effects, retries, process reopen and retained evidence.

## 2026-10-31 — MDB-0057 Delivery and publication readback

Source completion **`2c2341420f0d54a25606156d19151d30f4c963b8`** reaches origin/main
with matching tips, verified ancestry and clean source checkout. Java checks
**41257367765**, docs **41257367845** and automatic Pages **41257367777** all pass.
Ubuntu runs **1,092 core / 324 optional** tests without failures/errors/skips,
including all **36 API, 15 boundary/process and two concurrency** new cases.
Windows runs the same totals without failures/errors, retaining **228 pre-existing
core skips** and **107 explicit unsupported-local-placement optional skips**:
55 earlier native cases plus 35 new API, 15 boundary/process and two concurrency.
Its independent exact-key case and existing pure/SDK checks run. No Windows local
durability claim follows. All fourteen example steps succeed on both platforms;
both docs URL jobs also pass the unchanged 42 QA-rule fixtures.

Live HTTPS readback verifies the preparation guide/ADR 0028, local-state guide,
head protocol, current build timestamp, conditional-head counts, roadmap and
homepage. Correct canonicals, both new search routes/titles and **65 unique HTTPS
canonical sitemap pages** pass. Browser readback confirms the published guide;
`.local-qa/mdb-0057/site-published.png` is saved and embedded in the user-facing
update. Temporary viewport/theme changes are restored; the preview tab displays
the live deliverable and the owned loopback static server is stopped.

Reconcile queue/card state and commit/push this internal-only receipt normally;
Git supplies its own identity/remote verification, and these paths trigger no
further configured workflows. MDB-0057 is done and MDB-0058 ready; MDB-0053,
MDB-0049 and MDB-0037 remain expanded with all original submission/reconciliation/
late-effect acceptance intact. MDB-0054 stays independently queued. No later
implementation, release/artifact upload, real-cloud/account/DNS or hosting-setting
change occurs in this receipt.

**Next recommendation:** MDB-0058 — conditional submission and reconciliation,
resuming exact durable pending intent and completing every original publisher
parent criterion for uncertainty, late effects, retries, process reopen and evidence.

## 2026-10-31 — MDB-0058 Conditional submission and reconciliation

**Selection:** start clean at main/origin `e3ea7d248ce3fdcc3d4754f4eb9dda1c1c49a544`;
verify MDB-0057 source `2c2341420f0d54a25606156d19151d30f4c963b8`, Java/docs/Pages
and live delivery. Resume this last required publication child; allocate no new IDs.
Preserve every original MDB-0053/MDB-0049/MDB-0037 integration criterion.

**Outcome:** add optional S3CheckpointPublisher.submit/reconcile and typed checked
failure. Both require exact durable pending state, actual client/journal admission,
complete immutable intent evidence and an available local result generation/slot
before remote work. Share the existing concrete-resource admission helper with
preparation without changing its behavior. Hold local/checkpoint/journal/head/object
monitors throughout; caller ownership and normal local acknowledgement are unchanged.

An exact candidate observation permits complete original graph/owned-prefix checks,
owner/name force and durable local expected-state installation, with no remote PUT.
Only original predecessor bytes/token or independently fresh absence permits an
explicit submit. Verify the full original graph set, reserve an identical second
pass within the same total budget BEFORE force/PUT, force actual journal/name and
attempt one original-precondition/candidate SDK write. Require fresh exact-candidate
readback, the second complete original graph pass using remaining limits and forced
local result before success. No automatic retry loop, reset/adoption/rebase, fallback,
new identity, engine acknowledgement or deletion is introduced.

A failed PUT reply may be resolved by exact-candidate observation and complete
remaining checks; Result records immutable expected metadata, summed Usage, logical
write attempt/reply and optional known failure code. Old/missing/error readback
cannot establish that a request finished without effect and leaves original intent.
After result-installation uncertainty, validated reopen may find pending or already
installed expected state; an error does not prove rollback. Existing native poisoning/
quarantine remains. Metadata allowance is four logical SDK calls /1,024 normal body
bytes for actual submit, or two GETs/512 bytes without PUT, outside total graph usage,
framing/error caps, local scans/copies/open recovery and suffix-inclusive force work.
Single placement/no-external-rollback conditions remain assumptions, not fencing.

**Research:** Central at **November 1, 00:39:22 UTC / October 31, 20:39:22 EDT** and
official compatibility at 00:39:23 UTC reconfirm all fourteen selected pins latest
stable: Maven 3.10.0, wrapper 3.3.4, clean/resources 3.5.0, compiler 3.16.0,
Surefire 3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3, Jackson 3.2.4 and
optional SDK s3/URLConnection 2.57.8. No POM change. Metadata summary SHA-256
`5d1538d31e3eec06781ffdb8d31639f88ec9fe76e82505c1154fab3a07b52fa0`;
compatibility `c33b59b586c984cbe784c0ed4ff0276345332ae1ed4ac482022706aed3dd9929`;
report `2d1e220777f37ca267ef13e3c6dcb898e7fd5b7eb65643c70417c8e9b09bdce2`.
Six AWS sources at **00:39:23 UTC** have summary
`13b75da127ba80a310d197ebe2a682fd22e676cb89004a0564ac0d8db8c126f9`.
They preserve single-key versus multi-key limits, opaque validators/versions,
permission-dependent absence and original-intent uncertainty. Raw evidence is
ignored under `.local-qa/mdb-0058/research-helper/`; public build/ADR retain dates,
primary links and project-tested Java 25 wording.

**Verification:** 39 actual SDK/local API invocations cover empty/nonempty genesis,
replacement/repacking and exact original headers, changing-token candidate acceptance,
summed two-pass/pair Usage and one-pass/no-PUT reconciliation. Reject absent pending,
nine actual client scope/binding variants, wrong journal lineage/path, copied same-
lineage actual divergence, missing/truncated/changed immutable intent without repair,
damaged predecessor/candidate dependencies without fallback, old/missing/changed
head/token and all four second-pass budget dimensions before effects. Real 1,024-
generation capacity and codec-valid Long.MAX_VALUE pending states refuse before
remote work while preserving exact stored state and usable owners as applicable.

30 recovery invocations include creation/replacement lost-before explicit retry,
lost-after exact resolution with FAILED reply evidence,409/412/403, malformed GET,
small/huge200Error replies and acknowledged PUT with captured old readback. Two
actual competing heads installed between GET and PUT prove provider conditional
rejection without clearing or rebasing pending. Two held original requests time
out, return an actual old-head observation, refuse new preparation, survive local
owner reopen and later take effect; reconciliation then clears exact pending with
NO further PUT. Six child-JVM exits cover creation/replacement after write, verified
candidate and synchronized local result, using a parent-owned server, bounded
process/log lifetimes and exact state/generation/intent checks.

Nine additional cases inject before/after actual local candidate write/rename/
directory-force while remote selection has succeeded, check owner poisoning and
validated exact pending/expected reopen, and reconcile only when still pending.
Post-PUT graph corruption fails without clearing or fallback. All five cooperating
closes wait through selection/result installation; reentrant operations issue no
extra request. The new combined bounded fixture uses real SDK calls, changing
opaque tags and retained immutable routes. Existing fixtures/tests remain unchanged;
no TLS/signature authentication, live-provider, device-power-loss or throughput
qualification is inferred.

Independent review finds and closes a generation-arithmetic admission gap:
positive codec-valid Long.MAX_VALUE pending state now rejects before I/O, with
submit/reconcile regressions. Documentation review also clarifies that no remote
PUT is different from no local write. Initial 76 focused cases and the first clean
1,092 core/400 optional build pass; add the two explicit conditional race cases,
then final clean optional verification passes **402 cases**, zero failures/errors/
skips. Core remains **1,092 cases**, zero local skips. Fourteen examples run with
only packaged core; archive/jdeps retain 153 production classes, java.base-only
external modules, no SDK/test classes and unchanged one-class test classifier.
No core source, dependency, wire format or transaction acknowledgement changes.

**Documentation/completion:** add guide/ADR 0029 and reconcile current implemented/
planned claims across README/homepage, architecture/persistence/protocol, build,
head/graph/state/preparation/local guides, earlier ADR statuses, roadmap/nav/index
and agent rules. Both strict project-path and production builds pass source/HTML/
links/search/sitemap for **67 pages**, restoring production output. Desktop light
and 390-pixel dark operations tables have no page overflow; search and ADR navigation
pass. No diagram/renderer change is needed. Helpers own bounded research/review,
combined fixture and assigned tests; coordinator owns production/shared docs/Git.

Mark MDB-0058 plus complete parents MDB-0053/MDB-0049/MDB-0037 done, clear active
ownership and mark independent MDB-0054 ready subject to delivery. MDB-0011 remains
expanded for engine recovery, reader-safe remote reclamation and common conformance.
Validate all 58 IDs/cards/DAG/eligibility, inspect explicit staged paths, commit
Step-ID MDB-0058 / Step-Result completed, push normally and verify remote ancestry/
tips, Java/docs CI, automatic Pages and live guide/ADR/callers/canonical/search/
sitemap. Git and the delivery receipt supply actual identities. No later task,
release/artifact upload, cloud/account/DNS or hosting-setting change starts.

**Next recommendation:** MDB-0054 — legacy bounded segment preflight, admitting
expected ranges before payload materialization while preserving valid compaction
results, existing error behavior and the independent maintenance scope.

## 2026-10-31 — MDB-0058 Delivery verification

Source completion **`9e07766de3ae79bbcd52af048e72099e86c7840d`** carries
`Step-ID: MDB-0058` / `Step-Result: completed`, including implementation, public
documentation, agent rules and planning. Normal push to origin/main succeeds;
fresh fetch confirms matching tips and source ancestry with a clean working tree
before this internal delivery receipt.

- [Java checks 41259110096](https://github.com/MeridianDB/meridiandb/actions/runs/41259110096)
  passes both platforms. Ubuntu runs 1,092 core and 402 optional cases with zero
  failures/errors/skips, including all 78 new publisher cases. Windows reports
  zero failures/errors with 228 core and 185 optional explicit capability skips,
  including the 78 new local-placement-dependent cases. All fourteen examples
  pass on both platforms. This does not qualify Windows local publisher placement.
- [Documentation checks 41259110074](https://github.com/MeridianDB/meridiandb/actions/runs/41259110074)
  passes both production and project-path strict builds; each runs all 42 QA-rule
  fixtures and validates source, rendered HTML, links, search and sitemap.
- [Automatic Pages 41259110065](https://github.com/MeridianDB/meridiandb/actions/runs/41259110065)
  passes build and deployment, including 42 QA-rule fixtures. HTTPS readback
  verifies the new publication guide and ADR 0029 plus protocol, build, head,
  byte-object, preparation, local-state, roadmap and home pages, each with its
  own canonical URL and current implemented/planned wording. Both new titles/
  routes appear in search and the sitemap has 67 unique production HTTPS pages.
  Browser inspection confirms the published guide and saves
  `.local-qa/mdb-0058/site-published.png`; local desktop-light/mobile-dark table,
  search and ADR-navigation evidence is retained in the same ignored directory.
  The owned local HTTP server is stopped after readback.

CI/log/live readback evidence is under `.local-qa/mdb-0058/`; no public source or
workflow changes follow the verified completion. This receipt only reconciles
internal queue/card/history, so its normal push does not trigger another build or
deployment under the existing path filters. Validate all 58 IDs and completed
parent criteria again, stage these three internal paths explicitly, and verify
source ancestry plus matching tips after the receipt push. No release, library
upload, cloud/account/DNS or hosting-setting change occurs.

**Next recommendation:** MDB-0054 — Legacy bounded segment preflight. No active
claim remains; the next invocation starts that independent maintenance step.

## 2026-10-31 — MDB-0054 Legacy bounded segment preflight

**Selection:** start clean on main/origin
`809f669728646d911351dc43949e70996f3ff0a9`; verify previous source
`9e07766de3ae79bbcd52af048e72099e86c7840d` ancestry and successful Java
41259110096, docs 41259110074 and Pages 41259110065 before claiming MDB-0054.
No new IDs or scope expansion. Independent helpers own fresh version research,
read-only production/caller review and the two new test files; coordinator owns
production/docs/planning/Git and all builds.

**Outcome:** add package-private CheckpointVerifier.verifyBounded sharing existing
missing/exact-length admission and the complete-history verifier. Select the
existing independently expected header-range decode before body hashing, record
list allocation and nested decoding. HistoryCompactor uses it after record/byte
reservation; LocalCheckpointStore protected inspection uses it after all root
reservations and exact declared segment reads. Reclaim shares that inspection
before force/removal. Factor actual-journal prefix comparison without changing
ordering. Every matching image still needs complete integrity, identity, lineage,
request uniqueness and reconstructed/actual-prefix verification as applicable.

The caller audit finds only those two legacy aggregate record-budget paths.
ObjectCheckpointGraphs already uses checked ranges; public raw CheckpointVerifier,
ordinary local publish/load/publishCompaction verification and standalone segment
readers retain raw order. A bounded mismatching header now reports SegmentFormatException
INVALID_HISTORY before body/footer/expected-ID failures, instead of spending
unreserved body work and discovering a later manifest mismatch. Update the existing
shifted-range compactor assertion accordingly; preserve valid results, formats,
public APIs, local acknowledgement and retention. No new decoder or dependency.

**Research:** fresh Central and official compatibility sources at **November 1,
01:28:26 UTC / October 31, 21:28:26 EDT** retain all fourteen latest stable pins:
Maven 3.10.0; wrapper distribution/plugin 3.3.4; clean/resources 3.5.0; compiler
3.16.0; Surefire 3.6.1; jar 3.5.1; install/deploy 3.2.0; JUnit 6.1.3; Jackson 3.2.4;
optional SDK s3/URLConnection 2.57.8. No POM/wrapper change. Metadata summary SHA-256
`2984e201f0cee0763e0fd068a3d6299985c2bba2559475bd741273783dbc0285`, compatibility
`b8e85594f1ca6abb69232899d2663b532f4fe3c894faa28ae4fc7c1ca5d725a6`, report
`4b05e9929d1c1f9fc2423e0948b9564105f20658404bee4aca648751152bd6df`.
Raw evidence remains in `.local-qa/mdb-0054/research-helper/`; build guidance retains
primary links and the new check time. This step changes no external backend claim.

**Verification:** 15 pure cases use independently laid-out v1 images/CRC32C/hash
anchors, maximum valid count/range against one reserved record, equal-count shifted
ranges, nested 65,537-operation and footer traps. Confirm bounded-versus-raw
precedence, matching-range corruption/ID checks, exact records/prefix/byte totals,
header checksum before range trust, metadata budget before reads, missing/length
guards, unchanged I/O identity, late dependency stopping/no plan escape and genesis.
Eight local cases cover inspect/removal with nested/footer traps and exact byte/
record budgets, oversized stored images rejected within declared read caps, no
force/write/delete/move effects, immediate protection release and store poisoning.
Two supported real-file cases reacquire protection before poisoned-store close,
reopen to the same refusal and preserve every image/name except optional empty
permanent guard admission. Six other local boundary cases run without filesystem
capability skips. Existing fault/process/temporal conformance remains intact.

159 focused invocations pass with zero failures/errors/skips. Then serial
`./mvnw -B clean install` and
`./mvnw -B -f adapters/s3/pom.xml clean verify` pass **1,115 core / 402 optional**,
zero local failures/errors/skips. Fourteen examples run with only the packaged core
JAR. Archive/jdeps retain 153 production classes, java.base-only external modules,
no SDK/test classes and the unchanged one-class object-conformance classifier.
Independent production/caller review finds no actionable defect. No new process
halt test is needed for this pure admission change; existing crash/recovery and
reclamation process suites run in the full core verification.

**Documentation/completion:** the manifest guide owns bounded admission and failure
precedence; reconcile segment, compaction, inventory/removal guides, ADR 0025 current
status, root rules, README/build counts/commands and planning. Public content keeps
execution history outside docs. Both strict production/project-path builds pass
source/HTML/link/search/sitemap checks for 67 pages; no new route, diagram, renderer
or theme change. Desktop-light and 390-pixel dark checks have no page overflow.
Mark MDB-0054 done, clear its active claim, mark MDB-0038 ready subject to delivery
and validate all 58 IDs/cards/DAG/eligibility. Commit source/docs/rules/planning with
Step-ID MDB-0054 / Step-Result completed, push normally, verify remote ancestry/tips,
CI, automatic Pages and live changed pages/search/sitemap before handing off.

**Next recommendation:** MDB-0038 — Remote checkpoint engine recovery. Refine that
integration card before implementation in the next invocation; preserve temporal
history, original retry identity, actual local journal and acknowledgement semantics.
This receipt starts no later implementation, release, artifact upload or cloud/
account/DNS/hosting-setting change.

## 2026-10-31 — MDB-0054 Delivery verification

Source **`d2a30b950fad629ef38cb4bce4d367cb277b2dd3`** carries
`Step-ID: MDB-0054` / `Step-Result: completed`, including implementation, tests,
public documentation, agent rules and planning. Normal push succeeds; fresh fetch
confirms source ancestry, matching main/origin tips and a clean working tree before
this internal delivery receipt.

- [Java 41261721808](https://github.com/MeridianDB/meridiandb/actions/runs/41261721808)
  passes both platforms and all fourteen examples on each. Ubuntu runs all
  1,115 core / 402 optional cases with zero failures/errors/skips. Windows has
  zero failures/errors, with 230 core and 185 optional explicit capability skips.
  All 15 new pure cases and six platform-independent local cases run on Windows;
  only the two new real-file directory-sync cases skip there. Ubuntu runs all 23.
- [Docs 41261721797](https://github.com/MeridianDB/meridiandb/actions/runs/41261721797)
  passes both production/project-path strict builds and all 42 QA-rule fixtures
  on each. [Automatic Pages 41261721814](https://github.com/MeridianDB/meridiandb/actions/runs/41261721814)
  passes its strict build, 42 fixtures and deployment.
- HTTPS readback verifies current content and own canonical URLs for manifest,
  segment, compaction, inventory, physical-removal, ADR 0025 and build pages. The
  bounded-admission heading/anchor and compaction route appear in search, and the
  sitemap has 67 unique production HTTPS pages. Browser inspection confirms the
  published section and canonical; screenshot is
  `.local-qa/mdb-0054/site-published.png`. Local desktop-light/mobile-dark bounds
  and the remaining build/log/research/readback evidence share that ignored task
  directory. The normal light viewport is restored and the owned local HTTP server
  is stopped.

This internal-only receipt reconciles queue/card/history after verified delivery;
its push triggers no workflow under existing path filters. Revalidate all 58 IDs,
stage only these three internal paths, commit/push normally and confirm matching
tips plus source ancestry. Public source remains the CI/Pages-tested completion.
No later implementation, release, artifact upload or cloud/account/DNS/hosting
change starts. **Next: MDB-0038 — Remote checkpoint engine recovery.**

## 2026-10-31 — MDB-0059 Bounded pinned object history

**Selection/refinement:** start clean on main/origin
`d1a441fa6d063bec137a4b0c5ec06e548cd706d9`. Verify prior source
`d2a30b950fad629ef38cb4bce4d367cb277b2dd3` ancestry and successful Java
41261721808, docs 41261721797 and Pages 41261721814 before work. Expand MDB-0038
into required MDB-0059 same-pass bounded history, MDB-0060 core read-only engine,
MDB-0061 managed S3 startup and MDB-0062 writable/full parent conformance. Preserve
the original parent outcome/acceptance and all children as required; a loaded
history does not satisfy engine recovery. Allocate exactly these four IDs.

**Outcome:** add ObjectCheckpointGraphs.loadHistory(head, limits) and private-
constructed immutable History.graph/usage/records. Collect original JournalRecord
values privately during the existing complete graph/actual-owned-prefix pass.
Make the decoded-source collector package-private for this concrete consumer;
expose no application visitor. Existing verify/verifySuccessor use no-op collectors,
retaining metadata only. No second fetch/decode, lazy result I/O or budget-exempt
cache is introduced. Return records only after complete identity/lineage/range/
request uniqueness/reconstructed-prefix, actual prefix and reader cleanup succeed.
Records cover only the supplied pinned cutoff, excluding any local tail.

The result preserves original transaction order, no-ops, request identities and
UTF-16/interval values, with immutable lists independent of source arrays/close.
It owns no caller journal, adapter or readers and performs no force/create/head
GET/selection. All limits/error behavior remain: graph plus actual-prefix record
work, encoded bytes and logical reads, including repeats. Retaining all records
and list copies adds heap outside exact Usage accounting. Existing full matching
local journal is required; this is no current-selection/lease, missing-journal or
tail reconstruction, engine, acknowledgement, cleanup or replicated-durability proof.
Future core startup must load within its own actual ownership and validate the
frozen complete local tail; it cannot trust an externally supplied History as a
certificate for a reopened/different journal.

**Research:** fresh Central at **November 1, 01:45:34 UTC / October 31, 21:45:34 EDT**
and official compatibility at 01:45:35 UTC retain all fourteen stable pins unchanged:
Maven 3.10.0; wrapper distribution/plugin 3.3.4; clean/resources 3.5.0; compiler
3.16.0; Surefire 3.6.1; jar 3.5.1; install/deploy 3.2.0; JUnit 6.1.3; Jackson 3.2.4;
optional SDK s3/URLConnection 2.57.8. No POM/wrapper change. Metadata summary SHA-256
`7e2b66d0e9496b07d0d21d6255a3db78e6157ced45adab2d67c49387573ad939`, compatibility
`8c2e3d809531649667b284cab3af1283708f0a25dda4d96cad4a7057c1c7d32f`, report
`370bab6dd0f7a9e9e619c9c00d5c782066b001d0e1156711d69cc1bdd5b9e7bd`.
Eight primary XTDB/Lucene recovery sources refreshed at **01:46:44 UTC /21:46:44 EDT**;
summary `487874c7c1287726c3d01c263d8f7a618bba454c824b3ef4e6d47ed458d4f643`.
Keep checkpoint coverage separate from later log entries, explicit out-of-sync
intervention separate from fallback, and committed snapshots separate from NRT
reader visibility/full payload integrity. ADR 0030 retains dated primary links
and distinguishes these comparisons from MeridianDB's own guarantees. Raw evidence
is ignored in `.local-qa/mdb-0059/research-helper/`.

**Verification:** 22 core invocations cover genesis, exact original frames/no-ops/
correction/retraction/Unicode/interval extremes, an older explicitly pinned revision
with valid suffix/newer graph excluded, immutable deep-list lifetime and caller
ownership, metadata-equivalent Usage and exactly one fetch per dependency. Four
head-known budget dimensions and both manifest-discovered dependency limits reject
before excess work; foreign namespace/publisher/actual lineage, divergent/short
actual prefixes and late missing/corrupt/adapter-cleanup errors return no History.
CRC-valid maximum range/nested-payload traps retain early admission; actual local
object/journal integration returns values with no retained native object handles.
Seven real SDK/HTTP invocations cover immutable GET-only materialization, exact
usage/order/cutoff, genesis, late missing/corrupt/malformed bodies, explicit retry,
caller client/credential/journal ownership and immutable post-close lifetime.
Existing native-stream fault checks remain separate; a final heap reader's close
is not falsely presented as a throwable native cleanup seam.

85 focused core cases pass, then serial `./mvnw -B clean install` and
`./mvnw -B -f adapters/s3/pom.xml clean verify` pass **1,137 core /409 optional**,
zero local failures/errors/skips. Fourteen examples run with only the packaged
core JAR. Archive/jdeps find 154 production classes (one new History class), only
java.base external modules, no SDK/test classes and unchanged one-class object
conformance classifier. Independent production/caller and documentation review
finds no actionable issue. Existing temporal/engine/crash/reclamation suites pass;
this step adds no engine behavior or process publication boundary.

**Documentation/completion:** add ADR 0030 and navigable index entry, guide API/
ownership/limits section, root rule and README/homepage/persistence/roadmap/build/
S3 caller updates. Fix stale S3 guides' core counts alongside current 1,137/409
counts. Both strict production/project-path builds validate source, rendered HTML,
links, search and **68 pages**. Desktop-light/mobile-dark checks have no page
overflow; guide-to-ADR/back navigation passes and normal light viewport is restored.
Mark MDB-0059 done, clear the claim and make MDB-0060 ready subject to delivery;
MDB-0038 remains expanded. Validate all 62 IDs/cards/DAG/eligibility, stage explicit
paths, commit Step-ID MDB-0059 / Step-Result completed and push normally. Confirm
remote ancestry/tips, CI, automatic Pages and live guide/ADR/callers/canonical/
search/sitemap before handoff. No later child, library release/upload or cloud/
account/DNS/hosting change starts.

**Next recommendation:** MDB-0060 — Core pinned checkpoint recovery, using the
loaded prefix inside owned startup and applying only the exact contiguous local
suffix before exposing a read-only temporal engine.

## 2026-10-31 — MDB-0059 Delivery verification

Source **`c8d3778f495f971f1a4073e69d0cdb69ddab3133`** carries
`Step-ID: MDB-0059` / `Step-Result: completed`, including production/tests,
documentation/rules and required MDB-0038 expansion into four children. Normal
push succeeds; fresh fetch confirms source ancestry, matching main/origin tips and
a clean working tree before this internal receipt.

- [Java 41263069120](https://github.com/MeridianDB/meridiandb/actions/runs/41263069120)
  passes both platforms and all fourteen examples on each. Ubuntu runs all
  1,137 core /409 optional cases with zero failures/errors/skips. Windows reports
  zero failures/errors with 230 core and 185 optional explicit capability skips.
  All 22 new core and seven SDK cases run without skips on both platforms.
- [Docs 41263069123](https://github.com/MeridianDB/meridiandb/actions/runs/41263069123)
  passes production and project-path strict builds plus all 42 QA-rule fixtures
  on each. [Automatic Pages 41263069096](https://github.com/MeridianDB/meridiandb/actions/runs/41263069096)
  passes its strict build, 42 fixtures and deployment.
- Live HTTPS readback verifies the guide/new ADR 0030 and updated ADR 0025, build,
  both S3 guides, persistence boundaries, roadmap, decisions index and home page,
  each with its own canonical URL and current scope/count/source wording. The new
  guide-heading anchor and ADR title/route appear in search; sitemap has 68 unique
  production HTTPS pages. Browser inspection confirms the published guide and
  canonical URL; `.local-qa/mdb-0059/site-published.png` captures the result.
  Local light/mobile-dark rendering and guide/ADR navigation evidence share that
  ignored directory with research/build/CI/readback logs. Normal light viewport
  is restored and the owned local HTTP server is stopped.

Only queue/card/history follow the verified public source. Their internal-only
receipt push triggers no new workflow under existing filters. Validate all 62
IDs/cards/DAG/eligibility, stage these three paths, commit/push normally and verify
matching tips plus source ancestry again. MDB-0038 remains expanded; no later child,
library release/upload or cloud/account/DNS/hosting action begins here.

**Next recommendation:** MDB-0060 — Core pinned checkpoint recovery, with concrete
owned startup, complete local journal validation and prefix-once/suffix-only replay
before exposing the read-only temporal engine.


## 2026-10-31 — MDB-0060 Core pinned checkpoint recovery

**Preflight/ownership:** start clean main/origin at
`54dbe439e2c9b38bea4de495e1a8636e2452faf5`; verify previous source
`c8d3778f495f971f1a4073e69d0cdb69ddab3133` ancestry and successful Java
41263069120, docs 41263069123 and Pages 41263069096. Claim only MDB-0060.
The coordinator owns production, public documentation, planning and Git; two
bounded helpers supply independent focused/conformance tests and a third supplies
Maven/ownership/documentation review. No helper advances shared planning or Git.

**Implementation/decision:** add six/seven-argument LocalDatabase object-checkpoint
factories and private ObjectSource; no provider dependency or arbitrary backend/
restore callback. Validate arguments before effects, acquire concrete local owners,
freeze observed H, reject H below optional minimum before object reads, and load
History inside that same ownership. Reuse private reconstruction: restore prefix
once, validate complete journal order/cardinality through H, apply only the suffix
and expose no database before success. Preserve primary/suppressed failures and
independently release owners; never close the caller adapter. Ownership lasts until
database close; views invalidate first. Transactions remain rejected, including
exact retries. Ordinary local create/open/writable selection and acknowledgement
are unchanged. Explicit pins intentionally ignore unrelated newer/damaged local
manifests and provider current-head selection; invalid pins never fall back.

ADR 0031/guide/rules explain full matching local journal requirements, genesis C=0,
optional minimum as a sequence floor rather than content/ack proof, unknown clean
lost tails, resource lifetimes and graph limits versus initial journal scanning,
full replay and retained heap. Managed S3 admission, writable remote recovery,
replication and remote deletion remain their separate steps. Preserve all 62 IDs
and every original MDB-0038 criterion; no new item is admitted.

**Research:** all fourteen pinned Maven coordinates freshly checked at
**November 1 02:39:05 UTC /October 31 22:39:05 EDT**, official compatibility
02:39:06 UTC. All remain latest stable: Maven 3.10.0, wrapper distribution/plugin
3.3.4, clean/resources 3.5.0, compiler 3.16.0, Surefire 3.6.1, jar 3.5.1,
install/deploy 3.2.0, JUnit 6.1.3, Jackson 3.2.4 and optional S3/HTTP SDK 2.57.8.
Exclude Maven 4 RC/plugin beta lines; no POM/wrapper change. Metadata summary hash
`fa20566676fa518c2b80a3158505953c07b38f4ff5ea9e909f96e7624c7674b3`,
compatibility `ad35018de45a1868ab24393eea14b1869663c910597179e0de99937cccece86e`,
report `9df95b2db28da142d7d1333d25f531dbdbd85955cd917ceb78edccc572904e4c`.
Reuse MDB-0059's dated XTDB/Lucene primary recovery evidence at 01:46:44 UTC /
21:46:44 EDT; no new external-behavior claim. Raw research/build/QA files remain
ignored under `.local-qa/mdb-0060/`.

**Verification:** 29 focused invocations cover cutoff shapes, empty genesis,
Unicode/interval extremes, original lookup/no-ops, older explicit pin versus newer
or damaged local selection, invalid pin without fallback, both local owner locks,
caller adapter lifetime (including caller close after startup), preflight and
independent bindings, missing/corrupt/digest/divergent graphs, original I/O/runtime/
Error propagation, private post-reconstruction failure, strict incomplete/corrupt/
gapped/reused-ID tails, absent journal, exact/below minimum coverage and budget
failure cleanup. Raw file assertions occur after native owners close.

Forty conformance invocations preserve all five temporal/eight-total histories:
24 genesis/split/full canonical cases run all 174 original literal steps in each
shape (522 total); all canonical reads use the new factory. Sixteen unchanged
persisted seeds commit 32 transactions each through the existing local writer,
then recover each of three pin shapes and compare all 33 historical cutoffs against
the independent reference, dense forward cells and ordinary local reopen.
Get/Scan/Sum/all predicates/FilterScan, absence/extremes and every original request
position agree; raw journal frames remain unchanged. Write/retry acceptance belongs
to the existing local baseline, not a writable object API. Native cases skip only
explicit unavailable directory synchronization.

The initial focused command (new two classes plus LocalDatabaseTest) passes 90
cases before one additional adapter-close case. Final serial `./mvnw -B clean install`
and `./mvnw -B -f adapters/s3/pom.xml clean verify` pass **1,206 core /409 optional**,
zero failures/errors/skips locally. Fourteen examples run with only the packaged
core JAR; archive has 155 production classes and only java.base module dependencies,
no SDK/test classes, and the unchanged one-class test conformance classifier.
Forty-two documentation-rule fixtures and both strict production/project-path
builds pass with 70 pages. Desktop-light/mobile-dark layout has no page overflow;
new guide/ADR navigation and both search results work; normal light viewport is restored.
Independent production review finds no actionable defect; documentation review
corrects stale integration status and distinguishes record cardinality from exact
retained heap costs. Full affected callers and staged diff are reviewed.

**Completion/delivery:** mark MDB-0060 done, clear the claim and make MDB-0061 ready
subject to remote delivery checks; MDB-0038 stays expanded. Validate 62 IDs/cards/
DAG/eligibility, commit task-owned source/docs/rules/planning with Step-ID MDB-0060 /
Step-Result completed, push normally and confirm ancestry/tips. Verify Java/docs/
automatic Pages plus live guide/ADR/callers/canonical/search/sitemap, then record
actual delivery evidence separately. No later child, release/upload or cloud/account/
DNS/hosting action starts.

**Next recommendation:** MDB-0061 — Managed S3 recovery admission, binding actual
client scope and durable publication state to a fresh exact selection before recovery.


## 2026-10-31 — MDB-0060 Delivery verification

Source **`7912f2ffa64cfb7469b1a4663f131b77d3127e3f`** carries
`Step-ID: MDB-0060` / `Step-Result: completed`, with production/tests, public
technical documentation/rules and queue/card/history in one source commit.
Normal push and fresh fetch confirm matching main/origin tips, source ancestry
and a clean working tree before this internal receipt.

- [Java 41266850347](https://github.com/MeridianDB/meridiandb/actions/runs/41266850347)
  passes both platforms and all fourteen examples on each. Ubuntu runs all
  1,206 core /409 optional cases with zero failures/errors/skips, including all
  69 new recovery cases. Windows has zero failures/errors with 298 core and
  185 optional explicit capability skips. Of the new cases, its pure argument
  preflight runs while 28 focused native and 40 canonical/seeded cases skip for
  unsupported directory synchronization. This does not qualify that Windows
  placement for pinned recovery; existing capability requirements stay explicit.
- [Docs 41266850387](https://github.com/MeridianDB/meridiandb/actions/runs/41266850387)
  passes production and project-path strict builds plus all 42 documentation-rule
  fixtures on each. [Automatic Pages 41266850349](https://github.com/MeridianDB/meridiandb/actions/runs/41266850349)
  passes its 42 fixtures, strict build and deployment.
- Live HTTPS readback verifies the new recovery guide and ADR 0031, loader/ADR 0030,
  ADRs 0027/0028, build, both S3 primitive guides, local database, persistence
  boundaries, roadmap, decisions index and home page: fourteen current pages with
  exact canonical URLs and changed implementation/scope/count/source wording.
  The guide and ADR title/route appear in search; sitemap contains 70 unique
  production HTTPS pages. Browser inspection confirms the published guide and
  canonical URL. `.local-qa/mdb-0060/site-published.png` captures the published
  result and was shown in the session; local desktop-light/mobile-dark and guide/
  ADR/search navigation evidence share the ignored task directory. Normal light
  viewport is restored and the exact task-owned local HTTP server is stopped.

Only queue/card/history follow the verified source. Their internal-only receipt
push matches existing workflow path filters and needs no repeated source build.
Validate all 62 IDs/cards/DAG/eligibility, inspect/stage only those three paths,
commit/push normally and reconfirm tips/source ancestry. MDB-0038 remains expanded;
no later child, library release/upload or cloud/account/DNS/hosting action starts.

**Next recommendation:** MDB-0061 — Managed S3 recovery admission, binding actual
client scope and durable expected/pending state to a fresh exact head observation
before invoking the implemented core read-only recovery factory.


## 2026-11-01 — MDB-0061 Managed S3 recovery admission

**Preflight:** clean main/origin at `7caa7cf4e31fb7ed0ba11496f2beaa28afd0d711`.
Verify MDB-0060 source `7912f2ffa64cfb7469b1a4663f131b77d3127e3f` ancestry and
successful Java 41266850347, docs 41266850387 and Pages 41266850349 before claim.
Root owns production/docs/planning/Git. Two bounded helpers supply separate semantic
and startup test files; another supplies fresh research and independent production/
public documentation review. No helper advances planning or Git. Preserve all 62
IDs and all original MDB-0038 criteria; admit no new child or external action.

**Implementation:** add optional S3Database with owned existing publication metadata
and private LocalDatabase recovery. Validate null/minimum before path access and
reject canonical database/state directory identity. Open exact independently
configured metadata; refuse pending/no expected before remote reads. Under metadata,
head-client and object-client monitors, admit actual target/binding, read a complete
fresh head and require both expected bytes and original token. Invoke core pinned
recovery with independent path/minimum; expose only readonly query/lookup APIs and
immutable historical observation. Retain all local owners through wrapper close;
mark closed, invalidate views and independently attempt core/metadata cleanup with
primary/suppressed causes and retry. Caller clients/credentials remain unowned.

No core/wire/POM/SDK configuration or publisher mutation change. State stores no
local path certificate; actual full journal lineage/prefix binds recovery under
existing trusted placement/no-rollback assumptions. A head GET is historical, not
a continuing lease/fence or complete ABA detection. Minimum checks follow head GET
and precede graph reads. Pending requires explicit existing publisher reconciliation
before retry; no auto-adopt, rebase, repair, publication or fallback. Online startup
has no offline fallback; separate explicit local/pinned modes retain their contracts.
Full matching journal remains mandatory. Writable object recovery and full parent
conformance stay MDB-0062; remote reclamation/replication retain separate scope.

**Research:** all fourteen Central pins freshly checked November 1 at
**13:34:35 UTC /08:34:35 EST**, official compatibility 13:34:36 UTC. Toronto's
current offset is EST after the clock change. All latest stable selections remain
unchanged: Maven 3.10.0, wrapper distribution/plugin 3.3.4, clean/resources 3.5.0,
compiler 3.16.0, Surefire 3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3,
Jackson 3.2.4 and SDK S3/HTTP 2.57.8; exclude Maven 4 RC/plugin betas.
Metadata summary SHA256 `014a8f74948341ab85c598cfc894a6023f2a6a157e9b1c508abdf85f8afac46f`,
compatibility `06b452fc42e24146456b4ccafde9c7efee478435db434961fe31360ed161a6fb`.
Official AWS GET/consistency/ETag/versioning/delete-marker evidence refreshed
**13:35:18 UTC /08:35:18 EST**; summary
`f9dbe673d8cd44801bbe3e49edf5751c90af740600e4a79519d2051bfea9918c`.
ADR 0032 links those primary sources and distinguishes exact-match policy from
provider guarantees. Reviewed report SHA256
`a76fbfb1ccff33c2cdbbd90b0efd91ede5b0bcab2eac9fe7d6d57eb1183e5fa0`.
Raw research/build/browser/readback evidence remains ignored in `.local-qa/mdb-0061/`.

**Verification:** 50 SDK/real-local invocations cover genesis/split/full checkpoints,
representative literal correction/retraction/no-op answers at every cutoff of a
four-transaction history and original request lookup; fresh exact body/token and one dependency GET under normal transport;
client close after startup with continued queries; wrapper close preserving clients/
credentials; absent expected and fresh/old/candidate pending across metadata reopen;
explicit actual publisher reconciliation then managed open with no selection PUT;
six configured target/binding and eleven actual client/closed mismatches before GET;
nine missing/foreign/stale/token-only/corrupt/403/dropped-reply heads; damaged newest
state without fallback; four missing/corrupt dependencies; seven actual journal/
coverage/budget failures with specific error types/codes, no repair and exact bytes;
unknown clean tail loss and independent alternate matching path without a path
certificate. Native dropped reads may retry only the head; no graph fallback/write.

Thirteen startup invocations cover all pure argument admission, two canonical
colocation spellings, six HEAD_OBSERVED/RECOVERED I/O/runtime/Error failures with
no exposed wrapper and exact owner/state cleanup, three-owner lifetime/immutable
values, two actual child JVM halts/reopen with exact selection/history, and caller
client close serialization. Concurrency positively observes both close threads
BLOCKED on the held client monitors before permitting reconstruction, then checks
queries/credentials after both closes. Native storage skips only named unsupported
placement/directory synchronization; these are not device-power-loss tests or
injected native uncertain-close qualification. Raw snapshots follow owner close.

Focused two-class Maven check passes 63 cases. Full serial core `./mvnw -B clean install`
and optional `./mvnw -B -f adapters/s3/pom.xml clean verify` pass **1,206 /472**,
zero local failures/errors/skips. Fourteen packaged-core examples pass. Archive/
jdeps confirm unchanged 155 production core classes, java.base only, no SDK/test
classes and one-class conformance classifier. Optional archive has 49 production
classes, including only the three new wrapper/boundary/probe classes, no test/reference
payload. Forty-two docs-rule fixtures and strict production/project-path builds
pass with 72 pages. Guide/ADR navigation, both search results and desktop-light/
mobile-dark layout pass; normal light viewport is restored. Independent code
review finds no actionable issue. Documentation review corrects Windows support
policy wording and keeps fixed successful head body separate from transport retry/
wire work. Reconcile affected README/home/architecture/ADR/guide/roadmap callers;
no stale managed-startup-planned claim remains.

**Completion:** mark MDB-0061 done, clear claim and make MDB-0062 ready subject to
source delivery; MDB-0038 remains expanded. Validate queue/cards/references/DAG/
eligibility, stage explicit task-owned source/docs/rules/planning, commit Step-ID
MDB-0061 / Step-Result completed and push normally. Confirm ancestry/tips, Java/docs/
automatic Pages and live guide/ADR/current callers/canonical/search/sitemap before
an internal delivery receipt. No writable child, release/upload or cloud/account/
DNS/hosting operation begins.

**Next recommendation:** MDB-0062 — Writable remote recovery conformance, retaining
forced acknowledgement and original temporal/request behavior after managed recovery.


## 2026-11-01 — MDB-0061 Delivery verification

Source **`fc366509873a6a4f48eac27595f6017d6344f4d7`** carries
`Step-ID: MDB-0061` / `Step-Result: completed`, including optional production/tests,
public technical documentation/rules and the queue/card/progress source outcome.
Normal push and fresh fetch confirm matching main/origin tips, source ancestry
and a clean working tree before this internal receipt.

- [Java 41313228037](https://github.com/MeridianDB/meridiandb/actions/runs/41313228037)
  passes both platforms and all fourteen examples per platform. Ubuntu runs all
  1,206 core /472 optional cases with zero failures/errors/skips, including all
  63 new cases. Windows has zero failures/errors, 298 core and 245 optional explicit
  placement/capability skips. Three new pure argument/colocation invocations run;
  50 SDK/native plus ten startup/concurrency/process invocations skip for unsupported
  metadata placement. This does not qualify default Windows filesystem placement
  for managed S3 recovery; current adapter policy remains explicit.
- [Docs 41313228027](https://github.com/MeridianDB/meridiandb/actions/runs/41313228027)
  passes production and project-path strict builds plus all 42 docs-rule fixtures
  on each. [Automatic Pages 41313228113](https://github.com/MeridianDB/meridiandb/actions/runs/41313228113)
  passes its 42 fixtures, strict build and deployment.
- Live HTTPS readback verifies new managed guide/ADR 0032, core guide/ADR 0031,
  build, both S3 primitive guides, local metadata, publisher, persistence boundaries,
  architecture overview, roadmap, decisions index and home page: fourteen pages
  with exact canonical URLs and updated implementation/scope/count/research wording.
  Guide/ADR title/routes appear in search; sitemap contains 72 unique production
  HTTPS pages. The new ADR row is inside the decisions table. Browser confirms
  published guide/canonical; `.local-qa/mdb-0061/site-published.png` was shown in
  the session. Local guide/ADR/search navigation and desktop-light/mobile-dark
  layout pass without page overflow. Normal light viewport is restored; the exact
  task-owned local HTTP server is stopped. Ignored task evidence retains all logs.

Only queue/card/history follow the verified public source; internal-only receipt
paths trigger no workflow under existing filters. Validate all 62 IDs/cards/DAG/
eligibility, inspect/stage those three files, commit/push normally and verify
source ancestry and matching tips again. MDB-0038 remains expanded, with MDB-0062
still required for writable acknowledgement and complete parent conformance.
No later child, library release/upload or cloud/account/DNS/hosting operation begins.

**Next recommendation:** MDB-0062 — Writable remote recovery conformance, preserving
forced append/publication, original retry identity and canonical/seeded/fault/process
behavior before closing the remote recovery parent.


## 2026-11-01 — MDB-0063 Core pinned checkpoint writes

**Preflight/expansion:** start clean main/origin at
`5562b1e15cbe31b7c514c270befc6a597fae537b`. Verify MDB-0061 source
`fc366509873a6a4f48eac27595f6017d6344f4d7` ancestry and successful Java
41313228037, docs 41313228027 and Pages 41313228113 before advancing.
MDB-0062 covers independently useful core writing, managed S3 writable admission
and full SDK writable conformance. Preserve its original card/parent criteria,
mark it expanded and add required MDB-0063/0064/0065 in order with inherited
prerequisites; execute only MDB-0063. Unique IDs grow from 62 to 65, with no recycled
identity or prematurely closed parent. Root owns production/docs/planning/Git;
two bounded helpers supply separate focused/conformance files and a third supplies
fresh research plus independent production/public documentation review.

**Implementation:** add explicit six/seven-argument writable object-checkpoint
factories plus existing package-private per-instance probe route. Both modes share
pure preflight, concrete store/journal acquisition, frozen H and optional minimum,
owned same-pass history loading and private prefix-once/full-journal/suffix-only
reconstruction. ObjectSource implements private WriteSource by delegating startup
synchronization, append and force to existing concrete owners. Only the writable
route passes a non-null writer to existing rebuild; complete private reconstruction
then actual journal/name synchronization precede any exposed database or new/retry
acknowledgement. Frozen H bounds startup only; live writes use the actual journal.
No public restore/backend callback, new production class, wire/POM/dependency or
commit-protocol change. Existing prepare/encoding/append/force/atomic publication,
uncertainty poisoning and independent close remain unchanged.

Caller object adapters remain unowned and unused after startup, including after
caller close while new local writes continue. Complete matching local journal,
explicit pinned authority, minimum sequence floor, unknown clean tail loss,
no fallback/repair/reclamation and force/device assumptions remain explicit.
Ordinary local selection and all read-only modes, including S3Database, retain
contracts. Managed writable startup and full parent conformance remain separately
required; no local write uploads/selects a checkpoint or supplies remote durability.

**Research:** all fourteen Central pins and official compatibility freshly checked
November 1 **14:22:37 UTC /09:22:37 EST**. All selected stable releases remain current:
Maven 3.10.0, wrapper distribution/plugin 3.3.4, clean/resources 3.5.0, compiler
3.16.0, Surefire 3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3, Jackson
3.2.4 and optional SDK S3/HTTP 2.57.8. Reject Maven 4 RC/plugin betas; no pin changes.
Metadata summary SHA256 `1615c5345fe82eed53da77ff1772d185cf7c5b64d7a117d914a34c76dd681038`,
compatibility `d50af36dd61eb547e63dbeb899f44f091189a2a17170b799c28e14de56f4e054`,
reviewed report `4b739bc9b89bd1ca1e24fa45fd3e3ae63cf94f020cf29249e3c14c9575d5dd2f`.
Reuse existing local durability/temporal research and MDB-0061's AWS sources checked
13:35:18 UTC /08:35:18 EST explicitly; no new external write-equivalence claim.
Raw research/build/browser/readback evidence remains ignored in `.local-qa/mdb-0063/`.

**Verification:** 26 focused invocations cover pure argument/minimum admission,
unchanged read-only RECOVERED-only/no transactions, exact writable RECOVERED then
SYNCHRONIZED startup, prefix/tail exact retries without write boundaries, new no-op
and correction commits, original lookup, old views and fixed checkpoint cutoff,
caller adapter close with continued writes, held/released actual owners, six
startup I/O/runtime/Error failures and three source failures without exposure.
Eight conservative before/after append/force/publication failures poison views,
retain owners and resolve by actual writable reopen; five bounded child JVM halts
at RECOVERED/SYNCHRONIZED/AFTER_APPEND/AFTER_FORCE/AFTER_PUBLICATION verify exact
journal bytes, original retry position and unchanged object bytes. These are real
process/probe boundaries, not injected native force failure or device power loss;
existing generic native durability/transaction suites remain unchanged and pass.

Forty default conformance invocations run 24 unchanged canonical temporal/query
history/shape cases (all 174 original literal steps per shape, 522 total) and 16
persisted seeds, each through three independent 32-commit workflows: **1,536 actual
seeded new commits**. Every fixture/seed new write, retry, conflict and observed
read reopens the new writable factory; LocalDatabase.create only initializes empty
genesis, and ordinary local reopen supplies a separate final baseline. Preserve
original generator text/random draw order and seeds. Check all 33 historical seeded
cutoffs, dense/reference predicates/Select/FilterScan, original IDs and no-ops,
immutable pinned views and exact raw frames after owners close. Retry/conflict/read
operations append nothing. Native tests skip only explicit directory capability.

Focused two new classes plus existing ObjectCheckpointRecoveryTest pass **95** cases.
Then serial `./mvnw -B clean install` and optional
`./mvnw -B -f adapters/s3/pom.xml clean verify` pass **1,272 core /472 optional**,
zero local failures/errors/skips. Fourteen examples pass with only the packaged
core JAR. Archive/jdeps retain 155 production classes, java.base only, no SDK/test
classes and the unchanged one-class test classifier. Forty-two docs-rule fixtures
and strict production/project-path builds pass with 74 pages. New guide/ADR and
search navigation, desktop-light/mobile-dark layout and normal viewport restoration
pass without page overflow. Independent code/documentation review finds no actionable
issue; link implemented writable mode from ADR 0031 and qualify existing broad
'writable object recovery planned' callers as managed S3 scope.

**Completion/delivery:** mark MDB-0063 done, clear active claim and make MDB-0064
ready subject to source delivery. Keep MDB-0062/0038 expanded and preserve all original
criteria. Validate 65 IDs/cards/references/DAG/eligibility, inspect/stage explicit
task-owned source/docs/rules/planning, commit Step-ID MDB-0063 / Step-Result completed
and push normally. Verify source ancestry/tips, Java/docs/automatic Pages and live
guide/ADR/current callers/canonicals/search/sitemap before an internal delivery
receipt. No managed writable child, library release/upload or cloud/account/DNS/
hosting change starts.

**Next recommendation:** MDB-0064 — Managed S3 writable recovery, composing exact
managed expected/client admission with the implemented core startup barrier and
retaining all metadata/store/journal ownership through transaction uncertainty.


## 2026-11-01 — MDB-0063 Delivery verification

Source **`88b81ab705eef51586f6fb1793d277c4c8beec8b`** carries
`Step-ID: MDB-0063` / `Step-Result: completed`, including production/tests,
documentation/rules and required MDB-0062 expansion into three children plus
source queue/card/progress acceptance. Normal push and fresh fetch confirm matching
main/origin tips, source ancestry and a clean working tree before this receipt.

- [Java 41317159326](https://github.com/MeridianDB/meridiandb/actions/runs/41317159326)
  passes both platforms and all fourteen examples per platform. Ubuntu runs all
  1,272 core /472 optional cases with zero failures/errors/skips, including all
  66 new writable cases. Windows has zero failures/errors with 363 core and 245
  optional explicit capability skips. The new pure argument preflight runs;
  25 focused native and 40 canonical/seeded cases skip for required unavailable
  directory synchronization. This does not qualify that Windows placement for
  writable pinned recovery; the existing local capability contract remains.
- [Docs 41317159285](https://github.com/MeridianDB/meridiandb/actions/runs/41317159285)
  passes strict production and project-path builds plus all 42 docs-rule fixtures
  on each. [Automatic Pages 41317159286](https://github.com/MeridianDB/meridiandb/actions/runs/41317159286)
  passes its 42 fixtures, strict build and deployment.
- Live HTTPS readback verifies new write guide/ADR 0033, local database, read-only
  guide/ADR 0031, build, S3 byte/head/publication/managed-read guides, persistence
  boundaries, architecture overview, roadmap, decisions index and home page:
  fifteen pages with exact canonical URLs and implementation/scope/count/research
  wording. New guide/ADR title/routes appear in search; sitemap contains 74 unique
  production HTTPS pages. Browser confirms the published guide/canonical;
  `.local-qa/mdb-0063/site-published.png` was shown in the session. Local guide/ADR/
  search navigation and desktop-light/mobile-dark rendering have no page overflow.
  Normal light viewport is restored and the exact task-owned local server is
  stopped. Ignored task evidence retains all research/build/CI/readback/browser logs.

Only queue/card/history follow the verified public source. Internal-only receipt
paths trigger no workflow under existing filters. Validate all 65 IDs/cards/DAG/
eligibility, inspect/stage these three paths, commit/push normally and reconfirm
source ancestry and matching tips. MDB-0062 and MDB-0038 remain expanded with
MDB-0064/0065 required. No managed writable step, library release/upload or cloud/
account/DNS/hosting change starts.

**Next recommendation:** MDB-0064 — Managed S3 writable recovery, composing exact
managed admission with the core startup barrier and retaining metadata/store/journal
ownership through new writes, uncertainty, close and exact reopen.

## 2026-11-01 — MDB-0064 Managed S3 writable recovery

**Scope and ownership:** resume the next bounded child from clean main/origin
`5e17d70e741084d221e03e41836513709f45676b`. Recheck MDB-0063 source ancestry and
successful Java 41317159326, docs 41317159285 and Pages 41317159286. Root owns
production, documentation, shared planning and Git; bounded helpers return optional
test files, fresh version evidence and independent implementation/caller review.
Keep all 65 IDs and parent criteria; this child does not close MDB-0062/0038.

**Implementation:** explicit S3Database.openWritable routes existing exact managed
expected-state/client/head admission to LocalDatabase.openWritableFromObjectCheckpoint.
Actual reconstruction and journal/name synchronization finish before return. Delegate
public transact to the real core commit/retry protocol; existing open remains read-only
and rejects new and exact-retry transactions. Keep query capture outside the private
core write/close monitor, serialize actual writers/close, reject reentrancy before
invalidation and clear the guard on all outcomes. Core poison rejects wrapper head,
checkpoint, lookup, view, observation and transactions; retain metadata/store/journal
owners until independent close cleanup with existing suppression/retry behavior.
Clients/credentials remain unowned and can close after startup. Local writes change
neither remote head nor publication-state generation. No core protocol, wire format,
SDK setting, dependency or public arbitrary restore/backend interface changes.

**Tests:** 49 new writable API/admission invocations cover genesis/split/full graphs,
real SDK/loopback requests and local owners; original prefix/tail retries, new/no-op
writes, payload conflicts, historical answers and immutable pinned views; readonly/
closed calls, independently owned clients, exact scope/body/token, pending/absent
refusals before network, explicit reconciliation, corrupt graph/journal/state,
minimum/budget errors and cleanup. Twenty-five lifecycle cases cover actual core
startup and write I/O/runtime/Error boundaries, unchanged uncertain result evidence,
poisoned wrappers/views, retained owners, exact managed reopen/retry, two paused-write
snapshot/close races, serialized writers and reentrant-close/write rejection. Six
actual child halts include RECOVERED, SYNCHRONIZED, AFTER_APPEND, AFTER_FORCE,
AFTER_PUBLICATION and ACKNOWLEDGED_RETURN; the last is distinct from publication
before caller return. Bounded workers/processes clean up. A test-only engine-package
bridge injects the existing core probe into real native recovery; no fake writer
substitutes for append/force/publication and no helper enters published artifacts.

**Research:** fresh Central metadata and official compatibility evidence checked
November 1 at **15:50:20 UTC /10:50:20 EST**. All fourteen selected stable pins remain
current: Maven 3.10.0, Wrapper 3.3.4, clean/resources 3.5.0, compiler 3.16.0,
Surefire 3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3, Jackson 3.2.4 and
SDK s3/url-connection-client 2.57.8; Maven 4 RC/plugin betas remain unselected.
No POM edit is needed. Ignored `.local-qa/mdb-0064/research-helper/` retains source
URLs/versions/compatibility and SHA-256 evidence: metadata
`95e6d4cdcae3ed08e429efd58576dcc92d6b765673e6544a9af9fd9b63ea1c84`, compatibility
`b0c8e28deaf6f88fa1995b1dba2b169f66fa213996112b178c5e380cf9faac6e`, report
`70f92e9b044a141d5f9df8ed596300ffc6c12c26a71dc2e43189af5754efb4c4` and sums file
`f618687b9b650dbb7afd3bdfd5763d95cf09d467edafefebc5f9f3db1a81bb6f`.
Reuse ADR 0032 AWS evidence checked at 13:35:18 UTC /08:35:18 EST, explicitly dated
as existing evidence; this composition asserts no new provider-write behavior.

**Acceptance:** focused new tests plus S3DatabaseStartupTest pass **87** cases.
Serial clean core install and optional verify pass **1,272 core /546 optional**,
zero local failures/errors/skips. All fourteen examples run with only the packaged
core. Archive/jdeps preserve 155 core classes, java.base only and the unchanged
one-class conformance classifier; the optional JAR contains 50 production classes,
no test bridge/reference classes. Forty-two docs-rule fixtures and strict production/
project-path 76-page builds pass. Guide/ADR/back navigation, both title search entries
and desktop-light/mobile-dark rendering pass without page overflow; normal light
viewport is restored. Seventeen local page/content/canonical readbacks, both new
search entries and the 76-page unique production sitemap pass. Independent code and
caller review finds no actionable issue. Root reconciles stale managed-write-planned
claims and readonly no-transaction-API wording, adds ADR 0034 and the managed-write
guide, and persists owner/monitor/poison/fault-seam boundaries in agent rules.

**Limits and completion:** full unchanged canonical/seeded SDK histories and remaining
late-selection/cross-publication parent conformance stay in MDB-0065. This is not
remote write acknowledgement, replication, device-power-loss qualification or live
provider certification. Complete matching local journal and online managed startup
remain required; default Windows metadata placement remains explicitly unsupported.
Mark MDB-0064 source-done, clear active claim and make MDB-0065 ready conditional on
source delivery. Keep MDB-0062/0038 expanded. Validate queue/cards/DAG, inspect/stage
explicit task-owned paths, commit Step-ID MDB-0064 / Step-Result completed and push
normally. Verify exact-source Java/docs/automatic Pages and live pages before the
internal-only delivery receipt. No library release/upload or cloud/account/DNS/
hosting configuration change starts.

**Next recommendation:** MDB-0065 — Remote writable recovery conformance, completing
the unchanged canonical/seeded SDK corpus and cross-publication interruption checks
before closing the broader remote recovery parents.


## 2026-11-01 — MDB-0064 Windows argument-preflight follow-through

Initial source `4a3e14884c8cf11be3c0757b18592624ff279ff7` reached remote main.
Java 41324730342, docs 41324730429 and automatic Pages 41324730394 all pass.
Ubuntu runs all 1,272 core /546 optional cases with no skips; Windows reports
363 core /319 optional capability skips, including all 74 new invocations. Both
platforms pass fourteen packaged examples. Docs run 42 fixtures under both URL
configurations; Pages runs 42 fixtures, strict build and successful deployment.
Seventeen live page/content/canonical readbacks, both new search entries and the
76-page sitemap pass. Browser confirms the new live guide and its canonical;
`.local-qa/mdb-0064/site-published.png` was shown in the session. Normal light
viewport is restored and exact task-owned local server 45343 is stopped.

Inspection of actual Windows skips finds that the pure writable argument test
unnecessarily initializes durable metadata through the shared fixture. Refactor
that same invocation to ordinary directories with sentinel evidence and independently
configured actual SDK clients. Its seven null-argument checks, negative minimum
and same-directory rejection must finish before any metadata admission, network
request, credential ownership or file mutation. No native-force capability or
assumption is needed for this case; durable cases retain their explicit guard.
This is test-fixture follow-through, with no production/public-doc change and no
new invocation or protocol. All 546 optional tests pass again locally with zero failures/errors/skips
(`./mvnw -B -f adapters/s3/pom.xml verify`). Verify the repair's Java CI on
Ubuntu/Windows before closing delivery; expected Windows
optional capability skips fall to 318 while the pure case actually runs. Preserve
the original source's completed docs/Pages evidence instead of claiming a second
deployment for internal/test-only paths. MDB-0065 remains the sole next ready
item; MDB-0062/0038 stay expanded.


## 2026-11-01 — MDB-0064 Delivery verification

Source **`4a3e14884c8cf11be3c0757b18592624ff279ff7`** and test-fixture follow-through
**`7e666b3af237c9d1e6ffe6dfa048e3df19769677`** carry Step-ID MDB-0064 / Step-Result
completed and reached origin/main through normal pushes. Fresh fetch confirms both
source commits' ancestry, matching local/remote tips and a clean checkout before
this internal-only receipt. The follow-through changes only its test and internal
planning; public source is unchanged from the deployed source.

- [Final Java 41325115865](https://github.com/MeridianDB/meridiandb/actions/runs/41325115865)
  passes both platforms and all fourteen examples per platform. Ubuntu runs all
  **1,272 core /546 optional** cases without failures/errors/skips, including all
  74 new cases. Windows has no failures/errors and reports **363 core /318 optional**
  explicit capability skips. The pure argument-preflight invocation now runs;
  the remaining 48 new native API and 25 lifecycle cases skip only unavailable
  directory synchronization. This does not qualify that Windows placement for
  writable managed recovery. Original Java 41324730342 also passed; its extra
  fixture-induced skip and same-ID repair remain recorded above rather than
  misreported as executed coverage.
- [Docs 41324730429](https://github.com/MeridianDB/meridiandb/actions/runs/41324730429)
  passes strict production/project-path builds and 42 docs-rule fixtures on each.
  [Automatic Pages 41324730394](https://github.com/MeridianDB/meridiandb/actions/runs/41324730394)
  passes 42 fixtures, strict build and deployment of the original public source.
  No second docs/deployment run is triggered by the test/internal-only follow-through.
- Live HTTPS readback verifies the managed-write guide/ADR 0034, managed-read/core-write
  guides, build, S3 byte/head/publication/local-state guides, persistence boundaries,
  architecture overview/object protocol, ADRs 0032/0033, roadmap, decisions and home:
  **seventeen pages** with exact canonical URLs and implementation/scope/count/research
  wording. Both new titles/routes appear in search; sitemap contains **76 unique
  production HTTPS pages**. Browser confirms the published guide and canonical;
  `.local-qa/mdb-0064/site-published.png` was shown in the session. Local guide/ADR/
  search navigation and desktop-light/mobile-dark rendering show no page overflow.
  Normal light viewport is restored; the exact task-owned local server is stopped.
  Ignored task evidence retains research, build, XML/archive, both CI rounds,
  readback and browser logs/screenshots.

Only queue/card/history follow the verified source. Validate all 65 IDs/cards/DAG/
eligibility, inspect/stage these three paths, commit/push normally and reconfirm
both source commits' ancestry, matching tips and clean checkout. Internal receipt
paths trigger no workflow under existing filters. MDB-0064 is complete; MDB-0062/
0038 remain expanded with MDB-0065 required. No library release/upload or cloud/
account/DNS/hosting configuration change starts.

**Next recommendation:** MDB-0065 — Remote writable recovery conformance, completing
unchanged canonical/seeded SDK histories and cross-publication interruption evidence
before auditing and closing the broader remote recovery parents.


## 2026-11-01 — MDB-0065 Remote writable recovery conformance

**Scope and ownership:** claim the next bounded item from clean main/origin
`3849072587c1e0d59063492a768e33668a69bda9`. Recheck MDB-0064 source/follow-through
ancestry and final Java 41325115865, docs 41324730429 and Pages 41324730394 success.
Root owns implementation/docs/shared planning/Git. Independent helpers own fresh
Maven research and read-only parent/generator/lifetime/packaging/public-doc reviews;
no helper mutates tracked source or runs tests. Preserve all 65 IDs and every
original MDB-0038/0062 criterion; reclamation/general backend/replication stay separate.

**Implementation and checks:** add S3DatabaseWritableConformanceTest, reusing unchanged
ContractFixtures/ReferenceModel families and authoritative JSON/seed resources through
the existing attached test-only classifier. Optional Jackson 3.2.4 remains test scope;
no production core dependency, API, protocol, wire format or SDK setting changes.
Actual SDK immutable uploads, verified preparation and conditional publication precede
managed opens for every write/retry/conflict/read workflow. Preserve all five temporal
and three query histories, 174 literal steps per shape /522 across genesis/split/full,
and all sixteen persisted seeds through three independent 32-commit workflows:
**1,536 actual seeded new commits**. Every historical seeded cutoff (33 per workflow)
checks independent reference/dense values, all predicate operators, absence/extremes,
request IDs/no-ops and immutable pinned views; ordinary local reopen is a separate
baseline. Raw full journal frames remain exact after owners close. Queries/local
transactions make no remote request or publication-state generation change.

S3DatabasePublicationConformanceTest adds ten real SDK/native integration cases:
held timed-out original-condition PUT then late effect, pending refusal before GET
both before/after selection, explicit no-PUT reconciliation and managed retry/new-write
continuation; actual publisher child halts at WRITE_FINISHED, CANDIDATE_VERIFIED and
STATE_SYNCED followed by pending resolution or exact durable expected reopen; clean
tail-loss with C=0/1/3, independent minimum versus default-zero behavior; missing
complete journal despite full graph; failed online startup without fallback; and
captured old expected GET versus later selection with no continuing lease/fence.
Reconciled/synchronized paths preserve historical answers and original retries,
commit a new request, retain old views and reopen to retry it exactly. Fixture
budgets remain explicit (32,768 requests, 512 objects, 2 MiB aggregate, unchanged
4 KiB body/object caps); existing focused defaults remain unchanged. All acquired
SDK/native resources, held workers and child lifetimes have bounded cleanup.

**Parent acceptance audit:** combine the new cases with existing MDB-0059/0060/0061/
0063/0064 source and tests; source-level mapping is independently reviewed.

| Original MDB-0038/0062 requirement | Completion evidence |
| --- | --- |
| Actual SDK remote prefix plus exact complete local tail, canonical/seeded/local agreement | New 24 literal/shape and sixteen three-shape seed invocations; every observed operation uses managed writable open |
| New, duplicate, conflict and no-op writes; original identity; all historical answers | Unchanged corpus/reference/dense generator/draw order, all 33 seeded cutoffs, original retry positions, exact raw journal and separate local baseline |
| Startup barrier, append/force/atomic-publication uncertainty and lost acknowledgement | Existing core writable and 25 managed lifecycle cases, including actual boundary probes and six process stops through ACKNOWLEDGED_RETURN |
| Pending/late uncertain remote selection and exact original evidence | New held real PUT across caller timeout/reopen; pending blocks before GET before/after effect; reconcile without second PUT then actual engine continuation |
| Publication interruption and exact reopen | Three new actual publisher child halts; pending refusal/reconcile or durable expected validation, then managed historical/retry/new-write/reopen |
| Foreign/stale/missing/corrupt/divergent prefix/tail; cleanup | Existing writable scope/body/token/graph/journal refusal matrix and core/read reconstruction checks; unchanged no-fallback/adoption/repair behavior |
| Actual checkpoint C versus tail H, minimum and local storage loss | New C=0/1/3 clean-loss matrix, known-minimum refusal before graph reads, default-zero surviving head/absent lost ID/future rejection, full-prefix mismatch and missing journal with retained evidence |
| Ownership, view lifetime, online/local compatibility and costs | Existing retained-owner/poison/concurrency/client-close checks; new online/head-snapshot cases; unchanged JDK-only local runtime; guide separates graph/replay/metadata/startup/force/heap costs |

All original source gates and required children now pass; close MDB-0062 and MDB-0038
with MDB-0065 source acceptance, then verify delivery. MDB-0011 remains expanded with
MDB-0039/0040 required. No remote reclamation, general backend/live-provider certification,
replicated durability, distributed fencing, power-loss or performance claim is added.

**Fresh Maven evidence:** all fourteen selected pins remain latest stable compatible.
Central checked **November 1, 16:19:29 UTC /11:19:29 EST**; official compatibility
checked **16:19:30 UTC /11:19:30 EST**. Maven 3.10.0, Wrapper 3.3.4,
clean/resources 3.5.0, compiler 3.16.0, Surefire 3.6.1, jar 3.5.1,
install/deploy 3.2.0, JUnit 6.1.3, Jackson 3.2.4 and SDK s3/url-connection-client
2.57.8 remain selected; prerelease Maven/plugin 4 entries remain unselected. The
new adapter test dependency reuses researched Jackson 3.2.4. Twenty-eight fresh
source responses and report remain in ignored `.local-qa/mdb-0065/research-helper/`.
SHA-256 metadata `b5643735672e816786448e08caff923ad0fd182c27801a6b111818bdc44cae5c`,
compatibility `cd94b086715a12efad4d4502ca80129e3470a7c5222360ead4da6cf23f56f6e5`,
report `5f1bd0faa615fdab0672e8c72587fd27c0806c69df133ee4436d17780983d1ae`,
sums `c9300c956c00d98efe1838da85665094e3e929c51370edd9de5ac40b23da536a`.
Existing AWS evidence remains honestly dated 13:35:18 UTC /08:35:18 EST; this
conformance composition asserts no new external provider behavior.

**Execution/review:** first install core test support without tests as an explicit
prerequisite; a focused canonical+seed-zero run passes 25 cases, full corpus passes
40 and focused integration passes ten. Then serial `./mvnw -B clean install` and
`./mvnw -B -f adapters/s3/pom.xml clean verify` pass **1,272 core /596 optional**,
zero local failures/errors/skips. Fourteen examples pass using only the packaged
core. XML/archive readback confirms 155 production core classes, java.base only,
50 optional production classes and no test/reference/fixture/SDK leakage into core.
Classifier contains exactly ten intended support classes plus three byte-identical
contract/seed resources; no actual test, fault bridge or optional helper. Independent
review mechanically confirms request/dense/helper bodies and random draw sequence
match the original core suite. No actionable code/packaging issue; reconcile the
older AGENTS classifier prohibition to fault-injection support so it agrees with
the intentionally shared oracle families.

ADR 0035, conformance guide and current callers/rules describe experimental evidence,
complete local journal, online/minimum/default-zero limits and remaining separate
work. Forty-two docs-rule fixtures and strict production/project-path **78-page**
builds pass. New guide/ADR/back navigation, both title search entries, desktop-light/
mobile-dark layout and mobile coverage table have no page overflow; restore normal
light viewport. Fifteen local page/content/canonical readbacks, both new search
entries and the 78-page unique production sitemap pass. Public documentation review
finds no process/task leakage or unsupported durability/certification claims.

**Completion:** mark MDB-0065 and parents MDB-0062/0038 source-done, clear active
claim and make MDB-0039 ready conditional on remote delivery. Validate all 65 IDs/
cards/references/DAG/eligibility, inspect/stage task-owned paths and commit with
Step-ID MDB-0065 / Step-Result completed. Push normally; verify exact-source Java/
docs/automatic Pages, live pages/search/sitemap and matching tips/clean checkout
before an internal-only delivery receipt. No library release/upload or cloud/
account/DNS/hosting configuration change starts.

**Next recommendation:** MDB-0039 — Reader-safe object reclamation, beginning with
its explicit remote reader/publisher protection contract and bounded implementation
while preserving every admitted reclamation criterion.


## 2026-11-01 — MDB-0065 Delivery verification and recovery parent closure

Source **`c41b73b6488a2dbd612bcc26f11b32061d561376`** carries Step-ID MDB-0065 /
Step-Result completed, including tests/test packaging, public docs/rules and source
queue/card/progress acceptance. Normal push and fresh fetch confirm source ancestry,
matching main/origin tips and a clean checkout before this internal-only receipt.
All original MDB-0038/0062 criteria are audited above and all required children are
done; their remote source, CI and publication gates now pass. **MDB-0065, MDB-0062
and MDB-0038 are complete.** MDB-0011 remains expanded for MDB-0039/0040.

- [Java 41327286700](https://github.com/MeridianDB/meridiandb/actions/runs/41327286700)
  passes both platforms and all fourteen examples per platform. Ubuntu runs all
  **1,272 core /596 optional** cases with no failures/errors/skips, including all
  40 new corpus and ten integration invocations. Windows has no failures/errors
  and reports **363 core /368 optional** explicit capability skips, including all
  50 new native cases. Pure argument admission remains exercised independently.
  The Windows result does not qualify its unsupported directory-sync placement.
- [Docs 41327286721](https://github.com/MeridianDB/meridiandb/actions/runs/41327286721)
  passes strict production/project-path builds and 42 docs-rule fixtures on each.
  [Automatic Pages 41327286708](https://github.com/MeridianDB/meridiandb/actions/runs/41327286708)
  passes 42 fixtures, strict build and actual deployment of the same source SHA.
- Live HTTPS readback verifies the new conformance guide/ADR 0035, managed-write/
  core-write/build/S3 byte/head guides, ADRs 0032/0033/0034, persistence boundaries,
  architecture overview, roadmap, decisions and home: **fifteen pages** with exact
  canonical URLs and implementation/count/research/scope wording. Both new title/
  route entries appear in search; sitemap has **78 unique production HTTPS pages**.
  Browser confirms the published guide and its canonical; the session showed
  `.local-qa/mdb-0065/site-published.png`. Local guide/ADR/back and title search
  navigation, desktop-light/mobile-dark rendering and mobile coverage table have
  no page overflow. Normal light viewport is restored and exact task-owned local
  server 80169 is stopped. Ignored evidence retains fresh research, independent
  reviews, build/XML/archive, CI and readback/browser logs/screenshots.

Only queue/card/history follow verified public source. Validate all 65 IDs/cards/
DAG/eligibility, inspect/stage those three paths, commit/push normally and reconfirm
source ancestry, clean checkout and matching tips. Internal receipt paths trigger
no workflow under existing filters. No next engine step, library release/upload,
cloud/account/DNS/hosting change or replication work starts.

**Next recommendation:** MDB-0039 — Reader-safe object reclamation, beginning with
its explicit remote reader/publisher protection contract and bounded implementation,
retaining selected/backup/reader roots and every original reclamation criterion.


## Receipt template

Append a dated entry for the selected ID with: outcome and affected artifacts;
acceptance evidence and actual commands/results; decisions and remaining limits;
new/split/reordered IDs with rationale; blocker/unblock evidence when relevant;
completion or checkpoint commit trailers; and the next recommendation at that time.
Keep current status and active ownership in the queue rather than duplicating it here.
