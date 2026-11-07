---
title: Supervised Linux workload evidence
description: Run checked local Java workloads under a bounded Linux supervisor with fresh compilation, source and runtime bindings, strict result validation and scoped file/process accounting.
---

# Supervised Linux workload evidence

The Linux command supervises the [local operation measurement core](local-measurements.md),
retains raw results and admits them only after process settlement and consistency
checks. It binds the requested corpus, source, compiled classes, JVM and filesystem
placement to one fresh run. It supplies developer observations, with no published
comparative baseline or production qualification.

The [Linux observation protocol](linux-observations.md) defines a separate
sequential evaluation across temporal profiles and independently varied dimensions.

[ADR 0043](../decisions/0043-supervised-workload-evidence.md) explains the evidence
boundary. The [Linux qualification harness](linux-storage-qualification.md) remains
the complete correctness gate; this command adds a bounded workload execution.

## Run on Linux

Use Linux, Python 3.13, JDK 25 and the tracked Maven Wrapper. From the repository
root, prepare a trusted stable scratch directory on the intended filesystem:

```bash
mkdir -p .local-qa
```

```bash
python3 scripts/storage/run_workload.py --scratch-parent .local-qa
```

The supervisor synchronizes new directory names outside operation clocks. The
scratch directory and its ancestors must stay stable and permit directory sync.
The fresh run has private names; a previous run is never reused or swept.
Choose scratch outside the checkout or under its ignored `.local-qa/` directory,
so retained evidence does not change the source fingerprint during execution.

An explicitly smaller correctness smoke uses:

```bash
python3 scripts/storage/run_workload.py \
  --scratch-parent .local-qa \
  --profile CORRECTIONS \
  --seed 42 \
  --warmups 0 \
  --repetitions 1 \
  --timeout 300
```

Defaults are `CORRECTIONS`, seed 42, one warmup, three measured repetitions and
300 seconds. Other profiles are `APPEND`, `RETRACTIONS` and `HOT_ENTITY`; all use
the corpus's small 16-entity, 128-commit, 64-read defaults. Seeds are signed 64-bit
integers. Warmups are 0–2, measured repetitions 1–5, timeout 30–600 seconds.
These are development workloads, not a production sizing plan.

### Change dimensions independently

The named profile selects intent probabilities. Four options independently select
entity count, new commits, reads and finite interval width:

```bash
python3 scripts/storage/run_workload.py \
  --scratch-parent .local-qa \
  --profile CORRECTIONS \
  --entities 64 \
  --commits 512 \
  --queries 256 \
  --interval-width 8
```

| Option | Default | Admission |
| --- | --- | --- |
| `--entities` | 16 | 1–1,024, strictly fewer than commits |
| `--commits` | 128 | More than entities, at most 4,096 |
| `--queries` | 64 | 0–1,024; zero permits a write/maintenance run |
| `--interval-width` | 8 | 1–16 discrete valid-time coordinates per generated window |

All runs also satisfy the corpus's [reference-work bound](workload-corpus.md#bounds-and-conformance).
Before scratch creation, the command admits at most 20,000 conservative actions
across warmups and measured repetitions:
`(2 * commits + queries) * (warmups + repetitions) <= 20000`.
The factor two reserves one possible retry per commit, so this can reject a
configuration whose particular seed would perform fewer actions. These limits
bound preparation and execution scope; they do not guarantee a runtime or heap size.

Read operators cycle through Get, Scan, Select, FilterScan and Sum. Counts follow
the selected read count, including zero-count operators. Initial history segments
contain up to sixteen commits, with at least two source segments; a final partial
group is retained. Validation and file inventories follow those admitted dimensions.
Defaults preserve their existing schedule/history identities. Changing reads alone
preserves original write history; changing entity count, commits or width can change
that history. Record the complete specification and both fingerprints for comparison.

The command always runs `clean test-compile` before launching the worker with an
explicit Java executable, core/test classpath and `-Xmx256m`. It removes ambient
JVM/classpath/Maven/debug/native-loader overrides and skips Maven RC files;
`JAVA_HOME`/`PATH` still select the JDK. Fresh compilation avoids orphan classes.
Both source and actual classpath inputs are fingerprinted and must remain unchanged.
Avoid editing the checkout or running another build until it finishes.

## Read the retained files

The command prints a compact `WORKLOAD_SUMMARY`, run directory and report path.
The summary preserves admission and accounting facts in CI logs; raw samples stay
in the retained files. It returns zero only for
`status: checks_passed` after all admission checks. Exit three preserves an
explicitly unsupported worker capability; other nonzero outcomes are failures.
A final worker JSON file alone is insufficient evidence of success.

| File or directory | Meaning |
| --- | --- |
| `request.json` | Fresh run ID, exact source commit and requested profile/seed/repetition configuration |
| `build.log` / `worker.log` | Bounded combined stdout/stderr for the separate compilation and worker processes |
| `worker.json` | Versioned envelope with typed measurement results and raw per-iteration process-I/O observations |
| `worker.partial.json` | Interrupted/failed export evidence; never admitted as a complete result |
| `report.json` | Supervisor outcome, source/class/runtime/environment bindings, process outcomes and independently checked accounting |
| `stores/` | Separate retained warmup and measured databases, including old generations and obsolete segments |
| `tmp/` | Worker JVM temporary placement inside the same owned run tree |

The final report identifies clean or dirty source, a content fingerprint of actual
tracked/unignored inputs, compiled classpath identity, kernel/architecture, mount,
recognized options and exposed device facts. Unknown backing device/controller/
cache behavior remains unknown. Dirty local results are labeled; clean CI is a
different provenance fact, not a production or hardware guarantee.

New command runs use request/report and worker-envelope schema version two. The
request and worker envelope bind an exact `dimensions` object containing
`entities`, `commits`, `queries` and `intervalWidth`, including failure and unsupported
outcomes. The nested measurement result and corpus remain version one. Legacy
eight-argument worker invocations still produce the original version-one envelope
with fixed defaults; the validator continues to admit those exact legacy results.
Version one cannot carry scaled dimensions, and versions cannot be mixed within
one requested run.

The supervisor rejects wrong run/source/profile/seed/PID/configuration, missing or
extra fields, duplicate JSON keys, excessive nesting/size, invalid numeric values,
stale classes and inconsistent metrics. It recomputes counts, nearest-rank summaries,
counter availability and rates from raw samples. It also rechecks every final
file name/length and the closed journal's exact length/SHA-256. Returned paths must
stay in the fresh store tree, with no symlink, nonregular or duplicate-inode surprise.
This is consistency checking in a trusted checkout, not hostile-machine attestation.

The [saved-report command](workload-reports.md) recomputes the serialized evidence
and renders per-iteration metrics later. It checks the recorded receipt and worker
bytes without repeating the original native observations or requiring those paths
to remain present.

## Interpret storage and I/O separately

| Observation | Scope and limit |
| --- | --- |
| Encoded journal bytes | Exact logical history image for one corpus, with original commits and no duplicate retry frames |
| Named file lengths | Logical lengths of retained journal, manifests, original/replacement segments and control files |
| Attributed allocated bytes | Linux `st_blocks * 512` snapshots after process settlement; not `st_blksize`, exclusive physical extents or reclaimed space |
| Process I/O counters | `/proc/self/io` snapshots around each whole instrumented iteration, with warmup/index/directory tags |
| Process read/write-byte ratios | Process-accounted bytes divided by that iteration's encoded journal length; these are named ratios rather than hardware or query amplification |

Each process-I/O bracket starts after the fresh iteration directory exists and
before namespace/control preflight. It ends after the local owners close and
iteration checks/statistics complete. It includes runtime activity, validation,
recovery, checkpoint/compaction and observation overhead, while excluding corpus
generation/oracle setup and final JSON export. It is wider than the per-operation
service clocks. Reading the counters can itself affect character/read-call counts.

The seven raw fields are `rchar`, `wchar`, `syscr`, `syscw`, `read_bytes`,
`write_bytes` and `cancelled_write_bytes`. Retain before/after/delta values;
missing, disabled, inaccessible, decreasing or excessive counters remain unavailable
with a reason. These counters are process-wide, may include waited-for children
and are not an atomic snapshot. Character counts include cache-satisfied I/O;
storage accounting does not prove device transfer or stable-media completion.
Keep cancellation separate rather than inventing a net device-write figure.

Allocation snapshots can include metadata attribution and shared extents. Duplicate
inode references are refused here; reflink sharing and storage compression still
prevent treating the sum as exclusive physical space. The runner retains obsolete
objects and performs no reclamation, so no space-reclamation result follows.

Primary references checked **November 3, 2026**: Linux
[process I/O](https://man7.org/linux/man-pages/man5/proc_pid_io.5.html),
[kernel proc accounting](https://docs.kernel.org/filesystems/proc.html),
[stat](https://man7.org/linux/man-pages/man2/stat.2.html) and
[process-group signals](https://man7.org/linux/man-pages/man2/kill.2.html).

## Limits and failure handling

Compilation and the worker share one monotonic deadline, with reserved bounded
cleanup. Each runs in a separate private process group. Timeout, signal, nonzero
exit or output overflow triggers cleanup; even a zero-exit leader is followed by
group settlement before file inspection. Escalation and direct-child reaping are
attempted even if process enumeration fails. A surviving group or unverifiable
cleanup fails admission. This contains cooperating group members, not arbitrary
escaped sessions or a hostile workload. It is not a cgroup/security sandbox.

Logs are capped at 2 MiB each, worker JSON at 16 MiB, source/class snapshots at
64 MiB and 4,096 files each, and retained run output at 160 MiB with a final-report
reserve. Store-file accounting has its own lower aggregate limit. Native storage
calls, kernel scheduling and parent filesystem operations are not universal
hard-real-time guarantees; actual elapsed time and deadline exhaustion are recorded.
The limits cover run-owned output, not the Maven download cache. Failed evidence
is retained for inspection, without publishing a successful partial result.

The Java checks workflow runs default and independently scaled correctness smokes
on Linux after ordinary correctness and example checks. It retains both fresh
result trees in `linux-workload-evidence` for 14 days, separately from
the full-suite qualification artifact. Windows retains its ordinary compatibility
build; this supervisor requires Linux. CI execution proves the checked workload
ran on that observed placement. Controlled baseline evaluation and production
mount/device/power-loss qualification remain separate work.
