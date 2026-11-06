"""Supervisor boundaries without paid resources, cloud calls or production stores."""

import contextlib
import copy
import io
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import time
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_workload as runner


class WorkloadSupervisorTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()

    def test_environment_removes_jvm_maven_git_injection_and_preserves_normal_selection(self):
        incoming = {name: "injected" for name in runner.INJECTION_VARIABLES}
        incoming.update({"JAVA_HOME": "/jdk", "PATH": "/bin", "GIT_DIR": "/another-checkout"})
        result = runner.clean_environment(incoming)
        self.assertEqual({"JAVA_HOME": "/jdk", "PATH": "/bin", "MAVEN_OPTS": "-Xmx256m", "MAVEN_SKIP_RC": "1"}, result)
        self.assertEqual("injected", incoming["JAVA_TOOL_OPTIONS"])

    def test_process_identity_ignores_parentheses_in_the_process_name(self):
        fields = ["S", "7", "33", "33"] + ["0"] * 15 + ["98765"] + ["0"] * 10
        identity = runner.proc_stat(b"33 (private ) name) " + " ".join(fields).encode())
        self.assertEqual({"pid": 33, "state": "S", "group": 33, "session": 33, "startTicks": 98765}, identity)
        with self.assertRaises(runner.SupervisorError):
            runner.proc_stat(b"not-stat")

    def test_invalid_requests_fail_before_any_scratch_mutation(self):
        for profile, seed, warmups, repeats, timeout in [
                ("unknown", 42, 0, 1, 300), ("APPEND", True, 0, 1, 300),
                ("APPEND", 2**63, 0, 1, 300), ("APPEND", 42, True, 1, 300),
                ("APPEND", 42, 3, 1, 300), ("APPEND", 42, 0, 6, 300),
                ("APPEND", 42, 0, 1, 601)]:
            with mock.patch.object(runner.sys, "platform", "linux"):
                with self.assertRaises(runner.SupervisorError):
                    runner.supervise(self.root, self.root, profile, seed, warmups, repeats, timeout)
        self.assertEqual([], list(self.root.iterdir()))

    def test_fingerprint_detects_changed_content_added_orphan_and_missing_inputs(self):
        (self.root / "one.class").write_bytes(b"first")
        first = runner.tree_fingerprint(self.root, [Path("one.class")], time.monotonic() + 10)
        (self.root / "one.class").write_bytes(b"other")
        changed = runner.tree_fingerprint(self.root, [Path("one.class")], time.monotonic() + 10)
        self.assertNotEqual(first, changed)
        (self.root / "orphan.class").write_bytes(b"orphan")
        orphan = runner.tree_fingerprint(self.root, [Path("one.class"), Path("orphan.class")], time.monotonic() + 10)
        self.assertNotEqual(changed, orphan)
        with self.assertRaises(FileNotFoundError):
            runner.tree_fingerprint(self.root, [Path("missing.class")], time.monotonic() + 10)

    def test_dimension_admission_refuses_invalid_or_excessive_work_before_scratch_mutation(self):
        defaults = {"entities": 16, "commits": 128, "queries": 64, "intervalWidth": 8}
        bad = [dict(defaults, **{key: value}) for key, value in (
            ("entities", True), ("entities", 0), ("entities", 128), ("commits", 4097),
            ("queries", -1), ("queries", 1025), ("intervalWidth", 0), ("intervalWidth", 17),
            ("commits", 2000))]
        bad += [{}, dict(defaults, unknown=1), {"entities": 1, "commits": 2001, "queries": 0, "intervalWidth": 1}]
        for dimensions in bad:
            with self.subTest(dimensions=dimensions), mock.patch.object(runner.sys, "platform", "linux"):
                with self.assertRaises(runner.SupervisorError):
                    runner.supervise(self.root, self.root, "CORRECTIONS", 42, 0, 5, 300, dimensions)
        self.assertEqual([], list(self.root.iterdir()))

    def test_fingerprint_rejects_symlink_and_byte_file_or_deadline_exhaustion(self):
        (self.root / "source").write_bytes(b"source")
        (self.root / "alias").symlink_to("source")
        with self.assertRaises(runner.SupervisorError):
            runner.tree_fingerprint(self.root, [Path("alias")], time.monotonic() + 10)
        with mock.patch.object(runner, "MAX_TREE_BYTES", 3):
            with self.assertRaises(runner.SupervisorError):
                runner.tree_fingerprint(self.root, [Path("source")], time.monotonic() + 10)
        with mock.patch.object(runner, "MAX_TREE_FILES", 0):
            with self.assertRaises(runner.SupervisorError):
                runner.tree_fingerprint(self.root, [Path("source")], time.monotonic() + 10)
        with self.assertRaises(runner.SupervisorError):
            runner.tree_fingerprint(self.root, [Path("source")], time.monotonic() - 1)

    def test_classpath_requires_worker_and_detects_orphans_and_resource_changes(self):
        main = self.root / "target/classes"; main.mkdir(parents=True)
        tests = self.root / "target/test-classes"; tests.mkdir()
        with self.assertRaises(runner.SupervisorError):
            runner.class_snapshot(self.root, time.monotonic() + 10)
        worker = tests / "org/meridiandb/workload/WorkloadWorker.class"; worker.parent.mkdir(parents=True)
        worker.write_bytes(b"worker")
        first = runner.class_snapshot(self.root, time.monotonic() + 10)
        (main / "orphan.class").write_bytes(b"old")
        self.assertNotEqual(first, runner.class_snapshot(self.root, time.monotonic() + 10))
        (main / "orphan.class").unlink()
        (tests / "resource.txt").write_bytes(b"changed")
        self.assertNotEqual(first, runner.class_snapshot(self.root, time.monotonic() + 10))

    def test_report_publication_is_complete_create_only_and_retains_collision_evidence(self):
        final = self.root / "report.json"
        runner.write_report(final, {"status": "failed", "value": "\ud800"})
        self.assertEqual({"status": "failed", "value": "\ud800"}, json.loads(final.read_bytes()))
        before = final.read_bytes()
        with self.assertRaises(FileExistsError):
            runner.write_report(final, {"status": "checks_passed"})
        self.assertEqual(before, final.read_bytes())
        self.assertTrue(self.root.joinpath("report.partial.json").is_file())

    def test_retained_evidence_caps_count_bytes_and_rejects_symlinks(self):
        (self.root / "one").write_bytes(b"12345")
        self.assertEqual(5, runner.retain_size(self.root, time.monotonic() + 10))
        with mock.patch.object(runner, "MAX_RETAINED_BYTES", 4):
            with self.assertRaises(runner.SupervisorError):
                runner.retain_size(self.root, time.monotonic() + 10)
        (self.root / "alias").symlink_to("one")
        with self.assertRaises(runner.SupervisorError):
            runner.retain_size(self.root, time.monotonic() + 10)

    def test_observed_deadline_exhaustion_refuses_prior_success_and_preserves_failures(self):
        for status, code in [("checks_passed", 0), ("unsupported", 3), ("failed", 1)]:
            report = {"status": status}
            if status == "failed":
                report["error"] = {"type": "IOException", "message": "original failure"}
            self.assertEqual(1, runner.finish_report(report, 100, 400, 401.25, code))
            self.assertEqual("failed", report["status"])
            self.assertTrue(report["deadlineExceededBeforeReport"])
            self.assertEqual(301.25, report["elapsedSecondsBeforeReport"])
            if status == "failed":
                self.assertEqual("original failure", report["error"]["message"])
        report = {"status": "checks_passed"}
        self.assertEqual(0, runner.finish_report(report, 100, 400, 399, 0))
        self.assertFalse(report["deadlineExceededBeforeReport"])

    def test_failed_process_inventory_never_bypasses_kill_or_direct_child_reap(self):
        child = mock.Mock(pid=999999999)
        with mock.patch.object(runner.os, "killpg") as kill:
            with mock.patch.object(runner, "live_group", side_effect=PermissionError("injected proc restriction")):
                with self.assertRaises(runner.SupervisorError):
                    runner.settle_group(child, time.monotonic() + 5)
        self.assertEqual([mock.call(child.pid, signal.SIGTERM), mock.call(child.pid, signal.SIGKILL)], kill.call_args_list)
        child.wait.assert_called_once()

    def test_failure_and_unsupported_require_exact_binding_exit_and_no_partial_success(self):
        request = {"runId": "a" * 32, "source": "b" * 40, "profile": "CORRECTIONS", "seed": 42,
                   "warmups": 0, "repetitions": 1}
        doc = {"schemaVersion": 1, **{k: request[k] for k in ("runId", "source", "profile", "seed")},
               "workerPid": 123, "status": "failed", "error": {"type": "IOException", "message": "literal failure"},
               "io": [], "result": None}
        self.assertEqual(("failed", doc["error"], 1), runner.failure_envelope(doc, request, 123, 1))
        unsupported = dict(doc, status="unsupported")
        self.assertEqual("unsupported", runner.failure_envelope(unsupported, request, 123, 3)[0])
        for field, value in [("schemaVersion", True), ("runId", "old"), ("source", "old"), ("seed", True),
                             ("workerPid", 124), ("status", "success"), ("result", {"partial": True}),
                             ("error", {}), ("io", [None, None])]:
            changed = copy.deepcopy(doc); changed[field] = value
            with self.subTest(field=field):
                with self.assertRaises(runner.SupervisorError):
                    runner.failure_envelope(changed, request, 123, 1)
        with self.assertRaises(runner.SupervisorError):
            runner.failure_envelope(unsupported, request, 123, 0)

    def test_scaled_failure_and_unsupported_bind_all_dimensions_and_schema(self):
        dimensions = {"entities": 7, "commits": 33, "queries": 11, "intervalWidth": 3}
        request = {"schemaVersion": 2, "runId": "a" * 32, "source": "b" * 40,
                   "profile": "CORRECTIONS", "seed": 42, "warmups": 0, "repetitions": 1,
                   "dimensions": dimensions}
        doc = {key: request[key] for key in ("schemaVersion", "runId", "source", "profile", "seed", "dimensions")}
        doc.update(workerPid=123, status="failed", error={"type": "IOException", "message": "retained"}, io=[], result=None)
        for status, code in [("failed", 1), ("unsupported", 3)]:
            doc["status"] = status
            self.assertEqual(status, runner.failure_envelope(doc, request, 123, code)[0])
            for field in dimensions:
                changed = copy.deepcopy(doc); changed["dimensions"][field] += 1
                with self.subTest(status=status, field=field), self.assertRaises(runner.SupervisorError):
                    runner.failure_envelope(changed, request, 123, code)
            for version in (1, True, 3):
                changed = dict(doc, schemaVersion=version)
                with self.assertRaises(runner.SupervisorError):
                    runner.failure_envelope(changed, request, 123, code)
            changed = copy.deepcopy(doc); changed["dimensions"]["entities"] = True
            with self.assertRaises(runner.SupervisorError):
                runner.failure_envelope(changed, request, 123, code)

    def test_cli_forwards_dimensions_independently_without_changing_other_defaults(self):
        with mock.patch.object(runner, "supervise", return_value=0) as supervise:
            self.assertEqual(0, runner.main(["--scratch-parent", str(self.root), "--entities", "7",
                                           "--commits", "33", "--queries", "0", "--interval-width", "3"]))
        self.assertEqual(("CORRECTIONS", 42, 1, 3, 300), supervise.call_args.args[2:7])
        self.assertEqual({"entities": 7, "commits": 33, "queries": 0, "intervalWidth": 3}, supervise.call_args.args[7])

    @unittest.skipUnless(sys.platform == "linux", "Linux process-group/proc interface")
    def test_zero_and_nonzero_leaders_preserve_exit_status_and_bound_logs(self):
        for code in [0, 7]:
            path = self.root / f"code-{code}.log"
            result = runner.run_group([sys.executable, "-c", f"print('literal');raise SystemExit({code})"],
                                      self.root, path, time.monotonic() + 5, runner.clean_environment(os.environ))
            self.assertEqual(code, result["exitCode"])
            self.assertTrue(result["groupSettled"])
            self.assertEqual(b"literal\n", path.read_bytes())
            self.assertFalse(runner.live_group(result["pid"]))

    @unittest.skipUnless(sys.platform == "linux", "Linux process-group/proc interface")
    def test_timeout_cleans_live_process_without_reusing_a_fresh_deadline(self):
        start = time.monotonic()
        with self.assertRaises(runner.SupervisorError):
            runner.run_group([sys.executable, "-c", "import time;time.sleep(30)"], self.root,
                             self.root / "timeout.log", start + 3.5, runner.clean_environment(os.environ))
        self.assertLess(time.monotonic() - start, 8.5)

    @unittest.skipUnless(sys.platform == "linux", "Linux process-group/proc interface")
    def test_flooded_output_is_capped_and_fails_before_success_admission(self):
        path = self.root / "flood.log"
        with self.assertRaises(runner.SupervisorError):
            runner.run_group([sys.executable, "-c", "print('x'*10000)"], self.root, path,
                             time.monotonic() + 5, runner.clean_environment(os.environ), max_log_bytes=128)
        self.assertLessEqual(path.stat().st_size, 128)

    @unittest.skipUnless(sys.platform == "linux", "Linux process-group/proc interface")
    def test_live_term_ignoring_child_is_cleaned_even_when_leader_exits_zero(self):
        marker = self.root / "child-ready"
        child = ("import os,signal,time;from pathlib import Path;"
                 "signal.signal(signal.SIGTERM,signal.SIG_IGN);"
                 f"Path({str(marker)!r}).write_text(str(os.getpid()));time.sleep(30)")
        leader = ("import subprocess,sys,time;from pathlib import Path;"
                  f"subprocess.Popen([sys.executable,'-c',{child!r}]);"
                  f"p=Path({str(marker)!r});\n"
                  "while not p.exists():time.sleep(.01)\n")
        result = runner.run_group([sys.executable, "-c", leader], self.root, self.root / "child.log",
                                  time.monotonic() + 6, runner.clean_environment(os.environ))
        self.assertEqual(0, result["exitCode"])
        self.assertTrue(marker.is_file())
        self.assertFalse(runner.live_group(result["pid"]))

    @unittest.skipUnless(sys.platform == "linux", "Linux signal delivery")
    def test_signal_handlers_preserve_interruption_and_restore_previous_behavior(self):
        previous = signal.getsignal(signal.SIGTERM)
        with self.assertRaises(runner.Interrupted):
            with runner.interrupt_handlers():
                os.kill(os.getpid(), signal.SIGTERM)
        self.assertEqual(previous, signal.getsignal(signal.SIGTERM))


if __name__ == "__main__":
    unittest.main()
