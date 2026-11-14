"""Independent literal recovery images; no Java encoder or validator helper creates inputs."""

import copy
import hashlib
import importlib.util
from pathlib import Path
import struct
import sys
import unittest
from unittest import mock


_MODULE_PATH = Path(__file__).resolve().parents[1] / "recovery_images.py"
_SPEC = importlib.util.spec_from_file_location("recovery_images_independent_test_target", _MODULE_PATH)
images = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = images
_SPEC.loader.exec_module(images)

SHAPES = ("JOURNAL_ONLY", "GENESIS", "CHECKPOINT_TAIL", "COMPACTED_CHECKPOINT_TAIL")
LOCK = ".meridiandb-checkpoints.lock"
MAX_RAW_BYTES = 2 * 1024 * 1024
SUMMARY_KEYS = {"schemaVersion", "scope", "lineage", "commits", "headerBytes", "encodedHistoryBytes",
                "historyFingerprint", "checkpointSequence", "checkpointPrefixBytes", "checkpointPrefixSha256",
                "continuationFrameBytes", "continuationFrameSha256", "continuedJournalBytes", "continuedJournalSha256",
                "rawFixtureBytes", "decodedRecordWork", "fixtures"}
FIXTURE_KEYS = {"shape", "checkpointSequence", "prefixBytes", "prefixSha256", "selectedManifestBytes",
                "selectedSegmentBytes", "selectedSegments", "initialFileSha256"}

GOLDEN_HEADER = bytes.fromhex("4d52444e4a4e4c00000000010000000200640062a9cf7c87")
GOLDEN_FRAME = bytes.fromhex("4d54584e0000000100000022182a4007"
                           "00000000000000010000000100720000000101000000010065000000000000000007"
                           "538cd524")


def crc32c(data):
    """Reflected bit-at-a-time Castagnoli calculation, checked against published literals."""
    value = 0xFFFFFFFF
    for byte in data:
        value ^= byte
        for _ in range(8):
            value = (value >> 1) ^ (0x82F63B78 if value & 1 else 0)
    return value ^ 0xFFFFFFFF


def sha(data):
    return hashlib.sha256(data).hexdigest()


def text(value):
    encoded = value.encode("utf-16-be", "surrogatepass")
    return struct.pack(">i", len(encoded) // 2) + encoded


def header(lineage="workload-v1", version=1):
    prefix = b"MRDNJNL\0" + struct.pack(">i", version) + text(lineage)
    return prefix + struct.pack(">I", crc32c(prefix))


def frame(sequence, request=None, entity="e-0", start=0, end=1, value=7, kind=1,
          flags=None, count=1, trailing=b"", version=1):
    if flags is None:
        flags = int(start is not None) + 2 * int(end is not None)
    operation = bytes([kind]) + text(entity) + bytes([flags])
    if flags & 1:
        operation += struct.pack(">q", start)
    if flags & 2:
        operation += struct.pack(">q", end)
    if kind == 1:
        operation += struct.pack(">q", value)
    payload = struct.pack(">q", sequence) + text(request if request is not None else f"r-{sequence}")
    payload += struct.pack(">i", count) + operation + trailing
    prefix = b"MTXN" + struct.pack(">ii", version, len(payload))
    return prefix + struct.pack(">I", crc32c(prefix)) + payload + struct.pack(">I", crc32c(payload))


def segment(frames, lineage=None, first=None, last=None, count=None, body_length=None, version=1):
    lineage = header() if lineage is None else lineage
    body = b"".join(frames)
    first = struct.unpack_from(">q", frames[0], 16)[0] if first is None else first
    last = struct.unpack_from(">q", frames[-1], 16)[0] if last is None else last
    prefix = b"MRDNSEG\0" + struct.pack(">ii", version, len(lineage)) + lineage
    prefix += struct.pack(">qqiq", first, last, len(frames) if count is None else count,
                          len(body) if body_length is None else body_length)
    encoded = prefix + struct.pack(">I", crc32c(prefix)) + body
    return encoded + hashlib.sha256(encoded).digest()


def reference(encoded, first=None, last=None, length=None, identifier=None):
    nested = struct.unpack_from(">i", encoded, 12)[0] if first is None or last is None else 0
    return (bytes.fromhex(sha(encoded)) if identifier is None else identifier,
            len(encoded) if length is None else length,
            struct.unpack_from(">q", encoded, 16 + nested)[0] if first is None else first,
            struct.unpack_from(">q", encoded, 24 + nested)[0] if last is None else last)


def manifest(generation, cutoff, prefix, references, lineage=None, prefix_length=None, prefix_hash=None,
             count=None, version=1):
    lineage = header() if lineage is None else lineage
    encoded = b"MRDNMAN\0" + struct.pack(">ii", version, len(lineage)) + lineage
    encoded += struct.pack(">qqq", generation, cutoff, len(prefix) if prefix_length is None else prefix_length)
    encoded += hashlib.sha256(prefix).digest() if prefix_hash is None else prefix_hash
    encoded += struct.pack(">i", len(references) if count is None else count)
    encoded += struct.pack(">I", crc32c(encoded))
    for identifier, length, first, last in references:
        encoded += identifier + struct.pack(">qqq", length, first, last)
    return encoded + hashlib.sha256(encoded).digest()


def segment_name(encoded):
    return sha(encoded) + ".segment"


def manifest_name(encoded, generation=None):
    if generation is None:
        nested = struct.unpack_from(">i", encoded, 12)[0]
        generation = struct.unpack_from(">q", encoded, 16 + nested)[0]
    return f"checkpoint-{generation:019d}-{sha(encoded)}.manifest"


def fixture(commits=4, entities=1, width=1, queries=5, frames=None, source_chunk=None,
            source_segments=None, source_manifest=None, target_segment=None, target_manifest=None,
            lineage=None, continuation=None):
    """Four literal layouts, including every old object and actual one-segment reuse."""
    lineage = header() if lineage is None else lineage
    if frames is None:
        frames = [frame(sequence, entity=f"e-{sequence - 1}" if sequence <= entities else "e-0",
                        start=0 if sequence <= entities else (sequence - entities) * width,
                        end=width if sequence <= entities else (sequence - entities + 1) * width,
                        value=sequence) for sequence in range(1, commits + 1)]
    cutoff = commits // 2
    chunk = min(16, max(1, cutoff // 2)) if source_chunk is None else source_chunk
    if source_segments is None:
        source_segments = [segment(frames[first:min(first + chunk, cutoff)], lineage=lineage)
                           for first in range(0, cutoff, chunk)]
    prefix = lineage + b"".join(frames[:cutoff])
    if source_manifest is None:
        source_manifest = manifest(1, cutoff, prefix, [reference(value) for value in source_segments], lineage=lineage)
    if target_segment is None:
        target_segment = segment(frames[:cutoff], lineage=lineage)
    if target_manifest is None:
        target_manifest = manifest(2, cutoff, prefix, [reference(target_segment)], lineage=lineage)
    genesis = manifest(1, 0, lineage, [], lineage=lineage)
    source_files = {segment_name(value): value.hex() for value in source_segments}
    source_files[manifest_name(source_manifest, 1)] = source_manifest.hex()
    compacted_files = dict(source_files)
    compacted_files[segment_name(target_segment)] = target_segment.hex()
    compacted_files[manifest_name(target_manifest, 2)] = target_manifest.hex()
    layouts = [
        {"shape": SHAPES[0], "files": {LOCK: ""}},
        {"shape": SHAPES[1], "files": {LOCK: "", manifest_name(genesis): genesis.hex()}},
        {"shape": SHAPES[2], "files": {LOCK: "", **source_files}},
        {"shape": SHAPES[3], "files": {LOCK: "", **compacted_files}},
    ]
    continuation = frame(commits + 1, "recovery-continuation-0", "recovery-continuation-entity-0",
                         start=None, end=None, value=17) if continuation is None else continuation
    spec = {"seed": 7, "entities": entities, "commits": commits, "queries": queries, "intervalWidth": width,
            "correctionPercent": 0, "retractionPercent": 0, "repeatPercent": 0, "retryPercent": 0,
            "hotEntityPercent": 0, "historicalReadPercent": 0}
    source = {"headerHex": lineage.hex(), "framesHex": [value.hex() for value in frames]}
    config = {"warmups": 0, "repetitions": 1}
    limits = {"maxObjectBytes": 64 * 1024 * 1024, "maxJournalBytes": 64 * 1024 * 1024, "maxRecordWork": 262144}
    return [source, continuation.hex(), layouts, spec, config, limits]


def all_images(case):
    for layout in case[2]:
        for name, encoded in layout["files"].items():
            if encoded:
                yield layout["shape"], name, bytes.fromhex(encoded)


def selected(case, shape, suffix):
    files = case[2][SHAPES.index(shape)]["files"]
    values = [(name, bytes.fromhex(value)) for name, value in files.items() if name.endswith(suffix)]
    if suffix == ".manifest":
        return max(values, key=lambda value: int(value[0].split("-")[1]))
    return values[0]


def expected_fixture(case, shape):
    source, continuation, layouts, spec, config, limits = case
    lineage = bytes.fromhex(source["headerHex"])
    frames = [bytes.fromhex(value) for value in source["framesHex"]]
    original = lineage + b"".join(frames)
    files = layouts[SHAPES.index(shape)]["files"]
    hashes = {name: sha(bytes.fromhex(value)) for name, value in files.items()}
    hashes["transactions.journal"] = sha(original)
    if shape == "JOURNAL_ONLY":
        return {"shape": shape, "checkpointSequence": None, "prefixBytes": 0, "prefixSha256": None,
                "selectedManifestBytes": 0, "selectedSegmentBytes": 0, "selectedSegments": 0,
                "initialFileSha256": hashes}
    chosen = selected(case, shape, ".manifest")[1]
    nested = struct.unpack_from(">i", chosen, 12)[0]
    count = struct.unpack_from(">i", chosen, 72 + nested)[0]
    lengths = [struct.unpack_from(">q", chosen, 80 + nested + index * 56 + 32)[0] for index in range(count)]
    cutoff = 0 if shape == "GENESIS" else spec["commits"] // 2
    prefix = lineage + b"".join(frames[:cutoff])
    return {"shape": shape, "checkpointSequence": cutoff, "prefixBytes": len(prefix), "prefixSha256": sha(prefix),
            "selectedManifestBytes": len(chosen), "selectedSegmentBytes": sum(lengths), "selectedSegments": count,
            "initialFileSha256": hashes}


def case_from_worker(envelope):
    """Bridge for a root-owned real Java capture; outer-envelope admission belongs to its own tests."""
    request = envelope["request"]
    return [envelope["sourceHistory"], envelope["continuationFrameHex"], envelope["fixtureImages"],
            request["spec"], request["config"], request["limits"]]


class RecoveryImagesTests(unittest.TestCase):
    def reject(self, case):
        before = copy.deepcopy(case)
        with self.assertRaises(images.RecoveryImageError):
            images.validate_images(*case)
        self.assertEqual(before, case, "Rejected validation must preserve every caller-owned input")

    def test_builder_matches_documented_crc_header_frame_and_full_object_goldens(self):
        self.assertEqual(0xE3069283, crc32c(b"123456789"))
        self.assertEqual(GOLDEN_HEADER, header("db"))
        record = frame(1, "r", "e", None, None, 7)
        self.assertEqual(GOLDEN_FRAME, record)
        encoded_segment = segment([record], lineage=GOLDEN_HEADER)
        self.assertEqual(158, len(encoded_segment))
        self.assertEqual("af1a2406823229a1e45d59ada79103d9b9f834e1c0171ef6a3e83478ba0e28fa", encoded_segment[-32:].hex())
        self.assertEqual("d313d28a39ce9900e61fdead724735a02e09ab194861553910fe941d64b26ace", sha(encoded_segment))
        encoded_manifest = manifest(7, 1, GOLDEN_HEADER + record, [reference(encoded_segment)], lineage=GOLDEN_HEADER)
        self.assertEqual(192, len(encoded_manifest))
        self.assertEqual("fc0bccc3589bcab2f55535bf1acf6199dab723cbdbd1526329f511413e9e0dc3", sha(GOLDEN_HEADER + record))
        self.assertEqual("9e3dd8ae82bd0a3238a899500d9d5d3ac917dcc1fdee93484c058358190629d4", encoded_manifest[-32:].hex())
        self.assertEqual("e40b7bf552979c50d92fa672bba19ed93f2716fca847410d5406157f59cfae8e", sha(encoded_manifest))

    def test_literal_four_commit_summary_binds_all_shapes_lengths_hashes_and_fresh_copies(self):
        case = fixture()
        before = copy.deepcopy(case)
        summary = images.validate_images(*case)
        self.assertEqual(SUMMARY_KEYS, set(summary))
        self.assertEqual(1, summary["schemaVersion"])
        self.assertEqual("RECORDED_RECOVERY_IMAGES", summary["scope"])
        self.assertEqual("workload-v1", summary["lineage"])
        self.assertEqual(4, summary["commits"])
        self.assertEqual(42, summary["headerBytes"])
        self.assertEqual(354, summary["encodedHistoryBytes"])
        self.assertEqual(2, summary["checkpointSequence"])
        self.assertEqual(198, summary["checkpointPrefixBytes"])
        self.assertEqual(156, summary["continuationFrameBytes"])
        self.assertEqual(510, summary["continuedJournalBytes"])
        self.assertEqual(9, summary["decodedRecordWork"])
        original = bytes.fromhex(case[0]["headerHex"]) + b"".join(bytes.fromhex(value) for value in case[0]["framesHex"])
        continuation = bytes.fromhex(case[1])
        self.assertEqual(sha(original), summary["historyFingerprint"])
        self.assertEqual(sha(original[:198]), summary["checkpointPrefixSha256"])
        self.assertEqual(sha(continuation), summary["continuationFrameSha256"])
        self.assertEqual(sha(original + continuation), summary["continuedJournalSha256"])
        self.assertEqual(len(original) + sum(len(image) for _, _, image in all_images(case)), summary["rawFixtureBytes"])
        self.assertEqual([expected_fixture(case, shape) for shape in SHAPES], summary["fixtures"])
        for row in summary["fixtures"]:
            self.assertEqual(FIXTURE_KEYS, set(row))
        self.assertEqual(before, case)
        summary["fixtures"][0]["initialFileSha256"].clear()
        summary["fixtures"].clear()
        self.assertEqual(before, case)
        self.assertEqual([expected_fixture(case, shape) for shape in SHAPES], images.validate_images(*case)["fixtures"])

    def test_single_source_segment_is_reused_but_generation_two_and_source_manifest_are_retained(self):
        case = fixture(commits=2)
        summary = images.validate_images(*case)
        self.assertEqual(1, summary["checkpointSequence"])
        self.assertEqual(4, summary["decodedRecordWork"])
        source = summary["fixtures"][2]
        compacted = summary["fixtures"][3]
        self.assertEqual(source["selectedSegmentBytes"], compacted["selectedSegmentBytes"])
        self.assertEqual(1, source["selectedSegments"])
        self.assertEqual(1, compacted["selectedSegments"])
        names = compacted["initialFileSha256"]
        self.assertEqual(1, sum(name.endswith(".segment") for name in names))
        self.assertEqual(2, sum(name.endswith(".manifest") for name in names))
        self.assertEqual([expected_fixture(case, shape) for shape in SHAPES], summary["fixtures"])

    def test_multi_segment_source_and_odd_commit_cutoff_follow_actual_chunk_policy(self):
        for commits, expected_segments in ((6, 3), (7, 3), (8, 2), (9, 2), (10, 3), (67, 3)):
            with self.subTest(commits=commits):
                case = fixture(commits=commits, queries=0)
                summary = images.validate_images(*case)
                self.assertEqual(commits // 2, summary["checkpointSequence"])
                self.assertEqual(expected_segments, summary["fixtures"][2]["selectedSegments"])
                self.assertEqual(1, summary["fixtures"][3]["selectedSegments"])
                self.assertEqual(commits + 1 + 2 * (commits // 2), summary["decodedRecordWork"])
                self.assertEqual([expected_fixture(case, shape) for shape in SHAPES], summary["fixtures"])

    def test_acquisition_limits_accept_exact_required_values_and_reject_each_one_below(self):
        case = fixture()
        case[5] = {"maxObjectBytes": 666, "maxJournalBytes": 198, "maxRecordWork": 4}
        summary = images.validate_images(*case)
        self.assertEqual(2, summary["checkpointSequence"])
        for name in case[5]:
            invalid = copy.deepcopy(case)
            invalid[5][name] -= 1
            with self.subTest(limit=name), mock.patch.object(
                    images, "_decode_header", side_effect=AssertionError("Decoded before acquisition admission")) as decoder:
                self.reject(invalid)
                decoder.assert_not_called()
        for name, limit in (("maxObjectBytes", 64 * 1024 * 1024), ("maxJournalBytes", 64 * 1024 * 1024),
                            ("maxRecordWork", 262144)):
            invalid = fixture(); invalid[5][name] = limit + 1
            self.reject(invalid)

    def test_exact_object_shapes_types_integer_bounds_and_unknown_fields_are_enforced(self):
        for position in (0, 3, 4, 5):
            for field in fixture()[position]:
                for invalid_value in (None, True, False, 1.0, "1"):
                    if position == 0 and isinstance(fixture()[position][field], str) and invalid_value == "1":
                        continue
                    case = fixture(); case[position][field] = invalid_value
                    with self.subTest(position=position, field=field, value=invalid_value):
                        self.reject(case)
            case = fixture(); case[position]["unknown"] = 1
            self.reject(case)
            for field in fixture()[position]:
                case = fixture(); del case[position][field]
                self.reject(case)
        for key, value in (("seed", -(1 << 63) - 1), ("seed", 1 << 63), ("entities", 0), ("entities", 1025),
                           ("commits", 1), ("commits", 4097), ("queries", -1), ("queries", 1025),
                           ("intervalWidth", 0), ("intervalWidth", 17), ("correctionPercent", -1), ("retryPercent", 101)):
            case = fixture(); case[3][key] = value
            self.reject(case)
        for key, value in (("warmups", -1), ("warmups", 3), ("repetitions", 0), ("repetitions", 6)):
            case = fixture(); case[4][key] = value
            self.reject(case)
        case = fixture(); case[3]["correctionPercent"] = 70; case[3]["retractionPercent"] = 31
        self.reject(case)

    def test_fixture_order_duplicates_membership_safe_names_and_empty_control_lock_are_exact(self):
        for mutate in (
                lambda layouts: layouts.reverse(),
                lambda layouts: layouts.pop(),
                lambda layouts: layouts.append(copy.deepcopy(layouts[0])),
                lambda layouts: layouts.__setitem__(1, copy.deepcopy(layouts[0])),
                lambda layouts: layouts[0].__setitem__("unknown", 1),
                lambda layouts: layouts[0].__setitem__("shape", "journal_only"),
                lambda layouts: layouts[0]["files"].pop(LOCK),
                lambda layouts: layouts[0]["files"].__setitem__(LOCK, "00")):
            case = fixture(); mutate(case[2]); self.reject(case)
        for unsafe in ("../escape", "/absolute", "..", ".", "x/y.segment", "x\\y.segment", "transactions.journal",
                       ".meridiandb-readers.lock", "unknown.segment", "unknown.manifest", "A" * 64 + ".segment"):
            case = fixture(); case[2][0]["files"][unsafe] = ""
            self.reject(case)
        case = fixture(); case[2] = tuple(case[2]); self.reject(case)
        case = fixture(); case[2][0]["files"] = []; self.reject(case)

    def test_hex_and_frame_lists_are_bounded_lowercase_even_and_exactly_the_original_count(self):
        for invalid in (None, True, b"00", "", "0", "GG", "AA", "00 ", "00\n"):
            case = fixture(); case[0]["headerHex"] = invalid; self.reject(case)
            case = fixture(); case[1] = invalid; self.reject(case)
            case = fixture(); case[0]["framesHex"][0] = invalid; self.reject(case)
        for invalid in (None, {}, "00", (), ["00"] * 4097):
            case = fixture(); case[0]["framesHex"] = invalid; self.reject(case)
        for count in (0, 3, 5):
            case = fixture(); case[0]["framesHex"] = (case[0]["framesHex"] * 2)[:count]; self.reject(case)

    def test_every_original_header_frame_and_continuation_truncation_is_rejected_without_partial_success(self):
        valid = fixture()
        for part, encoded in (("header", valid[0]["headerHex"]), ("frame", valid[0]["framesHex"][0]),
                              ("continuation", valid[1])):
            data = bytes.fromhex(encoded)
            for end in range(len(data)):
                case = copy.deepcopy(valid)
                if part == "header": case[0]["headerHex"] = data[:end].hex()
                elif part == "frame": case[0]["framesHex"][0] = data[:end].hex()
                else: case[1] = data[:end].hex()
                with self.subTest(part=part, end=end): self.reject(case)

    def test_segment_manifest_truncation_trailing_byte_and_corruption_fail_with_matching_whole_object_names(self):
        valid = fixture()
        lineage = bytes.fromhex(valid[0]["headerHex"])
        frames = [bytes.fromhex(value) for value in valid[0]["framesHex"]]
        second = segment([frames[1]])
        for suffix in (".segment", ".manifest"):
            name, data = selected(valid, "CHECKPOINT_TAIL", suffix)
            for end in range(len(data)):
                malformed = data[:end]
                if suffix == ".manifest":
                    case = fixture(source_manifest=malformed)
                else:
                    refs = [reference(malformed, first=1, last=1), reference(second)]
                    metadata = manifest(1, 2, lineage + b"".join(frames[:2]), refs)
                    case = fixture(source_segments=[malformed, second], source_manifest=metadata)
                with self.subTest(suffix=suffix, end=end): self.reject(case)
            for malformed in (data + b"\0", data[:20] + bytes([data[20] ^ 1]) + data[21:],
                              data[:-1] + bytes([data[-1] ^ 1])):
                if suffix == ".manifest":
                    case = fixture(source_manifest=malformed)
                else:
                    refs = [reference(malformed, first=1, last=1), reference(second)]
                    metadata = manifest(1, 2, lineage + b"".join(frames[:2]), refs)
                    case = fixture(source_segments=[malformed, second], source_manifest=metadata)
                self.reject(case)

    def test_resigned_negative_nested_lengths_and_frame_lengths_reject_before_declared_allocation(self):
        lineage = bytearray(header())
        struct.pack_into(">i", lineage, 12, -1)
        struct.pack_into(">I", lineage, len(lineage) - 4, crc32c(lineage[:-4]))
        self.reject(fixture(lineage=bytes(lineage)))
        for length in (-1, 8 * 1024 * 1024 + 1, (1 << 31) - 1):
            case = fixture()
            encoded = bytearray(bytes.fromhex(case[0]["framesHex"][0]))
            struct.pack_into(">i", encoded, 8, length)
            struct.pack_into(">I", encoded, 12, crc32c(encoded[:12]))
            case[0]["framesHex"][0] = encoded.hex()
            self.reject(case)
        valid = fixture()
        frames = [bytes.fromhex(value) for value in valid[0]["framesHex"]]
        original_header = bytes.fromhex(valid[0]["headerHex"])
        first = segment([frames[0]])
        for length in (-1, 65557, (1 << 31) - 1):
            malformed = bytearray(first)
            struct.pack_into(">i", malformed, 12, length)
            struct.pack_into(">I", malformed, 44 + len(original_header), crc32c(malformed[:44 + len(original_header)]))
            malformed[-32:] = hashlib.sha256(malformed[:-32]).digest()
            malformed = bytes(malformed)
            second = segment([frames[1]])
            refs = [reference(malformed, first=1, last=1), reference(second)]
            self.reject(fixture(source_segments=[malformed, second],
                                source_manifest=manifest(1, 2, original_header + b"".join(frames[:2]), refs)))
            manifest_name_original, encoded = selected(valid, "CHECKPOINT_TAIL", ".manifest")
            malformed_metadata = bytearray(encoded)
            struct.pack_into(">i", malformed_metadata, 12, length)
            struct.pack_into(">I", malformed_metadata, 76 + len(original_header),
                             crc32c(malformed_metadata[:76 + len(original_header)]))
            malformed_metadata[-32:] = hashlib.sha256(malformed_metadata[:-32]).digest()
            self.reject(fixture(source_manifest=bytes(malformed_metadata)))

    def test_original_header_frames_and_continuation_reject_crc_corruption_or_trailing_bytes(self):
        for position in ("header", "frame", "continuation"):
            for mutation in ("crc", "body", "trailing"):
                case = fixture()
                raw = bytes.fromhex(case[0]["headerHex"] if position == "header" else
                                    case[0]["framesHex"][0] if position == "frame" else case[1])
                if mutation == "trailing":
                    malformed = raw + b"\0"
                else:
                    offset = len(raw) - 1 if mutation == "crc" else 20
                    malformed = raw[:offset] + bytes([raw[offset] ^ 1]) + raw[offset + 1:]
                if position == "header": case[0]["headerHex"] = malformed.hex()
                elif position == "frame": case[0]["framesHex"][0] = malformed.hex()
                else: case[1] = malformed.hex()
                with self.subTest(position=position, mutation=mutation): self.reject(case)

    def test_resigned_original_frames_reject_versions_sequences_requests_entities_and_invalid_payloads(self):
        for sequence, request, entity, start, end, kind, flags, count, version in (
                (0, "r-1", "e-0", 0, 1, 1, 3, 1, 1),
                (-1, "r-1", "e-0", 0, 1, 1, 3, 1, 1),
                (2, "r-1", "e-0", 0, 1, 1, 3, 1, 1),
                (1, "r-2", "e-0", 0, 1, 1, 3, 1, 1),
                (1, "r-1", "e-1", 0, 1, 1, 3, 1, 1),
                (1, "r-1", "e-0", 0, 0, 1, 3, 1, 1),
                (1, "r-1", "e-0", None, None, 1, 0, 1, 1),
                (1, "r-1", "e-0", 0, 1, 2, 3, 1, 1),
                (1, "r-1", "e-0", 0, 1, 3, 3, 1, 1),
                (1, "r-1", "e-0", 0, 1, 1, 7, 1, 1),
                (1, "r-1", "e-0", 0, 1, 1, 3, -1, 1),
                (1, "r-1", "e-0", 0, 1, 1, 3, 2, 1),
                (1, "r-1", "e-0", 0, 1, 1, 3, 1, 2)):
            case = fixture()
            case[0]["framesHex"][0] = frame(sequence, request, entity, start, end, kind=kind, flags=flags, count=count, version=version).hex()
            self.reject(case)
        case = fixture(lineage=header("other")); self.reject(case)
        case = fixture(lineage=header(version=2)); self.reject(case)
        case = fixture(); case[0]["framesHex"][3] = frame(4, request="r-3", start=3, end=4).hex(); self.reject(case)
        case = fixture(); case[0]["framesHex"][0] = frame(1, trailing=b"\0").hex(); self.reject(case)

    def test_continuation_requires_the_exact_forced_unbounded_put_identity_sequence_and_value(self):
        for kwargs in ({"sequence": 4}, {"sequence": 6}, {"request": "r-1"},
                       {"request": "recovery-continuation-1"}, {"entity": "e-0"},
                       {"entity": "recovery-continuation-entity-1"}, {"value": 18}, {"kind": 2},
                       {"start": 0}, {"end": 1}, {"count": 2}, {"trailing": b"\0"}):
            arguments = {"sequence": 5, "request": "recovery-continuation-0", "entity": "recovery-continuation-entity-0",
                         "start": None, "end": None, "value": 17}
            arguments.update(kwargs)
            self.reject(fixture(continuation=frame(**arguments)))

    def test_resigned_segment_range_count_length_and_nested_lineage_cannot_be_hidden_by_new_object_id(self):
        valid = fixture()
        frames = [bytes.fromhex(value) for value in valid[0]["framesHex"]]
        for kwargs in ({"first": -1}, {"first": 2}, {"last": 2}, {"count": -1}, {"count": 2},
                       {"body_length": -1}, {"body_length": (1 << 63) - 1}, {"lineage": header("other")}, {"version": 2}):
            malformed = segment([frames[0]], **kwargs)
            source = [malformed, segment([frames[1]])]
            # Both the CRC/footer and content-ID/reference are recomputed, leaving the semantic defect exposed.
            self.reject(fixture(source_segments=source))
        valid_source = [segment([frames[0]]), segment([frames[1]])]
        self.reject(fixture(source_segments=list(reversed(valid_source))))
        self.reject(fixture(source_segments=[segment(frames[:2])], source_chunk=2))

    def test_resigned_manifest_generation_prefix_reference_hash_length_range_and_coverage_are_checked(self):
        valid = fixture()
        lineage = bytes.fromhex(valid[0]["headerHex"])
        frames = [bytes.fromhex(value) for value in valid[0]["framesHex"]]
        prefix = lineage + b"".join(frames[:2])
        segments = [segment([value]) for value in frames[:2]]
        refs = [reference(value) for value in segments]
        for kwargs in ({"generation": -1}, {"generation": 0}, {"generation": 2}, {"cutoff": -1}, {"cutoff": 1},
                       {"prefix_length": -1}, {"prefix_length": len(prefix) - 1}, {"prefix_hash": b"\0" * 32},
                       {"count": -1}, {"count": 4097}, {"version": 2}, {"lineage": header("other")}):
            arguments = {"generation": 1, "cutoff": 2, "prefix": prefix, "references": refs}
            arguments.update(kwargs)
            self.reject(fixture(source_segments=segments, source_manifest=manifest(**arguments)))
        wrong_refs = [
            [reference(segments[0], identifier=b"\0" * 32), refs[1]],
            [reference(segments[0], length=-1), refs[1]],
            [reference(segments[0], length=len(segments[0]) + 1), refs[1]],
            [reference(segments[0], first=0), refs[1]],
            [reference(segments[0], last=2), refs[1]],
            [refs[0], refs[0]], list(reversed(refs)), [],
        ]
        for references in wrong_refs:
            self.reject(fixture(source_segments=segments, source_manifest=manifest(1, 2, prefix, references)))

    def test_compacted_target_generation_retained_sources_and_cross_shape_identity_are_required(self):
        valid = fixture()
        for source_shape in ("CHECKPOINT_TAIL", "COMPACTED_CHECKPOINT_TAIL"):
            for name in list(valid[2][SHAPES.index(source_shape)]["files"]):
                if name == LOCK:
                    continue
                case = copy.deepcopy(valid)
                del case[2][SHAPES.index(source_shape)]["files"][name]
                with self.subTest(shape=source_shape, omitted=name): self.reject(case)
        case = fixture(); case[2][3]["files"] = copy.deepcopy(case[2][2]["files"]); self.reject(case)
        lineage = bytes.fromhex(valid[0]["headerHex"])
        frames = [bytes.fromhex(value) for value in valid[0]["framesHex"]]
        prefix = lineage + b"".join(frames[:2])
        packed = segment(frames[:2])
        self.reject(fixture(target_manifest=manifest(3, 2, prefix, [reference(packed)])))
        changed = [frame(1, value=99), frames[1]]
        wrong_target = segment(changed)
        self.reject(fixture(target_segment=wrong_target,
                            target_manifest=manifest(2, 2, lineage + b"".join(changed), [reference(wrong_target)])))
        case = fixture()
        source_name, encoded = selected(case, "CHECKPOINT_TAIL", ".segment")
        files = case[2][2]["files"]; del files[source_name]; files[encoded[-32:].hex() + ".segment"] = encoded.hex()
        self.reject(case)

    def test_complete_preflight_rejects_oversized_aggregate_hex_counts_and_work_before_any_header_decode(self):
        over_raw = fixture()
        name, _ = selected(over_raw, "COMPACTED_CHECKPOINT_TAIL", ".segment")
        over_raw[2][3]["files"][name] = "00" * MAX_RAW_BYTES
        repeated_images = fixture()
        for layout in repeated_images[2][2:]:
            for name in list(layout["files"]):
                if name.endswith(".segment"):
                    layout["files"][name] = "00" * (MAX_RAW_BYTES // 3)
        over_continuation = fixture(); over_continuation[1] = "00" * 4097
        over_count = fixture(); over_count[0]["framesHex"] = [over_count[0]["framesHex"][0]] * 4097
        bad_last_hex = fixture()
        last_name = next(name for name in bad_last_hex[2][3]["files"] if name.endswith(".manifest"))
        bad_last_hex[2][3]["files"][last_name] = "0g"
        over_validation = fixture(commits=100, queries=993)
        over_reference = fixture()
        over_reference[3].update(commits=4001, queries=0)
        over_reference[0]["framesHex"] = [over_reference[0]["framesHex"][0]] * 4001
        bad_last_limit = fixture(); bad_last_limit[5]["maxRecordWork"] = True
        for name, case in (("aggregate", over_raw), ("duplicate image occurrences", repeated_images),
                           ("continuation", over_continuation), ("frame count", over_count),
                           ("late malformed hex", bad_last_hex), ("validation work", over_validation),
                           ("reference work", over_reference), ("late typed field", bad_last_limit)):
            with self.subTest(case=name), mock.patch.object(images, "_decode_header", side_effect=AssertionError("Decoded before complete admission")) as decoder:
                self.reject(case)
                decoder.assert_not_called()

    def test_conservative_validation_budget_accepts_exact_forty_thousand_and_keeps_repetitions_separate(self):
        case = fixture(commits=100, queries=992)
        self.assertEqual(40000, 16 * (2 * (2 * 100 + 992) + 100 + 16))
        summary = images.validate_images(*case)
        self.assertEqual(100, summary["commits"])
        self.assertEqual(201, summary["decodedRecordWork"])
        case = fixture(); case[4] = {"warmups": 2, "repetitions": 5}
        repeated = images.validate_images(*case)
        self.assertEqual(9, repeated["decodedRecordWork"], "Image validation decodes unique images, not timed matrix repetitions")
        self.assertEqual([expected_fixture(case, shape) for shape in SHAPES], repeated["fixtures"])

    def test_decoded_record_budget_admits_exact_unique_work_and_refuses_one_below_before_decode(self):
        case = fixture()
        with mock.patch.object(images, "MAX_DECODE_RECORDS", 9):
            self.assertEqual(9, images.validate_images(*case)["decodedRecordWork"])
        with mock.patch.object(images, "MAX_DECODE_RECORDS", 8), mock.patch.object(
                images, "_decode_header", side_effect=AssertionError("Decoded before unique-work admission")) as decoder:
            self.reject(case)
            decoder.assert_not_called()


if __name__ == "__main__":
    unittest.main()
