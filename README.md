# MeridianDB

MeridianDB is an early-stage project exploring an embeddable temporal storage
engine. Java and in-process JVM embedding are the initial working direction;
broader host requirements remain open. Its focus is bitemporal data: preserving
both when a fact applies in the domain and when the database recorded that knowledge.

**Status:** project foundation and architectural research. The documentation site
can be built and previewed locally. There is no engine, published library, stable
API or runnable database example yet. The capabilities below describe the intended
direction, not implemented engine features.

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
chosen Java build tool**, reflecting the maintainer's experience. The JDK baseline
and Maven Wrapper will be established with the first Java implementation.

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
history. No particular physical format has been selected.

SQL, document APIs, network protocols, replication and streaming integrations
remain open. Public interface implementation can wait while query semantics and
workloads guide the storage experiments. Current-state lookups, historical entity
lookups, interval scans and correction-sensitive aggregations are candidate tests,
not an API commitment.

## First implementation milestone

1. Specify a small temporal contract with worked examples, transaction ordering,
   interval boundaries, correction/retraction behavior and expected query results.
2. Implement a straightforward in-memory Java reference model and executable
   examples, including randomized histories checked against independent expectations.
3. Add one persistent implementation with an explicit initial writer model,
   acknowledgement/durability guarantees and crash/restart tests.
4. Exercise the persistence contract against local storage and an object-store
   backend, testing commit publication, retries and uncertain outcomes.
5. Compare layouts or indexes only after establishing correctness and a reproducible
   baseline, including query latency, write/read amplification and recovery behavior.

This sequence is a working research plan. Evidence may change it. Each increment
should leave a runnable, tested result and a short account of what was learned.
Distribution and a broad client ecosystem are later possibilities rather than
requirements for the first prototype.

## Working in this repository

Read [AGENTS.md](AGENTS.md) for agent working agreements and documentation upkeep.
Architectural rationale lives in [decision records](docs/decisions/0001-language-strategy.md).
Keep the README's implemented status accurate as the project develops. Java build
and test instructions will be added with the first engine implementation.

## Documentation website

The website uses Zensical with MkDocs configuration in `mkdocs.yml` and the
Material-style classic theme. Content lives in `docs/`; the intended public
address is `https://meridiandb.org/`. Use Python 3.13 (recorded in `.python-version`)
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
future Maven engine build. Content uses ordinary Markdown and source-relative
links to help preserve portability to other generators, including Hugo.

GitHub Pages is the initial hosting target. Pull requests and pushes to `main`
run checks; publication requires manually running **Publish documentation** after
Pages and the domain have been configured. Cloudflare can also host the static
output if hosting needs change. See the
[documentation and publishing guide](docs/contributing/documentation.md) and
[toolchain decision](docs/decisions/0002-documentation-toolchain.md) for setup,
compatibility, metadata and future versioning.

## License

[Apache License 2.0](LICENSE).
