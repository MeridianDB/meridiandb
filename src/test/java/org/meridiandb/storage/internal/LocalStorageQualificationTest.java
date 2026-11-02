package org.meridiandb.storage.internal;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.meridiandb.storage.manifest.LocalCheckpointStore;
import tools.jackson.databind.json.JsonMapper;

class LocalStorageQualificationTest {
    @TempDir
    Path fixture;

    @Test
    @Timeout(10)
    void recordsActualFixtureCapabilitiesWithoutClaimingDeviceDurability() throws IOException {
        Path real = fixture.toRealPath();
        String rootProperty = System.getProperty("meridiandb.qualification.root");
        boolean qualification = rootProperty != null;
        if (qualification) {
            assertEquals(Path.of(rootProperty).toRealPath(), real.getParent());
            assertEquals(real.getParent(), Path.of(System.getProperty("java.io.tmpdir")).toRealPath());
            assertEquals(QualificationTempDirFactory.class.getName(),
                    System.getProperty("junit.jupiter.tempdir.factory.default"));
        }

        // Exercise the production directory capability probe, not an OS-name guess.
        try (var store = LocalCheckpointStore.open(real)) {
            assertNotNull(store);
        } catch (LocalCheckpointStore.DirectorySyncUnsupportedException unsupported) {
            if (qualification) {
                throw unsupported;
            }
            assumeTrue(false, "Required directory synchronization is unavailable");
        }

        Path source = real.resolve("capability-source");
        Object original;
        try (var channel = FileChannel.open(source, CREATE_NEW, READ, WRITE)) {
            if (channel.write(ByteBuffer.wrap(new byte[] {42})) != 1) {
                throw new IOException("Capability probe did not complete its one-byte write");
            }
            channel.force(true);
            original = Files.readAttributes(source, BasicFileAttributes.class).fileKey();
            assertNotNull(original, "Required native identity is unavailable");
            try (var lock = channel.tryLock(0, 1, true)) {
                assertNotNull(lock, "Fresh shared lock was contended");
                assertTrue(lock.isShared(), "Shared mode was converted to exclusive");
            }
            try (var lock = channel.tryLock(0, 1, false)) {
                assertNotNull(lock, "Fresh exclusive lock was contended");
                assertTrue(!lock.isShared());
            }
        }
        Path destination = real.resolve("capability-destination");
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        try (var directory = FileChannel.open(real, READ)) {
            directory.force(true);
        }
        assertEquals(original, Files.readAttributes(destination, BasicFileAttributes.class).fileKey());
        assertEquals(42, Files.readAllBytes(destination)[0]);

        if (qualification) {
            var profile = new LinkedHashMap<String, Object>();
            profile.put("schemaVersion", 1);
            profile.put("runId", System.getProperty("meridiandb.qualification.runId"));
            profile.put("fixtureRoot", real.getParent().toString());
            profile.put("fileStoreType", Files.getFileStore(real).type());
            profile.put("javaVersion", System.getProperty("java.version"));
            profile.put("javaRuntimeVersion", System.getProperty("java.runtime.version"));
            profile.put("javaVendor", System.getProperty("java.vendor"));
            profile.put("osArch", System.getProperty("os.arch"));
            profile.put("observedCapabilities", java.util.List.of("production-directory-probe",
                    "file-force", "directory-force", "file-key", "actual-shared-lock",
                    "exclusive-lock", "atomic-move", "identity-after-move"));
            byte[] bytes = JsonMapper.builder().build().writeValueAsBytes(profile);
            Files.write(Path.of(System.getProperty("meridiandb.qualification.profile")), bytes, CREATE_NEW);
        }
    }
}
