# Linux baseline acceptance audit — November 7, 2026

This audit reconciles the unchanged original criteria of MDB-0012, MDB-0083,
MDB-0087 and MDB-0089. Queue state remains in [queue.md](queue.md); exact child
outcomes remain in [roadmap.md](roadmap.md). The public technical interpretation is
[Linux workload observations](../../docs/development/linux-observations.md).

## Evidence and identity

- Observation source: `fa0a26df9455e1d30f4c5bdb873a22436d52514f`, clean.
- [Sequential Linux run 42377033037](https://github.com/MeridianDB/meridiandb/actions/runs/42377033037):
  ten ordered cases, forty fresh stores, ten warmups, thirty measured repetitions,
  6,656 original commits across all iterations, complete successful saved reports.
- Durable [raw-data manifest](../../evidence/linux-observations/2026-11-07/manifest.json):
  fifty original/gzip file bindings for requests, receipts, worker results and logs;
  each replayed saved JSON matches its CI analysis SHA256. Ten reports also reproduce
  byte-for-byte from the downloaded artifact without original Linux paths.
- Raw/class fingerprints, exact commands, realized metadata, per-iteration sample
  distributions/counter availability and runtime/mount/cache scope are retained.
  Matching metadata is not hostile-machine authentication or production qualification.
- [Exact-source regression 42377033009](https://github.com/MeridianDB/meridiandb/actions/runs/42377033009)
  checks the unchanged Java implementation separately: 1,351 core cases /74 suites,
  135 Python guards, zero Linux skips/failures; native qualification run
  `ed47c1af7eee4bfea2c0c8e9517eff0e`, inventory SHA256
  `6306733a0f8a223f15a24b5c9cc99bcb209eb208c7cd60a7b4155a38620493c3`.

## Original criteria

| Original requirement | Evidence and bounded interpretation | Acceptance |
| --- | --- | --- |
| Independently scaled, repeatable measurements | TSV fixes four profiles, one-axis E/C/Q/W variants, seed sensitivity and repeated control; each fresh JVM uses warmup 1/measured 3 | Met for bounded hosted observations |
| Dataset seed/size/skew/history/overlap/correction/retraction rates | Exact request/spec, observed intent/effect/operator/entity counts, content/history fingerprints and retained raw samples; profile comparisons do not hold transactions identical | Met |
| Exact source/runtime/hardware/filesystem/cache/memory/ack context | Clean source/class fingerprints, Java/kernel/ext4/mount facts, unknown device/cache properties, explicit uncontrolled cache, 256 MiB heap, unchanged per-new-commit force/acknowledgement | Met with recorded unknowns; production gap stays MDB-0080 |
| Point/history/interval/aggregate and mixed workloads | All five query operators, head/historical cutoffs, independent expected answers and synchronous interleaved writes/queries; no offered-load queue or concurrent ingestion metric | Met for specified synchronous workload |
| Historical and retry identity preservation | Every corpus lifecycle checks original requests/records and prepared historical answers across writable reopen, checkpoint and old/new readers; exact retries remain separate from no-op commits | Met |
| Latency distributions and throughput | Every measured repetition retains raw service samples and nearest-rank distributions plus service and complete-loop rates; p50/p99 tables remain per repetition | Met with closed-loop/small-group/host variation limits |
| CPU/allocation/heap | Optional platform-thread deltas with availability counts; sampled used-heap gauges and allocation-depth finding; no process-wide CPU, retained heap, RSS or peak claim | Met within documented counter scope |
| Encoded/allocated storage and bytes read | Exact encoded history, retained logical files, Linux block attribution, seven process-I/O counters/deltas and explicit unknowns | Met for these named scopes; no exclusive-extents/device claim |
| Read/write amplification attributable to operations | Whole-process counters include validation/maintenance/runtime and cannot isolate commit/query/recovery file work; final lengths cannot establish repeated I/O | **Unmet: required MDB-0090** |
| Replay/recovery, checkpoint and compaction behavior | Journal-only writable reopen and full-prefix checkpoint reopen include acquisition/verification/reconstruction/startup; split writes, checkpoint publication and compaction with one pinned old reader measured | Met with no isolated pure-replay or checkpoint-tail-speedup claim |
| Pinned readers, lag and merge backlog | One old reader remains pinned through verified compaction publication; current runner is synchronous with no asynchronous lag/backlog | Met as explicit supported scope; no fabricated asynchronous metric |
| Failure, partial/stale evidence and bounded execution | Existing 135 Python guards cover source/class/request drift, partial/mixed/failed evidence, process cleanup, retention, raw-summary/accounting recomputation and no-original-path readback; native correctness remains complete | Met |
| Bounded defaults and explicit environment/budget | Existing 16/128/64/8 default, admitted dimensions/actions/reference work, one sequential hosted job, 90-second per-supervisor bound/20-minute ceiling, per-case retained-output caps | Met; no paid/privileged operation used |
| Separate CI/observations/controlled-device measurements | Separate smoke/regression and observational jobs; controls show material run-order variation; cold cache, sustained arrival and actual device qualification remain unmeasured | Met as an explicit distinction; production gaps stay MDB-0080 |
| Bottleneck findings and next experiments without required gain | History depth raises mixed-commit allocation about 3.1× and checkpoint-reopen allocation about 11.3× for 4× commits; map-copy/full-history reconstruction is a plausible mechanism, not isolated attribution | Met; first add attributable file work, then profile a bounded allocation/layout hypothesis |

## Completion decision

MDB-0089's explicit audit/prerequisite criterion passes with delivered public
results and durable evidence at `f65ccbd692876b858dbbcdeab30510dd0bc7d699`:
documentation 42377997373 and automatic Pages 42377997336 pass, with live results,
canonicals, search and sitemap verified. MDB-0087 has both required children
complete and is done. Keep MDB-0083 and MDB-0012 **expanded**: required child
MDB-0090 must establish operation-attributed file-work counters and useful named
amplification denominators before integrated baseline completion. Preserve all
original parent criteria rather than relabeling process ratios or accepting zero
device-read bytes as zero engine reads.

MDB-0090 measures actual completed bytes and force calls with literal independent
checks and bounded counter/lifetime behavior in separately instrumented runs.
It preserves original integrity, ownership and acknowledgement, with no native
accelerator, cloud integration, paid environment or privileged cache prerequisite.
Physical-device/cache/cold-arrival qualification remains separately owned by
MDB-0080; future layout experiments still depend on completed MDB-0012.

## Counter foundation checkpoint — November 7, 2026

MDB-0090 is expanded into required MDB-0091 counter foundation, MDB-0092 attributed
workload/result integration and MDB-0093 actual Linux evaluation/integrated
acceptance. MDB-0091 source `f8b8cf4fd62a7d10845d4592781fbdd14c0813c5` adds inert
internal native completed-byte/force counters and independent literal/failure/
lifecycle checks. Exact-source Java 42380298307 verifies 1,379 core cases /76
suites and 135 Python guards with zero Linux skips/failures; public guide/ADR and
automatic Pages delivery are verified. This foundation supplies no operation
labels, raw attributed workload result or amplification measurement. The unmet
original requirement above remains **unmet** pending MDB-0092/0093; original
observational data and accepted criteria are unchanged.

## Attributed lifecycle checkpoint — November 7, 2026

MDB-0092 is expanded into required MDB-0094 Java lifecycle attribution and
MDB-0095 serialized worker/supervisor/saved-reader integration. MDB-0094 source
`91ae1bc4acedc86708cc7663814260b607ac06ed` now supplies explicit operation and
auxiliary scopes, typed raw counters, incomplete/excluded coverage and original
logical-history denominators. Exact-source Java 42382641654 verifies 1,413 core
cases /79 suites and 135 Python guards with zero Linux skips/failures, including
independent literal native lifecycle and ownership-failure checks. Guide/ADR0046
and automatic Pages/live delivery pass. This is an in-process correctness
foundation; source-bound serialized admission, independent named-ratio reporting
and actual evaluated attributed runs remain MDB-0095/0093. The original
operation-attributed amplification criterion above remains **unmet** until that
integration and evaluation pass. Preserve original observational data and all
expanded ancestors' original criteria.

## Serialized attribution integration checkpoint — November 7, 2026

MDB-0095 source `62803041ee581f12fd4cce170821b2c715f80a0b` completes explicit
schema-three worker/supervisor/saved-reader integration. Independently decoded,
CRC32C-checked original header/frame bytes bind exact original-history hashes and
logical denominators. Ordered operation/auxiliary scopes, raw role counters,
aggregate sums and named ratio pairs are recomputed; incomplete/failed/unknown/
overflow evidence is refused. Zero denominators are N/A and excluded/uncaptured
work remains separate. Ordinary results and the original ten-case dataset stay
unchanged.

Exact-source Java 42385846700 passes 1,422 core cases /80 suites and 183 Python
guards with zero Linux skips/failures. Two ordinary plus two attributed native
supervised cases and all four saved-report readbacks pass, including warmup/
measured separation and the maximum admitted trace. Documentation/ADR 0047,
automatic Pages and live verification pass. Both required MDB-0092 children and
all original integration criteria pass; MDB-0095 and MDB-0092 are done.

The original operation-attributed amplification criterion remains **unmet** until
MDB-0093 evaluates useful bounded Linux profiles/contrasts, retains durable raw
evidence and audits all original criteria. Temporary CI smoke is regression and
source-admission evidence, not that evaluation. Keep MDB-0090/0083/0012 expanded;
preserve their original criteria and historical findings. Device/controller/cache,
cold-cache, sustained-arrival and production qualification gaps remain MDB-0080.
