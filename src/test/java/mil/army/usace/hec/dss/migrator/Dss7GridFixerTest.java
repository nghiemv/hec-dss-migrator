package mil.army.usace.hec.dss.migrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

class Dss7GridFixerTest {

    private static final Dss7GridFixer fixer = Dss7GridFixer.usingDefaultCache();
    private static final Dss7Migrator migrator = Dss7Migrator.usingDefaultCache();

    @Test
    void fixVersion6GridsIsNoOpOnCleanV7File() throws Exception {
        Path tempFile = Files.createTempFile("clean-v7-", ".dss");
        try {
            TestSupport.createEmptyV7File(migrator, tempFile);
            assertEquals(MigrationResult.ALREADY_UP_TO_DATE, fixer.fixVersion6Grids(tempFile));
            assertFalse(fixer.hasVersion6Grids(tempFile));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void detectsAndFixesV6GridsInsideV7File() throws Exception {
        Path source = Path.of(Dss7GridFixerTest.class.getClassLoader()
                .getResource("paramgrids.dss").toURI());
        Path tempFile = Files.createTempFile("paramgrids-test-", ".dss");
        Files.copy(source, tempFile, StandardCopyOption.REPLACE_EXISTING);
        try {
            assertTrue(fixer.hasVersion6Grids(tempFile));

            // migrate() must stay in its lane: a v7 file is already up-to-date.
            assertEquals(MigrationResult.ALREADY_UP_TO_DATE, migrator.migrate(tempFile));
            assertTrue(fixer.hasVersion6Grids(tempFile));

            assertEquals(MigrationResult.MIGRATED, fixer.fixVersion6Grids(tempFile));
            assertFalse(fixer.hasVersion6Grids(tempFile));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void returnsFalseAndFailedForNonExistentFile() {
        Path missing = Path.of("/tmp/does-not-exist-" + System.nanoTime() + ".dss");
        assertFalse(fixer.hasVersion6Grids(missing));
        assertEquals(MigrationResult.FAILED, fixer.fixVersion6Grids(missing));
    }

    @Test
    void migratorAndFixerShareIsolatedClassLoader() {
        assertSame(migrator.isolatedClassLoader(), fixer.isolatedClassLoader());
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(NullPointerException.class, () -> fixer.hasVersion6Grids(null));
        assertThrows(NullPointerException.class, () -> fixer.fixVersion6Grids(null));
    }

    @Test
    void returnsFalseAndFailedForDirectoryInput(@TempDir Path tmp) {
        assertFalse(fixer.hasVersion6Grids(tmp));
        assertEquals(MigrationResult.FAILED, fixer.fixVersion6Grids(tmp));
    }

    @Test
    void getHeclibVersionMatchesMigrator() {
        assertEquals(migrator.getHeclibVersion(), fixer.getHeclibVersion());
    }
}
