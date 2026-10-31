// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

class ObjectCheckpointHeadVerifierTest {
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final UUID FIRST_PUBLICATION = new UUID(0, 3);
    private static final UUID SECOND_PUBLICATION = new UUID(0, 4);
    private static final DatabaseId DATABASE = new DatabaseId("db");
    private static final ObjectId LINEAGE = new ObjectId("2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c");
    private static final ObjectId ZERO = new ObjectId("0".repeat(64));

    @Test void canonicalLineageHashMatchesTheExistingJournalVector() {
        assertEquals(LINEAGE, ObjectCheckpointHeadVerifier.lineageId(DATABASE));
        var head = first(fixture(DATABASE, 17, List.of()));
        assertDoesNotThrow(() -> ObjectCheckpointHeadVerifier.requireBinding(head, NAMESPACE, PUBLISHER, LINEAGE));
    }

    @Test void maximumAndUnpairedUtf16LineageAreNotNarrowedToHeadTextLimits() {
        var large = new DatabaseId("x".repeat(JournalCodec.MAX_ID_CODE_UNITS - 1) + "\ud800");
        var fixture = fixture(large, 23, List.of());
        var head = first(fixture);
        assertEquals(65_556, JournalCodec.encodeHeader(large).length);
        assertEquals(256, ObjectCheckpointHeadCodec.encode(head).length);
        assertEquals(large, ObjectCheckpointHeadVerifier.bindManifest(head, fixture.image()).manifest().databaseId());
        assertNotEquals(ObjectCheckpointHeadVerifier.lineageId(new DatabaseId("\ud800")),
                ObjectCheckpointHeadVerifier.lineageId(new DatabaseId("\ufffd")));
        assertNotEquals(ObjectCheckpointHeadVerifier.lineageId(new DatabaseId("\ud800")),
                ObjectCheckpointHeadVerifier.lineageId(new DatabaseId("\ud801")));
    }

    @Test void wrongIndependentNamespacePublisherOrLineageIsRejected() {
        var head = first(fixture(DATABASE, 17, List.of()));
        invalidBinding(() -> ObjectCheckpointHeadVerifier.requireBinding(head, SECOND_PUBLICATION, PUBLISHER, LINEAGE));
        invalidBinding(() -> ObjectCheckpointHeadVerifier.requireBinding(head, NAMESPACE, SECOND_PUBLICATION, LINEAGE));
        invalidBinding(() -> ObjectCheckpointHeadVerifier.requireBinding(head, NAMESPACE, PUBLISHER, ZERO));
    }

    @Test void manifestGenerationIsNotRemoteRevisionOrTemporalCutoff() {
        var fixture = fixture(DATABASE, 17, List.of(record(1, 100), record(2, 120)));
        var head = first(fixture);
        assertEquals(1, head.revision());
        var bound = ObjectCheckpointHeadVerifier.bindManifest(head, fixture.image());
        assertEquals(17, bound.manifest().generation());
        assertEquals(2, bound.manifest().journalPrefix().sequence());
        assertNull(head.predecessor());
    }

    @Test void firstRemoteSelectionMayCheckpointANonemptyExistingHistory() {
        var fixture = fixture(DATABASE, 7, List.of(record(1, 100), record(2, 120)));
        var head = first(fixture);
        assertEquals(1, head.revision());
        assertEquals(2, head.journalPrefix().sequence());
        assertDoesNotThrow(() -> ObjectCheckpointHeadVerifier.bindManifest(head, fixture.image()));
    }

    @Test void validSuccessorBindsTheEntirePreviousImage() {
        var first = first(fixture(DATABASE, 17, List.of()));
        var next = successor(first, fixture(DATABASE, 7, List.of(record(1, 100))));
        ObjectCheckpointHeadVerifier.requireSuccessor(first, next);
        assertEquals(2, next.revision());
        assertEquals(ObjectCheckpointHeadCodec.reference(first), next.predecessor());
        assertNotEquals(ObjectCheckpointHeadCodec.reference(first), ObjectCheckpointHeadCodec.reference(next));
    }

    @Test void unchangedCutoffMaySelectAnotherPhysicalGenerationOrIdenticalManifest() {
        var original = fixture(DATABASE, 17, List.of(record(1, 100)));
        var first = first(original);
        var same = successor(first, original);
        assertDoesNotThrow(() -> ObjectCheckpointHeadVerifier.requireSuccessor(first, same));
        var repacked = fixture(DATABASE, 18, List.of(record(1, 100)));
        var replacement = successor(first, repacked);
        assertEquals(first.journalPrefix(), replacement.journalPrefix());
        assertNotEquals(first.manifest(), replacement.manifest());
        assertDoesNotThrow(() -> ObjectCheckpointHeadVerifier.requireSuccessor(first, replacement));
    }

    @Test void staleSkippedAndOverflowedRevisionsRefuseTransition() {
        var fixture = fixture(DATABASE, 1, List.of());
        var first = first(fixture);
        invalidTransition(() -> ObjectCheckpointHeadVerifier.requireSuccessor(first, first));
        var skipped = candidate(NAMESPACE, PUBLISHER, SECOND_PUBLICATION, LINEAGE, 3,
                first.manifest(), first.journalPrefix(), ObjectCheckpointHeadCodec.reference(first));
        invalidTransition(() -> ObjectCheckpointHeadVerifier.requireSuccessor(first, skipped));
        var last = candidate(NAMESPACE, PUBLISHER, FIRST_PUBLICATION, LINEAGE, Long.MAX_VALUE,
                first.manifest(), first.journalPrefix(), new ObjectReference(ZERO, 256));
        invalidTransition(() -> ObjectCheckpointHeadVerifier.requireSuccessor(last, first));
    }

    @Test void changedBindingsAndReusedAdjacentPublicationIdentityRefuseTransition() {
        var first = first(fixture(DATABASE, 1, List.of(record(1, 100))));
        var previous = ObjectCheckpointHeadCodec.reference(first);
        for (var changed : List.of(
                candidate(SECOND_PUBLICATION, PUBLISHER, SECOND_PUBLICATION, LINEAGE, 2, first.manifest(), first.journalPrefix(), previous),
                candidate(NAMESPACE, SECOND_PUBLICATION, SECOND_PUBLICATION, LINEAGE, 2, first.manifest(), first.journalPrefix(), previous),
                candidate(NAMESPACE, PUBLISHER, SECOND_PUBLICATION, ZERO, 2, first.manifest(), first.journalPrefix(), previous),
                candidate(NAMESPACE, PUBLISHER, FIRST_PUBLICATION, LINEAGE, 2, first.manifest(), first.journalPrefix(), previous))) {
            invalidTransition(() -> ObjectCheckpointHeadVerifier.requireSuccessor(first, changed));
        }
    }

    @Test void predecessorIdIsNeitherAManifestIdNorAnIntegrityFooter() {
        var first = first(fixture(DATABASE, 1, List.of()));
        byte[] image = ObjectCheckpointHeadCodec.encode(first);
        var footerId = new ObjectId(java.util.HexFormat.of().formatHex(Arrays.copyOfRange(image, 224, 256)));
        for (var wrong : List.of(ZERO, first.manifest().id(), footerId)) {
            var next = candidate(NAMESPACE, PUBLISHER, SECOND_PUBLICATION, LINEAGE, 2,
                    first.manifest(), first.journalPrefix(), new ObjectReference(wrong, 256));
            invalidTransition(() -> ObjectCheckpointHeadVerifier.requireSuccessor(first, next));
        }
    }

    @Test void regressingOrContradictorySameCutoffCoordinatesRefuseTransition() {
        var first = first(fixture(DATABASE, 1, List.of(record(1, 100))));
        var prefix = first.journalPrefix();
        for (var wrong : List.of(
                new CheckpointManifest.JournalPrefix(0, 24, LINEAGE.value()),
                new CheckpointManifest.JournalPrefix(prefix.sequence(), prefix.byteLength() + 1, prefix.sha256()),
                new CheckpointManifest.JournalPrefix(prefix.sequence(), prefix.byteLength(), ZERO.value()),
                new CheckpointManifest.JournalPrefix(prefix.sequence() + 1, prefix.byteLength(), ZERO.value()),
                new CheckpointManifest.JournalPrefix(prefix.sequence() + 1, prefix.byteLength() - 1, ZERO.value()))) {
            var next = candidate(NAMESPACE, PUBLISHER, SECOND_PUBLICATION, LINEAGE, 2,
                    first.manifest(), wrong, ObjectCheckpointHeadCodec.reference(first));
            invalidTransition(() -> ObjectCheckpointHeadVerifier.requireSuccessor(first, next));
        }
    }

    @Test void encodedManifestMustHaveExactIndependentLengthAndWholeId() {
        var fixture = fixture(DATABASE, 17, List.of());
        var first = first(fixture);
        var wrongId = candidate(NAMESPACE, PUBLISHER, FIRST_PUBLICATION, LINEAGE, 1,
                new ObjectReference(ZERO, fixture.image().length), first.journalPrefix(), null);
        var failure = invalidManifest(() -> ObjectCheckpointHeadVerifier.bindManifest(wrongId, fixture.image()));
        assertEquals(ManifestFormatException.Code.IDENTIFIER_MISMATCH, ((ManifestFormatException) failure.getCause()).code());
        var wrongLength = candidate(NAMESPACE, PUBLISHER, FIRST_PUBLICATION, LINEAGE, 1,
                new ObjectReference(first.manifest().id(), fixture.image().length + 1), first.journalPrefix(), null);
        invalidManifest(() -> ObjectCheckpointHeadVerifier.bindManifest(wrongLength, fixture.image()));
    }

    @Test void matchingContentHashDoesNotMakeMalformedBytesAValidManifest() {
        var fixture = fixture(DATABASE, 1, List.of());
        var first = first(fixture);
        byte[] bad = fixture.image().clone();
        bad[bad.length - 1] ^= 1;
        var corrupt = candidate(NAMESPACE, PUBLISHER, FIRST_PUBLICATION, LINEAGE, 1,
                ObjectReference.forBytes(bad), first.journalPrefix(), null);
        var failure = invalidManifest(() -> ObjectCheckpointHeadVerifier.bindManifest(corrupt, bad));
        assertEquals(ManifestFormatException.Code.CORRUPT, ((ManifestFormatException) failure.getCause()).code());
        byte[] tiny = {0};
        var declaration = candidate(NAMESPACE, PUBLISHER, FIRST_PUBLICATION, LINEAGE, 1,
                ObjectReference.forBytes(tiny), first.journalPrefix(), null);
        invalidManifest(() -> ObjectCheckpointHeadVerifier.bindManifest(declaration, tiny));
    }

    @Test void exactManifestIdentityStillRequiresLineageAndPrefixBinding() {
        var fixture = fixture(DATABASE, 1, List.of(record(1, 100)));
        var first = first(fixture);
        var wrongLineage = candidate(NAMESPACE, PUBLISHER, FIRST_PUBLICATION, ZERO, 1,
                first.manifest(), first.journalPrefix(), null);
        invalidManifest(() -> ObjectCheckpointHeadVerifier.bindManifest(wrongLineage, fixture.image()));
        var prefix = first.journalPrefix();
        var wrongPrefix = candidate(NAMESPACE, PUBLISHER, FIRST_PUBLICATION, LINEAGE, 1,
                first.manifest(), new CheckpointManifest.JournalPrefix(prefix.sequence(), prefix.byteLength(), ZERO.value()), null);
        invalidManifest(() -> ObjectCheckpointHeadVerifier.bindManifest(wrongPrefix, fixture.image()));
    }

    @Test void emptyPrefixLengthMustEqualTheCanonicalHeaderLength() {
        var prefix = new CheckpointManifest.JournalPrefix(0, 25, LINEAGE.value());
        var manifest = new CheckpointManifest(DATABASE, 1, prefix, List.of());
        byte[] image = ManifestCodec.encode(manifest);
        var head = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, FIRST_PUBLICATION, LINEAGE, 1,
                ObjectReference.forBytes(image), prefix, null);
        invalidManifest(() -> ObjectCheckpointHeadVerifier.bindManifest(head, image));
    }

    @Test void metadataBindingDoesNotResolveOrValidateDependencies() {
        var fixture = fixture(DATABASE, 1, List.of(record(1, 100)));
        var decoded = ObjectCheckpointHeadVerifier.bindManifest(first(fixture), fixture.image());
        assertEquals(fixture.manifest(), decoded.manifest());
        assertEquals(ManifestFormatException.Code.INVALID_CHECKPOINT,
                assertThrows(ManifestFormatException.class, () -> CheckpointVerifier.verify(decoded.manifest(), id -> null)).code());
    }

    @Test void adjacentMetadataCannotProveExtensionForCopiedLineage() {
        var original = fixture(DATABASE, 17, List.of(record(1, 100)));
        var divergent = fixture(DATABASE, 18, List.of(record(1, 999), record(2, 120)));
        var before = first(original);
        var after = successor(before, divergent);
        assertEquals(before.lineageId(), after.lineageId());
        assertTrue(after.journalPrefix().byteLength() > before.journalPrefix().byteLength());
        assertNotEquals(before.journalPrefix().sha256(), fixture(DATABASE, 17, List.of(record(1, 999))).manifest().journalPrefix().sha256());
        assertDoesNotThrow(() -> ObjectCheckpointHeadVerifier.requireSuccessor(before, after));
        assertDoesNotThrow(() -> ObjectCheckpointHeadVerifier.bindManifest(after, divergent.image()));
        assertDoesNotThrow(() -> CheckpointVerifier.verify(original.manifest(), id -> original.segment()));
        assertDoesNotThrow(() -> CheckpointVerifier.verify(divergent.manifest(), id -> divergent.segment()));
    }

    @Test void validatorsRejectNullInputsExplicitly() {
        var fixture = fixture(DATABASE, 1, List.of());
        var head = first(fixture);
        assertThrows(NullPointerException.class, () -> ObjectCheckpointHeadVerifier.lineageId(null));
        assertThrows(NullPointerException.class, () -> ObjectCheckpointHeadVerifier.requireBinding(null, NAMESPACE, PUBLISHER, LINEAGE));
        assertThrows(NullPointerException.class, () -> ObjectCheckpointHeadVerifier.requireBinding(head, null, PUBLISHER, LINEAGE));
        assertThrows(NullPointerException.class, () -> ObjectCheckpointHeadVerifier.requireSuccessor(null, head));
        assertThrows(NullPointerException.class, () -> ObjectCheckpointHeadVerifier.requireSuccessor(head, null));
        assertThrows(NullPointerException.class, () -> ObjectCheckpointHeadVerifier.bindManifest(null, fixture.image()));
        assertThrows(NullPointerException.class, () -> ObjectCheckpointHeadVerifier.bindManifest(head, null));
    }

    private static ObjectCheckpointHead first(Fixture fixture) {
        return new ObjectCheckpointHead(NAMESPACE, PUBLISHER, FIRST_PUBLICATION,
                ObjectCheckpointHeadVerifier.lineageId(fixture.manifest().databaseId()), 1,
                ObjectReference.forBytes(fixture.image()), fixture.manifest().journalPrefix(), null);
    }

    private static ObjectCheckpointHead successor(ObjectCheckpointHead previous, Fixture fixture) {
        return new ObjectCheckpointHead(previous.namespaceId(), previous.publisherId(), SECOND_PUBLICATION, previous.lineageId(),
                previous.revision() + 1, ObjectReference.forBytes(fixture.image()), fixture.manifest().journalPrefix(),
                ObjectCheckpointHeadCodec.reference(previous));
    }

    private static ObjectCheckpointHead candidate(UUID namespace, UUID publisher, UUID publication,
                                                   ObjectId lineage, long revision, ObjectReference manifest,
                                                   CheckpointManifest.JournalPrefix prefix, ObjectReference predecessor) {
        return new ObjectCheckpointHead(namespace, publisher, publication, lineage, revision, manifest, prefix, predecessor);
    }

    private static JournalRecord record(long sequence, long value) {
        return new JournalRecord(sequence, new Transaction(new RequestId("request-" + sequence), List.of(
                new WriteOperation.Put(new EntityId("salary"), new ValidTimeInterval(10L, null), value))));
    }

    private record Fixture(CheckpointManifest manifest, byte[] image, byte[] segment) { }

    private static Fixture fixture(DatabaseId database, long generation, List<JournalRecord> records) {
        var journal = new ByteArrayOutputStream();
        journal.writeBytes(JournalCodec.encodeHeader(database));
        records.forEach(record -> journal.writeBytes(JournalCodec.encodeRecord(record)));
        byte[] bytes = journal.toByteArray();
        var prefix = new CheckpointManifest.JournalPrefix(records.size(), bytes.length, ObjectReference.forBytes(bytes).id().value());
        byte[] segment = records.isEmpty() ? null : SegmentCodec.encode(new ImmutableSegment(database, records));
        var references = records.isEmpty() ? List.<CheckpointManifest.SegmentReference>of() : List.of(
                new CheckpointManifest.SegmentReference(new SegmentId(ObjectReference.forBytes(segment).id().value()), segment.length, 1, records.size()));
        var manifest = new CheckpointManifest(database, generation, prefix, references);
        return new Fixture(manifest, ManifestCodec.encode(manifest), segment);
    }

    private static void invalidBinding(org.junit.jupiter.api.function.Executable operation) {
        assertEquals(ObjectCheckpointHeadException.Code.INVALID_BINDING, assertThrows(ObjectCheckpointHeadException.class, operation).code());
    }

    private static void invalidTransition(org.junit.jupiter.api.function.Executable operation) {
        assertEquals(ObjectCheckpointHeadException.Code.INVALID_TRANSITION, assertThrows(ObjectCheckpointHeadException.class, operation).code());
    }

    private static ObjectCheckpointHeadException invalidManifest(org.junit.jupiter.api.function.Executable operation) {
        var failure = assertThrows(ObjectCheckpointHeadException.class, operation);
        assertEquals(ObjectCheckpointHeadException.Code.INVALID_MANIFEST, failure.code());
        return failure;
    }
}
