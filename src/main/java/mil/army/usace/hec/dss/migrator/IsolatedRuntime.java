package mil.army.usace.hec.dss.migrator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-cache-root {@link Session} cache shared by every public migrator type.
 * A stable native path can only be loaded into one classloader; routing all
 * acquisition through this cache is what prevents UnsatisfiedLinkError on
 * the second {@code System.loadLibrary} call.
 */
final class IsolatedRuntime {

    private static final Map<Path, Session> CACHE = new HashMap<>();

    private IsolatedRuntime() {
    }

    static Session acquireDefault() {
        try {
            return getOrCreate(CacheDirResolver.resolve());
        } catch (IOException e) {
            throw new DssMigrationException("Failed to prepare migrator cache", e);
        }
    }

    static Session acquireFor(Path cacheDir) {
        try {
            return getOrCreate(CacheDirResolver.resolve(cacheDir));
        } catch (IOException e) {
            throw new DssMigrationException("Failed to prepare migrator cache at " + cacheDir, e);
        }
    }

    private static synchronized Session getOrCreate(Path resolvedRoot) throws IOException {
        Session existing = CACHE.get(resolvedRoot);
        if (existing != null) return existing;

        NativeExtractor.Prepared prepared = NativeExtractor.prepare(resolvedRoot);
        ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();
        IsolatedClassLoader classLoader = IsolatedClassLoader.fromJarBytes(
                prepared.isolatedJarBytes, parent, prepared.nativeDir);
        try {
            Session created = new Session(
                    classLoader, new HecDssHandles(classLoader), prepared.stagingDir);
            CACHE.put(resolvedRoot, created);
            return created;
        } catch (ReflectiveOperationException e) {
            throw new IOException("Failed to bind reflection handles", e);
        }
    }

    /** Runs {@code op} with the context CL set to the session's isolated CL. */
    static <T> T run(Session session, Op<T> op, T errorValue, Logger logger, String contextMsg) {
        Thread t = Thread.currentThread();
        ClassLoader original = t.getContextClassLoader();
        try {
            t.setContextClassLoader(session.classLoader);
            return op.run();
        } catch (Exception e) {
            logger.log(Level.SEVERE, contextMsg, e);
            return errorValue;
        } finally {
            t.setContextClassLoader(original);
        }
    }

    /** Returns the javaHeclib version string from the session's isolated classloader. */
    static String heclibVersion(Session session, Logger logger) {
        String result = run(session, () -> {
            HecDssHandles h = session.handles;
            Object sc = h.stringContainerCtor.newInstance();
            h.zquery.invoke(null, "vers", sc, new int[1]);
            return (String) h.stringField.get(sc);
        }, null, logger, "Failed to query heclib version");
        if (result == null) {
            throw new DssMigrationException("Failed to query heclib version");
        }
        return result;
    }

    @FunctionalInterface
    interface Op<T> {
        T run() throws Exception;
    }

    static final class Session {
        final IsolatedClassLoader classLoader;
        final HecDssHandles handles;
        final Path stagingDir;

        Session(IsolatedClassLoader classLoader, HecDssHandles handles, Path stagingDir) {
            this.classLoader = classLoader;
            this.handles = handles;
            this.stagingDir = stagingDir;
        }
    }
}
