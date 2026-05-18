package mil.army.usace.hec.dss.migrator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forces the long-path staging code path on Linux/macOS so the
 * platform-conditional logic gets exercised by the test suite.
 */
class Dss7MigratorStagingTest {

    private static final Dss7Migrator migrator = Dss7Migrator.usingDefaultCache();
    private static final Dss7GridFixer fixer = Dss7GridFixer.usingDefaultCache();
    private String previousForceStaging;

    @BeforeEach
    void enableForceStaging() {
        previousForceStaging = System.getProperty(MigratorPaths.FORCE_STAGING_PROPERTY);
        System.setProperty(MigratorPaths.FORCE_STAGING_PROPERTY, "true");
    }

    @AfterEach
    void restoreForceStaging() {
        if (previousForceStaging == null) {
            System.clearProperty(MigratorPaths.FORCE_STAGING_PROPERTY);
        } else {
            System.setProperty(MigratorPaths.FORCE_STAGING_PROPERTY, previousForceStaging);
        }
    }

    @Test
    void migrateAlreadyV7ReturnsAlreadyUpToDateThroughStaging() throws Exception {
        Path tempFile = Files.createTempFile("staging-v7-", ".dss");
        try {
            TestSupport.createEmptyV7File(migrator, tempFile);
            assertEquals(MigrationResult.ALREADY_UP_TO_DATE, migrator.migrate(tempFile));
            assertTrue(Files.exists(tempFile));
            assertNoStageLeftovers();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void migrateMissingFileReturnsFailedWithoutStaging() {
        Path missing = Path.of("/tmp/does-not-exist-" + System.nanoTime() + ".dss");
        assertEquals(MigrationResult.FAILED, migrator.migrate(missing));
        assertNoStageLeftovers();
    }

    @Test
    void fixVersion6GridsRoundTripsThroughStaging() throws Exception {
        Path source = Path.of(Dss7MigratorStagingTest.class.getClassLoader()
                .getResource("paramgrids.dss").toURI());
        Path tempFile = Files.createTempFile("staging-paramgrids-", ".dss");
        Files.copy(source, tempFile, StandardCopyOption.REPLACE_EXISTING);
        try {
            assertTrue(fixer.hasVersion6Grids(tempFile));
            assertEquals(MigrationResult.MIGRATED, fixer.fixVersion6Grids(tempFile));
            assertFalse(fixer.hasVersion6Grids(tempFile));
            assertTrue(Files.exists(tempFile));
            assertNoStageLeftovers();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static void assertNoStageLeftovers() {
        Path stagingDir = ((Dss7Migrator) migrator).isolatedClassLoader() != null
                ? stagingDirOf(migrator) : null;
        if (stagingDir == null || !Files.isDirectory(stagingDir)) return;
        try (Stream<Path> entries = Files.list(stagingDir)) {
            long count = entries.filter(p -> p.getFileName().toString().startsWith("stage_")).count();
            assertEquals(0, count, "Expected no leftover stage_ files in " + stagingDir);
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static Path stagingDirOf(Dss7Migrator m) {
        try {
            var sessionField = Dss7Migrator.class.getDeclaredField("session");
            sessionField.setAccessible(true);
            Object session = sessionField.get(m);
            var stagingField = session.getClass().getDeclaredField("stagingDir");
            stagingField.setAccessible(true);
            return (Path) stagingField.get(session);
        } catch (Exception e) {
            return null;
        }
    }
}
