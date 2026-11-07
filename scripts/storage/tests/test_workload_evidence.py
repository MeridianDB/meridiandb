"""Pure schema, recomputation and retained-file checks; no Java/build invocation."""

import copy
from contextlib import ExitStack
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import shutil
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
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


class DimensionAdmissionTests(unittest.TestCase):
    def dimensions(self, entities=7, commits=33, queries=11, width=3):
        return {"entities": entities, "commits": commits, "queries": queries, "intervalWidth": width}

    def request(self):
        return {"runId": "b" * 32, "source": "a" * 40, "profile": "CORRECTIONS", "seed": 42,
                "warmups": 0, "repetitions": 1}

    def test_legacy_request_still_implies_exact_original_dimensions_and_returns_a_copy(self):
        request = self.request()
        dimensions = evidence.validate_request(request)
        self.assertEqual({"entities": 16, "commits": 128, "queries": 64, "intervalWidth": 8}, dimensions)
        self.assertEqual(6, len(request))
        dimensions["entities"] = 1
        self.assertEqual(16, evidence.validate_request(request)["entities"])

    def test_schema2_request_checks_dimensions_and_all_original_bindings(self):
        request = {**self.request(), "schemaVersion": 2, "dimensions": self.dimensions()}
        self.assertEqual(self.dimensions(), evidence.validate_request(request))
        for mutation in (lambda r: r.update(schemaVersion=1), lambda r: r.update(schemaVersion=True),
                         lambda r: r.update(schemaVersion=3), lambda r: r.pop("dimensions"),
                         lambda r: r.update(extra=0), lambda r: r.update(runId="invalid"),
                         lambda r: r.update(source="short"), lambda r: r.update(profile="UNKNOWN"),
                         lambda r: r.update(seed=True), lambda r: r.update(seed=1 << 63),
                         lambda r: r.update(warmups=True), lambda r: r.update(repetitions=0)):
            with self.subTest(mutation=mutation):
                changed = copy.deepcopy(request)
                mutation(changed)
                with self.assertRaises(evidence.EvidenceError):
                    evidence.validate_request(changed)
        without_version = {**self.request(), "dimensions": self.dimensions()}
        with self.assertRaises(evidence.EvidenceError):
            evidence.validate_request(without_version)

    def test_each_dimension_type_range_and_exact_shape_is_checked(self):
        for field, invalid in (("entities", 0), ("entities", 1025), ("commits", 7), ("commits", 4097),
                               ("queries", -1), ("queries", 1025), ("intervalWidth", 0), ("intervalWidth", 17)):
            changed = self.dimensions()
            changed[field] = invalid
            with self.subTest(field=field, invalid=invalid), self.assertRaises(evidence.EvidenceError):
                evidence.validate_dimensions(changed, 0, 1)
        for field in evidence.DIMENSION_FIELDS:
            for invalid in (True, 1.0, "1", None):
                changed = self.dimensions()
                changed[field] = invalid
                with self.subTest(field=field, invalid=invalid), self.assertRaises(evidence.EvidenceError):
                    evidence.validate_dimensions(changed, 0, 1)
        for changed in (None, [], {"entities": 7}, {**self.dimensions(), "extra": 1}):
            with self.subTest(changed=changed), self.assertRaises(evidence.EvidenceError):
                evidence.validate_dimensions(changed, 0, 1)

    def test_valid_dimension_edges_and_seed_limits_remain_distinct_from_aggregate_work(self):
        for dimensions in (self.dimensions(1, 2, 0, 1), self.dimensions(1024, 1025, 0, 1),
                           self.dimensions(1, 2, 1024, 16)):
            with self.subTest(dimensions=dimensions):
                self.assertEqual(dimensions, evidence.validate_dimensions(dimensions, 2, 5))
        for seed in (-(1 << 63), (1 << 63) - 1):
            request = {**self.request(), "seed": seed, "schemaVersion": 2, "dimensions": self.dimensions()}
            self.assertEqual(self.dimensions(), evidence.validate_request(request))

    def test_reference_work_accepts_exact_32million_and_refuses_one_step_more(self):
        exact = self.dimensions(1, 4000, 0, 1)
        self.assertEqual(exact, evidence.validate_dimensions(exact, 0, 1))
        with self.assertRaisesRegex(evidence.EvidenceError, "reference-work"):
            evidence.validate_dimensions(self.dimensions(1, 4001, 0, 1), 0, 1)
        # Each dimension is individually valid; their combined reference work is not.
        with self.assertRaisesRegex(evidence.EvidenceError, "reference-work"):
            evidence.validate_dimensions(self.dimensions(16, 2000, 1024, 16), 0, 1)

    def test_conservative_action_bound_accepts_20000_and_does_not_use_actual_retry_counts(self):
        exact = self.dimensions(1, 2000, 0, 1)
        self.assertEqual(exact, evidence.validate_dimensions(exact, 0, 5))
        with self.assertRaisesRegex(evidence.EvidenceError, "iteration action"):
            evidence.validate_dimensions(self.dimensions(1, 2001, 0, 1), 0, 5)
        exact_queries = self.dimensions(1, 1999, 2, 1)
        self.assertEqual(exact_queries, evidence.validate_dimensions(exact_queries, 0, 5))
        with self.assertRaisesRegex(evidence.EvidenceError, "iteration action"):
            evidence.validate_dimensions(self.dimensions(1, 1999, 3, 1), 0, 5)
        for config in ((-1, 1), (3, 1), (0, 0), (0, 6), (False, 1), (0, True)):
            with self.subTest(config=config), self.assertRaises(evidence.EvidenceError):
                evidence.validate_dimensions(self.dimensions(), *config)

    def test_checked_dimensions_do_not_alias_caller_mutable_objects(self):
        dimensions = self.dimensions()
        accepted = evidence.validate_dimensions(dimensions, 0, 1)
        dimensions["commits"] = 1
        self.assertEqual(33, accepted["commits"])
        accepted["entities"] = 0
        self.assertEqual(7, evidence.validate_dimensions(self.dimensions(), 0, 1)["entities"])


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

    def fixture(self, warmups=0, repetitions=1, dimensions=None):
        self.request.update(warmups=warmups, repetitions=repetitions)
        scaled = dimensions is not None
        if scaled:
            self.request.update(schemaVersion=2, dimensions=copy.deepcopy(dimensions))
            entities, commits, queries, width = (dimensions[k] for k in evidence.DIMENSION_FIELDS)
            mixed = commits - entities
            retries = min(2, mixed)
            read_counts = {key: queries // 5 + (index < queries % 5)
                           for index, key in enumerate(("GET", "SCAN", "SELECT", "FILTER_SCAN", "SUM"))}
            w = {"attempts": commits + retries, "commits": commits, "retries": retries, "writeOperations": commits,
                 "reads": queries, "noOpCommits": 0, "putOperations": commits, "retractOperations": 0,
                 "noOpPuts": 0, "noOpRetractions": 0, "putsReplacingPresent": 0, "retractionsRemovingPresent": 0,
                 "geometricOverlaps": 0, "entityTouches": {f"e-{i}": mixed + 1 if i == 0 else 1 for i in range(entities)},
                 "scheduledIntents": {"SEED": entities, "APPEND": mixed, "CORRECTION": 0, "RETRACTION": 0,
                                      "REPEAT": 0, "RETRY": retries}, "readOperators": read_counts,
                 "historicalReads": 0, "headReads": queries, "pointHits": 0, "pointMisses": read_counts["GET"],
                 "emptyResults": queries, "nonemptyResults": 0}
        else:
            entities, commits, queries, width = 16, 128, 64, 8
            w = workload()
        source_segments = math.ceil(commits / min(16, math.ceil(commits / 2)))
        actions = w["attempts"] + w["reads"]
        kinds = [("LOAD", "CREATE")] + [("LOAD", "COMMIT")] * entities
        kinds += [("MIXED", "COMMIT")] * (commits - entities) + [("MIXED", "RETRY")] * w["retries"]
        for key, count in w["readOperators"].items():
            kinds += [("MIXED", key)] * count
        kinds += [("MAINTENANCE", "JOURNAL_REOPEN")] + [("MAINTENANCE", "SEGMENT_WRITE")] * source_segments
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
            for segment in range(source_segments + 1):
                content = f"segment-{segment}".encode()
                (home / f"{hashlib.sha256(content).hexdigest()}.segment").write_bytes(content)
            lengths = {p.name: p.stat().st_size for p in home.iterdir()}
            iterations.append({"index": index, "warmup": index < warmups, "directory": str(home),
                               "namespacePreparationNanos": 20, "actionLoopNanos": loop,
                               "actionServiceNanos": service, "actionValidationNanos": actions * 2, "actionOtherNanos": 3,
                               "actionLoopOperationsPerSecond": actions * 1e9 / loop,
                               "totalValidationNanos": actions * 2 + 10, "validationTasks": actions + source_segments + 8,
                               "sourceSegments": source_segments, "replacementSegments": 1, "pinnedReadersDuringPublication": 1,
                               "namedStorage": {"fileLengths": lengths, "totalBytes": sum(lengths.values())},
                               "samples": copy.deepcopy(samples), "summaries": summaries(samples)})
            before = {key: (index + 1) * 100 + number for number, key in enumerate(evidence.IO_FIELDS)}
            delta = {key: number + 1 for number, key in enumerate(evidence.IO_FIELDS)}
            io.append({"index": index, "warmup": index < warmups, "directory": str(home), "before": before,
                       "after": {key: before[key] + delta[key] for key in evidence.IO_FIELDS}, "delta": delta, "unavailable": None})
        result = {"schemaVersion": 1, "corpusVersion": 1,
                  "spec": dict(zip(evidence.SPEC_FIELDS, (self.request["seed"], entities, commits, queries, width,
                                                         *evidence.PROFILES[self.request["profile"]]))),
                  "config": {"warmups": warmups, "repetitions": repetitions}, "fingerprint": "c" * 64,
                  "historyFingerprint": hashlib.sha256(self.journal).hexdigest(), "encodedHistoryBytes": len(self.journal),
                  "workload": w, "runtime": {"javaVersion": "25.0.5", "javaRuntimeVersion": "25.0.5+8-LTS",
                  "javaVendor": "test-vendor", "osName": "Linux", "osVersion": platform.release(),
                  "architecture": platform.machine(), "availableProcessors": 2, "maxHeapBytes": 256 * 1024 * 1024,
                  "fileStoreType": "ext4"}, "runRoot": str(self.root), "preparationNanos": 40,
                  "iterations": iterations, "gaps": ["Observed developer data; backing device unknown"]}
        envelope = {"schemaVersion": 2 if scaled else 1,
                    **{key: self.request[key] for key in ("runId", "source", "profile", "seed")},
                    "workerPid": self.pid, "status": "success", "error": None, "io": io, "result": result}
        if scaled:
            envelope["dimensions"] = copy.deepcopy(dimensions)
        return envelope

    def scaled(self, entities=7, commits=33, queries=11, width=3, warmups=0, repetitions=1):
        for home in self.root.iterdir():
            shutil.rmtree(home)
        self.doc = self.fixture(warmups, repetitions, {"entities": entities, "commits": commits,
                                                     "queries": queries, "intervalWidth": width})
        return self.doc

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

    def recorded(self, doc=None, request=None, kernel=None, architecture=None, stores=None):
        runtime = self.doc["result"]["runtime"]
        return evidence.validate_recorded_worker(
            self.doc if doc is None else doc, self.request if request is None else request,
            self.pid, self.stores if stores is None else stores, self.mount,
            runtime["osVersion"] if kernel is None else kernel,
            runtime["architecture"] if architecture is None else architecture)

    def no_native_observation(self):
        stack = ExitStack()
        for target, names in ((evidence, ("_directory", "_files", "_entries", "_path")),
                              (evidence.platform, ("release", "machine")),
                              (evidence.os, ("scandir", "stat", "lstat")),
                              (Path, ("stat", "lstat", "open", "resolve", "iterdir", "is_symlink"))):
            for name in names:
                stack.enter_context(mock.patch.object(target, name,
                    side_effect=AssertionError(f"Recorded validation observed native state: {name}")))
        return stack

    def recorded_rejects(self, mutation):
        doc = copy.deepcopy(self.doc)
        mutation(doc)
        with self.no_native_observation(), self.assertRaises(evidence.EvidenceError):
            self.recorded(doc=doc)

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

    def test_scaled_7_33_11_3_evidence_has_derived_groups_lifecycle_and_ten_files(self):
        self.scaled()
        accepted = self.validate()
        self.assertEqual(2, self.doc["schemaVersion"])
        self.assertEqual(1, self.doc["result"]["schemaVersion"])
        self.assertEqual(1, self.doc["result"]["corpusVersion"])
        self.assertEqual(10, accepted["retainedFileCount"])
        iteration = self.doc["result"]["iterations"][0]
        self.assertEqual(3, iteration["sourceSegments"])
        self.assertEqual(57, iteration["validationTasks"], "35attempts+11reads+3segments+8checks")
        self.assertEqual({"GET": 3, "SCAN": 2, "SELECT": 2, "FILTER_SCAN": 2, "SUM": 2},
                         self.doc["result"]["workload"]["readOperators"])
        self.assertEqual(7, next(s["count"] for s in iteration["summaries"]
                                if s["phase"] == "LOAD" and s["kind"] == "COMMIT"))

    def test_zero_and_fewer_than_five_reads_keep_zero_metadata_but_no_zero_sample_groups(self):
        for queries in range(5):
            with self.subTest(queries=queries):
                self.scaled(entities=2, commits=5, queries=queries, width=1)
                self.validate()
                counts = self.doc["result"]["workload"]["readOperators"]
                self.assertEqual([int(index < queries) for index in range(5)], list(counts.values()))
                summaries_present = {s["kind"] for s in self.doc["result"]["iterations"][0]["summaries"]}
                self.assertEqual({kind for kind, count in counts.items() if count}, summaries_present & set(counts))
                if queries == 0:
                    self.assertEqual(0, self.doc["result"]["workload"]["pointHits"])
                    self.assertEqual(0, self.doc["result"]["workload"]["emptyResults"])

    def test_large_seed_prefix_and_minimal_commit_corpus_are_both_admitted(self):
        for entities, commits in ((1024, 1025), (1, 2)):
            with self.subTest(entities=entities, commits=commits):
                self.scaled(entities=entities, commits=commits, queries=0, width=1)
                accepted = self.validate()
                self.assertEqual(entities, self.doc["result"]["workload"]["scheduledIntents"]["SEED"])
                source_count = math.ceil(commits / min(16, math.ceil(commits / 2)))
                self.assertEqual(source_count + 7, accepted["retainedFileCount"])

    def test_scaled_request_envelope_and_inner_result_versions_cannot_be_mixed(self):
        self.scaled()
        for mutation in (lambda d: d.update(schemaVersion=1), lambda d: d.update(schemaVersion=True),
                         lambda d: d.pop("dimensions"), lambda d: d.update(extra=1),
                         lambda d: d["result"].update(schemaVersion=2),
                         lambda d: d["result"].update(corpusVersion=2)):
            self.rejects(mutation)
        self.request.pop("schemaVersion")
        self.request.pop("dimensions")
        with self.assertRaises(evidence.EvidenceError):
            self.validate()

    def test_legacy_envelope_and_default_spec_reject_scaled_fields_without_schema2_admission(self):
        self.validate()
        self.assertEqual(evidence.ENVELOPE_FIELDS, set(self.doc))
        self.rejects(lambda d: d.update(dimensions=dict(evidence.DEFAULT_DIMENSIONS)))
        self.rejects(lambda d: d.update(schemaVersion=2))
        self.rejects(lambda d: d["result"]["spec"].update(commits=33))

    def test_every_scaled_dimension_echo_and_spec_field_is_compared_to_the_request(self):
        self.scaled()
        for name in evidence.DIMENSION_FIELDS:
            self.rejects(lambda d, name=name: d["dimensions"].update({name: d["dimensions"][name] + 1}))
        for name in evidence.SPEC_FIELDS:
            self.rejects(lambda d, name=name: d["result"]["spec"].update({name: d["result"]["spec"][name] + 1}))
        self.rejects(lambda d: d["dimensions"].update(entities=True))
        self.rejects(lambda d: d["dimensions"].update(extra=0))

    def test_scaled_tampering_with_phase_counts_summaries_and_file_shape_is_rejected(self):
        self.scaled()
        for mutation in (lambda d: d["result"]["workload"]["readOperators"].update(GET=13),
                         lambda d: d["result"]["workload"]["entityTouches"].update({"e-7": 1}),
                         lambda d: d["result"]["workload"]["scheduledIntents"].update(SEED=16),
                         lambda d: d["result"]["iterations"][0].update(sourceSegments=8),
                         lambda d: d["result"]["iterations"][0].update(validationTasks=62),
                         lambda d: d["result"]["iterations"][0]["samples"][7].update(phase="MIXED"),
                         lambda d: d["result"]["iterations"][0]["summaries"][1].update(count=16),
                         lambda d: d["result"]["iterations"][0]["namedStorage"]["fileLengths"].pop("transactions.journal")):
            self.rejects(mutation)

    def test_scaled_preflight_budget_failure_occurs_before_any_retained_path_inspection(self):
        self.request.update(schemaVersion=2, dimensions={"entities": 1, "commits": 2001, "queries": 0, "intervalWidth": 1},
                            warmups=0, repetitions=5)
        with mock.patch.object(evidence, "_directory", side_effect=AssertionError("Native inspection reached")) as inspect:
            with self.assertRaisesRegex(evidence.EvidenceError, "iteration action"):
                self.validate()
            inspect.assert_not_called()

    def test_scaled_profiles_preserve_their_exact_percentage_presets(self):
        for profile in ("APPEND", "CORRECTIONS", "RETRACTIONS", "HOT_ENTITY"):
            with self.subTest(profile=profile):
                self.request["profile"] = profile
                self.scaled(entities=2, commits=5, queries=2, width=1)
                self.validate()
                self.rejects(lambda d: d["result"]["spec"].update(retryPercent=d["result"]["spec"]["retryPercent"] + 1))

    def test_recorded_default_recomputes_exact_declarations_after_original_files_disappear(self):
        expected_lengths = dict(self.doc["result"]["iterations"][0]["namedStorage"]["fileLengths"])
        self.doc["result"]["runtime"].update(osVersion="archived-kernel", architecture="amd64")
        shutil.rmtree(self.stores)
        with self.no_native_observation():
            accepted = self.recorded(kernel="archived-kernel", architecture="x86_64")
        self.assertEqual({"encodedHistoryBytes", "historyFingerprint", "sourceSegments", "retainedFileCount",
                          "totalLogicalBytes", "iterations"}, set(accepted))
        self.assertEqual((len(self.journal), hashlib.sha256(self.journal).hexdigest(), 8, 15),
                         tuple(accepted[k] for k in ("encodedHistoryBytes", "historyFingerprint", "sourceSegments",
                                                    "retainedFileCount")))
        iteration = accepted["iterations"][0]
        self.assertEqual({"index", "warmup", "directory", "fileLengths", "logicalBytes", "processIo"}, set(iteration))
        self.assertEqual(expected_lengths, iteration["fileLengths"])
        self.assertEqual(sum(expected_lengths.values()), accepted["totalLogicalBytes"])
        self.assertEqual(accepted["totalLogicalBytes"], iteration["logicalBytes"])
        self.assertEqual((0, False, str(self.root / "iteration-0")),
                         tuple(iteration[k] for k in ("index", "warmup", "directory")))
        self.assertEqual(5 / len(self.journal), iteration["processIo"]["processReadBytesPerEncodedJournalByte"])

    def test_recorded_scaled_dimensions_and_zero_queries_need_no_original_files(self):
        for queries, expected_reads in ((11, {"GET": 3, "SCAN": 2, "SELECT": 2, "FILTER_SCAN": 2, "SUM": 2}),
                                        (0, dict.fromkeys(("GET", "SCAN", "SELECT", "FILTER_SCAN", "SUM"), 0))):
            with self.subTest(queries=queries):
                self.scaled(queries=queries)
                shutil.rmtree(self.stores)
                with self.no_native_observation():
                    accepted = self.recorded()
                self.assertEqual((3, 10), (accepted["sourceSegments"], accepted["retainedFileCount"]))
                self.assertEqual(expected_reads, self.doc["result"]["workload"]["readOperators"])
                self.assertEqual(10, len(accepted["iterations"][0]["fileLengths"]))
                # Recreate only fixture scaffolding for the next independent recorded case.
                self.root.mkdir(parents=True)

    def test_recorded_warmups_and_repetitions_validate_every_iteration_after_removal(self):
        self.scaled(warmups=1, repetitions=2)
        expected = sum(item["namedStorage"]["totalBytes"] for item in self.doc["result"]["iterations"])
        shutil.rmtree(self.stores)
        with self.no_native_observation():
            accepted = self.recorded()
        self.assertEqual((30, expected), (accepted["retainedFileCount"], accepted["totalLogicalBytes"]))
        self.assertEqual([0, 1, 2], [i["index"] for i in accepted["iterations"]])
        self.assertEqual([True, False, False], [i["warmup"] for i in accepted["iterations"]])
        self.recorded_rejects(lambda d: d["result"]["iterations"][1].update(warmup=True))
        self.recorded_rejects(lambda d: d["result"]["iterations"][2].update(
            directory=d["result"]["iterations"][1]["directory"]))
        self.recorded_rejects(lambda d: d["io"][2].update(index=1))

    def test_recorded_context_is_supplied_and_kernel_architecture_filesystem_and_runtime_are_checked(self):
        with self.no_native_observation():
            for kernel, architecture in (("another-kernel", "x86_64"), ("", "x86_64"),
                                         (False, "x86_64"), ("kernel", ""), ("kernel", 1)):
                with self.subTest(kernel=kernel, architecture=architecture), self.assertRaises(evidence.EvidenceError):
                    self.recorded(kernel=kernel, architecture=architecture)
            with self.assertRaises(evidence.EvidenceError):
                self.recorded(architecture="unrelated-architecture")
        for mutation in (lambda d: d["result"]["runtime"].update(osName="Darwin"),
                         lambda d: d["result"]["runtime"].update(javaVersion="26"),
                         lambda d: d["result"]["runtime"].update(fileStoreType="xfs"),
                         lambda d: d["result"]["runtime"].update(maxHeapBytes=evidence.MAX_HEAP_BYTES + 1),
                         lambda d: d["result"]["runtime"].update(availableProcessors=True)):
            self.recorded_rejects(mutation)
        self.doc["result"]["runtime"]["architecture"] = "arm64"
        with self.no_native_observation():
            self.recorded(architecture="aarch64")

    def test_recorded_paths_require_absolute_canonical_linux_lexical_topology(self):
        for stores in ("relative/stores", "/saved/../stores", "//server/stores", "/saved//stores",
                       "/saved/./stores", "/saved/stores/", "/saved/stores\n"):
            with self.subTest(stores=stores), self.no_native_observation(), self.assertRaises(evidence.EvidenceError):
                self.recorded(stores=stores)
        for root in (str(self.parent), str(self.root) + "/../mdb-workload-other", str(self.root) + "/",
                     str(self.stores / "wrong-prefix")):
            self.recorded_rejects(lambda d, root=root: d["result"].update(runRoot=root))
        for home in (str(self.root / "iteration-1"), str(self.root / "iteration-0") + "/.",
                     str(self.root / "iteration-0") + "\x00", "iteration-0"):
            self.recorded_rejects(lambda d, home=home: d["result"]["iterations"][0].update(directory=home))
        self.recorded_rejects(lambda d: d["io"][0].update(directory=str(self.parent)))

    def test_recorded_bindings_and_dimensions_remain_exact_without_live_observations(self):
        self.scaled()
        for key, value in (("runId", "d" * 32), ("source", "d" * 40), ("profile", "APPEND"), ("seed", 43),
                           ("workerPid", self.pid + 1), ("workerPid", True), ("schemaVersion", 1),
                           ("status", "failed"), ("error", {"message": "failed"})):
            self.recorded_rejects(lambda d, key=key, value=value: d.update({key: value}))
        for name in evidence.DIMENSION_FIELDS:
            self.recorded_rejects(lambda d, name=name: d["dimensions"].update({name: d["dimensions"][name] + 1}))
            self.recorded_rejects(lambda d, name=name: d["result"]["spec"].update({name: d["result"]["spec"][name] + 1}))
        request = copy.deepcopy(self.request)
        request["dimensions"]["queries"] = 12
        with self.no_native_observation(), self.assertRaises(evidence.EvidenceError):
            self.recorded(request=request)
        self.recorded_rejects(lambda d: d.update(extra=1))
        self.recorded_rejects(lambda d: d["result"].update(schemaVersion=2))

    def test_recorded_raw_samples_summaries_and_action_timing_are_recomputed(self):
        mutations = (lambda d: d["result"]["iterations"][0]["samples"].pop(),
                     lambda d: d["result"]["iterations"][0]["samples"][0].update(kind="COMMIT"),
                     lambda d: d["result"]["iterations"][0]["samples"][0].update(elapsedNanos=True),
                     lambda d: d["result"]["iterations"][0]["summaries"][1].update(p50Nanos=9),
                     lambda d: d["result"]["iterations"][0]["summaries"][1].update(threadCpuNanos=0),
                     lambda d: d["result"]["iterations"][0]["summaries"].reverse(),
                     lambda d: d["result"]["iterations"][0].update(actionServiceNanos=1),
                     lambda d: d["result"]["iterations"][0].update(actionLoopNanos=1),
                     lambda d: d["result"]["iterations"][0].update(totalValidationNanos=0),
                     lambda d: d["result"]["iterations"][0].update(actionLoopOperationsPerSecond=0),
                     lambda d: d["result"]["iterations"][0].update(validationTasks=0),
                     lambda d: d["result"]["workload"].update(attempts=1),
                     lambda d: d["result"]["workload"]["readOperators"].update(GET=1))
        for mutation in mutations:
            self.recorded_rejects(mutation)

    def test_recorded_exact_named_file_declarations_and_aggregate_caps_are_enforced(self):
        def rename(doc, old, new):
            lengths = doc["result"]["iterations"][0]["namedStorage"]["fileLengths"]
            lengths[new] = lengths.pop(old)

        lengths = self.doc["result"]["iterations"][0]["namedStorage"]["fileLengths"]
        segment = next(name for name in lengths if name.endswith(".segment"))
        manifest = next(name for name in lengths if name.startswith("checkpoint-0000000000000000001-"))
        for mutation in (lambda d: rename(d, segment, "../outside.segment"),
                         lambda d: rename(d, segment, "d" * 63 + ".segment"),
                         lambda d: rename(d, manifest, manifest.replace("0000000000000000001", "0000000000000000004")),
                         lambda d: d["result"]["iterations"][0]["namedStorage"]["fileLengths"].pop(segment),
                         lambda d: d["result"]["iterations"][0]["namedStorage"]["fileLengths"].update({segment: 0}),
                         lambda d: d["result"]["iterations"][0]["namedStorage"]["fileLengths"].update({segment: True}),
                         lambda d: d["result"]["iterations"][0]["namedStorage"]["fileLengths"].update({"transactions.journal": 0}),
                         lambda d: d["result"]["iterations"][0]["namedStorage"]["fileLengths"].update({next(iter(evidence.CONTROL_NAMES)): 1}),
                         lambda d: d["result"]["iterations"][0]["namedStorage"].update(totalBytes=0)):
            self.recorded_rejects(mutation)
        self.scaled(warmups=1, repetitions=2)
        total = sum(item["namedStorage"]["totalBytes"] for item in self.doc["result"]["iterations"])
        with self.no_native_observation(), mock.patch.object(evidence, "MAX_FILES", 30), \
                mock.patch.object(evidence, "MAX_RETAINED_BYTES", total):
            self.recorded()
        for name, limit in (("MAX_FILES", 29), ("MAX_RETAINED_BYTES", total - 1)):
            with self.no_native_observation(), mock.patch.object(evidence, name, limit), \
                    self.assertRaises(evidence.EvidenceError):
                self.recorded()

    def test_recorded_io_is_recomputed_and_missing_reset_counters_remain_explicit(self):
        for mutation in (lambda d: d["io"][0]["delta"].update(write_bytes=0),
                         lambda d: d["io"][0]["delta"].update(cancelled_write_bytes=0),
                         lambda d: d["io"][0]["before"].update(read_bytes=True),
                         lambda d: d["io"][0].update(unavailable="unknown")):
            self.recorded_rejects(mutation)
        original = copy.deepcopy(self.doc["io"][0])
        cases = ((None, None, "Before snapshot unavailable: NoSuchFileException"),
                 (original["before"], None, "After snapshot unavailable: AccessDeniedException"),
                 (original["before"], {**original["after"], "rchar": original["before"]["rchar"] - 1},
                  "Nonmonotonic process I/O counter: rchar"))
        for before, after, reason in cases:
            with self.subTest(reason=reason):
                self.doc["io"][0].update(before=before, after=after, delta=None, unavailable=reason)
                with self.no_native_observation():
                    observed = self.recorded()["iterations"][0]["processIo"]
                self.assertEqual(reason, observed["unavailable"])
                self.assertIsNone(observed["delta"])
                self.assertIsNone(observed["processReadBytesPerEncodedJournalByte"])
                self.assertIsNone(observed["processWriteBytesPerEncodedJournalByte"])
                self.recorded_rejects(lambda d: d["io"][0].update(unavailable="unexplained"))

    def test_recorded_return_values_do_not_alias_declared_file_lengths_or_io_deltas(self):
        with self.no_native_observation():
            accepted = self.recorded()
        iteration = accepted["iterations"][0]
        original_length = iteration["fileLengths"]["transactions.journal"]
        original_read = iteration["processIo"]["delta"]["read_bytes"]
        self.doc["result"]["iterations"][0]["namedStorage"]["fileLengths"]["transactions.journal"] = 0
        self.doc["io"][0]["delta"]["read_bytes"] = 0
        self.assertEqual(original_length, iteration["fileLengths"]["transactions.journal"])
        self.assertEqual(original_read, iteration["processIo"]["delta"]["read_bytes"])

    def test_recorded_admission_does_not_claim_live_hash_allocation_or_namespace_verification(self):
        journal = self.root / "iteration-0" / "transactions.journal"
        journal.write_bytes(b"x" * len(self.journal))
        with self.no_native_observation():
            accepted = self.recorded()
        self.assertNotIn("totalAttributedAllocatedBytes", accepted)
        self.assertNotIn("files", accepted["iterations"][0])
        with self.assertRaisesRegex(evidence.EvidenceError, "journal identity"):
            self.validate()
        shutil.rmtree(self.stores)
        with self.no_native_observation():
            self.assertEqual(accepted, self.recorded())
        with self.assertRaises(evidence.EvidenceError):
            self.validate()


if __name__ == "__main__":
    unittest.main()
