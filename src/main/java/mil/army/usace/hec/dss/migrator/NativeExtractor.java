package mil.army.usace.hec.dss.migrator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Reads the build-time manifest, streams resources via the classloader, and
 * stages natives into a versioned cache with SHA-256 verification. Going
 * through {@code getResourceAsStream} (never opening the outer jar by path)
 * makes this work the same when shaded into a host fat jar, nested in a
 * Spring Boot layout, or run from an exploded test classpath.
 */
final class NativeExtractor {

    private static final Logger LOGGER = Logger.getLogger(NativeExtractor.class.getName());

    static final String RESOURCE_ROOT = "mil/army/usace/hec/dss/migrator";
    static final String MANIFEST_PATH = RESOURCE_ROOT + "/manifest.properties";
    static final String NATIVE_PREFIX = "native/";
    static final String ISOLATED_PREFIX = "isolated/";

    private static final String MANIFEST_VERSION_KEY = "version";
    private static final long STALE_TEMP_CUTOFF_MS = TimeUnit.HOURS.toMillis(1);

    private NativeExtractor() {
    }

    static Prepared prepare(Path cacheRoot) throws IOException {
        Properties manifest = loadManifest();
        String version = manifest.getProperty(MANIFEST_VERSION_KEY, "dev");
        String platform = detectPlatform();
        Path leaf = cacheRoot.resolve(MigratorPaths.sanitizeForPath(version)).resolve(platform);
        Files.createDirectories(leaf);

        Map<String, String> expectedNatives = manifestSubset(manifest, NATIVE_PREFIX + platform + "/");
        if (expectedNatives.isEmpty()) {
            throw new IOException("Manifest declares no natives for platform " + platform);
        }

        // Cross-process lock so two cold-start JVMs don't both extract; sweep
        // .tmp-* leftovers from a previously crashed JVM while we hold it.
        try (FileChannel ch = FileChannel.open(leaf.resolve(".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = ch.lock()) {
            sweepStaleTempFiles(leaf);
            for (Map.Entry<String, String> e : expectedNatives.entrySet()) {
                Path target = leaf.resolve(lastSegment(e.getKey()));
                if (Files.exists(target) && sha256(target).equals(e.getValue())) {
                    continue;
                }
                extractResourceTo(e.getKey(), target, e.getValue());
            }
        }

        Map<String, String> expectedJars = manifestSubset(manifest, ISOLATED_PREFIX);
        List<byte[]> isolatedJarBytes = new ArrayList<>(expectedJars.size());
        for (Map.Entry<String, String> e : expectedJars.entrySet()) {
            byte[] bytes = readResource(e.getKey());
            verifyHash(e.getValue(), sha256(bytes), "isolated jar", e.getKey());
            isolatedJarBytes.add(bytes);
        }
        Path stagingDir = leaf.resolve("staging");
        Files.createDirectories(stagingDir);
        sweepStaleStageFiles(stagingDir);
        return new Prepared(leaf, stagingDir, isolatedJarBytes);
    }

    static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "");
        if (os.startsWith("windows")) return "win-x86_64";
        if (os.startsWith("linux")) return "linux-x86_64";
        if (os.startsWith("mac")) {
            if ("aarch64".equals(arch) || "arm64".equals(arch)) {
                LOGGER.warning("Apple Silicon (aarch64): native may require Rosetta 2.");
            }
            return "macOS-x86_64";
        }
        if (os.startsWith("sunos")) return "SunOS-SPARC_64";
        throw new UnsupportedOperationException("Unsupported platform: os=" + os + " arch=" + arch);
    }

    private static void sweepStaleTempFiles(Path leaf) {
        sweepOlderThan(leaf, name -> name.contains(".tmp-"));
    }

    private static void sweepStaleStageFiles(Path stagingDir) {
        sweepOlderThan(stagingDir, name -> name.startsWith("stage_"));
    }

    private static void sweepOlderThan(Path dir, Predicate<String> matchName) {
        long cutoff = System.currentTimeMillis() - STALE_TEMP_CUTOFF_MS;
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(p -> matchName.test(p.getFileName().toString()))
                    .filter(p -> mtimeBefore(p, cutoff))
                    .forEach(MigratorPaths::deleteQuietly);
        } catch (IOException ignore) {
            // sweep is opportunistic
        }
    }

    private static boolean mtimeBefore(Path p, long cutoff) {
        try {
            return Files.getLastModifiedTime(p).toMillis() < cutoff;
        } catch (IOException ignore) {
            return false;
        }
    }

    private static Properties loadManifest() throws IOException {
        ClassLoader cl = NativeExtractor.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(MANIFEST_PATH)) {
            if (is == null) {
                throw new IOException("Manifest not found: " + MANIFEST_PATH
                        + " — was the jar built with generateManifest?");
            }
            Properties props = new Properties();
            props.load(is);
            return props;
        }
    }

    private static Map<String, String> manifestSubset(Properties props, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                out.put(key, props.getProperty(key));
            }
        }
        return out;
    }

    private static byte[] readResource(String resourceKey) throws IOException {
        try (InputStream is = openResource(RESOURCE_ROOT + "/" + resourceKey)) {
            return is.readAllBytes();
        }
    }

    private static void extractResourceTo(String resourceKey, Path target, String expectedHash)
            throws IOException {
        String path = RESOURCE_ROOT + "/" + resourceKey;
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + MigratorPaths.uniqueSuffix());
        try (InputStream is = openResource(path)) {
            Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
            verifyHash(expectedHash, sha256(tmp), "native", path);
            makeExecutableIfNative(tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                // ATOMIC_MOVE not supported on some filesystems; identical bytes make this safe.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            MigratorPaths.deleteQuietly(tmp);
        }
    }

    private static InputStream openResource(String fullPath) throws IOException {
        InputStream is = NativeExtractor.class.getClassLoader().getResourceAsStream(fullPath);
        if (is == null) throw new IOException("Resource not found: " + fullPath);
        return is;
    }

    private static void verifyHash(String expected, String actual, String kind, String identifier)
            throws IOException {
        if (!actual.equals(expected)) {
            throw new IOException("Corrupted " + kind + ": " + identifier
                    + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest md = newSha256();
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
        }
        return toHex(md.digest());
    }

    private static String sha256(byte[] bytes) {
        MessageDigest md = newSha256();
        md.update(bytes);
        return toHex(md.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static void makeExecutableIfNative(Path path) {
        String name = path.getFileName().toString();
        if ((name.endsWith(".so") || name.endsWith(".dylib") || name.endsWith(".jnilib"))
                && !path.toFile().setExecutable(true)) {
            LOGGER.warning("Could not set executable bit on: " + path);
        }
    }

    static final class Prepared {
        final Path nativeDir;
        final Path stagingDir;
        final List<byte[]> isolatedJarBytes;

        Prepared(Path nativeDir, Path stagingDir, List<byte[]> isolatedJarBytes) {
            this.nativeDir = nativeDir;
            this.stagingDir = stagingDir;
            this.isolatedJarBytes = isolatedJarBytes;
        }
    }
}
