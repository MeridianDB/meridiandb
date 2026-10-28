# MeridianDB

MeridianDB is an early-stage project exploring an embeddable temporal storage
engine. Java and in-process JVM embedding are the initial working direction;
broader host requirements remain open. Its focus is bitemporal data: preserving
both when a fact applies in the domain and when the database recorded that knowledge.

**Status:** project foundation and architectural research. The documentation site
is live at [meridiandb.org](https://meridiandb.org/) and can be built locally.
The [initial temporal contract](docs/architecture/temporal-contract.md) and exact
workload fixtures are defined. The Java 25/Maven build now includes a memory-only
embedded engine, immutable temporal types, an independent test reference and a
runnable embedding example. There is no persistence, published library or stable
API yet. The engine implements the initial numeric temporal contract. A pure
versioned journal codec is available; local append/sync and recovery remain planned.
The broader storage capabilities below remain the intended direction.

## Why temporal storage?

A correction received today can change what we believe about yesterday without
erasing what we believed yesterday. MeridianDB aims to make that distinction
explicit and queryable.

- **Valid time:** the interval in which an asserted fact applies in the domain.
- **System time:** the database-managed history of committed knowledge.
- **Event history:** the observations or messages received, which may produce
  changes to temporal state but are not themselves the same thing as that state.

For example, a salary recorded in January might be corrected in March with effect
from February. Compare the salary effective in February as known in February
with the salary effective in February as known after the March correction. The
effective date is the same; the recorded knowledge differs. Audit records,
configuration history, inventory and financial events are possible workloads;
finance and FIX are examples rather than boundaries on the engine's purpose.

## Language direction

**Java is the default for the initial engine and reference implementation.** The
maintainer knows Java best, which helps with reviewing generated code, diagnosing
failures and maintaining the system. It also fits the intended embedded JVM use.
These are stronger reasons than assumptions about AI training data, code length
or how much attention a language will attract.

Clojure and Scala remain useful candidates for experiments and future idiomatic
JVM integrations. Rust remains an option for a narrowly bounded native component
when measurements justify the integration cost, or for the whole engine if
non-JVM embedding becomes a firm requirement. Go remains an option if a Go
embedding target or separate service becomes important. None is currently a
dependency or a promised module. Learning another language is welcome; production
adoption should serve an identified engineering need.

See the [language decision](docs/decisions/0001-language-strategy.md) for alternatives,
tradeoffs, evidence and conditions for revisiting this default. **Maven is the
chosen Java build tool**, reflecting the maintainer's experience. The initial
[build foundation](docs/development/build.md) uses Java 25 and a checksum-pinned
Maven Wrapper. Maven artifact versions are researched from current primary sources
before selection and then pinned; see the dated evidence in the build guide.

## Research direction

The initial investigation will focus on:

- Precise semantics for insertion, partial retroactive correction, retraction and
  queries across valid time and system time.
- A simple reference model against which optimized implementations can be tested.
- Immutable persistent segments, explicit commit visibility and reliable recovery.
- Persistence contracts that can support both local files and object storage,
  including S3-compatible services, without depending on one cloud vendor.
- Storage layouts and indexes evaluated against representative temporal queries.

Parquet is a candidate file format; an object store is a persistence service;
MergeTree-like organization is a candidate storage technique. These choices are
related but separate. Snapshot history also remains distinct from bitemporal
history. The [transaction journal format](docs/architecture/journal-format.md) now
has a tested codec; an immutable query-segment format remains unselected. The
[storage research](docs/architecture/storage-research.md) compares XTDB, Kafka,
RocksDB, Lucene/Elasticsearch, Quickwit and ClickHouse, keeping durable append,
index publication and query visibility distinct.

SQL, document APIs, network protocols, replication and streaming integrations
remain open. Public interface implementation can wait while query semantics and
workloads guide the storage experiments. Current-state lookups, historical entity
lookups, interval scans and correction-sensitive aggregations have specified
[expected results](docs/architecture/temporal-contract.md#worked-history-and-workload-expectations),
without committing to a public API.

## Implementation plan and agent queue

The first target is an embedded, single-node Java engine built with Maven. Its
[implementation roadmap](docs/planning/roadmap.md) progresses through temporal
semantics, a reproducible build and reference model, in-memory embedding, durable
local storage/recovery, query operators, compaction, object storage, workload
measurements and a prototype release candidate.

The [live queue](docs/planning/queue.md) owns current order, dependencies and state.
An agent can expand a broad item into bounded substeps or insert a discovered
prerequisite while preserving stable IDs and completion criteria. The
[iteration workflow](docs/planning/index.md) carries one selected item through
implementation, focused checks, documentation/planning updates, a commit and push
to `origin/main`, then a recommendation for the next step. Dated evidence lives in
[progress history](docs/planning/progress.md).

To start an iteration, ask: **Continue the implementation queue for one bounded
step.** Preparing the plan does not automatically start engine implementation.
Source pushes run applicable checks; documentation changes pushed to `main` also
publish the website automatically after its strict build succeeds. Library releases
remain separate actions. The local-storage milestone is split into required
journal format/codec and file I/O/recovery children. The codec is available; the
next implementation outcome is local append, sync, writer ownership and recovery.

## Build the Java foundation

Use JDK 25 and run from the repository root; Maven is downloaded by the wrapper:

```bash
./mvnw -B verify
```

On Windows PowerShell:

```powershell
.\mvnw.cmd -B verify
```

This runs 100 JUnit test invocations: the existing 71 temporal/engine checks plus
29 journal codec checks. Coverage includes all 84 contract expectations against
both engine and reference, 16 seeded histories with 512 new transactions applied to
each, lifecycle/concurrency, golden journal bytes, corruption and truncation.
It creates the local experimental
`target/meridiandb-core-0.1.0-SNAPSHOT.jar`. The
[reference model](docs/development/reference-model.md) and fixture parser remain
test-only. See the [Java/Maven guide](docs/development/build.md) for setup, version
research, wrapper verification and Linux/Windows CI.

Run the memory-only embedding example after verification:

```bash
java --class-path target/classes examples/EmbeddedExample.java
```

It prints February salary values of `100` before a correction and `120` after it,
then confirms an identical retry returns commit `2`. The
[engine guide](docs/development/embedded-engine.md) explains the experimental API,
read-view lifetimes and memory limits. Closing or crashing loses the database.

## Working in this repository

Read [AGENTS.md](AGENTS.md) for agent working agreements and documentation upkeep.
Architectural rationale lives in [decision records](docs/decisions/index.md).
Keep the README's implemented status accurate as the project develops. Use the
Java command above and run the separate documentation checks for public docs changes.

## Documentation website

The website uses Zensical with MkDocs configuration in `mkdocs.yml` and the
Material-style classic theme. Content lives in `docs/`; the published site is
[meridiandb.org](https://meridiandb.org/). Use Python 3.13 (recorded in `.python-version`)
and run these commands from the repository root.

Create the isolated documentation environment:

```bash
python3 -m venv .venv
```

Install the locked dependencies:

```bash
.venv/bin/python -m pip install --require-hashes -r requirements-docs.txt
```

Start the local preview:

```bash
.venv/bin/zensical serve --dev-addr 127.0.0.1:8000
```

Build the static site with strict checks:

```bash
.venv/bin/zensical build --strict
```

The generated site is `site/`. Python documentation tooling is independent of the
Maven Java build. Content uses ordinary Markdown and source-relative
links to help preserve portability to other generators, including Hugo.

GitHub Pages is the initial hosting target. Affected pull requests and pushes to
`main` run documentation checks. Documentation changes pushed to `main` also run
**Publish documentation**, which builds and deploys automatically. **Run workflow**
remains available on `main` for a manual rebuild. Cloudflare can also host the static
output if hosting needs change. See the
[documentation and publishing guide](docs/contributing/documentation.md) and
[toolchain decision](docs/decisions/0002-documentation-toolchain.md) for setup,
compatibility, metadata and future versioning.

## License

[Apache License 2.0](LICENSE).
