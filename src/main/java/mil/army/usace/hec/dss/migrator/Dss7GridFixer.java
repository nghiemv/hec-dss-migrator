package mil.army.usace.hec.dss.migrator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * Detects and repairs v7 DSS files that contain grid records still stored
 * in the v6 grid struct format ({@code gridStructVersion == 0}).
 *
 * <p>Some v7 files were produced by tools that did not upgrade embedded
 * grid records when the containing file was upgraded; downstream v7-only
 * consumers may reject those grids. This is a deliberately separate API
 * from {@link Dss7Migrator} — the migrator handles file-level v6→v7
 * conversion; this fixer handles the v6-grid-inside-v7 repair.
 *
 * <p><b>Threading, lifecycle, and path semantics:</b> identical to
 * {@link Dss7Migrator} — instances are safe to share across threads with
 * per-file serialization, are process-cached and need no explicit close, and
 * accept only default-filesystem regular-file paths (symlinks followed).
 */
public class Dss7GridFixer {

    private static final Logger LOGGER = Logger.getLogger(Dss7GridFixer.class.getName());

    private final IsolatedRuntime.Session session;

    private Dss7GridFixer(IsolatedRuntime.Session session) {
        this.session = session;
    }

    /** Fixer backed by the platform-default cache directory. */
    public static Dss7GridFixer usingDefaultCache() {
        return new Dss7GridFixer(IsolatedRuntime.acquireDefault());
    }

    /**
     * Fixer backed by {@code cacheDir} as the migrator cache root. Created if
     * missing and write-probed before use. See {@link Dss7Migrator#usingCache(Path)}
     * for the same caveats.
     */
    public static Dss7GridFixer usingCache(Path cacheDir) {
        return new Dss7GridFixer(IsolatedRuntime.acquireFor(cacheDir));
    }

    /**
     * True iff {@code pathToFile} is a v7 DSS file with at least one v6-format grid.
     * Returns false on missing files, non-regular files, or any I/O failure
     * (best-effort hint only — call {@link #fixVersion6Grids} directly when
     * correctness matters; it short-circuits to {@link MigrationResult#ALREADY_UP_TO_DATE}
     * on clean v7). Throws {@link IllegalArgumentException} for null inputs,
     * foreign-filesystem paths, or paths containing NUL.
     */
    public boolean hasVersion6Grids(Path pathToFile) {
        return MigratorPaths.runReadOnly(session, pathToFile, "hasVersion6Grids",
                false, this::doScan);
    }

    /**
     * Rewrites a v7 file with v6-format grids by round-tripping v7 → v6 → v7
     * through unique temp paths (the native lib caches version by filename, so
     * the temp paths must not be reused). Non-v7 input is rejected with
     * {@link MigrationResult#FAILED}; v7 files without v6 grids return
     * {@link MigrationResult#ALREADY_UP_TO_DATE} without doing the round-trip.
     *
     * <p>The input must reside on the default filesystem and must denote a regular
     * file. Symlinks are followed; the resolved target is what gets repaired.
     * Throws {@link IllegalArgumentException} for null inputs, foreign-filesystem
     * paths, or paths containing NUL. Throws {@link DssMigrationException} only in
     * the recovery-required case where the round-trip succeeded but the staged
     * result could not be moved back over the original.
     */
    public MigrationResult fixVersion6Grids(Path pathToFile) {
        return MigratorPaths.runMutating(session, pathToFile, "fixVersion6Grids", this::doFix);
    }

    /** Returns the javaHeclib version string from the isolated classloader. */
    public String getHeclibVersion() {
        return IsolatedRuntime.heclibVersion(session, LOGGER);
    }

    private boolean doScan(Path pathToFile) throws Exception {
        HecDssHandles h = session.handles;
        Object utilities = h.utilitiesCtor.newInstance();
        h.setDSSFileName.invoke(utilities, pathToFile.toString());
        int version = (int) h.getDssFileVersion.invoke(utilities);
        try {
            return version == 7 && scanForVersion6Grids(utilities);
        } finally {
            h.closeDSSFile.invoke(utilities);
        }
    }

    private MigrationResult doFix(Path pathToFile) throws Exception {
        HecDssHandles h = session.handles;

        Object probe = h.utilitiesCtor.newInstance();
        h.setDSSFileName.invoke(probe, pathToFile.toString());
        int version = (int) h.getDssFileVersion.invoke(probe);
        if (version != 7) {
            h.closeDSSFile.invoke(probe);
            LOGGER.warning(() -> "fixVersion6Grids requires v7 (got v" + version
                    + "): " + pathToFile + " — use Dss7Migrator.migrate() for v6→v7");
            return MigrationResult.FAILED;
        }
        boolean hasV6Grids = scanForVersion6Grids(probe);
        h.closeDSSFile.invoke(probe);
        if (!hasV6Grids) {
            return MigrationResult.ALREADY_UP_TO_DATE;
        }
        return roundTripThroughV6(pathToFile);
    }

    private MigrationResult roundTripThroughV6(Path pathToFile) throws Exception {
        HecDssHandles h = session.handles;
        String suffix = MigratorPaths.uniqueSuffix();
        Path v6Temp = MigratorPaths.siblingTempPath(pathToFile, "v6_" + suffix);
        Path v7Temp = MigratorPaths.siblingTempPath(pathToFile, "v7_" + suffix);
        Object utilities = h.utilitiesCtor.newInstance();

        int s1 = convertAndClose(utilities, pathToFile.toString(), v6Temp.toString());
        if (s1 != 0) {
            LOGGER.warning(() -> "v7→v6 failed (" + s1 + "): " + pathToFile);
            MigratorPaths.deleteQuietly(v6Temp);
            return MigrationResult.FAILED;
        }

        int s2 = convertAndClose(utilities, v6Temp.toString(), v7Temp.toString());
        MigratorPaths.deleteQuietly(v6Temp);
        if (s2 != 0) {
            LOGGER.warning(() -> "v6→v7 failed (" + s2 + "): " + pathToFile);
            MigratorPaths.deleteQuietly(v7Temp);
            return MigrationResult.FAILED;
        }

        Files.move(v7Temp, pathToFile, StandardCopyOption.REPLACE_EXISTING);
        return MigrationResult.MIGRATED;
    }

    private int convertAndClose(Object utilities, String src, String dst) throws Exception {
        HecDssHandles h = session.handles;
        h.setDSSFileName.invoke(utilities, src);
        int status = (int) h.convertVersion.invoke(utilities, dst);
        h.closeDSSFile.invoke(utilities);
        h.setDSSFileName.invoke(utilities, dst);
        h.closeDSSFile.invoke(utilities);
        return status;
    }

    private boolean scanForVersion6Grids(Object utilities) throws Exception {
        HecDssHandles h = session.handles;
        String[] catalog = (String[]) h.getCatalog.invoke(utilities, false, "");
        if (catalog == null || catalog.length == 0) {
            return false;
        }
        Object gd = h.griddedDataCtor.newInstance();
        h.gdSetDSSFileName.invoke(gd, h.dssFileName.invoke(utilities));
        for (String path : catalog) {
            int rt = (int) h.recordType.invoke(utilities, path);
            if (rt >= 400 && rt < 450) {
                h.gdSetPathname.invoke(gd, path);
                if ((int) h.gdGetGridStructVersion.invoke(gd) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    IsolatedClassLoader isolatedClassLoader() {
        return session.classLoader;
    }
}
