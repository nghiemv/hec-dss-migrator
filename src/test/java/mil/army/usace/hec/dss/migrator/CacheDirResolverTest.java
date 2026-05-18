package mil.army.usace.hec.dss.migrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CacheDirResolverTest {

    @Test
    void picksFirstWritableCandidate(@TempDir Path tmp) throws IOException {
        Path good = tmp.resolve("good");
        Path resolved = CacheDirResolver.resolve(List.of(good));
        assertEquals(good.toRealPath(), resolved);
        assertTrue(Files.isDirectory(resolved));
    }

    @Test
    void fallsBackPastUnwritablePrimary(@TempDir Path tmp) throws IOException {
        assumeTrue(Files.getFileStore(tmp).supportsFileAttributeView("posix"),
                "POSIX permissions required to simulate unwritable directory");

        Path locked = Files.createDirectory(tmp.resolve("locked"));
        Path primary = locked.resolve("denied");
        Files.setPosixFilePermissions(locked, Set.of(PosixFilePermission.OWNER_READ));
        try {
            Path fallback = tmp.resolve("fallback");
            Path resolved = CacheDirResolver.resolve(List.of(primary, fallback));
            assertEquals(fallback.toRealPath(), resolved);
        } finally {
            Files.setPosixFilePermissions(locked, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void throwsWhenNoCandidateIsWritable(@TempDir Path tmp) throws IOException {
        assumeTrue(Files.getFileStore(tmp).supportsFileAttributeView("posix"),
                "POSIX permissions required to simulate unwritable directory");

        Path locked = Files.createDirectory(tmp.resolve("locked"));
        Files.setPosixFilePermissions(locked, Set.of(PosixFilePermission.OWNER_READ));
        try {
            assertThrows(IOException.class,
                    () -> CacheDirResolver.resolve(List.of(locked.resolve("a"), locked.resolve("b"))));
        } finally {
            Files.setPosixFilePermissions(locked, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void defaultResolveReturnsWritableDirectory() throws IOException {
        Path resolved = CacheDirResolver.resolve();
        assertTrue(Files.isDirectory(resolved));
        Path probe = Files.createTempFile(resolved, ".test-probe-", ".tmp");
        Files.deleteIfExists(probe);
    }

    @Test
    void sysPropOverrideTakesPrecedenceOverPlatformDefault(@TempDir Path tmp) throws IOException {
        Path override = tmp.resolve("override-cache");
        String previous = System.getProperty(CacheDirResolver.CACHE_DIR_PROPERTY);
        System.setProperty(CacheDirResolver.CACHE_DIR_PROPERTY, override.toString());
        try {
            Path resolved = CacheDirResolver.resolve();
            assertEquals(override.toRealPath(), resolved);
        } finally {
            if (previous == null) {
                System.clearProperty(CacheDirResolver.CACHE_DIR_PROPERTY);
            } else {
                System.setProperty(CacheDirResolver.CACHE_DIR_PROPERTY, previous);
            }
        }
    }

    @Test
    void sysPropOverrideFallsThroughWhenUnwritable(@TempDir Path tmp) throws IOException {
        assumeTrue(Files.getFileStore(tmp).supportsFileAttributeView("posix"),
                "POSIX permissions required to simulate unwritable directory");

        Path locked = Files.createDirectory(tmp.resolve("locked"));
        Path bogusOverride = locked.resolve("denied");
        Path fallback = tmp.resolve("fallback");
        Files.setPosixFilePermissions(locked, Set.of(PosixFilePermission.OWNER_READ));

        String previous = System.getProperty(CacheDirResolver.CACHE_DIR_PROPERTY);
        System.setProperty(CacheDirResolver.CACHE_DIR_PROPERTY, bogusOverride.toString());
        try {
            Path resolved = CacheDirResolver.resolve(
                    List.of(bogusOverride, fallback));
            assertEquals(fallback.toRealPath(), resolved);
        } finally {
            if (previous == null) {
                System.clearProperty(CacheDirResolver.CACHE_DIR_PROPERTY);
            } else {
                System.setProperty(CacheDirResolver.CACHE_DIR_PROPERTY, previous);
            }
            Files.setPosixFilePermissions(locked, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }
}
