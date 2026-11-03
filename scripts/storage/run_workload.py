#!/usr/bin/env python3
"""Supervise checked local Java workloads on Linux; no comparative performance claim."""

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import selectors
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import uuid

from qualify_linux import device_metadata, read_bounded, select_mount
from workload_evidence import EvidenceError, parse_document, validate_worker


MAX_LOG_BYTES = 2 * 1024 * 1024
MAX_TREE_BYTES = 64 * 1024 * 1024
MAX_TREE_FILES = 4096
MAX_RETAINED_BYTES = 160 * 1024 * 1024
MAX_REPORT_BYTES = 16 * 1024 * 1024
CLEANUP_RESERVE = 3.0
INJECTION_VARIABLES = {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH",
                       "MAVEN_ARGS", "MAVEN_OPTS", "MAVEN_DEBUG_OPTS", "MAVEN_BASEDIR",
                       "MAVEN_PROJECTBASEDIR", "LD_PRELOAD", "LD_LIBRARY_PATH"}


class SupervisorError(ValueError):
    """Incomplete, failed or contradictory evidence is never a successful measurement."""


class Interrupted(SupervisorError):
    pass


def remaining(deadline, reserve=0):
    seconds = deadline - time.monotonic() - reserve
    if seconds <= 0:
        raise SupervisorError("Overall workload deadline exhausted")
    return seconds


def clean_environment(environment):
    result = {k: v for k, v in environment.items()
              if k not in INJECTION_VARIABLES and not k.startswith("GIT_")}
    result["MAVEN_OPTS"] = "-Xmx256m"
    result["MAVEN_SKIP_RC"] = "1"
    return result


@contextmanager
def interrupt_handlers():
    def interrupted(number, frame):
        raise Interrupted(f"Interrupted by signal {number}")
    previous = {s: signal.getsignal(s) for s in (signal.SIGTERM, signal.SIGHUP)}
    try:
        for s in previous:
            signal.signal(s, interrupted)
        yield
    finally:
        for s, handler in previous.items():
            signal.signal(s, handler)


@contextmanager
def cleanup_handlers():
    previous = {s: signal.getsignal(s) for s in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT)}
    try:
        for s in previous:
            signal.signal(s, signal.SIG_IGN)
        yield
    finally:
        for s, handler in previous.items():
            signal.signal(s, handler)


def proc_stat(data):
    """Read only numeric identity/state fields; process names are not retained."""
    head, separator, tail = data.rpartition(b") ")
    fields = tail.split()
    if not separator or len(fields) < 20:
        raise SupervisorError("Malformed process identity")
    return {"pid": int(head.split(b" ", 1)[0]), "state": fields[0].decode("ascii"),
            "group": int(fields[2]), "session": int(fields[3]), "startTicks": int(fields[19])}


def live_group(group):
    result = []
    paths = list(Path("/proc").iterdir())
    if len(paths) > 100_000:
        raise SupervisorError("Process inventory limit exceeded")
    for path in paths:
        if not path.name.isdecimal():
            continue
        try:
            identity = proc_stat(read_bounded(path / "stat", 8192))
        except (FileNotFoundError, ProcessLookupError):
            continue
        if identity["group"] == group and identity["state"] != "Z":
            result.append(identity["pid"])
    return result


def settle_group(process, deadline):
    """Retain the leader identity until group cleanup, then reap and verify no live members."""
    def send(sig):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            pass
    failure = None
    try:
        send(signal.SIGTERM)
        until = min(deadline - 1.0, time.monotonic() + 0.5)
        while live_group(process.pid) and time.monotonic() < until:
            time.sleep(0.025)
    except Exception as error:
        failure = error
    finally:
        try:
            send(signal.SIGKILL)  # Escalation does not depend on successful /proc enumeration.
            until = min(deadline, time.monotonic() + 1.0)
            while live_group(process.pid) and time.monotonic() < until:
                time.sleep(0.025)
            if live_group(process.pid):
                raise SupervisorError("Process group still has live members after bounded cleanup")
        except Exception as error:
            failure = error
        finally:
            try:
                process.wait(timeout=max(0.1, min(1.0, deadline - time.monotonic())))
            except Exception as error:
                failure = error
    if failure is not None:
        raise SupervisorError(f"Process cleanup/verification failed: {type(failure).__name__}: {failure}")


def run_group(command, cwd, log_path, deadline, environment, max_log_bytes=MAX_LOG_BYTES):
    """One deadline, bounded combined output, private cooperating process group on every outcome."""
    remaining(deadline, CLEANUP_RESERVE)
    written = 0
    process = None
    failure = None
    identity = None
    with Path(log_path).open("xb") as log:
        try:
            process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.DEVNULL,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       env=environment, start_new_session=True)
            os.set_blocking(process.stdout.fileno(), False)
            identity = proc_stat(read_bounded(f"/proc/{process.pid}/stat", 8192))
            if identity["group"] != process.pid or identity["session"] != process.pid:
                raise SupervisorError("Child does not own the expected private group/session")
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while True:
                    seconds = remaining(deadline, CLEANUP_RESERVE)
                    for key, _ in selector.select(min(0.05, seconds)):
                        chunk = os.read(key.fileobj.fileno(), 8192)
                        if not chunk:
                            selector.unregister(key.fileobj)
                            continue
                        allowed = max_log_bytes - written
                        log.write(chunk[:allowed]); written += min(len(chunk), allowed)
                        if len(chunk) > allowed:
                            raise SupervisorError("Process log byte limit exceeded")
                    # WNOWAIT prevents PID reuse until we have addressed the owned process group.
                    info = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                    if info is not None:
                        break
        except BaseException as error:
            failure = error
        finally:
            if process is not None:
                try:
                    with cleanup_handlers():
                        settle_group(process, deadline)
                    # Drain bounded bytes buffered before group settlement, including last-line errors.
                    if process.stdout is not None:
                        while True:
                            chunk = os.read(process.stdout.fileno(), 8192)
                            if not chunk:
                                break
                            allowed = max_log_bytes - written
                            log.write(chunk[:allowed]); written += min(len(chunk), allowed)
                            if len(chunk) > allowed:
                                raise SupervisorError("Process log byte limit exceeded")
                except BaseException as cleanup:
                    failure = SupervisorError(f"Process cleanup/output failed: {type(cleanup).__name__}: {cleanup}; prior={type(failure).__name__}")
                finally:
                    if process.stdout is not None:
                        process.stdout.close()
    if failure is not None:
        raise failure
    return {"pid": process.pid, "startTicks": identity["startTicks"], "exitCode": process.returncode,
            "logBytes": written, "groupSettled": True}


def tree_fingerprint(root, paths, deadline):
    digest = hashlib.sha256()
    total = 0
    names = sorted(set(paths))
    if not names or len(names) > MAX_TREE_FILES:
        raise SupervisorError("Source/class file inventory limit exceeded")
    for name in names:
        remaining(deadline)
        path = root / name
        a = path.lstat()
        if not stat.S_ISREG(a.st_mode) or not 0 <= a.st_size <= MAX_TREE_BYTES - total:
            raise SupervisorError("Source/class inputs must be ordinary bounded files")
        digest.update(os.fsencode(name)); digest.update(b"\0"); digest.update(a.st_mode.to_bytes(8, "big"))
        count = 0
        with path.open("rb") as source:
            while chunk := source.read(min(1024 * 1024, MAX_TREE_BYTES - total + 1)):
                total += len(chunk); count += len(chunk)
                if total > MAX_TREE_BYTES:
                    raise SupervisorError("Source/class aggregate byte limit exceeded")
                digest.update(chunk)
        if count != a.st_size:
            raise SupervisorError("Source/class input changed during fingerprint")
        digest.update(b"\0")
    return {"sha256": digest.hexdigest(), "files": len(names), "bytes": total}


def git(repo, arguments, deadline, environment):
    # Even Git inventories use bounded capture and the same deadline/group lifecycle.
    with tempfile.TemporaryDirectory(prefix="mdb-git-capture-") as temporary:
        capture = Path(temporary) / "capture.log"
        result = run_group(["git", *arguments], repo, capture, deadline, environment)
        if result["exitCode"] != 0:
            raise SupervisorError("Source inventory Git command failed")
        return read_bounded(capture, MAX_LOG_BYTES)


def source_snapshot(repo, deadline, environment):
    top = Path(os.fsdecode(git(repo, ["rev-parse", "--show-toplevel"], deadline, environment)).strip()).resolve()
    if top != repo:
        raise SupervisorError("Git root differs from the selected source checkout")
    commit = git(repo, ["rev-parse", "HEAD"], deadline, environment).decode("ascii").strip()
    if len(commit) != 40 or any(c not in "0123456789abcdef" for c in commit):
        raise SupervisorError("Invalid source commit identity")
    status = git(repo, ["status", "--porcelain", "-z"], deadline, environment)
    paths = [Path(os.fsdecode(p)) for p in git(repo, ["ls-files", "--cached", "--others", "--exclude-standard", "-z"], deadline, environment).split(b"\0") if p]
    return {"commit": commit, "dirty": bool(status), "statusSha256": hashlib.sha256(status).hexdigest(),
            "tree": tree_fingerprint(repo, paths, deadline)}


def class_snapshot(repo, deadline):
    paths = []
    for directory in (repo / "target/classes", repo / "target/test-classes"):
        if not directory.is_dir() or directory.is_symlink():
            raise SupervisorError("Fresh compiled classpath is missing")
        for path in directory.rglob("*"):
            if path.is_symlink():
                raise SupervisorError("Compiled classpath contains a symlink")
            if path.is_file():
                paths.append(path.relative_to(repo))
                if len(paths) > MAX_TREE_FILES:
                    raise SupervisorError("Compiled classpath file limit exceeded")
    expected = repo / "target/test-classes/org/meridiandb/workload/WorkloadWorker.class"
    if expected.relative_to(repo) not in paths:
        raise SupervisorError("Fresh worker class is missing")
    return tree_fingerprint(repo, paths, deadline)


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def retain_size(root, deadline, reserve=0):
    total = 0
    count = 0
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in dirs + files:
            remaining(deadline)
            path = Path(directory) / name
            a = path.lstat(); count += 1
            if count > 8192 or stat.S_ISLNK(a.st_mode):
                raise SupervisorError("Retained evidence entry limit or symlink violation")
            if stat.S_ISREG(a.st_mode):
                total += a.st_size
                if total > MAX_RETAINED_BYTES - reserve:
                    raise SupervisorError("Retained evidence byte limit exceeded")
            elif not stat.S_ISDIR(a.st_mode):
                raise SupervisorError("Unexpected retained evidence object")
    return total


def write_report(path, report):
    data = json.dumps(report, indent=2, ensure_ascii=True, allow_nan=False).encode() + b"\n"
    if len(data) > MAX_REPORT_BYTES:
        raise SupervisorError("Final supervisor report byte limit exceeded")
    partial = path.with_suffix(".partial.json")
    with partial.open("xb") as destination:
        destination.write(data)
    os.link(partial, path)  # Publish complete bytes with create-only final-name semantics.
    partial.unlink()


def finish_report(report, started, deadline, observed, exit_code):
    report["elapsedSecondsBeforeReport"] = round(observed - started, 6)
    report["deadlineExceededBeforeReport"] = observed > deadline
    if report["deadlineExceededBeforeReport"]:
        report["status"] = "failed"
        report.setdefault("error", {"type": "SupervisorError", "message": "Overall workload deadline exhausted"})
        return 1
    return exit_code


def log_summary(report):
    """Retain compact admission/provenance facts in CI logs without raw samples."""
    keys = ("schemaVersion", "runId", "status", "productionQualified", "request", "source", "classes",
            "kernel", "architecture", "mount", "device", "worker", "workload", "workerResultSha256",
            "workerResultBytes", "elapsedSecondsBeforeReport", "deadlineExceededBeforeReport", "error")
    summary = {key: report[key] for key in keys if key in report}
    accounting = report.get("accounting")
    if accounting is not None:
        summary["accounting"] = {key: accounting[key] for key in (
            "retainedFileCount", "totalLogicalBytes", "totalAttributedAllocatedBytes")}
        summary["accounting"]["iterations"] = [
            {key: item[key] for key in ("index", "warmup", "logicalBytes", "attributedAllocatedBytes", "processIo")}
            for item in accounting["iterations"]]
    return summary


def failure_envelope(document, request, worker_pid, code):
    expected_fields = {"schemaVersion", "runId", "source", "profile", "seed", "workerPid", "status", "error", "io", "result"}
    error = document.get("error")
    status = document.get("status")
    expected_code = {"failed": 1, "unsupported": 3}.get(status) if isinstance(status, str) else None
    if (set(document) != expected_fields or type(document.get("schemaVersion")) is not int
            or document["schemaVersion"] != 1 or document.get("runId") != request["runId"]
            or document.get("source") != request["source"] or document.get("profile") != request["profile"]
            or type(document.get("seed")) is not int or document["seed"] != request["seed"]
            or type(document.get("workerPid")) is not int or document["workerPid"] != worker_pid
            or document.get("result") is not None or expected_code is None or code != expected_code
            or not isinstance(error, dict) or set(error) != {"type", "message"}
            or not isinstance(error["type"], str) or not 1 <= len(error["type"]) <= 256
            or not isinstance(error["message"], str) or len(error["message"]) > 1024
            or not isinstance(document.get("io"), list)
            or len(document["io"]) > request["warmups"] + request["repetitions"]):
        raise SupervisorError("Invalid failed/unsupported worker binding")
    return status, error, expected_code


def supervise(repo, parent, profile, seed, warmups, repetitions, timeout):
    if sys.platform != "linux":
        raise SupervisorError("The supervised workload command requires Linux")
    if (profile not in {"APPEND", "CORRECTIONS", "RETRACTIONS", "HOT_ENTITY"}
            or type(seed) is not int or not -(2**63) <= seed < 2**63):
        raise SupervisorError("Invalid named workload/seed")
    if (any(type(v) is not int for v in (warmups, repetitions, timeout))
            or not 0 <= warmups <= 2 or not 1 <= repetitions <= 5 or not 30 <= timeout <= 600):
        raise SupervisorError("Invalid bounded workload configuration")
    if not parent.is_dir() or parent.is_symlink():
        raise SupervisorError("Scratch parent must be an existing trusted real directory")
    repo = repo.resolve(); parent = parent.resolve()
    started = time.monotonic()
    deadline = started + timeout
    root = Path(tempfile.mkdtemp(prefix="mdb-measure-", dir=parent)).resolve()
    stores = root / "stores"; stores.mkdir()
    temporary = root / "tmp"; temporary.mkdir()
    run_id = uuid.uuid4().hex
    request = {"runId": run_id, "source": None, "profile": profile, "seed": seed,
               "warmups": warmups, "repetitions": repetitions}
    report = {"schemaVersion": 1, "runId": run_id, "status": "failed", "productionQualified": False,
              "observedAtUtc": datetime.now(timezone.utc).isoformat(), "request": request,
              "runRoot": str(root), "kernel": platform.release(), "architecture": platform.machine(),
              "cache": "uncontrolled; no eviction or forced GC", "heapLimitBytes": 256 * 1024 * 1024,
              "evidenceScope": "checked synchronous developer workload observations on one Linux placement",
              "gaps": ["No controlled comparative baseline or production/power-loss qualification",
                       "Process counters are non-atomic instrumented-iteration accounting, not hardware transfers",
                       "Attributed file blocks do not establish exclusive physical or reclaimable space",
                       "Cleanup covers cooperating children in the dedicated group, not escaped sessions/cgroups"]}
    exit_code = 1
    try:
        for directory in (stores, temporary, root, parent, parent.parent):
            sync_directory(directory)
        environment = clean_environment(os.environ)
        source = source_snapshot(repo, deadline, environment)
        request["source"] = source["commit"]
        report["source"] = source
        write_report(root / "request.json", {"schemaVersion": 1, **request})
        device = stores.stat().st_dev
        major_minor = f"{os.major(device)}:{os.minor(device)}"
        report["mount"] = select_mount(read_bounded("/proc/self/mountinfo", 2 * 1024 * 1024).decode(), stores, major_minor)
        report["device"] = device_metadata(major_minor)
        build = [str(repo / "mvnw"), "-B", "clean", "test-compile"]
        report["buildCommand"] = build
        report["build"] = run_group(build, repo, root / "build.log", deadline, environment)
        if report["build"]["exitCode"] != 0:
            raise SupervisorError("Clean compilation failed")
        if source_snapshot(repo, deadline, environment) != source:
            raise SupervisorError("Source changed during compilation")
        classes = class_snapshot(repo, deadline)
        report["classes"] = classes
        java_home = environment.get("JAVA_HOME")
        executable = str((Path(java_home) / "bin/java").resolve()) if java_home else shutil.which("java", path=environment.get("PATH"))
        if executable is None:
            raise SupervisorError("Explicit Java executable unavailable")
        output = root / "worker.json"
        command = [executable, "-Xmx256m", f"-Djava.io.tmpdir={temporary}", "--class-path",
                   os.pathsep.join([str(repo / "target/classes"), str(repo / "target/test-classes")]),
                   "org.meridiandb.workload.WorkloadWorker", run_id, source["commit"], profile,
                   str(seed), str(warmups), str(repetitions), str(stores), str(output)]
        report["workerCommand"] = command
        report["worker"] = run_group(command, repo, root / "worker.log", deadline, environment)
        if source_snapshot(repo, deadline, environment) != source or class_snapshot(repo, deadline) != classes:
            raise SupervisorError("Source or compiled inputs changed during the worker")
        if output.is_symlink() or not output.is_file():
            raise SupervisorError("Worker has no complete ordinary result")
        data = read_bounded(output, 16 * 1024 * 1024)
        document = parse_document(data)
        report["workerResultSha256"] = hashlib.sha256(data).hexdigest()
        report["workerResultBytes"] = len(data)
        # Failure/unsupported envelopes cannot carry partial successful evidence into admission.
        if document.get("status") != "success":
            status, error, exit_code = failure_envelope(document, request, report["worker"]["pid"], report["worker"]["exitCode"])
            report["status"] = status
            report["error"] = error
        else:
            if report["worker"]["exitCode"] != 0:
                raise SupervisorError("Nonzero worker cannot supply successful evidence")
            report["accounting"] = validate_worker(document, request, report["worker"]["pid"], stores, report["mount"])
            report["workload"] = {key: document["result"][key] for key in (
                "schemaVersion", "corpusVersion", "spec", "config", "fingerprint", "historyFingerprint",
                "encodedHistoryBytes", "runtime", "workload")}
            remaining(deadline)
            report["status"] = "checks_passed"
            exit_code = 0
        report["retainedBytesBeforeReport"] = retain_size(root, deadline, reserve=MAX_REPORT_BYTES)
        report["reservedReportBytes"] = MAX_REPORT_BYTES
    except (Exception, KeyboardInterrupt) as failure:
        report["status"] = "failed"
        report["error"] = {"type": type(failure).__name__, "message": str(failure)[:2048]}
        exit_code = 1
    finally:
        exit_code = finish_report(report, started, deadline, time.monotonic(), exit_code)
        write_report(root / "report.json", report)
    print("WORKLOAD_SUMMARY " + json.dumps(log_summary(report), ensure_ascii=True, allow_nan=False))
    print(json.dumps({"status": report["status"], "runRoot": str(root), "report": str(root / "report.json")}, indent=2))
    return exit_code


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scratch-parent", type=Path, required=True)
    parser.add_argument("--profile", choices=["APPEND", "CORRECTIONS", "RETRACTIONS", "HOT_ENTITY"], default="CORRECTIONS")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args(argv)
    try:
        with interrupt_handlers():
            return supervise(Path(__file__).resolve().parents[2], args.scratch_parent, args.profile,
                             args.seed, args.warmups, args.repetitions, args.timeout)
    except (Exception, KeyboardInterrupt) as failure:
        print(f"Workload supervisor: {type(failure).__name__}: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
