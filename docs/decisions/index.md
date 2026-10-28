---
description: Follow MeridianDB’s architectural decision records, including the rationale, alternatives and evidence behind its Java and Maven direction.
---

# Architectural decisions

Decision records explain a choice, its alternatives and the evidence that could
change it. They preserve the reasoning without treating an early hypothesis as a
permanent commitment.

| Record | Status | Scope |
| --- | --- | --- |
| [0001: Language strategy](0001-language-strategy.md) | Initial working decision | Java as the initial default; conditions for considering other languages |
| [0002: Documentation toolchain](0002-documentation-toolchain.md) | Working decision | Zensical, MkDocs configuration, initial hosting and portability |

The current project has no stable engine API or storage format. Read the
[architecture overview](../architecture/overview.md) for the open design questions.

When a material decision changes, update its current status and retain the dated
reasoning. Keep the README, agent rules and affected public pages consistent with
the decision rather than copying detailed policy into each page.
