package org.meridiandb.storage.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/** Opt-in test-only factory: all qualification fixtures use the profiled filesystem. */
public final class QualificationTempDirFactory implements TempDirFactory {
    @Override
    public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context)
            throws IOException {
        String configured = System.getProperty("meridiandb.qualification.root");
        if (configured == null || configured.isBlank()) {
            throw new IOException("Qualification fixture root is required");
        }
        Path root = Path.of(configured).toRealPath();
        Path result = Files.createTempDirectory(root, "junit-").toRealPath();
        if (!result.getParent().equals(root)) {
            throw new IOException("Fixture escaped qualification root");
        }
        return result;
    }
}
