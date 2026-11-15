"""Opt-in seeded-input smoke wiring; semantic independence lives in corpus tests."""

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_recovery_images as smoke
from recovery_corpus import prepare_corpus


class VerifyRecoveryCorpusTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.repo = self.root / "repo"
        worker = self.repo / "target/test-classes/org/meridiandb/workload/RecoveryComparisonWorker.class"
        worker.parent.mkdir(parents=True)
        worker.write_bytes(b"never executed")
        (self.repo / "target/classes").mkdir()
        self.parent = self.root / "scratch"
        self.parent.mkdir()

    def produced(self, command, cwd, log_path, deadline, environment, max_log_bytes):
        spec = smoke._expected_spec(command[9], int(command[16]), int(command[15]),
                                    int(command[17]), int(command[18]), int(command[10]))
        prepared = prepare_corpus(spec)
        self.commands.append(command)
        # This producer intentionally has no complete outer request, matrix or native counters.
        document = dict(protocol="MERIDIANDB_RECOVERY_COMPARISON", schemaVersion=1, status="success",
                        sourceHistory=prepared["sourceHistory"], result={key: prepared[key] for key in smoke.CORPUS_FIELDS})
        if self.change is not None:
            self.change(document)
        Path(command[14]).write_text(json.dumps(document))
        Path(log_path).write_text("")
        return dict(pid=123, startTicks=4, exitCode=0, logBytes=0, groupSettled=True)

    def run_smoke(self, change=None):
        self.commands, self.change = [], change
        # Image validation already has independent graph tests and actual CI compatibility.
        # This seam isolates corpus selection/admission and its failure receipt.
        with mock.patch.object(smoke.sys, "platform", "linux"), mock.patch.object(smoke, "sync_directory"), \
                mock.patch.object(smoke, "run_group", side_effect=self.produced), \
                mock.patch.object(smoke, "validate_images", return_value={"imageScopeOnly": True}):
            return smoke.verify(self.repo, self.parent, "a" * 40, check_corpus=True)

    def test_explicit_selection_type_rejects_before_effects(self):
        for value in (1, 0, None, "true"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                smoke.verify(self.repo, self.parent, "a" * 40, check_corpus=value)
        self.assertEqual([], list(self.parent.iterdir()))

    def test_six_cases_bind_expected_profiles_and_signed_seeds_without_whole_result_claim(self):
        code, report = self.run_smoke()
        self.assertEqual(0, code)
        self.assertEqual(2, report["schemaVersion"])
        self.assertEqual("input_checks_passed", report["status"])
        self.assertEqual(smoke.CORPUS_SCOPE, report["scope"])
        self.assertFalse(report["productionQualified"])
        self.assertEqual(6, len(report["cases"]))
        self.assertEqual({"APPEND", "CORRECTIONS", "RETRACTIONS", "HOT_ENTITY"}, {c[9] for c in self.commands})
        self.assertEqual([42, 42, 42, -17, -(1 << 63), (1 << 63) - 1], [int(c[10]) for c in self.commands])
        self.assertEqual(report["cases"][0]["corpus"], report["cases"][1]["corpus"])
        self.assertTrue(Path(report["runRoot"]).name.startswith("mdb-recovery-corpus-"))
        self.assertEqual(report, json.loads((Path(report["runRoot"]) / "summary.json").read_bytes()))

    def test_changed_schedule_fingerprint_fails_with_known_case_process_retained(self):
        code, report = self.run_smoke(lambda value: value["result"].update(fingerprint="0" * 64))
        self.assertEqual(1, code)
        self.assertEqual("failed", report["status"])
        self.assertEqual([], report["cases"])
        self.assertTrue(report["failedCase"]["worker"]["groupSettled"])
        self.assertEqual(0, report["failedCase"]["worker"]["exitCode"])

    def test_absent_corpus_result_is_failure_even_when_producer_reports_success(self):
        code, report = self.run_smoke(lambda value: value.pop("result"))
        self.assertEqual(1, code)
        self.assertEqual("failed", report["status"])
        self.assertEqual([], report["cases"])
        self.assertTrue((Path(report["runRoot"]) / "control/worker.json").is_file())


if __name__ == "__main__":
    unittest.main()
