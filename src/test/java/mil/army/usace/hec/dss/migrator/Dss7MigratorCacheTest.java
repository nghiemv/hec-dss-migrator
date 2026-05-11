package mil.army.usace.hec.dss.migrator;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class Dss7MigratorCacheTest {

    @Test
    void reusesClassLoaderAcrossConstructions() {
        Dss7Migrator a = Dss7Migrator.usingDefaultCache();
        Dss7Migrator b = Dss7Migrator.usingDefaultCache();
        assertSame(a.isolatedClassLoader(), b.isolatedClassLoader());
    }

    @Test
    void programmaticCacheDirCreatesNativesAndMigrates(@TempDir(cleanup = CleanupMode.NEVER) Path cacheDir) throws Exception {
        Dss7Migrator m = Dss7Migrator.usingCache(cacheDir);
        assertNotNull(m.getHeclibVersion());

        Path tempFile = Files.createTempFile("ctor-cache-", ".dss");
        try {
            TestSupport.createEmptyV7File(m, tempFile);
            assertEquals(MigrationResult.ALREADY_UP_TO_DATE, m.migrate(tempFile));

            // Natives must have been extracted somewhere under the supplied dir.
            try (Stream<Path> walk = Files.walk(cacheDir)) {
                assertTrue(walk.anyMatch(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib");
                }), "Expected at least one native staged under " + cacheDir);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void programmaticCacheDirSharesSessionWithSameRoot(@TempDir(cleanup = CleanupMode.NEVER) Path cacheDir) {
        Dss7Migrator a = Dss7Migrator.usingCache(cacheDir);
        Dss7Migrator b = Dss7Migrator.usingCache(cacheDir);
        assertSame(a.isolatedClassLoader(), b.isolatedClassLoader());
    }

    @Test
    void programmaticCacheDirThrowsForUnwritablePath(@TempDir Path tmp) throws IOException {
        Assumptions.assumeTrue(
                Files.getFileStore(tmp).supportsFileAttributeView("posix"),
                "POSIX permissions required to simulate unwritable directory");
        Path locked = Files.createDirectory(tmp.resolve("locked"));
        Files.setPosixFilePermissions(locked, Set.of(PosixFilePermission.OWNER_READ));
        try {
            Path denied = locked.resolve("nope");
            assertThrows(DssMigrationException.class, () -> Dss7Migrator.usingCache(denied));
        } finally {
            Files.setPosixFilePermissions(locked, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void prepareRecoversWhenCachedNativeIsDeleted(@TempDir Path cacheRoot) throws IOException {
        Path victim = firstPlatformNative(NativeExtractor.prepare(cacheRoot));
        Files.delete(victim);

        NativeExtractor.prepare(cacheRoot);

        assertTrue(Files.exists(victim));
        assertTrue(Files.size(victim) > 0);
    }

    @Test
    void prepareRecoversWhenCachedNativeIsCorrupted(@TempDir Path cacheRoot) throws IOException {
        Path victim = firstPlatformNative(NativeExtractor.prepare(cacheRoot));
        long originalSize = Files.size(victim);
        Files.write(victim, new byte[]{0x00, 0x01, 0x02, 0x03});

        NativeExtractor.prepare(cacheRoot);

        assertEquals(originalSize, Files.size(victim));
    }

    @Test
    void prepareSweepsStaleTempFiles(@TempDir Path cacheRoot) throws IOException {
        NativeExtractor.Prepared first = NativeExtractor.prepare(cacheRoot);
        Path stale = first.nativeDir.resolve("orphan.so.tmp-99999-deadbeef");
        Files.write(stale, new byte[]{0});
        Files.setLastModifiedTime(stale,
                FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));

        NativeExtractor.prepare(cacheRoot);

        assertFalse(Files.exists(stale));
    }

    @Test
    void prepareLeavesRecentTempFilesAlone(@TempDir Path cacheRoot) throws IOException {
        NativeExtractor.Prepared first = NativeExtractor.prepare(cacheRoot);
        // Recent .tmp-* could belong to a sibling process mid-extract.
        Path recent = first.nativeDir.resolve("inflight.so.tmp-12345-cafebabe");
        Files.write(recent, new byte[]{0});

        NativeExtractor.prepare(cacheRoot);

        assertTrue(Files.exists(recent));
    }

    private static Path firstPlatformNative(NativeExtractor.Prepared prepared) throws IOException {
        try (Stream<Path> stream = Files.list(prepared.nativeDir)) {
            List<Path> natives = stream
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .collect(Collectors.toList());
            assertFalse(natives.isEmpty());
            return natives.get(0);
        }
    }
}
