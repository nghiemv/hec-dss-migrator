package mil.army.usace.hec.dss.migrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DssMigrationTest {

    private static final Dss7Migrator migrator = Dss7Migrator.usingDefaultCache();

    @Test
    void canInitializeAndQueryVersion() {
        String version = migrator.getHeclibVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
    }

    @Test
    void migrateReturnsFailedForNonExistentFile() {
        assertEquals(MigrationResult.FAILED, migrator.migrate(
                Path.of("/tmp/does-not-exist-" + System.nanoTime() + ".dss")));
    }

    @Test
    void contextClassLoaderRestoredAfterOperations() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        migrator.getHeclibVersion();
        assertSame(original, Thread.currentThread().getContextClassLoader());
        migrator.migrate(Path.of("/tmp/does-not-exist-" + System.nanoTime() + ".dss"));
        assertSame(original, Thread.currentThread().getContextClassLoader());
    }

    @Test
    void migrateReturnsAlreadyUpToDateForVersion7() throws Exception {
        Path tempFile = Files.createTempFile("dss-migrator-test-", ".dss");
        try {
            TestSupport.createEmptyV7File(migrator, tempFile);
            assertEquals(MigrationResult.ALREADY_UP_TO_DATE, migrator.migrate(tempFile));
            assertTrue(Files.exists(tempFile));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void migrateRejectsNullInput() {
        assertThrows(NullPointerException.class, () -> migrator.migrate(null));
    }

    @Test
    void migrateReturnsFailedForDirectoryInput(@TempDir Path tmp) {
        assertEquals(MigrationResult.FAILED, migrator.migrate(tmp));
    }

    @Test
    void migrateEmptyV6FileProducesV7AndKeepsFileOnDisk(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("empty-v6.dss");
        TestSupport.createEmptyV6File(migrator, file);
        long sizeBeforeMigrate = Files.size(file);

        assertEquals(MigrationResult.MIGRATED, migrator.migrate(file));

        // The bug fix matters here: the recreate path no longer deletes-then-creates,
        // so a forced exit mid-operation would still leave a file at the original path.
        assertTrue(Files.exists(file), "Original path must hold a file after migrate()");
        // v7 files have a different header/size than v6; confirming a size change
        // tells us we got v7 bytes on disk (the native version cache makes a
        // second migrate() unreliable as a v7 check on the same path).
        assertNotEquals(sizeBeforeMigrate, Files.size(file),
                "Expected v6 file to be rewritten as v7 on disk");
    }
}
