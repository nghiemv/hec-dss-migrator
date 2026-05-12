package mil.army.usace.hec.dss.migrator;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Filename and staging helpers shared by the migrator and the grid fixer.
 *
 * <p>Native heclib uses the C runtime, which on Windows is bounded by
 * {@code MAX_PATH=260}. Java NIO uses the wide-char Win32 APIs and is
 * long-path-safe, so when the user-supplied path would overrun the CRT
 * we copy into a short-named stage under the migrator's cache, run the
 * conversion against the stage, then move the result back to the original.
 */
final class MigratorPaths {

    private static final Logger LOGGER = Logger.getLogger(MigratorPaths.class.getName());

    /**
     * Conservative path-length ceiling used across all platforms. Heclib uses
     * the C runtime for file I/O, which imposes limits on every OS (260 on
     * Windows, 1024 on macOS, 4096 on Linux). 240 is safely under all three
     * and leaves room for the {@code _<pid>_<rand>.dss} suffix we append.
     */
    private static final int HECLIB_SAFE_PATH_LEN = 240;

    /** Set {@code -Dhec.dss.migrator.forceStaging=true} to always stage, regardless of path length. */
    static final String FORCE_STAGING_PROPERTY = "hec.dss.migrator.forceStaging";

    private MigratorPaths() {
    }

    /** Operation invoked against a (possibly staged) DSS file inside the isolated runtime. */
    @FunctionalInterface
    interface DssOp<T> {
        T run(Path working) throws Exception;
    }

    /**
     * Validates a caller-supplied path and returns its canonical, absolute form.
     * Throws {@link IllegalArgumentException} for inputs that cannot be migrated
     * regardless of file state — null, foreign filesystems (Jimfs, zipfs, ...),
     * empty paths, paths containing NUL bytes. Existing paths are resolved to
     * their real on-disk location (symlinks followed); non-existent paths are
     * returned absolute-and-normalized so downstream existence checks can decide
     * how to handle them.
     */
    static Path validateAndNormalize(Path input) {
        Objects.requireNonNull(input, "pathToFile");
        if (input.getFileSystem() != FileSystems.getDefault()) {
            throw new IllegalArgumentException(
                    "default-filesystem paths only (got: "
                            + input.getFileSystem().provider().getScheme() + ")");
        }
        String s = input.toString();
        if (s.isEmpty() || s.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "invalid path: " + (s.isEmpty() ? "<empty>" : "contains NUL"));
        }
        Path abs = input.toAbsolutePath().normalize();
        try {
            return Files.exists(abs) ? abs.toRealPath() : abs;
        } catch (IOException e) {
            return abs;
        }
    }

    /**
     * Validates {@code input}, rejects non-regular files with {@link MigrationResult#FAILED},
     * stages if the path is too long for the C runtime, and runs {@code op} inside
     * the session's isolated classloader. Move-back failures after a successful
     * migration propagate as {@link DssMigrationException}.
     */
    static MigrationResult runMutating(IsolatedRuntime.Session session, Path input,
                                       String label, DssOp<MigrationResult> op) {
        Path p = validateAndNormalize(input);
        if (!Files.isRegularFile(p)) {
            LOGGER.warning(() -> label + ": not a regular file: " + p);
            return MigrationResult.FAILED;
        }
        return withMutatingStage(p, session.stagingDir, needsStaging(p),
                working -> IsolatedRuntime.run(session,
                        () -> op.run(working),
                        MigrationResult.FAILED, LOGGER, label + " failed for: " + p));
    }

    /**
     * Read-only counterpart to {@link #runMutating}. Stages a copy when the input
     * path is too long for the C runtime, runs {@code op} inside the session's
     * isolated classloader, and deletes the copy afterward. The original is never
     * modified.
     */
    static <T> T runReadOnly(IsolatedRuntime.Session session, Path input,
                             String label, T errorValue, DssOp<T> op) {
        Path p = validateAndNormalize(input);
        if (!Files.isRegularFile(p)) {
            LOGGER.warning(() -> label + ": not a regular file: " + p);
            return errorValue;
        }
        return withReadOnlyStage(p, session.stagingDir, needsStaging(p), errorValue,
                working -> IsolatedRuntime.run(session,
                        () -> op.run(working),
                        errorValue, LOGGER, label + " failed for: " + p));
    }

    static String uniqueSuffix() {
        return ProcessHandle.current().pid() + "_"
                + Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    static String filenameSansExt(String filename) {
        return filename.replaceFirst("[.][^.]+$", "");
    }

    /** Replaces filesystem-unfriendly characters in {@code s} with {@code '-'}. */
    static String sanitizeForPath(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    /**
     * Builds a sibling path of {@code original} with a {@code .dss} extension
     * and {@code discriminator} appended after the stem (e.g. a unique suffix,
     * or {@code "v6_<suffix>"} to share a suffix across related siblings).
     */
    static Path siblingTempPath(Path original, String discriminator) {
        String stem = filenameSansExt(original.getFileName().toString());
        return original.resolveSibling(stem + "_" + discriminator + ".dss");
    }

    /** Deletes {@code p} if it exists, swallowing IOExceptions. */
    static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignore) {}
    }

    /** True iff the path is long enough that heclib's C-runtime I/O may reject it. */
    static boolean needsStaging(Path original) {
        if (Boolean.getBoolean(FORCE_STAGING_PROPERTY)) return true;
        return original.toAbsolutePath().toString().length() > HECLIB_SAFE_PATH_LEN;
    }

    /**
     * Runs {@code scan} against the original file or, when {@code stage} is true,
     * a short-named copy that is deleted afterward. Read-only — never moves
     * anything back.
     */
    static <T> T withReadOnlyStage(Path original, Path stagingDir, boolean stage,
                                   T errorValue, Function<Path, T> scan) {
        if (!stage) return scan.apply(original);
        Path stagePath = newStagePath(stagingDir);
        try {
            Files.copy(original, stagePath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            return scan.apply(stagePath);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to stage long-path file: " + original, e);
            return errorValue;
        } finally {
            deleteQuietly(stagePath);
        }
    }

    /**
     * Runs {@code op} against the original file or, when {@code stage} is true,
     * a short-named copy. On {@link MigrationResult#MIGRATED} the staged
     * result is moved back over the original (REPLACE_EXISTING). If the
     * move-back fails after a successful migration, the staged result is
     * preserved on disk so the user can recover, and the call throws
     * {@link DssMigrationException} carrying the staged path — this case
     * requires manual intervention and must not be silently collapsed into
     * {@link MigrationResult#FAILED}.
     */
    static MigrationResult withMutatingStage(Path original, Path stagingDir, boolean stage,
                                             Function<Path, MigrationResult> op) {
        if (!stage) return op.apply(original);
        Path stagePath = newStagePath(stagingDir);
        boolean preserveStage = false;
        try {
            try {
                Files.copy(original, stagePath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to stage long-path file: " + original, e);
                return MigrationResult.FAILED;
            }
            MigrationResult result = op.apply(stagePath);
            if (result == MigrationResult.MIGRATED) {
                try {
                    Files.move(stagePath, original, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    preserveStage = true;
                    throw new DssMigrationException(
                            "Migration succeeded but move-back failed for: " + original
                                    + " — staged result preserved at: " + stagePath, e);
                }
            }
            return result;
        } finally {
            if (!preserveStage) {
                deleteQuietly(stagePath);
            }
        }
    }

    private static Path newStagePath(Path stagingDir) {
        return stagingDir.resolve("stage_" + uniqueSuffix() + ".dss");
    }
}
