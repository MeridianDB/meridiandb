"""Compatibility smoke boundaries; fabricated worker output is image evidence only."""

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_recovery_images as smoke
from test_recovery_images import fixture


class VerifyRecoveryImagesTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.repo = self.root / "repo"
        worker = self.repo / "target/test-classes/org/meridiandb/workload/RecoveryComparisonWorker.class"
        worker.parent.mkdir(parents=True)
        worker.write_bytes(b"test-only mock class; never executed")
        (self.repo / "target/classes").mkdir()
        self.parent = self.root / "scratch"
        self.parent.mkdir()
        self.source = "a" * 40

    def test_invalid_platform_source_timeout_placement_and_missing_compile_precede_run_effects(self):
        with mock.patch.object(smoke.sys, "platform", "darwin"):
            with self.assertRaises(ValueError):
                smoke.verify(self.repo, self.parent, self.source)
        with mock.patch.object(smoke.sys, "platform", "linux"), mock.patch.object(smoke, "run_group") as runner:
            for source, timeout in [("A" * 40, 120), ("a" * 39, 120), (self.source, 29),
                                    (self.source, 301), (self.source, True)]:
                with self.subTest(source=source, timeout=timeout), self.assertRaises(ValueError):
                    smoke.verify(self.repo, self.parent, source, timeout)
            with self.assertRaises(ValueError):
                smoke.verify(Path("relative"), self.parent, self.source)
            (self.repo / "target/test-classes/org/meridiandb/workload/RecoveryComparisonWorker.class").unlink()
            with self.assertRaises(ValueError):
                smoke.verify(self.repo, self.parent, self.source)
            runner.assert_not_called()
        self.assertEqual([], list(self.parent.iterdir()))

    def produced(self, command, cwd, log_path, deadline, environment, max_log_bytes):
        self.assertEqual(self.repo, cwd)
        self.assertNotIn("JAVA_TOOL_OPTIONS", environment)
        self.assertNotIn("GIT_INDEX_FILE", environment)
        self.assertEqual(256 * 1024, max_log_bytes)
        entities, commits, queries, width = map(int, command[15:19])
        case = fixture(commits=commits, entities=entities, queries=queries, width=width)
        # No outer result/request/runtime exists: this smoke intentionally does not admit them.
        payload = dict(protocol="MERIDIANDB_RECOVERY_COMPARISON", schemaVersion=1, status="success",
                       sourceHistory=case[0], continuationFrameHex=case[1], fixtureImages=case[2])
        Path(command[14]).write_text(json.dumps(payload))
        Path(log_path).write_text("")
        return dict(pid=123, startTicks=456, exitCode=0, logBytes=0, groupSettled=True)

    def test_three_fixed_cases_use_expected_request_and_return_only_scoped_image_facts(self):
        with mock.patch.object(smoke.sys, "platform", "linux"), mock.patch.object(smoke, "sync_directory"), \
                mock.patch.dict(smoke.os.environ, {"JAVA_TOOL_OPTIONS": "blocked", "GIT_INDEX_FILE": "blocked"}), \
                mock.patch.object(smoke, "run_group", side_effect=self.produced) as runner:
            code, report = smoke.verify(self.repo, self.parent, self.source)
        self.assertEqual(0, code)
        self.assertEqual(3, runner.call_count)
        self.assertEqual("image_checks_passed", report["status"])
        self.assertFalse(report["productionQualified"])
        self.assertIn("no complete worker", report["scope"])
        self.assertEqual(self.source, report["declaredSource"])
        self.assertEqual(["control", "captured", "maximum-history"], [case["name"] for case in report["cases"]])
        self.assertEqual(report["cases"][0]["images"], report["cases"][1]["images"])
        self.assertEqual(496, report["cases"][2]["images"]["commits"])
        self.assertEqual(21, len(report["cases"][2]["images"]["fixtures"][-1]["initialFileSha256"]))
        self.assertTrue(all(set(case) == {"name", "requestedMode", "worker", "workerOutput", "images"}
                            for case in report["cases"]))
        self.assertEqual(report, json.loads((Path(report["runRoot"]) / "summary.json").read_bytes()))

    def test_nonzero_exit_preserves_raw_partial_and_returns_no_successful_case(self):
        def failed(command, cwd, log_path, deadline, environment, max_log_bytes):
            Path(command[14]).with_name("worker.partial.json").write_text("retained partial")
            Path(log_path).write_text("native failure")
            return dict(pid=123, startTicks=456, exitCode=3, logBytes=14, groupSettled=True)
        with mock.patch.object(smoke.sys, "platform", "linux"), mock.patch.object(smoke, "sync_directory"), \
                mock.patch.object(smoke, "run_group", side_effect=failed):
            code, report = smoke.verify(self.repo, self.parent, self.source)
        self.assertEqual(1, code)
        self.assertEqual("failed", report["status"])
        self.assertEqual([], report["cases"])
        self.assertEqual("control", report["failedCase"]["name"])
        self.assertEqual(smoke.CONTROL, report["failedCase"]["requestedMode"])
        self.assertEqual(3, report["failedCase"]["worker"]["exitCode"])
        self.assertTrue(report["failedCase"]["worker"]["groupSettled"])
        self.assertEqual("retained partial", (Path(report["runRoot"]) / "control/worker.partial.json").read_text())

    def test_changed_image_rejects_even_when_fresh_producer_claims_success(self):
        def changed(*args, **kwargs):
            result = self.produced(*args, **kwargs)
            path = Path(args[0][14])
            payload = json.loads(path.read_bytes())
            payload["continuationFrameHex"] = payload["sourceHistory"]["framesHex"][0]
            path.write_text(json.dumps(payload))
            return result
        with mock.patch.object(smoke.sys, "platform", "linux"), mock.patch.object(smoke, "sync_directory"), \
                mock.patch.object(smoke, "run_group", side_effect=changed):
            code, report = smoke.verify(self.repo, self.parent, self.source)
        self.assertEqual(1, code)
        self.assertEqual("failed", report["status"])
        self.assertEqual([], report["cases"])
        self.assertTrue((Path(report["runRoot"]) / "control/worker.json").is_file())

    def test_fresh_output_reader_refuses_duplicate_keys_invalid_utf8_nonfinite_and_wrong_status(self):
        path = self.root / "output.json"
        values = [b'{"status":"success","status":"success"}', b'\xff', b'{"x":NaN}',
                  b'{"protocol":"MERIDIANDB_RECOVERY_COMPARISON","schemaVersion":true,"status":"success"}',
                  b'{"protocol":"MERIDIANDB_RECOVERY_COMPARISON","schemaVersion":1,"status":"failed"}']
        for value in values:
            path.write_bytes(value)
            with self.subTest(value=value), self.assertRaises(ValueError):
                smoke._produced_document(path)

    def test_group_failure_retains_partial_visit_receipt_and_existing_files(self):
        def interrupted(command, cwd, log_path, deadline, environment, max_log_bytes):
            Path(log_path).write_text("retained interruption")
            raise KeyboardInterrupt("owned child cleanup already completed")
        with mock.patch.object(smoke.sys, "platform", "linux"), mock.patch.object(smoke, "sync_directory"), \
                mock.patch.object(smoke, "run_group", side_effect=interrupted):
            code, report = smoke.verify(self.repo, self.parent, self.source)
        self.assertEqual(1, code)
        self.assertEqual("KeyboardInterrupt", report["error"]["type"])
        self.assertEqual("retained interruption", (Path(report["runRoot"]) / "control/worker.log").read_text())


if __name__ == "__main__":
    unittest.main()
