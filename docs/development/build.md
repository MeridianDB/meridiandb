---
title: Java and Maven development
description: Build MeridianDB's Java 25 foundation with its checksum-pinned Maven Wrapper, run contract boundary tests and research current Maven artifact versions.
---

# Java and Maven development

The repository contains one experimental Java artifact,
`org.meridiandb:meridiandb-core:0.1.0-SNAPSHOT`. It contains immutable experimental
command, query, interval, result and commit-position types in
`org.meridiandb.temporal`. A [test-only reference model](reference-model.md) executes
the canonical contract histories and seeded checks alongside the
[embedded in-memory engine](embedded-engine.md) in `org.meridiandb.engine`.
The engine is experimental and memory-only. The pure journal codec in
`org.meridiandb.storage.journal` adds a tested representation; it supplies no local
file I/O or durable acknowledgement. No library is published.

## Build and verify

Install a JDK from the **Java 25** line, the baseline recorded in `.java-version`
and compiler `release` in `pom.xml`. Select it through `JAVA_HOME`/`PATH` in your
normal environment. A separately installed Maven is not required. The
[build decision](../decisions/0004-java-build-foundation.md) explains this baseline.

Run from the repository root on macOS or Linux:

```bash
./mvnw -B verify
```

On Windows PowerShell:

```powershell
.\mvnw.cmd -B verify
```

The wrapper downloads Maven 3.10.0 on first use, checks the distribution's pinned
SHA-256, and caches it. Maven resolves pinned plugins and test dependencies from
Maven Central, so a cold build needs network access. The POSIX bootstrap needs a
shell, `unzip` and either `sha256sum` or `shasum`; it can download through `curl`,
`wget` or its Java fallback. The Windows launcher uses PowerShell.

Verification compiles production/test code, runs JUnit Jupiter and produces the
local `target/meridiandb-core-0.1.0-SNAPSHOT.jar`. Reports are under
`target/surefire-reports/`. `target/` is ignored. The artifact has no external
runtime dependencies; JUnit and Jackson are test-only. The command does not
install a library into a remote repository or publish a release.

To remove stale output before verifying:

```bash
./mvnw -B clean verify
```

To inspect the actual Maven and Java runtime selected by the wrapper:

```bash
./mvnw -version
```

The current 100 test invocations include 29 journal codec checks plus the existing
71 temporal/engine checks: 18 interval boundary checks, eight type
checks, ten reference/decoding checks, five canonical histories per implementation,
16 seeded histories comparing both implementations with dense snapshots, and nine
engine lifecycle/concurrency checks. Each implementation executes 84 exact fixture
expectations and 512 seeded new transactions. The [reference guide](reference-model.md)
and [engine guide](embedded-engine.md) explain coverage and limits. Durable storage
and crash recovery remain unimplemented. The
[journal specification](../architecture/journal-format.md) documents golden bytes,
corruption/truncation, exact identity and allocation-limit coverage.

Run the embedding example after verification, on either platform:

```bash
java --class-path target/classes examples/EmbeddedExample.java
```

This uses JDK source-file launch mode with the compiled project classes. There is
no extra execution plugin or example dependency.

## Project conventions

Use standard `src/main/java` and `src/test/java` layouts. Keep production packages
under `org.meridiandb`; the first primitive lives in `.temporal`. Match test package
and source names, with `*Test` classes discovered by Surefire. Use JUnit Jupiter,
exact contract expectations and focused failure cases. The build fails when no
tests are discovered. Production code compiles with `--release 25`, `-Xlint:all`
and `-Werror`; preview features are not enabled.

Start with one JAR and introduce another module only for an actual deliverable.
The group/artifact coordinates are project conventions, not a Maven Central
publication or verified-namespace claim. No release repository is configured.
The records and their nullable unbounded endpoints are experimental Java syntax;
the logical contract remains authoritative as the type set develops. Maven loads
`docs/architecture/fixtures/temporal-contract-v0.json` as a test resource under
`contracts/`; keep that source authoritative rather than copying its expectations.
The seed corpus is `src/test/resources/reference-seeds.txt`. Test code/resources
and their JSON parser stay outside the production JAR.

Pin build/test plugins in the POM, including the default clean/verify lifecycle
and managed install/deploy/wrapper goals. Install/deploy versions are managed for
predictability, without binding either goal to verification. Maven site generation
is not part of this build; the documentation toolchain remains separate.
`project.build.outputTimestamp` fixes archive timestamps for repeatable packaging
from identical inputs and JDK; it is not a release date or a guarantee that every
compiler patch/vendor emits identical bytes.

## Research Maven versions for every Maven change

For Maven implementation, build or dependency changes, check the current versions
of the affected pinned artifacts: Maven, wrapper components, lifecycle/test plugins
and direct dependencies. Use Maven Central metadata and official project release
and compatibility documentation. Record the date, coordinates, latest stable
release, selected version and any compatibility/security reason for a difference.
The dated table below records the current selection.

Research before selecting versions; do not substitute a remembered version or a
passing cached build. Metadata `latest` and `release` can point to prereleases:
the initial Maven metadata points to Maven 4 RC and several plugin 4.x betas.
Check the release channel and runtime requirements. Prefer the latest stable
compatible release, then pin its exact version. A prerelease needs a concrete
reason and validation. Keep transitive dependencies governed by their pinned
upstream artifacts unless a researched compatibility/security finding requires
an explicit override; inspect the resolved graph when that contract changes.
Floating `LATEST`, version ranges and snapshot dependencies are not the update
mechanism. The project's own unreleased `0.1.0-SNAPSHOT` is separate from that rule.

## Artifact research checked on 2026-10-28

These are dated findings, not a promise that the versions remain latest. POM and
wrapper properties own executable configuration. Apache's
[download page](https://maven.apache.org/download.cgi) recommends Maven 3.10.0;
Maven 4 is preview. The [plugin catalog](https://maven.apache.org/plugins/index.html)
confirms the stable Maven 3 plugin lines. The
[JUnit guide](https://docs.junit.org/6.1.3/overview.html) identifies JUnit 6.1.3 and
its Java 17 minimum, compatible with this Java 25 baseline. Jackson 3.2.4 decodes
canonical test fixtures. The [Jackson project](https://github.com/FasterXML/jackson-databind)
documents its 3.x `tools.jackson` packages and Java 17 baseline. Its tree model lets
the adapter reject non-integral or out-of-range values before constructing a
transaction. The journal codec uses JDK CRC32C and byte buffers, adding no runtime
dependency. All selected versions in the table were checked on the date above.

| Artifact | Selected stable version | Metadata latest entry | Evidence |
| --- | --- | --- | --- |
| `org.apache.maven:apache-maven` | 3.10.0 | 4.0.0-rc-7 | [Central metadata](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml) |
| `org.apache.maven.wrapper:maven-wrapper-distribution` | 3.3.4 | 3.3.4 | [Central metadata](https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper-distribution/maven-metadata.xml) |
| `org.apache.maven.plugins:maven-wrapper-plugin` | 3.3.4 | 3.3.4 | [Central metadata](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-wrapper-plugin/maven-metadata.xml) |
| `org.apache.maven.plugins:maven-clean-plugin` | 3.5.0 | 4.0.0-beta-3 | [Central metadata](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-clean-plugin/maven-metadata.xml) |
| `org.apache.maven.plugins:maven-resources-plugin` | 3.5.0 | 4.0.0-beta-2 | [Central metadata](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-resources-plugin/maven-metadata.xml) |
| `org.apache.maven.plugins:maven-compiler-plugin` | 3.16.0 | 4.0.0-beta-5 | [Central metadata](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-compiler-plugin/maven-metadata.xml) |
| `org.apache.maven.plugins:maven-surefire-plugin` | 3.6.1 | 3.6.1 | [Central metadata](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-surefire-plugin/maven-metadata.xml) |
| `org.apache.maven.plugins:maven-jar-plugin` | 3.5.1 | 4.0.0-beta-2 | [Central metadata](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-jar-plugin/maven-metadata.xml) |
| `org.apache.maven.plugins:maven-install-plugin` | 3.2.0 | 4.0.0-beta-3 | [Central metadata](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-install-plugin/maven-metadata.xml) |
| `org.apache.maven.plugins:maven-deploy-plugin` | 3.2.0 | 4.0.0-beta-3 | [Central metadata](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-deploy-plugin/maven-metadata.xml) |
| `org.junit.jupiter:junit-jupiter` | 6.1.3 | 6.1.3 | [Central metadata](https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/maven-metadata.xml) |
| `tools.jackson.core:jackson-databind` | 3.2.4 | 3.2.4 | [Central metadata](https://repo.maven.apache.org/maven2/tools/jackson/core/jackson-databind/maven-metadata.xml) |

All selected entries are the latest stable releases found. Newer metadata entries
in the Maven/plugin 4.x series are RC/beta releases, so they are not selected for
the stable Maven 3 build. The Enforcer plugin was also researched but is not needed
for this small foundation; javac's release setting and the wrapper define the
current baseline without another verification plugin.

## Wrapper provenance and updates

The scripts were generated with Apache Maven Wrapper Plugin 3.3.4, using its
`only-script` distribution. Both launchers are tracked; `mvnw` is executable with
LF endings and `mvnw.cmd` uses CRLF on checkout. There is no wrapper JAR to commit.

The Maven ZIP at the wrapper's `distributionUrl` was compared against Apache's
[published SHA-512](https://downloads.apache.org/maven/maven-3/3.10.0/binaries/apache-maven-3.10.0-bin.zip.sha512).
Its computed SHA-256 is pinned as `distributionSha256Sum` in
`.mvn/wrapper/maven-wrapper.properties`. A fresh-bootstrap negative check also
confirmed rejection of an incorrect checksum. This verifies distribution integrity;
it does not claim independent signature verification of every dependency.

After researching a wrapper/runtime update, generate the scripts using fully
qualified, exact plugin/version arguments. The initial reproducible generation
command is:

```bash
./mvnw org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.10.0 -Dtype=only-script
```

Generation can rewrite wrapper properties. Verify the selected archive against
Apache's official checksum, restore its exact SHA-256 pin before building, review
the scripts/properties and run a fresh bootstrap plus `./mvnw -B verify`. Keep the
checksum when resolving missing bootstrap tools. Update POM management, research
evidence and build guidance with a version change.

## Continuous integration

**Java checks** runs on affected pull requests and pushes to `main`, with read-only
repository permissions and credentials removed after checkout. Linux runs
`./mvnw -B verify`; Windows runs `.\mvnw.cmd -B verify`. Both select Temurin from
`.java-version`, checking for the latest patch in that Java line. Action versions
are pinned by commit; CI prints the actual runtime. Maven dependencies are cached
by POM content. Changes to the temporal contract, journal format or canonical fixture directory
also trigger Java checks, since they affect executable expectations. No Java
artifact is uploaded or released by this workflow. Both platforms also run the
embedding example; changes under `examples/` trigger these checks.

Documentation checks and automatic Pages publication retain their own workflows.
For website setup and verification, follow the
[documentation guide](../contributing/documentation.md).
