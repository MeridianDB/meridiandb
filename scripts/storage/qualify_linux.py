#!/usr/bin/env python3
"""Run bounded local-core conformance on one observed Linux filesystem. No cloud I/O."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import sys
import tempfile
import uuid
import xml.etree.ElementTree as ET


FACTORY = "org.meridiandb.storage.internal.QualificationTempDirFactory"
CAPABILITIES = {"production-directory-probe", "file-force", "directory-force", "file-key",
                "actual-shared-lock", "exclusive-lock", "atomic-move", "identity-after-move"}
MAX_XML_BYTES = 4 * 1024 * 1024
MAX_REPORT_BYTES = 64 * 1024 * 1024
SAFE_MOUNT_OPTIONS = {"rw", "ro", "relatime", "noatime", "strictatime", "lazytime",
                      "sync", "async", "dirsync", "nosuid", "nodev", "noexec",
                      "discard", "nodiscard", "nobarrier", "barrier", "dax"}


class ProfileError(ValueError):
    """Missing or contradictory evidence; never an unsupported-capability success."""


def read_bounded(path, limit):
    with Path(path).open("rb") as source:
        data = source.read(limit + 1)
    if len(data) > limit:
        raise ProfileError(f"Evidence exceeds byte limit: {Path(path).name}")
    return data


def read_json(data):
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ProfileError("Duplicate JSON evidence key")
            result[key] = value
        return result

    def invalid_constant(value):
        raise ProfileError(f"Invalid JSON evidence constant: {value}")

    return json.loads(data.decode("utf-8"), object_pairs_hook=unique_object,
                      parse_constant=invalid_constant)


def unescape_mount(value):
    return re.sub(r"\\(040|011|012|134)", lambda m: chr(int(m[1], 8)), value)


def select_mount(text, scratch, device):
    """Bind by st_dev and path boundary; refuse ambiguous stacked mount evidence."""
    candidates = []
    for line in text.splitlines():
        left, separator, right = line.partition(" - ")
        fields, tail = left.split(), right.split()
        if not separator or len(fields) < 6 or len(tail) != 3:
            raise ProfileError("Malformed mountinfo")
        if not re.fullmatch(r"\d+:\d+", fields[2]):
            raise ProfileError("Malformed mount device")
        mountpoint = Path(unescape_mount(fields[4]))
        if not mountpoint.is_absolute():
            raise ProfileError("Nonabsolute mount point")
        if fields[2] == device and scratch.is_relative_to(mountpoint):
            options = fields[5].split(",") + tail[2].split(",")
            recognized = sorted(option for option in set(options)
                if option in SAFE_MOUNT_OPTIONS or re.fullmatch(
                    r"data=(ordered|journal|writeback)|barrier=[01]|commit=\d+|errors=(remount-ro|continue|panic)",
                    option))
            candidates.append((len(mountpoint.parts), {
                "mountId": fields[0], "majorMinor": device,
                "mountPoint": str(mountpoint), "fileSystem": tail[0],
                "recognizedOptions": recognized,
                # Do not emit overlay lowerdirs, network sources, usernames or raw options.
                "unreportedOptionCount": len(set(options)) - len(recognized),
            }))
    if not candidates:
        raise ProfileError("No mount matches the actual scratch device and path")
    depth = max(item[0] for item in candidates)
    selected = [item[1] for item in candidates if item[0] == depth]
    if len(selected) != 1:
        raise ProfileError("Ambiguous stacked mounts; select an unambiguous scratch placement")
    return selected[0]


def device_metadata(major_minor, sys_devices=Path("/sys/dev/block")):
    """Record only exposed queue facts. Virtual/partition backing remains unqualified."""
    device = sys_devices / major_minor
    result = {"writeCache": None, "logicalBlockBytes": None,
              "physicalBlockBytes": None, "rotational": None}
    for output, name in [("writeCache", "write_cache"), ("logicalBlockBytes", "logical_block_size"),
                         ("physicalBlockBytes", "physical_block_size"), ("rotational", "rotational")]:
        try:
            value = read_bounded(device / "queue" / name, 256).decode().strip()
        except FileNotFoundError:
            continue
        if not value:
            raise ProfileError("Empty exposed device queue evidence")
        result[output] = value
    result["backingDeviceAndController"] = "unknown; not inferred from virtual or partition metadata"
    return result


def read_reports(directory, inventory, scratch, run_id):
    files = sorted(directory.glob("TEST-*.xml"))
    if len(files) != len(inventory):
        raise ProfileError("Report inventory differs from the required suite inventory")
    total_bytes = 0
    results, observed, runtime = [], set(), None
    for path in files:
        if path.is_symlink() or not path.is_file():
            raise ProfileError("Report must be an ordinary run-owned file")
        data = read_bounded(path, MAX_XML_BYTES)
        total_bytes += len(data)
        try:
            xml_text = data.decode("utf-8")
        except UnicodeDecodeError as error:
            raise ProfileError("Reports must use UTF-8 XML") from error
        if (total_bytes > MAX_REPORT_BYTES or "<!DOCTYPE" in xml_text
                or "<!ENTITY" in xml_text or "\x00" in xml_text):
            raise ProfileError("Unsafe or excessive XML evidence")
        try:
            root = ET.fromstring(xml_text)
        except ET.ParseError as error:
            raise ProfileError("Malformed test report XML") from error
        name = root.get("name")
        if root.tag != "testsuite" or name not in inventory or name in observed:
            raise ProfileError("Unexpected or duplicate suite")
        if path.name != f"TEST-{name}.xml":
            raise ProfileError("Report name and suite differ")
        if any(child.tag not in {"properties", "testcase", "system-out", "system-err"} for child in root):
            raise ProfileError("Unknown report element")
        if len(root.findall("properties")) != 1 or root.get("flakes", "0") != "0":
            raise ProfileError("Missing/duplicate properties or retry-masked failures")
        observed.add(name)
        properties = {}
        for prop in root.findall("properties/property"):
            key = prop.get("name")
            if key in properties:
                raise ProfileError("Duplicate report property")
            properties[key] = prop.get("value")
        for key, expected in {
            "meridiandb.qualification.root": str(scratch),
            "meridiandb.qualification.runId": run_id,
            "java.io.tmpdir": str(scratch),
            "junit.jupiter.tempdir.factory.default": FACTORY,
        }.items():
            if properties.get(key) != expected:
                raise ProfileError(f"Wrong fixture/run binding: {key}")
        actual_runtime = {key: properties.get(key) for key in
                          ("java.version", "java.runtime.version", "java.vendor", "os.arch")}
        if any(not isinstance(value, str) or not value.strip() or len(value) > 512
               for value in actual_runtime.values()):
            raise ProfileError("Missing Java runtime evidence")
        if not actual_runtime["java.version"].startswith("25."):
            raise ProfileError("Qualification requires the Java 25 line")
        if runtime is not None and actual_runtime != runtime:
            raise ProfileError("Mixed Java runtime reports")
        runtime = actual_runtime
        cases, seen, counts = [], set(), Counter()
        for case in root.findall("testcase"):
            case_name = case.get("name")
            if (not case_name or len(case_name) > 4096 or case_name in seen
                    or case.get("classname") != name):
                raise ProfileError("Invalid or duplicate testcase identity")
            seen.add(case_name)
            if any(child.tag not in {"failure", "error", "skipped", "system-out", "system-err"}
                   for child in case):
                raise ProfileError("Unknown or retry-masked testcase outcome")
            outcomes = [child.tag for child in case if child.tag in {"failure", "error", "skipped"}]
            if len(outcomes) > 1:
                raise ProfileError("Conflicting testcase outcomes")
            status = outcomes[0] if outcomes else "passed"
            counts[status] += 1
            cases.append({"name": case_name, "status": status})
        try:
            actual = {key: int(root.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
        except (KeyError, ValueError) as error:
            raise ProfileError("Invalid report totals") from error
        expected = {"tests": len(cases), "failures": counts["failure"],
                    "errors": counts["error"], "skipped": counts["skipped"]}
        if actual != expected or len(cases) != inventory[name] or len(cases) <= 0:
            raise ProfileError("Report counters or expected invocation count differ")
        if any(actual[key] for key in ("failures", "errors", "skipped")):
            raise ProfileError(f"Required suite failed or skipped: {name}")
        results.append({"suite": name, "tests": len(cases), "cases": cases})
    if observed != set(inventory):
        raise ProfileError("Missing required suite")
    return {"tests": sum(item["tests"] for item in results), "failures": 0,
            "errors": 0, "skipped": 0, "suites": results, "runtime": runtime}


def stop_process_group(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass
    # The leader may have exited while a fixture child survived TERM.
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait(timeout=5)


def run_command(command, cwd, log_path, timeout):
    """Bound and clean the dedicated process group, including Maven's child JVMs."""
    with log_path.open("xb") as log:
        process = subprocess.Popen(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            code = process.wait(timeout=timeout)
            if code != 0:
                stop_process_group(process)
            return code
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            stop_process_group(process)
            raise ProfileError("Maven interrupted or timed out; process-group cleanup attempted")


def qualify(repo, parent, label, timeout):
    run_id = uuid.uuid4().hex
    run = Path(tempfile.mkdtemp(prefix="mdb-linux-", dir=parent)).resolve()
    scratch, reports = run / "fixtures", run / "reports"
    scratch.mkdir()
    reports.mkdir()
    report = {"schemaVersion": 1, "runId": run_id, "label": label,
              "observedAtUtc": datetime.now(timezone.utc).isoformat(),
              "status": "failed", "productionQualified": False,
              "evidenceScope": "local-core process/fault correctness on the observed Linux placement",
              "kernel": platform.release(), "architecture": platform.machine(),
              "fixtureRoot": str(scratch),
              "gaps": ["Device/controller, power-loss and storage-firmware behavior unqualified",
                       "No controlled throughput, total-memory or operational-production qualification"]}
    command = []
    try:
        if any(char in str(run) for char in ('"', "\\", "\n", "\r", "\x00")):
            raise ProfileError("Scratch path contains unsupported JVM argument quoting characters")
        source = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, capture_output=True,
                                text=True, check=True, timeout=10).stdout.strip()
        dirty = subprocess.run(["git", "status", "--porcelain"], cwd=repo, capture_output=True,
                               check=True, timeout=10).stdout != b""
        report["source"] = {"commit": source, "dirty": dirty}
        dev = scratch.stat().st_dev
        device = f"{os.major(dev)}:{os.minor(dev)}"
        report["mount"] = select_mount(read_bounded("/proc/self/mountinfo", 2 * 1024 * 1024).decode(),
                                        scratch, device)
        report["device"] = device_metadata(device)
        inventory_data = read_bounded(repo / "scripts/storage/required-suites.json", 64 * 1024)
        inventory_document = read_json(inventory_data)
        if not isinstance(inventory_document, dict):
            raise ProfileError("Invalid required suite inventory document")
        inventory = inventory_document["suites"]
        if (inventory_document.get("schemaVersion") != 1 or not isinstance(inventory, dict)
                or not 1 <= len(inventory) <= 256
                or any(not isinstance(v, int) or isinstance(v, bool) or v <= 0 for v in inventory.values())):
            raise ProfileError("Invalid required suite inventory")
        sources = {".".join(p.relative_to(repo / "src/test/java").with_suffix("").parts)
                   for p in (repo / "src/test/java").rglob("*Test.java")}
        if set(inventory) != sources:
            raise ProfileError("Required inventory differs from the current core test sources")
        report["inventorySha256"] = hashlib.sha256(inventory_data).hexdigest()
        command = [str(repo / "mvnw"), "-B", "clean", "install", "-Dmaven.test.skip=false",
                   "-DskipTests=false", "-Dsurefire.rerunFailingTestsCount=0",
                   f"-Dmeridiandb.test.reportsDirectory={reports}",
                   f'-DargLine=-Djava.io.tmpdir="{scratch}"',
                   f"-Djunit.jupiter.tempdir.factory.default={FACTORY}",
                   f"-Dmeridiandb.qualification.root={scratch}",
                   f"-Dmeridiandb.qualification.runId={run_id}",
                   f"-Dmeridiandb.qualification.profile={run / 'java-profile.json'}"]
        report["command"] = command
        code = run_command(command, repo, run / "maven.log", timeout)
        report["mavenExitCode"] = code
        if code != 0:
            raise ProfileError(f"Maven failed with exit code {code}")
        report["tests"] = read_reports(reports, inventory, scratch, run_id)
        java = read_json(read_bounded(run / "java-profile.json", 32 * 1024))
        if (not isinstance(java, dict) or java.get("schemaVersion") != 1 or java.get("runId") != run_id
                or java.get("fixtureRoot") != str(scratch)):
            raise ProfileError("Java capability report has the wrong run or fixture binding")
        for key, runtime_key in [("javaVersion", "java.version"), ("javaVendor", "java.vendor"),
                                 ("javaRuntimeVersion", "java.runtime.version"), ("osArch", "os.arch")]:
            if java.get(key) != report["tests"]["runtime"][runtime_key]:
                raise ProfileError("Java probe and test runtime differ")
        capabilities = java.get("observedCapabilities")
        if (not isinstance(capabilities, list) or not all(isinstance(c, str) for c in capabilities)
                or len(capabilities) != len(CAPABILITIES) or set(capabilities) != CAPABILITIES):
            raise ProfileError("Incomplete or duplicate capability evidence")
        if java.get("fileStoreType") != report["mount"]["fileSystem"]:
            raise ProfileError("Java probe and Linux mount filesystem types differ")
        report["java"] = java
        report["status"] = "checks_passed"
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        report["error"] = f"{type(error).__name__}: {error}"
    output = run / "profile.json"
    with output.open("x", encoding="utf-8") as destination:
        json.dump(report, destination, indent=2, ensure_ascii=True)
        destination.write("\n")
    # Keep every case in the retained run-owned JSON. Bounded CI output includes
    # profile facts plus every suite's count, without dumping fixture/test logs.
    summary = dict(report)
    if "tests" in summary:
        summary["tests"] = dict(summary["tests"])
        summary["tests"]["suites"] = [{"suite": s["suite"], "tests": s["tests"]}
                                      for s in report["tests"]["suites"]]
    print(json.dumps(summary, indent=2, ensure_ascii=True))
    print(f"Retained evidence: {output}")
    if report["status"] != "checks_passed" and (run / "maven.log").exists():
        with (run / "maven.log").open("rb") as log:
            log.seek(0, 2)
            log.seek(max(0, log.tell() - 16 * 1024))
            print(log.read().decode(errors="replace"))
    return 0 if report["status"] == "checks_passed" else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scratch-parent", type=Path, required=True,
                        help="Existing trusted stable directory; a fresh child is retained")
    parser.add_argument("--label", default="linux-local-development-v1")
    parser.add_argument("--timeout", type=int, default=360)
    args = parser.parse_args(argv)
    if sys.platform != "linux":
        parser.error("This harness requires actual Linux; platform names cannot emulate it")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", args.label) or not 30 <= args.timeout <= 600:
        parser.error("Use a simple label (1-64 characters) and timeout of 30-600 seconds")
    parent = args.scratch_parent.resolve(strict=True)
    if not parent.is_dir():
        parser.error("Scratch parent must be an existing directory")
    repo = Path(__file__).resolve().parents[2]
    return qualify(repo, parent, args.label, args.timeout)


if __name__ == "__main__":
    sys.exit(main())
