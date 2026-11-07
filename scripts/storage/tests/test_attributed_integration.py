"""Schema-three integration uses native counter fixtures with synthetic recorded host context."""

import contextlib
import copy
import gzip
import hashlib
import html
import io
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import attributed_workload as attributed
import run_workload as runner
import summarize_workload as reporter
import workload_evidence as evidence


FIXTURES = Path(__file__).with_name("fixtures")


class AttributedIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.saved = Path(self.temporary.name)
        self.select("attributed-tiny-v3.json.gz")

    def select(self, name):
        bundle = json.loads(gzip.decompress((FIXTURES / name).read_bytes()))
        self.request, self.worker = bundle["request"], bundle["worker"]
        self.root = "/recorded-linux/mdb-measure-attributed-fixture"
        result = self.worker["result"]
        result["runRoot"] = self.root + "/stores/mdb-workload-fixture"
        result["runtime"].update(osName="Linux", osVersion="fixture-kernel", architecture="amd64", fileStoreType="ext4")
        for index, iteration in enumerate(result["iterations"]):
            iteration["directory"] = result["runRoot"] + f"/iteration-{index}"
            self.worker["io"][index]["directory"] = iteration["directory"]
        self.mount = {"mountId": "28", "majorMinor": "8:1", "mountPoint": "/", "fileSystem": "ext4",
                      "recognizedOptions": ["relatime", "rw"], "unreportedOptionCount": 0}
        self.checked = self.validate(self.worker)
        files = []
        for iteration in self.checked["iterations"]:
            entries = [{"name": name, "logicalBytes": length, "attributedAllocatedBytes": ((length + 4095) // 4096) * 4096}
                       for name, length in sorted(iteration["fileLengths"].items())]
            files.append({"index": iteration["index"], "warmup": iteration["warmup"], "directory": iteration["directory"],
                          "files": entries, "logicalBytes": iteration["logicalBytes"],
                          "attributedAllocatedBytes": sum(item["attributedAllocatedBytes"] for item in entries),
                          "processIo": iteration["processIo"]})
        accounting = {"schemaVersion": 1, "retainedFileCount": self.checked["retainedFileCount"],
                      "totalLogicalBytes": self.checked["totalLogicalBytes"],
                      "totalAttributedAllocatedBytes": sum(item["attributedAllocatedBytes"] for item in files),
                      "iterations": files, "limits": ["Synthetic allocation units for recorded-reader tests"]}
        wrapper = "/recorded-source/meridiandb/mvnw"
        self.report = {
            "schemaVersion": 3, "mode": attributed.MODE, "runId": self.request["runId"], "status": "checks_passed",
            "productionQualified": False, "observedAtUtc": "2026-11-07T14:00:00+00:00", "request": copy.deepcopy(self.request),
            "runRoot": self.root, "kernel": "fixture-kernel", "architecture": "x86_64", "cache": "uncontrolled fixture",
            "heapLimitBytes": 256 * 1024 * 1024, "evidenceScope": "Synthetic recorded context around native counter fixture",
            "gaps": ["This fixture is not a performance observation or producer attestation"],
            "source": {"commit": self.request["source"], "dirty": False, "statusSha256": hashlib.sha256(b"").hexdigest(),
                       "tree": {"sha256": "d" * 64, "files": 400, "bytes": 5000000}},
            "classes": {"sha256": "e" * 64, "files": 480, "bytes": 3300000}, "mount": self.mount,
            "device": {"writeCache": None, "logicalBlockBytes": None, "physicalBlockBytes": None,
                       "rotational": None, "backingDeviceAndController": "unknown fixture"},
            "buildCommand": [wrapper, "-B", "clean", "test-compile"],
            "build": {"pid": 1000, "startTicks": 123, "exitCode": 0, "logBytes": 50, "groupSettled": True},
            "workerCommand": ["/recorded-jdk/bin/java", "-Xmx256m", f"-Djava.io.tmpdir={self.root}/tmp", "--class-path",
                              "/recorded-source/meridiandb/target/classes:/recorded-source/meridiandb/target/test-classes",
                              "org.meridiandb.workload.WorkloadWorker", "--attributed", self.request["runId"],
                              self.request["source"], self.request["profile"], str(self.request["seed"]),
                              str(self.request["warmups"]), str(self.request["repetitions"]),
                              f"{self.root}/stores", f"{self.root}/worker.json",
                              *[str(self.request["dimensions"][key]) for key in evidence.DIMENSION_FIELDS]],
            "worker": {"pid": self.worker["workerPid"], "startTicks": 234, "exitCode": 0, "logBytes": 0, "groupSettled": True},
            "accounting": accounting, "workload": {key: result[key] for key in reporter.WORKLOAD_COPY},
            "attribution": self.checked["attribution"], "retainedBytesBeforeReport": 0,
            "reservedReportBytes": evidence.MAX_DOCUMENT_BYTES, "elapsedSecondsBeforeReport": 599.0,
            "deadlineExceededBeforeReport": False, "workerResultBytes": 0, "workerResultSha256": "0" * 64,
        }
        self.store()

    def validate(self, worker, request=None):
        return evidence.validate_recorded_worker(worker, self.request if request is None else request,
                worker["workerPid"], Path(self.root + "/stores"), self.mount, "fixture-kernel", "x86_64")

    def store(self):
        request = json.dumps(self.request, ensure_ascii=True).encode() + b"\n"
        worker = json.dumps(self.worker, ensure_ascii=True).encode() + b"\n"
        self.report.update(workerResultBytes=len(worker), workerResultSha256=hashlib.sha256(worker).hexdigest(),
                retainedBytesBeforeReport=self.checked["totalLogicalBytes"] + len(request) + len(worker) + 8192)
        for name, data in (("request.json", request), ("worker.json", worker),
                           ("report.json", json.dumps(self.report, ensure_ascii=True).encode() + b"\n")):
            (self.saved / name).write_bytes(data)

    def test_native_tiny_fixture_checks_logical_source_lengths_raw_groups_and_zero_denominator(self):
        summary = reporter.summarize(self.saved)
        self.assertEqual(2, summary["schemaVersion"])
        self.assertEqual(attributed.MODE, summary["mode"])
        self.assertEqual(198, summary["encodedHistoryBytes"])
        denoms = summary["attribution"]["denominators"]
        self.assertEqual((42, 78, 78), tuple(denoms[k] for k in ("headerBytes", "seedFrameBytes", "mixedFrameBytes")))
        groups = summary["attribution"]["iterations"][0]["operationGroups"]
        self.assertEqual([78, 78], [group["totalWriteBytes"] for group in groups if group["kind"] == "COMMIT"])
        queries = [group for group in groups if group["kind"] in evidence.READ_COUNTS]
        self.assertEqual(5, len(queries))
        self.assertTrue(all(ratio["denominator"] == 0 and ratio["value"] is None
                            for group in queries for ratio in group["ratios"].values()))
        rendered = reporter.render_markdown(summary)
        self.assertIn(attributed.MODE, rendered)
        self.assertIn("N/A (zero logical denominator)", rendered)
        self.assertIn("EXCLUDED_EXTERNAL_IO", html.unescape(rendered))

    def test_recorded_reader_never_reopens_original_paths_or_executes_producer_commands(self):
        with mock.patch.object(evidence, "_files", side_effect=AssertionError("native reobservation forbidden")), \
                mock.patch.object(runner, "run_group", side_effect=AssertionError("execution forbidden")):
            self.assertEqual("recorded_consistency_passed", reporter.summarize(self.saved)["status"])

    def test_mode_and_schema_mixing_or_prefix_command_changes_are_refused(self):
        for change in (lambda: self.report.update(mode="ordinary"), lambda: self.request.update(schemaVersion=2),
                       lambda: self.worker.update(mode="ordinary"), lambda: self.report.update(schemaVersion=2),
                       lambda: self.report["workerCommand"].remove("--attributed")):
            with self.subTest(change=change):
                self.select("attributed-tiny-v3.json.gz")
                change(); self.store()
                with self.assertRaises(evidence.EvidenceError): reporter.summarize(self.saved)

    def test_rebound_worker_hash_cannot_hide_missing_failed_unknown_or_corrupt_counter_history(self):
        for change in (lambda a: a["scopes"].pop(),
                       lambda a: a["scopes"][0]["counters"].update(overflow=True),
                       lambda a: a["scopes"][0].update(callReturned=False, operationFailureType="IOException"),
                       lambda a: a["denominators"].update(seedFrameBytes=79),
                       lambda a: a["sourceHistory"]["framesHex"].__setitem__(0, "00"),
                       lambda a: a["scopes"][0]["counters"]["counts"][7].__setitem__(0, 1)):
            with self.subTest(change=change):
                self.select("attributed-tiny-v3.json.gz")
                change(self.worker["attribution"]); self.store()
                with self.assertRaises(evidence.EvidenceError): reporter.summarize(self.saved)

    def test_cached_attribution_aggregate_or_ratio_changes_are_refused(self):
        for field in ("totalReadBytes", "totalWriteBytes", "totalForceSuccesses"):
            self.select("attributed-tiny-v3.json.gz")
            self.report["attribution"]["iterations"][0]["operationGroups"][0][field] += 1
            self.store()
            with self.assertRaises(evidence.EvidenceError): reporter.summarize(self.saved)

    def test_failure_mode_binding_preserves_partial_scopes_without_accepting_a_result(self):
        document = copy.deepcopy(self.worker)
        document.update(status="failed", result=None, error={"type": "IOException", "message": "retained failure"})
        document["attribution"]["scopes"] = document["attribution"]["scopes"][:3]
        self.assertEqual(("failed", document["error"], 1), runner.failure_envelope(document, self.request, document["workerPid"], 1))
        document["mode"] = "ordinary"
        with self.assertRaises(runner.SupervisorError): runner.failure_envelope(document, self.request, document["workerPid"], 1)
        with self.assertRaises(evidence.EvidenceError): self.validate(document)

    def test_supervisor_budget_rejects_before_parent_inspection_build_or_namespace_creation(self):
        missing = self.saved / "missing"
        with mock.patch.object(runner.sys, "platform", "linux"), \
                mock.patch.object(runner, "run_group", side_effect=AssertionError("build forbidden")):
            with self.assertRaises(runner.SupervisorError):
                runner.supervise(self.saved, missing, "CORRECTIONS", 42, 1, 3, 30,
                                 dict(entities=1, commits=100, queries=1024, intervalWidth=1), attributed=True)
        self.assertFalse(missing.exists())
        self.assertFalse(list(self.saved.glob("mdb-measure-*")))

    def test_compact_ci_log_retains_mode_but_omits_raw_scope_rows(self):
        logged = runner.log_summary(self.report)
        self.assertEqual(attributed.MODE, logged["mode"])
        self.assertEqual(self.checked["attribution"]["denominators"], logged["attribution"]["denominators"])
        self.assertNotIn('"scopes"', json.dumps(logged))
        self.assertLess(len(json.dumps(logged)), 65536)

    def test_maximum_admitted_native_fixture_fits_both_decoders_and_report_output_limits(self):
        self.select("attributed-max-v3.json.gz")
        self.assertEqual(2016, len(self.worker["attribution"]["scopes"]))
        evidence.parse_document((self.saved / "worker.json").read_bytes())
        evidence.parse_document((self.saved / "report.json").read_bytes())
        summary = reporter.summarize(self.saved)
        self.assertLessEqual(len(json.dumps(summary).encode()), reporter.MAX_OUTPUT_BYTES)
        self.assertLessEqual(len(reporter.render_markdown(summary).encode()), reporter.MAX_OUTPUT_BYTES)
        self.assertEqual(2048, attributed.request_budget(self.request["profile"], self.request["seed"],
                            self.request["dimensions"], self.request["warmups"], self.request["repetitions"]))

    def test_cli_reports_mode_without_original_host_and_emits_no_success_for_failed_raw_evidence(self):
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            self.assertEqual(0, reporter.main(["--run-dir", str(self.saved), "--format", "markdown"]))
        self.assertIn("Recorded attributed file work", output.getvalue())
        self.worker["status"] = "failed"; self.store()
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            self.assertEqual(1, reporter.main(["--run-dir", str(self.saved)]))
        self.assertEqual("", output.getvalue())
        self.assertIn("refused", errors.getvalue())


if __name__ == "__main__": unittest.main()
