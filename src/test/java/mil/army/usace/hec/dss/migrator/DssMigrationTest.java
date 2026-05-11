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
}
