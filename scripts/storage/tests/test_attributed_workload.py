import copy
import hashlib
import importlib.util
import math
from pathlib import Path
import struct
import unittest
from unittest import mock


_SPEC = importlib.util.spec_from_file_location("attributed_workload", Path(__file__).resolve().parents[1] / "attributed_workload.py")
attributed = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(attributed)

GOLDEN_HEADER = bytes.fromhex("4d52444e4a4e4c00000000010000000200640062a9cf7c87")
GOLDEN_RECORD = bytes.fromhex("4d54584e0000000100000022182a4007"
        "00000000000000010000000100720000000101000000010065000000000000000007"
        "538cd524")


def fixture_crc(data):
    # Independently structured byte-table CRC32C, checked against the existing literal goldens.
    table = []
    for byte in range(256):
        value = byte
        for _ in range(8):
            value = value >> 1 ^ (0x82F63B78 if value & 1 else 0)
        table.append(value)
    value = 0xFFFFFFFF
    for byte in data:
        value = table[(value ^ byte) & 255] ^ (value >> 8)
    return value ^ 0xFFFFFFFF


def identity(text):
    image = text.encode("utf-16-be", "surrogatepass")
    return struct.pack(">i", len(image) // 2) + image


def header(lineage="workload-v1"):
    image = b"MRDNJNL\0" + struct.pack(">i", 1) + identity(lineage)
    return image + struct.pack(">I", fixture_crc(image))


def frame(sequence, request=None, entity="e-0", start=0, end=2, kind=1, value=7, count=1, flags=None, trailing=b""):
    flags = ((start is not None) + 2 * (end is not None)) if flags is None else flags
    operation = bytes([kind]) + identity(entity) + bytes([flags])
    if flags & 1:
        operation += struct.pack(">q", start)
    if flags & 2:
        operation += struct.pack(">q", end)
    if kind == 1:
        operation += struct.pack(">q", value)
    body = struct.pack(">q", sequence) + identity(request or f"r-{sequence}") + struct.pack(">i", count) + operation + trailing
    prefix = b"MTXN" + struct.pack(">ii", 1, len(body))
    return prefix + struct.pack(">I", fixture_crc(prefix)) + body + struct.pack(">I", fixture_crc(body))


def snapshot(reads=0, writes=0, forces=0, role="JOURNAL", eof=0):
    rows = [[0] * 14 for _ in range(8)]
    row = rows[attributed.ROLES.index(role)]
    if reads:
        row[0] = row[1] = 1
        row[2] = reads
    if eof:
        row[0] += eof
        row[1] += eof
        row[4] = eof
    if writes:
        row[6] = row[7] = 1
        row[8] = writes
    row[11] = row[12] = forces
    return {"schemaVersion": 1, "overflow": False, "invalidResult": False, "counts": rows}


def combine(*values):
    return {"schemaVersion": 1, "overflow": False, "invalidResult": False,
            "counts": [[sum(value["counts"][r][c] for value in values) for c in range(14)] for r in range(8)]}


def fixture(queries=5, warmups=0, repetitions=1):
    """Two literal original frames, one exact retry, and a hand-enumerated lifecycle."""
    source_header = header()
    frames = [frame(1), frame(2, start=0, end=1, value=8)]
    spec = {"seed": 7, "entities": 1, "commits": 2, "queries": queries, "intervalWidth": 2,
            "correctionPercent": 100, "retractionPercent": 0, "repeatPercent": 0, "retryPercent": 100,
            "hotEntityPercent": 100, "historicalReadPercent": 100}
    result = {"spec": spec, "config": {"warmups": warmups, "repetitions": repetitions},
              "encodedHistoryBytes": 198, "historyFingerprint": hashlib.sha256(source_header + b"".join(frames)).hexdigest(),
              "workload": {"commits": 2, "reads": queries, "retries": 1, "attempts": 3, "writeOperations": 2,
                           "putOperations": 2, "retractOperations": 0, "entityTouches": {"e-0": 2}}, "iterations": []}
    scopes = []

    def add(category, iteration=-1, phase=None, kind=None, counters=None):
        scopes.append({"ordinal": len(scopes), "iteration": iteration, "category": category, "phase": phase, "kind": kind,
                       "callReturned": True, "counters": snapshot() if counters is None else counters,
                       "operationFailureType": None, "observationFailureType": None, "cleanupOutsideCapture": False})

    add("PREPARATION")
    add("PREPARATION")
    for iteration in range(warmups + repetitions):
        samples = []

        def scope(category, counters=None):
            add(category, iteration, counters=counters)

        def operation(phase, kind, counters=None):
            add("OPERATION", iteration, phase, kind, counters)
            samples.append({"phase": phase, "kind": kind})

        scope("NAMESPACE", snapshot(forces=1, role="DIRECTORY"))
        scope("TEARDOWN")
        for _ in range(3):
            scope("NAMESPACE", snapshot(forces=1, role="DIRECTORY"))
        operation("LOAD", "CREATE", combine(snapshot(168, 42, 3), snapshot(154, 154, 1, "MANIFEST"),
                  snapshot(forces=1, role="CONTROL"), snapshot(forces=5, role="DIRECTORY")))
        operation("LOAD", "COMMIT", snapshot(writes=78, forces=1))
        scope("VALIDATION")
        operation("MIXED", "COMMIT", snapshot(writes=78, forces=1))
        scope("VALIDATION")
        operation("MIXED", "RETRY")
        scope("VALIDATION")
        for index in range(queries):
            operation("MIXED", ("GET", "SCAN", "SELECT", "FILTER_SCAN", "SUM")[index % 5])
            scope("VALIDATION")
        scope("VALIDATION")
        scope("TEARDOWN")
        operation("MAINTENANCE", "JOURNAL_REOPEN", combine(snapshot(reads=438, forces=1),
                  snapshot(reads=154, role="MANIFEST"), snapshot(forces=1, role="CONTROL"), snapshot(forces=3, role="DIRECTORY")))
        scope("VALIDATION")
        scope("TEARDOWN")
        scope("OWNER_ACQUISITION", snapshot(forces=1, role="DIRECTORY"))
        scope("OWNER_ACQUISITION", snapshot(reads=198))
        scope("PREFIX_VERIFICATION", snapshot(reads=198))
        scope("VALIDATION")
        for _ in range(2):
            operation("MAINTENANCE", "SEGMENT_WRITE", snapshot(writes=200, forces=1, role="SEGMENT"))
            scope("VALIDATION")
        operation("MAINTENANCE", "CHECKPOINT_PUBLISH", combine(snapshot(reads=240, forces=1),
                  snapshot(reads=400, forces=2, role="SEGMENT"), snapshot(154, 266, 1, "MANIFEST"), snapshot(forces=2, role="DIRECTORY")))
        scope("READER_ACQUISITION", combine(snapshot(reads=198), snapshot(reads=400, role="SEGMENT"),
              snapshot(reads=266, role="MANIFEST"), snapshot(forces=1, role="CONTROL"), snapshot(forces=1, role="DIRECTORY")))
        scope("VALIDATION")
        operation("MAINTENANCE", "COMPACTION_PLAN")
        scope("VALIDATION")
        operation("MAINTENANCE", "COMPACTION_PUBLISH", combine(snapshot(reads=396, forces=1),
                  snapshot(678, 278, 2, "SEGMENT"), snapshot(266, 210, 1, "MANIFEST"), snapshot(forces=2, role="DIRECTORY")))
        scope("READER_ACQUISITION", combine(snapshot(reads=198), snapshot(reads=278, role="SEGMENT"),
              snapshot(reads=210, role="MANIFEST"), snapshot(forces=1, role="DIRECTORY")))
        scope("VALIDATION", snapshot(reads=678, role="SEGMENT"))
        for _ in range(4):
            scope("TEARDOWN")
        operation("MAINTENANCE", "CHECKPOINT_REOPEN", combine(snapshot(reads=594, forces=1),
                  snapshot(reads=556, role="SEGMENT"), snapshot(reads=210, role="MANIFEST"),
                  snapshot(forces=1, role="CONTROL"), snapshot(forces=3, role="DIRECTORY")))
        scope("VALIDATION")
        scope("TEARDOWN")
        scope("EXTERNAL_HASH")
        scope("FILE_INVENTORY")
        result["iterations"].append({"index": iteration, "warmup": iteration < warmups, "sourceSegments": 2, "samples": samples})
    payload = {"schemaVersion": 1, "mode": attributed.MODE, "scopes": scopes,
               "denominators": {"headerBytes": 42, "seedFrameBytes": 78, "mixedFrameBytes": 78,
                                "encodedHistoryBytes": 198, "newCommits": 2, "exactRetries": 1, "queries": queries},
               "gaps": ["Recorded caller-thread adapter work; external hashing and export are excluded"],
               "sourceHistory": {"headerHex": source_header.hex(), "framesHex": [value.hex() for value in frames]}}
    return payload, result


class RequestBudgetTests(unittest.TestCase):
    def dimensions(self, queries=64):
        return {"entities": 16, "commits": 128, "queries": queries, "intervalWidth": 8}

    def test_default_seed42_retry_draws_match_existing_java_literal_profile_counts(self):
        for profile, expected in (("APPEND", 1898), ("CORRECTIONS", 1962), ("RETRACTIONS", 1962), ("HOT_ENTITY", 2026)):
            with self.subTest(profile=profile):
                self.assertEqual(expected, attributed.request_budget(profile, 42, self.dimensions(), 1, 3))

    def test_exact2048_scope_reservation_is_admitted_then_one_read_more_is_rejected(self):
        self.assertEqual(2048, attributed.request_budget("CORRECTIONS", 42, self.dimensions(842), 0, 1))
        with self.assertRaisesRegex(attributed.AttributionError, "scope admission"):
            attributed.request_budget("CORRECTIONS", 42, self.dimensions(843), 0, 1)

    def test_retry_random_effective48bit_seed_alias_is_preserved(self):
        self.assertEqual(attributed.request_budget("HOT_ENTITY", 42, self.dimensions(), 0, 1),
                         attributed.request_budget("HOT_ENTITY", 42 + (1 << 48), self.dimensions(), 0, 1))
        self.assertGreater(attributed.request_budget("APPEND", -(1 << 63), self.dimensions(), 0, 1), 0)
        self.assertGreater(attributed.request_budget("APPEND", (1 << 63) - 1, self.dimensions(), 0, 1), 0)

    def test_java_random_vectors_and_power_of_two_branch(self):
        random = attributed._JavaRandom(0)
        self.assertEqual([60, 48, 29, 47, 15, 53, 91, 61, 19, 54], [random.next_int(100) for _ in range(10)])
        random = attributed._JavaRandom(0)
        self.assertEqual([2, 3, 0, 2, 2], [random.next_int(4) for _ in range(5)])

    def test_java_signed_int_rejection_consumes_a_second_draw(self):
        random = attributed._JavaRandom(0)
        with mock.patch.object(random, "_next", side_effect=[2147483647, 7]) as draws:
            self.assertEqual(7, random.next_int(100))
            self.assertEqual(2, draws.call_count)

    def test_invalid_types_dimensions_profiles_and_repetition_budgets_are_rejected(self):
        for profile, seed, dimensions, warmups, repetitions in (
                ("unknown", 42, self.dimensions(), 0, 1), (True, 42, self.dimensions(), 0, 1),
                ("APPEND", True, self.dimensions(), 0, 1), ("APPEND", 1 << 63, self.dimensions(), 0, 1),
                ("APPEND", 42, {**self.dimensions(), "entities": 0}, 0, 1),
                ("APPEND", 42, {**self.dimensions(), "commits": 16}, 0, 1),
                ("APPEND", 42, {**self.dimensions(), "intervalWidth": True}, 0, 1),
                ("APPEND", 42, {**self.dimensions(), "extra": 0}, 0, 1),
                ("APPEND", 42, self.dimensions(), True, 1), ("APPEND", 42, self.dimensions(), 0, 6)):
            with self.subTest(profile=profile, seed=seed, dimensions=dimensions, warmups=warmups), self.assertRaises(attributed.AttributionError):
                attributed.request_budget(profile, seed, dimensions, warmups, repetitions)

    def test_reference_and_conservative_action_bounds_precede_scope_admission(self):
        with self.assertRaisesRegex(attributed.AttributionError, "Reference-work"):
            attributed.request_budget("APPEND", 42, {"entities": 1, "commits": 2000, "queries": 0, "intervalWidth": 16}, 0, 1)
        with self.assertRaisesRegex(attributed.AttributionError, "Conservative action"):
            attributed.request_budget("APPEND", 42, {"entities": 1, "commits": 2000, "queries": 0, "intervalWidth": 1}, 2, 5)


class JournalSourceTests(unittest.TestCase):
    def test_bitwise_crc32c_matches_check_string_and_existing_independent_codec_literals(self):
        self.assertEqual(0xE3069283, attributed._crc32c(b"123456789"))
        self.assertEqual(0xE3069283, fixture_crc(b"123456789"))
        self.assertEqual("db", attributed._header(GOLDEN_HEADER))
        self.assertEqual({"sequence": 1, "request": "r", "kind": 1, "entity": "e", "start": None, "end": None, "value": 7},
                         attributed._frame(GOLDEN_RECORD))

    def test_every_partial_header_and_frame_is_rejected_without_a_prefix_result(self):
        for image, decoder in ((GOLDEN_HEADER, attributed._header), (GOLDEN_RECORD, attributed._frame)):
            for length in range(len(image)):
                with self.subTest(length=length, decoder=decoder), self.assertRaises(attributed.AttributionError):
                    decoder(image[:length])

    def test_all_literal_byte_mutations_are_rejected_by_magic_version_crc_or_body_checks(self):
        for image, decoder in ((GOLDEN_HEADER, attributed._header), (GOLDEN_RECORD, attributed._frame)):
            for offset in range(len(image)):
                corrupt = bytearray(image)
                corrupt[offset] ^= 1
                with self.subTest(offset=offset, decoder=decoder), self.assertRaises(attributed.AttributionError):
                    decoder(bytes(corrupt))

    def test_rechecks_validly_resigned_unknown_versions_lengths_and_trailing_bytes(self):
        for image, decoder in ((GOLDEN_HEADER + b"\0", attributed._header), (GOLDEN_RECORD + b"\0", attributed._frame),
                               (frame(1, count=2), attributed._frame), (frame(1, kind=3), attributed._frame),
                               (frame(1, flags=7), attributed._frame), (frame(1, start=2, end=1), attributed._frame),
                               (frame(1, trailing=b"x"), attributed._frame)):
            with self.subTest(image=image.hex()), self.assertRaises(attributed.AttributionError):
                decoder(image)
        for length in (-1, 25, 8 * 1024 * 1024 + 1):
            prefix = b"MTXN" + struct.pack(">ii", 1, length)
            with self.subTest(length=length), self.assertRaises(attributed.AttributionError):
                attributed._frame(prefix + struct.pack(">I", fixture_crc(prefix)))

    def test_retract_and_signed_extreme_values_decode_without_float_conversion(self):
        self.assertEqual(-(1 << 63), attributed._frame(frame(1, value=-(1 << 63)))["value"])
        self.assertEqual((1 << 63) - 1, attributed._frame(frame(1, value=(1 << 63) - 1))["value"])
        self.assertIsNone(attributed._frame(frame(1, kind=2))["value"])

    def test_valid_checksums_cannot_admit_unsupported_header_or_frame_versions_and_bad_identity_lengths(self):
        for image, decoder in ((header(), attributed._header), (frame(1), attributed._frame)):
            changed = bytearray(image)
            offset = 8 if decoder is attributed._header else 4
            changed[offset:offset + 4] = struct.pack(">i", 2)
            if decoder is attributed._header:
                changed[-4:] = struct.pack(">I", fixture_crc(changed[:-4]))
            else:
                changed[12:16] = struct.pack(">I", fixture_crc(changed[:12]))
            with self.subTest(decoder=decoder), self.assertRaises(attributed.AttributionError):
                decoder(bytes(changed))
        for units in (-1, 0, 32769):
            image = b"MRDNJNL\0" + struct.pack(">ii", 1, units)
            image += struct.pack(">I", fixture_crc(image))
            with self.subTest(units=units), self.assertRaises(attributed.AttributionError):
                attributed._header(image)


class AttributionTests(unittest.TestCase):
    def setUp(self):
        self.payload, self.result = fixture()

    def validate(self, payload=None, result=None):
        return attributed.validate_attribution(self.payload if payload is None else payload, self.result if result is None else result)

    def rejects(self, change):
        payload, result = copy.deepcopy(self.payload), copy.deepcopy(self.result)
        change(payload, result)
        with self.assertRaises(attributed.AttributionError):
            self.validate(payload, result)

    def rewrite_source(self, payload, result, frames=None, source_header=None):
        history = payload["sourceHistory"]
        if frames is not None:
            history["framesHex"] = [image.hex() for image in frames]
        if source_header is not None:
            history["headerHex"] = source_header.hex()
        values = [bytes.fromhex(value) for value in history["framesHex"]]
        first = bytes.fromhex(history["headerHex"])
        result.update(encodedHistoryBytes=len(first) + sum(map(len, values)),
                      historyFingerprint=hashlib.sha256(first + b"".join(values)).hexdigest())
        payload["denominators"].update(headerBytes=len(first), seedFrameBytes=len(values[0]),
                                     mixedFrameBytes=sum(map(len, values[1:])), encodedHistoryBytes=result["encodedHistoryBytes"])

    def operation(self, kind, phase=None):
        return next(scope for scope in self.payload["scopes"] if scope["kind"] == kind
                    and (phase is None or scope["phase"] == phase))

    def test_literal_fixture_has54_scopes_and_independently_computed_append_and_recovery_ratios(self):
        accepted = self.validate()
        self.assertEqual(54, len(self.payload["scopes"]))
        self.assertEqual({"schemaVersion", "mode", "denominators", "preparationTotals", "uncapturedWork", "iterations", "limits"}, set(accepted))
        self.assertEqual(["EXPORT", "PROCESS_TELEMETRY", "SUPERVISOR_VALIDATION"],
                         [entry["category"] for entry in accepted["uncapturedWork"]])
        self.assertTrue(all(entry["counters"] is None and entry["coverage"] == "EXCLUDED_EXTERNAL_IO"
                            for entry in accepted["uncapturedWork"]))
        self.assertEqual(self.payload["denominators"], accepted["denominators"])
        groups = {(group["phase"], group["kind"]): group for group in accepted["iterations"][0]["operationGroups"]}
        append = groups[("LOAD", "COMMIT")]
        self.assertEqual(78, append["totalWriteBytes"])
        self.assertEqual({"numerator": 78, "denominator": 78, "value": 1.0}, append["ratios"]["fileWriteBytesPerOriginalFrameByte"])
        recovery = groups[("MAINTENANCE", "JOURNAL_REOPEN")]
        self.assertEqual(592, recovery["totalReadBytes"])
        self.assertEqual({"numerator": 592, "denominator": 198, "value": 592 / 198},
                         recovery["ratios"]["fileReadBytesPerOriginalHistoryByte"])
        self.assertEqual(438, recovery["counters"]["JOURNAL"]["READ_BYTES"])
        self.assertEqual(154, recovery["counters"]["MANIFEST"]["READ_BYTES"])
        self.assertEqual(set(attributed.ROLES), set(recovery["counters"]))
        self.assertEqual(set(attributed.METRICS), set(recovery["counters"]["JOURNAL"]))

    def test_auxiliary_counters_never_enter_operation_ratios_and_exclusions_do_not_claim_zero_io(self):
        accepted = self.validate()["iterations"][0]
        operations = sum(group["totalReadBytes"] for group in accepted["operationGroups"])
        auxiliaries = sum(group["totalReadBytes"] for group in accepted["auxiliaryGroups"])
        self.assertEqual(operations, accepted["operationTotals"]["totalReadBytes"])
        self.assertEqual(operations + auxiliaries, accepted["capturedTotals"]["totalReadBytes"])
        self.assertGreater(auxiliaries, 0)
        groups = {group["category"]: group for group in accepted["auxiliaryGroups"]}
        self.assertEqual(678, groups["VALIDATION"]["totalReadBytes"])
        self.assertEqual(198, groups["PREFIX_VERIFICATION"]["totalReadBytes"])
        for category in ("EXTERNAL_HASH", "FILE_INVENTORY"):
            self.assertEqual("EXCLUDED_EXTERNAL_IO", groups[category]["coverage"])
            self.assertNotIn("ratios", groups[category])
        self.assertIn("do not establish zero", " ".join(self.validate()["limits"]))

    def test_preparation_counters_remain_visible_outside_iteration_and_operation_totals(self):
        before = self.validate()
        self.payload["scopes"][0]["counters"] = snapshot(reads=11)
        self.payload["scopes"][1]["counters"] = snapshot(writes=13, role="SEGMENT")
        accepted = self.validate()
        preparation = accepted["preparationTotals"]
        self.assertEqual((2, "ADAPTERS_ONLY", 11, 13),
                         tuple(preparation[key] for key in ("scopeCount", "coverage", "totalReadBytes", "totalWriteBytes")))
        self.assertEqual(before["iterations"], accepted["iterations"])
        self.assertNotIn("ratios", preparation)

    def test_validation_does_not_open_files_or_consult_live_platform_state(self):
        expected = self.validate()
        with mock.patch("builtins.open", side_effect=AssertionError("No live reads")), \
                mock.patch.object(Path, "stat", side_effect=AssertionError("No live stat")), \
                mock.patch.object(Path, "resolve", side_effect=AssertionError("No live paths")):
            self.assertEqual(expected, self.validate())

    def test_warmup_and_repetitions_keep_distinct_raw_totals_and_never_pool_ratios(self):
        payload, result = fixture(warmups=1, repetitions=2)
        accepted = self.validate(payload, result)
        self.assertEqual([True, False, False], [value["warmup"] for value in accepted["iterations"]])
        self.assertEqual([0, 1, 2], [value["index"] for value in accepted["iterations"]])
        self.assertNotIn("pooled", accepted)
        self.assertEqual(accepted["iterations"][0]["operationTotals"], accepted["iterations"][2]["operationTotals"])

    def test_zero_and_fewer_than_five_queries_have_exact_round_robin_operation_inventory(self):
        for queries in range(5):
            with self.subTest(queries=queries):
                payload, result = fixture(queries=queries)
                groups = self.validate(payload, result)["iterations"][0]["operationGroups"]
                query_groups = [group for group in groups if group["kind"] in attributed.QUERIES]
                self.assertEqual(list(attributed.QUERIES[:queries]), [group["kind"] for group in query_groups])
                self.assertTrue(all(group["scopeCount"] == 1 for group in query_groups))

    def test_retry_and_query_zero_denominators_remain_unavailable_even_with_nonzero_recorded_work(self):
        self.operation("GET")["counters"] = snapshot(reads=9)
        self.operation("RETRY")["counters"] = snapshot(writes=3)
        groups = self.validate()["iterations"][0]["operationGroups"]
        for group in groups:
            if group["kind"] in ("GET", "RETRY"):
                for ratio in group["ratios"].values():
                    self.assertEqual(0, ratio["denominator"])
                    self.assertIsNone(ratio["value"])
        self.assertEqual(9, next(group for group in groups if group["kind"] == "GET")["totalReadBytes"])

    def test_payload_schema_mode_denominator_keys_and_gap_bounds_are_closed(self):
        for change in (lambda p, r: p.update(extra=1), lambda p, r: p.update(schemaVersion=True),
                       lambda p, r: p.update(schemaVersion=2), lambda p, r: p.update(mode="ordinary"),
                       lambda p, r: p["denominators"].update(extra=1), lambda p, r: p.update(gaps=[]),
                       lambda p, r: p.update(gaps=["duplicate", "duplicate"]), lambda p, r: p.update(gaps=["x" * 2049])):
            self.rejects(change)

    def test_each_exported_denominator_is_compared_to_source_bytes_and_retry_schedule(self):
        for field in attributed.DENOMINATORS:
            self.rejects(lambda p, r, field=field: p["denominators"].update({field: p["denominators"][field] + 1}))
        self.rejects(lambda p, r: p["denominators"].update(newCommits=True))

    def test_different_original_frame_lengths_derive_seed_and_mixed_denominators_separately(self):
        self.rewrite_source(self.payload, self.result, [frame(1), frame(2, start=0, end=1, kind=2)])
        self.result["workload"].update(putOperations=1, retractOperations=1)
        self.operation("COMMIT", "MIXED")["counters"] = snapshot(writes=70, forces=1)
        accepted = self.validate()
        self.assertEqual((42, 78, 70, 190), tuple(accepted["denominators"][key] for key in
                         ("headerBytes", "seedFrameBytes", "mixedFrameBytes", "encodedHistoryBytes")))
        mixed = next(group for group in accepted["iterations"][0]["operationGroups"]
                     if group["phase"] == "MIXED" and group["kind"] == "COMMIT")
        self.assertEqual({"numerator": 70, "denominator": 70, "value": 1.0},
                         mixed["ratios"]["fileWriteBytesPerOriginalFrameByte"])

    def test_excessive_attributed_budget_is_refused_before_source_decoding(self):
        self.result["spec"].update(entities=16, commits=128, queries=843, retryPercent=10)
        self.result["spec"]["seed"] = 42
        self.result["config"].update(warmups=0, repetitions=1)
        with mock.patch.object(attributed, "_history", side_effect=AssertionError("Source decoding was reached")), \
                self.assertRaisesRegex(attributed.AttributionError, "scope admission"):
            self.validate()

    def test_history_hash_length_hex_canonicality_and_original_frame_inventory_are_bound(self):
        for change in (lambda p, r: r.update(historyFingerprint="0" * 64),
                       lambda p, r: r.update(encodedHistoryBytes=199),
                       lambda p, r: p["sourceHistory"].update(headerHex=p["sourceHistory"]["headerHex"].upper()),
                       lambda p, r: p["sourceHistory"]["framesHex"].pop(),
                       lambda p, r: p["sourceHistory"]["framesHex"].append(p["sourceHistory"]["framesHex"][0]),
                       lambda p, r: p["sourceHistory"]["framesHex"].__setitem__(0, "0"),
                       lambda p, r: p["sourceHistory"].update(extra=1)):
            self.rejects(change)

    def test_resigned_source_sequence_request_entity_seed_and_finite_window_errors_are_refused(self):
        for images in ([frame(1), frame(3)], [frame(1), frame(2, request="r-1")],
                       [frame(1), frame(2, entity="e-1")], [frame(1, entity="e-00"), frame(2)],
                       [frame(1, kind=2), frame(2)], [frame(1, start=None, end=2), frame(2)],
                       [frame(1, start=1, end=2), frame(2)], [frame(1), frame(2, start=1, end=3)],
                       [frame(1), frame(2, start=3, end=4)], [frame(1), frame(2, start=0, end=5)],
                       [frame(1), frame(2, start=-1, end=1)]):
            self.rejects(lambda p, r, images=images: self.rewrite_source(p, r, images))
        self.rejects(lambda p, r: self.rewrite_source(p, r, source_header=header("db")))

    def test_rehashed_corrupt_and_truncated_original_bytes_still_fail_independent_crc_checks(self):
        frame_image = bytes.fromhex(self.payload["sourceHistory"]["framesHex"][0])
        for image in (frame_image[:-1], bytes([frame_image[0] ^ 1]) + frame_image[1:], frame_image + b"\0"):
            self.rejects(lambda p, r, image=image: self.rewrite_source(p, r, [image, frame(2)]))

    def test_source_put_retract_and_entity_counts_are_compared_to_workload_metadata(self):
        for change in (lambda p, r: r["workload"].update(putOperations=1),
                       lambda p, r: r["workload"].update(retractOperations=1),
                       lambda p, r: r["workload"].update(commits=3),
                       lambda p, r: r["workload"].update(attempts=2),
                       lambda p, r: r["workload"].update(retries=0),
                       lambda p, r: r["workload"]["entityTouches"].update({"e-0": True}),
                       lambda p, r: r["workload"]["entityTouches"].update({"e-1": 1})):
            self.rejects(change)

    def test_full_scope_order_rejects_missing_duplicate_extra_and_reordered_auxiliary_scopes(self):
        for change in (lambda p, r: p["scopes"].pop(), lambda p, r: p["scopes"].append(copy.deepcopy(p["scopes"][-1])),
                       lambda p, r: p["scopes"].__setitem__(0, copy.deepcopy(p["scopes"][1])),
                       lambda p, r: p["scopes"].__setitem__(slice(2, 4), list(reversed(p["scopes"][2:4]))),
                       lambda p, r: p["scopes"][2].update(category="VALIDATION")):
            self.rejects(change)

    def test_scope_ordinals_iterations_phase_kind_and_base_samples_are_all_bound(self):
        for change in (lambda p, r: p["scopes"][0].update(ordinal=True),
                       lambda p, r: p["scopes"][0].update(iteration=0),
                       lambda p, r: p["scopes"][2].update(iteration=-1),
                       lambda p, r: p["scopes"][2].update(phase="LOAD"),
                       lambda p, r: next(s for s in p["scopes"] if s["kind"] == "GET").update(kind="SCAN"),
                       lambda p, r: r["iterations"][0]["samples"].reverse(),
                       lambda p, r: r["iterations"][0].update(warmup=0),
                       lambda p, r: r["iterations"][0].update(sourceSegments=3)):
            self.rejects(change)

    def test_operation_and_observation_failures_unknown_cleanup_and_missing_snapshot_never_admit(self):
        for values in ({"callReturned": False}, {"callReturned": 1}, {"operationFailureType": "java.io.IOException"},
                       {"observationFailureType": "java.lang.IllegalStateException"}, {"cleanupOutsideCapture": True},
                       {"cleanupOutsideCapture": 0}, {"counters": None}, {"extra": 1}):
            self.rejects(lambda p, r, values=values: p["scopes"][0].update(values))

    def test_snapshot_schema_shape_boolean_negative_and_unknown_flags_are_refused(self):
        for change in (lambda c: c.update(schemaVersion=True), lambda c: c.update(schemaVersion=2),
                       lambda c: c.update(overflow=True), lambda c: c.update(invalidResult=True),
                       lambda c: c.update(overflow=0), lambda c: c.update(extra=1),
                       lambda c: c["counts"].pop(), lambda c: c["counts"][0].pop(),
                       lambda c: c["counts"][0].__setitem__(0, -1), lambda c: c["counts"][0].__setitem__(0, True),
                       lambda c: c["counts"][0].__setitem__(0, 1.0), lambda c: c["counts"][0].__setitem__(0, 1 << 63)):
            self.rejects(lambda p, r, change=change: change(p["scopes"][0]["counters"]))
        self.rejects(lambda p, r: p["scopes"][0].update(counters=snapshot(reads=1, role="UNKNOWN")))

    def test_attempt_return_failure_and_positive_byte_invariants_are_independently_recomputed(self):
        for row in ([1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
                    [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0],
                    [1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
                    [1, 1, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0],
                    [0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0],
                    [0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0],
                    [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1]):
            self.rejects(lambda p, r, row=row: p["scopes"][0]["counters"]["counts"].__setitem__(0, row))

    def test_short_completions_zero_and_eof_are_distinct_from_bytes(self):
        counters = snapshot(reads=7, eof=1)
        row = counters["counts"][0]
        row[0] = row[1] = 4
        row[3] = 1  # Two positive short completions, one zero and one EOF.
        self.operation("JOURNAL_REOPEN")["counters"] = counters
        group = next(g for g in self.validate()["iterations"][0]["operationGroups"] if g["kind"] == "JOURNAL_REOPEN")
        self.assertEqual(7, group["totalReadBytes"])
        self.assertEqual(4, group["counters"]["JOURNAL"]["READ_RETURNS"])
        self.assertEqual(1, group["counters"]["JOURNAL"]["READ_ZERO"])
        self.assertEqual(1, group["counters"]["JOURNAL"]["READ_EOF"])

    def test_excluded_external_scopes_cannot_smuggle_adapter_bytes(self):
        for category in ("EXTERNAL_HASH", "FILE_INVENTORY"):
            self.rejects(lambda p, r, category=category: next(s for s in p["scopes"] if s["category"] == category)
                         .update(counters=snapshot(reads=1)))

    def test_per_snapshot_role_sum_and_cross_scope_aggregate_overflow_are_refused(self):
        self.rejects(lambda p, r: p["scopes"][0].update(counters=combine(snapshot(reads=attributed.LONG_MAX),
                     snapshot(reads=1, role="SEGMENT"))))
        self.payload["scopes"][0]["counters"] = snapshot(reads=attributed.LONG_MAX)
        with self.assertRaisesRegex(attributed.AttributionError, "aggregate counter"):
            self.validate()

    def test_result_independent_copies_cannot_be_changed_through_caller_input_mutations(self):
        accepted = self.validate()
        original = copy.deepcopy(accepted)
        self.payload["denominators"]["headerBytes"] = 0
        self.operation("COMMIT")["counters"]["counts"][0][8] = 0
        self.result["iterations"][0]["warmup"] = True
        self.assertEqual(original, accepted)

    def test_nonfinite_unsupported_and_excessive_structure_is_refused_before_validation(self):
        self.rejects(lambda p, r: p.update(extra=math.nan))
        self.rejects(lambda p, r: p.update(extra=object()))
        nested = None
        for _ in range(34):
            nested = [nested]
        self.rejects(lambda p, r: p.update(extra=nested))
        with mock.patch.object(attributed, "MAX_NODES", 10), self.assertRaises(attributed.AttributionError):
            self.validate()


if __name__ == "__main__":
    unittest.main()
