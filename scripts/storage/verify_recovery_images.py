#!/usr/bin/env python3
"""Linux Java/Python image-compatibility smoke, not complete recovery evidence admission."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import time
import uuid

from qualify_linux import read_bounded
from recovery_images import validate_images
from run_workload import (CLEANUP_RESERVE, MAX_REPORT_BYTES, SupervisorError, clean_environment,
                          cleanup_handlers, finish_report, interrupt_handlers, remaining,
                          retain_size, run_group, sync_directory, write_report)


SCOPE = "Recorded binary-image consistency only; no complete worker, schedule, source/class, native-file or saved-report admission"
CONTROL = "RECOVERY_UNINSTRUMENTED"
CAPTURED = "CALLER_THREAD_RECOVERY_IO_INSTRUMENTED"
CASES = (("control", CONTROL, "CORRECTIONS", 7, 2, 5, 2),
         ("captured", CAPTURED, "CORRECTIONS", 7, 2, 5, 2),
         ("maximum-history", CAPTURED, "APPEND", 496, 1, 2, 1))
CORPUS_CASES = tuple(case + (42,) for case in CASES) + (
    ("retractions-negative", CAPTURED, "RETRACTIONS", 21, 3, 35, 3, -17),
    ("hot-entity-minimum", CONTROL, "HOT_ENTITY", 37, 4, 37, 4, -(1 << 63)),
    ("append-maximum-seed", CONTROL, "APPEND", 35, 1, 10, 2, (1 << 63) - 1))
CORPUS_SCOPE = ("Recorded binary images and independently reconstructed seeded corpus only; "
                "no complete worker, matrix/counter, source/class, native-file or saved-report admission")
CORPUS_FIELDS = ("corpusVersion", "fingerprint", "historyFingerprint", "encodedHistoryBytes", "workload")


def _require(condition, message):
    if not condition:
        raise SupervisorError(message)


def _expected_spec(profile, commits, entities, queries, width, seed=42):
    percentages = {"APPEND": (0, 0, 0, 5, 0, 50), "CORRECTIONS": (70, 10, 10, 10, 10, 75),
                   "RETRACTIONS": (20, 60, 10, 10, 10, 50), "HOT_ENTITY": (60, 10, 10, 20, 95, 80)}[profile]
    result = dict(seed=seed, entities=entities, commits=commits, queries=queries, intervalWidth=width)
    result.update(zip(("correctionPercent", "retractionPercent", "repeatPercent", "retryPercent",
                       "hotEntityPercent", "historicalReadPercent"), percentages))
    return result


def _produced_document(path):
    """Read only this smoke's fresh, bounded producer output; no general archive-admission API."""
    attributes = path.lstat()
    _require(stat.S_ISREG(attributes.st_mode), "Worker output is not an ordinary file")
    raw = read_bounded(path, 16 * 1024 * 1024)

    def unique(pairs):
        result = {}
        for key, value in pairs:
            _require(key not in result, "Duplicate producer JSON key")
            result[key] = value
        return result

    def constant(_value):
        raise SupervisorError("Nonfinite producer JSON value")

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=unique, parse_constant=constant)
    except (ValueError, RecursionError) as error:
        raise SupervisorError("Malformed producer JSON") from error
    _require(type(value) is dict and value.get("protocol") == "MERIDIANDB_RECOVERY_COMPARISON"
             and type(value.get("schemaVersion")) is int and value["schemaVersion"] == 1
             and value.get("status") == "success", "Producer did not export the expected successful protocol")
    return value, dict(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())


def verify(repo, parent, declared_source, timeout=120, *, check_corpus=False):
    """Run fixed correctness cases. Returned zero certifies only the selected input scope."""
    _require(type(check_corpus) is bool, "Require an explicit boolean corpus selection")
    _require(sys.platform == "linux", "The compatibility smoke requires Linux process-group facilities")
    _require(type(timeout) is int and 30 <= timeout <= 300, "Smoke timeout must be 30..300 seconds")
    _require(type(declared_source) is str and re.fullmatch(r"[0-9a-f]{40}", declared_source), "Require full declared source SHA")
    repo, parent = Path(repo), Path(parent)
    for path in (repo, parent):
        _require(path.is_absolute() and path == path.resolve(strict=True) and not path.is_symlink()
                 and path.is_dir(), "Require canonical existing repository and scratch parent")
    worker = repo / "target/test-classes/org/meridiandb/workload/RecoveryComparisonWorker.class"
    _require(worker.is_file() and (repo / "target/classes").is_dir(), "Compile core/test support before the smoke")
    started = time.monotonic()
    deadline = started + timeout
    root = Path(tempfile.mkdtemp(prefix="mdb-recovery-corpus-" if check_corpus else "mdb-recovery-images-", dir=parent))
    report = dict(schemaVersion=2 if check_corpus else 1, scope=CORPUS_SCOPE if check_corpus else SCOPE,
                  productionQualified=False, declaredSource=declared_source,
                  runRoot=str(root), status="failed", cases=[])
    code = 1
    active_case = None
    try:
        sync_directory(parent)
        environment = clean_environment(os.environ)
        config = dict(warmups=0, repetitions=1)
        limits = dict(maxObjectBytes=67108864, maxJournalBytes=67108864, maxRecordWork=262144)
        cases = CORPUS_CASES if check_corpus else tuple(case + (42,) for case in CASES)
        for name, mode, profile, commits, entities, queries, width, seed in cases:
            remaining(deadline, CLEANUP_RESERVE)
            home = root / name
            home.mkdir()
            scratch = home / "fixtures"
            scratch.mkdir()
            sync_directory(root)
            sync_directory(home)
            sync_directory(scratch)
            run_id = uuid.uuid4().hex
            output = home / "worker.json"
            command = ["java", "-Xms32m", "-Xmx256m", "--class-path", "target/test-classes:target/classes",
                       "org.meridiandb.workload.RecoveryComparisonWorker", run_id, declared_source, mode,
                       profile, str(seed), "0", "1", str(scratch), str(output), str(entities), str(commits), str(queries),
                       str(width), "67108864", "67108864", "262144"]
            active_case = dict(name=name, requestedMode=mode, directory=str(home), worker=None)
            process = run_group(command, repo, home / "worker.log", deadline, environment, max_log_bytes=256 * 1024)
            active_case["worker"] = process
            _require(process["exitCode"] == 0 and process["groupSettled"], "Java compatibility worker failed or did not settle")
            document, identity = _produced_document(output)
            spec = _expected_spec(profile, commits, entities, queries, width, seed)
            facts = validate_images(document.get("sourceHistory"), document.get("continuationFrameHex"),
                                    document.get("fixtureImages"), spec, config, limits)
            # Deliberately do not admit the outer result or copy/interpret raw timing/counter observations.
            case = dict(name=name, requestedMode=mode, worker=process, workerOutput=identity, images=facts)
            if check_corpus:
                from recovery_corpus import validate_corpus
                result = document.get("result")
                _require(type(result) is dict, "Require recorded corpus declarations")
                case["corpus"] = validate_corpus(document.get("sourceHistory"),
                                                {key: result.get(key) for key in CORPUS_FIELDS}, spec)
            report["cases"].append(case)
            active_case = None
            remaining(deadline, CLEANUP_RESERVE)
            retain_size(root, deadline, reserve=2 * MAX_REPORT_BYTES)
        _require(report["cases"][0]["images"] == report["cases"][1]["images"],
                 "Captured/control workers produced different original image facts")
        if check_corpus:
            _require(report["cases"][0]["corpus"] == report["cases"][1]["corpus"],
                     "Captured/control workers produced different seeded corpus facts")
        report["status"] = "input_checks_passed" if check_corpus else "image_checks_passed"
        code = 0
    except BaseException as error:
        report["status"] = "failed"
        report["failedCase"] = active_case
        report["error"] = dict(type=type(error).__name__[:256], message=str(error)[:1024])
    with cleanup_handlers():
        code = finish_report(report, started, deadline, time.monotonic(), code)
        write_report(root / "summary.json", report)
    return code, report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scratch-parent", required=True, type=Path)
    parser.add_argument("--source", required=True, help="Declared source SHA; this smoke does not certify source/classes")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--corpus", action="store_true", help="Also independently bind seeded corpus inputs; no full worker admission")
    args = parser.parse_args(argv)
    try:
        with interrupt_handlers():
            code, report = verify(Path(__file__).resolve().parents[2], args.scratch_parent, args.source, args.timeout,
                                  check_corpus=args.corpus)
    except (OSError, ValueError) as error:
        print(f"Recovery image smoke could not start/retain evidence: {type(error).__name__}: {str(error)[:1024]}", file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=True, allow_nan=False))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
