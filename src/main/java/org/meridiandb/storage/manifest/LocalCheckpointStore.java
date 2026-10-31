// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.manifest;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.meridiandb.storage.internal.LocalFileOwnership;
import org.meridiandb.storage.journal.LocalJournal;
import org.meridiandb.storage.journal.JournalCodec;
import org.meridiandb.storage.segment.ImmutableSegment;
import org.meridiandb.storage.segment.SegmentCodec;
import org.meridiandb.storage.segment.SegmentId;
import org.meridiandb.temporal.DatabaseId;

/**
 * Exclusive local checkpoint publisher in an existing trusted, stable, durably named directory.
 * All dependencies share that directory. Atomic rename and directory force are required, without
 * fallback. This protocol does not integrate engine acknowledgement or reclaim any history.
 */
public final class LocalCheckpointStore implements AutoCloseable {
    public static final int MAX_DIRECTORY_ENTRIES = 16_384;
    public static final int MAX_GENERATIONS = 1024;
    static final String JOURNAL = "transactions.journal";
    static final String LOCK = ".meridiandb-checkpoints.lock";
    private static final Pattern GENERATION = Pattern.compile("checkpoint-([0-9]{19})-([0-9a-f]{64})\\.manifest");

    public static final class DirectorySyncUnsupportedException extends IOException {
        private static final long serialVersionUID = 1L;
        DirectorySyncUnsupportedException(Throwable cause) {
            super("Directory open/force is unavailable; checkpoint publication requires this capability", cause);
        }
    }

    public static final class SharedReaderLockUnsupportedException extends IOException {
        private static final long serialVersionUID = 1L;
        SharedReaderLockUnsupportedException() {
            super("Shared file locking is unavailable; lazy checkpoint readers require this capability");
        }
    }

    public static final class UncertainPublicationException extends IOException {
        private static final long serialVersionUID = 1L;
        UncertainPublicationException(Throwable cause) {
            super("Atomic publication was attempted; close and reopen to establish the selected generation", cause);
        }
    }

    public static final class UncertainReclamationException extends IOException {
        private static final long serialVersionUID = 1L;
        UncertainReclamationException(Throwable cause) {
            super("Physical removal was attempted; close and reopen to establish remaining objects", cause);
        }
    }

    enum ReclamationBoundary { LIVE_ROOTS_SYNCED, MANIFEST_REMOVED, MANIFESTS_SYNCED, SEGMENT_REMOVED, DIRECTORY_SYNCED }
    @FunctionalInterface interface ReclamationProbe {
        void reached(ReclamationBoundary boundary, String objectName) throws IOException;
    }

    // Per-instance package seam; production callers have no observer or global fault state.
    enum PublicationBoundary { JOURNAL_CREATED, DEPENDENCIES_SYNCED, CANDIDATE_SYNCED, MANIFEST_RENAMED, DIRECTORY_SYNCED }
    @FunctionalInterface interface PublicationProbe { void reached(PublicationBoundary boundary) throws IOException; }

    @FunctionalInterface
    interface DirectoryProbe {
        void force(FileChannel directory) throws IOException;
    }

    interface Namespace extends AutoCloseable {
        Path directory();
        LocalReaderBarrier.Protection acquireReader() throws IOException;
        Optional<LocalReaderBarrier.Protection> tryReclamationBarrier() throws IOException;
        List<String> entries() throws IOException;
        void checkObjectAliases(String name, List<String> entries) throws IOException;
        byte[] read(String name, int maxBytes) throws IOException;
        void writeNew(String name, byte[] image) throws IOException;
        void forceFile(String name) throws IOException;
        void atomicMove(String from, String to) throws IOException;
        void delete(String name) throws IOException;
        void forceDirectory() throws IOException;
        @Override void close() throws IOException;
    }

    interface JournalAccess {
        DatabaseId databaseId();
        LocalJournal.Prefix prefix(long sequence) throws IOException;
        void force() throws IOException;
    }

    interface FileAccess extends AutoCloseable {
        int read(ByteBuffer target, long offset) throws IOException;
        int write(ByteBuffer source, long offset) throws IOException;
        long size() throws IOException;
        void force() throws IOException;
        @Override void close() throws IOException;
    }

    private record Generation(String name, long number, ManifestId id) { }
    private final PublicationProbe probe;
    private final ReclamationProbe reclamationProbe;
    private record Inspection(ReclamationInventory inventory, List<String> retainedFiles) { }
    private final Namespace namespace;
    private boolean failed;
    private boolean closed;
    private boolean resourcesClosed;
    private boolean operating;
    private LocalReaderBarrier.Protection inspectionProtection;

    LocalCheckpointStore(Namespace namespace) { this(namespace, null); }

    LocalCheckpointStore(Namespace namespace, PublicationProbe probe) { this(namespace, probe, null); }

    LocalCheckpointStore(Namespace namespace, PublicationProbe probe, ReclamationProbe reclamationProbe) {
        this.probe = probe;
        this.reclamationProbe = reclamationProbe;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /** Capability is probed before any lock or candidate file is created. */
    public static LocalCheckpointStore open(Path directory) throws IOException {
        return open(directory, channel -> channel.force(true));
    }

    static LocalCheckpointStore open(Path directory, DirectoryProbe probe) throws IOException {
        return new LocalCheckpointStore(NioNamespace.acquire(directory, Objects.requireNonNull(probe, "probe")));
    }

    static LocalCheckpointStore openObserved(Path directory, PublicationProbe probe) throws IOException {
        Objects.requireNonNull(probe, "probe");
        return new LocalCheckpointStore(NioNamespace.acquire(directory, channel -> channel.force(true)), probe);
    }

    static LocalCheckpointStore openReclamationObserved(Path directory, ReclamationProbe probe) throws IOException {
        Objects.requireNonNull(probe, "probe");
        return new LocalCheckpointStore(NioNamespace.acquire(directory, channel -> channel.force(true)), null, probe);
    }

    private void observe(ReclamationBoundary boundary, String name) throws IOException {
        if (reclamationProbe != null) { reclamationProbe.reached(boundary, name); }
    }

    private void observe(PublicationBoundary boundary) throws IOException {
        if (probe != null) { probe.reached(boundary); }
    }

    public synchronized Path journalPath() throws IOException {
        usable();
        return namespace.directory().resolve(JOURNAL);
    }

    /**
     * Initialize a fresh store with an owned journal and selected genesis generation one.
     * Only this store's ownership file may already exist. Failure retains all candidate evidence.
     * The returned journal is owned by the caller and must be closed separately.
     */
    public synchronized LocalJournal initialize(DatabaseId databaseId) throws IOException {
        usable();
        JournalCodec.encodeHeader(databaseId); // Invalid lineage must not create a journal candidate.
        LocalJournal journal = null;
        operating = true;
        try {
            if (namespace.entries().stream().anyMatch(name -> !name.equals(LOCK))) {
                throw new IOException("Database initialization requires a fresh directory");
            }
            journal = LocalJournal.create(namespace.directory().resolve(JOURNAL), databaseId);
            observe(PublicationBoundary.JOURNAL_CREATED);
            var prefix = journal.prefix(0);
            var genesis = new CheckpointManifest(databaseId, 1,
                    new CheckpointManifest.JournalPrefix(0, prefix.byteLength(), prefix.sha256()), List.of());
            operating = false; // publish owns the guarded I/O phase; the store monitor remains held.
            publish(genesis, journal);
            return journal;
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            if (journal != null) {
                try { journal.close(); }
                catch (IOException | RuntimeException | Error closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw failure;
        } finally {
            operating = false;
        }
    }

    /** Establish a recovered owner's file and name before enabling durable write acknowledgements. */
    public synchronized void synchronizeJournal(LocalJournal journal) throws IOException {
        usable();
        var owned = access(journal);
        operating = true;
        try {
            owned.force();
            namespace.forceDirectory();
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            operating = false;
        }
    }

    /** Create a new content-addressed segment; an existing candidate is never replaced or repaired. */
    public synchronized SegmentId writeSegment(ImmutableSegment segment) throws IOException {
        usable();
        byte[] image = SegmentCodec.encode(segment);
        var id = new SegmentId(HexFormat.of().formatHex(ManifestCodec.sha256().digest(image)));
        operating = true;
        try {
            requireCapacity(false);
            namespace.writeNew(segmentName(id), image);
            return id;
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            operating = false;
        }
    }

    public synchronized ManifestId publish(CheckpointManifest manifest, LocalJournal journal) throws IOException {
        usable();
        return publish(manifest, access(journal));
    }

    synchronized ManifestId publish(CheckpointManifest manifest, JournalAccess journal) throws IOException {
        usable();
        Objects.requireNonNull(journal, "journal");
        byte[] image = ManifestCodec.encode(manifest); // Validate caller metadata before storage work.
        var id = ManifestCodec.decode(image).id();
        operating = true;
        try {
            var previous = loadLatest(journal);
            long generation = previous.map(value -> value.manifest().generation()).orElse(0L);
            if (generation == Long.MAX_VALUE || manifest.generation() != generation + 1
                    || (previous.isPresent() && manifest.journalPrefix().sequence()
                        < previous.orElseThrow().manifest().journalPrefix().sequence())) {
                throw new IllegalArgumentException("Publication requires the next generation and a nondecreasing cutoff");
            }
            requireCapacity(true);
            verify(manifest, journal);
            return publishVerified(manifest, image, id, journal);
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            operating = false;
        }
    }

    /**
     * Install a pure compaction plan for the exact currently selected generation and publish its
     * replacement at the same journal cutoff. Existing content is verified byte for byte before
     * reuse; missing objects are created, never replaced. Even unchanged layouts publish the next
     * generation. All older objects, failed candidates and the complete journal remain retained.
     * Close any database owner before acquiring this store and its journal for compaction.
     */
    public synchronized ManifestId publishCompaction(HistoryCompactor.Plan plan, LocalJournal journal) throws IOException {
        usable();
        return publishCompaction(plan, access(journal));
    }

    synchronized ManifestId publishCompaction(HistoryCompactor.Plan plan, JournalAccess journal) throws IOException {
        usable();
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(journal, "journal");
        var replacement = plan.replacementManifest();
        byte[] manifestImage = ManifestCodec.encode(replacement);
        var id = ManifestCodec.decode(manifestImage).id();
        operating = true;
        try {
            loadLatest(journal, plan.sourceManifestId())
                    .orElseThrow(() -> new IOException("Compaction requires a selected source generation"));
            // Plan construction is private and validates immutable metadata/history. Recheck the
            // selected stored graph and actual owned journal above before any installation effect.
            var entries = namespace.entries();
            var existing = new HashSet<>(entries);
            int missing = 0;
            for (int index = 0; index < plan.segments().size(); index++) {
                String name = segmentName(replacement.segments().get(index).id());
                if (existing.contains(name)) {
                    byte[] expected = SegmentCodec.encode(plan.segments().get(index));
                    byte[] stored = namespace.read(name, expected.length);
                    if (!Arrays.equals(expected, stored)) {
                        throw new IOException("Existing compaction object differs from its expected complete image: " + name);
                    }
                } else {
                    missing++;
                }
            }
            // Reserve every missing object and the candidate before creating the first one. The
            // rename replaces the candidate name, so it requires no additional directory slot.
            if ((long) entries.size() + missing + 1 + (existing.contains(LocalReaderBarrier.NAME) ? 0 : 1) > MAX_DIRECTORY_ENTRIES
                    || entries.stream().filter(name -> name.startsWith("checkpoint-")).count() >= MAX_GENERATIONS) {
                throw new IOException("No capacity for complete compaction installation and publication");
            }
            for (int index = 0; index < plan.segments().size(); index++) {
                String name = segmentName(replacement.segments().get(index).id());
                if (!existing.contains(name)) {
                    namespace.writeNew(name, SegmentCodec.encode(plan.segments().get(index)));
                }
            }
            verify(replacement, journal);
            return publishVerified(replacement, manifestImage, id, journal);
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            operating = false;
        }
    }

    /** Shared force/selection protocol; caller holds the monitor and guarded I/O phase. */
    private ManifestId publishVerified(CheckpointManifest manifest, byte[] image, ManifestId id,
                                       JournalAccess journal) throws IOException {
        boolean moving = false;
        try {
            journal.force(); // Through the owning journal, never a second same-file channel.
            for (var reference : manifest.segments()) {
                namespace.forceFile(segmentName(reference.id()));
            }
            namespace.forceDirectory(); // Persist all dependency names, including the journal name.
            observe(PublicationBoundary.DEPENDENCIES_SYNCED);
            String pending = "pending-" + manifest.generation() + "-" + UUID.randomUUID() + ".manifest";
            namespace.writeNew(pending, image); // Full write, force and successful close before rename.
            observe(PublicationBoundary.CANDIDATE_SYNCED);
            String selected = generationName(manifest.generation(), id);
            moving = true; // A move may take effect and then throw: never infer rollback from an exception.
            namespace.atomicMove(pending, selected);
            observe(PublicationBoundary.MANIFEST_RENAMED);
            namespace.forceDirectory();
            observe(PublicationBoundary.DIRECTORY_SYNCED);
            return id;
        } catch (IOException | RuntimeException failure) {
            if (moving) { throw new UncertainPublicationException(failure); }
            throw failure;
        }
    }

    public synchronized Optional<LocalCheckpointReader> readLatest(LocalJournal journal) throws IOException {
        usable();
        return readLatest(access(journal));
    }

    synchronized Optional<LocalCheckpointReader> readLatest(JournalAccess journal) throws IOException {
        usable();
        Objects.requireNonNull(journal, "journal");
        operating = true;
        try {
            var selected = loadLatest(journal);
            if (selected.isEmpty()) { return Optional.empty(); }
            // Store ownership excludes reclamation throughout verification and admission. Acquire
            // independent protection before exposing the reader or permitting store close.
            var protection = namespace.acquireReader();
            try {
                return Optional.of(new LocalCheckpointReader(namespace.directory(), selected.orElseThrow(), protection));
            } catch (RuntimeException | Error failure) {
                try { protection.close(); }
                catch (IOException | RuntimeException | Error closeFailure) {
                    if (failure != closeFailure) { failure.addSuppressed(closeFailure); }
                }
                throw failure;
            }
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            operating = false;
        }
    }

    /** Package-level barrier seam; callers must retain store ownership until protection is released. */
    synchronized Optional<LocalReaderBarrier.Protection> tryReclamationBarrier() throws IOException {
        usable();
        operating = true;
        try { return namespace.tryReclamationBarrier(); }
        catch (IOException | RuntimeException | Error failure) { failed = true; throw failure; }
        finally { operating = false; }
    }

    /**
     * Inspect physical retention under exclusive reader protection. The selected generation is
     * always retained. Explicit IDs designate additional retained/backup roots in this directory;
     * callers must repeat them on every future inspection/removal. This is no persistent pin.
     * A live reader defers inspection. No files are removed; first admission can create/sync the
     * permanent guard. The result is evidence only and must never be used as a deletion capability.
     */
    public synchronized ReclamationInventory inspectReclamation(Set<ManifestId> retainedRoots, LocalJournal journal) throws IOException {
        usable();
        return inspectReclamation(retainedRoots, access(journal), ReclamationInventory.Limits.DEFAULT);
    }

    public synchronized ReclamationInventory inspectReclamation(Set<ManifestId> retainedRoots, LocalJournal journal,
                                                                ReclamationInventory.Limits limits) throws IOException {
        usable();
        return inspectReclamation(retainedRoots, access(journal), limits);
    }

    synchronized ReclamationInventory inspectReclamation(Set<ManifestId> retainedRoots, JournalAccess journal,
                                                         ReclamationInventory.Limits limits) throws IOException {
        usable();
        var retained = Set.copyOf(retainedRoots); // Freeze caller input before storage effects.
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(limits, "limits");
        operating = true;
        try {
            var acquired = namespace.tryReclamationBarrier();
            if (acquired.isEmpty()) { return ReclamationInventory.deferred(); }
            final ReclamationInventory result;
            try (var protection = acquired.orElseThrow()) {
                // Keep failed cleanup reachable for close retry; native uncertainty stays quarantined.
                inspectionProtection = protection;
                protection.checkValid();
                result = inspectProtected(retained, journal, limits).inventory();
                protection.checkValid();
            }
            inspectionProtection = null;
            return result; // Release failure prevents any successful inventory from escaping.
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            operating = false;
        }
    }

    /**
     * Recompute retention and remove only verified obsolete published metadata/dependencies.
     * The selected root is always retained; explicit IDs must be supplied on every invocation.
     * A live reader defers all removal. Preserve full journal, permanent controls, unknown/orphan
     * objects and pending evidence. No previously returned inventory is accepted as authority.
     */
    public synchronized ReclamationResult reclaim(Set<ManifestId> retainedRoots, LocalJournal journal) throws IOException {
        usable();
        return reclaim(retainedRoots, access(journal), ReclamationInventory.Limits.DEFAULT);
    }

    public synchronized ReclamationResult reclaim(Set<ManifestId> retainedRoots, LocalJournal journal,
                                                   ReclamationInventory.Limits limits) throws IOException {
        usable();
        return reclaim(retainedRoots, access(journal), limits);
    }

    synchronized ReclamationResult reclaim(Set<ManifestId> retainedRoots, JournalAccess journal,
                                           ReclamationInventory.Limits limits) throws IOException {
        usable();
        var retained = Set.copyOf(retainedRoots);
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(limits, "limits");
        operating = true;
        boolean removing = false;
        try {
            var acquired = namespace.tryReclamationBarrier();
            if (acquired.isEmpty()) { return ReclamationResult.deferred(); }
            final ReclamationResult result;
            try (var protection = acquired.orElseThrow()) {
                inspectionProtection = protection;
                protection.checkValid();
                var inspection = inspectProtected(retained, journal, limits);
                var inventory = inspection.inventory();
                result = ReclamationResult.completed(inventory); // Allocate receipt before the first deletion effect.
                if (!inventory.obsoleteManifests().isEmpty() || !inventory.obsoleteSegments().isEmpty()) {
                    // A selected image can be visible after an uncertain publication. Verification
                    // alone is no durability receipt: establish live files/names before retiring roots.
                    journal.force();
                    for (String name : inspection.retainedFiles()) { namespace.forceFile(name); }
                    namespace.forceDirectory();
                    observe(ReclamationBoundary.LIVE_ROOTS_SYNCED, null);
                    protection.checkValid();
                    for (var file : inventory.obsoleteManifests()) {
                        removing = true; // A delete may take effect and then throw; never infer rollback.
                        namespace.delete(file.name());
                        observe(ReclamationBoundary.MANIFEST_REMOVED, file.name());
                    }
                    namespace.forceDirectory(); // No dependency deletion before durable metadata retirement.
                    observe(ReclamationBoundary.MANIFESTS_SYNCED, null);
                    protection.checkValid();
                    for (var file : inventory.obsoleteSegments()) {
                        removing = true;
                        namespace.delete(file.name());
                        observe(ReclamationBoundary.SEGMENT_REMOVED, file.name());
                    }
                    namespace.forceDirectory();
                    observe(ReclamationBoundary.DIRECTORY_SYNCED, null);
                }
                protection.checkValid();
            }
            inspectionProtection = null;
            return result; // Final sync AND successful protection release precede a removal receipt.
        } catch (IOException | RuntimeException failure) {
            failed = true;
            if (removing) { throw new UncertainReclamationException(failure); }
            throw failure;
        } catch (Error failure) {
            failed = true;
            throw failure;
        } finally {
            operating = false;
        }
    }

    private Inspection inspectProtected(Set<ManifestId> requested, JournalAccess journal,
                                                  ReclamationInventory.Limits limits) throws IOException {
        var entries = namespace.entries();
        var generations = generations(entries);
        if (entries.stream().anyMatch(name -> name.startsWith("pending-"))) {
            throw new IOException("Pending checkpoint evidence requires explicit inspection before reclamation inventory");
        }
        var retained = new HashSet<>(requested);
        Optional<ManifestId> selected = generations.isEmpty() ? Optional.empty()
                : Optional.of(generations.getLast().id());
        selected.ifPresent(retained::add);
        var present = new HashSet<ManifestId>();
        for (var generation : generations) { present.add(generation.id()); }
        if (!present.containsAll(requested)) { throw new IOException("Requested retained checkpoint root is missing"); }

        // Identity checks do not open another file channel. Check every metadata name before the
        // first metadata open, then every distinct referenced segment before any segment open.
        var checked = new HashSet<String>();
        for (var generation : generations) { checkInventoryAliases(generation.name(), entries, checked); }
        var decoded = new LinkedHashMap<Generation, ManifestCodec.DecodedManifest>();
        var manifestLengths = new LinkedHashMap<String, Long>();
        var segmentLengths = new LinkedHashMap<String, Long>();
        var liveSegments = new HashSet<String>();
        long bytes = 0;
        long records = 0;
        for (var generation : generations) {
            int bound = (int) Math.min(ManifestCodec.MAX_MANIFEST_BYTES, limits.maxBytes() - bytes);
            byte[] image = namespace.read(generation.name(), bound);
            bytes += image.length;
            if (bytes > limits.maxBytes()) { throw new IOException("Reclamation inspection byte budget exceeded"); }
            var value = ManifestCodec.decode(image, generation.id());
            var manifest = value.manifest();
            if (manifest.generation() != generation.number()) { throw new IOException("Filename generation differs from manifest"); }
            if (!manifest.databaseId().equals(journal.databaseId())) { throw new IOException("Checkpoint and journal lineage differ"); }
            if (manifest.journalPrefix().sequence() > limits.maxRecords() - records) {
                throw new IOException("Reclamation inspection record budget exceeded");
            }
            records += manifest.journalPrefix().sequence();
            for (var reference : manifest.segments()) {
                if (reference.byteLength() > limits.maxBytes() - bytes) {
                    throw new IOException("Reclamation inspection byte budget exceeded");
                }
                bytes += reference.byteLength(); // Shared objects count again for repeated verification.
                String name = segmentName(reference.id());
                Long previous = segmentLengths.putIfAbsent(name, reference.byteLength());
                if (previous != null && previous.longValue() != reference.byteLength()) {
                    throw new IOException("Referenced segment lengths disagree");
                }
                if (retained.contains(generation.id())) { liveSegments.add(name); }
            }
            manifestLengths.put(generation.name(), (long) image.length);
            decoded.put(generation, value);
        }
        for (String name : segmentLengths.keySet()) { checkInventoryAliases(name, entries, checked); }
        for (var value : decoded.values()) {
            verify(value.manifest(), journal, id -> {
                String name = segmentName(id);
                // A corrupt oversized stored file must not bypass the metadata work budget.
                return namespace.read(name, Math.toIntExact(segmentLengths.get(name)));
            });
        }
        var obsoleteManifests = new ArrayList<ReclamationInventory.ObjectFile>();
        for (var generation : generations) {
            if (!retained.contains(generation.id())) {
                obsoleteManifests.add(new ReclamationInventory.ObjectFile(generation.name(), manifestLengths.get(generation.name())));
            }
        }
        var obsoleteSegments = new ArrayList<ReclamationInventory.ObjectFile>();
        for (var entry : segmentLengths.entrySet()) {
            if (!liveSegments.contains(entry.getKey())) {
                obsoleteSegments.add(new ReclamationInventory.ObjectFile(entry.getKey(), entry.getValue()));
            }
        }
        obsoleteSegments.sort(Comparator.comparing(ReclamationInventory.ObjectFile::name));
        var obsoleteNames = new HashSet<String>();
        obsoleteManifests.forEach(file -> obsoleteNames.add(file.name()));
        obsoleteSegments.forEach(file -> obsoleteNames.add(file.name()));
        var preserved = entries.stream().filter(name -> !obsoleteNames.contains(name)).sorted().toList();
        var inventory = new ReclamationInventory(ReclamationInventory.Status.VERIFIED, selected, retained,
                obsoleteManifests, obsoleteSegments, preserved, bytes, records);
        var retainedFiles = new HashSet<>(liveSegments);
        for (var generation : generations) {
            if (retained.contains(generation.id())) { retainedFiles.add(generation.name()); }
        }
        return new Inspection(inventory, retainedFiles.stream().sorted().toList());
    }

    private void checkInventoryAliases(String name, List<String> entries, Set<String> checked) throws IOException {
        if (checked.contains(name)) { return; }
        if ((long) (checked.size() + 1) * Math.max(0, entries.size() - 1) > ReclamationInventory.MAX_ALIAS_COMPARISONS) {
            throw new IOException("Reclamation inspection alias-comparison budget exceeded");
        }
        namespace.checkObjectAliases(name, entries);
        checked.add(name);
    }

    private Optional<ManifestCodec.DecodedManifest> loadLatest(JournalAccess journal) throws IOException {
        return loadLatest(journal, null);
    }

    private Optional<ManifestCodec.DecodedManifest> loadLatest(JournalAccess journal, ManifestId expectedSource) throws IOException {
        var generations = generations(namespace.entries());
        if (generations.isEmpty()) { return Optional.empty(); }
        var latest = generations.getLast();
        if (expectedSource != null && !latest.id().equals(expectedSource)) {
            throw new IOException("Compaction source is no longer the selected generation");
        }
        var decoded = ManifestCodec.decode(namespace.read(latest.name(), ManifestCodec.MAX_MANIFEST_BYTES), latest.id());
        if (decoded.manifest().generation() != latest.number()) { throw new IOException("Filename generation differs from manifest"); }
        verify(decoded.manifest(), journal); // Fail on damaged newest state; never silently fall back.
        return Optional.of(decoded);
    }

    private static List<Generation> generations(List<String> entries) throws IOException {
        if (entries.size() > MAX_DIRECTORY_ENTRIES) { throw new IOException("Checkpoint directory entry limit exceeded"); }
        var result = new ArrayList<Generation>();
        var seen = new HashSet<Long>();
        for (String name : entries) {
            if (!name.startsWith("checkpoint-")) { continue; }
            var match = GENERATION.matcher(name);
            if (!match.matches()) { throw new IOException("Malformed selected checkpoint name: " + name); }
            final long number;
            try { number = Long.parseLong(match.group(1)); }
            catch (NumberFormatException invalid) { throw new IOException("Invalid checkpoint generation", invalid); }
            if (number <= 0 || !seen.add(number)) { throw new IOException("Invalid or repeated checkpoint generation"); }
            if (seen.size() > MAX_GENERATIONS) { throw new IOException("Retained checkpoint generation limit exceeded"); }
            result.add(new Generation(name, number, new ManifestId(match.group(2))));
        }
        result.sort(Comparator.comparingLong(Generation::number));
        return result;
    }

    private void requireCapacity(boolean newGeneration) throws IOException {
        var entries = namespace.entries();
        if ((long) entries.size() + 1 + (entries.contains(LocalReaderBarrier.NAME) ? 0 : 1) > MAX_DIRECTORY_ENTRIES) {
            throw new IOException("No capacity for another checkpoint object and permanent reader barrier");
        }
        if (newGeneration && entries.stream().filter(name -> name.startsWith("checkpoint-")).count() >= MAX_GENERATIONS) {
            throw new IOException("No capacity for another retained generation");
        }
    }

    private void verify(CheckpointManifest manifest, JournalAccess journal) throws IOException {
        verify(manifest, journal, id -> namespace.read(segmentName(id), SegmentCodec.MAX_SEGMENT_BYTES));
    }

    private void verify(CheckpointManifest manifest, JournalAccess journal, CheckpointVerifier.SegmentSource source) throws IOException {
        if (!manifest.databaseId().equals(journal.databaseId())) { throw new IOException("Checkpoint and journal lineage differ"); }
        CheckpointVerifier.verify(manifest, source);
        var actual = journal.prefix(manifest.journalPrefix().sequence());
        var expected = manifest.journalPrefix();
        if (actual.sequence() != expected.sequence() || actual.byteLength() != expected.byteLength()
                || !actual.sha256().equals(expected.sha256())) {
            throw new IOException("Stored journal prefix differs from checkpoint history");
        }
    }

    private JournalAccess access(LocalJournal journal) throws IOException {
        Objects.requireNonNull(journal, "journal");
        if (!journal.path().orElseThrow(() -> new IOException("A real owned local journal is required"))
                .equals(namespace.directory().resolve(JOURNAL))) {
            throw new IOException("Journal must occupy this store's transactions.journal path");
        }
        return new JournalAccess() {
            @Override public DatabaseId databaseId() { return journal.databaseId(); }
            @Override public LocalJournal.Prefix prefix(long sequence) throws IOException { return journal.prefix(sequence); }
            @Override public void force() throws IOException { journal.force(); }
        };
    }

    static String segmentName(SegmentId id) { return Objects.requireNonNull(id, "id").value() + ".segment"; }
    static String generationName(long generation, ManifestId id) {
        return String.format(Locale.ROOT, "checkpoint-%019d-%s.manifest", generation, id.value());
    }

    @Override public synchronized void close() throws IOException {
        if (operating) { throw new IOException("Cannot close store inside a storage callback"); }
        closed = true;
        Throwable failure = null;
        if (inspectionProtection != null) {
            try { inspectionProtection.close(); inspectionProtection = null; }
            catch (IOException | RuntimeException | Error closeFailure) { failure = closeFailure; }
        }
        if (!resourcesClosed) {
            try { namespace.close(); resourcesClosed = true; }
            catch (IOException | RuntimeException | Error closeFailure) {
                if (failure == null) { failure = closeFailure; }
                else if (failure != closeFailure) { failure.addSuppressed(closeFailure); }
            }
        }
        if (failure instanceof IOException io) { throw io; }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }

    private void usable() throws IOException {
        if (closed || failed || operating) {
            throw new IOException(closed ? "Checkpoint store is closed" : failed
                    ? "Checkpoint store failed; close and reopen before retry" : "Reentrant checkpoint operation");
        }
    }

    static byte[] read(FileAccess file, int maxBytes) throws IOException {
        try (file) {
            long size = file.size();
            if (size < 0 || size > maxBytes) { throw new IOException("Checkpoint object exceeds read bounds"); }
            var buffer = ByteBuffer.allocate((int) size);
            long offset = 0;
            int zeros = 0;
            while (buffer.hasRemaining()) {
                int count = file.read(buffer, offset);
                if (count < 0) { throw new EOFException("Checkpoint object shrank during read"); }
                if (count == 0 && ++zeros >= 16) { throw new IOException("Checkpoint object read made no progress"); }
                if (count > 0) { offset += count; zeros = 0; }
            }
            var probe = ByteBuffer.allocate(1);
            zeros = 0;
            int count;
            do {
                count = file.read(probe, size);
                if (count == 0 && ++zeros >= 16) { throw new IOException("Checkpoint EOF read made no progress"); }
            } while (count == 0);
            if (count > 0 || file.size() != size) { throw new IOException("Checkpoint object size changed"); }
            return buffer.array();
        }
    }

    static void write(FileAccess file, byte[] image) throws IOException {
        try (file) {
            if (file.size() != 0) { throw new IOException("New checkpoint object must be empty"); }
            var buffer = ByteBuffer.wrap(image);
            long offset = 0;
            int zeros = 0;
            while (buffer.hasRemaining()) {
                int count = file.write(buffer, offset);
                if (count < 0 || (count == 0 && ++zeros >= 16)) { throw new IOException("Checkpoint write made no progress"); }
                if (count > 0) { offset += count; zeros = 0; }
            }
            if (file.size() != image.length) { throw new IOException("Checkpoint object size changed during write"); }
            file.force();
        }
    }

    private static final class NioFile implements FileAccess {
        private final FileChannel channel;
        private final LocalFileOwnership.OwnedChannel owned;
        NioFile(LocalFileOwnership.OwnedChannel owned) { this.owned = owned; this.channel = owned.channel(); }
        static NioFile open(Path path, StandardOpenOption option) throws IOException { return open(path, option, false); }
        static NioFile create(Path path) throws IOException { return open(path, StandardOpenOption.WRITE, true); }
        private static NioFile open(Path path, StandardOpenOption option, boolean create) throws IOException {
            var owned = create ? LocalFileOwnership.openData(path, option, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
                    : LocalFileOwnership.openData(path, option, LinkOption.NOFOLLOW_LINKS);
            try { return new NioFile(owned); }
            catch (RuntimeException | Error failure) {
                try { owned.close(); }
                catch (IOException | RuntimeException | Error closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        }
        @Override public int read(ByteBuffer target, long offset) throws IOException { return channel.read(target, offset); }
        @Override public int write(ByteBuffer source, long offset) throws IOException { return channel.write(source, offset); }
        @Override public long size() throws IOException { return channel.size(); }
        @Override public void force() throws IOException { channel.force(true); }
        @Override public void close() throws IOException { owned.close(); }
    }

    private static final class NioNamespace implements Namespace {
        private final Path directory;
        private final Path lockPath;
        private final FileChannel directoryChannel;
        private final FileLock lock;
        private final LocalFileOwnership.OwnedChannel owned;

        private NioNamespace(Path directory, Path lockPath, FileChannel directoryChannel, FileLock lock, LocalFileOwnership.OwnedChannel owned) {
            this.directory = directory;
            this.lockPath = lockPath;
            this.directoryChannel = directoryChannel;
            this.lock = lock;
            this.owned = owned;
        }

        static NioNamespace acquire(Path requested, DirectoryProbe probe) throws IOException {
            Objects.requireNonNull(requested, "directory");
            if (!Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Store requires an existing nonsymlink directory"); }
            Path directory = requested.toRealPath();
            FileChannel dir = null;
            try {
                dir = FileChannel.open(directory, StandardOpenOption.READ);
                probe.force(dir); // Required probe before lock-file creation or any other mutation.
            } catch (IOException | UnsupportedOperationException unavailable) {
                if (dir != null) {
                    try { dir.close(); } catch (IOException closeFailure) { unavailable.addSuppressed(closeFailure); }
                }
                throw new DirectorySyncUnsupportedException(unavailable);
            } catch (RuntimeException | Error failure) {
                if (dir != null) {
                    try { dir.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                }
                throw failure;
            }
            Path lockPath = directory.resolve(LOCK);
            FileChannel channel = null;
            LocalFileOwnership.OwnedChannel owned = null;
            try {
                LocalReaderBarrier.validateName(directory);
                LocalReaderBarrier.rejectActiveAlias(lockPath);
                LocalReaderBarrier.rejectActiveAlias(directory.resolve(JOURNAL));
                var existing = listNames(directory);
                if ((long) existing.size() + (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) ? 0 : 1)
                        + (existing.contains(LocalReaderBarrier.NAME) ? 0 : 1) > MAX_DIRECTORY_ENTRIES) {
                    throw new IOException("No directory capacity for checkpoint ownership file");
                }
                if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Checkpoint lock must be a regular file");
                }
                Path journalPath = directory.resolve(JOURNAL);
                if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)
                        && Files.isSameFile(lockPath, journalPath)) {
                    throw new IOException("Checkpoint lock must not alias the journal");
                }
                owned = LocalFileOwnership.openControl(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                channel = owned.channel();
                if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Checkpoint lock path changed"); }
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException overlap) { throw new IOException("Checkpoint store lock overlaps", overlap); }
                if (lock == null) { throw new IOException("Checkpoint store is locked by another process"); }
                return new NioNamespace(directory, lockPath, dir, lock, owned);
            } catch (IOException | RuntimeException | Error failure) {
                if (owned != null) {
                    try { owned.close(); }
                    catch (IOException | RuntimeException | Error closeFailure) { failure.addSuppressed(closeFailure); }
                }
                try { dir.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        }

        @Override public Path directory() { return directory; }
        @Override public LocalReaderBarrier.Protection acquireReader() throws IOException {
            return acquireBarrier(true).orElseThrow(() -> new IOException("Reader barrier is held exclusively"));
        }
        @Override public Optional<LocalReaderBarrier.Protection> tryReclamationBarrier() throws IOException {
            return acquireBarrier(false);
        }
        private Optional<LocalReaderBarrier.Protection> acquireBarrier(boolean shared) throws IOException {
            var acquired = LocalReaderBarrier.acquire(directory, shared);
            if (acquired.isEmpty()) { return acquired; }
            try { forceDirectory(); return acquired; }
            catch (IOException | RuntimeException | Error failure) {
                try { acquired.orElseThrow().close(); }
                catch (IOException | RuntimeException | Error closeFailure) {
                    if (failure != closeFailure) { failure.addSuppressed(closeFailure); }
                }
                throw failure;
            }
        }
        @Override public List<String> entries() throws IOException { return listNames(directory); }
        private static List<String> listNames(Path directory) throws IOException {
            var names = new ArrayList<String>();
            try (var stream = Files.newDirectoryStream(directory)) {
                for (var entry : stream) {
                    if (names.size() == MAX_DIRECTORY_ENTRIES) { throw new IOException("Checkpoint directory entry limit exceeded"); }
                    names.add(entry.getFileName().toString());
                }
            }
            return names;
        }
        @Override public void checkObjectAliases(String name, List<String> entries) throws IOException {
            Path object = regular(name);
            for (String other : entries) {
                if (!name.equals(other) && Files.isSameFile(object, directory.resolve(other))) {
                    throw new IOException("Checkpoint inventory object aliases another directory entry: " + name + " / " + other);
                }
            }
        }
        @Override public byte[] read(String name, int maxBytes) throws IOException {
            Path path = regular(name);
            return LocalCheckpointStore.read(NioFile.open(path, StandardOpenOption.READ), maxBytes);
        }
        @Override public void writeNew(String name, byte[] image) throws IOException {
            LocalCheckpointStore.write(NioFile.create(directory.resolve(name)), image);
        }
        @Override public void forceFile(String name) throws IOException {
            Path path = regular(name);
            try (var file = NioFile.open(path, StandardOpenOption.WRITE)) { file.force(); }
        }
        private Path regular(String name) throws IOException {
            Path path = directory.resolve(name);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Checkpoint object must be a regular file: " + name); }
            LocalReaderBarrier.rejectObjectAlias(directory, path);
            return path;
        }
        @Override public void atomicMove(String from, String to) throws IOException {
            Path destination = directory.resolve(to);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { throw new FileAlreadyExistsException(destination.toString()); }
            // Exclusive cooperating ownership and stable paths protect the precondition. No fallback.
            Files.move(directory.resolve(from), destination, StandardCopyOption.ATOMIC_MOVE);
        }
        @Override public void delete(String name) throws IOException {
            Path path = regular(name);
            // Reserve identity without opening a native handle. This excludes controls and active
            // transient data channels, including same-file aliases in other loaded adapters/stores.
            var reservation = LocalFileOwnership.control(path);
            try {
                checkObjectAliases(name, listNames(directory));
                Files.delete(path); // Missing names and ordinary I/O failures fail closed; no blind retry.
            } finally { reservation.close(); }
        }
        @Override public void forceDirectory() throws IOException { directoryChannel.force(true); }
        @Override public void close() throws IOException {
            Throwable failure = null;
            try { if (lock.isValid()) { lock.release(); } }
            catch (IOException | RuntimeException | Error releaseFailure) { failure = releaseFailure; }
            for (var resource : new AutoCloseable[] {owned, directoryChannel}) {
                try { resource.close(); }
                catch (Exception | Error closeFailure) {
                    if (failure == null) { failure = closeFailure; }
                    else if (failure != closeFailure) { failure.addSuppressed(closeFailure); }
                }
            }
            if (failure instanceof IOException io) { throw io; }
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
            if (failure != null) { throw new IOException("Checkpoint cleanup failed", failure); }
        }
    }
}
