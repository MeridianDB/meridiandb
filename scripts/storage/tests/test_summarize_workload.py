"""Saved-record checks use synthetic evidence; no original host, Java or network is required."""

import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import summarize_workload as reporter
import test_workload_evidence as fixtures


class SavedWorkloadTests(unittest.TestCase):
    def setUp(self):
        # Import the fixture module, not a TestCase class alias: discovery must not duplicate its tests.
        self.fixture = fixtures.WorkerEvidenceTests()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.setUp()
        self.worker = self.fixture.scaled(warmups=1, repetitions=2)
        self.request = copy.deepcopy(self.fixture.request)
        self.saved = self.fixture.parent / "saved-records"
        self.saved.mkdir()
        self.recorded_root = PurePosixPath("/recorded-linux/mdb-measure-fixture")
        result = self.worker["result"]
        result["runRoot"] = str(self.recorded_root / "stores/mdb-workload-fixture")
        result["runtime"].update(osVersion="6.8.0-recorded", architecture="amd64")
        for index, iteration in enumerate(result["iterations"]):
            home = f"{result['runRoot']}/iteration-{index}"
            iteration["directory"] = home
            self.worker["io"][index]["directory"] = home
            # Distinct per-iteration distributions make accidental pooling observable.
            factor = index + 1
            for sample in iteration["samples"]:
                sample["elapsedNanos"] *= factor
            iteration["summaries"] = fixtures.summaries(iteration["samples"])
            service = sum(s["elapsedNanos"] for s in iteration["samples"]
                          if s["phase"] != "MAINTENANCE" and s["kind"] != "CREATE")
            actions = result["workload"]["attempts"] + result["workload"]["reads"]
            validation = actions * 2 * factor
            iteration.update(namespacePreparationNanos=20 * factor, actionServiceNanos=service,
                             actionValidationNanos=validation, actionOtherNanos=3 * factor,
                             actionLoopNanos=service + validation + 3 * factor,
                             totalValidationNanos=validation + 10 * factor)
            iteration["actionLoopOperationsPerSecond"] = actions * 1e9 / iteration["actionLoopNanos"]

        accounting = []
        for iteration, process_io in zip(result["iterations"], self.worker["io"]):
            files = [{"name": name, "logicalBytes": length,
                      "attributedAllocatedBytes": ((length + 4095) // 4096) * 4096}
                     for name, length in sorted(iteration["namedStorage"]["fileLengths"].items())]
            delta = copy.deepcopy(process_io["delta"])
            accounting.append({"index": iteration["index"], "warmup": iteration["warmup"],
                               "directory": iteration["directory"], "files": files,
                               "logicalBytes": sum(f["logicalBytes"] for f in files),
                               "attributedAllocatedBytes": sum(f["attributedAllocatedBytes"] for f in files),
                               "processIo": {"delta": delta, "unavailable": None,
                               "processReadBytesPerEncodedJournalByte": delta["read_bytes"] / result["encodedHistoryBytes"],
                               "processWriteBytesPerEncodedJournalByte": delta["write_bytes"] / result["encodedHistoryBytes"]}})
        wrapper = "/recorded-source/meridiandb/mvnw"
        self.report = {
            "schemaVersion": 2, "runId": self.request["runId"], "status": "checks_passed",
            "productionQualified": False, "observedAtUtc": "2026-11-07T12:00:00+00:00",
            "request": copy.deepcopy(self.request), "runRoot": str(self.recorded_root),
            "kernel": "6.8.0-recorded", "architecture": "x86_64", "cache": "uncontrolled fixture cache",
            "heapLimitBytes": 256 * 1024 * 1024, "evidenceScope": "Recorded instrumented developer observations",
            "gaps": ["Backing storage was not qualified"],
            "source": {"commit": self.request["source"], "dirty": False,
                       "statusSha256": hashlib.sha256(b"").hexdigest(),
                       "tree": {"sha256": "d" * 64, "files": 369, "bytes": 4834989}},
            "classes": {"sha256": "e" * 64, "files": 446, "bytes": 3196684},
            "mount": {"mountId": "28", "majorMinor": "8:1", "mountPoint": "/", "fileSystem": "ext4",
                      "recognizedOptions": ["relatime", "rw"], "unreportedOptionCount": 1},
            "device": {"writeCache": None, "logicalBlockBytes": None, "physicalBlockBytes": None,
                       "rotational": None, "backingDeviceAndController": "unknown recorded backing storage"},
            "buildCommand": [wrapper, "-B", "clean", "test-compile"],
            "build": {"pid": 1000, "startTicks": 123, "exitCode": 0, "logBytes": 73, "groupSettled": True},
            "workerCommand": ["/recorded-jdk/bin/java", "-Xmx256m", f"-Djava.io.tmpdir={self.recorded_root}/tmp",
                              "--class-path", "/recorded-source/meridiandb/target/classes:/recorded-source/meridiandb/target/test-classes",
                              "org.meridiandb.workload.WorkloadWorker", self.request["runId"], self.request["source"],
                              self.request["profile"], str(self.request["seed"]), str(self.request["warmups"]),
                              str(self.request["repetitions"]), f"{self.recorded_root}/stores", f"{self.recorded_root}/worker.json",
                              *[str(self.request["dimensions"][k]) for k in ("entities", "commits", "queries", "intervalWidth")]],
            "worker": {"pid": self.fixture.pid, "startTicks": 234, "exitCode": 0,
                       "logBytes": 0, "groupSettled": True},
            "workerResultSha256": "0" * 64, "workerResultBytes": 1,
            "accounting": {"schemaVersion": 1, "iterations": accounting,
                           "retainedFileCount": sum(len(x["files"]) for x in accounting),
                           "totalLogicalBytes": sum(x["logicalBytes"] for x in accounting),
                           "totalAttributedAllocatedBytes": sum(x["attributedAllocatedBytes"] for x in accounting),
                           "limits": ["Allocation is recorded attribution, not exclusive physical space"]},
            "workload": {key: copy.deepcopy(result[key]) for key in reporter.WORKLOAD_COPY},
            "retainedBytesBeforeReport": 1, "reservedReportBytes": 16 * 1024 * 1024,
            "elapsedSecondsBeforeReport": 12.5, "deadlineExceededBeforeReport": False,
        }
        self.store(rebind_worker=True)

    def store(self, request=None, report=None, worker=None, rebind_worker=False):
        request = self.request if request is None else request
        report = self.report if report is None else report
        worker = self.worker if worker is None else worker
        request_bytes = json.dumps(request, sort_keys=True, ensure_ascii=True, allow_nan=False).encode() + b"\n"
        worker_bytes = json.dumps(worker, sort_keys=True, ensure_ascii=True, allow_nan=False).encode() + b"\n"
        if rebind_worker:
            report["workerResultSha256"] = hashlib.sha256(worker_bytes).hexdigest()
            report["workerResultBytes"] = len(worker_bytes)
            report["retainedBytesBeforeReport"] = (report["accounting"]["totalLogicalBytes"]
                    + len(request_bytes) + len(worker_bytes) + report["build"]["logBytes"] + report["worker"]["logBytes"] + 4096)
        for name, image in (("request.json", request_bytes), ("worker.json", worker_bytes),
                            ("report.json", json.dumps(report, sort_keys=True, ensure_ascii=True, allow_nan=False).encode() + b"\n")):
            (self.saved / name).write_bytes(image)

    def rejects_report(self, change):
        report = copy.deepcopy(self.report)
        change(report)
        self.store(report=report)
        with self.assertRaises(reporter.EvidenceError):
            reporter.summarize(self.saved)

    def test_recorded_linux_context_is_accepted_without_original_paths_processes_or_host(self):
        shutil.rmtree(self.fixture.stores)
        before = {p.name: p.read_bytes() for p in self.saved.iterdir()}
        with mock.patch.object(subprocess, "Popen", side_effect=AssertionError("No execution in saved reporting")), \
                mock.patch.object(fixtures.evidence.platform, "release", return_value="another-kernel"), \
                mock.patch.object(fixtures.evidence.platform, "machine", return_value="another-architecture"):
            summary = reporter.summarize(self.saved, self.request["source"], self.request["runId"])
        self.assertEqual("recorded_consistency_passed", summary["status"])
        self.assertFalse(summary["productionQualified"])
        self.assertIn("not reobserved", summary["validationScope"])
        self.assertEqual("6.8.0-recorded", summary["kernel"])
        self.assertEqual("amd64", summary["runtime"]["architecture"])
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.saved.iterdir()})
        for name, image in before.items():
            self.assertEqual({"bytes": len(image), "sha256": hashlib.sha256(image).hexdigest()}, summary["inputFiles"][name])

    def test_warmups_and_distinct_measured_distributions_remain_separate_without_pooling(self):
        summary = reporter.summarize(self.saved)
        self.assertEqual([0], [x["index"] for x in summary["warmupIterations"]])
        self.assertEqual([1, 2], [x["index"] for x in summary["measuredIterations"]])
        rows = summary["warmupIterations"] + summary["measuredIterations"]
        for original, copied in zip(self.worker["result"]["iterations"], rows):
            self.assertEqual(original["summaries"], copied["summaries"])
            self.assertEqual(original["actionLoopNanos"], copied["actionLoopNanos"])
        load_medians = [next(g["p50Nanos"] for g in x["summaries"] if g["phase"] == "LOAD" and g["kind"] == "COMMIT") for x in rows]
        self.assertEqual([4, 8, 12], load_medians)
        self.assertNotIn("pooled", summary)

    def test_selected_source_and_run_are_required_to_match_the_saved_observation(self):
        for source, run in (("f" * 40, None), (None, "f" * 32), (True, None), (None, True), ("short", None)):
            with self.subTest(source=source, run=run), self.assertRaises(reporter.EvidenceError):
                reporter.summarize(self.saved, source, run)

    def test_partial_or_missing_final_worker_cannot_be_reported_as_success(self):
        (self.saved / "worker.json").rename(self.saved / "worker.partial.json")
        with self.assertRaises(OSError):
            reporter.summarize(self.saved)
        output, diagnostic = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(diagnostic):
            self.assertEqual(1, reporter.main(["--run-dir", str(self.saved)]))
        self.assertEqual("", output.getvalue())
        self.assertIn("refused", diagnostic.getvalue())

    def test_failed_unsupported_incomplete_and_version_mixed_reports_are_refused(self):
        mutations = [lambda r: r.update(status="failed"), lambda r: r.update(status="unsupported"),
                     lambda r: r.update(productionQualified=True), lambda r: r.update(schemaVersion=1),
                     lambda r: r.update(extra=True), lambda r: r.pop("classes")]
        for change in mutations:
            with self.subTest(change=change):
                self.rejects_report(change)
        for status in ("failed", "unsupported"):
            worker = copy.deepcopy(self.worker)
            worker.update(status=status, error={"type": "IOException", "message": "recorded failure"}, result=None)
            report = copy.deepcopy(self.report)
            self.store(report=report, worker=worker, rebind_worker=True)
            with self.subTest(worker_status=status), self.assertRaises(reporter.EvidenceError):
                reporter.summarize(self.saved)

    def test_mixed_requests_dimensions_workload_copies_and_paths_are_refused(self):
        for change in (lambda r: r.update(runId="f" * 32),
                       lambda r: r["request"].update(seed=43),
                       lambda r: r["request"]["dimensions"].update(commits=34),
                       lambda r: r["workload"]["spec"].update(entities=8),
                       lambda r: r["workload"].update(fingerprint="f" * 64),
                       lambda r: r.update(runRoot="/another/mdb-measure-fixture")):
            with self.subTest(change=change):
                self.rejects_report(change)

    def test_worker_byte_hash_and_length_bindings_cover_even_valid_json_whitespace(self):
        with (self.saved / "worker.json").open("ab") as destination:
            destination.write(b" ")
        with self.assertRaises(reporter.EvidenceError):
            reporter.summarize(self.saved)
        for change in (lambda r: r.update(workerResultSha256="f" * 64),
                       lambda r: r.update(workerResultBytes=r["workerResultBytes"] + 1),
                       lambda r: r.update(workerResultBytes=True)):
            with self.subTest(change=change):
                self.rejects_report(change)

    def test_rehashed_worker_metric_and_counter_tampering_still_fails_recomputation(self):
        mutations = [lambda w: w["result"]["iterations"][0]["summaries"][0].update(p50Nanos=99),
                     lambda w: w["result"]["iterations"][0]["samples"][1].update(elapsedNanos=99),
                     lambda w: w["result"]["iterations"][0].update(actionServiceNanos=1),
                     lambda w: w["io"][0]["delta"].update(read_bytes=999),
                     lambda w: w["result"]["iterations"][0]["summaries"][0].update(threadCpuNanos=None)]
        for change in mutations:
            worker, report = copy.deepcopy(self.worker), copy.deepcopy(self.report)
            change(worker)
            self.store(report=report, worker=worker, rebind_worker=True)
            with self.subTest(change=change), self.assertRaises(reporter.EvidenceError):
                reporter.summarize(self.saved)

    def test_source_and_compiled_class_snapshots_retain_exact_types_and_bounds(self):
        mutations = [lambda r: r["source"].update(commit="f" * 40),
                     lambda r: r["source"].update(dirty=True),
                     lambda r: r["source"].update(dirty=0),
                     lambda r: r["source"].update(statusSha256="invalid"),
                     lambda r: r["source"]["tree"].update(files=0),
                     lambda r: r["classes"].update(sha256="invalid"),
                     lambda r: r["classes"].update(files=True),
                     lambda r: r["classes"].update(bytes=64 * 1024 * 1024 + 1)]
        for change in mutations:
            with self.subTest(change=change):
                self.rejects_report(change)
        report = copy.deepcopy(self.report)
        report["source"].update(dirty=True, statusSha256=hashlib.sha256(b" M source.java").hexdigest())
        self.store(report=report)
        self.assertTrue(reporter.summarize(self.saved)["source"]["dirty"])

    def test_process_exit_settlement_pid_and_elapsed_deadline_bindings_are_checked(self):
        mutations = [lambda r: r["build"].update(exitCode=1),
                     lambda r: r["worker"].update(exitCode=3),
                     lambda r: r["worker"].update(groupSettled=False),
                     lambda r: r["build"].update(groupSettled=1),
                     lambda r: r["worker"].update(pid=9999),
                     lambda r: r["build"].update(pid=r["worker"]["pid"]),
                     lambda r: r["build"].update(startTicks=0),
                     lambda r: r["worker"].update(logBytes=2 * 1024 * 1024 + 1),
                     lambda r: r.update(deadlineExceededBeforeReport=True),
                     lambda r: r.update(deadlineExceededBeforeReport=0),
                     lambda r: r.update(elapsedSecondsBeforeReport=601),
                     lambda r: r.update(elapsedSecondsBeforeReport=0),
                     lambda r: r.update(elapsedSecondsBeforeReport=True)]
        for change in mutations:
            with self.subTest(change=change):
                self.rejects_report(change)
        # The fixture contributes 3,274 ns of known nonoverlapping work. Producer elapsed
        # seconds are rounded to six decimal places, with a 1,000 ns rounding allowance.
        report = copy.deepcopy(self.report)
        report["elapsedSecondsBeforeReport"] = 0.000003
        self.store(report=report)
        self.assertEqual("recorded_consistency_passed", reporter.summarize(self.saved)["status"])
        report["elapsedSecondsBeforeReport"] = 0.000002
        self.store(report=report)
        with self.assertRaises(reporter.EvidenceError):
            reporter.summarize(self.saved)

    def test_recorded_commands_heap_runtime_and_filesystem_are_bound_to_the_request(self):
        mutations = [lambda r: r["buildCommand"].__setitem__(2, "verify"),
                     lambda r: r["workerCommand"].__setitem__(1, "-Xmx512m"),
                     lambda r: r["workerCommand"].__setitem__(4, "/stale/classes"),
                     lambda r: r["workerCommand"].__setitem__(9, "43"),
                     lambda r: r["workerCommand"].__setitem__(14, "8"),
                     lambda r: r.update(heapLimitBytes=512 * 1024 * 1024),
                     lambda r: r.update(kernel="different-kernel"),
                     lambda r: r.update(architecture="different-architecture"),
                     lambda r: r["mount"].update(fileSystem="another-fs"),
                     lambda r: r["mount"].update(mountPoint="/unrelated-mount"),
                     lambda r: r.update(observedAtUtc="2026-11-07T12:00:00+01:00")]
        for change in mutations:
            with self.subTest(change=change):
                self.rejects_report(change)

    def test_recorded_file_lengths_allocations_process_ratios_and_aggregate_totals_are_checked(self):
        mutations = [lambda r: r["accounting"]["iterations"][0]["files"][0].update(logicalBytes=1),
                     lambda r: r["accounting"]["iterations"][0]["files"][0].update(name="unknown"),
                     lambda r: r["accounting"]["iterations"][0]["files"].append(copy.deepcopy(r["accounting"]["iterations"][0]["files"][0])),
                     lambda r: r["accounting"]["iterations"][0]["files"][0].update(attributedAllocatedBytes=1),
                     lambda r: r["accounting"]["iterations"][0].update(attributedAllocatedBytes=512),
                     lambda r: r["accounting"]["iterations"][0]["processIo"].update(processReadBytesPerEncodedJournalByte=0.0),
                     lambda r: r["accounting"].update(retainedFileCount=1),
                     lambda r: r["accounting"].update(totalLogicalBytes=True),
                     lambda r: r["accounting"].update(totalAttributedAllocatedBytes=0),
                     lambda r: r.update(retainedBytesBeforeReport=1),
                     lambda r: r.update(reservedReportBytes=1)]
        for change in mutations:
            with self.subTest(change=change):
                self.rejects_report(change)

    def test_malformed_ambiguous_and_nonfinite_json_never_reaches_a_success_report(self):
        originals = {p.name: p.read_bytes() for p in self.saved.iterdir()}
        for name, image in (("request.json", b'{"schemaVersion":2,"schemaVersion":2}'),
                            ("report.json", b'{"x":NaN}'), ("report.json", b'{"x":1e999}'),
                            ("worker.json", b'{'), ("worker.json", b'\xff')):
            for original_name, original in originals.items():
                (self.saved / original_name).write_bytes(original)
            (self.saved / name).write_bytes(image)
            with self.subTest(name=name, image=image), self.assertRaises(reporter.EvidenceError):
                reporter.summarize(self.saved)

    def test_input_file_and_aggregate_byte_admission_precedes_json_materialization(self):
        total = sum(p.stat().st_size for p in self.saved.iterdir())
        with mock.patch.object(reporter, "MAX_INPUT_BYTES", total - 1), \
                mock.patch.object(reporter, "parse_document") as decode:
            with self.assertRaises(reporter.EvidenceError):
                reporter.summarize(self.saved)
            decode.assert_not_called()
        with mock.patch.object(reporter, "MAX_INPUT_BYTES", total):
            self.assertEqual("recorded_consistency_passed", reporter.summarize(self.saved)["status"])
        with mock.patch.object(reporter, "MAX_DOCUMENT_BYTES", 32), \
                mock.patch.object(reporter, "parse_document") as decode:
            with self.assertRaises(reporter.EvidenceError):
                reporter.summarize(self.saved)
            decode.assert_not_called()
        (self.saved / "request.json").write_bytes(b" " * 65537)
        with mock.patch.object(reporter, "parse_document") as decode:
            with self.assertRaises(reporter.EvidenceError):
                reporter.summarize(self.saved)
            decode.assert_not_called()

    def test_symlink_directory_and_fifo_inputs_are_refused_before_opening(self):
        target = self.saved / "worker.json"
        image = target.read_bytes()
        target.unlink()
        alias = self.saved / "alias.json"
        alias.write_bytes(image)
        target.symlink_to(alias)
        with mock.patch.object(reporter.os, "open") as opening:
            with self.assertRaises(reporter.EvidenceError):
                reporter.read_input(target, reporter.MAX_DOCUMENT_BYTES)
            opening.assert_not_called()
        target.unlink()
        target.mkdir()
        with self.assertRaises(reporter.EvidenceError):
            reporter.read_input(target, reporter.MAX_DOCUMENT_BYTES)
        target.rmdir()
        if hasattr(os, "mkfifo"):
            os.mkfifo(target)
            with mock.patch.object(reporter.os, "open") as opening:
                with self.assertRaises(reporter.EvidenceError):
                    reporter.read_input(target, reporter.MAX_DOCUMENT_BYTES)
                opening.assert_not_called()
            target.unlink()
        target.write_bytes(image)
        actual_open = os.open
        with mock.patch.object(reporter.os, "open", side_effect=lambda _path, flags: actual_open(alias, flags)):
            with self.assertRaisesRegex(reporter.EvidenceError, "changed before reading"):
                reporter.read_input(target, reporter.MAX_DOCUMENT_BYTES)

    def test_null_telemetry_is_preserved_and_markdown_states_clock_units_and_availability(self):
        worker, report = copy.deepcopy(self.worker), copy.deepcopy(self.report)
        for iteration in worker["result"]["iterations"]:
            for sample in iteration["samples"]:
                sample.update(threadCpuNanos=None, threadAllocatedBytes=None)
            iteration["summaries"] = fixtures.summaries(iteration["samples"])
        self.store(worker=worker, report=report, rebind_worker=True)
        summary = reporter.summarize(self.saved)
        group = summary["measuredIterations"][0]["summaries"][0]
        self.assertIsNone(group["threadCpuNanos"])
        self.assertIsNone(group["threadAllocatedBytes"])
        self.assertEqual(0, group["threadCpuSamples"])
        markdown = reporter.render_markdown(summary)
        for phrase in ("p50 ns", "p95 ns", "p99 ns", "CPU ns", "Allocated bytes", "sampled used heap bytes",
                       "unavailable (0)", "Warmup iteration 0", "Measured iteration 1", "Measured iteration 2",
                       "whole instrumented iteration", "not reobserved", "Namespace preparation ns",
                       "Total validation ns", "Recorded source dirty", "Input provenance", "Recorded environment"):
            self.assertIn(phrase, markdown)
        for label, value in (("Source tree SHA-256", summary["source"]["tree"]["sha256"]),
                             ("Compiled classpath SHA-256", summary["classes"]["sha256"]),
                             ("Schedule fingerprint", summary["fingerprint"]),
                             ("History fingerprint", summary["historyFingerprint"]),
                             ("Encoded journal bytes", summary["encodedHistoryBytes"]),
                             ("Recorded at UTC", summary["observedAtUtc"])):
            self.assertIn(f"| {label} | {value} |", markdown)
        self.assertIn("| Recorded source dirty | False |", markdown)
        for name, item in summary["inputFiles"].items():
            self.assertIn(f"| {name} | {item['bytes']} | {item['sha256']} |", markdown)
        for item in summary["warmupIterations"] + summary["measuredIterations"]:
            self.assertIn(f"Namespace preparation ns: {item['namespacePreparationNanos']}.", markdown)
            self.assertIn(f"Total validation ns: {item['totalValidationNanos']} across {item['validationTasks']} tasks.", markdown)
        blocks = re.findall(r"(?ms)^```json\n(.*?)\n```(?=\n|$)", markdown)
        environment = json.loads(blocks[0])
        self.assertEqual({"mount": summary["mount"], "device": summary["device"], "runtime": summary["runtime"]}, environment)

    def test_markdown_escapes_recorded_metadata_and_unavailable_io_reasons(self):
        summary = reporter.summarize(self.saved)
        hostile = r"vendor\|<script>`text` ``` [link]*_~"
        summary["runtime"]["javaVendor"] = hostile
        summary["producerGaps"] = [hostile]
        for item in summary["warmupIterations"] + summary["measuredIterations"]:
            item["processIo"] = {"delta": None, "unavailable": hostile,
                                 "processReadBytesPerEncodedJournalByte": None,
                                 "processWriteBytesPerEncodedJournalByte": None}
        markdown = reporter.render_markdown(summary)
        blocks = re.findall(r"(?ms)^```json\n(.*?)\n```(?=\n|$)", markdown)
        self.assertEqual(4, len(blocks), "One environment and three per-iteration JSON blocks remain safely fenced")
        self.assertEqual(hostile, json.loads(blocks[0])["runtime"]["javaVendor"])
        self.assertTrue(all(json.loads(block)["unavailable"] == hostile for block in blocks[1:]))
        outside = re.sub(r"(?ms)^```json\n.*?\n```(?=\n|$)", "", markdown)
        self.assertNotIn("<script>", outside)
        self.assertNotIn("`text`", outside)
        self.assertIn("&lt;script&gt;", outside)
        self.assertIn("&#96;text&#96;", outside)
        line = next(line for line in outside.splitlines() if line.startswith("| JVM |"))
        self.assertEqual(3, line.count("|"), "A backslash/pipe pair cannot create an extra Markdown table column")
        self.assertIn("&#92;&#124;", line)
        for character in "|\\`[]*_~":
            self.assertIn(f"&#{ord(character)};", line)

    def test_cli_selects_json_or_markdown_without_modifying_inputs_and_refuses_stale_selection(self):
        before = {p.name: p.read_bytes() for p in self.saved.iterdir()}
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, reporter.main(["--run-dir", str(self.saved), "--expect-source", self.request["source"],
                                              "--expect-run", self.request["runId"]]))
        self.assertEqual("recorded_consistency_passed", json.loads(output.getvalue())["status"])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, reporter.main(["--run-dir", str(self.saved), "--format", "markdown"]))
        self.assertTrue(output.getvalue().startswith("# Recorded workload observations\n"))
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.saved.iterdir()})
        output, diagnostic = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(diagnostic):
            self.assertEqual(1, reporter.main(["--run-dir", str(self.saved), "--expect-run", "f" * 32]))
        self.assertEqual("", output.getvalue())
        self.assertIn("refused", diagnostic.getvalue())

    def test_cli_output_limit_refuses_before_printing_a_partial_summary(self):
        output, diagnostic = io.StringIO(), io.StringIO()
        with mock.patch.object(reporter, "MAX_OUTPUT_BYTES", 32), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(diagnostic):
            self.assertEqual(1, reporter.main(["--run-dir", str(self.saved)]))
        self.assertEqual("", output.getvalue())
        self.assertIn("output admission", diagnostic.getvalue())


if __name__ == "__main__":
    unittest.main()
