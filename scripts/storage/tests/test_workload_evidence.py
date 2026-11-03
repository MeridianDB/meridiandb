"""Pure schema, recomputation and retained-file checks; no Java/build invocation."""

import copy
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock


_spec = importlib.util.spec_from_file_location(
    "workload_evidence", Path(__file__).resolve().parents[1] / "workload_evidence.py")
evidence = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(evidence)


def sample(phase, kind, index):
    return {"phase": phase, "kind": kind, "elapsedNanos": index % 17,
            "threadCpuNanos": 3 if phase == "LOAD" or index % 2 else None,
            "threadAllocatedBytes": 5 if phase == "LOAD" or index % 3 else None,
            "heapUsedBeforeBytes": 100, "heapUsedAfterBytes": 90}


def summaries(samples):
    """Fixture arithmetic, independent of the validator's helpers."""
    result = []
    for phase in evidence.PHASES:
        for kind in evidence.KINDS:
            group = [item for item in samples if item["phase"] == phase and item["kind"] == kind]
            if not group:
                continue
            times = sorted(item["elapsedNanos"] for item in group)
            item = {"phase": phase, "kind": kind, "count": len(group), "totalNanos": sum(times),
                    "minNanos": times[0], "maxNanos": times[-1], "maxSampledHeapUsedBytes": 100,
                    "serviceOperationsPerSecond": len(group) * 1e9 / sum(times) if sum(times) else None}
            for percent in (50, 95, 99):
                item[f"p{percent}Nanos"] = times[math.ceil(percent / 100 * len(times)) - 1]
            for field, count in (("threadCpuNanos", "threadCpuSamples"),
                                 ("threadAllocatedBytes", "threadAllocatedSamples")):
                available = [value[field] for value in group if value[field] is not None]
                item[field] = sum(available) if len(available) == len(group) else None
                item[count] = len(available)
            result.append(item)
    return result


def workload():
    return {"attempts": 141, "commits": 128, "retries": 13, "writeOperations": 128, "reads": 64,
            "noOpCommits": 15, "putOperations": 113, "retractOperations": 15,
            "noOpPuts": 12, "noOpRetractions": 3, "putsReplacingPresent": 80,
            "retractionsRemovingPresent": 12, "geometricOverlaps": 99,
            "entityTouches": {f"e-{i}": 25 if i == 0 else 7 if i < 14 else 6 for i in range(16)},
            "scheduledIntents": {"SEED": 16, "APPEND": 13, "CORRECTION": 79, "RETRACTION": 11,
                                 "REPEAT": 9, "RETRY": 13},
            "readOperators": {"GET": 13, "SCAN": 13, "SELECT": 13, "FILTER_SCAN": 13, "SUM": 12},
            "historicalReads": 48, "headReads": 16, "pointHits": 9, "pointMisses": 4,
            "emptyResults": 12, "nonemptyResults": 52}


class DocumentTests(unittest.TestCase):
    def test_utf8_preserves_unicode_and_escaped_utf16_units(self):
        doc = {"name": "\u2603", "identity": "\ud800", "empty": "", "blank": " ", "number": -42, "flag": True}
        self.assertEqual(doc, evidence.parse_document(json.dumps(doc).encode()))

    def test_duplicates_nonfinite_overflow_and_non_objects_are_rejected(self):
        for image in (b'{"x":1,"x":1}', b'{"x":{"a":1,"a":2}}', b'{"x":NaN}', b'{"x":Infinity}',
                      b'{"x":1e999}', b'{"x":9223372036854775808}', b'{"x":-9223372036854775809}',
                      b'{"x":' + b'1' * 100 + b'}', b'[]', b'null', b'{', b'\xff', b''):
            with self.subTest(image=image), self.assertRaises(evidence.EvidenceError):
                evidence.parse_document(image)

    def test_signed_long_extremes_are_accepted_as_values(self):
        self.assertEqual({"low": -(1 << 63), "high": (1 << 63) - 1},
                         evidence.parse_document(b'{"low":-9223372036854775808,"high":9223372036854775807}'))

    def test_depth_node_string_and_byte_limits_accept_boundary_then_refuse_more(self):
        image = b'{"x":"abc"}'
        with mock.patch.object(evidence, "MAX_DOCUMENT_BYTES", len(image)):
            self.assertEqual({"x": "abc"}, evidence.parse_document(image))
        with mock.patch.object(evidence, "MAX_DOCUMENT_BYTES", len(image) - 1), self.assertRaises(evidence.EvidenceError):
            evidence.parse_document(image)
        with mock.patch.object(evidence, "MAX_STRING_UNITS", 3):
            evidence.parse_document(image)
            with self.assertRaises(evidence.EvidenceError):
                evidence.parse_document(b'{"x":"abcd"}')
        with mock.patch.object(evidence, "MAX_NODES", 3):
            evidence.parse_document(b'{"x":1}')
            with self.assertRaises(evidence.EvidenceError):
                evidence.parse_document(b'{"x":[1]}')
        with mock.patch.object(evidence, "MAX_DEPTH", 2):
            evidence.parse_document(b'{"x":[1]}')
            with self.assertRaises(evidence.EvidenceError):
                evidence.parse_document(b'{"x":[[1]]}')
        evidence.parse_document(b'{"text":"[\\\"{}]"}')

    def test_depth_is_bounded_before_decoder_recursion(self):
        with self.assertRaises(evidence.EvidenceError):
            evidence.parse_document(b'{"x":' + b'[' * 10000 + b'0' + b']' * 10000 + b'}')

    def test_nodes_and_oversized_strings_are_refused_before_json_materialization(self):
        for image in (b'{"x":[0,0,0]}', b'{"x":"' + b'a' * 100 + b'"}'):
            with self.subTest(image=image), mock.patch.object(evidence, "MAX_NODES", 4), \
                    mock.patch.object(evidence, "MAX_STRING_UNITS", 4), \
                    mock.patch.object(evidence.json, "loads") as decoder:
                with self.assertRaises(evidence.EvidenceError):
                    evidence.parse_document(image)
                decoder.assert_not_called()


class WorkerEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mdb-evidence-")
        self.addCleanup(self.temporary.cleanup)
        self.parent = Path(self.temporary.name).resolve()
        self.stores = self.parent / "stores"
        self.stores.mkdir()
        self.root = self.stores / "mdb-workload-fixture"
        self.root.mkdir()
        self.request = {"runId": "b" * 32, "source": "a" * 40, "profile": "CORRECTIONS", "seed": 42,
                        "warmups": 0, "repetitions": 1}
        self.pid = 1234
        self.mount = {"fileSystem": "ext4"}
        self.journal = b"exact retained journal fixture\n"
        self.doc = self.fixture()

    def fixture(self, warmups=0, repetitions=1):
        self.request.update(warmups=warmups, repetitions=repetitions)
        w = workload()
        actions = w["attempts"] + w["reads"]
        kinds = [("LOAD", "CREATE")] + [("LOAD", "COMMIT")] * 16
        kinds += [("MIXED", "COMMIT")] * 112 + [("MIXED", "RETRY")] * 13
        for key, count in w["readOperators"].items():
            kinds += [("MIXED", key)] * count
        kinds += [("MAINTENANCE", "JOURNAL_REOPEN")] + [("MAINTENANCE", "SEGMENT_WRITE")] * 8
        kinds += [("MAINTENANCE", key) for key in ("CHECKPOINT_PUBLISH", "COMPACTION_PLAN", "COMPACTION_PUBLISH", "CHECKPOINT_REOPEN")]
        samples = [sample(phase, kind, index) for index, (phase, kind) in enumerate(kinds)]
        service = sum(s["elapsedNanos"] for s in samples if s["phase"] != "MAINTENANCE" and s["kind"] != "CREATE")
        loop = service + actions * 2 + 3
        iterations, io = [], []
        for index in range(warmups + repetitions):
            home = self.root / f"iteration-{index}"
            home.mkdir()
            for name in evidence.CONTROL_NAMES:
                (home / name).write_bytes(b"")
            (home / "transactions.journal").write_bytes(self.journal)
            for gen in (1, 2, 3):
                content = f"manifest-{gen}".encode()
                (home / f"checkpoint-{gen:019d}-{hashlib.sha256(content).hexdigest()}.manifest").write_bytes(content)
            for segment in range(9):
                content = f"segment-{segment}".encode()
                (home / f"{hashlib.sha256(content).hexdigest()}.segment").write_bytes(content)
            lengths = {p.name: p.stat().st_size for p in home.iterdir()}
            iterations.append({"index": index, "warmup": index < warmups, "directory": str(home),
                               "namespacePreparationNanos": 20, "actionLoopNanos": loop,
                               "actionServiceNanos": service, "actionValidationNanos": actions * 2, "actionOtherNanos": 3,
                               "actionLoopOperationsPerSecond": actions * 1e9 / loop,
                               "totalValidationNanos": actions * 2 + 10, "validationTasks": actions + 16,
                               "sourceSegments": 8, "replacementSegments": 1, "pinnedReadersDuringPublication": 1,
                               "namedStorage": {"fileLengths": lengths, "totalBytes": sum(lengths.values())},
                               "samples": copy.deepcopy(samples), "summaries": summaries(samples)})
            before = {key: (index + 1) * 100 + number for number, key in enumerate(evidence.IO_FIELDS)}
            delta = {key: number + 1 for number, key in enumerate(evidence.IO_FIELDS)}
            io.append({"index": index, "warmup": index < warmups, "directory": str(home), "before": before,
                       "after": {key: before[key] + delta[key] for key in evidence.IO_FIELDS}, "delta": delta, "unavailable": None})
        result = {"schemaVersion": 1, "corpusVersion": 1,
                  "spec": dict(zip(evidence.SPEC_FIELDS, (42, 16, 128, 64, 8, 70, 10, 10, 10, 10, 75))),
                  "config": {"warmups": warmups, "repetitions": repetitions}, "fingerprint": "c" * 64,
                  "historyFingerprint": hashlib.sha256(self.journal).hexdigest(), "encodedHistoryBytes": len(self.journal),
                  "workload": w, "runtime": {"javaVersion": "25.0.5", "javaRuntimeVersion": "25.0.5+8-LTS",
                  "javaVendor": "test-vendor", "osName": "Linux", "osVersion": platform.release(),
                  "architecture": platform.machine(), "availableProcessors": 2, "maxHeapBytes": 256 * 1024 * 1024,
                  "fileStoreType": "ext4"}, "runRoot": str(self.root), "preparationNanos": 40,
                  "iterations": iterations, "gaps": ["Observed developer data; backing device unknown"]}
        return {"schemaVersion": 1, **{key: self.request[key] for key in ("runId", "source", "profile", "seed")},
                "workerPid": self.pid, "status": "success", "error": None, "io": io, "result": result}

    def validate(self):
        return evidence.validate_worker(self.doc, self.request, self.pid, self.stores, self.mount)

    def rejects(self, mutation):
        original = self.doc
        self.doc = copy.deepcopy(original)
        try:
            mutation(self.doc)
            with self.assertRaises(evidence.EvidenceError):
                self.validate()
        finally:
            self.doc = original

    def test_acceptance_reobserves_actual_file_allocations_and_keeps_process_ratio_scope(self):
        accepted = self.validate()
        self.assertEqual(15, accepted["retainedFileCount"])
        files = accepted["iterations"][0]["files"]
        expected_blocks = sum(p.stat().st_blocks * 512 for p in (self.root / "iteration-0").iterdir())
        self.assertEqual(expected_blocks, accepted["totalAttributedAllocatedBytes"])
        self.assertEqual(sum(item["logicalBytes"] for item in files), accepted["totalLogicalBytes"])
        io = accepted["iterations"][0]["processIo"]
        self.assertEqual(5 / len(self.journal), io["processReadBytesPerEncodedJournalByte"])
        self.assertEqual(6 / len(self.journal), io["processWriteBytesPerEncodedJournalByte"])
        self.assertEqual(7, io["delta"]["cancelled_write_bytes"])
        self.assertIn("not unique physical", " ".join(accepted["limits"]))

    def test_fixture_literal_load_statistics_have_exact_distribution_and_counters(self):
        load = next(s for s in self.doc["result"]["iterations"][0]["summaries"] if s["phase"] == "LOAD" and s["kind"] == "COMMIT")
        self.assertEqual((16, 136, 1, 8, 16, 16, 16),
                         tuple(load[k] for k in ("count", "totalNanos", "minNanos", "p50Nanos", "p95Nanos", "p99Nanos", "maxNanos")))
        self.assertEqual((48, 80), (load["threadCpuNanos"], load["threadAllocatedBytes"]))
        self.validate()

    def test_warmups_and_repetitions_must_have_fresh_exact_path_and_boolean_bindings(self):
        for home in self.root.iterdir():
            import shutil
            shutil.rmtree(home)
        self.doc = self.fixture(1, 2)
        self.assertEqual([True, False, False], [item["warmup"] for item in self.validate()["iterations"]])
        for mutation in (lambda d: d["result"]["iterations"][0].update(warmup=False),
                         lambda d: d["result"]["iterations"][1].update(index=0),
                         lambda d: d["result"]["iterations"][1].update(directory=d["result"]["iterations"][0]["directory"]),
                         lambda d: d["io"][1].update(warmup=True)):
            self.rejects(mutation)

    def test_envelope_request_identity_exit_status_and_closed_schema_are_required(self):
        for mutation in (lambda d: d.update(runId="d" * 32), lambda d: d.update(source="d" * 40),
                         lambda d: d.update(profile="APPEND"), lambda d: d.update(seed=43),
                         lambda d: d.update(workerPid=1235), lambda d: d.update(workerPid=True),
                         lambda d: d.update(status="unsupported"), lambda d: d.update(error={"message": "failed"}),
                         lambda d: d.update(extra=True), lambda d: d.pop("io"), lambda d: d.update(schemaVersion=True)):
            self.rejects(mutation)

    def test_wrong_profile_spec_and_runtime_limits_are_rejected(self):
        for mutation in (lambda d: d["result"]["spec"].update(entities=17),
                         lambda d: d["result"]["spec"].update(seed=True),
                         lambda d: d["result"]["config"].update(repetitions=2),
                         lambda d: d["result"]["runtime"].update(javaVersion="21.0.1"),
                         lambda d: d["result"]["runtime"].update(osName="Windows"),
                         lambda d: d["result"]["runtime"].update(osVersion="another-kernel"),
                         lambda d: d["result"]["runtime"].update(architecture="unrelated"),
                         lambda d: d["result"]["runtime"].update(fileStoreType="xfs"),
                         lambda d: d["result"]["runtime"].update(maxHeapBytes=evidence.MAX_HEAP_BYTES + 1),
                         lambda d: d["result"]["runtime"].update(availableProcessors=0)):
            self.rejects(mutation)

    def test_architecture_aliases_are_accepted_without_changing_filesystem_identity(self):
        with mock.patch.object(evidence.platform, "machine", return_value="x86_64"):
            self.doc["result"]["runtime"]["architecture"] = "amd64"
            self.validate()

    def test_workload_denominators_effects_and_mixes_cannot_be_fabricated(self):
        for key, wrong in (("attempts", 140), ("commits", 127), ("reads", 63), ("retries", True),
                           ("writeOperations", 129), ("noOpPuts", 13), ("retractionsRemovingPresent", 13),
                           ("geometricOverlaps", 80), ("pointHits", 10), ("headReads", 17), ("emptyResults", 11)):
            self.rejects(lambda d, key=key, wrong=wrong: d["result"]["workload"].update({key: wrong}))
        for mutation in (lambda d: d["result"]["workload"]["scheduledIntents"].update(SEED=15),
                         lambda d: d["result"]["workload"]["entityTouches"].update({"e-0": 0}),
                         lambda d: d["result"]["workload"]["readOperators"].update(GET=12)):
            self.rejects(mutation)

    def test_raw_sample_inventory_order_and_schema_must_match_checked_lifecycle(self):
        for mutation in (lambda d: d["result"]["iterations"][0]["samples"].pop(),
                         lambda d: d["result"]["iterations"][0]["samples"][0].update(kind="COMMIT"),
                         lambda d: d["result"]["iterations"][0]["samples"][17].update(phase="LOAD"),
                         lambda d: d["result"]["iterations"][0]["samples"][0].update(elapsedNanos=True),
                         lambda d: d["result"]["iterations"][0]["samples"][0].update(threadCpuNanos=-1),
                         lambda d: d["result"]["iterations"][0]["samples"][0].update(extra=1)):
            self.rejects(mutation)

    def test_all_reported_summary_numbers_are_recomputed_from_raw_samples(self):
        integer_fields = evidence.SUMMARY_FIELDS - {"phase", "kind", "serviceOperationsPerSecond", "threadCpuNanos", "threadAllocatedBytes"}
        for field in integer_fields:
            self.rejects(lambda d, field=field: d["result"]["iterations"][0]["summaries"][1].update(
                {field: d["result"]["iterations"][0]["summaries"][1][field] + 1}))
        for field in ("threadCpuNanos", "threadAllocatedBytes", "serviceOperationsPerSecond"):
            self.rejects(lambda d, field=field: d["result"]["iterations"][0]["summaries"][1].update({field: 0}))
        self.rejects(lambda d: d["result"]["iterations"][0]["summaries"].reverse())

    def test_partial_counter_availability_stays_null_with_exact_usable_counts(self):
        mixed = next(s for s in self.doc["result"]["iterations"][0]["summaries"] if s["phase"] == "MIXED" and s["kind"] == "COMMIT")
        self.assertIsNone(mixed["threadCpuNanos"])
        self.assertLess(mixed["threadCpuSamples"], mixed["count"])
        self.validate()
        self.rejects(lambda d: next(s for s in d["result"]["iterations"][0]["summaries"]
                                  if s["phase"] == "MIXED" and s["kind"] == "COMMIT").update(threadCpuNanos=0))

    def test_sampled_heap_cannot_exceed_the_observed_worker_heap_limit(self):
        self.rejects(lambda d: d["result"]["iterations"][0]["samples"][0].update(
            heapUsedAfterBytes=evidence.MAX_HEAP_BYTES + 1))

    def test_zero_duration_has_unavailable_rate_and_no_infinite_numeric_values(self):
        iteration = self.doc["result"]["iterations"][0]
        self.assertIsNone(iteration["summaries"][0]["serviceOperationsPerSecond"])
        self.validate()
        self.rejects(lambda d: d["result"]["iterations"][0]["summaries"][0].update(serviceOperationsPerSecond=0))
        self.rejects(lambda d: d["result"]["iterations"][0].update(actionLoopOperationsPerSecond=float("inf")))

    def test_loop_service_validation_rate_and_explicit_lifecycle_counts_must_agree(self):
        for field in ("actionLoopNanos", "actionServiceNanos", "actionValidationNanos", "actionOtherNanos",
                      "validationTasks", "sourceSegments", "replacementSegments", "pinnedReadersDuringPublication"):
            self.rejects(lambda d, field=field: d["result"]["iterations"][0].update(
                {field: d["result"]["iterations"][0][field] + 1}))
        self.rejects(lambda d: d["result"]["iterations"][0].update(totalValidationNanos=0))
        self.rejects(lambda d: d["result"]["iterations"][0].update(actionLoopOperationsPerSecond=1.0))

    def test_exact_io_deltas_and_cancellation_are_checked_separately(self):
        self.validate()
        self.rejects(lambda d: d["io"][0]["delta"].update(write_bytes=0))
        self.rejects(lambda d: d["io"][0]["delta"].update(cancelled_write_bytes=0))
        self.rejects(lambda d: d["io"][0]["before"].update(read_bytes=True))
        self.rejects(lambda d: d["io"][0].update(unavailable="unknown"))

    def test_missing_and_reset_io_remain_explicit_gaps_without_zero_ratios(self):
        cases = ((None, None, "Before snapshot unavailable: NoSuchFileException"),
                 ("keep", None, "After snapshot unavailable: AccessDeniedException"),
                 ("keep", "reset", "Nonmonotonic process I/O counter: rchar"))
        original = copy.deepcopy(self.doc["io"][0])
        for before, after, reason in cases:
            with self.subTest(reason=reason):
                item = copy.deepcopy(original)
                if before is None:
                    item["before"] = None
                if after is None:
                    item["after"] = None
                else:
                    item["after"]["rchar"] = item["before"]["rchar"] - 1
                item.update(delta=None, unavailable=reason)
                self.doc["io"][0] = item
                io = self.validate()["iterations"][0]["processIo"]
                self.assertIsNone(io["processReadBytesPerEncodedJournalByte"])
                self.assertIsNone(io["processWriteBytesPerEncodedJournalByte"])
                item["unavailable"] = "incorrect explanation"
                with self.assertRaises(evidence.EvidenceError):
                    self.validate()
        self.doc["io"][0] = original

    def test_full_java_unavailability_reason_can_include_its_bounded_prefix(self):
        item = self.doc["io"][0]
        item.update(before=None, delta=None, unavailable="Before snapshot unavailable: " + "x" * 512)
        self.assertIsNone(self.validate()["iterations"][0]["processIo"]["delta"])
        item["unavailable"] = "Before snapshot unavailable: "
        with self.assertRaises(evidence.EvidenceError):
            self.validate()

    def test_returned_paths_cannot_escape_or_alias_the_fresh_namespace(self):
        self.rejects(lambda d: d["result"].update(runRoot=str(self.parent)))
        self.rejects(lambda d: d["result"]["iterations"][0].update(directory=str(self.parent)))
        self.rejects(lambda d: d["io"][0].update(directory=str(self.parent)))
        (self.stores / "unexpected").mkdir()
        with self.assertRaises(evidence.EvidenceError):
            self.validate()

    def test_unexpected_run_entries_and_iteration_files_are_refused(self):
        path = self.root / "unexpected"
        path.write_text("do not consume")
        with self.assertRaises(evidence.EvidenceError):
            self.validate()
        path.unlink()
        (self.root / "iteration-0" / "unexpected").write_bytes(b"extra")
        with self.assertRaises(evidence.EvidenceError):
            self.validate()

    def test_recorded_file_names_lengths_and_totals_are_not_trusted(self):
        self.rejects(lambda d: d["result"]["iterations"][0]["namedStorage"]["fileLengths"].update({"../outside": 0}))
        self.rejects(lambda d: d["result"]["iterations"][0]["namedStorage"].update(totalBytes=0))
        target = next((self.root / "iteration-0").glob("*.segment"))
        target.write_bytes(b"length changed")
        with self.assertRaises(evidence.EvidenceError):
            self.validate()

    def test_same_length_journal_corruption_is_detected_by_closed_file_hash(self):
        path = self.root / "iteration-0" / "transactions.journal"
        path.write_bytes(b"x" * len(self.journal))
        with self.assertRaisesRegex(evidence.EvidenceError, "journal identity"):
            self.validate()

    def test_symbolic_file_and_directory_aliases_are_rejected(self):
        home = self.root / "iteration-0"
        target = next(home.glob("*.segment"))
        external = self.parent / "external"
        external.write_bytes(target.read_bytes())
        target.unlink()
        target.symlink_to(external)
        with self.assertRaises(evidence.EvidenceError):
            self.validate()
        target.unlink()
        target.write_bytes(external.read_bytes())
        external_dir = self.parent / "saved-home"
        home.rename(external_dir)
        home.symlink_to(external_dir, target_is_directory=True)
        with self.assertRaises(evidence.EvidenceError):
            self.validate()

    def test_hardlinks_are_rejected_even_when_all_recorded_sizes_still_match(self):
        target = next((self.root / "iteration-0").glob("*.segment"))
        os.link(target, self.parent / "external-hardlink")
        with self.assertRaisesRegex(evidence.EvidenceError, "multiply-linked"):
            self.validate()

    def test_special_file_inventory_is_rejected_before_opening_or_hashing(self):
        target = next((self.root / "iteration-0").glob("*.segment"))
        target.unlink()
        target.mkdir()
        with self.assertRaises(evidence.EvidenceError):
            self.validate()

    def test_allocated_block_observations_and_aggregate_byte_budgets_are_enforced(self):
        with mock.patch.object(evidence, "MAX_FILES", 14), self.assertRaises(evidence.EvidenceError):
            self.validate()
        baseline = self.validate()
        exact = max(baseline["totalLogicalBytes"], baseline["totalAttributedAllocatedBytes"])
        with mock.patch.object(evidence, "MAX_RETAINED_BYTES", exact):
            self.validate()
        with mock.patch.object(evidence, "MAX_RETAINED_BYTES", exact - 1), self.assertRaises(evidence.EvidenceError):
            self.validate()
        target = next((self.root / "iteration-0").glob("*.segment"))
        native_lstat = Path.lstat

        def oversized(path):
            result = native_lstat(path)
            if path == target:
                return SimpleNamespace(st_mode=result.st_mode, st_nlink=result.st_nlink, st_size=result.st_size,
                                       st_dev=result.st_dev, st_ino=result.st_ino, st_blocks=evidence.MAX_RETAINED_BYTES)
            return result

        with mock.patch.object(Path, "lstat", oversized), self.assertRaises(evidence.EvidenceError):
            self.validate()


if __name__ == "__main__":
    unittest.main()
