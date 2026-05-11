package mil.army.usace.hec.dss.migrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MigratorPathsTest {

    @Test
    void uniqueSuffixVariesAcrossCalls() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(MigratorPaths.uniqueSuffix()),
                    "uniqueSuffix collision at iteration " + i);
        }
    }

    @Test
    void filenameSansExtStripsLastExtension() {
        assertEquals("foo", MigratorPaths.filenameSansExt("foo.dss"));
        assertEquals("a.b.c", MigratorPaths.filenameSansExt("a.b.c.dss"));
        assertEquals("noext", MigratorPaths.filenameSansExt("noext"));
    }

    @Test
    void readOnlyStageRunsAgainstOriginalWhenStagingDisabled(@TempDir Path tmp) throws IOException {
        Path original = Files.writeString(tmp.resolve("file.dss"), "payload");
        Path stagingDir = Files.createDirectory(tmp.resolve("stage"));

        Path observed = MigratorPaths.withReadOnlyStage(
                original, stagingDir, false, null, working -> working);

        assertEquals(original, observed);
        assertTrue(Files.exists(original));
    }

    @Test
    void readOnlyStageCopiesToStageWhenForced(@TempDir Path tmp) throws IOException {
        Path original = Files.writeString(tmp.resolve("file.dss"), "payload");
        Path stagingDir = Files.createDirectory(tmp.resolve("stage"));

        Path observed = MigratorPaths.withReadOnlyStage(
                original, stagingDir, true, null,
                working -> {
                    assertNotEquals(original, working);
                    assertTrue(working.startsWith(stagingDir));
                    try {
                        assertEquals("payload", Files.readString(working));
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                    return working;
                });

        assertNotNull(observed);
        // The staged copy is cleaned up after the scan.
        assertFalse(Files.exists(observed));
        assertTrue(Files.exists(original));
        assertEquals("payload", Files.readString(original));
    }

    @Test
    void readOnlyStageReturnsErrorValueWhenSourceMissing(@TempDir Path tmp) throws IOException {
        Path missing = tmp.resolve("missing.dss");
        Path stagingDir = Files.createDirectory(tmp.resolve("stage"));

        String observed = MigratorPaths.withReadOnlyStage(
                missing, stagingDir, true, "ERROR",
                working -> "OK");

        assertEquals("ERROR", observed);
    }

    @Test
    void mutatingStageRunsAgainstOriginalWhenStagingDisabled(@TempDir Path tmp) throws IOException {
        Path original = Files.writeString(tmp.resolve("file.dss"), "v6");
        Path stagingDir = Files.createDirectory(tmp.resolve("stage"));

        MigrationResult result = MigratorPaths.withMutatingStage(
                original, stagingDir, false,
                working -> {
                    assertEquals(original, working);
                    return MigrationResult.MIGRATED;
                });

        assertEquals(MigrationResult.MIGRATED, result);
        assertTrue(Files.exists(original));
    }

    @Test
    void mutatingStageMovesBackOnMigrated(@TempDir Path tmp) throws IOException {
        Path original = Files.writeString(tmp.resolve("file.dss"), "v6");
        Path stagingDir = Files.createDirectory(tmp.resolve("stage"));

        MigrationResult result = MigratorPaths.withMutatingStage(
                original, stagingDir, true,
                working -> {
                    try {
                        Files.writeString(working, "v7");  // simulate the conversion
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                    return MigrationResult.MIGRATED;
                });

        assertEquals(MigrationResult.MIGRATED, result);
        assertEquals("v7", Files.readString(original));
        assertEquals(0, countStageFiles(stagingDir));
    }

    @Test
    void mutatingStageDeletesStageOnFailure(@TempDir Path tmp) throws IOException {
        Path original = Files.writeString(tmp.resolve("file.dss"), "v6");
        Path stagingDir = Files.createDirectory(tmp.resolve("stage"));

        MigrationResult result = MigratorPaths.withMutatingStage(
                original, stagingDir, true,
                working -> MigrationResult.FAILED);

        assertEquals(MigrationResult.FAILED, result);
        assertEquals("v6", Files.readString(original), "Original must be untouched on FAILED");
        assertEquals(0, countStageFiles(stagingDir));
    }

    @Test
    void mutatingStageReturnsAlreadyUpToDateWithoutMoveBack(@TempDir Path tmp) throws IOException {
        Path original = Files.writeString(tmp.resolve("file.dss"), "v7");
        Path stagingDir = Files.createDirectory(tmp.resolve("stage"));

        MigrationResult result = MigratorPaths.withMutatingStage(
                original, stagingDir, true,
                working -> MigrationResult.ALREADY_UP_TO_DATE);

        assertEquals(MigrationResult.ALREADY_UP_TO_DATE, result);
        assertEquals("v7", Files.readString(original));
        assertEquals(0, countStageFiles(stagingDir));
    }

    private static long countStageFiles(Path stagingDir) throws IOException {
        try (var s = Files.list(stagingDir)) {
            return s.count();
        }
    }

    @Test
    void validateAndNormalizeRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> MigratorPaths.validateAndNormalize(null));
    }

    @Test
    void validateAndNormalizeRejectsForeignFilesystem(@TempDir Path tmp) throws IOException {
        Path jarFile = tmp.resolve("dummy.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            zos.putNextEntry(new ZipEntry("inside.dss"));
            zos.closeEntry();
        }
        URI uri = URI.create("jar:" + jarFile.toUri());
        try (FileSystem fs = FileSystems.newFileSystem(uri, new HashMap<>())) {
            Path foreign = fs.getPath("/inside.dss");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> MigratorPaths.validateAndNormalize(foreign));
            assertTrue(ex.getMessage().contains("default-filesystem"),
                    "message: " + ex.getMessage());
        }
    }

    @Test
    void validateAndNormalizeReturnsAbsolutePathForRelativeInput() {
        // The default UnixPath / WindowsPath providers reject NUL at Path.of(), so
        // validateAndNormalize's own NUL guard is defence-in-depth for foreign Path
        // implementations and isn't naturally testable through Path.of() here.
        Path relative = Path.of("does-not-need-to-exist.dss");
        Path normalized = MigratorPaths.validateAndNormalize(relative);
        assertTrue(normalized.isAbsolute(), "expected absolute: " + normalized);
    }

    @Test
    void validateAndNormalizeNormalizesDotDotSegments(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("file.dss"), "x");
        Path detoured = tmp.resolve("sub").resolve("..").resolve("file.dss");
        Path normalized = MigratorPaths.validateAndNormalize(detoured);
        assertEquals(file.toRealPath(), normalized);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)  // symlink creation requires elevation on Windows
    void validateAndNormalizeFollowsSymlinks(@TempDir Path tmp) throws IOException {
        Path target = Files.writeString(tmp.resolve("real.dss"), "x");
        Path link = Files.createSymbolicLink(tmp.resolve("link.dss"), target);
        Path normalized = MigratorPaths.validateAndNormalize(link);
        assertEquals(target.toRealPath(), normalized);
    }

    @Test
    void validateAndNormalizeAcceptsNonExistentPath(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope.dss");
        Path normalized = MigratorPaths.validateAndNormalize(missing);
        assertTrue(normalized.isAbsolute());
        assertEquals(missing.toAbsolutePath().normalize(), normalized);
    }
}
