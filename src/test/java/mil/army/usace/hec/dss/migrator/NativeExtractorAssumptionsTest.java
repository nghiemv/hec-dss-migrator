package mil.army.usace.hec.dss.migrator;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Catches packaging regressions: dropped platform natives, renamed jars, missing manifest entries. */
class NativeExtractorAssumptionsTest {

    private static final Set<String> BUNDLED_PLATFORMS = Set.of(
            "win-x86_64", "linux-x86_64", "macOS-x86_64");

    private static final List<String> EXPECTED_ISOLATED_JARS = List.of(
            "hec-monolith-slim", "flogger-", "flogger-system-backend-",
            "hec-nucleus-metadata-", "hec-nucleus-data-", "lookup-");

    @Test
    void detectedPlatformIsBundled() {
        assertTrue(BUNDLED_PLATFORMS.contains(NativeExtractor.detectPlatform()));
    }

    @Test
    void manifestIsPresentAndParseable() throws IOException {
        assertNotNull(loadManifest().getProperty("version"));
    }

    @Test
    void manifestListsNativesForEveryBundledPlatform() throws IOException {
        Properties props = loadManifest();
        for (String platform : BUNDLED_PLATFORMS) {
            String prefix = NativeExtractor.NATIVE_PREFIX + platform + "/";
            assertTrue(props.stringPropertyNames().stream().anyMatch(k -> k.startsWith(prefix)),
                    "No native entries for platform: " + platform);
        }
    }

    @Test
    void manifestListsAllExpectedIsolatedJars() throws IOException {
        List<String> jars = loadManifest().stringPropertyNames().stream()
                .filter(k -> k.startsWith(NativeExtractor.ISOLATED_PREFIX))
                .map(k -> k.substring(NativeExtractor.ISOLATED_PREFIX.length()))
                .collect(Collectors.toList());
        assertFalse(jars.isEmpty());
        for (String expected : EXPECTED_ISOLATED_JARS) {
            assertTrue(jars.stream().anyMatch(j -> j.startsWith(expected)),
                    "Missing jar starting with: " + expected + " — got: " + jars);
        }
    }

    @Test
    void everyManifestEntryIsLoadableViaClassLoader() throws IOException {
        Properties props = loadManifest();
        ClassLoader cl = NativeExtractor.class.getClassLoader();
        for (String key : props.stringPropertyNames()) {
            if ("version".equals(key)) continue;
            String path = NativeExtractor.RESOURCE_ROOT + "/" + key;
            try (InputStream is = cl.getResourceAsStream(path)) {
                assertNotNull(is, "Not loadable: " + path);
            }
        }
    }

    @Test
    void allThreePlatformNativesPresentInPackaging() throws IOException {
        for (String platform : BUNDLED_PLATFORMS) {
            String prefix = NativeExtractor.RESOURCE_ROOT + "/"
                    + NativeExtractor.NATIVE_PREFIX + platform + "/";
            assertFalse(resourcesStartingWith(prefix).isEmpty(),
                    "No natives for: " + platform);
        }
    }

    private static Properties loadManifest() throws IOException {
        ClassLoader cl = NativeExtractor.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(NativeExtractor.MANIFEST_PATH)) {
            assertNotNull(is, "Manifest not found");
            Properties props = new Properties();
            props.load(is);
            return props;
        }
    }

    private static List<String> resourcesStartingWith(String prefix) throws IOException {
        URL location = NativeExtractor.class.getProtectionDomain().getCodeSource().getLocation();
        if (location.getFile().endsWith(".jar")) {
            try (JarFile jar = new JarFile(location.getFile())) {
                return jar.stream().map(JarEntry::getName)
                        .filter(n -> n.startsWith(prefix) && !n.endsWith("/"))
                        .collect(Collectors.toList());
            }
        }
        URL dirUrl = NativeExtractor.class.getClassLoader().getResource(prefix);
        assertNotNull(dirUrl, "Resource not on classpath: " + prefix);
        Path dir;
        try {
            dir = Path.of(dirUrl.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("Invalid resource URL: " + dirUrl, e);
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(p -> !Files.isDirectory(p))
                    .map(p -> prefix + dir.relativize(p).toString().replace(File.separatorChar, '/'))
                    .collect(Collectors.toList());
        }
    }
}
