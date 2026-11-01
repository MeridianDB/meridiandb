// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.meridiandb.storage.journal.JournalRecord;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.object.ImmutableObjectStore;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectKey;
import org.meridiandb.storage.object.ObjectReference;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentFormatException;
import org.meridiandb.storage.segment.SegmentId;

/**
 * Read-only complete graph/owned-journal verification. Owns neither supplied adapter nor journal.
 * Results are immutable metadata or original history, not a selected head, writer lease, force
 * or cleanup permission. Only loadHistory retains checkpoint records after verification.
 */
public final class ObjectCheckpointGraphs {
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    public static final long MAX_RECORD_WORK = 1_000_000;
    public static final int MAX_OBJECT_READS = 2 * (ManifestCodec.MAX_SEGMENTS + 1);

    /** Per operation: repeated objects and both graph/actual-journal record counts are charged. */
    public record Limits(long maxObjectBytes, long maxJournalBytes, long maxRecordWork, int maxObjectReads) {
        public Limits {
            if (maxObjectBytes < 0 || maxObjectBytes > MAX_BYTES || maxJournalBytes < 0 || maxJournalBytes > MAX_BYTES
                    || maxRecordWork < 0 || maxRecordWork > MAX_RECORD_WORK
                    || maxObjectReads < 0 || maxObjectReads > MAX_OBJECT_READS) {
                throw new IllegalArgumentException("Invalid graph verification limits");
            }
        }
    }

    public static final Limits DEFAULT_LIMITS = new Limits(MAX_BYTES, MAX_BYTES, MAX_RECORD_WORK, MAX_OBJECT_READS);

    /** Complete successful encoded work, not exact heap usage, native read calls or CPU instructions. */
    public record Usage(long objectBytes, long journalBytes, long recordWork, int objectReads, int journalReads) { }

    public static final class Graph {
        private final ObjectCheckpointHead head;
        private final CheckpointManifest manifest;
        private final LocalJournal.Prefix actualPrefix;
        private final ObjectReference headReference;
        private Graph(ObjectCheckpointHead head, CheckpointManifest manifest, LocalJournal.Prefix actualPrefix) {
            this.head = head;
            this.manifest = manifest;
            this.actualPrefix = actualPrefix;
            headReference = ObjectCheckpointHeadCodec.reference(head);
        }
        public ObjectCheckpointHead head() { return head; }
        public CheckpointManifest manifest() { return manifest; }
        public LocalJournal.Prefix actualPrefix() { return actualPrefix; }
        public ObjectReference headReference() { return headReference; }
    }

    public static final class Verification {
        private final List<Graph> graphs;
        private final Usage usage;
        private Verification(List<Graph> graphs, Usage usage) {
            this.graphs = List.copyOf(graphs);
            this.usage = usage;
        }
        public List<Graph> graphs() { return graphs; }
        public Usage usage() { return usage; }
    }

    /** Heap-owned original history, exposed only after complete graph/actual-prefix verification. */
    public static final class History {
        private final Graph graph;
        private final Usage usage;
        private final List<JournalRecord> records;
        private History(Verification verified, List<JournalRecord> records) {
            this.graph = verified.graphs().getFirst();
            this.usage = verified.usage();
            this.records = List.copyOf(records);
        }
        public Graph graph() { return graph; }
        public Usage usage() { return usage; }
        public List<JournalRecord> records() { return records; }
    }

    private final ImmutableObjectStore objects;
    private final LocalJournal journal;
    private final UUID namespace;
    private final UUID publisher;

    public ObjectCheckpointGraphs(ImmutableObjectStore objects, LocalJournal journal, UUID namespace, UUID publisher) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.namespace = identity(namespace, "namespace");
        this.publisher = identity(publisher, "publisher");
    }

    /** Verify one supplied declaration; its remote selection/provenance is outside this API. */
    public Verification verify(ObjectCheckpointHead head, Limits limits) throws IOException {
        return verifyHeads(List.of(head), limits);
    }

    /**
     * Load original records from one supplied pinned declaration, using the same bounded reads as
     * verification. No tail replay, current-head observation, engine or continuing journal lease.
     */
    public History loadHistory(ObjectCheckpointHead head, Limits limits) throws IOException {
        var records = new ArrayList<JournalRecord>();
        var verified = verifyHeads(List.of(head), limits, records::add);
        return new History(verified, records);
    }

    /** Verify both complete graphs in predecessor order under one shared budget, without ancestry traversal. */
    public Verification verifySuccessor(ObjectCheckpointHead previous, ObjectCheckpointHead candidate,
                                        Limits limits) throws IOException {
        ObjectCheckpointHeadVerifier.requireSuccessor(previous, candidate);
        return verifyHeads(List.of(previous, candidate), limits);
    }

    public static ObjectKey manifestKey(UUID namespace, ObjectId id) {
        return new ObjectKey(prefix(namespace) + "-manifest-" + Objects.requireNonNull(id, "id").value() + ".bin");
    }

    public static ObjectKey segmentKey(UUID namespace, SegmentId id) {
        return new ObjectKey(prefix(namespace) + "-segment-" + Objects.requireNonNull(id, "id").value() + ".bin");
    }

    private Verification verifyHeads(List<ObjectCheckpointHead> heads, Limits limits) throws IOException {
        return verifyHeads(heads, limits, record -> { });
    }

    // Only private collectors may see records before the final actual-prefix check. No application
    // visitor can observe partially accepted history or substitute a later unbudgeted fetch.
    private Verification verifyHeads(List<ObjectCheckpointHead> heads, Limits limits,
                                     Consumer<? super JournalRecord> collector) throws IOException {
        var budget = new Budget(Objects.requireNonNull(limits, "limits"));
        var lineage = ObjectCheckpointHeadVerifier.lineageId(journal.databaseId());
        for (var head : heads) {
            ObjectCheckpointHeadVerifier.requireBinding(head, namespace, publisher, lineage);
            if (head.journalPrefix().sequence() > journal.appendedSequence()) {
                throw failure(ObjectCheckpointGraphException.Code.JOURNAL_MISMATCH, "Head exceeds owned journal history", null);
            }
            budget.head(head);
        }

        // Both manifests are bounded and closed before any dependency or actual-prefix scan.
        var manifests = new ArrayList<CheckpointManifest>(heads.size());
        for (var head : heads) {
            byte[] image = read(manifestKey(namespace, head.manifest().id()), head.manifest());
            try { manifests.add(ObjectCheckpointHeadVerifier.bindManifest(head, image).manifest()); }
            catch (ObjectCheckpointHeadException invalid) {
                throw failure(ObjectCheckpointGraphException.Code.INVALID_GRAPH, "Invalid referenced manifest", invalid);
            }
        }
        for (var manifest : manifests) {
            for (var reference : manifest.segments()) { budget.object(reference.byteLength()); }
        }

        var graphs = new ArrayList<Graph>(heads.size());
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            var manifest = manifests.get(i);
            try {
                CheckpointVerifier.verifyDecoded(manifest, reference -> {
                    var expected = new ObjectReference(new ObjectId(reference.id().value()), Math.toIntExact(reference.byteLength()));
                    byte[] image = read(segmentKey(namespace, reference.id()), expected);
                    return SegmentCodec.decode(image, reference.id(), reference.firstSequence(), reference.lastSequence());
                }, collector);
            } catch (ManifestFormatException | SegmentFormatException invalid) {
                throw failure(ObjectCheckpointGraphException.Code.INVALID_GRAPH, "Invalid complete checkpoint history", invalid);
            }
            final LocalJournal.Prefix actual;
            try { actual = journal.prefix(head.journalPrefix().sequence(), head.journalPrefix().byteLength()); }
            catch (LocalJournal.PrefixLimitException exceeded) {
                throw failure(ObjectCheckpointGraphException.Code.JOURNAL_MISMATCH, "Owned journal prefix exceeds declaration", exceeded);
            }
            var expected = head.journalPrefix();
            if (actual.sequence() != expected.sequence() || actual.byteLength() != expected.byteLength()
                    || !actual.sha256().equals(expected.sha256())) {
                throw failure(ObjectCheckpointGraphException.Code.JOURNAL_MISMATCH, "Owned journal prefix differs from graph", null);
            }
            graphs.add(new Graph(head, manifest, actual));
        }
        return new Verification(graphs, budget.usage(heads.size()));
    }

    private byte[] read(ObjectKey key, ObjectReference expected) throws IOException {
        try (var object = objects.read(key, expected)) {
            if (object == null || !object.reference().equals(expected)) { throw new IOException("Adapter returned a different object"); }
            return object.bytes();
        }
    }

    private static final class Budget {
        private final Limits limits;
        private long objectBytes;
        private long journalBytes;
        private long recordWork;
        private int objectReads;
        Budget(Limits limits) { this.limits = limits; }
        void head(ObjectCheckpointHead head) throws ObjectCheckpointGraphException {
            object(head.manifest().byteLength());
            long bytes = head.journalPrefix().byteLength();
            long records = 2 * head.journalPrefix().sequence();
            if (bytes > limits.maxJournalBytes() - journalBytes || records > limits.maxRecordWork() - recordWork) {
                throw failure(ObjectCheckpointGraphException.Code.LIMIT_EXCEEDED, "Journal-byte or record-work budget exceeded", null);
            }
            journalBytes += bytes;
            recordWork += records;
        }
        void object(long bytes) throws ObjectCheckpointGraphException {
            if (bytes > limits.maxObjectBytes() - objectBytes || objectReads == limits.maxObjectReads()) {
                throw failure(ObjectCheckpointGraphException.Code.LIMIT_EXCEEDED, "Object-byte or fetch budget exceeded", null);
            }
            objectBytes += bytes;
            objectReads++;
        }
        Usage usage(int journalReads) { return new Usage(objectBytes, journalBytes, recordWork, objectReads, journalReads); }
    }

    private static String prefix(UUID namespace) { return "ns-" + identity(namespace, "namespace").toString().replace("-", ""); }
    private static UUID identity(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0) {
            throw new IllegalArgumentException(name + " must not be nil");
        }
        return value;
    }
    private static ObjectCheckpointGraphException failure(ObjectCheckpointGraphException.Code code, String message, Throwable cause) {
        return new ObjectCheckpointGraphException(code, message, cause);
    }
}
