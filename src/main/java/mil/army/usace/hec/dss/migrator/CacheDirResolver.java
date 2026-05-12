package mil.army.usace.hec.dss.migrator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves a writable per-user cache directory used to stage extracted natives.
 * An explicit override via the {@code hec.dss.migrator.cacheDir} system property
 * (or {@code HEC_DSS_MIGRATOR_CACHE_DIR} env var) is tried first — this is the
 * escape hatch for locked-down sites where AppLocker/WDAC blocks DLL execution
 * out of {@code %LOCALAPPDATA%} and natives must live elsewhere. Otherwise the
 * platform default (LOCALAPPDATA / Library/Caches / XDG_CACHE_HOME / ~/.cache)
 * is tried, then {@code java.io.tmpdir}.
 *
 * <p>Extraction has no useful behavior if no directory is writable, so we
 * always pick a candidate that passes a write probe rather than failing on
 * the first denial.
 */
final class CacheDirResolver {

    private static final Logger LOGGER = Logger.getLogger(CacheDirResolver.class.getName());
    private static final String APP_NAME = "hec-dss-migrator";
    static final String CACHE_DIR_PROPERTY = "hec.dss.migrator.cacheDir";
    static final String CACHE_DIR_ENV = "HEC_DSS_MIGRATOR_CACHE_DIR";

    private CacheDirResolver() {
    }

    static Path resolve() throws IOException {
        return resolve(defaultCandidates());
    }

    /** Validates and canonicalizes a single explicit candidate; throws if it isn't writable. */
    static Path resolve(Path explicit) throws IOException {
        try {
            return prepare(explicit);
        } catch (SecurityException e) {
            throw new IOException("Cache directory not usable: " + explicit, e);
        }
    }

    static Path resolve(List<Path> candidates) throws IOException {
        IOException lastFailure = null;
        for (Path candidate : candidates) {
            try {
                return prepare(candidate);
            } catch (IOException | SecurityException e) {
                LOGGER.log(Level.WARNING,
                        "Cache directory not usable: " + candidate + " — trying next candidate", e);
                lastFailure = (e instanceof IOException) ? (IOException) e : new IOException(e);
            }
        }
        throw new IOException("No writable cache directory available; tried: " + candidates,
                lastFailure);
    }

    private static Path prepare(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (!Files.isDirectory(dir)) {
            throw new IOException("Cache path is not a directory: " + dir);
        }
        Path probe = Files.createTempFile(dir, ".probe-", ".tmp");
        try {
            Files.write(probe, new byte[]{0});
        } finally {
            MigratorPaths.deleteQuietly(probe);
        }
        // Canonicalize so symlinks/relative paths don't yield duplicate Session keys.
        return dir.toRealPath();
    }

    private static List<Path> defaultCandidates() {
        Set<Path> out = new LinkedHashSet<>();
        Path override = explicitOverride();
        if (override != null) out.add(override);
        out.add(platformDefault());
        out.add(tmpdirFallback());
        return new ArrayList<>(out);
    }

    private static Path explicitOverride() {
        String prop = nonEmpty(System.getProperty(CACHE_DIR_PROPERTY));
        if (prop != null) return Path.of(prop);
        String env = nonEmpty(System.getenv(CACHE_DIR_ENV));
        if (env != null) return Path.of(env);
        return null;
    }

    private static Path platformDefault() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", "");
        if (os.startsWith("windows")) {
            String localAppData = nonEmpty(System.getenv("LOCALAPPDATA"));
            return localAppData != null
                    ? Path.of(localAppData, APP_NAME)
                    : Path.of(home, "AppData", "Local", APP_NAME);
        }
        if (os.startsWith("mac")) {
            return Path.of(home, "Library", "Caches", APP_NAME);
        }
        String xdg = nonEmpty(System.getenv("XDG_CACHE_HOME"));
        if (xdg != null) return Path.of(xdg, APP_NAME);
        return home.isEmpty()
                ? Path.of(".", "." + APP_NAME)
                : Path.of(home, ".cache", APP_NAME);
    }

    private static Path tmpdirFallback() {
        String tmpdir = System.getProperty("java.io.tmpdir", ".");
        String user = System.getProperty("user.name", "anon");
        return Path.of(tmpdir, APP_NAME + "-" + MigratorPaths.sanitizeForPath(user));
    }

    private static String nonEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
