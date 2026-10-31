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

## Receipt template

Append a dated entry for the selected ID with: outcome and affected artifacts;
acceptance evidence and actual commands/results; decisions and remaining limits;
new/split/reordered IDs with rationale; blocker/unblock evidence when relevant;
completion or checkpoint commit trailers; and the next recommendation at that time.
Keep current status and active ownership in the queue rather than duplicating it here.
