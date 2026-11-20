---
title: Recorded recovery worker evidence
description: Validate recorded MeridianDB recovery requests, binary inputs, ordered comparison results and scoped native counters against independent expectations.
---

# Recorded recovery worker evidence

The Python consumer checks a complete version-one recovery comparison worker
document against a caller's expected request, worker PID and runtime facts.
It combines independent binary image checks and seeded corpus reconstruction
with validation of the complete result matrix. The validation returns
recorded evidence for later comparison. Live source, process and filesystem
verification belong to a separate supervisor.

## Validate a recorded worker

Add `scripts/storage` to the importing program's module search path. Supply the
exact request constructed for the invocation, the observed PID and at least one
independently available runtime field. For a complete runtime comparison, supply
all nine runtime fields.

```python
from recovery_worker_evidence import MAX_DOCUMENT_BYTES, validate_worker

with worker_output.open("rb") as stream:
    raw = stream.read(MAX_DOCUMENT_BYTES + 1)

facts = validate_worker(
    raw,
    expected_request,
    worker_pid=observed_pid,
    expected_runtime=observed_runtime,
)
```

Use a known run-owned output for this example. A caller managing a live worker
also checks successful process exit, settles its process group, confirms the
output's identity and inspects the actual files. The pure consumer uses POSIX
paths lexically and never reads paths from the document. It reports the names
of runtime fields it compared, so partial observations remain explicit.

The reader limits the document to 16 MiB of UTF-8, limits depth and node count,
and rejects duplicate keys and numbers outside signed integer bounds. Ordinary
strings have a 4,096 UTF-16 unit cap. The specified binary image fields permit
longer lowercase hex within their own byte and aggregate limits. The existing
workload readers keep their original limits.

## Read the accepted evidence

The report carries the raw document hash and length, the matched request, the
checked runtime field names, independently derived image and corpus facts, the
accepted result and logical byte denominators for each observation. It retains
warmup and measured strategy orders separately.

Every iteration must contain all 16 observations in order. Each storage shape
has read-only and writable scenarios, with both recovery strategies in each.
The warmup and measured sequences start their alternating strategy orders
independently. Initial file hashes and selected checkpoint byte counts derive
from the images. Closed writable journals include the exact continuation frame,
and closed read-only journals match the original history.

| Recorded scope | Validation |
| --- | --- |
| Open | Journal replay, selected prefix and checkpoint reads, plus the applicable startup forces |
| Original retry | The exact retry operation and its recorded adapter scope |
| Continuation | The expected appended frame and journal force |
| Continuation retry | The continuation retry operation and its recorded adapter scope |

Captured scopes carry complete native matrices. The validator checks successful
transfer and force arithmetic, role boundaries and deterministically expected
bytes, EOF probes and force successes. Successful transfer call counts can vary
with partial or zero returns. Control scopes carry absent counters. Optional
JVM CPU and allocation readings can also be absent. The report preserves raw clocks and counts so
consumers can compare like cohorts and choose the relevant logical denominator.

Adapter counters describe the recording scope around the call. Device traffic,
cache state, peak or retained heap and source provenance require separate evidence.

## Run the Linux compatibility checks

Compile test support and use a trusted scratch parent on the target filesystem.

```bash
./mvnw -B test-compile
python3 scripts/storage/verify_recovery_images.py --worker \
  --scratch-parent /absolute/existing/scratch \
  --source "$(git rev-parse HEAD)" --timeout 180
```

The four fixed cases cover balanced measured pairs following a warmup in both
modes, a 496-commit history and the seven-iteration matrix. The smoke binds its
own process PID, request and observed Linux OS, kernel, architecture and
filesystem. Its schema-three receipt uses `worker_checks_passed` and retains
the checked expectations, worker output and known failure evidence. GitHub
Actions retains the receipts and fixtures for 14 days. Source identity in this
compatibility receipt comes from the command argument. A production evaluation
requires source and class verification plus live and saved report admission.
