package mil.army.usace.hec.dss.migrator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * DSS 6→7 file migration via an isolated classloader. Bundles its own copy
 * of javaHeclib so it does not conflict with any version already loaded by
 * the host application.
 *
 * <p>Repairing v7 files that contain residual v6-format grid records is handled
 * by the separate {@link Dss7GridFixer}.
 *
 * <p><b>Threading:</b> instances are safe to share across threads. The underlying
 * native library serializes file I/O internally, so calls on distinct files
 * proceed concurrently. Concurrent calls on the same {@code Path} are racy and
 * unsupported — callers must serialize per file.
 *
 * <p><b>Lifecycle:</b> the underlying isolated runtime is process-cached and
 * shared across all instances built with the same {@code cacheDir}, so
 * constructing additional {@code Dss7Migrator} instances is cheap and there is
 * nothing to close.
 *
 * <p><b>Path semantics:</b> inputs to {@link #migrate(Path)} must reside on the
 * default filesystem and must denote regular files. Symlinks are followed; the
 * resolved target is what gets migrated.
 */
public class Dss7Migrator {

    private static final Logger LOGGER = Logger.getLogger(Dss7Migrator.class.getName());

    private final IsolatedRuntime.Session session;

    private Dss7Migrator(IsolatedRuntime.Session session) {
        this.session = session;
    }

    /**
     * Migrator backed by the platform-default cache directory
     * ({@code %LOCALAPPDATA%}, {@code ~/Library/Caches}, {@code ~/.cache}, ...).
     * For locked-down environments where natives must live elsewhere — typically
     * AppLocker / WDAC sites — use {@link #usingCache(Path)} instead.
     */
    public static Dss7Migrator usingDefaultCache() {
        return new Dss7Migrator(IsolatedRuntime.acquireDefault());
    }

    /**
     * Migrator backed by {@code cacheDir} as the migrator cache root. Created if
     * missing, write-probed before use, and canonicalized so symlinks share one
     * underlying session. Throws {@link DssMigrationException} if the directory
     * isn't writable — caller is expected to pick a path the JVM can write to
     * <em>and</em> that the OS will let us {@code LoadLibrary} from.
     */
    public static Dss7Migrator usingCache(Path cacheDir) {
        return new Dss7Migrator(IsolatedRuntime.acquireFor(cacheDir));
    }

    /**
     * Migrates a v6 DSS file to v7. v7 files yield {@link MigrationResult#ALREADY_UP_TO_DATE};
     * empty v6 files are recreated as empty v7; non-empty v6 files are converted via a
     * temp file that atomically replaces the original. Paths longer than the C runtime
     * can address on Windows are staged into the migrator cache and the result is
     * moved back over the original.
     *
     * <p>The input must reside on the default filesystem and must denote a regular
     * file. Symlinks are followed; the resolved target is what gets migrated.
     * Throws {@link IllegalArgumentException} for null inputs, foreign-filesystem
     * paths, or paths containing NUL. Throws {@link DssMigrationException} only in
     * the recovery-required case where conversion succeeded but the staged result
     * could not be moved back over the original — the staged file path is in the
     * exception message.
     */
    public MigrationResult migrate(Path pathToFile) {
        return MigratorPaths.runMutating(session, pathToFile, "migrate", this::doMigrate);
    }

    /** Returns the javaHeclib version string from the isolated classloader. */
    public String getHeclibVersion() {
        return IsolatedRuntime.heclibVersion(session, LOGGER);
    }

    private MigrationResult doMigrate(Path pathToFile) throws Exception {
        String fileName = pathToFile.toString();
        HecDssHandles h = session.handles;
        int version = h.peekVersion(fileName);
        if (version == 7) {
            return MigrationResult.ALREADY_UP_TO_DATE;
        }
        if (version < 6) {
            LOGGER.warning(() -> "Unsupported DSS version " + version + ": " + fileName);
            return MigrationResult.FAILED;
        }
        return h.withUtilities(fileName, utilities -> {
            int recordCount = (int) h.getNumberRecords.invoke(utilities);
            return recordCount == 0
                    ? recreateEmptyAsV7(pathToFile, utilities)
                    : convertNonEmptyV6(pathToFile, utilities);
        });
    }

    private MigrationResult recreateEmptyAsV7(Path pathToFile, Object utilities) throws Exception {
        HecDssHandles h = session.handles;
        h.closeDSSFile.invoke(utilities);
        Files.delete(pathToFile);
        Object manager = h.managerCtor.newInstance(pathToFile.toString());
        try {
            h.managerOpen.invoke(manager);
        } finally {
            try { h.managerClose.invoke(manager); } catch (Exception ignore) {}
        }
        return MigrationResult.MIGRATED;
    }

    private MigrationResult convertNonEmptyV6(Path pathToFile, Object utilities) throws Exception {
        HecDssHandles h = session.handles;
        Path tempPath = MigratorPaths.siblingTempPath(pathToFile, MigratorPaths.uniqueSuffix());

        int status = h.convertAndClose(utilities, pathToFile.toString(), tempPath.toString());

        if (status != 0) {
            LOGGER.warning(() -> "Convert failed (" + status + "): " + pathToFile);
            MigratorPaths.deleteQuietly(tempPath);
            return MigrationResult.FAILED;
        }
        // Single atomic step — never leaves the user with no file at the original path.
        Files.move(tempPath, pathToFile, StandardCopyOption.REPLACE_EXISTING);
        return MigrationResult.MIGRATED;
    }

    IsolatedClassLoader isolatedClassLoader() {
        return session.classLoader;
    }
}
