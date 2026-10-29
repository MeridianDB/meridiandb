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
runnable embedding example. `InMemoryEngine` implements the initial numeric
temporal contract and remains nonpersistent. A separate local journal supports append,
force, exclusive writer access and bounded replay. An immutable history-segment
codec preserves exact transaction ranges
in bounded, checksummed byte images. The [local segment adapter](docs/development/local-segments.md)
adds create-only file writes and verified heap readers. A separate
[manifest codec and verifier](docs/architecture/checkpoint-manifest-format.md)
check a complete segment set against its reconstructed journal-prefix binding.
[Local checkpoint publication](docs/development/local-checkpoints.md) now verifies the actual
owned journal prefix and selects immutable generations through ordered forces and
atomic rename on supported filesystems. [Local database recovery](docs/development/local-database.md)
now restores historical queries and request lookup from checkpoint history plus
the journal tail, retaining the stored lineage. Its explicit writable mode now
forces each new journal record before atomic query publication and acknowledgement.
The local protocol is experimental. [Durability conformance](docs/development/durability-conformance.md)
checks process exits, lost acknowledgements, retries and seeded writable reopen;
it does not simulate device power loss.
There is no published library or stable API.
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

**Java is the default for the initial engine and reference implementation.** It
matches the initial JVM embedding target and provides static checking, standard
diagnostic tooling and one build environment for production and reference code.

Clojure and Scala remain candidates for idiomatic JVM integrations. Rust may be
useful for a measured native bottleneck or a non-JVM embedding requirement; Go may
serve a Go integration or separate service. None is a current dependency or
promised module. These choices depend on concrete deployment and workload needs.

See the [language decision](docs/decisions/0001-language-strategy.md) for alternatives,
tradeoffs, evidence and conditions for revisiting this default. **Maven is the
chosen Java build tool** for the initial artifact. The initial
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
has a tested codec and [local file adapter](docs/development/local-journal.md);
a [history-segment codec](docs/architecture/immutable-segment-format.md) now reuses
those exact transaction frames. Query-optimized layouts remain open. The
[storage research](docs/architecture/storage-research.md) compares XTDB, Kafka,
RocksDB, Lucene/Elasticsearch, Quickwit and ClickHouse, keeping durable append,
index publication and query visibility distinct.

SQL, document APIs, network protocols, replication and streaming integrations
remain open. Public interface implementation can wait while query semantics and
workloads guide the storage experiments. Current-state lookups, historical entity
lookups, interval scans and correction-sensitive aggregations have specified
[expected results](docs/architecture/temporal-contract.md#worked-history-and-workload-expectations),
without committing to a public API.

## Development roadmap

The [technical roadmap](docs/roadmap.md) describes the current memory engine and
local journal, immutable segment files, checkpoint publication and read recovery,
plus forced local commit publication, process crash/retry conformance and typed
temporal query operators. Planned work covers history-preserving compaction,
object storage and reproducible workload measurements. The initial target is an embedded, single-node
Java engine built with Maven.

## Build the Java foundation

Use JDK 25 and run from the repository root; Maven is downloaded by the wrapper:

```bash
./mvnw -B verify
```

On Windows PowerShell:

```powershell
.\mvnw.cmd -B verify
```

This runs 495 JUnit test invocations: 71 temporal/engine, 29 journal codec,
57 local journal I/O/process/prefix, 27 immutable segment codec, 36 local segment
I/O/lifetime, 45 manifest codec/consistency, 39 local checkpoint publication,
37 local database recovery and 49 commit preparation/initialization/publication
checks, plus 50 durable process/retry/temporal conformance checks
and 55 typed query checks. Coverage
includes all 84 contract expectations against
both engine and reference, 16 seeded histories with 512 new transactions applied to
each, lifecycle/concurrency, golden journal bytes, short/failed I/O, repair
evidence and separate-process writer exclusion/recovery.
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
The separate [local journal example](docs/development/local-journal.md) exercises
append/force and exact reopen. The [local checkpoint example](docs/development/local-checkpoints.md#readers-and-example)
adds verified generation publication and reopen. The [recovery example](docs/development/local-database.md#cost-and-verification)
reopens temporal queries through `LocalDatabase`. The
[writable example](docs/development/local-database.md#create-and-write) adds forced
commits and an identical retry after reopen. `InMemoryEngine` remains nonpersistent.
The [typed query guide](docs/development/temporal-queries.md) explains signed-value
predicates, deterministic entity selection and filtered interval scans at one
pinned cutoff. Run its checked example after verification:

```bash
java --class-path target/classes examples/QueryExample.java
```

## Working in this repository

See [Contributing](docs/contributing/index.md) for focused changes and validation.
Architectural rationale lives in [decision records](docs/decisions/index.md).
Use the Java command above and the separate documentation checks for public docs
changes.

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
.venv/bin/python scripts/build_docs.py
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
