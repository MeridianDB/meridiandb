---
title: Java and Maven development
description: Build MeridianDB's Java 25 foundation with its checksum-pinned Maven Wrapper, run contract boundary tests and research current Maven artifact versions.
---

# Java and Maven development

The root build produces `org.meridiandb:meridiandb-core:0.1.0-SNAPSHOT`.
A separate optional [S3 byte adapter](s3-objects.md) produces `meridiandb-s3`;
ordinary local embedding requires only core. Core contains immutable experimental
command, query, interval, result and commit-position types in
`org.meridiandb.temporal`. A [test-only reference model](reference-model.md) executes
the canonical contract histories and seeded checks alongside the
[embedded in-memory engine](embedded-engine.md) in `org.meridiandb.engine`.
The engine is experimental and memory-only. The journal codec and
[local file adapter](local-journal.md) in `org.meridiandb.storage.journal` provide
bounded append, force and replay independently of that engine. The pure
[immutable history codec](../architecture/immutable-segment-format.md) adds exact
segment bytes and content identity. The [local segment adapter](local-segments.md)
adds create-only file writes and verified heap readers. The
[manifest codec and verifier](../architecture/checkpoint-manifest-format.md) add
complete-set consistency against a reconstructed journal-prefix binding. The
[local checkpoint store](local-checkpoints.md) adds actual owner-journal verification,
immutable generation publication and verified reopen under explicit filesystem
capabilities. [Local database recovery](local-database.md) restores read-only
temporal queries and request lookup from complete checkpoint history plus the
journal suffix. Explicit writable modes now synchronize startup and force each
new record before atomic query publication and acknowledgement.
[Durability conformance](durability-conformance.md) adds process exits, retry and
canonical/seeded writable-reopen checks.
[Pure history compaction](history-compaction.md) validates supplied checkpoint
history and returns bounded exact-history replacement proposals. Local compaction
publication now verifies/reuses or installs outputs and selects the next generation
at the same cutoff. Lazy readers now hold independent shared cross-process
[protection](reader-protection.md), with explicit release/cleanup behavior. [Protected physical deletion](physical-reclamation.md) now recomputes retention
and orders live-root synchronization and metadata retirement before dependencies. A bounded [inventory](reclamation-inventory.md)
now verifies explicit retained roots and obsolete-object metadata without removal.
The [immutable-object interface](immutable-objects.md) adds a JDK-only local
adapter and segment-codec consumer with complete expected-ID/length checks and
verified heap ranges, independently of checkpoint selection. The pure
[object-checkpoint-head codec](../architecture/object-checkpoint-protocol.md) adds
a bounded selection format and metadata checks. Optional
[conditional S3 head access](s3-checkpoint-heads.md) provides same-response
observations and exact conditional writes. JDK-only
[object graph verification](object-checkpoint-graphs.md) adds bounded complete
history and actual-journal comparisons. Optional [verified publication](s3-checkpoint-publication.md)
composes exact durable intent, conditional selection and reconciliation. Optional
[managed S3 read recovery](s3-database.md) admits actual client scope and exact
durable expected selection before privately owned core recovery. No library is published.

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
`target/surefire-reports/`. `target/` is ignored. The core artifact has no external
runtime dependencies; JUnit and Jackson are test-only. A small attached test-support
JAR contains the reusable object contract, unchanged temporal fixture/reference
classes and authoritative contract/seed resources. The optional S3 build uses it
and Jackson only in test scope for [managed recovery conformance](s3-recovery-conformance.md),
and resolves its SDK separately. The optional build also verifies the
[pure coordination state/codec](../architecture/s3-coordination-state.md), with no
new runtime dependency, native lease or SDK operation. A separate
[internal native guard](s3-coordination-guard.md) adds existing-file leases without
enrollment, ledger installation or SDK participation; follow the
[S3 build commands](s3-objects.md#build-and-verify) to verify both artifacts. The command does not
install a library into a remote repository or publish a release.

To remove stale output before verifying:

```bash
./mvnw -B clean verify
```

To inspect the actual Maven and Java runtime selected by the wrapper:

```bash
./mvnw -version
```

The current 1,351 core test invocations include 16
[supervised worker checks](supervised-workloads.md), 26
[local measurement checks](local-measurements.md), 36
[workload corpus checks](workload-corpus.md), one
[filesystem qualification probe](linux-storage-qualification.md) and 66 [pinned checkpoint write checks](object-checkpoint-writes.md), 69 [pinned checkpoint recovery checks](object-checkpoint-recovery.md), 22 [pinned object-history loading checks](object-checkpoint-graphs.md#load-pinned-history), 23 [bounded compaction/reclamation preflight checks](../architecture/checkpoint-manifest-format.md#bounded-consumer-range-admission), 34 [object graph/actual-prefix checks](object-checkpoint-graphs.md), 46 [object-checkpoint-head format/metadata checks](../architecture/object-checkpoint-protocol.md), 84 [immutable-object/type/local/fault/segment-consumer checks](immutable-objects.md), 46 [integrated compaction/reclamation cases](compaction-conformance.md), 93 [protected removal checks](physical-reclamation.md), 69 [reclamation inventory checks](reclamation-inventory.md), 70 [reader protection/identity/cleanup checks](reader-protection.md), 55 [local compaction publication/integration/process checks](history-compaction.md#install-and-publish-locally), 57 [pure compaction checks](history-compaction.md), 55 [typed query checks](temporal-queries.md), 50 [durable conformance checks](durability-conformance.md), 49 [commit preparation/initialization/publication checks](local-database.md),
37 [local database recovery checks](local-database.md),
39 [local checkpoint publication checks](local-checkpoints.md),
45 [manifest codec/consistency checks](../architecture/checkpoint-manifest-format.md),
36 [local segment I/O/lifetime checks](local-segments.md),
43 immutable segment codec checks, 29 journal codec checks, 84 local journal
I/O/process/prefix checks (including 26 [caller-budget checks](local-journal.md#bound-prefix-verification-by-encoded-bytes)) and 71 temporal/engine checks: 18 interval boundary checks, eight type
checks, ten reference/decoding checks, five canonical histories per implementation,
16 seeded histories comparing both implementations with dense snapshots, and nine
engine lifecycle/concurrency checks. The memory engine and reference each execute
84 exact fixture expectations and 512 seeded new transactions. Local read recovery
executes the canonical histories in three checkpoint shapes. The [reference guide](reference-model.md)
and [engine guide](embedded-engine.md) explain coverage and limits. The local
adapters have separate failure/reopen tests; forced local writes and atomic
publication have focused checks alongside real process exits and seeded writable reopen. The
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

Keep the root core JAR independent of optional integrations. The separate S3
artifact is the first additional deliverable; [ADR 0022](../decisions/0022-optional-s3-object-access.md)
explains its standalone POM and local-install prerequisite.
The group/artifact coordinates are project conventions, not a Maven Central
publication or verified-namespace claim. No release repository is configured.
The records and their nullable unbounded endpoints are experimental Java syntax;
the logical contract remains authoritative as the type set develops. Maven loads
`docs/architecture/fixtures/temporal-contract-v0.json` and `temporal-query-v0.json`
as test resources under `contracts/`; keep those sources authoritative rather than
copying their expectations.
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

## Artifact research checked on 2026-11-07

These are dated findings, not a promise that the versions remain latest. POM and
wrapper properties own executable configuration. Apache's
[download page](https://maven.apache.org/download.cgi) recommends Maven 3.10.0;
Maven 4 is preview. The [plugin catalog](https://maven.apache.org/plugins/index.html)
confirms the stable Maven 3 plugin lines. The
[JUnit guide](https://docs.junit.org/6.1.3/overview.html) identifies JUnit 6.1.3 and
its Java 17 minimum, compatible with this Java 25 baseline. Jackson 3.2.4 decodes
canonical temporal and query test fixtures. The [Jackson project](https://github.com/FasterXML/jackson-databind)
documents its 3.x `tools.jackson` packages and Java 17 baseline. Its tree model lets
the adapter reject non-integral or out-of-range values before constructing a
transaction. The journal codec uses JDK CRC32C and byte buffers, adding no runtime
dependency. The local adapter also uses only JDK file I/O. All fourteen metadata
sources and official Maven/SDK/JUnit/Jackson compatibility information were rechecked
on November 7: metadata at 07:53:51 EST (12:53:51 UTC) and
compatibility at 07:53:51 EST (12:53:51 UTC). The listed selections remain unchanged; the adapter
uses the same Jackson version only in test scope for the shared temporal oracles. The optional adapter adds AWS SDK 2.57.8, released October 30, as
confirmed by its [official release](https://github.com/aws/aws-sdk-java-v2/releases/tag/2.57.8)
and both fresh Central metadata sources below. The [SDK baseline](https://github.com/aws/aws-sdk-java-v2/blob/2.57.8/README.md)
is Java 8 or later; project tests supply Java 25 compatibility evidence rather
than an AWS certification claim. The segment/manifest codecs and local adapters
continue to use JDK SHA-256/CRC32C and file I/O; the SDK stays in the optional
artifact.

AWS SDK [2.57.11](https://github.com/aws/aws-sdk-java-v2/releases/tag/2.57.11),
released November 6, is now the latest stable version of both optional SDK
artifacts. Their selected version remains 2.57.8 while further S3 work is deferred.
A separately scoped adapter update can adopt and verify the newer SDK; the local
workload runner introduces no cloud dependency or SDK behavior change.

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
| `software.amazon.awssdk:s3` (optional adapter) | 2.57.8 | 2.57.11 | [Central metadata](https://repo.maven.apache.org/maven2/software/amazon/awssdk/s3/maven-metadata.xml) |
| `software.amazon.awssdk:url-connection-client` (optional adapter) | 2.57.8 | 2.57.11 | [Central metadata](https://repo.maven.apache.org/maven2/software/amazon/awssdk/url-connection-client/maven-metadata.xml) |

All selected core/build entries are the latest stable releases found. Newer metadata entries
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

Linux is the primary production target; Windows has a basic compatibility role
under [ADR 0039](../decisions/0039-linux-local-storage-priority.md). The existing
matrix below still runs both full builds as regression coverage. Passing them,
including capability skips, does not qualify durable Windows operation or any
Linux filesystem/device profile. The [Linux storage plan](../architecture/linux-storage.md)
defines the separate qualification and measurement work.

**Java checks** runs on affected pull requests and pushes to `main`, with read-only
repository permissions and credentials removed after checkout. Linux uses the
[qualification harness](linux-storage-qualification.md) to run core `clean install`
on one explicit scratch filesystem, with a test-only fixture factory and isolated
reports. Windows runs `.\mvnw.cmd -B install`. Both select Temurin from
`.java-version`, checking for the latest patch in that Java line. Action versions
are pinned by commit; CI prints the actual runtime. Maven dependencies are cached
by POM content. Changes to the temporal contract, journal format or canonical fixture directory
also trigger Java checks, since they affect executable expectations. No Java
artifact is uploaded or released by this workflow. Both platforms also run fourteen
examples: embedded queries, local journal, pure segment codec, local immutable
files, manifest consistency, local checkpoint publication, local read recovery,
forced local writes, typed temporal queries, pure history compaction, local
compaction publication, reclamation inventory, protected physical removal and
immutable byte-object access with segment history/reuse/verified heap ranges. The
six local checkpoint/recovery/write/compaction/inventory/removal examples
report an unavailable
directory open/force capability
explicitly; capability-dependent real-file tests skip on that specific boundary
while injected checks still run.
Changes under `examples/` trigger these checks.

The Linux job also runs forty Python evidence/cleanup guard tests, prints the
observed profile and suite counts, and retains JSON/XML/log evidence for 14 days.
Its upload-artifact action is pinned to v7.0.2 commit
`cf430e030ddbb5b0abf93d22962f4752f3646cd9`, checked against the
[official release](https://github.com/actions/upload-artifact/releases/tag/v7.0.2)
and action inputs on November 2, 2026. Neither these test artifacts nor the local
Maven install are library releases. Qualification helpers stay outside both the
155-class production archive and the ten-class attached contract-support archive.

Documentation checks and automatic Pages publication retain their own workflows.
For website setup and verification, follow the
[documentation guide](../contributing/documentation.md).
