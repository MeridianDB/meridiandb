#!/usr/bin/env python3
"""Check and report saved workload records without reobserving their original environment."""

import argparse
from datetime import datetime, timezone
import hashlib
import html
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys

from workload_evidence import EvidenceError, MAX_DOCUMENT_BYTES, MAX_RETAINED_BYTES, parse_document, validate_recorded_worker
from attributed_workload import MODE as ATTRIBUTED_MODE


MAX_INPUT_BYTES = 2 * MAX_DOCUMENT_BYTES + 65536
MAX_OUTPUT_BYTES = 4 * 1024 * 1024
SCOPE = "Saved-record consistency only; original database files, processes, source and hardware are not reobserved"
REPORT_FIELDS = {
    "schemaVersion", "runId", "status", "productionQualified", "observedAtUtc", "request", "runRoot",
    "kernel", "architecture", "cache", "heapLimitBytes", "evidenceScope", "gaps", "source", "mount",
    "device", "buildCommand", "build", "classes", "workerCommand", "worker", "workerResultSha256",
    "workerResultBytes", "accounting", "workload", "retainedBytesBeforeReport", "reservedReportBytes",
    "elapsedSecondsBeforeReport", "deadlineExceededBeforeReport",
}
WORKLOAD_COPY = ("schemaVersion", "corpusVersion", "spec", "config", "fingerprint", "historyFingerprint",
                 "encodedHistoryBytes", "runtime", "workload")


def require(condition, message):
    if not condition:
        raise EvidenceError(message)


def fields(value, expected, name):
    require(type(value) is dict and set(value) == set(expected), f"Wrong {name} fields")
    return value


def integer(value, name, low=0, high=(1 << 63) - 1):
    require(type(value) is int and low <= value <= high, f"Invalid {name}")
    return value


def text(value, name, limit=4096):
    require(type(value) is str and bool(value.strip()) and len(value) <= limit
            and not any(ord(c) < 32 or ord(c) == 127 for c in value), f"Invalid {name}")
    return value


def digest(value, name):
    require(type(value) is str and re.fullmatch(r"[0-9a-f]{64}", value) is not None, f"Invalid {name}")


def same(left, right):
    """JSON booleans, integer counts and floating ratios retain their distinct types."""
    if type(left) is not type(right):
        return False
    if type(left) is dict:
        return set(left) == set(right) and all(same(left[k], right[k]) for k in left)
    if type(left) is list:
        return len(left) == len(right) and all(same(a, b) for a, b in zip(left, right))
    return left == right


def recorded_path(value, name):
    text(value, name)
    path = PurePosixPath(value)
    require(path.is_absolute() and str(path) == value and ".." not in path.parts, f"Invalid recorded {name}")
    return path


def read_input(path, maximum):
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= maximum, "Input must be a bounded ordinary file")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        opened = os.fstat(source.fileno())
        require(stat.S_ISREG(opened.st_mode) and (opened.st_dev, opened.st_ino) == (before.st_dev, before.st_ino),
                "Input changed before reading")
        data = source.read(maximum + 1)
        after = os.fstat(source.fileno())
    require(len(data) == before.st_size <= maximum and (after.st_size, after.st_mtime_ns)
            == (before.st_size, before.st_mtime_ns), "Input changed or exceeded its byte admission")
    return data


def snapshot(value, name):
    fields(value, {"sha256", "files", "bytes"}, name)
    digest(value["sha256"], name)
    integer(value["files"], f"{name} file count", 1, 4096)
    integer(value["bytes"], f"{name} bytes", 0, 64 * 1024 * 1024)


def process(value, name):
    fields(value, {"pid", "startTicks", "exitCode", "logBytes", "groupSettled"}, name)
    integer(value["pid"], f"{name} PID", 1)
    integer(value["startTicks"], f"{name} start", 1)
    integer(value["exitCode"], f"{name} exit", 0, 0)
    integer(value["logBytes"], f"{name} log", 0, 2 * 1024 * 1024)
    require(value["groupSettled"] is True, f"{name} group was not reported settled")


def strings(value, name, maximum=32, item_limit=2048):
    require(type(value) is list and 1 <= len(value) <= maximum, f"Invalid {name}")
    for item in value:
        text(item, name, item_limit)
    require(len(set(value)) == len(value), f"Duplicate {name}")


def summarize(run_dir, expected_source=None, expected_run=None):
    """Return checked recorded data; this never launches code or opens original store paths."""
    data = {name: read_input(Path(run_dir) / name, 65536 if name == "request.json" else MAX_DOCUMENT_BYTES)
            for name in ("request.json", "report.json", "worker.json")}
    require(sum(map(len, data.values())) <= MAX_INPUT_BYTES, "Aggregate input admission exceeded")
    request, report, worker = (parse_document(data[name]) for name in ("request.json", "report.json", "worker.json"))
    attributed = request.get("schemaVersion") == 3
    fields(report, REPORT_FIELDS | ({"mode", "attribution"} if attributed else set()), "successful report")
    version = 3 if attributed else 2
    require(type(report["schemaVersion"]) is int and report["schemaVersion"] == version
            and type(request.get("schemaVersion")) is int and request["schemaVersion"] == version,
            "Saved reports require coherent schema two or attributed schema three")
    if attributed:
        require(report["mode"] == request.get("mode") == ATTRIBUTED_MODE, "Wrong recorded attributed mode")
    require(report["status"] == "checks_passed" and report["productionQualified"] is False
            and report["deadlineExceededBeforeReport"] is False, "Report is not a complete admitted observation")
    require(same(report["request"], request) and report["runId"] == request.get("runId"), "Mixed request/report")
    if expected_source is not None:
        require(type(expected_source) is str and re.fullmatch(r"[0-9a-f]{40}", expected_source)
                and request.get("source") == expected_source, "Source differs from the selected revision")
    if expected_run is not None:
        require(type(expected_run) is str and re.fullmatch(r"[0-9a-f]{32}", expected_run)
                and request.get("runId") == expected_run, "Run differs from the selected observation")
    digest(report["workerResultSha256"], "worker result hash")
    integer(report["workerResultBytes"], "worker result bytes", 1, MAX_DOCUMENT_BYTES)
    require(report["workerResultBytes"] == len(data["worker.json"])
            and report["workerResultSha256"] == hashlib.sha256(data["worker.json"]).hexdigest(), "Worker bytes differ from report")
    root = recorded_path(report["runRoot"], "run root")
    require(root.name.startswith("mdb-measure-"), "Wrong recorded supervisor namespace")
    text(report["kernel"], "kernel", 512)
    text(report["architecture"], "architecture", 512)
    text(report["cache"], "cache policy", 2048)
    text(report["evidenceScope"], "producer evidence scope", 2048)
    strings(report["gaps"], "producer gaps")
    integer(report["heapLimitBytes"], "heap limit", 256 * 1024 * 1024, 256 * 1024 * 1024)
    elapsed = report["elapsedSecondsBeforeReport"]
    require(type(elapsed) in (int, float) and math.isfinite(elapsed) and 0 <= elapsed <= 600, "Invalid recorded elapsed time")
    observed_at = datetime.fromisoformat(text(report["observedAtUtc"], "observation date", 64))
    require(observed_at.tzinfo is not None and observed_at.utcoffset() == timezone.utc.utcoffset(observed_at),
            "Observation date must be UTC")
    source = fields(report["source"], {"commit", "dirty", "statusSha256", "tree"}, "source snapshot")
    require(source["commit"] == request.get("source") and type(source["dirty"]) is bool, "Wrong source binding")
    digest(source["statusSha256"], "source status hash")
    require(source["dirty"] == (source["statusSha256"] != hashlib.sha256(b"").hexdigest()), "Wrong source status binding")
    snapshot(source["tree"], "source tree")
    snapshot(report["classes"], "compiled classpath")
    process(report["build"], "build")
    process(report["worker"], "worker")
    require(report["build"]["pid"] != report["worker"]["pid"], "Build and worker share one process identity")
    build = report["buildCommand"]
    require(type(build) is list and len(build) == 4 and build[1:] == ["-B", "clean", "test-compile"], "Wrong recorded clean build")
    wrapper = recorded_path(build[0], "Maven wrapper")
    require(wrapper.name == "mvnw", "Wrong recorded wrapper executable")
    command = report["workerCommand"]
    require(type(command) is list and len(command) == (19 if attributed else 18), "Wrong recorded worker command")
    executable = recorded_path(command[0], "Java executable")
    require(executable.name == "java" and executable.parent.name == "bin", "Wrong recorded Java executable")
    expected_command = [command[0], "-Xmx256m", f"-Djava.io.tmpdir={root}/tmp", "--class-path",
                        f"{wrapper.parent}/target/classes:{wrapper.parent}/target/test-classes",
                        "org.meridiandb.workload.WorkloadWorker", *(["--attributed"] if attributed else []),
                        request.get("runId"), request.get("source"),
                        request.get("profile"), str(request.get("seed")), str(request.get("warmups")), str(request.get("repetitions")),
                        f"{root}/stores", f"{root}/worker.json"]
    dimensions = request.get("dimensions")
    require(type(dimensions) is dict, "Missing dimension binding")
    expected_command += [str(dimensions.get(k)) for k in ("entities", "commits", "queries", "intervalWidth")]
    require(same(command, expected_command), "Worker command differs from requested inputs")
    mount = fields(report["mount"], {"mountId", "majorMinor", "mountPoint", "fileSystem", "recognizedOptions", "unreportedOptionCount"}, "mount")
    text(mount["mountId"], "mount ID", 32)
    require(re.fullmatch(r"[0-9]+:[0-9]+", text(mount["majorMinor"], "device ID", 64)), "Invalid recorded device ID")
    mount_point = recorded_path(mount["mountPoint"], "mount point")
    require((root / "stores").is_relative_to(mount_point), "Recorded stores are outside the recorded mount")
    text(mount["fileSystem"], "filesystem", 512)
    strings(mount["recognizedOptions"], "mount options", 64, 256)
    integer(mount["unreportedOptionCount"], "omitted mount options", 0, 10000)
    device = fields(report["device"], {"writeCache", "logicalBlockBytes", "physicalBlockBytes", "rotational", "backingDeviceAndController"}, "device")
    for key, value in device.items():
        if value is not None:
            text(value, f"recorded device {key}", 512)
    text(device["backingDeviceAndController"], "device/controller limit", 512)
    checked = validate_recorded_worker(worker, request, report["worker"]["pid"], Path(str(root / "stores")),
                                       mount, report["kernel"], report["architecture"])
    if attributed:
        require(same(report["attribution"], checked["attribution"]), "Recorded attribution differs from independently checked raw scopes")
    result = worker["result"]
    known_nanos = result["preparationNanos"] + sum(
        item["namespacePreparationNanos"] + item["actionLoopNanos"]
        + item["totalValidationNanos"] - item["actionValidationNanos"]
        + sum(sample["elapsedNanos"] for sample in item["samples"]
              if sample["phase"] == "MAINTENANCE" or sample["kind"] == "CREATE")
        for item in result["iterations"])
    # The producer rounds its elapsed seconds to six decimal places; allow one microsecond.
    require(known_nanos <= elapsed * 1_000_000_000 + 1000, "Recorded timed intervals exceed producer elapsed time")
    require(same(report["workload"], {key: result[key] for key in WORKLOAD_COPY}), "Copied workload context differs")
    accounting = fields(report["accounting"], {"schemaVersion", "retainedFileCount", "totalLogicalBytes", "totalAttributedAllocatedBytes", "iterations", "limits"}, "accounting")
    integer(accounting["schemaVersion"], "accounting schema", 1, 1)
    strings(accounting["limits"], "accounting limits")
    require(type(accounting["iterations"]) is list and len(accounting["iterations"]) == len(checked["iterations"]), "Wrong accounting iterations")
    iterations = []
    total_allocated = 0
    for original, declared, observed in zip(result["iterations"], checked["iterations"], accounting["iterations"]):
        fields(observed, {"files", "logicalBytes", "attributedAllocatedBytes", "index", "warmup", "directory", "processIo"}, "recorded iteration accounting")
        for key in ("index", "warmup", "directory", "logicalBytes", "processIo"):
            require(same(observed[key], declared[key]), f"Recorded accounting differs: {key}")
        files = observed["files"]
        require(type(files) is list and len(files) == len(declared["fileLengths"]), "Wrong recorded file count")
        seen, allocated = set(), 0
        for item in files:
            fields(item, {"name", "logicalBytes", "attributedAllocatedBytes"}, "recorded file")
            name = text(item["name"], "recorded filename", 128)
            require(name in declared["fileLengths"] and name not in seen, "Wrong or duplicate recorded filename")
            seen.add(name)
            integer(item["logicalBytes"], "recorded logical length", 0, MAX_RETAINED_BYTES)
            require(item["logicalBytes"] == declared["fileLengths"][name], "Recorded file length differs")
            blocks = integer(item["attributedAllocatedBytes"], "recorded allocated bytes", 0, MAX_RETAINED_BYTES)
            require(blocks % 512 == 0, "Recorded Linux allocation is not in 512-byte units")
            allocated += blocks
        integer(observed["attributedAllocatedBytes"], "recorded iteration allocation", 0, MAX_RETAINED_BYTES)
        require(observed["attributedAllocatedBytes"] == allocated, "Recorded iteration allocation differs")
        total_allocated += allocated
        iterations.append({"index": original["index"], "warmup": original["warmup"],
                           **{k: original[k] for k in ("namespacePreparationNanos", "actionLoopNanos", "actionServiceNanos",
                              "actionValidationNanos", "actionOtherNanos", "actionLoopOperationsPerSecond", "totalValidationNanos", "validationTasks")},
                           "summaries": original["summaries"], "logicalBytes": declared["logicalBytes"],
                           "recordedAttributedAllocatedBytes": allocated, "processIo": declared["processIo"]})
    for key in ("retainedFileCount", "totalLogicalBytes", "totalAttributedAllocatedBytes"):
        integer(accounting[key], key, 0, MAX_RETAINED_BYTES)
    require(accounting["retainedFileCount"] == checked["retainedFileCount"]
            and accounting["totalLogicalBytes"] == checked["totalLogicalBytes"]
            and accounting["totalAttributedAllocatedBytes"] == total_allocated <= MAX_RETAINED_BYTES, "Recorded aggregate accounting differs")
    integer(report["reservedReportBytes"], "report reserve", MAX_DOCUMENT_BYTES, MAX_DOCUMENT_BYTES)
    retained = integer(report["retainedBytesBeforeReport"], "retained bytes", 0, 144 * 1024 * 1024)
    require(retained >= checked["totalLogicalBytes"] + len(data["worker.json"]) + len(data["request.json"])
            + report["build"]["logBytes"] + report["worker"]["logBytes"], "Recorded retained bytes omit required files")
    limits = [SCOPE, "Recorded producer admission is not an independent attestation or a new qualification",
              "Each iteration keeps its own raw-sample percentiles; warmups and repetitions are not pooled",
              "Operation service, action-loop, validation and namespace-preparation clocks have different scopes",
              "Optional CPU/allocation are platform-thread counters; sampled used heap is not peak heap or RSS",
              "Process I/O includes validation/maintenance/runtime; ratios use encoded journal bytes, not device traffic",
              "Recorded allocated blocks do not establish exclusive physical or reclaimable space"]
    summary = {"schemaVersion": 2 if attributed else 1, "status": "recorded_consistency_passed", "validationScope": SCOPE,
            "productionQualified": False, "runId": report["runId"], "observedAtUtc": report["observedAtUtc"],
            "request": request, "source": source, "classes": report["classes"], "runtime": result["runtime"],
            "kernel": report["kernel"], "architecture": report["architecture"], "mount": mount, "device": device,
            "cache": report["cache"], "fingerprint": result["fingerprint"], "historyFingerprint": result["historyFingerprint"],
            "encodedHistoryBytes": result["encodedHistoryBytes"], "workload": result["workload"],
            "inputFiles": {name: {"bytes": len(value), "sha256": hashlib.sha256(value).hexdigest()} for name, value in data.items()},
            "warmupIterations": [x for x in iterations if x["warmup"]],
            "measuredIterations": [x for x in iterations if not x["warmup"]],
            "limits": limits, "producerGaps": report["gaps"], "recordedAccountingLimits": accounting["limits"]}
    if attributed:
        summary["mode"] = ATTRIBUTED_MODE
        summary["attribution"] = checked["attribution"]
        summary["limits"].append("Instrumented operation file work is distinct from original latency observations and device traffic")
    return summary


def cell(value):
    if value is None:
        return "unavailable"
    escaped = html.escape(str(value)).replace("\n", " ").replace("\r", " ")
    for character in "|\\`[]*_~":
        escaped = escaped.replace(character, f"&#{ord(character)};")
    return escaped


def render_markdown(summary):
    request = summary["request"]
    attributed = summary.get("mode") == ATTRIBUTED_MODE
    lines = ["# Recorded attributed file work" if attributed else "# Recorded workload observations", "", SCOPE + ".", "",
             *([f"Execution mode: `{ATTRIBUTED_MODE}`. Timings are from this instrumented run.", ""] if attributed else []),
             "| Context | Recorded value |", "| --- | --- |",
             f"| Run | {summary['runId']} |", f"| Recorded at UTC | {cell(summary['observedAtUtc'])} |",
             f"| Source | {request['source']} |", f"| Recorded source dirty | {summary['source']['dirty']} |",
             f"| Source tree SHA-256 | {summary['source']['tree']['sha256']} |",
             f"| Compiled classpath SHA-256 | {summary['classes']['sha256']} |",
             f"| Schedule fingerprint | {summary['fingerprint']} |",
             f"| History fingerprint | {summary['historyFingerprint']} |",
             f"| Encoded journal bytes | {summary['encodedHistoryBytes']} |",
             f"| Profile / seed | {request['profile']} / {request['seed']} |",
             f"| E/C/Q/W | {' / '.join(str(request['dimensions'][k]) for k in ('entities', 'commits', 'queries', 'intervalWidth'))} |",
             f"| JVM | {cell(summary['runtime']['javaVendor'])} {cell(summary['runtime']['javaRuntimeVersion'])} |",
             f"| Kernel / filesystem | {cell(summary['kernel'])} / {cell(summary['mount']['fileSystem'])} |",
             f"| Cache | {cell(summary['cache'])} |", "",
             "## Input provenance", "", "| Input | Bytes | SHA-256 |", "| --- | --- | --- |"]
    lines += [f"| {name} | {item['bytes']} | {item['sha256']} |" for name, item in summary["inputFiles"].items()]
    lines += ["", "## Recorded environment", "", "```json",
              json.dumps({"mount": summary["mount"], "device": summary["device"], "runtime": summary["runtime"]},
                         indent=2, sort_keys=True, ensure_ascii=True), "```", ""]
    if attributed:
        lines += ["## Original logical denominators", "", "| Denominator / count | Value |", "| --- | --- |"]
        lines += [f"| {cell(key)} | {cell(value)} |" for key, value in summary["attribution"]["denominators"].items()]
        preparation = summary["attribution"]["preparationTotals"]
        lines += ["", f"Preparation: {preparation['scopeCount']} separate scopes; captured adapter read/write bytes "
                  f"{preparation['totalReadBytes']} / {preparation['totalWriteBytes']}; successful forces {preparation['totalForceSuccesses']}.", "",
                  "Adapter-returned bytes and force outcomes are distinct from metadata, external hashing and device traffic.", ""]
        lines += ["Uncaptured work: " + ", ".join(cell(entry["category"]) for entry in summary["attribution"]["uncapturedWork"])
                  + ". Their counters are unavailable, not zero.", ""]
    for label, key in (("Warmup", "warmupIterations"), ("Measured", "measuredIterations")):
        for item in summary[key]:
            lines += [f"## {label} iteration {item['index']}", "",
                      f"Action-loop operations/s: {cell(item['actionLoopOperationsPerSecond'])}. "
                      f"Loop/service/validation/other ns: {item['actionLoopNanos']} / {item['actionServiceNanos']} / "
                      f"{item['actionValidationNanos']} / {item['actionOtherNanos']}.", "",
                      f"Namespace preparation ns: {item['namespacePreparationNanos']}. "
                      f"Total validation ns: {item['totalValidationNanos']} across {item['validationTasks']} tasks.", "",
                      f"Logical file bytes: {item['logicalBytes']}; recorded attributed allocation: {item['recordedAttributedAllocatedBytes']} bytes.", "",
                      "| Phase / operation | Count | p50 ns | p95 ns | p99 ns | Max ns | Service ops/s | CPU ns (available samples) | Allocated bytes (available samples) | Max sampled used heap bytes |",
                      "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
            for group in item["summaries"]:
                values = [f"{group['phase']} / {group['kind']}", group["count"], group["p50Nanos"], group["p95Nanos"], group["p99Nanos"], group["maxNanos"],
                          group["serviceOperationsPerSecond"], f"{cell(group['threadCpuNanos'])} ({group['threadCpuSamples']})",
                          f"{cell(group['threadAllocatedBytes'])} ({group['threadAllocatedSamples']})", group["maxSampledHeapUsedBytes"]]
                lines.append("| " + " | ".join(cell(x) for x in values) + " |")
            lines += ["", "Process I/O (whole instrumented iteration):", "", "```json",
                      json.dumps(item["processIo"], indent=2, sort_keys=True, ensure_ascii=True), "```", ""]
            if attributed:
                observed = summary["attribution"]["iterations"][item["index"]]
                lines += ["### Operation-attributed local adapter work", "",
                          "| Phase / operation | Scopes | Read bytes | Write bytes | Successful forces |",
                          "| --- | --- | --- | --- | --- |"]
                for group in observed["operationGroups"]:
                    values = [f"{group['phase']} / {group['kind']}", group["scopeCount"],
                              group["totalReadBytes"], group["totalWriteBytes"], group["totalForceSuccesses"]]
                    lines.append("| " + " | ".join(cell(value) for value in values) + " |")
                lines += ["", "| Operation / ratio | Numerator | Logical denominator | Value |", "| --- | --- | --- | --- |"]
                for group in observed["operationGroups"]:
                    for name, ratio in group["ratios"].items():
                        value = "N/A (zero logical denominator)" if ratio["denominator"] == 0 else ratio["value"]
                        label = f"{group['phase']} / {group['kind']} / {name}"
                        lines.append("| " + " | ".join(cell(x) for x in (label, ratio["numerator"], ratio["denominator"], value)) + " |")
                lines += ["", "### Auxiliary coverage", "",
                          "| Category | Coverage | Scopes | Captured adapter read bytes | Captured adapter write bytes | Successful forces |",
                          "| --- | --- | --- | --- | --- | --- |"]
                for group in observed["auxiliaryGroups"]:
                    values = [group["category"], group["coverage"], group["scopeCount"], group["totalReadBytes"],
                              group["totalWriteBytes"], group["totalForceSuccesses"]]
                    lines.append("| " + " | ".join(cell(x) for x in values) + " |")
                lines += ["", "Excluded coverage is unavailable filesystem coverage; zero adapter counts do not mean zero external I/O.", ""]
    lines += ["## Interpretation limits", ""]
    limits = summary["limits"] + summary["producerGaps"] + summary["recordedAccountingLimits"]
    if attributed:
        limits += summary["attribution"]["limits"]
    lines += ["- " + cell(value) for value in limits]
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--expect-source")
    parser.add_argument("--expect-run")
    parser.add_argument("--format", choices=("json", "markdown"), default="json")
    args = parser.parse_args(argv)
    try:
        summary = summarize(args.run_dir, args.expect_source, args.expect_run)
        rendered = json.dumps(summary, indent=2, ensure_ascii=True, allow_nan=False) + "\n" if args.format == "json" else render_markdown(summary)
        require(len(rendered.encode("utf-8")) <= MAX_OUTPUT_BYTES, "Report output admission exceeded")
        print(rendered, end="")
        return 0
    except (OSError, ValueError, TypeError, KeyError, OverflowError) as error:
        print(f"Saved workload report refused: {type(error).__name__}: {str(error)[:512]}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
