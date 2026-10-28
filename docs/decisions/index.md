---
description: Follow MeridianDB's decisions on Java and Maven, documentation tooling and initial temporal semantics, with rationale and alternatives.
---

# Architectural decisions

Decision records explain a choice, its alternatives and the evidence that could
change it. They preserve the reasoning without treating an early hypothesis as a
permanent commitment.

| Record | Status | Scope |
| --- | --- | --- |
| [0001: Language strategy](0001-language-strategy.md) | Initial working decision | Java as the initial default; conditions for considering other languages |
| [0002: Documentation toolchain](0002-documentation-toolchain.md) | Working decision | Zensical, MkDocs configuration, initial hosting and portability |
| [0003: Initial temporal semantics](0003-initial-temporal-semantics.md) | Working specification; reference and memory engine implemented | Whole-value intervals, ordered commits, read views and request identity |
| [0004: Java build foundation](0004-java-build-foundation.md) | Adopted for the experimental artifact | Java 25, pinned stable Maven toolchain, version research and verification |
| [0005: Reference model](0005-reference-model.md) | Implemented in test support | Immutable logical types, history-search oracle and independent dense-state checks |
| [0006: In-memory engine](0006-in-memory-engine.md) | Implemented; experimental | Immutable interval snapshots, serialized publication, pinned views and explicit close behavior |
| [0007: Journal format](0007-journal-format.md) | Pure codec implemented | Versioned bounded framing, exact identity, checksums and strict incomplete/corrupt input handling |

The current project has no stable engine API or storage-format compatibility promise. Read the
[architecture overview](../architecture/overview.md) for the open design questions.

When a material decision changes, update its current status and retain the dated
reasoning. Keep the README, agent rules and affected public pages consistent with
the decision rather than copying detailed policy into each page.
