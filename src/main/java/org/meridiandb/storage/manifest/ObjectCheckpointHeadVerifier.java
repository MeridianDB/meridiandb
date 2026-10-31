// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.util.Objects;
import java.util.UUID;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.temporal.DatabaseId;

/** Pure metadata checks; success proves neither actual prefix extension nor selected durable history. */
public final class ObjectCheckpointHeadVerifier {
    private ObjectCheckpointHeadVerifier() { }

    /** Hash every byte of the canonical journal header, preserving the existing UTF-16 lineage domain. */
    public static ObjectId lineageId(DatabaseId database) {
        return ObjectReference.forBytes(JournalCodec.encodeHeader(database)).id();
    }

    /** Expected values come from independent configuration/owned lineage, not the untrusted head itself. */
    public static void requireBinding(ObjectCheckpointHead head, UUID namespace, UUID publisher, ObjectId lineage) {
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(lineage, "lineage");
        if (!head.namespaceId().equals(namespace) || !head.publisherId().equals(publisher) || !head.lineageId().equals(lineage)) {
            throw failure(ObjectCheckpointHeadException.Code.INVALID_BINDING, "Namespace, publisher or lineage differs");
        }
    }

    /** Validate an adjacent candidate, without reading a provider token or any actual journal bytes. */
    public static void requireSuccessor(ObjectCheckpointHead previous, ObjectCheckpointHead candidate) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(candidate, "candidate");
        if (!previous.namespaceId().equals(candidate.namespaceId()) || !previous.publisherId().equals(candidate.publisherId())
                || !previous.lineageId().equals(candidate.lineageId())) {
            throw transition("Candidate changes the namespace, publisher or lineage");
        }
        if (previous.revision() == Long.MAX_VALUE || candidate.revision() != previous.revision() + 1) {
            throw transition("Candidate must advance exactly one remote head revision");
        }
        if (previous.publicationId().equals(candidate.publicationId())) {
            throw transition("An adjacent publication must have a distinct identity");
        }
        if (!ObjectCheckpointHeadCodec.reference(previous).equals(candidate.predecessor())) {
            throw transition("Candidate does not identify the exact complete predecessor image");
        }
        var before = previous.journalPrefix();
        var after = candidate.journalPrefix();
        if (after.sequence() < before.sequence()
                || (after.sequence() == before.sequence() && !after.equals(before))
                || (after.sequence() > before.sequence() && after.byteLength() <= before.byteLength())) {
            throw transition("Candidate regresses or contradicts the predecessor prefix coordinates");
        }
    }

    /**
     * Bind the complete encoded manifest's identity, lineage and prefix declarations.
     * Does not resolve segments, compare actual journal bytes, force, publish or grant cleanup.
     * Manifest generation is intentionally independent of remote head revision.
     */
    public static ManifestCodec.DecodedManifest bindManifest(ObjectCheckpointHead head, byte[] image) {
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(image, "image");
        if (image.length != head.manifest().byteLength()) {
            throw failure(ObjectCheckpointHeadException.Code.INVALID_MANIFEST, "Manifest encoded length differs");
        }
        final ManifestCodec.DecodedManifest decoded;
        try { decoded = ManifestCodec.decode(image, new ManifestId(head.manifest().id().value())); }
        catch (ManifestFormatException invalid) {
            throw new ObjectCheckpointHeadException(ObjectCheckpointHeadException.Code.INVALID_MANIFEST,
                    "Invalid referenced manifest", invalid);
        }
        var manifest = decoded.manifest();
        byte[] lineage = JournalCodec.encodeHeader(manifest.databaseId());
        if (!ObjectReference.forBytes(lineage).id().equals(head.lineageId()) || !manifest.journalPrefix().equals(head.journalPrefix())
                || (head.journalPrefix().sequence() == 0 && head.journalPrefix().byteLength() != lineage.length)) {
            throw failure(ObjectCheckpointHeadException.Code.INVALID_MANIFEST, "Manifest lineage or prefix differs");
        }
        return decoded;
    }

    private static ObjectCheckpointHeadException transition(String message) {
        return failure(ObjectCheckpointHeadException.Code.INVALID_TRANSITION, message);
    }

    private static ObjectCheckpointHeadException failure(ObjectCheckpointHeadException.Code code, String message) {
        return new ObjectCheckpointHeadException(code, message);
    }
}
