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


## 2026-11-01 — MDB-0066 Remote reclamation protocol and staged plan

**Scope and selection:** resume from clean main/origin
`f0dd734b693df2de9c1ed6ed394fffb31d873f1c`, rechecking prior source
`c41b73b6488a2dbd612bcc26f11b32061d561376` ancestry and Java 41327286700,
docs 41327286721 and Pages 41327286708 success. MDB-0039 combines coordination,
root inventory, destructive provider effects and integration, so expand it into
required MDB-0066 protocol, MDB-0067 namespace admission/hazards, MDB-0068 verified
inventory, MDB-0069 exact conditional deletion and MDB-0070 ordered removal/conformance.
Preserve every original reader/publisher/root/uncertainty/removal criterion; protocol,
preview or universal no-op alone never closes the parent. All 70 IDs/cards/DAG remain
valid; only MDB-0066 executes this invocation. Root owns shared source/planning/Git;
independent helpers own fresh primary/version research and read-only design/public QA.

**Protocol outcome:** ADR 0036 and architecture/remote-reclamation.md define an
accepted design, explicitly unimplemented. Initial mode is a fresh cooperating
namespace, never-versioned general-purpose bucket and one canonical local control
placement on one host. Old raw clients/pinned readers/publication APIs remain
independent and cannot enroll automatically. An absent head, client shutdown or
restart proves neither fresh namespace authority nor absence of unknown historical
mutations. Keep the permanent guard inode, immutable binding/activity generations,
common identity reservation, actual shared locks, ordered ownership, invalidation/
cleanup/suppression and native uncertain-close quarantine. Cross-host/copied-control
placement, legacy adoption and version-aware removal remain unsupported extensions.

All participating readers/uploaders/publishers retain shared admission; maintenance
requires exclusive admission through root verification and effects. Root registration
must be complete before upload/use, including failed/pending candidates' dependencies.
Retaining an unknown manifest body alone does not protect a segment shared with an
obsolete candidate. Unregistered roots or uncertain coverage defer deletion; explicit
candidate lists alone prove no complete liveness. Selected/current intent, catalog,
retained/backup roots and full journal/history remain protected. Verify complete
candidate graphs against actual owner journal and pair SHA-256/length/body with the
original opaque ETag from one GET; no separate HEAD can bind an earlier body.
Bound aggregate root/fetch/byte/record/identity work before I/O/allocation.

Before every possible remote PUT/DELETE, durably record that individual attempt and
reserve completion capacity. Only its validated terminal response can supply its
completion record. Recognize an already installed valid completion on reopen after
local failure; never manufacture it from observation or fall back past damaged
newest evidence. Unknown effects remain quarantined across close/restart and block
new remote activity, cleanup and key resurrection. Logical publication reconciliation,
GET absence, retry success, TTL and PID/lease assumptions do not settle earlier
requests. Document the conservative D1-unknown/D2-success/recreate-same-key/D1-late
delete schedule as an inference, not an observed provider incident or new guarantee.
Each exact DELETE must settle durably before another mutation. Planned ordering is
obsolete immutable head metadata, obsolete manifests then unused segments; stop
immediately on uncertainty and retain remaining/orphan/diagnostic evidence. Reopen
validates evidence and surviving roots without automatic replay/sweep/repair.

**Research:** fresh fourteen-pin Central metadata checked November 1 at
**19:10:52 UTC /14:10:52 EST**; official compatibility at **19:10:53 /14:10:53**.
All selected releases remain latest stable compatible: Maven 3.10.0, Wrapper 3.3.4,
clean/resources 3.5.0, compiler 3.16.0, Surefire 3.6.1, jar 3.5.1, install/deploy
3.2.0, JUnit 6.1.3, Jackson 3.2.4 and SDK s3/url-connection-client 2.57.8. No POM,
Wrapper, SDK setting or dependency selection changes. Fresh S3/SDK primary set
started **19:12:05 UTC /14:12:05 EST**: nine documents plus three exact Central
2.57.8 source JARs. DeleteObject supports exact If-Match for general-purpose buckets;
size/time predicates are directory-only. Current-only conditions, mismatch412 and
published concurrency409/404 guidance require future exact outcome qualification.
Never-versioned deletion differs from enabled/suspended markers/versions. Strong
single-key consistency and client-side timeout/retry settings provide no cross-key
fence or operation-drain certificate; exact SDK source exposes ifMatch and the
existing explicit maxAttempts(1). Distinguish these source facts from protocol
inferences. All primary links/date are in public protocol; raw evidence remains
ignored under `.local-qa/mdb-0066/research-helper/`.

SHA-256 metadata `158481afaecd8bf30f1ee72051eccfb3c5e0c166ea661e42ad4c4e66b7ffe3f0`,
compatibility `fafcebeb585dc0c69def11cac5a9bf2b2cb282d1981c9a5802e81099eb02f1b0`,
S3 summary `8c7388150e923e7294f1ee0518579340a72c2df001d63db1faa533b471637206`,
report `2e09ddc0016dc443cb69b7ec2b936fba7e80efd993fb917b5d41b4dedbc834e0`,
sums `d9922975c378accf6c83c5bceae3c19b6887ecbc3fede0b56853a8bca4c4982c`.

**QA and caller reconciliation:** independent review verifies fresh-only enrollment,
complete root coverage, immutable/head/DELETE hazards, same-attempt settlement,
same-GET evidence and ordered effects. Incorporate its root-dependency and per-attempt
completion clarifications; final review reports no remaining findings or public
process leakage. Update root rules and README/home/architecture/persistence/object
protocol/decision index/nav with clear accepted-versus-implemented status. Correct a
stale lower-roadmap sentence calling already completed full writable conformance
open; protected reclamation/common backend stages remain open. No public task IDs,
workflow narrative or runtime/provider/deletion qualification is claimed.

Forty-two docs-rule fixtures and strict production/project-path **80-page** builds
pass. New protocol/ADR/back navigation and title search entries pass; desktop-light/
mobile-dark rendering has no page overflow and normal light viewport is restored.
Nine local page/content/canonical readbacks, both new search routes and 80 unique
production sitemap URLs pass. Java/POM/workflow inputs are unchanged; do not repeat
1,272 core /596 optional tests or claim a new Java CI run for this documentation-only
step. Prior exact-source Java delivery is rechecked above. Public protocol source
inspection performs no live provider call, bucket/IAM/account mutation or deletion.

**Completion/delivery:** mark MDB-0066 source-done, clear active claim and make
MDB-0067 ready conditional on source delivery. Keep MDB-0039/0011 expanded and all
four implementation children required. Validate all 70 IDs/cards/references/DAG,
inspect/stage task-owned paths and commit Step-ID MDB-0066 / Step-Result completed.
Push normally; verify exact-source docs/automatic Pages, live protocol/ADR/callers/
canonical/search/sitemap and matching tips/clean tree before internal-only delivery
receipt. No child implementation, library release/upload, remote delete or cloud/
account/DNS/hosting configuration change starts.

**Next recommendation:** MDB-0067 — Coordinated S3 namespace admission, implementing
the fresh binding/complete root registration, reader/publisher protection and durable
per-attempt hazard boundary before any reclamation effect is possible.


## 2026-11-01 — MDB-0066 Delivery verification

Source **`b883d420923a164c2545980322447abe43253b55`** carries Step-ID MDB-0066 /
Step-Result completed, including protocol/ADR/current callers/rules and the required
MDB-0039 expansion into five children with source queue/card/progress acceptance.
Normal push and fresh fetch confirm source ancestry, matching main/origin tips and
a clean checkout before this internal-only receipt.

- [Docs 41341579887](https://github.com/MeridianDB/meridiandb/actions/runs/41341579887)
  passes strict production/project-path builds and all 42 docs-rule fixtures on each.
  [Automatic Pages 41341579894](https://github.com/MeridianDB/meridiandb/actions/runs/41341579894)
  passes its 42 fixtures, strict build and actual deployment of the same source SHA.
- Java/POM/SDK/workflow inputs remain unchanged and no Java workflow is triggered.
  Prior source Java 41327286700 was rechecked successful before selecting this
  documentation-only step; do not claim new Java test execution or deletion coverage.
- Live HTTPS readback verifies the new protocol/ADR 0036, persistence boundaries,
  architecture overview, object-head protocol, roadmap, decisions, build and home:
  **nine pages** with exact canonical URLs and accepted/unimplemented, root/hazard,
  ordering/research wording. Both new titles/routes appear in search; sitemap
  contains **80 unique production HTTPS pages**. Browser confirms the published
  protocol's explicit accepted-design/implementation-pending status and canonical.
  The session showed `.local-qa/mdb-0066/site-published.png`. Local protocol/ADR/back
  and title search navigation plus desktop-light/mobile-dark layout pass without
  page overflow. Normal light viewport is restored and exact task-owned local
  server 96540 is stopped. Ignored evidence retains fresh source/version research,
  independent reviews, docs build/CI, readback and browser logs/screenshots.

Only queue/card/history follow verified public source. Validate all 70 IDs/cards/
DAG/eligibility, inspect/stage these three paths, commit/push normally and reconfirm
source ancestry, clean checkout and matching tips. Internal-only receipt paths
trigger no workflow under existing filters. MDB-0066 is complete; MDB-0039/0011 stay
expanded with all four implementation children required. No child implementation,
library release/upload, real-provider mutation or account/DNS/hosting change starts.

**Next recommendation:** MDB-0067 — Coordinated S3 namespace admission: fresh bound
placement, complete root registration, shared reader/publisher protection and durable
individual mutation hazards before any reclamation effect is offered.



## 2026-11-01 — MDB-0071 Bounded S3 coordination state and codec

**Selection and refinement:** reconcile clean main/origin at prior receipt
`1e412c5ab7baf4cf0771051126833dde583c3358`. Recheck prior source
`b883d420923a164c2545980322447abe43253b55`, docs 41341579887 and Pages
41341579894 as successful and reachable. Expand MDB-0067 into required
MDB-0071/0072/0073/0074, preserving its original criteria and prerequisites;
execute only the pure state/codec child. All 74 stable IDs/cards/dependencies
remain; MDB-0067/0039/0011 stay expanded.

**Implementation:** optional S3CoordinationState/Codec/Exception provide immutable
exact target/binding/placement, full head/manifest roots, canonical ordering,
typed dependencies, shared-key consistency, retention and permanent tombstones.
One unresolved compound placement/contiguous ordinal/nonce attempt preserves exact
operation/key/content, original condition and replacement original head. Pending
freezes catalog/new attempts; settlement requires full original equality and
schema terminal 200/204/412. Retain exact last completion/watermark and reject
foreign placement, gaps and exhaustion. Reserve two generations before begin.
Caps are 32 roots, 16 KiB manifests, 4,096 aggregate reference occurrences and
1 MiB whole image. A conservative 537,715-byte maximum includes two maximum
attempts; canonical v1 SHA-256 integrity differs from full-image identity.

Public fresh/inspection/codec are declarations; transitions stay package-private.
No native admission, fresh enrollment, path/copy/rollback detection, selected-root
authority, actual journal/full remote coverage, SDK dispatch, terminal provenance
or durable installation follows. Future MDB-0072 retains full immutable receipt
history; MDB-0073 qualifies original responses. No actual S3 call, remote deletion,
release/upload, bucket/account/DNS or hosting configuration changes occur.

**Verification:** `./mvnw -B clean install` passes 1,272 core invocations.
Optional clean verify passes 701 before review adds three cases; final
`./mvnw -B -f adapters/s3/pom.xml verify` passes **704**, all with zero failures,
errors or skips. The 108 new pure cases comprise 50 state and 58 codec checks.
Five frozen coordination images use independent Python struct/hashlib assembly
from existing literal head/manifest inputs; expected lengths/full hashes are
separate. Reject every prefix and single-byte mutation, checksum-valid malformed
fields/nested bounds, ordering, identity/status/condition mismatch, pending
changes, live shared deletion, tombstone reuse and numeric/capacity boundaries.
Successful CREATE_HEAD, DELETE 204/412 and completion→retirement roundtrips protect
distinct layouts and historical validation. Review fixes CRLF fixture parsing;
final tests accept both line endings. Test-resource provenance is retained.

All fourteen examples pass against the packaged core. Archives retain core 155
classes, unchanged classifier 10 classes/three contract-seed resources, optional
61 production classes, no tests/helper/hex resources and unchanged runtime scopes.
Core JAR SHA-256 `62c61ebe40871912f79f2f98c6705f0301e8ca78b3aa374b14f860a9e2a0aad9`,
classifier `080ff20a9ecc9303b72889741e7cb448e05a848c9b6d65923e118645cf6d80d1`,
optional `8f3fd612c7eff19be0062621bfc955fd1df552d7addfb09cd6239db81bef2a55`.

**Research:** all fourteen Central metadata sources freshly checked November 1,
19:48:14 UTC /14:48:14 EST, compatibility at 19:48:15 UTC /14:48:15 EST.
Selected stable pins remain Maven 3.10.0, Wrapper 3.3.4, clean/resources 3.5.0,
compiler 3.16.0, Surefire 3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3,
Jackson 3.2.4 and SDK s3/url-connection-client 2.57.8. No POM/Wrapper/SDK setting
changes. Metadata SHA `73970419008986fe17469e441a2d5393a61c5aaa56253329ae1e195b9fa3e96b`,
compatibility `96e2516b8b214a5862ba54925b7b28488177091cd7e476dc45547b7c99d84be1`,
report `e9b0e78fb8a1fc94e82f8b0a0bbab1ce5b60a02b3beb1f453df1e53b2239ed0c`,
sums `eab342b542fafd1938ac4f9028a3b7e1fd7b0f069a913c9a8af9514d4211fd00`.
Reuse MDB-0066 provider evidence with its original 19:12:05 UTC /14:12:05 EST
check; summary `8c7388150e923e7294f1ee0518579340a72c2df001d63db1faa533b471637206`.
Pure code introduces no new provider-operation claim. Raw evidence stays ignored
under `.local-qa/mdb-0071/` and the linked prior protocol research.

**Documentation and review:** add format/ADR 0037; reconcile README/home,
architecture/persistence/protocol/ADR 0036/decisions/roadmap/build/S3 object/head/
conformance callers and nav. Persist declaration/provenance rules in AGENTS.md.
Independent implementation and final test/vector/public review have no remaining
findings. Both production/project-path **82-page** strict builds and 42 docs-rule
fixtures pass. Browser verifies new format/ADR/back links, title search results,
desktop-light/mobile-dark and restored normal viewport; no page overflow.
Thirteen local page/content/canonical routes, both new search routes and 82 unique
production HTTPS sitemap URLs pass. Public copy contains no task/process narration.

**Source completion/delivery:** mark MDB-0071 source-done, clear active claim and
make MDB-0072 ready conditional on delivery. Validate all 74 IDs/cards/DAG/eligibility,
inspect/stage task-owned paths and commit Step-ID MDB-0071 / Step-Result completed.
Normal push, exact-source Java/docs/automatic Pages, live content/canonical/search/
sitemap and clean matching tips precede the internal-only delivery receipt.
No later child starts.

**Next recommendation:** MDB-0072 — Local coordination guard and durable ledger:
permanent native identity and forced immutable intent/completion generations before
participating SDK sessions can use this pure model.



## 2026-11-01 — MDB-0071 Delivery verification

Source **`86310645e84a82004c7b2d8f0da5dd8e27671abf`** carries Step-ID MDB-0071 /
Step-Result completed. It includes code, independent literal fixtures/tests,
format/ADR/current callers/rules and MDB-0067's required four-child expansion with
source acceptance and next MDB-0072 readiness. Normal push/fresh fetch establish
source ancestry, matching main/origin tips and a clean checkout before this receipt.

- [Java 41345469509](https://github.com/MeridianDB/meridiandb/actions/runs/41345469509)
  passes Linux/Windows core and optional builds plus all fourteen examples on each.
  Linux runs core 1,272 /optional 704 with zero failures/errors/skips. Windows has
  363 core and 368 optional existing native-capability skips; all **108 new pure
  coordination cases (50 state/58 codec) actually execute without skips** on both.
  The fixture CRLF correction and historical-completion/delete/create layouts are
  verified in this exact-source CI. No new native admission/deletion qualification
  follows from these pure tests.
- [Docs 41345469586](https://github.com/MeridianDB/meridiandb/actions/runs/41345469586)
  passes 42 QA fixtures and strict production/project-path builds. [Automatic Pages
  41345469623](https://github.com/MeridianDB/meridiandb/actions/runs/41345469623)
  passes its 42 fixtures, build/upload and deployment of the same full source SHA.
- Live HTTPS readback verifies format/ADR 0037, protocol/ADR 0036, persistence,
  overview, roadmap, decisions, build, S3 object/head/conformance guides and home:
  **thirteen pages** with exact production canonicals and current scope/test/research
  wording. Both new titles/routes appear in search; sitemap has **82 unique
  production HTTPS pages**. Browser verifies the published pure-state/pending-native/
  SDK status and new test count. The session shows
  `.local-qa/mdb-0071/site-published.png`. Local format/ADR/back/search and desktop-
  light/mobile-dark checks pass without overflow; normal light viewport is restored.
  Task-owned preview server 98137 is stopped. Ignored evidence retains fresh Maven
  research, independent reviews, full CI logs, readbacks and screenshots.

Only queue/card/history change in this delivery receipt. Validate all 74 IDs/cards/
DAG/eligibility, inspect/stage these three paths, commit/push normally and reconfirm
source ancestry, clean checkout and matching tips. Existing workflow path filters
trigger no workflow for this internal-only receipt. MDB-0071 is complete;
MDB-0067/0039/0011 remain expanded. No next-child implementation, library artifact
release/upload, real-provider mutation or account/DNS/hosting change starts.

**Next recommendation:** MDB-0072 — Local coordination guard and durable ledger:
permanent native identity, shared/exclusive local ownership and forced immutable
intent/completion generations, preserving unknown evidence across reopen.



## 2026-11-01 — MDB-0075 Permanent local S3 coordination guard

**Selection/refinement:** reconcile clean main/origin at
`2dd5766423fe1cc2ca2befa81e44ba3c2a8d26d0`. Recheck source
`86310645e84a82004c7b2d8f0da5dd8e27671abf`, Java 41345469509, docs 41345469586
and automatic Pages 41345469623 as successful/reachable. Split MDB-0072 into
required MDB-0075 guard, MDB-0076 durable generations and MDB-0077 integrated
native/ledger conformance, preserving every original criterion and prerequisite.
Execute only guard; all 77 stable IDs remain. MDB-0072/0067/0039/0011 stay expanded.

**Implementation:** one new package-private optional-artifact primitive operates
on an existing empty regular nonsymlink permanent coordination file. Resolve the
real parent, require default filesystem/nonnull file key, preserve common local
identity reservations and reject aliases/changed names before another channel.
One synchronized registry bounds 256 placements and 1,024 shared leases per entry;
shared local holders reuse one actual shared channel/lock, incompatible admission
defers before another local open. Native nonblocking shared/exclusive locks cover
cooperating processes. Check actual shared mode and original identity after lock,
on validation and before/after sole-channel force. Native health/force/path
uncertainty poisons all entry leases. No raw lock/channel/delegate escapes.

Close invalidates first; only the final lease attempts native release and close.
Preserve first/suppressed failures. Known successful channel close can free the
entry after a release failure; any thrown close, even with isOpen false, retains
quarantine without raw retry. Unexpected unregistered overlap preserves its
channel/common reservation until process exit. The future ledger must separately
serialize generation writers across processes without upgrading a shared lease,
following guard-before-ledger/publication/checkpoint/journal/SDK order.

No creation, directory force, enrollment, bound durable state/receipt, complete
root liveness, actual terminal provenance, SDK operation or reclamation authority
follows. No actual S3 request, library release/upload, bucket/account/DNS or hosting
configuration change occurs. Existing raw SDK clients remain unchanged/unprotected.

**Tests/builds:** focused 58 cases pass: 43 registry/fault/capacity/lifetime and
15 native/process invocations. Independent handles cover IO/runtime/Error,
health accessor uncertainty, wrong lock modes, original name loss/replacement,
pre/post force identity, bounded holders/placements, reentrant/serialized force,
invalidation before independent cleanup, failed close after reported closure,
no-op retry refusal and startup/conflict cleanup. Review identified thrown native
health accessors bypassing poisoning; fix and four permanent regressions retain
uncertainty. Real child JVMs cover all mode combinations, last-reader protection,
rejected same-JVM/cross-adapter aliases, child-isolated overlap quarantine and
graceful/abrupt exit. Logs live outside guarded placements; no directory-sync
prerequisite or durability claim. Compile/test warnings are fixed under -Werror.

`./mvnw -B clean install` passes 1,272 core and optional clean verify passes **762**,
zero failures/errors/skips locally. Fourteen examples pass against packaged core.
Archive inspection retains core 155 classes and unchanged 10-class/three-resource
test classifier; optional main has 70 classes, no test/ChildMain/helper/vectors.
Core SHA `62c61ebe40871912f79f2f98c6705f0301e8ca78b3aa374b14f860a9e2a0aad9`,
classifier `080ff20a9ecc9303b72889741e7cb448e05a848c9b6d65923e118645cf6d80d1`,
optional `ce7477db2e344ea34a938fedf855917b422bd8a060011df69f1b04bcad627503`.
No POM/Wrapper/runtime dependency/SDK setting changes; core remains JDK-only.

**Research:** fresh 14 Central metadata sources at November 1, 23:00:22 EST
(November 2, 04:00:22 UTC), compatibility at 23:00:23 EST /04:00:23 UTC and five
JDK 25 primary pages at 23:01:48 EST /04:01:48 UTC. Preserve the differing UTC date.
Latest stable pins remain Maven 3.10.0, Wrapper 3.3.4, clean/resources 3.5.0,
compiler 3.16.0, Surefire 3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3,
Jackson 3.2.4 and SDK s3/url-connection-client 2.57.8. Primary JDK docs support
shared-mode inspection, JVM-wide lock coordination, same-file-close hazards,
optional/reusable file keys and nonfreezing realpath/attribute observations.
Distinguish these facts from project quarantine/ownership deductions; no new AWS
provider claim. Evidence in ignored `.local-qa/mdb-0075/research-helper/`:
metadata `4c213d75c3084b951a82c0557509111b04a21b8f49de4674508c239a2f701015`,
compatibility `7805debb1d370306f78ba7061177c8059c6f48404821fe536f0f664711c1a336`,
JDK summary `be6bc5d15dc879d6726cfc60351ab75e1ee57e1ea32a970ecc2ef8ac65b895c3`,
report `2eb39e21d0ba1fe169267c3445e0307139cf10df60321a16c6f34f85c1e00138`,
sums `16957ed7983d368552d72b5dee691714b2a733901b663601c7aea90b48f97689`.

**Documentation/review:** add native guide/ADR 0038; reconcile README/home,
architecture/persistence/protocol/state format/ADR 0036/0037/decisions/roadmap/
build/S3 object/head/conformance/nav and root rules. Public QA distinguishes
native exclusion from durable enrollment, future writer serialization, receipt
provenance and reclamation. Independent source/tests/Windows subprocess/public
review has no remaining finding. Forty-two docs-rule fixtures and strict
production/project-path **84-page** builds pass. Browser verifies guide/ADR/back/
search navigation, desktop-light/mobile-dark without overflow and restored normal
light viewport. Fifteen local canonical/content routes, both new search entries
and 84 unique production sitemap URLs pass. No public task/process narrative.

**Source completion/delivery:** mark MDB-0075 source-done, clear active claim and
make MDB-0076 ready conditional on delivery. Validate 77 IDs/cards/DAG/eligibility;
inspect/stage task-owned paths and commit Step-ID MDB-0075 /Step-Result completed.
Normal push, exact-source Java/docs/automatic Pages, live readback/search/sitemap
and clean matching tips precede the internal-only receipt. No later child starts.

**Next recommendation:** MDB-0076 — Durable S3 coordination generations: explicit
fresh/existing bound state, separately serialized immutable receipt generations
and fail-closed force/reopen, preserving the permanent native guard.



## 2026-11-01 — MDB-0075 Windows delivery repair

Initial source `917f52c5987464a80821da526d84e40eef50661d` reaches remote main.
Java 41387827680 passes Linux but fails Windows: BasicFileAttributes.fileKey is
null on that placement. Production correctly rejects it, while injected registry
tests incorrectly relied on real provider keys and two native cases bypassed
capability preflight. Docs 41387827682 and Pages 41387827689 pass for that initial
source; retain failed Windows evidence in ignored java-windows-initial-ci.log.

Add a package-private IdentityReader seam beside the existing injected Handle;
both production Registry constructors and PROCESS retain the unchanged strict
real identity reader. All 43 registry checks use controlled identities with real
regular/empty-name validation. Replacement controls use the same real-parent/
final-name canonicalization; three failing local replacement cases verify that
correction. Native cross-adapter/overlap tests preflight the actual required
capability before their assertions. Unsupported identity skips native checks only,
never the 43 fault checks. Update public guide and root rules with the actual
Windows limitation and portable injected-test requirement; no production fallback.

Repaired focused 58 and full optional 762 cases pass locally with zero failures,
errors or skips. Reuse unchanged core 1,272/example evidence and exact core/
classifier hashes from the source receipt. Optional main now has 71 classes
(IdentityReader is the additional interface), SHA `fab87dc8530edbd4348643b930dfdbac78bda7f2d31cde9a2ba5d2430e48d959`; no fault helpers,
ChildMain, tests or vectors enter main/classifier. Repaired strict production/
project-path 84-page builds and fifteen local canonical/content readbacks pass,
including the new Windows paragraph; search/sitemap remain correct. Existing 42
QA fixture evidence is reused and exact-source CI will execute those fixtures.
Independent repair review has no findings. Same-invocation Maven/JDK research
remains applicable with no dependency or provider requirement change.

Inspect/stage repair code/tests/guide/rules plus these queue/card/history paths;
commit Step-ID MDB-0075 /Step-Result completed and push normally. Verify exact
repair Java/docs/Pages and updated live content before delivery completion.
No next item or parent completion starts; MDB-0076 remains conditional on delivery.



## 2026-11-01 — MDB-0075 Final delivery verification

Initial source **`917f52c5987464a80821da526d84e40eef50661d`** and repair
**`dfdc24d28b0558a93aa4ce8c141a9fc304b828fd`** carry Step-ID MDB-0075 /
Step-Result completed. Normal pushes/fresh fetches confirm both on origin/main,
matching tips and clean checkout before this internal-only receipt. Preserve the
initial Windows failure as repair evidence; its passing docs/Pages did not close
Java delivery. The exact repaired source supplies completion evidence below.

- [Java 41388769784](https://github.com/MeridianDB/meridiandb/actions/runs/41388769784)
  passes both Maven builds and fourteen examples on Linux and Windows. Linux runs
  core 1,272 /optional 762 with zero failures/errors/skips, including all **43
  injected registry/fault and 15 native/process guard cases**. Windows runs all
  43 registry/fault cases without skips. Its native guard suite reports 13 explicit
  missing-file-key skips and two passing pre-identity checks; total capability
  skips are 363 core /381 optional (368 existing plus 13 guard). No unsupported
  native placement is represented as successful locking or enrollment.
- [Docs 41388769816](https://github.com/MeridianDB/meridiandb/actions/runs/41388769816)
  passes 42 QA fixtures and strict production/project-path builds. [Automatic Pages
  41388769799](https://github.com/MeridianDB/meridiandb/actions/runs/41388769799)
  passes its 42 fixtures, build/upload and deployment of full repair SHA
  `dfdc24d28b0558a93aa4ce8c141a9fc304b828fd`.
- Live HTTPS checks verify **fifteen pages** with exact production canonicals,
  current native-versus-durable boundaries, test totals and research times. The
  guide includes the actual Windows null-key observation and controlled-identity
  rule. Both new titles/routes are in search; sitemap contains **84 unique HTTPS
  production URLs**. Browser confirms repaired live wording/canonical and the
  published verification section; the session shows
  `.local-qa/mdb-0075/site-published-final.png`. Local guide/ADR/back/search and
  desktop-light/mobile-dark checks pass without overflow, and normal light viewport
  is restored. Task-owned servers 29767 and 11308 are stopped. Ignored evidence
  retains fresh Maven/JDK research, independent reviews, initial failure/repair
  logs, full exact-source CI logs, archive/XML evidence, readbacks and screenshots.

Only queue/card/history change in this receipt. Validate 77 IDs/cards/DAG/eligibility,
inspect/stage these three paths, commit/push normally and verify source ancestry,
matching tips and a clean checkout. Existing workflow filters do not trigger on
this internal-only receipt. MDB-0075 is complete; MDB-0072/0067/0039/0011 remain
expanded. No next-child implementation, Maven artifact release/upload, real-provider
mutation or account/DNS/hosting change starts.

**Next recommendation:** MDB-0076 — Durable S3 coordination generations: explicit
fresh/existing bound state, separately serialized immutable intent/completion
receipts, force/capacity reservation and fail-closed reopen beside the permanent guard.


## 2026-11-02 — MDB-0078 Linux local storage priority and format research

**Outcome:** completed planning source for the owner's Linux production-target
pivot. Java/Maven and the current JDK I/O baseline remain. Windows needs basic
logical/build/local smoke coverage rather than native parity. Existing S3 source,
tests, implemented guarantees and incomplete acceptance requirements are retained;
its unfinished work is deferred. Kafka-topic persistence and other providers stay
outside the selected local path. Replication remains separately planned.

**Artifacts and research:** ADR 0039 and `docs/architecture/linux-storage.md` own
the public direction and candidate design. The guide distinguishes ordered durable
history from temporal query blocks, entity/time ordering, conservative pruning,
correction-induced region splitting, indexed-cutoff/tail resolution, exact UTF-16
and unbounded bounds, aggregate memory budgets, block-integrity/versioning and
compatibility. Dated November 2 primary sources cover Parquet layout/page indexes,
RocksDB blocks/caching, Lucene release-tagged BKD, Linux synchronization,
preallocation/advice, reflink/copy/direct/asynchronous I/O and Hadoop native/local
reads. It records candidate inferences rather than invented gains: specialize for
measured temporal workloads and report regressions; no universal speed ranking or
production qualification follows. Reflinks share a failure domain and neither
cloning nor copying supplies node replication or the database publication protocol.

**Caller and queue reconciliation:** updated root agent rules, README, homepage,
public roadmap, architecture/persistence/research/segment guides, S3 entry/protocol,
build/contribution guides, decision index, ADR 0004/0020 and navigation plus internal
workflow/roadmap/queue. The earlier object-first sequence is explicitly historical.
All 77 original IDs, completed results and parent/child criteria remain. Twelve
unfinished S3 rows, including four expanded parents and former ready MDB-0076,
are deferred. Added MDB-0078 source completion, ready MDB-0079 Linux profile/harness
and queued MDB-0080 evidence-led operational hardening. Existing MDB-0012 now depends
on local correctness/MDB-0079, not MDB-0011. The selected path is MDB-0079 →
MDB-0012 local baseline → MDB-0013 measured layouts/I/O → MDB-0080 hardening →
MDB-0014 experimental candidate. MDB-0041 remains separately deferred.

**Verification:** queue checker passes 80 unique IDs, exact cards/anchors, parent/
dependency DAG, ready eligibility and completed children. All original dependency/
child criteria and source evidence were inspected; independent read-only planning/
source review has no actionable findings. Forty-two documentation QA fixtures pass;
strict production and project-path builds pass with 86 public pages. Fifteen local
HTTP content/canonical readbacks, both new search entries and the 86-URL HTTPS
sitemap pass. Browser verifies guide→decision→guide, title search and desktop-light/
390×844-dark table readability without page overflow; normal light viewport is
restored. Ignored `.local-qa/mdb-0078/` retains research, build/readback logs and
screenshots. No Java/Maven/runtime/format/CI inputs changed; earlier 1,272 core /
762 optional acceptance evidence is retained without rerunning unchanged suites or
misrepresenting it as Linux production qualification. No Maven version is selected
or changed in this documentation/planning step.

**Delivery:** inspect/stage explicit task-owned documentation/rules/planning paths,
commit Step-ID MDB-0078 / Step-Result completed and push normally under standing
authorization. Exact-source documentation CI and automatic Pages deployment/live
readback remain pending for the delivery receipt. No format/native implementation,
next queue step, native/cloud provisioning, library release or account change starts.

**Next recommendation:** MDB-0079 — Linux storage contract and qualification harness.


## 2026-11-02 — MDB-0078 Delivery verification

Planning source **`4ba55629490c8fd49e5d351af3b9c43cbdf9aa35`**, with Step-ID
MDB-0078 /Step-Result completed, was normally pushed and verified as an ancestor of
origin/main with matching clean tips before this internal-only receipt.

- [Java 41454600048](https://github.com/MeridianDB/meridiandb/actions/runs/41454600048)
  passes both builds and all fourteen examples per platform. Linux job 125307960023
  runs **1,272 core /762 optional tests**, zero failures/errors/skips. Windows
  job 125307960648 passes with unchanged 363 core /381 optional capability skips.
  This automatic run was triggered by the existing storage-format-guide path
  filter; no Java, Maven, native-format or CI configuration changed. Broad Windows
  regression coverage does not change its basic compatibility role.
- [Docs 41454600128](https://github.com/MeridianDB/meridiandb/actions/runs/41454600128)
  passes production job 125307960545 and project-path job 125307960934. Exact-source
  logs confirm 42 QA fixtures and strict source/HTML/link/search/sitemap checks.
- [Automatic Pages 41454600658](https://github.com/MeridianDB/meridiandb/actions/runs/41454600658)
  passes build 125307961733 and deploy 125308243636 for the full source SHA. Live
  readback verifies fifteen HTTPS pages, exact production canonicals, both new
  search routes/titles and **86 unique production sitemap URLs**. The browser
  verifies the published local-first roadmap. Proof is saved at ignored
  `.local-qa/mdb-0078/site-published-final.png` and embedded in session commentary.
  Earlier guide/decision/return/search, desktop-light and 390×844-dark table checks
  passed; the normal light viewport is restored. Task-owned local server 87972 is
  stopped with exit 0. Full exact-source CI and readback logs remain ignored scratch.

Only queue/card/history change in this receipt. Validate 80 IDs/cards/DAG and
preserved deferrals, inspect/stage these three paths and push normally. The
internal-only receipt matches no workflow filter, so it needs no duplicate CI.
No Linux qualification or performance result is inferred from these regressions;
no new format/native dependency, next step, S3 continuation, deployment outside
Pages or release begins.

**Next recommendation:** MDB-0079 — Linux storage contract and qualification harness.


## 2026-11-02 — MDB-0079 Linux qualification harness checkpoint

**Outcome:** implemented a bounded development harness around the unchanged local
core. `scripts/storage/qualify_linux.py` creates a fresh run-owned scratch child,
observes real path/st_dev/mountinfo plus limited sysfs queue facts, records source
commit/dirty state and kernel/architecture, and invokes core clean/install with an
explicit fixture root, JUnit factory, fork startup temp path, fresh report directory
and retries disabled. `required-suites.json` preserves all existing core test
coverage plus the new native metadata/capability probe: 68 suites, 1,273 invocations.

The test-only factory roots every current JUnit fixture; existing process helpers
receive explicit fixture paths. LocalStorageQualificationTest checks its actual
placement, the production directory-sync probe, file/directory force, file key,
actual shared/exclusive locks, atomic move and unchanged identity after rename.
It emits run-bound Java/JDK/FileStore evidence only in qualification mode. Ordinary
portable builds retain explicit unsupported-directory handling. No production
engine, native binding, public API, persisted bytes or acknowledgement changes.

**Evidence guards:** bounded fresh XML/JSON, exact suite/invocation inventory,
nonempty unique testcase identity, coherent counters, root/run/factory/runtime
bindings and complete probe capabilities are required. DTDs/entities, non-UTF8,
duplicate JSON/properties, retry/flaky outcomes, wrong runtime/filesystem, skips,
partial/missing/oversized reports and nonzero Maven all fail. Timeout/interruption
and nonzero leader exit clean the dedicated process group, including a TERM-
resistant fixture child. Failed run-owned evidence remains. Raw mount source/
overlay paths, credentials, hostnames and serials are not collected; missing or
virtual device/cache details remain unknown. ProductionQualified stays false.

**Build/CI/research:** POM adds a narrow reports-directory property wired to
Surefire, defaulting to its ordinary target path. Linux CI runs 40 Python guards,
the actual-root core harness, unchanged optional regression and all examples;
Windows retains its ordinary broader regression build/basic support role. Added
script and Python-version path filters. The officially researched upload-artifact
v7.0.2 is pinned at cf430e030ddbb5b0abf93d22962f4752f3646cd9; a 14-day test artifact
retains JSON/XML/Maven logs, separate from library releases. Fresh Central and
28 official metadata/compatibility sources were checked November 2 at 14:52:53/
14:52:54 UTC (09:52:53/54 EST); all fourteen selected stable versions remain latest.
Research hashes: metadata 39522c010b319e9ae966b7730e9d662bea2f45633334a10bbc0b95ca09b858db,
compatibility 576b68662bb71e3404de5af2388d317d61af4901fb51e77c83d71f8e75345a58,
report d73f2eb95a0e1c8c9e2eb78e7db1e9f345b8e434c8a1ff8fc6fabca955eac60c.
Current Surefire, JUnit factory, Linux mountinfo/sysfs ABI and artifact action inputs
supply dated primary evidence in public guides. No dependency/Wrapper pin changes.

**Local verification:** ordinary core clean/verify and explicit-root clean/install
both pass 1,273 cases, zero failures/errors/skips. The rooted run's 68 fresh XML
reports pass strict parsing and actual fixture/JVM/FileStore binding; it observed
macOS arm64/APFS with Oracle JDK25.0.2, not Linux qualification. Optional verify
passes all 762 cases and fourteen packaged-core examples pass. Archive inspection
confirms 155 production classes, ten contract-support classes and no qualification
helpers in either; jdeps remains java.base. Forty Python guards pass, including real
timeout and nonzero-exit grandchild cleanup. Forty-two docs fixtures, both strict
88-page URL builds, fourteen local HTTP content/canonical readbacks, both new search
entries and sitemap pass. Browser guide→decision→guide/search and desktop-light/
390×844-dark tables pass without page overflow; normal light viewport is restored.
Independent reviews resolved all findings; full exact Linux acceptance remains pending.

**Documentation:** guide and ADR 0040 plus README, home/roadmap, Linux architecture,
build/contribution/durability guides, decision index/nav and agent rules agree on
same-filesystem evidence and no production/power-loss claim. Updated five current
core-test count callers while preserving S3 code and its deferred scope. Maintain
inventory from reviewed complete results rather than reducing it to mask failures.

**Delivery checkpoint:** commit Step-ID MDB-0079 /Step-Result checkpoint and push
normally to exercise actual Linux CI. Retain the active item and every original
acceptance criterion until exact-source Linux profile, full CI, automatic Pages
and live checks pass. Then record completion and make MDB-0012 ready; this invocation
starts no benchmark, next implementation, S3 work or library release. Ignored
`.local-qa/mdb-0079/` holds raw research, all local logs/reports, readbacks and images.

**Next action:** finish MDB-0079 Linux/remote delivery; only then recommend MDB-0012.


## 2026-11-02 — MDB-0079 Linux acceptance and completion

**Actual source and Linux evidence:** checkpoint
**`520b86da523fbea3cf82b83c70e716d05ca8f664`** is verified on origin/main. Its
Step-Result checkpoint correctly retained the active item until this actual Linux
run, rather than treating the earlier Mac/APFS binding check as qualification.
[Java 41470158485](https://github.com/MeridianDB/meridiandb/actions/runs/41470158485)
passes Linux job 125361299119 and Windows job 125361298186, with fourteen examples
per platform. Linux passes all 40 Python guards, **68 suites /1,273 core cases**
with zero failures/errors/skips, then all 762 optional regressions. Windows passes
1,273 core with 364 explicit capability skips (one new native probe) and 762 optional
with 381 existing skips. S3 code/scope is unchanged and deferred.

The driver emitted schema 1 status checks_passed, productionQualified false,
runID **2b00a33982fa4038b7a671108b81d51e**, label
**github-ubuntu-java25-local-v1**, source commit 520b86d and dirty false. The
inventory SHA256 **7b3a2cdb93235cc04ef7dcc0efd1fec00acc4de53e167a6d81c2b400bc90fe8c**
matches the committed required-suites.json; source/report counts and actual fixture/
runtime/FileStore bindings pass. Native observations: production-directory-probe,
file-force, directory-force, file-key, actual-shared-lock, exclusive-lock,
atomic-move and identity-after-move.

**Observed CI profile:** kernel **7.0.0-1017-azure**, machine **x86_64**, Java
architecture amd64, **Eclipse Adoptium 25.0.5 /25.0.5+8-LTS**, FileStore/mount
**ext4**, mount ID 28, device 8:1 at `/`. Recognized settings:
`commit=30,data=writeback,discard,errors=remount-ro,nobarrier,relatime,rw`, with
one unreported option. Write-cache, logical/physical block size and rotational
facts are all null; backing device/controller remain unknown. This is explicitly
**CI process/fault/capability evidence only**, not a production mount profile or
power-loss/force-to-hardware guarantee. MDB-0080 carries those deployment, mount,
device/controller/cache and failure-qualification gaps. Benchmarks still need
controlled environment/cache evidence under MDB-0012.

**Retained test artifact:** ID **13155005925**, name linux-storage-evidence,
**190,231 bytes**, digest
**bd4d0bdc28e4c58881f424d09d4361861a83ce03b99e25bb39f80f662d439173**,
created November 2 at 15:19:05UTC and expires **November 16, 2026 at 15:19:03UTC**.
The API associates it with run 41470158485, main and the full checkpoint SHA. The
retained JSON contains every case outcome; XML and full Maven log remain in the
14-day test artifact. The checked job summary and durable facts above preserve
scope when that artifact expires. Ignored local copies hold full CI logs and the
parsed Linux summary; no device serial/hostname/credential data is collected.

**Documentation/publication:** [docs 41470158494](https://github.com/MeridianDB/meridiandb/actions/runs/41470158494)
passes production job 125361298908 and project-path job 125361298481 with 42 fixtures
and strict source/HTML/link/search/sitemap checks. [Pages 41470158499](https://github.com/MeridianDB/meridiandb/actions/runs/41470158499)
build 125361298114 and deploy 125361547047 pass for exact checkpoint SHA. Fourteen
live HTTPS content/canonical readbacks, both new titles/routes and 88 unique HTTPS
sitemap URLs pass. Browser verifies published guide/inventory and records
`.local-qa/mdb-0079/site-published-final.png`, embedded in commentary. Earlier
local guide→decision→guide/search and desktop-light/mobile-dark tables pass; normal
light viewport is restored, local server 54593 is stopped with exit 0. Independent
review confirms every original 0079 criterion with the production gaps explicit.

**Completion delivery:** this commit changes queue/card/history and clarifies the
public guide's scratch-directory preservation versus Maven clean replacing
ordinary target output. No runtime/POM/test/CI inputs change; reuse exact-source
Java evidence above, run affected strict docs checks, push Step-ID MDB-0079 /
Step-Result completed normally and verify final docs/Pages/live clarification.
Keep all 80 IDs, S3 deferrals and original parent criteria. MDB-0079 is done;
MDB-0012 is ready. This invocation starts no benchmark, next implementation,
format/native optimization, cloud/provider mutation or library release.

**Next recommendation:** MDB-0012 — Reproducible workload baseline.


## 2026-11-02 — MDB-0079 Final delivery receipt

Completion **`17a2c0edb37c8dfd723450b53934430aa0ceb073`** carries Step-ID MDB-0079 /
Step-Result completed and is verified on origin/main. Implementation checkpoint
**`520b86da523fbea3cf82b83c70e716d05ca8f664`** remains its ancestor and owns the
actual passing Linux runtime/profile evidence above. Completion changes only
planning and the public guide's precise cleanup clarification, so Java/runtime
checks are correctly reused; no new Java workflow is triggered by these paths.

- [Final docs 41471550359](https://github.com/MeridianDB/meridiandb/actions/runs/41471550359)
  passes project-path job 125366121477 and production job 125366121814, with 42 QA
  fixtures and strict source/HTML/link/search/sitemap validation at the exact
  completion SHA.
- [Final Pages 41471550376](https://github.com/MeridianDB/meridiandb/actions/runs/41471550376)
  passes build 125366122539 and deploy 125366371164 at the same SHA. Fourteen live
  HTTPS content/canonical checks, both search routes/titles and 88 sitemap URLs
  pass. The guide includes the new statement that Maven clean replaces ordinary
  target output while preserving the scratch parent and older runs. Browser
  confirms it live; ignored `.local-qa/mdb-0079/site-completion-final.png` is
  embedded in commentary. Normal light viewport is restored and the local server
  remains stopped. Full final CI and live-readback logs are saved in ignored QA.

This receipt updates only queue/card/history, validates all 80 IDs/dependencies/
criteria and preserves all S3/replication deferrals. Commit/push normally and verify
completion ancestry, matching tips and clean status. These internal-only paths
trigger no duplicate CI or publication. No benchmark, native/file-format change,
S3 continuation, real-provider mutation or library release starts.

**Next recommendation:** MDB-0012 — Reproducible workload baseline.


## 2026-11-02 — MDB-0081 Deterministic temporal workload corpus

**Scope and planning:** resumed from clean matching main/origin at
`e1f31b36165bf87110f734452fe82451680c3119`. Expanded MDB-0012 into required
MDB-0081 corpus, MDB-0082 local measurement runner and MDB-0083 Linux evaluation/
conformance. Retain all original latency/throughput/CPU/allocation/heap, encoded/
allocated bytes, actual I/O/amplification, replay/checkpoint/compaction criteria.
All 83 IDs, S3 deferrals and independent replication scope remain. This invocation
implements only MDB-0081; no timed runner, benchmark result, optimization, native
binding, release or remote-provider change starts.

**Implementation:** five test-only workload files define bounded v1 immutable
spec/actions, four 16-entity/128-commit/64-query defaults and separate write/read/
retry Random streams. Explicit setup boundaries, original request IDs, new no-op
commits, exact retry sequences and read cutoffs are validated before prepared
answers escape. Preserve UTF-16 identities and exact signed-long/BigInteger values.
Streaming schedule SHA256 binds spec/actions/typed query fields; separate history
SHA256 binds the exact journal header and original frames. The literal seed 7
vector has 354 encoded bytes, schedule
`39bd170644930733aef2da90da5ed3d9c1d42bf48c37a42052d19f57e52f2af0`
and history `cdbb2a26ffde6b13937bf196fa3fe77630c6b65922696e67073d37050be7a211`.
Random 48 aliases are explicit. Constructor admission caps dimensions, reference
work estimate, action/encoding totals, identities, finite width and query fanout.
Finite one-operation scope is deliberate and leaves canonical contracts unchanged.

Independent oracle preparation uses the existing backward reference, checks
actual replay/sequence and cutoff availability, then records realized intent/effect,
geometric overlap, entity history/skew, query/hit/miss/head/historical counts with
denominators. Counters overlap; fallback restoration is an actual put even when
scheduled as a retraction. Separate literal answers/metadata and a forward dense
cell model avoid treating agreement with production as an independent oracle.
All four profiles execute actual memory and writable local databases with reopen
after every 16 new commits, pinned read/retry sweeps and closed-journal byte/hash
comparison. No corpus helper is attached to production/classifier artifacts.

**Validation:** initial focused12 and then34/35 invocations passed as coverage grew;
a private memory-constructor compile attempt was corrected to use the existing
public no-argument API and its assigned lineage, without changing production.
Independent review caught an unreachable extreme-predicate schedule; `(index/5)%5`
and a behavioral regression fix it. Final review added setup-stage validation and
its malformed-schedule check; final focused **36** cases pass (14 corpus,13 oracle,
9 conformance), zero failures/errors/skips. The final tiny-spec fanout boundary
assertions accept 2/reject 3. No remaining actionable independent review findings.

Complete `./mvnw -B clean install` with a fresh explicit qualification fixture root,
report directory, fork tmpdir, test-only factory and run bindings passes **1,309
cases in 71 suites**, zero failures/errors/skips. Local run
`6e8d8bf87a0242f1bdc3782b83050377` uses Oracle Java 25.0.2 /runtime 25.0.2+10-LTS-69,
macOS aarch64. All71 reports pass the strict `read_reports` parser with matching
run/root/runtime bindings. Prior68 suite counts are unchanged; only new 14/13/9
workload suites are added. Inventory SHA256
`d84101066178be9e03ac3d0e2eaaf6c0332f29e141acd982954740c5290c0287`.
The preceding full run with 35 new checks passed 1,308; the stage-validation change
justified the final complete rerun. The subsequent fanout assertion strengthening
passes all 36 focused checks. Actual Linux execution remains an exact-source CI
check; these local results are not Linux/device qualification.

Optional `./mvnw -B -f adapters/s3/pom.xml verify` passes **762**, zero failures/
errors/skips. All **fourteen** examples run against the packaged core JAR. Archive
inspection preserves 155 production classes and 10 classifier classes, no workload/
probe/factory leakage; `jdeps --print-module-deps` remains `java.base`. No POM,
Wrapper, dependency version or production source changes. Python storage guards
pass 40; public documentation fixtures pass 42. Ignored `.local-qa/mdb-0081/` holds
research bodies, logs, report bindings, vectors, archive facts and screenshots.

**Fresh Maven research:** all 14 current coordinates were checked from current
Central metadata at **16:19:43 UTC /11:19:43 EST**, official compatibility at
**16:19:44 UTC /11:19:44 EST**, November 2. Selections remain Maven 3.10.0,
Wrapper 3.3.4, clean/resources 3.5.0, compiler 3.16.0, Surefire 3.6.1, jar 3.5.1,
install/deploy 3.2.0, JUnit 6.1.3, Jackson 3.2.4 and both AWS SDK 2.57.8 components.
All selected releases remain latest stable compatible;4.x preview/beta entries
are not substitutions. Metadata summary SHA256
`f6b8d65021a88209ae909d338c7d0fe3f05cded6c65ddaa12d8583b62df23003`;
compatibility summary
`0267e49024d25c2ae4c4bfebb95f811c1a1741425ae0da2b98c0636121f5681b`.
Public build guide updates the actual research times and retains exact source
links. Java 25 Random primary specification confirms the portable 48-bit generator;
its seed aliases are tested rather than treated as independent datasets.

**Documentation and rules:** new public workload guide and ADR 0041 describe profiles,
bounds, fingerprints, literal vectors, observed seed 42 metadata, limitations and
measurement separation. README/home/roadmap/Linux design/contribution/build and
three S3 current-count callers are reconciled; navigation and decision index link
both pages. Agent rules persist correct generation/oracle/timing/reset/fingerprint
and physical-byte boundaries. No chat/owner/task-state narrative enters public
copy. Both production and project-path strict builds pass for 90 public pages,
including source, HTML, links, search and sitemap. Browser verifies desktop light,
390 px mobile dark table containment (375px scroll width), guide/decision navigation
and both search results. Normal light viewport is restored. Local server 8790
remains available until delivery verification, then must stop. No renderer/asset
or theme change is introduced.

**Source completion:** mark MDB-0081 done and MDB-0082 ready, retaining MDB-0012
expanded. Commit with Step-ID MDB-0081 /Step-Result completed, push normally,
verify exact-source Java/docs/Pages and live guide/ADR/canonicals/search/sitemap,
then record actual delivery. New Linux inventory must pass with zero skips;
Windows retains four additional explicit native-capability skips, pure corpus
checks remain runnable there. Hosted Linux evidence still has unknown device/
cache and known mount limitations; MDB-0080 owns production qualification.

**Next recommendation:** MDB-0082 — Local workload measurement runner.


## 2026-11-02 — MDB-0081 Verified delivery receipt

Completion **`1b41bd397dbe28de4db8fa7f712d3b097f4cdd5b`** carries Step-ID MDB-0081 /
Step-Result completed and is verified on origin/main with matching clean tips.
All delivery checks use that exact source; no production/POM/dependency code changed.

- [Java 41485888694](https://github.com/MeridianDB/meridiandb/actions/runs/41485888694)
  passes Linux job 125416003995 and Windows job 125416004633. Linux runs all
  **1,309 core cases /71 suites**, zero failures/errors/skips, **762** optional
  cases with zero skips, forty Python guards and all fourteen examples. Windows
  passes 1,309 core with 368 explicit capability skips and 762 optional with 381
  skips, plus fourteen examples; all 32 pure workload cases run and four native
  workload cases explicitly skip. No retry-masked success is admitted.
- Linux profile run **`9d224b6cdf9a405580814d93ca473c67`**, observed16:53:54 UTC,
  binds clean exact source, scratch-root/runtime evidence and inventory SHA256
  `d84101066178be9e03ac3d0e2eaaf6c0332f29e141acd982954740c5290c0287`.
  All71 suite counts equal the tracked inventory, including14 corpus/13 oracle/
  9 conformance. Native evidence includes directory probe, file/directory force,
  nonnull file key, actual shared/exclusive locks, atomic move and identity after
  move. Runtime is Eclipse Adoptium25.0.5+8-LTS / amd64; kernel 7.0.0-1017-azure,
  x86_64, ext4. Recognized mount flags remain `commit=30`, `data=writeback`,
  `discard`, `errors=remount-ro`, `nobarrier`, `relatime`, `rw`; one option omitted.
  Write cache, logical/physical block size and rotational state are unknown.
  Keep **productionQualified: false** and device/controller/power-loss/operational
  gaps with MDB-0080. This is hosted process/capability correctness, not controlled
  performance or production-device qualification.
- Test artifact **13161617264**, linux-storage-evidence, **197,946 bytes**, digest
  **`9c98f9ed540a5e890fbe80ca48799dfed5c99fcdb2d8e2ae4431a43a04262815`**,
  is associated with that run/main/full source SHA. Created November 2 at 16:54:29 UTC;
  expires **November 16, 2026 at 16:54:28 UTC**. Full case outcomes, XML and Maven log
  are retained 14 days; durable source/run/count/environment facts above remain
  after artifact expiration. Ignored QA holds fetched job logs and parsed summary.
- [Documentation 41485888464](https://github.com/MeridianDB/meridiandb/actions/runs/41485888464)
  passes production job 125416002728 and project-path job 125416003242, each with 42
  QA fixtures and strict source/HTML/link/search/sitemap checks.
  [Pages 41485888491](https://github.com/MeridianDB/meridiandb/actions/runs/41485888491)
  build 125416003603 and deploy 125416263307 pass. Twelve live HTTPS content/canonical
  readbacks pass, including updated core counts and actual Maven research times;
  both new guide/ADR search entries and 90 unique canonical sitemap URLs pass.
  Browser verifies published guide, 36-check status and canonical; ignored
  `site-published.png` is embedded in commentary. Earlier local search→ADR→guide,
  desktop light and 390 px mobile dark checks pass. Light/default viewport restored;
  local server 8790 stopped with exit 0. Initial readback expected the phrase
  physical I/O in ADR body; its actual accurate wording is device I/O, so only
  the ignored readback assertion was corrected before all twelve checks passed.

This receipt updates only queue/card/history; runtime and public documentation
remain byte-identical to the verified completion. Validate 83 IDs, DAG, completed
children and eligibility, inspect staged diff, commit/push normally and verify
completion ancestry, matching tips and clean status. Internal-only paths start no
new CI/publication. MDB-0081 is done, MDB-0082 ready, MDB-0012 expanded; no next
implementation, benchmark run, S3 continuation, native format or release starts.

**Next recommendation:** MDB-0082 — Local workload measurement runner.


## 2026-11-02 — MDB-0084 Local operation measurement core

**Scope:** resumed clean matching main/origin at
`8b85be23a41823d6e4c1cba4aa976229a26751a9`, with MDB-0081 remotely verified.
Expanded broad MDB-0082 into required MDB-0084 in-process core and MDB-0085
supervised Linux evidence/accounting. Original 0082 and 0012 criteria remain;
0083 still owns actual evaluation. All 85 IDs and S3/replication deferrals remain.
This invocation executes only MDB-0084, with no CLI, physical/controlled baseline,
new dependency, native binding, format change, remote provider or release.

**Implementation:** four test-only files add a bounded synchronous runner and
metrics helper with 26 focused cases. Prepare oracle answers/segment images once;
use a fresh namespace for each warmup/repetition (0..2 /1..5, at most 20,000 total
corpus actions). Measure actual forced new commits/retries and pinned queries,
journal writable reopen, original segment writes, generation-two publication,
pure supplied-image compaction planning, generation-three publication with one
old reader pinned, and checkpoint writable reopen. Check independent outcomes,
original request positions, old/new records and exact closed-journal identity
outside operation clocks; all old generations/segments remain, without reclamation.

Typed v1 results bind corpus inputs/fingerprints, runtime/configuration and each
iteration's raw phase/kind samples. Nearest-rank summaries retain min/p50/p95/p99/
max, counts and service rates separately from all-action loop wall rate, validation
and other probe/recording/dispatch time. CPU/allocation use already enabled optional
current-platform-thread counters; missing/reset/virtual values stay null with
availability counts. Heap samples can fall and are not RSS/retained/peak claims.
Named lengths are logical bytes; allocated storage, actual I/O/amplification,
source/mount/device provenance and process supervision remain explicit MDB-0085 gaps.

**Review/fixes:** a compile-time serial warning under Werror was fixed by storing
the failure-directory identity as String rather than Path. Independent review
corrected modular nanoTime subtraction (a signed MAX→MIN transition can be 1 ns),
Windows-only unsupported skips (Linux must fail), and fresh-directory durability.
Before CREATE, untimed production capability/control preflight closes, then home→
run-root→already-durably-named scratch parent forces establish child names. Typed
scope and public guide disclose excluded initial control-file/preflight cost and
separate namespacePreparationNanos. Post-call instrumentation failure closes a
newly returned owner; cleanup failures are suppressed on the original exception.
Final independent source/docs/planning review has no actionable findings.

**Checks:** focused 26 passed, then complete rooted `./mvnw -B clean install`
passed 1,335. The naming/lifecycle fix justified final complete rerun: **1,335 cases
in 73 suites**, zero failures/errors/skips, fresh run
`e259fbd3b4664dfdbdd09f68666ec15f`, Oracle Java 25.0.2 /runtime 25.0.2+10-LTS-69,
macOS aarch64. Strict report parser confirms every run/root/runtime binding.
Inventory preserves all previous 71 counts and adds 9 runner +17 metric cases;
SHA256 `171ca84a854a24bbc76f0673d03fefb405e02ee0ff3ecc47c1065be403c3cb2c`.
`./mvnw -B -f adapters/s3/pom.xml verify` passes 762 with zero skips, and all 14
examples pass against the unchanged production JAR. Final archives contain 155
production /10 classifier classes, no workload/probe/factory leakage; production
jdeps remains java.base. Python storage guards 40 and docs fixtures 42 pass. Actual
Linux execution remains exact-source CI; local macOS observations are not Linux
or production-device evidence. Ignored `.local-qa/mdb-0082/` retains logs/results.

**Research:** freshly fetched all 14 Central sources at **November 2, 23:06:56 UTC /
18:06:56 EST**, official compatibility at **23:06:57 UTC /18:06:57 EST** and Java 25
measurement specifications at 23:07:37 UTC /18:07:37 EST. Pins remain latest stable
compatible: Maven 3.10.0, Wrapper 3.3.4, clean/resources 3.5.0, compiler 3.16.0,
Surefire 3.6.1, jar 3.5.1, install/deploy 3.2.0, JUnit 6.1.3, Jackson 3.2.4 and AWS
SDK 2.57.8. No POM/Wrapper/version change. Metadata SHA256
`45252caf9f4b5981e8b7d3c27aa3115971be4084a088f889f0776fb4b6403728`;
compatibility `075d0ea5b76a8e26a1c95caf2e4ca27895514e435b01868d067a752a5dca49e9`;
metrics `6b04391a609b1c0a8628abcc1c11f6dfd21d920f1052c3f8c717ae30034627bf`.
Primary Java 25 CPU/allocation/heap/nanoTime documentation supports the recorded
limits and clock fix; exact URLs/times remain in research and the public guide.

**Documentation:** new guide/ADR 0042, README/home/roadmap, corpus/ADR 0041,
Linux design, contribution/build and three S3 current-count callers are reconciled.
Root rules persist fresh directories, call/check/counter boundaries and failure
cleanup; no internal owner/chat/queue narrative enters public copy. Both strict
92-page production/project-path builds pass source/HTML/link/search/sitemap QA.
Browser verifies desktop light, 390 px mobile dark table containment, search results
and search→ADR→guide navigation. Normal light/default viewport restored; local
server 8791 remains until delivery readback, then must stop. No theme/asset changes.

**Completion:** mark MDB-0084 done, MDB-0085 ready, both parents expanded; validate 85 IDs,
DAG/cards/eligibility and stage only owned files. Commit with Step-ID MDB-0084 /
Step-Result completed, push normally and verify exact-source Java/docs/Pages/live
checks before further execution. Record Linux inventory/profile and expected
Windows 6 additional native skips separately; retain production qualification gaps.
No supervised runner, benchmark evaluation or other next implementation starts.

**Next recommendation:** MDB-0085 — Supervised Linux workload evidence.


## 2026-11-02 — MDB-0084 Verified delivery receipt

Completion **`742ea991b6b5298c78ebca31b8b208bd8ca4ef88`** carries Step-ID MDB-0084 /
Step-Result completed and is verified on origin/main with matching clean tips.
All checks use that exact source; production source/POM/dependency versions remain
unchanged. The parent runner and baseline remain expanded.

- [Java 41545388930](https://github.com/MeridianDB/meridiandb/actions/runs/41545388930)
  passes Linux job 125619599021 and Windows job 125619598668. Linux runs **1,335
  core cases /73 suites**, zero failures/errors/skips, **762** optional cases with
  zero skips, forty Python guards and all fourteen examples. Windows passes 1,335
  core with 374 explicit capability skips and 762 optional with 381 skips, plus
  fourteen examples. The new metric 17 and pure runner 3 checks run; six native
  runner cases skip only on that Windows provider. No retries mask failures.
- Linux run **`f5061c7e00e54d94b7531912978eada5`**, label `github-ubuntu-java25-local-v1`,
  binds clean exact source and inventory SHA256
  `171ca84a854a24bbc76f0673d03fefb405e02ee0ff3ecc47c1065be403c3cb2c`.
  All 73 suite counts equal the tracked inventory, including new 9 runner and 17
  metric cases. All eight native observations pass: production directory probe,
  file/directory force, file identity, actual shared/exclusive locks, atomic move
  and identity after move. Runtime is Eclipse Adoptium 25.0.5+8-LTS / amd64,
  kernel 7.0.0-1017-azure, x86_64, ext4 (observed major/minor 8:1). Recognized flags
  are `commit=30`, `data=writeback`, `discard`, `errors=remount-ro`, `nobarrier`,
  `relatime`, `rw`; one option omitted. Write cache, logical/physical block size,
  rotational state and backing controller are unknown. Preserve
  **productionQualified: false**; hosted correctness does not qualify performance,
  device power loss or production. MDB-0080 retains those deployment gaps.
- Test artifact **13188392288**, linux-storage-evidence, **203,270 bytes**,
  digest **`9580a8edfb77a48d1744500660982bb242e6dc0a0803c0835e7eab079dca38a2`**,
  is associated with main/full source and run 41545388930. Created November 2 at
  23:26:59 UTC and expires **November 16, 2026 at 23:26:59 UTC**. JSON/XML/full Maven
  logs retain 14 days; this durable summary preserves source/runtime/scope/counts
  after expiration. Ignored QA holds fetched CI logs and parsed profile summary.
- [Documentation 41545388908](https://github.com/MeridianDB/meridiandb/actions/runs/41545388908)
  passes project-path job 125619599089 and production job 125619599955, each with 42
  fixtures and strict source/HTML/link/search/sitemap validation.
  [Pages 41545389042](https://github.com/MeridianDB/meridiandb/actions/runs/41545389042)
  build 125619598882 and deploy 125619805055 pass. Fourteen live HTTPS page/content/
  canonical checks verify both new pages, caller updates, 1,335 core counts and the
  actual Maven research times. Both new search entries and 92 unique canonical
  sitemap URLs pass. Browser verifies published guide, 26-check status and excluded
  namespace-preparation scope; `site-published.png` is embedded in commentary.
  Earlier desktop light, 390 px mobile dark and search→ADR→guide checks pass.
  Normal light/default viewport restored; local server 8791 stopped with exit 0.

This receipt updates only queue/card/history, with runtime/public source identical
to the verified completion. Validate 85 IDs/cards/DAG/eligibility, inspect staged
diff and commit/push normally. Verify source ancestry, matching tips and clean
status. Internal-only changes trigger no duplicate CI/publication. MDB-0084 is done,
MDB-0085 ready, MDB-0082/0012 expanded; no supervised execution, actual baseline, S3 work,
native binding, format experiment or library release starts in this invocation.

**Next recommendation:** MDB-0085 — Supervised Linux workload evidence.


## 2026-11-03 — MDB-0085 Supervised workload source checkpoint

**Scope:** resumed clean matching main/origin at
`26d77a46cee9127c3fe9cf390da50cebda5f65dd`. Execute only MDB-0085; preserve
all 85 IDs, required children and original MDB-0082/0012 criteria. Both parents
remain expanded. No actual baseline comparison, production qualification, format
change, native dependency, S3 continuation, provider action or release.

**Implementation:** a test-only JDK worker wraps the existing bounded corpus/runner,
exports a strict version-one envelope and brackets complete iterations with seven
raw `/proc/self/io` counters. Preserve reset/missing readings as explicit gaps;
keep cancelled writes separate. Complete output publishes create-only through a
closed partial file and hard link, retaining collision/failure evidence. Python
admission recomputes all sample inventories, nearest-rank summaries, rates and
counter availability; checks exact run/source/PID/config/runtime/mount bindings;
reobserves every final ordinary file and closed-journal length/SHA; and separately
reports logical lengths, Linux st_blocks*512 attribution and process-byte ratios
with encoded-journal denominators. No device-transfer, reclaimed-space or durability
claim follows from accounting.

The Linux supervisor synchronizes fresh directory names, uses one overall deadline,
clean compilation, source/class content fingerprints, explicit Java/classpath/heap
and scrubbed option injection. Git/build/worker processes all use bounded capture
and private sessions/groups. Preserve leader identity until cleanup; terminate and
reap even after zero exit, enumeration error, timeout, signal or output overflow.
This covers cooperating group members, not arbitrary escaped sessions. Retain
bounded request/build/worker/report evidence; log a compact validated provenance
and accounting summary. Final observed deadline exhaustion overrides prior success.

**Checks:** complete rooted `./mvnw -B clean install` passes **1,347 cases /74
suites**, zero failures/errors/skips, run `ebab475ec28c4e7f8ce23ce5141d6a26`,
Oracle Java 25.0.2 /runtime 25.0.2+10-LTS-69, macOS aarch64. Strict parser verifies
every suite's run/root/runtime binding. Reviewed inventory preserves all prior
73 suite counts and adds `WorkloadWorkerTest: 12`; SHA256
`b07fbb6592703dad09a37dd7e0b6cd5c12f3931c29430042e61df62dc846012b`.
Optional S3 verify passes 762, zero skips; fourteen examples pass. Archives remain
155 production /10 classifier classes with no workload/probe/factory leakage;
production jdeps remains java.base. Final Python guards pass **86**, with five
explicit Linux process tests skipped on macOS. Documentation fixtures pass 42;
both strict URL builds pass 94 pages. Desktop light, 390 px mobile dark/table
containment and search→ADR navigation pass; normal light/default viewport restored.

**Review:** independent review found and resolved cleanup bypass on process
enumeration failure, Maven RC/debug/base injection, unbounded Git capture,
clamped elapsed evidence, atomic-move replacement ambiguity and final deadline
admission. Dedicated regressions cover these observable boundaries. Final review
has no remaining correctness finding. Actual Linux process tests and supervised
worker smoke remain required before completing this step.

**Fresh research:** all fourteen Central sources checked November 3 at
**13:48:16 UTC /08:48:16 EST**, official compatibility at the same second;
Linux primary accounting/process specifications checked 13:49:49–13:50:57 UTC.
Every selected pin remains latest stable compatible: Maven 3.10.0, Wrapper 3.3.4,
clean/resources 3.5.0, compiler 3.16.0, Surefire 3.6.1, jar 3.5.1,
install/deploy 3.2.0, JUnit 6.1.3, Jackson 3.2.4 and AWS SDK 2.57.8.
No version/POM change. Metadata SHA256
`b3ed168e5dd4ad124102905bbd9e1a15f2438a3509956536a6972278fcfd8bc6`;
compatibility `6a78ac81d9d39327e8a46d4412ee4f9a828de3811e37d4cd6ed98b871b577c78`;
Linux primary evidence `7e2558c16b05f4b8b622f01841d458e09fa8f5f0fa1a77fb868102608400be7d`.
Guide links checked primary proc/stat/signal references and explains non-atomic
process accounting and shared allocated-block attribution. Ignored
`.local-qa/mdb-0085/` retains research, test logs, parser summary and screenshots.

**Delivery checkpoint:** normal source push will invoke exact-source Java/Linux,
docs and automatic Pages checks. Keep MDB-0085 active, MDB-0082/0012 expanded and
MDB-0083 queued until actual Linux smoke and preceding delivery pass. Source commit
uses `Step-ID: MDB-0085` /`Step-Result: checkpoint`; no dependent implementation
starts. Next action is inspect Linux evidence, resolve any actual failure and
audit MDB-0082 before completion.


## 2026-11-03 — MDB-0085 Linux acceptance and runner integration

**Delivered source:** checkpoint `3c2cd06890b55ff1fcb9d583cec3c406404e7aee` is
verified on origin/main with matching clean local/remote tips. Exact-source
[Java 41653893887](https://github.com/MeridianDB/meridiandb/actions/runs/41653893887),
[docs 41653893965](https://github.com/MeridianDB/meridiandb/actions/runs/41653893965)
and [Pages 41653894847](https://github.com/MeridianDB/meridiandb/actions/runs/41653894847)
all pass on their first attempt. No retry-masked success.

**Actual Linux qualification:** run `d5dd72f148204e32923131857ff1855c` passes all
**1,347 cases /74 suites**, zero failures/errors/skips, exact reviewed inventory
`b07fbb6592703dad09a37dd7e0b6cd5c12f3931c29430042e61df62dc846012b`, clean
source and complete run/root/runtime/capability binding. All 86 Python guards pass
with zero Linux skips, including timeout, zero/nonzero exit, flooded output,
TERM-ignoring child after successful leader exit, signal restoration and cleanup
despite process-enumeration failure. Both CI platforms pass 762 optional invocations
and fourteen examples; Windows retains 374 core /381 optional capability skips.

Placement: kernel 7.0.0-1017-azure, ext4, x86_64, Eclipse Adoptium Java 25.0.5,
runtime 25.0.5+8-LTS. Recognized flags: commit=30, data=writeback, discard,
errors=remount-ro, nobarrier, relatime, rw; one option is omitted. Actual probe reports
directory/file force, file key, shared/exclusive locks, atomic move and moved-file
identity. Backing device/controller/cache and block sizes remain unknown. This is
hosted correctness evidence; productionQualified:false and MDB-0080 gaps remain.

**Actual supervised worker:** run `b002d8985eac4495ae577c35d7ff9dcc`, clean
checkpoint source, CORRECTIONS seed 42, warmups 0, repetitions 1, 256 MiB heap, four
reported processors. Clean compilation and worker complete under one 300-second
deadline; actual pre-report elapsed 7.642978 seconds, no observed exhaustion.
Worker PID 11513/startTicks 14047 exits 0, group settled before result/file admission.
Source content fingerprint:
`5fc02ef96c2baffb15b09ccfcc4f378f5ae1985a7dbd1e44a7b532be80ea4272`
(369 files /4,776,092 bytes); classpath fingerprint:
`ed01c34cc8b34b564c40398796d751256b4ecd8a5520a997e26a26848fc95910`
(445 files /3,181,518 bytes). Both remain unchanged through admission.

Corpus v1 fingerprint
`d773c131a39ef3427e152eecfbd2ae5e6ff67080edfd5eacbb8e13a683ce21a5`;
history `4cd56d634a2bc44076417e4959e8ef06a1d5524bd17c0e9c0716bffa4f4d6d46`,
encoded journal 10,306 bytes. Actual counts: 16 entities, 128 new commits, 13 retries,
64 reads (GET/SCAN/SELECT/FILTER_SCAN 13 each, SUM 12), 49 historical/15 head reads.
Final ordinary-file inventory: 15 files, 32,898 logical bytes, 69,632 attributed
allocated bytes. Complete-iteration process deltas: rchar 811,085, wchar 32,898,
syscr 3,465, syscw 141, read_bytes 0, write_bytes 593,920, cancelled_write_bytes 0.
Process-write bytes /encoded-journal bytes = 57.6285658839511; read ratio 0.
These include validation/maintenance/runtime/instrumentation and are not device
amplification, cold reads, stable-media completion or reclaimed-space evidence.
Raw worker JSON 46,758 bytes, SHA256
`db90e9409d9e93ddb9f65b4e40dddab3e73da442d80653a4efa7dded21149da9`.
The raw artifact retains samples; the compact validated CI summary preserves these
provenance/accounting facts after artifact expiry. No timings are promoted to a
controlled or comparative baseline.

**Retained artifacts:** same source/run, fourteen-day retention:

| Artifact | ID | Bytes | Expiry UTC | SHA256 |
| --- | --- | --- | --- | --- |
| linux-storage-evidence | 13239380380 | 206,493 | November 17, 14:28:31 | `98d6ad498b2ac9686c308e11c3d62364bf37b7791ccf5c44e48faffd7619add4` |
| linux-workload-evidence | 13238154587 | 12,024 | November 17, 14:29:49 | `e76652c2c18469870b878a2f6950e2aaf4433127da8f26b93c771624631fa01b` |

**MDB-0082 original acceptance audit:** both required children and integrated
runner now pass; the parent can close without closing baseline evaluation.

| Original criterion | Evidence and scope |
| --- | --- |
| Prepared inputs, setup/load/warmup/reset | Oracle/corpus preparation outside call clocks; fresh stores for every warmup/repeat; exact original requests and answers |
| Writes, queries, recovery, checkpoint, compaction | Forced commits/retries, all five read kinds, journal/checkpoint writable reopen, 8 original segments, generation 2 then 3 and one pinned old reader |
| Counts, latency/rates, resource/check overhead | Raw per-phase/kind samples and recomputed nearest-rank summaries; service versus loop wall and validation/other intervals; optional thread CPU/allocation and sampled heap with unavailable gaps |
| Source/profile/result/storage/I/O | Fresh clean compile and unchanged fingerprints, strict v1 worker binding, actual Linux runtime/mount, exact logical journal/files, st_blocks attribution and explicitly named process-byte ratios |
| Arrival, lag, readers, backlog and cache | Synchronous closed-loop service; no arrival queue/asynchronous lag/merge backlog measurement; exactly one pinned reader; uncontrolled OS cache, no eviction/forced GC or hidden acknowledgement change |
| Bounded execution and failure evidence | One deadline, bounded logs/heap/retention, settled private groups, partial/stale/malformed refusal, unsupported distinct from ordinary failure, preserved interruption/export/collision evidence |
| Repeatable command and verification | Published command/ADR 43/callers/rules, 86 Linux guards, 12 worker tests plus prior 26 metric/runner cases, exact-source supervised smoke and full regression qualification |

MDB-0083 still owns actual baseline evaluation, integrated failure/result review,
all original MDB-0012 criteria and explained bottlenecks/next experiments. Keep
MDB-0012 expanded. A correctness-only smoke supplies no controlled performance or
production-device qualification; no privileged cache operation or paid environment
is authorized by this receipt.

**Website readback:** fifteen live HTTPS pages pass updated-content and production
canonical checks; both new search titles and 94 unique canonical sitemap URLs pass.
Published guide/browser shows the exact command, scratch/source rule and accounting
limits; screenshot `site-published.png` is embedded in commentary. Earlier desktop
light, 390 px mobile dark/table containment and search→ADR checks pass. Normal
light/default viewport restored; local server 8792 stopped with exit 0. Both strict
URL builds and 42 docs fixtures pass in CI; no internal narrative is published.

This completion changes only queue/card/history. Source and public documentation
remain identical to the remotely verified checkpoint. Validate 85 IDs/cards/DAG/
eligibility, inspect the staged diff, commit with Step-ID MDB-0085 /Step-Result
completed and push normally. Verify source/completion ancestry, matching tips and
clean status; internal-only changes require no duplicate CI/publication. Close
MDB-0085 and MDB-0082, make MDB-0083 ready and stop. No next implementation,
format experiment, native binding, remote provider or release starts here.

**Next recommendation:** MDB-0083 — Linux baseline evaluation and conformance.


## 2026-11-06 — MDB-0086 Configurable dimensions source checkpoint

**Scope and refinement:** resumed clean matching main/origin at
`1194a5c3ad1a91ec9e1fd03e5c6942a06402145a`. Reconfirmed exact-source
Java 41653893887/docs 41653893965/Pages 41653894847 success before new work.
Expanded MDB-0083 into required MDB-0086 independently configurable dimensions
and MDB-0087 actual Linux evaluation/integration audit. The fixed default-only
command cannot fulfill original MDB-0012 scaling criteria. Preserve all original
outcomes, every prior ID and S3/replication deferral; 87 IDs now exist. Execute only
MDB-0086; parents 0083/0012 remain expanded. No actual comparative baseline,
format/native change, cloud operation, SDK update, paid environment or release.

**Implementation:** named profiles accept explicit entities/commits/queries/width
under unchanged corpus v1 semantics, percentages and golden identities. Legacy
eight-argument worker remains v1 with its original envelope. Explicit twelve-arg
worker and normal Python command use v2 with all four exact dimensions bound in
request/result/failure/unsupported evidence; nested result/corpus versions stay 1.
Both languages enforce dimension/reference-work plus conservative aggregate
`(2*C+Q)*(warmups+repetitions)<=20000` bounds before scratch mutation or corpus
generation. Python admission derives seed/mixed/read inventories, partial history
segment groups, validation tasks and exact final files from requested inputs.
Zero/fewer-than-five reads have accurate zero operator counts and no invented
sample groups. Existing process cleanup, source/class identities, accounting
denominators, heap/log/output bounds and force acknowledgement remain intact.

**Checks:** focused WorkloadWorkerTest passes 16 cases without skips (prior 12 + 4).
Complete rooted `./mvnw -B clean install` passes **1,351 cases /74 suites**,
zero failures/errors/skips, run `2ea91ab6b6c74905be6641a4bd3e679f`, Oracle
Java 25.0.2/runtime 25.0.2+10-LTS-69, macOS aarch64. Strict XML parser checks
every run/root/runtime binding; inventory preserves prior counts and changes only
WorkerTest 12→16, SHA256
`6306733a0f8a223f15a24b5c9cc99bcb209eb208c7cd60a7b4155a38620493c3`.
Real Java cases exercise 7/33/11/3 plus 1/2/0/1 dimensions, independent historical
answers/retries, three/two source segments and closed-journal identity. Python
guards pass 105 (40 qualification, 46 evidence, 19 supervisor), with five Linux
process skips on macOS; all prior 86 remain. Sixteen new admission/evidence tests
and three supervisor tests cover schema mixing, all dimensions, requested profile
probabilities, early refusal and exact reference/action boundaries. Production
archives remain 155/10 classes with no workload/probe/factory leakage; jdeps remains
java.base. Optional adapter/examples retain prior evidence locally and execute
in exact-source CI; no local adapter claim is inferred from core tests.

**Documentation/review:** guide flags/table, reference/action limits, v1/v2
compatibility, profile overload, ADR 43, README, current counts, roadmap and agent
rules are reconciled. Both strict 94-page URL builds and 42 docs fixtures pass.
Desktop light and 390 px mobile dark inspection confirms the dimension command/table
is readable and contained; normal light/default viewport restored. Independent
cross-language integration review has no concrete finding. CI will run default
and 32/257/127/6 scaled correctness smokes, retaining separate fresh output trees.
These smokes are execution/admission evidence, not baseline evaluation.

**Fresh Maven research:** all fourteen metadata sources retrieved November 6 at
20:02:18 UTC /15:02:18 EST; official compatibility at 20:02:19 UTC /15:02:19 EST.
Twelve core/build pins remain latest stable compatible. Both optional AWS SDK
artifacts have latest stable 2.57.10, released November 5, versus selected 2.57.8.
Retain 2.57.8 because further S3 work is explicitly deferred; a scoped adapter
update owns adoption/testing. Public build guide records selected/latest/reason
and official release link. No POM or version changes. Metadata summary SHA256
`bfbd310d8fd0b919ccf070cdd71efea23a9911945c3f89c07672164cf27e3029`;
compatibility `a5ed9ae9308146ecb6f61174686bc96f082795d62f3639bf839145c1306ca87c`;
research report `d296d77cce18bf5915734f2c94fbb562220001286c6cd7c6fba05233fe54f87e`.
Ignored `.local-qa/mdb-0086/` retains exact fresh source bodies/hashes, test logs,
parser binding/summary and screenshots.

**Delivery checkpoint:** commit task-owned source/docs/planning with Step-ID
MDB-0086 /Step-Result checkpoint and push normally to origin/main. Keep MDB-0086
active until actual Linux default/scaled v2 CLI and regression qualification pass;
inspect automatic Pages delivery/live source. Next action is exact-source CI
readback and completion; no MDB-0087 evaluation starts in this invocation.


## 2026-11-06 — MDB-0086 Linux acceptance and remote delivery

**Delivered source:** checkpoint `ed0e45b6434280cd9d91f7f6749edb37a84146c2`
is verified on origin/main with matching clean tips. Exact-source
[Java 42286467497](https://github.com/MeridianDB/meridiandb/actions/runs/42286467497),
[docs 42286467525](https://github.com/MeridianDB/meridiandb/actions/runs/42286467525)
and [Pages 42286467496](https://github.com/MeridianDB/meridiandb/actions/runs/42286467496)
all pass on their first attempt, without retry-masked acceptance.

Linux qualification run `a4308f0a1129487b8c888c8732df72f1` passes all
**1,351 cases /74 suites**, zero failures/errors/skips and exact source/run/root/
runtime/inventory binding. Inventory SHA256
`6306733a0f8a223f15a24b5c9cc99bcb209eb208c7cd60a7b4155a38620493c3`.
All 105 Python guards pass with zero Linux skips. Both platforms pass 762 optional
invocations and fourteen examples; Windows retains 374 core /381 optional capability
skips. Local focused 16/full 1,351 runs, parser/archives/jdeps and independent review
are recorded in the source checkpoint; no further source fixes were needed.

**Actual default and scaled CLI:** both use schema 2 request/report/envelope,
inner result/corpus 1, CORRECTIONS seed 42, warmups 0/repetitions 1, explicit 256 MiB
heap and separate fresh output trees. Both verify clean compile, unchanged source/
class fingerprints, exact metrics/request/history/file counts and settled worker
groups before admission. Both return checks_passed with no observed deadline
exhaustion. Hosted placement is ext4, kernel 7.0.0-1017-azure, x86_64, Temurin
25.0.5+8-LTS, four reported processors. Recognized options: commit=30,
data=writeback, discard, errors=remount-ro, nobarrier, relatime, rw; one option is
omitted. Device/controller/cache/block sizes remain unknown; productionQualified
stays false. These are correctness/admission smokes, not controlled baseline runs.

| Field | Default v2 | Scaled v2 |
| --- | --- | --- |
| Run ID | `133738b1bbcf4f91964abdd805827f7b` | `df39c7787098473bbdddcee40a1e54e2` |
| E/C/Q/W | 16/128/64/8 | 32/257/127/6 |
| New commits /retries /reads | 128/13/64 | 257/27/127 |
| Get/Scan/Select/FilterScan/Sum | 13/13/13/13/12 | 26/26/25/25/25 |
| Original /replacement segments | 8/1 | 17/1 |
| Final files | 15 | 24 |
| Encoded journal bytes | 10,306 | 20,974 |
| Logical retained file bytes | 32,898 | 66,504 |
| Attributed allocated bytes | 69,632 | 135,168 |
| Worker export bytes | 46,984 | 84,300 |
| Pre-report elapsed seconds | 11.512110 | 12.301805 |

Default schedule fingerprint remains
`d773c131a39ef3427e152eecfbd2ae5e6ff67080edfd5eacbb8e13a683ce21a5`,
history `4cd56d634a2bc44076417e4959e8ef06a1d5524bd17c0e9c0716bffa4f4d6d46`.
Scaled schedule `9faae754d3134212b26d26f45bb5cab05caad1ca14e57277255289a8145cbbce`,
history `7cff3239489e7f24c8b0e753e20a125383c2d1d8d1582471734f261fc4d6a2f3`.
Worker result hashes: default
`802099e3bf357f62b0dec67d02c7f0b03cb7c58df490b13d50147029c9dacfbe`;
scaled `c311f46ecf723a0f24252d6bd489a2ac1800a8075bca03fc5e9c0bfb4ae07c9e`.
Source tree 369 files /4,834,989 bytes, SHA256
`26e7f258301f9dac26cde6722bf556317ad17762988f2d6155000b98149f0bae`;
classpath 446 files /3,196,684 bytes, SHA256
`7a21d38f854c70630f46fc170ec6e5f6568da135e5069f46fdf6840e23d68e6b`.
Both runs reproduce those same input fingerprints after their separate clean builds.

Complete-iteration process deltas (default /scaled): rchar 811,086/1,051,182;
wchar 32,898/66,504; syscr 3,465/6,634; syscw 141/279; read_bytes 0/0;
write_bytes 593,920/1,179,648; cancelled_write_bytes 0/0. Process write/journal
ratios 57.6285658839511/56.243348908172024, read ratios 0/0. The bracket includes
validation/maintenance/runtime/probe overhead; these are not device amplification,
cold-read, exclusive physical space or stable-media receipts. Elapsed includes
compilation/source checks; it is not an engine throughput comparison. Raw samples
remain in the artifact, with compact admission/provenance facts in CI logs.

**Retained artifacts, fourteen days:**

| Artifact | ID | Bytes | Expiry UTC | SHA256 |
| --- | --- | --- | --- | --- |
| linux-storage-evidence | 13537916981 | 206,870 | November 20, 20:14:21 | `b0fb71d6cf2756b58c4d6477156e1104d207310a6487ce3f44a3b0b5569164af` |
| linux-workload-evidence | 13537822522 | 28,591 | November 20, 20:16:26 | `8d115ac196be11d35ba41931e4d838e10a123ab0eee2dc681fe7a0cf00f5b1a2` |

**Website delivery:** both strict 94-page URL builds and 42 docs fixtures pass.
Nine affected live HTTPS pages pass content and production canonical checks;
94 unique sitemap URLs and the dimension search entry pass. Browser verifies
published controls/limits and v2 compatibility; `site-published.png` is embedded
in commentary. Desktop light, 390 px mobile dark table containment/search pass;
normal light/default viewport restored and local server 8793 stopped with exit 0.
The first ignored readback assertion inserted spaces into highlighted code tokens;
using text without artificial tag separators corrected that assertion, with no
website-source change or failed CI. Raw logs, profile, summaries, artifact metadata
and live readbacks remain in ignored `.local-qa/mdb-0086/`.

**Acceptance:** all MDB-0086 criteria pass: independent dimensions and early bounds,
legacy identities, strict v2 success/failure/unsupported binding, dynamic partial/
zero-read inventories, exact full-suite reconciliation, public commands/limits,
rules and actual Linux CLI. This receipt changes only queue/card/history and closes
MDB-0086. Keep MDB-0083/0012 expanded; all 87 IDs and original baseline criteria
remain. MDB-0087 is ready. Validate queue/DAG/eligibility, inspect staged diff,
commit Step-ID MDB-0086 /Step-Result completed, push normally and verify ancestry,
matching tips and clean status. Internal-only changes need no duplicate CI/Pages.
No evaluation, layout/native experiment, cloud change, SDK update or release starts.

**Next recommendation:** MDB-0087 — Linux baseline runs and integration audit.


## 2026-11-07 — MDB-0088 Saved workload reporting source checkpoint

**Scope:** resumed clean matching main/origin at
`28b10b55b2adf26a61c00a093ed2e02e9297bcfd`. Reconfirmed prior exact-source
Java 42286467497/docs 42286467525/Pages 42286467496 success. Expanded MDB-0087
into required MDB-0088 saved-result reports and MDB-0089 actual Linux evaluation/
integration audit; all 89 IDs and every parent criterion remain. Execute only
MDB-0088; MDB-0087/0083/0012 stay expanded. No actual baseline, format/native
experiment, remote backend, SDK update, paid environment or library release.

**Implementation:** factor pure `validate_recorded_worker` out of live validation.
Recompute exact request/schema/context/paths, raw summaries/rates/counter availability,
clock equations, declared file inventory and process I/O from supplied recorded
Linux context without filesystem/platform calls. The live wrapper retains native
topology/device/inode/block/journal-hash checks and its existing accounting shape.
Shared file-declaration checks avoid inconsistent live/recorded rules.

New `scripts/storage/summarize_workload.py` reads three bounded ordinary files,
binds request/report/worker bytes and selected source/run, verifies exact recorded
source/class/command/heap/process/deadline context and recomputes recorded accounting.
JSON/Markdown preserve per-iteration distributions and separate warmup/measured
lists; unavailable CPU/allocation stay unavailable with sample counts. Provenance
includes dirty/tree/class/corpus/history/input hashes, unknown mount/device context
and precise metric units. Recorded observations neither requalify originals nor
authenticate a producer. Input is 64 KiB request plus 16 MiB report/worker each;
output 4 MiB. Reads refuse nonregular/symlink/raced inputs without blocking on a FIFO.

**Checks:** all 135 Python guards pass on macOS (40 qualification,19 supervisor,
57 evidence,19 reporter), with five explicit Linux process skips. Prior 105 checks
remain; added 11 pure recorded checks and 19 reporter cases. Tests cover absent
original paths/current-host differences, mixed/stale-selected/partial/failed evidence,
raw/rehashed metric tampering, typed/bounded bindings, file/accounting deltas,
zero/scaled/warmup distributions, literal metadata, read limits and CLI output caps.
An initial test incorrectly banned literals inside safe JSON fences; that test
expectation was corrected before final acceptance. Independent review found and
resolved omitted Markdown provenance/clocks, unrelated mount placement, impossible
recorded elapsed duration and backslash/pipe rendering. Final review has no finding.

Java, POM, production sources and inventory are byte-identical to the verified prior
step: 1,351 cases /74 suites, inventory
`6306733a0f8a223f15a24b5c9cc99bcb209eb208c7cd60a7b4155a38620493c3`.
No new local Maven run or optional adapter/example run is claimed; exact-source CI
will run them. Both strict 96-page URL builds and 42 docs fixtures pass. Guide/ADR 44,
README/callers/navigation/rules explain recorded versus live evidence. Desktop light,
390 px mobile dark table containment and search→ADR→guide checks pass; normal
light/default viewport restored. Ignored `.local-qa/mdb-0088/` retains checks/images.

**Actual saved Linux evidence:** downloaded artifact 13537822522 from
Java 42286467497 using the GitHub artifact tool. Verified ZIP bytes 28,591 and SHA256
`8d115ac196be11d35ba41931e4d838e10a123ab0eee2dc681fe7a0cf00f5b1a2`
against its published metadata before bounded safe extraction. Both recorded runs
from source `ed0e45b6434280cd9d91f7f6749edb37a84146c2` pass on macOS without
their original `/home/runner/...` database paths. Default run
`133738b1bbcf4f91964abdd805827f7b`: JSON 14,671 bytes SHA256
`8ab95c5c3655d1ccc23ccb5d82159ed0bcae23061f4f8306657c8740ae116b63`,
Markdown 6,638 bytes SHA256
`13a74e542362749c9756b1a3343b448409d2839c84c05fb676e5308f32d4b26e`.
Scaled run `df39c7787098473bbdddcee40a1e54e2`: JSON 14,967 bytes SHA256
`8809b992165a76b6c23a190772d374fa9511b75421031dfd60dd74685d547820`,
Markdown 6,652 bytes SHA256
`9adcd7ff90d544da938096497a037f5b0ba2650e1c74fa56df55655312d2db43`.
These are checked saved observations, not fresh Linux execution or baseline results.
CI now retains two fresh source-bound analyses outside producer-owned trees,
including default warmup 1/measured 2 and scaled warmup 0/measured 1.

**Fresh research:** all fourteen Central sources checked November 7,
12:53:51 UTC /07:53:51 EST; official compatibility checked in the same second.
Twelve non-AWS pins remain latest stable compatible. Both optional AWS artifacts
selected 2.57.8 now have latest stable 2.57.11, released November 6; public build
guide records availability and deliberate retention under the deferred S3 scope.
No dependency version change. Metadata summary SHA256
`97b9d72fc618e96370d6c3067bf3d24c33f9c487a815fa968f4037333af4bd0b`;
compatibility `8843ce886696f1fa18039357c6ba8d84aa03000bfb631f2eb6840d58f0b48185`;
research report `604d0bd9c0b3f89ecdd97e13aba0b96d23df5bab7a97aa423081819344d3b039`.
Exact primary URLs, response bodies/check times and hashes remain under ignored
research/. SDK support still distinguishes Java 8 baseline from project-tested 25.

**Delivery checkpoint:** validate 89 IDs/cards/DAG/eligibility, inspect staged diff,
commit task-owned source/docs/planning with Step-ID MDB-0088 /Step-Result checkpoint
and push normally to origin/main. Keep MDB-0088 active until exact-source Linux
live+saved report checks, regression qualification and automatic Pages/live readback
pass. No dependent MDB-0089 evaluation starts in this invocation.


## 2026-11-07 — MDB-0088 Linux reporting and delivery acceptance

**Delivered source:** `232be154f88f542d94c062829a86031860df8696` was normally
pushed to origin/main and remote ancestry/tip equality verified. Exact-source
[Java checks 42376268105](https://github.com/MeridianDB/meridiandb/actions/runs/42376268105),
[documentation checks 42376268110](https://github.com/MeridianDB/meridiandb/actions/runs/42376268110)
and [automatic Pages 42376268104](https://github.com/MeridianDB/meridiandb/actions/runs/42376268104)
all succeed. No source repair was required after this checkpoint.

**Regression:** Linux runs all 135 Python guards with zero skips in 6.053 seconds.
The unchanged core inventory passes 1,351 cases /74 suites with zero skips,
failures or errors; inventory SHA256 remains
`6306733a0f8a223f15a24b5c9cc99bcb209eb208c7cd60a7b4155a38620493c3`.
Qualification run `38e281306ef342bdaf81d363707b9c64` binds this source on Linux
7.0.0-1017-azure x86_64, ext4 and Eclipse Adoptium 25.0.5+8-LTS.
Windows runs 1,351 core cases with 374 capability skips. Optional loopback adapter
regression runs 762 invocations per platform (Linux zero skips, Windows 381);
all fourteen examples per platform pass. These are preserved regression checks,
not additional S3 implementation. No Java/POM/dependency/inventory change occurred.

**Fresh report acceptance:** Linux's saved-report step succeeds for exactly two
source-bound observations. Both use CORRECTIONS/seed 42 and a 256 MiB heap.
Default E16/C128/Q64/W8 run `10c1699278dd4fd0b098d0f08d98a364` has warmup 1
and measured 2; scaled E32/C257/Q127/W6 run
`5445ebe4e4b64041aad2a3e91e2f1825` has warmup 0 and measured 1. The clean source
tree is 373 files /4,931,612 bytes, SHA256
`dbf5df8f9a9748b2ec8de1da4729f54f54a3e0d47e4a2038b1f2eae3f45dcb5c`;
compiled classpath 446 files /3,196,684 bytes, SHA256
`7a21d38f854c70630f46fc170ec6e5f6568da135e5069f46fdf6840e23d68e6b`.
All build/worker exits and settled-group flags pass; no deadline exhaustion.

Downloaded workload artifact through the GitHub connector, verified the exact
49,347-byte ZIP against its published SHA256 before bounded extraction of twelve
ordinary files. Both saved analyses reproduce byte-for-byte on macOS without
original Linux database paths. Every input byte count/hash matches, every
iteration summary matches the recomputed worker distribution, and warmups remain
separate from measured iterations. JSON default: 32,682 bytes, SHA256
`e4cde3e5daed05ef366c59a43436fc90a051612c79436688f8ef93dcd61a98d9`;
scaled: 14,948 bytes, SHA256
`9f852c86735c0e559aca4ac43cb26caa5cfe5a85810fde3341b2888cae06f54b`.
Markdown default: 12,166 bytes, SHA256
`7062f7c08f6be36763d0084ed949e138a56a692368a7f9e6a5e98378f5ea068f`;
scaled: 6,638 bytes, SHA256
`929e3ac25279861770cdf9f3493b6cd9a830fd6054de8a681f6248c49615e0a3`.
Raw input hashes and complete metric observations remain in ignored
`.local-qa/mdb-0088/ci-analysis-readback.json` and the retained artifact.

| Artifact | ID | ZIP bytes | Expiry UTC | SHA256 |
| --- | --- | --- | --- | --- |
| linux-storage-evidence | 13575698231 | 206,674 | November 21, 13:18:12 | `4aad1e4f730aae338255e00be7c57ff7fa99a1a57ce92e4d729daeae72d2f0cb` |
| linux-workload-evidence | 13576160350 | 49,347 | November 21, 13:19:37 | `eeba7662a33122871970fcfc85f1b26bd9397a960ab211cdea95c6e943f507d2` |

**Interpretation:** these runs verify reporting and live admission. Hosted ext4
mount options include data=writeback, nobarrier and commit=30, with one omitted
option. Backing device/controller, cache and block details remain unknown. No
controlled baseline, device durability, power-loss, throughput superiority or
production qualification is inferred. Recorded consistency does not authenticate
the producer or reobserve its files/processes/hardware. Actual evaluation remains
MDB-0089; ancestors MDB-0087/0083/0012 stay expanded.

**Website:** both strict 96-page URL builds and 42 docs fixtures pass. Seven
affected live HTTPS pages pass expected content and production canonical checks;
96 unique sitemap URLs and both new search entries pass. Browser inspection of
the published guide passes; `site-published.png` is embedded in commentary.
Desktop light/mobile dark/navigation/search checks from the source receipt remain
valid, normal light/default viewport is restored and local server 8794 stopped
with exit 0. One ignored readback assertion expected a command name absent from
the contributing page; it was corrected to the actual saved-report link. No
website source defect or CI failure resulted.

**Completion:** all MDB-0088 criteria pass. This internal-only completion changes
queue/card/history, marks MDB-0088 done and MDB-0089 ready, and keeps all 89 IDs
and parent criteria. Validate the queue/DAG/eligibility and staged diff; commit
Step-ID MDB-0088 /Step-Result completed, push normally and verify remote ancestry,
matching tips and clean status. No duplicate CI/Pages is required for these
internal records. No evaluation, layout/native work, cloud change or release starts.

**Next recommendation:** MDB-0089 — Linux evaluation runs and baseline acceptance.


## 2026-11-07 — MDB-0089 Evaluation acquisition checkpoint

**Scope:** clean matching main/origin at
`d65d62488dc6b33f7967705021e319a9156fa8ec`; previous MDB-0088 source
`232be154f88f542d94c062829a86031860df8696` and its successful exact-source CI
remain authoritative. Claim MDB-0089; keep 0012/0083/0087 expanded and all 89 IDs.
Independent helpers review measurement/integration scope and refresh versions;
primary coordinator owns queue/source/Git. No engine, format, native, S3 or release change.

**Protocol:** add a ten-row TSV and a separate push-triggered Linux observations
workflow. Run sequentially on one hosted placement, each fresh JVM/256 MiB with
warmup 1/measured 3 and separate stores. Four fixed-dimension profile cases, four
one-dimension-at-a-time contrasts, a seed sensitivity case and an identical final
control retain raw request/report/worker/log evidence and checked analyses.
Use existing admission, forced acknowledgement, historical/retry checks and
90-second per-supervisor bounds; job ceiling twenty minutes, ninety-day artifact.
Stop on a failed case and retain available failure evidence. The public protocol
and agent rule distinguish observed service/resources from device/cold-cache/
arrival/production qualification. No result or parent completion is claimed yet.

**Fresh Maven research:** fourteen Central and sixteen official sources checked
November 7 at 13:23:41 UTC /08:23:41 EST. All twelve non-AWS latest stable pins
remain selected; optional AWS latest 2.57.11 versus retained 2.57.8 under S3 deferral.
No dependency/POM update. Research report SHA256
`26e183aee255aea1ac316ee46d807f6da3897fe1910a7e832e5809cf539a18b2`;
metadata `04bf57f8e4cfe6e0ed8213f02c30ebbaa88d4905b29c01de2e508a104246e889`;
compatibility `30abe8e1500b5166bf9b84156b3985dc4c525fec9b09d2317cd4ecb7c1c64043`;
manifest `8a507dbf81bb7a65ef0cbf11a10a2020a8c6df55d5ac92aff173deb1fe5e66d3`.
Ignored `.local-qa/mdb-0089/research/` retains raw bodies, request times and URLs.

**Checks:** all ten cases pass existing dimension/reference/action admission;
independent-contrast, profile, seed/control, final-newline and YAML/shell syntax
checks pass. All 89 queue IDs/cards/dependencies/eligibility pass. Strict 97-page
production-URL documentation generation and source/rendered/link/search/sitemap QA
pass. No Java/POM source changed; full exact-source regression follows in CI.

**Delivery:** commit a Step-ID MDB-0089 /Step-Result checkpoint and normal push. Obtain actual
Linux observations, preserve durable checked raw inputs, evaluate metrics and audit
every original criterion before completion. The current item remains active.


## 2026-11-07 — MDB-0089 Observations, durable evidence and integration audit

**Acquisition:** protocol source `fa0a26df9455e1d30f4c5bdb873a22436d52514f`
was normally pushed and matching remote ancestry/tips verified. Dedicated
[Linux observations 42377033037](https://github.com/MeridianDB/meridiandb/actions/runs/42377033037)
passes all ten sequential cases, each with warmup 1/measured 3: forty fresh stores,
thirty measured iterations and 6,656 original commits across all iterations.
The four profile baselines, four independent dimension contrasts, seed 43 and
identical final seed-42 control all pass live and saved-result admission.

Every case uses the same clean source tree (376 files /4,950,141 bytes, SHA256
`501641fdf0f1cff12efdb420167039f1bd59ad0115537bc2cfa15883d5c3a629`),
compiled classpath (446 files /3,196,684 bytes, SHA256
`7a21d38f854c70630f46fc170ec6e5f6568da135e5069f46fdf6840e23d68e6b`),
Java 25.0.5+8-LTS/Temurin, Linux 7.0.0-1017-azure x86_64/ext4, four processors,
256 MiB heap and uncontrolled cache. Device/controller/cache/block facts remain
unknown; mount includes data=writeback, nobarrier, commit=30 and one omitted option.
These facts do not qualify a production placement or controlled device comparison.

**Durable evidence:** downloaded artifact 13576033241, 401,169 bytes, SHA256
`76c23534b79f3aa09117bee94dae6883e8f11333a6fe82f82f8f8e1cef5e4880`,
expires February 5, 2027 at 13:27:20 UTC. Verified ZIP digest/size before bounded
safe extraction. Ten reports reproduce CI JSON byte-for-byte on macOS without
original Linux paths. Retain immutable `evidence/linux-observations/2026-11-07/`:
fifty exact request/report/worker/log gzip files totaling 311,945 bytes, zero gzip
timestamps, manifest with original/compressed lengths/SHA256, identities/requests/
fingerprints and analysis SHA256, plus exercised decompression/report commands.
Replaying all fifty retained files verifies both hash layers and reproduces all
ten source/run-selected analysis hashes. The repeated control matches both corpus
fingerprints; changing reads alone preserves the original history fingerprint.

**Findings:** independent review verifies every one of 33 public result rows
against raw summaries/selectors and rounding, with three repetitions kept separate.
History 128→512 increases mixed-commit allocation about 3.1× (16.7 kB→51.8–52.1 kB
per call) and checkpoint-reopen allocation about 11.25× (2.3→25.9 MiB). These are
scoped allocation observations, not proof that one algorithm caused them. Growing
history/request-map copying and complete-state recovery are a source-backed
hypothesis for later profiling. Identical control mixed-commit p50 falls 16.8–19.3%
by paired repetition: one warmup does not establish steady state or host stability.
More reads change the loop numerator; observed operations/s is not ingestion-only
throughput. All thirty measured process read_bytes deltas are zero while character
reads are nonzero; this does not establish zero engine reads or cold storage behavior.

**Audit and planning:** [the original-criteria audit](baseline-audit.md) maps every
MDB-0012/0083/0087/0089 requirement to evidence and precise limits. Original
operation-attributed read/write amplification remains unmet: whole-process counters
include validation/runtime/maintenance and final lengths cannot establish repeated
I/O. Admit required MDB-0090 under MDB-0083 for actual completed file-work bytes,
force counts and recomputable named denominators in separate attributed runs.
Keep all original criteria and all 90 stable IDs; MDB-0083/0012 stay expanded.
After final delivery, MDB-0089/0087 can close and MDB-0090 becomes ready. Device,
cold-cache, arrival and production gaps remain MDB-0080. No attribution or layout/
native implementation is started here. Agent rules now preserve immutable dated
technical datasets and forbid treating process ratios as attributed amplification.

**Regression and docs:** exact-source Java 42377033009 passes all 1,351 core cases
/74 suites and 135 Python guards with zero Linux skips/failures. Qualification
run `ed47c1af7eee4bfea2c0c8e9517eff0e` preserves inventory SHA256
`6306733a0f8a223f15a24b5c9cc99bcb209eb208c7cd60a7b4155a38620493c3`.
Windows retains 374 core skips; optional regression has 762 invocations/platform
(Linux zero skips, Windows 381), and fourteen examples/platform pass. Java/POM/
inventory are unchanged. Documentation 42377033252 and Pages 42377033061 pass for
the protocol source. Final results introduce no runtime code; both local strict
97-page URL builds, 42 docs fixtures, all 90 queue/card/DAG checks and diff checks
pass. Desktop light and 390 px mobile dark table scrolling/containment pass, with
375 px page width and six independent scroll containers. Search finds the guide,
navigation points to it, and normal light/default viewport is restored. An initial
theme-radio automation did not toggle the custom input; clicking its visible label
resolved it. An ignored draft table selector selected LOAD instead of MIXED commits;
it was corrected before appending public results and all final rows were verified.

**Delivery:** commit/push results, raw evidence, audit, rules and handoff with
Step-ID MDB-0089 /Step-Result checkpoint. Keep MDB-0089 active until the final
public results pass exact-source docs/automatic Pages and live readback. Then
record completion and next MDB-0090, preserving expanded MDB-0083/0012.


## 2026-11-07 — MDB-0089 Publication and completion acceptance

**Delivered results:** `f65ccbd692876b858dbbcdeab30510dd0bc7d699` reached
origin/main with normal push, ancestry and matching-tip verification. Exact-source
[docs 42377997373](https://github.com/MeridianDB/meridiandb/actions/runs/42377997373)
and [automatic Pages 42377997336](https://github.com/MeridianDB/meridiandb/actions/runs/42377997336)
both succeed. Seven affected HTTPS pages pass expected final content and canonical
checks; 97 unique sitemap URLs and new numerical result search entries pass.
Published browser inspection verifies actual resource tables and findings;
`site-published.png` is embedded in commentary. The first readback during pending
deployment correctly saw the older protocol; after successful deployment every
final assertion passed without a source change. A screenshot during the theme's
header transition was recaptured after navigation settled. Local server 8795
stopped with exit 0; browser remains normal light/default viewport.

**Integrity and regression:** public remote manifest readback confirms ten cases
and fifty files at the committed results revision. Manifest SHA256
`c47f0254bf740b0897067a99de56f2d6d3f660c878a33b088d23fc73b3d46788`.
Durable hashes, exact analyses, all 33 numerical result rows, strict builds and
focused documentation checks are recorded above. Git diff confirms no Java,
POM, storage-script or observation-workflow byte change from the successful
acquisition/regression source `fa0a26df9455e1d30f4c5bdb873a22436d52514f`.
Reuse its actual Linux 1,351-case /74-suite, 135-guard, optional/example and
ten-observation CI evidence rather than claiming another Maven run.

**Acceptance:** MDB-0089 satisfies its bounded observations/evaluation/audit outcome
and MDB-0087 has both required children complete. Close MDB-0089/0087; keep
MDB-0083/0012 expanded for admitted required MDB-0090's operation-attributed file
work and amplification. Make MDB-0090 ready without starting it. Preserve original
parent criteria, all ninety stable IDs and separately owned MDB-0080 production
gaps. The current queue checkpoint is refreshed from obsolete historical smoke/
version/page-count summaries; prior detailed evidence remains in progress history.
Update the roadmap and original-criteria audit consistently. No attribution,
layout/native, cloud, release or paid operation is performed.

**Final delivery:** validate queue/DAG/eligibility and staged diff; commit these
internal records with Step-ID MDB-0089 /Step-Result completed and push normally.
Verify both source ancestors, matching remote tip and clean status. No duplicate
CI or Pages build is required for internal-only completion records.

**Next recommendation:** MDB-0090 — Operation-attributed file-work accounting.


## 2026-11-07 — MDB-0091 Internal local I/O counter source checkpoint

**Scope and plan:** resumed clean matching main/origin at
`6926bab4ef2d2a7c4a38480bc306a85546e3a866`. Expanded MDB-0090 without changing
its criteria into required MDB-0091 native collection, MDB-0092 attributed workload/
schema/report integration and MDB-0093 actual Linux attributed evaluation/audit.
All 93 stable IDs remain. Primary coordinator owns source/queue/Git; independent
helpers completed version research, boundary review and two explicitly owned test
files, returning ownership before builds. Keep 0090/0083/0012 expanded. No attributed
baseline result, layout/native accelerator, cloud continuation or release starts.

**Implementation:** add internal `LocalIo`, package-private capture activation and
eight fixed roles ×fourteen scalar counters. Track read/write attempts, returns,
positive bytes, zero/EOF/failures and force attempts/successes/failures. Throwing
calls retain known earlier completions but have unknown additional effects; hooks
rethrow original checked/runtime/Error objects. Saturate at the counter limit and
invalidate telemetry instead of throwing from storage. Unknown roles/invalid
returns invalidate telemetry. Immutable schema-one snapshots follow owner close.

Captures reject same-thread overlap, wrong-thread close/snapshot and process
capacity above 64. A volatile immutable bounded owner registry has allocation-free
lookup, including uncaptured threads while another capture exists. Review found
that an initial ThreadLocal lookup could allocate a null binding on uncaptured
threads; replaced it before verification. Registry setup/teardown/snapshots stay
outside storage calls. Native hooks contain no callback, path lookup, log or
growing event buffer; normal inactive calls delegate directly after empty lookup.

Wire concrete `FileChannel` sites exactly once: journal positional I/O/force,
tail-evidence relative output/force, standalone segments, checkpoint namespace
segments and pending/published manifests, actual default directory-force probes/
later directory forces, reader-control force and the JDK-only local object adapter.
Owner roles persist while each call resolves its current capture. Existing file
ownership, handles, flags, prefix/integrity work and acknowledgement ordering stay
unchanged. Metadata/locks/open/close/rename/delete/truncate, external hashing/oracle/
export and cloud/injected non-native seams are outside byte coverage, explicitly.

**Focused and full verification:** all 28 new tests pass (17 deterministic
short/zero/EOF/failure/overflow/role/lifetime/64-thread cases and 11 actual-native
create/commit/retry/query/reopen/checkpoint/compaction/tail/object cases).
Independent literals include header 24, frame 54, segment 158 and genesis manifest
136 bytes. Create observes journal 96 read/24 write with three journal forces,
manifest 136 read/write and one force, one control and five directory forces.
Heap queries/exact retries observe zero file work; a new/no-op commit writes its
54-byte frame and forces once. Repeated integrity/recovery passes remain visible.

Full `clean verify` on rooted macOS Oracle Java 25.0.2 passes **1,379 tests /76
suites**, zero failures/errors/skips, run `00d56947efd74c8b9af1094bb6dca793`.
Reconcile fresh XML against every existing suite/count and current source files;
only LocalIoTest17 and LocalIoIntegrationTest11 are added. New inventory SHA256
`c119f1332553d25d24a6d1ffa758afe8a72333d168cbeaba599b1b19d53f735d`,
prior `6306733a0f8a223f15a24b5c9cc99bcb209eb208c7cd60a7b4155a38620493c3`.
Shared report-binding parser verifies exact fresh root/run/runtime. Core install
after that successful run and separate optional loopback `verify` pass 762 cases,
zero skips/failures/errors. No SDK/provider account or version change. Core JAR 160
classes, classifier 10; workload/probe/factory/new tests absent from shipped archives
and observer absent from classifier. `jdeps -s` reports only java.base; javap shows
capture activation package-private. An initial unsupported jdeps long option was
corrected to its supported short option before reporting dependency verification.

All 135 Python guards pass locally (five Linux-specific process skips); all 42 docs
fixtures and both strict 99-page URL builds pass. Guide/ADR0045, README/build/count
callers, roadmap and agent rules match scope and pending attribution. Independent
final source/docs review finds no issue. Browser verifies desktop light, 390 px
mobile dark (343 px table/375 px container/page, no viewport overflow), search
guide+ADR, decision links and normal light/default viewport restoration. A search
snapshot initially preceded async results and a heading locator used an unsupported
level filter; fresh results and an explicit h1 locator verified the actual pages.
Ignored `.local-qa/mdb-0091/` retains logs, fresh XML/bindings and screenshots.

**Fresh research:** fourteen Central and sixteen official sources checked November 7,
13:45:55 UTC /08:45:55 EST. Twelve non-AWS pins remain latest stable compatible;
AWS 2.57.11 latest versus selected 2.57.8 stays deferred. POM unchanged. Research
report SHA256 `2493c22e42eefa03aa4eeaf3f58b9e492c4eb947c97f19fcdc22f03ba5418cee`;
metadata `b683092159d4b21858fedaec40d312567d6ef5baa941ade1665f22ffc1658d4d`;
compatibility `1aaa65b213d9989682999e115404c0c1fe24e423a4641846cd9cd5ff2e96dcb0`;
manifest `fdebce827cd919fddb8824540f64c624bc7357981249a5ad8c08387e5916d49d`.

**Delivery:** commit task-owned code/tests/docs/rules/planning/inventory with
Step-ID MDB-0091 /Step-Result checkpoint, push normally and verify remote. Keep
MDB-0091 active until exact-source Linux suite/native/guard/workload regression,
Windows/basic adapter/example checks and automatic Pages/live readback pass.
MDB-0092 remains queued; no dependent implementation starts here.


## 2026-11-07 — MDB-0091 CI, publication and completion acceptance

**Delivered source:** normal push of
`f8b8cf4fd62a7d10845d4592781fbdd14c0813c5` reached origin/main; fetch, ancestry
and matching-tip verification pass. Exact-source
[Java 42380298307](https://github.com/MeridianDB/meridiandb/actions/runs/42380298307)
succeeds on both platforms. Linux job 128426319245 passes all **1,379 core cases
/76 suites** with zero failures/errors/skips, including LocalIoTest17 and
LocalIoIntegrationTest11. Qualification run `651c219e605e4443be0638972aa57e11`
binds clean source, fresh root/runtime and inventory SHA256
`c119f1332553d25d24a6d1ffa758afe8a72333d168cbeaba599b1b19d53f735d`.
Observed ext4/kernel 7.0.0-1017-azure/x86_64 and Temurin 25.0.5+8-LTS retain
unknown device/cache/controller and production/power-loss gaps. All 135 Python
guards pass on Linux with zero skips. Optional loopback regression passes all
762 invocations with zero skips. Windows job 128426319419 passes 1,379 core
invocations with 380 capability skips and 762 optional invocations with 381 skips;
all 17 counter unit tests run, six native lifecycle tests skip for explicit
unsupported directory-sync capability. Fourteen examples pass on each platform.

**Workload regression:** unchanged schema-two default and scaled Linux smoke
complete successfully with settled worker groups and saved-report verification.
Default run `085dab94aeb54e2cabed0b308bcf933c` uses E16/C128/Q64/W8,
warmup1/measured2; scaled run `147c838c4386460982c659f9999b9240` uses
E32/C257/Q127/W6, warmup0/measured1. Worker results are respectively 134,830 bytes /
SHA256 `5b02fc0335971b297c0ebfc65a238c9883cffb26117763fc38e4a73322c0a23b`
and 84,067 bytes /
`18e1b9595acff8c0fab606c43c8822ccce779817db6af1296cff81ded287be42`.
These are correctness/regression smoke, without counter activation or attributed
performance claims. Original MDB-0089 observations remain immutable.

Temporary CI artifacts: `linux-storage-evidence` 13577636441, 212,598 bytes,
SHA256 `dac3bb1e5743d54ae2bd35dd2692a8ac7b319706f790664d2371de8e40404213`,
expires November 21, 2026 14:10:48 UTC; `linux-workload-evidence` 13576964897,
49,422 bytes, SHA256
`ebc4fbff7754786cb54514255aa91ce7a75acca21d18b5cfe66de01b946a1763`,
expires November 21, 2026 14:12:12 UTC. Ignored `.local-qa/mdb-0091/` retains
decoded job logs, parsed profile and local verification evidence. These artifacts
do not replace the separate durable evaluated-data requirement of MDB-0093.

**Documentation delivery:** exact-source
[docs 42380298321](https://github.com/MeridianDB/meridiandb/actions/runs/42380298321)
passes both canonical URL builds;
[automatic Pages 42380298316](https://github.com/MeridianDB/meridiandb/actions/runs/42380298316)
passes build 128426319080 and deploy 128426423780. Nine affected HTTPS pages pass
expected content/canonical checks; 99 unique sitemap URLs and new guide/ADR search
entries pass. The project URL returns the same guide with status 200 through its
existing redirect to `http://meridiandb.org/development/local-io-counters/`, with
the correct HTTPS canonical; direct production HTTPS checks pass. An initial
extra check assumed an HTTPS final redirect URL; corrected it to record the actual
redirect/content/canonical rather than misreporting a deployment failure. Hosting
settings were not changed. Published browser inspection verifies scope, metric
definitions and pending attribution. `site-published.png` is embedded in commentary.
Local server 8796 stops successfully; normal light/default viewport is restored.

**Completion and handoff:** mark MDB-0091 done, remove its active claim and prepare
MDB-0092 as ready, preserving all 93 IDs and queued MDB-0093. Update the original
baseline audit without crediting operation-attributed amplification. MDB-0090,
MDB-0083 and MDB-0012 stay expanded. Validate queue/cards/DAG and task-owned diff;
commit this internal completion receipt with Step-ID MDB-0091 /Step-Result
completed, push normally and verify remote ancestry/tips. No engine/POM/public
source changes follow the verified checkpoint; no repeat deployment is expected
from this internal-only receipt. Next: **MDB-0092 — Attributed workload and result
integration**, after completion delivery. No dependent implementation starts here.


## 2026-11-07 — MDB-0094 Attributed local lifecycle source checkpoint

**Selection and refinement:** continue resumes clean matching main/origin at
`d166575eaa553588f0544bd897c4d9cdb93c1030`. MDB-0092 spans independently useful
Java lifecycle attribution and serialized worker/supervisor/saved-reader work.
Expand it without removing criteria into required MDB-0094 and MDB-0095, retaining
MDB-0093 actual evaluated Linux acceptance. All 95 stable IDs remain; only
MDB-0094 is active. Primary coordinator owns runner/trace/docs/planning/Git;
helpers own the new test-only bridge and native integration test, plus independent
research/review, returning ownership before builds. No serialized mode, new ratio
report, layout/native accelerator, S3 continuation or release starts here.

**Implementation:** add test-only `LocalIoObservation` immutable fixed-shape
exports and strict counter-coherence/completeness checks. Add ordered
`LocalWorkloadIo` operation and auxiliary scopes around the existing checked
`LocalWorkloadRunner` lifecycle. Default execution activates no capture and keeps
schema one. Explicit `LocalAttributedWorkload.run` returns separate mode
`CALLER_THREAD_LOCAL_IO_INSTRUMENTED`, original lifecycle metrics, raw scopes,
logical denominators and precise gaps. Capture setup/snapshot/recording surround
Recorder.measure, outside service clocks; native updates remain inside calls.

Scopes distinguish preparation, namespace, operation, maintenance owner/prefix/
reader acquisition, validation, external hash, file inventory and teardown.
Actual directory-name forces use the native hook; engine integrity work remains
inside its operation and lazy validation reads remain outside it. External raw
hashing and metadata inventory carry excluded coverage, not false zero-I/O claims.
No production source, POM, codec, format, acknowledgement or reclamation changes.

Complete successful trace shape is two preparation scopes plus
`(2*actions + 2*sourceSegments + 32)` per iteration; preflight reserves another
32 slots per iteration, with maximum 2,048 across the run. Reject oversized,
insufficient or reused traces before namespace effects. Denominators derive
original header and seed/mixed non-retry encoded frames before storage, with
original commit/retry/read counts and exact history consistency. Named ratios,
source binding and serialized recomputation remain MDB-0095; evaluated evidence
and ancestor completion remain MDB-0093.

**Review and failure corrections:** independent review caught teardown capture
setup failing before its close callback, post-acquisition wrapper/attempt-array
allocation, supplied-trace preflight gaps, denominator count/header mismatches and
wrong-thread fallback relabeling earlier evidence. Correct all before final
verification. Fallback closes only when native close was never attempted; it
never retries a throwing native close. Wrong-thread/reentrant preflight mutates
neither owners nor prior scopes. Observation failure after acquisition closes the
returned owner and suppresses cleanup failures; missing/incomplete raw evidence
is retained with call/observation outcomes. Final independent review is clear.
An initial compile found the exception's nonserializable diagnostic list under
Werror; mark it transient, explicitly retaining scope diagnostics in-process.
No Java-exception serialization is used as a raw-evidence export contract.

**Verification:** focused final run passes 43 cases: bridge9, trace/fault12,
attributed lifecycle13 and original runner9. Tests use independent four-commit
literals (header42, frames78, full history354, source segments278 each, replacement
434) and exact native operation/auxiliary byte/force vectors. Heap queries,
exact retries and pure planning have zero adapter work. Exact conservative scope
admission accepts 2,048 and refuses the next configuration before mutation.
Four scaled profiles, zero-query cases, fresh warmup/repetitions, original history,
immutability, partial/failure/unknown/overflow and actual post-create owner cleanup
pass. Existing native counter tests remain unchanged.

Rooted full `clean verify` passes **1,413 invocations /79 suites**, zero failures,
errors or skips on macOS/Oracle Java 25.0.2+10-LTS-69/aarch64, run
`d64be43bcf62419486357fa6b4ff29e7`, elapsed 2:19. Fresh XML is reconciled against
all prior suite counts and source files; only three new suites/34 invocations are
added. Inventory SHA256
`a7fa250e9aa2f561c26a22f55c02c7d98813779888393e49059ad290048592e0`;
prior `c119f1332553d25d24a6d1ffa758afe8a72333d168cbeaba599b1b19d53f735d`.
Shared qualification parser verifies the fresh run/root/runtime binding; this
macOS run is wiring evidence, not Linux qualification. Core remains 160 classes
and jdeps reports only java.base; attached classifier remains ten classes, with
new observation/workload/qualification helpers excluded. Optional SDK/core
production code is unchanged; exact-source CI still verifies the optional adapter.

All 135 Python guards pass locally (five Linux-only skips), and 42 docs fixtures
pass. Both strict 101-page production/project URL builds pass, with source/HTML/
links/search/sitemap QA. Guide/ADR0046, README/build/count callers, roadmap,
counter-guide/ADR0045 and agent rules agree on implemented versus pending scope.
Desktop light and 390 px mobile dark rendering pass: page375, article343,
table400.64 within a375 px overflow-auto scroll wrapper, no page overflow.
Search returns the new guide; guide-to-ADR navigation works. A generic dialog
selector matched two search containers and the input is a combobox, not textbox;
observed open-dialog/input selectors verify actual results. Restore normal
light/default viewport. Ignored `.local-qa/mdb-0092/` retains logs, rooted XML,
runtime bindings and screenshots; no helper remains a writer/build owner.

**Fresh Maven research:** fourteen Central and sixteen official sources checked
November 7 at 14:18:56 UTC /09:18:56 EST. Twelve non-AWS pins remain latest stable
compatible; optional AWS selected2.57.8/latest2.57.11 stays deferred. POM unchanged.
Report SHA256 `c9b71e69b7443ddc92526a7a86aa492292ce88dbc9e2a144dfef3372dcc436a6`;
metadata `c74a86e84efbde608aea7cb3a046bc167ed5f9b8df25453099e9a9890edb6ad3`;
compatibility `b30acab6b9ecb07e9c3644fe8308f59f1e64d9b0ee5c044df01fbfbf1ce87738`;
manifest `880142deb043f132d2a9fbd0e66d30551e7ad087ba891f456707145108009bad`.

**Delivery:** validate task-owned staged diff and all queue/card/DAG references;
commit code/tests/docs/rules/inventory/planning with Step-ID MDB-0094 /
Step-Result checkpoint. Push normally, verify ancestry/tips and inspect exact-source
Linux/Windows/native/guard/workload/optional/example regression plus docs/automatic
Pages/live readback. Keep MDB-0094 active until acceptance, then record completion
and ready MDB-0095. MDB-0092/0090/0083/0012 remain expanded. No dependent
implementation starts in this invocation.


## 2026-11-07 — MDB-0094 CI, publication and completion acceptance

**Delivered source:** `91ae1bc4acedc86708cc7663814260b607ac06ed` reached
origin/main through normal push, with fetch, matching tips and ancestry verified.
Exact-source [Java 42382641654](https://github.com/MeridianDB/meridiandb/actions/runs/42382641654)
passes both platforms. Linux job 128433325332 verifies **1,413 core invocations
/79 suites** with zero failures/errors/skips. Qualification run
`98cabc09356445419ff5789446b70fbf` binds clean source, fresh root/runtime and inventory
SHA256 `a7fa250e9aa2f561c26a22f55c02c7d98813779888393e49059ad290048592e0`.
The three new suites run all bridge9, trace/fault12 and native lifecycle13 cases.
Observed ext4/kernel7.0.0-1017-azure/x86_64/Temurin25.0.5+8-LTS preserves recorded
mount options and unknown device/cache/controller/production/power-loss limits.
All 135 Python guards pass on Linux without skips. Optional loopback regression
passes 762 invocations with zero skips. Windows job 128433325111 passes 1,413 core
invocations with 394 capability skips and 762 optional invocations with 381 skips:
new bridge9 all run, trace12 skips only its actual directory-sync-dependent case,
and native lifecycle13 explicitly skip unsupported directory synchronization.
Fourteen examples pass on each platform.

**Legacy workload regression:** default run `05c22e1818eb40c7806816e459f8a79a`
(E16/C128/Q64/W8, warmup1/measured2) and scaled run
`029b2f38b0bc4f82824668991d723b37` (E32/C257/Q127/W6, warmup0/measured1)
both pass exact-source admission, native lifecycle checks, settled worker cleanup
and saved-report verification. Worker bytes/hashes respectively:
135,004 / `32a89e4768307b8e1a49ef5b50210ae89652de133f475cdcc695aae67d7e7bc3`
and 84,204 / `fd65a658ab90eb9f8bb30915414ea981543d3c4e313aef2d8ff4b51c0c17249a`.
These smoke runs use the unchanged ordinary schema-two supervisor/worker path,
not a new attributed serialized mode. Original evaluated observations remain
immutable; MDB-0095/0093 retain serialized admission/evaluation requirements.

Temporary CI artifacts: `linux-storage-evidence`13578366602, 221,052 bytes,
SHA256 `d275eb03fb29de61ca0a8cce38827896720e223bb128c4823e5cf8eefb539256`,
expires November 21, 2026 14:40:58 UTC; `linux-workload-evidence`13577939450,
49,496 bytes, SHA256
`2d527536e9d1116ff9fe218589bacdabaf09f2a97edd5dd629288ee3a32739fb`,
expires November 21, 2026 14:42:45 UTC. Ignored `.local-qa/mdb-0092/` retains
decoded logs/profile and local rooted test/readback evidence. Temporary regression
artifacts do not replace future durable evaluated attributed data.

**Documentation delivery:** exact-source
[docs42382641640](https://github.com/MeridianDB/meridiandb/actions/runs/42382641640)
passes both production/project URL builds. Automatic
[Pages42382641651](https://github.com/MeridianDB/meridiandb/actions/runs/42382641651)
passes build128433324943 and deploy128433438374. Eleven affected HTTPS pages pass
content and canonical assertions; 101 unique sitemap URLs and new guide/ADR search
entries pass. Published guide bytes60,780, SHA256
`e7d9f00007cff0c13b233b589e69cbe44e56664907ba1c6a90cd9ce515a2c223`.
Project URL returns the same guide through the existing redirect to
`http://meridiandb.org/development/attributed-local-workloads/`, status200 with
correct HTTPS canonical; direct production HTTPS checks pass. Hosting settings
are unchanged. Published browser verifies actual scope/status copy and rendering;
`site-published.png` is embedded in commentary. Preview server8797 stops with
exit0; normal light/default viewport is restored.

**Completion:** mark MDB-0094 done, remove its active claim and prepare MDB-0095
ready, preserving all 95 IDs and queued MDB-0093. Reconcile roadmap/current queue
and baseline audit without granting amplification/baseline completion. MDB-0092,
MDB-0090, MDB-0083 and MDB-0012 stay expanded. Validate queue/cards/DAG and
task-owned staged diff; commit internal completion with Step-ID MDB-0094 /
Step-Result completed, push normally and verify remote ancestry/tips. No runtime,
public docs, POM or inventory changes follow the exact-source acceptance, so this
internal-only receipt triggers no repeat CI/Pages build. Next: **MDB-0095 —
Attributed serialized evidence integration**, after completion delivery. No
dependent implementation starts here.


## 2026-11-07 — MDB-0095 Attributed serialized evidence source checkpoint

**Selection:** resume clean matching main/origin at
`57382ba86cf5940bd6b8974f18b8b2bac17586bf`, with prior completion ancestry and
delivery reconciled. MDB-0095 is the only active executable item; preserve all
95 IDs and original criteria. Primary coordinator owns Python integration,
reporting, docs, queue and Git; helpers own Java worker/observer/test changes,
the new pure Python validator/tests, and independent research/review. All helper
write/build ownership returns before coordinator builds. Keep actual evaluation,
durable evaluated data and original baseline acceptance with MDB-0093; no layout,
native binding, S3 continuation or release starts here.

**Worker and live integration:** add explicit prefix `--attributed` to twelve
dimensioned worker arguments, and supervisor `--attributed`, selecting schema three
plus `CALLER_THREAD_LOCAL_IO_INSTRUMENTED` on request, worker and receipt. Preserve
exact legacy8/schema1 and dimensioned12/schema2 shapes. Reuse the checked Java
lifecycle with the real process-I/O observer outside capture scopes. Success
exports the unchanged inner lifecycle result plus typed scopes/denominators/gaps
and original header/frame hex. Original non-retry sequences/length/hash are checked
before storage; failed/unsupported outcomes retain partial scopes and never a
successful result. Existing bounded export, complete/create-only publication,
source/class stability, native file graph/hash and process cleanup remain intact.

Exact retry PRNG admission runs before paths/effects in worker and supervisor:
48-bit Java Random seed/salt, signed32 nextInt rejection and extra original-request
draws. The Python side does not trust producer action counts to select its budget.
Default CORRECTIONS42 warmup1/measured3 reserves 1,962 scopes and fits; the admitted
APPEND42 E1/C100/Q879/W1 boundary reserves 2,048 and emits 2,016 successful scopes.

**Independent raw checking/reporting:** new pure `attributed_workload.py` decodes
v1 header and every original frame, checks both CRC32C boundaries, original request
sequence/entity/finite-window/count bindings, original-operation metadata and full
SHA256/length. Source-derived header/seed/mixed lengths establish denominators;
neither final sizes nor observed counters supply those logical lengths. Require
the exact operation and auxiliary ordering, fixed eight-role/fourteen-counter
shape, typed nonnegative/coherent returns/bytes/forces, complete outcomes and
signed-long sums. Reject missing/unknown/failed/overflow and mode/version mixing.

Derive per-iteration named matrices, operation groups/totals and exact ratio pairs.
New commits use original seed/mixed frame bytes; retry/query denominators are zero
with N/A; named lifecycle/recovery ratios use full encoded original history.
Preparation and auxiliary counts remain separate. External hash/inventory carry
excluded coverage; export, process telemetry and supervisor validation have
explicit unavailable counters. Saved reader independently recomputes attribution,
compares cached producer totals/ratios and binds exact worker bytes. Attributed
analysis has schema two plus explicit mode; ordinary analysis stays schema one.
It opens no original paths/commands and supplies no producer authentication.
Markdown keeps compact per-iteration rows and N/A, not every raw counter cell.

**Tests and bounds:** focused Java worker run passes 25 cases, including nine new
mode/source-vector/failure/default/max/native/export cases. Full rooted clean
verify passes **1,422 cases /80 suites**, zero skips/failures/errors on
macOS/Oracle25.0.2+10-LTS-69/aarch64, run `bfcfafd5e30843bc8d2f51cf5f9890c3`,
elapsed2:23. Reconcile fresh XML against every prior suite/count and source file;
only AttributedWorkloadWorkerTest9 is added. New inventory SHA256
`dc6edb3b4ce83e2f74f577f3037f92c2b8287c89da803d32e7cf4d05d5191fb9`;
prior `a7fa250e9aa2f561c26a22f55c02c7d98813779888393e49059ad290048592e0`.
Shared parser verifies fresh run/root/runtime; macOS evidence is not Linux
qualification. Core JAR remains160 classes and jdeps only java.base; classifier
ten with new developer helpers excluded. Production source/POM unchanged.

All **183 Python guards** pass locally (five Linux-only skips), including 38
independent CRC/history/schedule/scope/counter/ratio cases and ten integration
cases. Corrupted/rebound worker hashes, wrong modes/commands, altered cached
reports, partial failure and no-original-host readback are exercised. One initial
assertion expected literal underscores in escaped Markdown; check decoded display
text and rerun successfully. Maximum serialized fixtures exercise 500,000-node/
16MiB input and 4MiB output caps with 2,048-scope admission, without changing limits.
Frozen tiny/max fixtures under scripts/storage/tests/fixtures are actual native
counter serialization with synthetic recorded Linux paths/PID/context, explicitly
not qualification/performance data. Tiny:39,832 raw/4,772 gzip bytes, 52 scopes,
198 history bytes; max:1,222,369 raw/26,768 gzip bytes, 2,016 scopes, 8,026 history
bytes. README retains exact original/gzip hashes and provenance distinction.

CI adds real attributed CORRECTIONS warmup1/measured2 and max APPEND E1/C100/Q879/W1
supervised execution alongside both ordinary cases, then requires four checked
saved reports/two explicit modes, warmup/measured and boundary readback. Existing
source bindings and retention remain. Independent source/CI review finds no
remaining issue; actual Linux execution is still pending source delivery.

**Documentation/rules:** add evidence guide/ADR0047, reconcile prior guides/ADRs,
README/build/test-count callers, public roadmap/navigation and agent rules.
Independent editorial review catches and fixes a generic “New command runs…”
schema-two caller to “Ordinary command runs…”. Forty-two docs fixtures and both
strict103-page URL builds pass, including source/HTML/links/search/sitemap checks.
Desktop light and390px mobile dark table containment pass:375px page/wrappers,
343px admission table and464.31px ratio table within overflow-auto wrapper.
Search returns22 results including guide/ADR; decision navigation works. Restore
normal light/default viewport. Ignored .local-qa/mdb-0095 retains rooted reports,
research, logs, native fixture acquisition and screenshots.

**Fresh Maven research:** fourteen Central and sixteen official sources checked
November7 at14:47:13UTC/09:47:13EST; twelve non-AWS pins remain latest stable
compatible. AWS selected2.57.8/latest2.57.11 stays deferred; POM unchanged.
ReportSHA256 `06b061da714772352c104e075876dbc7bcc6ac998302f7ab472d36124b942a00`;
metadata `edfb0efbb4c84892898f7f7e2c8640f7a57d41886dfb0f04cc4326163527a7b2`;
compatibility `6e2d7708a204a83087464082f1285139920ee184894500c9cb01c35740a47222`;
manifest `731090578814fc3d09fbdff617755089cb039c07b4c172881c0b57eed7a77bb7`.

**Delivery:** inspect task-owned staged diff and queue/card/DAG, commit code/tests/
fixtures/docs/rules/inventory/planning with Step-ID MDB-0095 /Step-Result checkpoint,
push normally and verify remote ancestry/tips. Keep MDB-0095 active until actual
exact-source Linux/Windows core/optional/examples,183guards, four supervised
workload/readback cases and docs/automatic Pages/live acceptance pass. Then audit
and close MDB-0095/0092, preparing MDB-0093. Keep MDB-0090/0083/0012 expanded;
no actual evaluated attributed dataset or downstream implementation starts here.


## 2026-11-07 — MDB-0095 CI, publication and parent integration acceptance

**Delivered source:** `62803041ee581f12fd4cce170821b2c715f80a0b` reaches
origin/main by normal push, with fetch, ancestry and matching tips verified.
Exact-source [Java 42385846700](https://github.com/MeridianDB/meridiandb/actions/runs/42385846700)
passes both platforms. Linux job 128442929100 verifies **1,422 core cases /80
suites**, zero failures/errors/skips, including all nine new worker cases.
Qualification run `5e9d21866b984a63a7f35c17a31c5409` binds clean source, fresh root,
Temurin 25.0.5+8-LTS/amd64 and inventory SHA256
`dc6edb3b4ce83e2f74f577f3037f92c2b8287c89da803d32e7cf4d05d5191fb9`.
The ext4/kernel 7.0.0-1017-azure/x86_64 profile retains its observed mount options
and unknown device/cache/controller limits. All **183 Python guards** pass on
Linux without skips (10.517 seconds); 762 optional loopback cases pass without skips.
Windows job 128442928960 passes 1,422 core cases with 394 capability skips and
762 optional cases with 381 skips. All nine new worker cases run, asserting unsupported
native outcomes where appropriate. Fourteen examples pass on each platform.

**Actual supervised regression:** all four runs bind the exact clean source,
unchanged source/class fingerprints, native files and settled process cleanup.
All four saved-report readbacks pass; the job verifies two ordinary/two explicit
attributed analyses, warmup/measured separation and maximum admitted dimensions.

| Run | Configuration | Worker bytes | Worker SHA256 |
| --- | --- | ---: | --- |
| `09eaa19295104365a6591e0f330c5bf5` | Ordinary schema2 CORRECTIONS42 E16/C128/Q64/W8, warmup1/measured2 | 135,050 | `3b209253f67ee497531baf5bb14bd51dfaa5d7c7dcd35ec651252c17b44e012d` |
| `ae4070b90f734688b9e604aca8e2f737` | Ordinary schema2 CORRECTIONS42 E32/C257/Q127/W6, warmup0/measured1 | 84,190 | `c86334f307fca0cda0de1885d00f9a726c229e1fe8fb4d5589ed0918d1473646` |
| `9cd65225601a493db70c8b5c8d0b73c0` | Attributed schema3 CORRECTIONS42 E16/C128/Q64/W8, warmup1/measured2 | 861,914 | `b608763f81272f0fb46fd0ff4ab4ba97bafe2d3b1cf19e72e662d912d59107e1` |
| `410ced2b8c1d431389d9ccc9c01f9b3d` | Attributed schema3 APPEND42 E1/C100/Q879/W1, warmup0/measured1 | 1,222,047 | `7b9d6cda8f3f26c0ae5a81aa4f0da61fa754e9903b99a5a74d27896ea8114679` |

Both attributed runs use `CALLER_THREAD_LOCAL_IO_INSTRUMENTED`. Independent
source-history recomputation yields header 42/seed 1,274/mixed 8,990/full 10,306 bytes
for CORRECTIONS (128 originals, 13 retries, 64 queries), and header 42/seed 78/
mixed 7,906/full 8,026 for max APPEND (100 originals, 5 retries, 879 queries).
The max case reserves 2,048 scopes and emits 2,016 successful scopes within existing
parser/output limits. Raw counters and named ratios pass independent admission
and cached-summary comparison. These are bounded CI regression runs; preserve
the original evaluated observations without comparing instrumented timings.

Temporary artifacts: `linux-storage-evidence` 13579053618, 223,370 bytes, SHA256
`78cddacd137dcef6924e33301afa92bd934e74cb82ff4d0ba2c9849e78fb0a9a`,
expires November 21, 2026 15:20:53 UTC; `linux-workload-evidence` 13579008803,
167,175 bytes, SHA256
`85d085c048018469766b087cc91932e9333c3a68f610d019f145b9fc98a5a89a`,
expires November 21, 2026 15:22:50 UTC. Ignored `.local-qa/mdb-0095/` retains decoded
logs/profile, local rooted verification, fixture provenance and screenshots.
Temporary artifacts do not replace MDB-0093's durable evaluated raw data.

**Documentation delivery:** exact-source
[docs 42385846698](https://github.com/MeridianDB/meridiandb/actions/runs/42385846698)
passes both production/project URL builds. Automatic
[Pages 42385846676](https://github.com/MeridianDB/meridiandb/actions/runs/42385846676)
passes build 128442928640/deploy 128443016162. Fifteen affected HTTPS pages pass
content/canonical assertions; 103 unique sitemap URLs and guide/ADR 0047 search
entries pass. Published evidence guide: 63,130 bytes, SHA256
`4b24200c691f2bf9a5870b50a3836209b5a066b68964725d2071455cef118c46`.
The project URL returns the same guide through the existing redirect to
`http://meridiandb.org/development/attributed-workload-evidence/`, status 200 with
correct HTTPS canonical; direct production HTTPS checks pass. Hosting unchanged.
Published browser inspection verifies actual content/rendering; the screenshot is
embedded in commentary. Preview 8798 stops with exit 0; normal light/default viewport
is restored. Forty-two docs fixtures and both strict 103-page builds pass locally.

**Original MDB-0092 criteria audit:** required child MDB-0094 supplies shared native
lifecycle scopes, bounded recording and cleanup, original logical denominators
and explicit auxiliary/excluded work. MDB-0095 supplies versioned source/runtime/
configuration/operation/counter identity across worker/supervisor/saved reader,
independent byte/CRC/hash/ratio checks, strict incomplete/unknown/failed/overflow
refusal, zero-denominator N/A, bounded maximum parsing/export, failure provenance
and actual Linux smoke/readback. Native and independent fault/schema/ratio tests,
docs/rules/inventory and publication acceptance pass. All original integration
criteria are met; evaluated profiles and original baseline acceptance remain
MDB-0093. No production, power-loss, controlled-device or speed-gain claim follows.

**Completion and handoff:** mark MDB-0095 and parent MDB-0092 done, clear active
ownership and prepare **MDB-0093 — Linux attributed work and baseline acceptance**
ready, preserving all 95 IDs. Reconcile roadmap/current queue/baseline audit;
MDB-0090/0083/0012 stay expanded with the original amplification criterion unmet
pending actual evaluated data/audit. Validate queue/cards/DAG and task-owned staged
diff; commit only four internal planning files with Step-ID MDB-0095 /Step-Result
completed, normal-push and verify source/completion ancestry, matching tips and
clean status. Git/final handoff supplies the actual completion SHA/push result.
No runtime/public/POM/inventory changes follow exact-source acceptance; current
path filters require no repeat CI/Pages build. No MDB-0093 implementation starts.


## 2026-11-07 — MDB-0093 Attributed Linux evaluation protocol checkpoint

**Selection:** clean matching main/origin at completion
`141166148f5fc6786b3dc07af4730c95effaf7d6`; prior exact-source Java/docs/Pages
acceptance remains successful. Claim MDB-0093 as the only active executable item,
with all 95 IDs preserved. Root owns evaluation/docs/queue/Git; independent helpers
review protocol/original criteria and refresh versions without source/build writes.
No engine, format, acknowledgement, native acceleration or deferred S3 change.

**Protocol:** separate `scripts/storage/attributed-evaluation.tsv` and
`.github/workflows/linux-attributed-evaluation.yml` retain the original ordinary
TSV/workflow/dataset unchanged. Ten ordered cases use fresh JVMs/256 MiB, uniform
warmup1/measured2 and fresh stores: base E16/C128/Q64/W8 for CORRECTIONS/APPEND/
RETRACTIONS/HOT_ENTITY, one-axis C192/E64/Q128/W16, seed43 and a final identical
control. Exact reservations are 1,424–1,934, under unchanged 2,048 cap. All-case
preflight precedes execution; each supervisor has90 seconds including compilation
and the job20 minutes. Stop on failure, retain raw/partial/log/report evidence
90 days and require ten source-bound explicit attributed saved analyses.
This is30 stores/10warmups/20measured/4,032original commits if all cases succeed.
Two measured repetitions support deterministic byte-repeatability checks, not
stable latency tails. An independent review finds this bounded alternative useful
and no additional original baseline prerequisite apparent if actual evidence passes.

**Local validation:** workflow YAML and all shell blocks pass syntax checks; the
exact embedded Python preflight verifies every plan row, profile contrast, one-axis
variant, seed and repeated control. An initial local preflight lacked `python` on
PATH; rerun using the existing venv PATH succeeds (the hosted workflow sets up
Python explicitly). All183 Python guards pass with five Linux-only macOS skips;
42 docs fixtures and strict104-page production build pass. No Java behavior/POM/
inventory changes require a new local core run; native evaluation will cleanly
compile the unchanged Java source. Public protocol/navigation, evidence-guide
caller and agent baseline-evaluation rule are reconciled. Queue/card/DAG checks
pass with MDB-0093 active; no dependent implementation starts.

**Fresh Maven research:** fourteen Central and sixteen official responses checked
November7 at15:31:01UTC/10:31:01EST, before evaluation compilation. All twelve
core/build selections remain current stable; AWS selected2.57.8/latest2.57.11
stays deferred. POM unchanged. Ignored `.local-qa/mdb-0093/research/` retains exact
response bytes/checks. SHA256: report
`f52f1d0ea8b037a79d0dab6f8dc8e8464697a9138329a1db44d812c4c92c46c3`,
metadata `076cd21354546de1b256693c321edd122a6d6b71e0caacc6e83123eb54998055`,
compatibility `89e4c49ef74776d7a9acb96c2abe30f92ca9bcbc9f3977891fdadebfe867c50d`,
manifest `a0f6b125b74813df8413c4b59abe025070b95aaeedd0117e4654a3f12be95fad`.

**Delivery:** commit this protocol with Step-ID MDB-0093 /Step-Result checkpoint,
normal-push and verify remote ancestry/tips, triggering actual sequential native
evaluation plus applicable source checks and automatic Pages. Then acquire exact
artifacts, independently recheck them without original paths, preserve durable
immutable raw inputs/hashes, evaluate file work and audit all original criteria.
MDB-0093 stays active; MDB-0090/0083/0012 remain expanded. No performance result
or ancestor completion is claimed by this checkpoint.


## 2026-11-07 — MDB-0093 Actual Linux evidence and baseline audit checkpoint

**Native acquisition:** protocol source
`b79a0366fca194dd56f326cd3f0952a31598a958` reaches origin/main through normal
push/fetch/matching tips. [Linux evaluation42387103000](https://github.com/MeridianDB/meridiandb/actions/runs/42387103000)
job128446689341 passes all ten ordered cases/source-bound saved analyses:
thirty fresh stores, ten warmups/twenty measured repetitions,4,032 original
commits. Every case shares clean source/class fingerprints and runtime/mount/
cache context. Class SHA256
`d564d6a30da747a59fdbcee0a218cc1d9af69bf338915176835715b3e021d7ea`
binds480 compiled files/3,488,969 bytes. Placement: ext4/Linux7.0.0-1017-azure/
x86_64/Temurin25.0.5+8-LTS,4CPUs,256MiB worker heap; recognized mount options
retain data=writeback/nobarrier/commit=30/discard/errors=remount-ro/relatime/rw,
one omitted option and unknown device/controller/cache/block details. No controlled
device, power-loss, cold-cache or production evidence is inferred.

**Exact-source regression:** [Java42387102889](https://github.com/MeridianDB/meridiandb/actions/runs/42387102889)
passes Linux job128446689326 and Windows128446689172. Linux qualification
`99204085beba49c0bfda574ade385b25` verifies1,422 core cases/80suites, no failures/
errors/skips, inventory SHA256
`dc6edb3b4ce83e2f74f577f3037f92c2b8287c89da803d32e7cf4d05d5191fb9`.
All183 Python guards pass without skips (11.045s). Optional762/fourteen examples
pass each platform; Windows retains394 core/381 optional capability skips.
Both ordinary and both attributed/max CI workloads plus saved readback pass.
Production/test Java, POM, inventory and evidence validators are unchanged.
Protocol docs42387102911 and automatic Pages42387102885 pass both URL builds and
publication; results will be delivered by the next checkpoint, not these runs.

**Durable evidence:** verify downloaded artifact13579983154,680,047 bytes, SHA256
`e27e16da97a293c8dd6f9c225611cdae8e7663089b64e90b340d29e197094f28`,
expires2027-02-05T15:35:14Z. Preserve exact fifty original/gzip file bindings and
source/run/request/content/history/analysis identities under immutable
`evidence/linux-attributed-observations/2026-11-07/`; compressed files total450,633
bytes with zero gzip timestamps. All ten saved JSON analyses reproduce CI bytes;
Markdown and exact README decompression/readback commands pass without original
Linux paths. Direct raw-matrix column sums independently match groups/totals.
Independent reviewer verifies every header/frame/CRC32C/sequence/SHA256 and exact
denominator,14,570 scopes (14,550 iteration+20 preparation),450 groups and all
ratio pairs. Every case's matrices match across warmup+both measured repetitions;
final control attribution matches base. Eight unchanged-input cases reproduce
both fingerprints from the earlier ordinary dataset, which remains unchanged.

**Findings:** base seed/mixed commits write exactly1,274/1,274 and8,990/8,990 original
frame bytes with one force/new commit. Heap queries/retries/pure planning perform
zero adapter work; query/retry byte ratios remain N/A. Base operation lifecycle
reads137,612/writes32,898/forces174, versus all captured read222,900/write32,898/
forces182. Auxiliary reads85,288 (38.3%) stay outside operation ratios; external
hash/inventory and export/process/supervisor coverage stay excluded/unavailable.
Checkpoint writable reopen reads3H journal+twice compacted segment+selected
manifest=51,900 versus journal reopen20,808; history192 gives78,190/31,324.
All twenty measured proc read_bytes deltas are zero despite positive adapter
reads. Repeated-control mixed-commit p50 drops18.9%/24.0% despite identical bytes;
no timing gain follows. Profile full-history reconstruction and evaluate bounded
verified-image reuse within one acquisition, preserving integrity/actual prefix/
ownership/history/acknowledgement; no optimization starts now.

**Documentation/audit:** publish protocol/results, operation/auxiliary tables,
exact metric bases, durable readback and precise limits. Reconcile README,
roadmap, prior core/counter/evidence guides, ADR0041–0047 and decision-index
current callers. Keep the earlier ordinary observations' numerical data intact
while linking the separate study. Governing baseline-audit table now reconciles
every original criterion; earlier missing-measurement decisions are explicitly
historical. Technical criteria pass; keep queue ancestors expanded until delivery.
Independent editorial/numerical/caller review finds no remaining defect.
Both strict104-page URL builds, links/search/sitemap and diff checks pass. Desktop
light and390px mobile dark results tables are readable; all four scroll wrappers
are375px overflow-auto without page overflow. Search finds the page/results and
keyboard selection navigates to the base breakdown. Restore light/default viewport.
Ignored `.local-qa/mdb-0093/` retains local readbacks, logs/profile and screenshots.

**Delivery:** commit evaluated raw evidence/docs/audit/planning with Step-ID
MDB-0093 /Step-Result checkpoint, normal-push and verify remote tips/ancestry.
Then verify exact-source docs/automatic Pages/live content and retain completion
acceptance. Only after that close MDB-0093/0090/0083/0012, prepare MDB-0013 ready
and stop. No Java/parser/workflow/evaluation-plan change follows native acquisition;
there is no new measurement or source CI prerequisite from these results-only paths.


## 2026-11-07 — MDB-0093 Publication, integrated acceptance and completion

**Results delivery:** normal-pushed source
`901d1413ce1a2e99323c2f4549fb6be8f32a8468` is verified on origin/main through
fetch/matching tips, retaining acquisition source
`b79a0366fca194dd56f326cd3f0952a31598a958` in ancestry. The latter owns actual
ten-case Linux42387103000 evaluation and exact-source Java42387102889 regression
(1,422 core cases/80 suites,183 Python guards, zero Linux skips/failures). Results
changes affect documentation/raw evidence/planning only; Java, POM, inventory,
evidence validators, acquisition workflow and TSV remain unchanged. Path filters
correctly require no duplicate Java or observation run for these results paths.

Exact-source [docs42388143691](https://github.com/MeridianDB/meridiandb/actions/runs/42388143691)
passes production job128449823847 and project-URL job128449823402. Automatic
[Pages42388143649](https://github.com/MeridianDB/meridiandb/actions/runs/42388143649)
passes build128449823508/deploy128449941572. Fifteen affected HTTPS pages pass
content/canonical checks;104 unique sitemap URLs and results search entries pass.
Published result page:73,002 bytes, SHA256
`a156a0f8df6570352a03bd3ed3b8a70aefcf16108c6a552c668710fe90b72975`.
Project URL returns the same result through the existing redirect to
`http://meridiandb.org/development/linux-attributed-observations/`,status200 with
correct HTTPS canonical; direct production HTTPS checks pass. Hosting unchanged.
Published browser verifies result tables/coverage and the screenshot is embedded
in commentary. Local8799 stops exit0; default viewport/light scheme restored.

**Original criteria and parent closure:** governing baseline-audit table passes
every original MDB-0012/0083 requirement using distinct ordinary timing/resource
and attributed-file-work datasets. MDB-0090's remaining original criterion now
has useful native contrasts, exact source-history denominators, strict raw
recomputation, explicit failed/missing/unknown/overflow/N/A and coverage boundaries,
durable evidence and public interpretation. Independent review verifies50files,
all histories/CRC/hash identities,14,570 scopes,450 groups/ratios and all ten
byte-for-byte saved analyses. All MDB-0090 children91/92/93 and MDB-0083 children
86/87/90, then MDB-0012 children81/82/83, satisfy their original integrated outcomes.
Close MDB-0093, MDB-0090, MDB-0083 and MDB-0012 without waiving criteria or
relabelling whole-process/device I/O. Keep controlled-device/cache, cold-cache,
arrival, power-loss and production qualification with MDB-0080.

**Completion and handoff:** clear the active claim, preserve95 stable IDs and
make **MDB-0013 — Measured layout and index experiments** ready, conditional on
this completion reaching remote main. Use observed recovery/full-history
reconstruction costs to refine one bounded experiment, measuring returned bytes,
allocation, latency and memory under equivalent history/integrity/actual-prefix/
ownership/acknowledgement. No optimization or deferred S3/replication work starts.
Validate queue/cards/DAG/completed children, inspect the task-owned staged diff
and commit only four internal planning files with Step-ID MDB-0093 /Step-Result
completed. Normal-push, fetch and verify source/completion ancestry, matching tips
and clean status; Git/final handoff supplies the actual completion SHA/result.
No runtime/public/evidence byte changes follow acceptance; internal-only receipt
does not trigger repeat source checks, observations or Pages publication.


## 2026-11-07 — MDB-0096 Bounded local checkpoint history source checkpoint

**Selection/refinement:** start clean matching main/origin at baseline completion
`4a05319f62619040402bf910fc94561754718917`. MDB-0013 spans prototype acquisition
and measured recovery adoption. Preserve its original criteria and split into
required MDB-0096 bounded local history acquisition and MDB-0097 integration/
evaluation/adoption or rejection. All97 stable IDs remain;96 is the sole active
item,13 expanded and97 queued. Root owns production/docs/queue/Git; helpers own
independent version/design reviews and separate unit/native test files. All helper
write/build ownership returns before coordinator verification. Keep default engine
opens, ordinary lazy readers, journal/segment formats and acknowledgement unchanged.

**Implementation:** add `LocalCheckpointHistory` with immutable original records,
selected metadata/ID, actual prefix and successful encoded Usage. Explicit Limits
cap object/journal work at64MiB each and record work at262,144. Reserve metadata+
all declared segment bytes, H and2N before the first segment fetch; exact image
caps and range-aware complete decode preserve checksums/whole-object identity,
cross-segment request uniqueness and reconstructed prefix. Collect privately;
only bounded `journal.prefix(sequence, declaredH)` plus exact actual identity
acceptance exposes the result. No unbounded fallback: new BoundedJournalAccess
extends the existing seam without changing old mocks. Shared strict-selection
helper preserves ordinary native/error order, adding one small metadata value
allocation; future control/candidate comparisons must use the same revision.

The heap result owns no channels, continuing reader barrier or reclamation
permission. Store ownership covers acquisition; later source deletion cannot alter
original immutable records. Fresh calls reselect/reverify, old lazy readers still
reread/validate. Domain/acquisition failures, including policy exhaustion, poison
the store with caller-journal ownership separate; invalid/null arguments precede
effects. Native close uncertainty propagates; no partial record prefix escapes.
Usage excludes prior journal open/later engine rebuild, is not exact heap and
separate escaped results accumulate. Independent review identifies existing1byte
EOF probes: a failed growth probe may return one extra byte beyond its admitted
image. Keep the native integrity probe, clarify encoded-image vs failure-native
bounds and add two independent growth traps rather than weakening verification.

**Independent verification:** focused new+existing-store run passes72 cases.
New suites supply27 platform-neutral fault/budget cases and11 actual local cases:
literal native acquisition reads manifest266/segments556/journal354, zero writes/
forces and Usage(822,354,8,3). Ordinary lazy verification+replay still reads1112
segment bytes. No-op/retraction/request identity, strict newest refusal, exact and
one-below limits, CRC-valid range/payload traps, actual prefix mismatch, IO/runtime/
Error cleanup, no continuing cache/protection, hardlink owner safety and actual
protected reclamation of old files pass. Failed growth probes read264+1 or202+1
then reject privately. These are correctness vectors, not measured recovery gains.

Full rooted clean verify passes **1,460 cases/82 suites**, zero failures/errors/
skips, elapsed1:52, Oracle25.0.2+10-LTS-69/aarch64/macOS. Fresh run
`09596c49e9114a548b08ad3e7a48d669` binds reports/fixtures/runtime; the shared
qualification parser verifies them. Compare every prior suite count and source
file; only HistoryTest27/HistoryIntegrationTest11 are added. Inventory SHA256
`908a58cfa1b093323356980d436892c9c13943f3e33e4e5fd46cb1402a4dd785`,
prior `dc6edb3b4ce83e2f74f577f3037f92c2b8287c89da803d32e7cf4d05d5191fb9`.
Core165 classes and classifier ten exclude all developer helpers; jdeps only
java.base. All183 final Python guards pass locally (five Linux-only skips).
Optional/Windows/native Linux acceptance remains exact-source CI, not local credit.

**Documentation/rules:** add public guide/ADR0048 and navigation, reconcile README,
local-checkpoint guide, public roadmap, decisions index, build/qualification/count
callers and persisted opt-in/budget/EOF/ownership rules. Forty-two docs fixtures
and both final strict106-page URL builds pass. Desktop limit labels were adjusted
for readable wrapping;390px mobile dark shows358px table within390px overflow-auto
wrapper, with no page overflow. Search finds guide+ADR, and decision navigation
works. Normal light/default viewport restored; local8800 preview remains available
until final publication inspection. Ignored `.local-qa/mdb-0096/` retains exact
research, native reports, logs, binding/inventory verification and screenshots.

**Fresh Maven research:**14 Central/16 official responses checked November7,
metadata15:52:38UTC/10:52:38EST and compatibility15:52:39UTC/10:52:39EST. All12
core/build selections remain latest stable; optional AWSselected2.57.8/latest2.57.11
stays deferred. POM unchanged. SHA256 report
`aedfb236dfa02da75c7513c99e56529297cbda5f20c19da58b4de216fb841cdd`,
metadata `6853dba8559ad05c167ab34d070503df5a22d5df4f8ac191e9af14a9b435df29`,
compatibility `7060dbc2831aea3814774705c4d00f1bcc98e2612963e56993cb09978d346d0c`,
manifest `230b5952e38204ed5ceeded615a776869f3c4525b5e5d573cf227e424c0ff870`.

**Delivery:** validate queue/cards/DAG, staged diff and current callers; commit
source/tests/docs/rules/inventory/planning with Step-ID MDB-0096 /Step-Result
checkpoint. Normal-push/fetch/verify matching tips and source ancestry; require
exact-source Linux/Windows core/optional/examples, all183 guards, ordinary+
attributed/max workload/readback regression and docs/automatic Pages/live checks.
Keep96 active until actual acceptance; then record completion and prepare97,
preserving expanded13 and original baseline data. No recovery integration,
optimization adoption, deferred S3 work or release starts here.


## 2026-11-07 — MDB-0096 CI, publication and completion acceptance

**Delivered source:** normal-pushed
`2ddf5cd563f45253c582a01fa9952ff370d02486` is verified on origin/main by fetch,
matching tips and predecessor ancestry. Exact-source
[Java42390449514](https://github.com/MeridianDB/meridiandb/actions/runs/42390449514)
passes both platforms. Linux job128456718282 qualifies **1,460 core cases/82
suites**, zero failures/errors/skips, including27 new platform-neutral and11
native history cases. Run `f8a2fd2a578f4c39aee60f0c9cf9782c` binds fresh root/runtime,
clean source and inventory SHA256
`908a58cfa1b093323356980d436892c9c13943f3e33e4e5fd46cb1402a4dd785`.
Observed ext4/Linux7.0.0-1017-azure/x86_64/Temurin25.0.5+8-LTS retains recorded
mount/capability facts and unknown device/controller/cache/production limits.
All183 Python guards pass without Linux skips (12.507s). Optional loopback762
and fourteen examples pass. Windows job128456718002 passes1,460 core with405
capability skips (all27 new unit cases run;11 native cases skip typed unsupported
directory force), optional762 with381 skips, and fourteen examples. No scope
parity or production claim follows from Windows regression coverage.

**Unchanged workload regression:** two ordinary and two attributed cases pass
source/class/native/process admission and saved reporting on the new revision.
Default schema2 CORRECTIONS run `3d5b30a6b35b4aaebd909574c60c6eb5`
(warmup1/measured2) exports135,221 bytes, SHA256
`df13cb38f834fb2940ca7bbe68e350f421c176b2e8663902e977b280ce39c222`;
scaled E32/C257/Q127/W6 run `70d991224d4f4d309c6fe5320516d439` exports84,277,
SHA256 `53d172fcfceb15dbac9f083a5c1339c7b466891553165f7d00be14886b4fa563`.
Attributed CORRECTIONS warmup1/measured2 `241b1833c90a4832aa5625542c94c669`
exports862,244, SHA256
`a2a910e77c475c9ffd12471fce02a88909969780b144c889869f29924b829771`;
max APPEND E1/C100/Q879/W1 `e1941b09d256426da25de86562a1b321` exports1,222,495,
SHA256 `14f7b50a6448110200ba24d3f4f79e41533fdeaa9d1fc9bd74b95d53f3dee39e`.
Four saved reports and maximum-scope bounds pass. These remain regression runs;
neither ordinary recovery nor experimental comparison selects the new loader yet.

Temporary artifacts: linux-storage-evidence13580449479,229,477 bytes, SHA256
`71078be47ccfa413bbddb9c3004cdb0897fc0357c87ddc9e8ad43229bd45308a`,
expires November21,2026 16:17:31UTC; linux-workload-evidence13581127541,
167,879 bytes, SHA256
`8499469ed63b565ad8fdd6178af8ec53106dc09416ab34609155581eee02553f`,
expires November21,2026 16:20:04UTC. Ignored `.local-qa/mdb-0096/` retains decoded
logs/profile and prior rooted local/native/visual evidence. Original evaluated
datasets remain unchanged, outside temporary regression retention.

**Documentation delivery:** exact-source
[docs42390449558](https://github.com/MeridianDB/meridiandb/actions/runs/42390449558)
passes production128456718349/project128456718695. Automatic
[Pages42390449490](https://github.com/MeridianDB/meridiandb/actions/runs/42390449490)
passes build128456718286/deploy128456826657. Ten affected live HTTPS pages pass
content/canonical checks;106 unique sitemap URLs and guide/ADR0048 search pass.
Published guide66,207 bytes, SHA256
`255ef1a98729f6875b5e521d012e45358407e8a5d64fb8e013df7665064061b5`.
Project URL reaches the guide through the existing redirect to
`http://meridiandb.org/development/local-checkpoint-history/`,status200 with correct
HTTPS canonical; direct production HTTPS checks pass. Hosting unchanged. Published
browser confirms actual API/lifetime/status copy and the screenshot is embedded in
commentary. Local8800 stops exit0; normal default viewport/light scheme restored.

**Original acceptance and handoff:** all MDB-0096 criteria pass: per-call aggregate
admission, exact image/range caps with explicit EOF-probe failure overhead,
single-pass complete verification, private original records until actual prefix,
newest/alias/ownership/close safety, immutable heap lifetime without a reader pin,
independent vectors/failures, native reclamation, full regression/inventory,
docs/rules and source delivery. Mark96 done, clear active ownership and prepare
**MDB-0097 — Recovery history reuse evaluation** ready after completion delivery.
Keep13 expanded with all original criteria and97's actual integration/evaluation/
adoption outcome; a comparison must also account for changed reader-barrier work.
No integration, default optimization, S3 work or release starts here.

Validate97 IDs/cards/DAG/completed children and task-owned staged diff, then
commit three internal planning files with Step-ID MDB-0096 /Step-Result completed,
normal-push/fetch and verify source/completion ancestry, matching tips and clean
status. Git/final handoff supplies the actual completion SHA/push outcome. No
runtime/public/inventory/evidence change follows exact-source acceptance, so
internal-only completion requires no repeat CI/Pages publication.


## November 7, 2026 — MDB-0098 source checkpoint

**Scope and refinement:** resume from clean main/origin
`7a80541e9e895c8600a82cbacf21196020f4294f`. Expand MDB-0097 into required
MDB-0098 explicit engine integration and MDB-0099 equivalent comparison/decision,
preserving all original MDB-0097/0013 acceptance criteria and original datasets.
Only0098 executes. Root owns queue/Git; helpers own independent version research,
read-only design review, reference conformance and one focused engine test file.
All return ownership before root's serialized Maven runs.

**Implementation:** explicit read-only/writable `LocalDatabase` history factories
acquire through `loadLatestHistory` and reuse shared private rebuilding/write
protocols. The acquisition helper returns before rebuild, leaving no live history
value/Optional frame. The source retains only cutoff/list and a readerless owned
local delegate; checkpoint replay clears the list in finally and close clears it
before resource cleanup. Null inputs reject before effects. Acquisition limits
exclude journal open/full replay/tail/engine heap; zero limits admit journal-only,
not selected genesis. Budget or corruption failure has no fallback. Complete
journal passes, actual-prefix verification, exclusive owners, writable journal/name
startup synchronization and forced acknowledgement remain. Ordinary opens/create,
object recovery, on-disk formats, POM and original measurements are unchanged.
Cleanup attempts include runtime/Error paths, without guaranteeing native close
success under arbitrary resource exhaustion.

**Independent checks:**17 focused cases include10 platform-neutral input/owned-source
checks and7 native histories/budgets/ownership/boundary-failure/corruption cases.
25 additional canonical cases cover the same5 histories in2 writable and3 read-only
shapes:168+252 unchanged literal expectations across explicit recovery.48 seeded
histories use16 saved seeds ×3 shapes,
32 real commits per seed/shape, finite dense cells[-3,4), exact sums, separate
reference checks for all5 operators, pinned views, all historical cutoffs/request
identities, original retries and conflicting reuse.7 added real child-JVM exits use
a nonempty checkpoint through the new factory at4 commit and3 startup boundaries,
then reopen/retry explicitly and ordinarily. These are process exits, not power loss.
All prior controls and fixture IDs remain.

Literal complete writable reopen: candidate journal1062/manifest266/segments556 =
1884 returned read bytes versus ordinary2440 with segments1112; no write bytes.
Force successes3 versus5 explicitly account for removed lazy-reader control+
directory admission; journal/name startup and store directory probe remain.
Read-only candidate has only the store directory probe force. All3 journal passes
remain. This establishes adapter work/correctness, not allocation/latency/memory
improvement or default adoption. MDB-0099 retains those decisions and compaction/
maintenance comparison; Linux production qualification remains MDB-0080.

**Fresh versions:**14 Central responses checked November7 16:23:32.869688–
16:23:33.488837 UTC (11:23 EST);16 official compatibility responses through
16:23:34.018669 UTC. Twelve core/build pins remain latest stable; Maven3.10.0,
Wrapper3.3.4, compiler3.16.0, Surefire3.6.1, JUnit6.1.3 and Jackson3.2.4 unchanged.
Optional AWS2.57.8/latest2.57.11 remains deferred. Metadata SHA256
`a695a933e781ec4878585c88db5272194aac3ca2090c5fa4f9994b8d543ec48c`,
compatibility `b99efd0158a7b7dd21ee3cb2ff54aff7440e9be68b66bd9502344bc5d82588d4`;
exact coordinates/URLs/times/response hashes are saved under ignored
`.local-qa/mdb-0098/research/`. Compatibility minima are separate from actual
project Java25 evidence.

**Local verification:** focused133/0fail/0error/0skip passes. Fresh rooted
`clean verify` run828b6757df134ff9915e001c15cad5f9 passes1,557/83suites without skips,
failures or retries on macOS/aarch64 Oracle25.0.2+10-LTS-69. Shared strict XML
readback verifies exact run/root/runtime and complete reviewed inventory. Inventory
SHA256 `dfcbac8e6f60e6bcd231a313a090960e4cc50cc643bd2c692f9ad70f9f53ea1b`
adds CheckpointHistoryRecoveryTest17; Crash11→18, DurableFixture10→68 and
RecoveryFixture15→30, reducing no existing suite. Core166 classes are JDK-only
(`jdeps` java.base); classifier10 excludes developer helpers. Core JAR SHA256
`ed1a5ccf936c90fb008f9ec7e6402fe060a2f1fd14bcfb54127c7ba8dd407abb`;
classifier `9b1692f0d5e5b1df83dd30ebbe3abb2af618b84d9bd8ecbb1d63bcdbc217c6a4`.
Python183guards pass with5 Linux-only local skips; docs42fixtures pass.

**Documentation and review:** ADR0049, acquisition/recovery/durability guides,
README/build/current-count callers, technical roadmap, decision navigation and
agent rules agree. Full source diff design review finds no material bug; list
release uses deterministic structural assertions rather than GC timing. Both
strict production/project URL builds pass after final public edits, including
107 unique sitemap URLs and new guide/ADR search entries. Desktop light/mobile
dark inspection finds no document overflow; the code example is wrapped and
mobile code/table overflow stays within its container. Search visibly returns the
new recovery guide content. Default viewport/light theme restored. Local preview
remains running for later published comparison.

**Delivery gate:** keep0098 active and0099 queued pending source normal push,
exact-source Java/Linux inventory/workload regression, docs/Pages success and
published content/canonical/search/browser checks. Parents0097/0013 stay expanded.
Commit Step-ID MDB-0098 /Step-Result checkpoint after task-owned diff/99-ID
DAG/parent checks. The subsequent internal-only receipt will record actual source
SHA, CI jobs, live acceptance and completion push. No default adoption, next
comparison, remote backend work or release starts here.

## November 7, 2026 — MDB-0098 delivery and completion acceptance

**Source delivery:** source checkpoint
`c72ecfa08709afc8c043cfa3406521d80b592414` normal-pushes from main to origin/main;
fetch verifies matching tips and clean source checkout. All following verification
is bound to that exact source; only internal planning changes follow it.

**CI acceptance:** [Java42392433169](https://github.com/MeridianDB/meridiandb/actions/runs/42392433169)
completes successfully: Linux job128462636215 and Windows128462636056. Linux
qualification run14ca48570cc743388942df0b328fb239 binds clean source to complete
reviewed inventory SHA256
`dfcbac8e6f60e6bcd231a313a090960e4cc50cc643bd2c692f9ad70f9f53ea1b`:
1,557 core cases/83 suites, zero failures/errors/skips/retries,183 Python guards
without Linux skips. All97 added cases execute. Actual scratch filesystem ext4,
kernel7.0.0-1017-azure/x86_64, Eclipse Adoptium25.0.5+8-LTS; fixture/run/runtime,
file keys/forces/locks/atomic move/identity-after-move checks pass. Device cache,
block geometry and controller remain unknown; productionQualified=false and
MDB-0080 retains deployment/power-loss qualification gaps.

Both platforms pass optional762 and fourteen Java examples. Windows reports492
core/381 optional typed capability skips, preserving basic compatibility scope.
Exact-source ordinary runs7ee87e5025884a898f43fdf307128e51 and
2083b3258c644cfaab43347600e938d9, attributed runs6a1bf72cf0dc4bf083034fcdc0d93326
and b7e5be9a7f6c4221af5826f23899bbd7 all checks_passed with no dirty source or
deadline excess; schemas2/2/3/3 preserved. Four saved reports pass independent
readback, including scaled/max input. Regression artifacts13581708711
(linux-storage-evidence,234020bytes) and13581679193
(linux-workload-evidence,168050bytes) are unexpired at capture. These are transient
regression artifacts; original immutable baseline datasets remain unchanged.

**Website acceptance:** exact-source
[docs42392433114](https://github.com/MeridianDB/meridiandb/actions/runs/42392433114)
passes project job128462635883 and production128462636039. Automatic
[Pages42392433091](https://github.com/MeridianDB/meridiandb/actions/runs/42392433091)
passes build128462635739/deploy128462741211. Twelve affected live HTTPS pages pass
content/canonical checks;107 unique sitemap URLs and577 search entries include the
new explicit API and ADR0049. Published history guide74,073bytes SHA256
`5f9ce8b7c46cec44003fc68915cba417470d90e0eb66e6c383b49dd2f092bc78`.
Project URL reaches that content through existing redirect to
`http://meridiandb.org/development/local-checkpoint-history/`,200 with correct
HTTPS canonical; direct production HTTPS checks pass. Published browser verifies
actual API/status and embeds its screenshot in commentary. Mobile document width
390 stays390; the counter table scrolls within its container. Default viewport/
light theme restored; local8800 preview stops exit0. Hosting unchanged.

**Original acceptance and handoff:** explicit factories, input/effect boundaries,
no budget fallback, journal-only/genesis/checkpoint-tail canonical+seeded temporal
and retry identity, complete scans/actual-prefix verification, private recovery,
startup barriers/forced acknowledgement, deterministic list release and owned
cleanup all pass. Native literal work/force changes are independently checked and
separated from performance claims. Full inventory, docs/rules and actual delivery
complete0098. Mark0098 done, clear its claim and prepare
**MDB-0099 — Recovery history comparison and decision** ready after completion
push. Keep0097/0013 expanded with every original allocation/latency/memory/
maintenance/compaction/durable-evidence/adoption criterion. No default change,
remote work, new format, next experiment or release starts here.

Clarify the earlier source receipt's canonical count:25 added cases cover5 unchanged
histories in2 writable/3 read-only shapes,168+252 literal expectations in total;
84 is per complete five-history storage shape, not per individual case.
Validate99 IDs/cards/DAG, completed children, no active item and only0099 ready;
inspect task-owned staged diff and commit only these3 internal planning files with
Step-ID MDB-0098 /Step-Result completed. Normal-push/fetch and verify matching
remote tips, source/completion ancestry and clean status. Final handoff records
actual completion SHA/push; no runtime/public/inventory changes require repeat CI.

## Receipt template

Append a dated entry for the selected ID with: outcome and affected artifacts;
acceptance evidence and actual commands/results; decisions and remaining limits;
new/split/reordered IDs with rationale; blocker/unblock evidence when relevant;
completion or checkpoint commit trailers; and the next recommendation at that time.
Keep current status and active ownership in the queue rather than duplicating it here.
