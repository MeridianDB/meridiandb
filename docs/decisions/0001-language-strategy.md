---
title: Language strategy — Java and Maven
description: Why MeridianDB starts with Java and Maven, how Clojure, Scala, Rust and Go compare, and what evidence could change the language decision.
---

# 0001: Start with Java; revisit language choices against concrete requirements

- Date: 2026-10-28
- Status: Initial working decision, revisitable
- Scope: Initial engine, correctness reference model and future language boundaries

## Context

The maintainer knows Java better than other languages, is willing to learn while
developing, and is considering Clojure, Scala, Rust and Go. The project is still
investigating temporal semantics and storage design. Its initial deployment
assumption is an engine embedded in a JVM application; that assumption must be
confirmed before treating the public API or deployment model as stable.

Java familiarity is an advantage in reviewing AI-generated code and diagnosing
storage failures. It is not a claim that AI models necessarily generate better
Java, that the most concise language is best, or that Java guarantees adoption.

## Working decision

Use Java for the initial engine and simple reference model. Keep the initial
runtime and tooling focused on one implementation language. Maven is the Java
build tool, following the owner's October 28, 2026 preference and existing
fluency. The [build foundation](0004-java-build-foundation.md) now establishes
Java 25 and the checksum-pinned Maven Wrapper. The documentation website has a
separate Python build; it does not add a language or runtime dependency to the engine.

The reference model should use a simpler, independently reasoned algorithm than
the optimized implementation. It need not use a different language. Correctness
evidence comes from explicit expectations, invariant tests and fault experiments,
not merely from compiling two versions of the same logic.

Choose a language for the component's responsibilities and deployment boundary.
A future Clojure or Scala client does not require rewriting the storage engine.
A future native component does not require moving temporal semantics into it.
Design those boundaries when there is a concrete consumer or experiment; do not
create placeholder modules for every possible language now.

## Alternatives and tradeoffs

| Option | Reasons to consider it | Main tradeoff for this project |
| --- | --- | --- |
| Java | Maintainer fluency, direct JVM embedding, static checking and established diagnostic tooling | Object allocation, GC, off-heap lifetimes and layout still need deliberate engineering and measurement |
| Clojure | Expressive immutable data transformations, interactive exploration and a potentially concise temporal model | Learning and reviewing unfamiliar production code while also inventing database semantics; fewer lines alone do not establish maintainability or performance |
| Scala | Strong types and functional modeling within the JVM | Another language and toolchain to maintain without a demonstrated need in the initial engine |
| Rust | Native embedding, explicit resource control and components where runtime footprint is a primary constraint | New expertise and, when combined with Java, native ownership, ABI, packaging and debugging responsibilities |
| Go | A Go embedding target, independently deployed service or useful operational tool | A second language/runtime and an integration boundary; it does not automatically improve a JVM engine's internals |

Clojure can require less source code for particular transformations or models.
The useful comparison is total effort to implement, test, explain, debug and
change the same behavior. Immutable in-memory collections do not by themselves
provide durable history or a correct commit protocol. A small comparative
experiment is welcome when it answers a defined question or serves an explicit
learning objective; a production dependency needs a separate engineering case.

Java's ecosystem is a plausible distribution advantage for a JVM library, but
attention and adoption depend on solving a useful problem, good documentation,
credible correctness and measured results. Language choice alone is not product
differentiation.

## What the existing projects demonstrate

Primary sources checked on 2026-10-28. Development-branch source links describe
current implementation evidence, not the language composition of every release.

- **Kafka:** its history includes substantial Scala, but current storage internals
  include Java, such as [`LocalLog.java`](https://github.com/apache/kafka/blob/trunk/storage/src/main/java/org/apache/kafka/storage/internals/log/LocalLog.java).
  Apache's [modularization work](https://issues.apache.org/jira/browse/KAFKA-14524)
  includes replacing remaining Scala with Java. Kafka is not evidence that a new
  engine must choose Scala, nor should it be described as wholly Java.
- **XTDB:** current core sources include both [Clojure expression processing](https://github.com/xtdb/xtdb/blob/main/core/src/main/clojure/xtdb/expression.clj)
  and [Kotlin bitemporal processing](https://github.com/xtdb/xtdb/blob/main/core/src/main/kotlin/xtdb/bitemporal/PolygonCalculator.kt).
  Its [architecture](https://docs.xtdb.com/intro/what-is-xtdb.html) already combines
  JVM execution, bitemporality, columnar Arrow storage and object storage. It is a
  relevant design reference and comparison target; copying its language mix is
  not a substitute for testing MeridianDB's requirements.
- **Arrow Java:** its [memory implementation](https://arrow.apache.org/java/main/memory.html)
  supports off-heap buffers independently of C++. Particular modules, including
  [Dataset](https://arrow.apache.org/java/current/dataset.html), use native bindings.
  Evaluate dependencies at the module level rather than assuming that all Arrow
  use is either pure Java or native.
- **Native interoperability:** Java's [Foreign Function and Memory API](https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/package-summary.html)
  provides a route to native code and memory. A binding still needs explicit
  ownership, lifetime, ABI, error and platform-distribution contracts. These costs
  belong in any comparison with an entirely Java implementation.

## Conditions for revisiting the decision

1. **Embedding requirements:** confirm the first host, memory budget and startup
   constraints before committing to the core. A firm need for lightweight,
   in-process use from non-JVM hosts makes Rust a serious whole-engine alternative.
   A client calling a server is a different requirement from native embedding.
2. **Developer effectiveness:** if a Clojure or Scala experiment is undertaken,
   compare the same temporal contract, tests and maintenance task. Evaluate
   understandability and debugging effort as well as code volume and speed.
3. **Measured engine constraints:** after a correct durable prototype exists,
   profile ingestion, temporal lookups, scans, compaction and recovery. Include
   total process memory, allocation and latency distributions under a stated load.
4. **Bounded native evaluation:** when profiling identifies a relevant bottleneck,
   compare a Java improvement with a native candidate under identical semantics
   and durability. Include data conversion, boundary crossings, failure behavior
   and delivery complexity in the result. Agree workload targets before treating
   a speedup as sufficient justification.
5. **Real consumers:** reassess language APIs and deployment assumptions before
   stabilizing the public interface, using actual integration needs.

A change to this decision should record the requirement or experiment, evidence,
alternatives and migration consequences. Update the current decision and its
README/agent-rule references together, preserving the dated rationale. There is
no permanent ban on another language and no automatic obligation to adopt one.
