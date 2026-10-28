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
| [0003: Initial temporal semantics](0003-initial-temporal-semantics.md) | Working specification; interval primitive implemented | Whole-value intervals, ordered commits, read views and request identity |

| [0004: Java build foundation](0004-java-build-foundation.md) | Adopted for the experimental artifact | Java 25, pinned stable Maven toolchain, version research and verification |

The current project has no stable engine API or storage format. Read the
[architecture overview](../architecture/overview.md) for the open design questions.

When a material decision changes, update its current status and retain the dated
reasoning. Keep the README, agent rules and affected public pages consistent with
the decision rather than copying detailed policy into each page.
