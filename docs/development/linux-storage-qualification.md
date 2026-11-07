---
title: Linux storage qualification harness
description: Run MeridianDB's local-core tests on one observed Linux filesystem with fresh reports, explicit native capabilities, exact fixture binding and retained environment evidence.
---

# Linux storage qualification harness

The development harness runs the complete local-core test inventory on one
explicit Linux scratch filesystem. It records the actual kernel, architecture,
Java runtime, mount and exposed device-queue facts, then checks every required
test result. A successful report means those checks passed on that placement;
it does not establish production readiness or device power-loss durability.

[ADR 0040](../decisions/0040-linux-qualification-evidence.md) defines the evidence
boundary. The [Linux storage design](../architecture/linux-storage.md) separates
this correctness work from future performance and production qualification.
S3, Kafka and node replication are outside this harness.

## Run on the filesystem being evaluated

Use actual Linux, JDK 25, Python 3.13 and the tracked Maven Wrapper. From the
repository root, select an existing trusted stable directory on the intended
filesystem. For a repository-local development run:

```bash
mkdir -p .local-qa
```

```bash
python3 scripts/storage/qualify_linux.py --scratch-parent .local-qa --label linux-local-development-v1
```

Choose a different scratch parent when the repository is on a different filesystem
from the intended deployment. The harness creates a fresh private `mdb-linux-*`
child and retains it. It never reuses or deletes the scratch parent or older runs.
Maven clean still replaces ordinary repository build output under `target/`.
Keep enough free space for compilation, Maven installation and the bounded test
fixtures. The timeout defaults to 360 seconds and accepts 30–600 seconds; a slow
machine can select a longer value with `--timeout 600`.

The command executes core `clean install`, resolving the pinned build/test
dependencies as necessary and installing only into the local Maven cache. It does
not build the optional S3 artifact, contact a storage provider or publish a library.
Non-Linux hosts are rejected before a run directory is created. Local macOS
verification of the test wiring is separate evidence, not a Linux run.

## What the evidence covers

| Evidence | Mechanism and interpretation |
| --- | --- |
| Actual storage placement | Match the real scratch path and `st_dev` to the longest unambiguous mount entry in `/proc/self/mountinfo`; ambiguous stacked mounts fail |
| Required native capabilities | Probe the production directory-sync check, file/directory force, nonnull file identity, actual shared and exclusive lock modes, atomic move and identity after move |
| Fixture location | An opt-in JUnit temporary-directory factory creates all temporary fixtures below the profiled root; the probe checks its actual parent, and every XML report must carry the same root, run ID and factory |
| Java runtime | Record version/vendor/runtime/architecture and require consistent Java 25 evidence in the probe and all suite reports |
| Local persistence behavior | Reuse create/forced-commit, uncertainty, injected ordinary-I/O/unsupported capability, crash/reopen, checkpoint, reader protection and reclamation suites |
| Temporal behavior | Retain canonical expectations, persisted random seeds, original request identity and all supported historical answers |

Current core fixtures use JUnit temporary directories. Their child-JVM tests receive
those fixture paths explicitly. `java.io.tmpdir` is also set at fork startup so
temporary allocations do not silently revert to another filesystem. Reports and
logs live beside the fixture root, outside fresh database namespaces.

The reviewed `scripts/storage/required-suites.json` inventory currently requires
**76 suites and 1,379 test invocations**. Every suite and invocation must be present
with coherent counters and zero failures, errors or skips. A capability skip that
is acceptable in an ordinary portability build fails this Linux evidence gate.
Ordinary I/O failures also fail; they are not translated into unsupported capability.

The harness rejects missing, duplicate, unexpected, oversized or malformed reports,
wrong run/root bindings, mixed runtimes and incomplete capability evidence. XML
must be UTF-8 without DTD/entity declarations. Flaky/rerun outcomes are rejected;
Maven retries are disabled for this run. A nonzero Maven exit remains failure even
if some XML files look successful. Timeout, interruption and nonzero leader exit
trigger bounded cleanup of the dedicated Maven/child-JVM process group.

## Read and retain a run

Each run contains:

- `profile.json`: versioned status, source commit/dirty flag, exact command,
  environment/probe facts and every observed testcase outcome on success.
- `java-profile.json`: actual Java filesystem and capability observations.
- `reports/`: fresh Surefire XML evidence, never reused from `target/`.
- `maven.log`: complete build/test output; failures also print a bounded log tail.
- `fixtures/`: run-owned temporary storage, subject to normal JUnit cleanup.

Exit zero and `status: checks_passed` require complete evidence.
`productionQualified` remains `false`. Failed runs retain their diagnostic files;
an interrupted machine may leave partial evidence that does not count as success.
The run ID and exact paths provide consistency checks within a trusted checkout,
not cryptographic attestation against a malicious report writer.

Mount reporting includes recognized durability/access options and a count of
unreported options. It omits raw overlay lowerdirs, network sources, hostnames,
credentials and device serials. Missing device/cache metadata is explicitly
unknown; exposed virtual/partition queue facts do not qualify the backing device,
controller or storage firmware. A hosted runner, container, overlay or memory
filesystem must not be promoted to a physical-disk guarantee.

The [Java checks workflow](https://github.com/MeridianDB/meridiandb/actions/workflows/java-checks.yml)
uses label `github-ubuntu-java25-local-v1` on Linux. Each run supplies its actual
filesystem and runtime fields instead of assuming ext4 or XFS. The job logs print
the profile summary and every suite count; the `linux-storage-evidence` test
artifact retains JSON, XML and the Maven log for 14 days. These are test evidence,
separate from library release artifacts. Windows retains its ordinary build and
basic compatibility role without this Linux gate.

## Maintain and extend qualification

Run the driver/parser guards separately:

```bash
python3 -m unittest discover -s scripts/storage/tests -p 'test_*.py'
```

When a reviewed core-test change adds or removes invocations, reconcile the
inventory against a complete successful run. Preserve meaningful expected coverage;
do not lower counts to hide a failure or capability skip. The test-only factory and
probe remain outside production and the attached contract-support classifier.
The POM's `meridiandb.test.reportsDirectory` property explicitly controls Surefire
reports; its default remains `target/surefire-reports` for ordinary builds.

Remaining production work includes named filesystem/device qualification,
controlled workload measurements, aggregate resource bounds, operational lifecycle
and checkpoint management, backup/restore and device-failure evidence. This harness
does not select a native binding, optimized file format, filesystem-wide support
promise or distributed acknowledgement mode.

Primary references checked **November 2, 2026**: Linux
[mountinfo](https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html),
[block-device sysfs ABI](https://github.com/torvalds/linux/blob/master/Documentation/ABI/stable/sysfs-block),
[file/directory synchronization](https://man7.org/linux/man-pages/man2/fsync.2.html),
JUnit's [temporary-directory factory](https://docs.junit.org/6.1.3/api/org.junit.jupiter.api/org/junit/jupiter/api/io/TempDirFactory.html)
and Surefire's [report/JVM configuration](https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html).
