package mil.army.usace.hec.dss.migrator;

import hec.heclib.util.Heclib;
import hec.heclib.util.stringContainer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves a host using javaHeclib 7-IU-16 can use the migrator (which bundles
 * 7-IU-0) without native-library conflicts. The host CL loads its native from
 * java.library.path; the migrator's isolated CL loads its bundled native from
 * the cache dir. Both coexist because different classloaders may load
 * different native files even with the same library name.
 */
class DssMigrationIsolationTest {

    private static final Dss7Migrator migrator = Dss7Migrator.usingDefaultCache();

    @Test
    void clientAndMigratorUseIndependentNativeLibraries() throws Exception {
        // Client side (host CL, 7-IU-16)
        stringContainer sc = new stringContainer();
        Heclib.zquery("vers", sc, new int[1]);
        String clientVersion = sc.string;
        Path clientNativePath = Path.of(
                System.getProperty("java.library.path").split(File.pathSeparator)[0],
                System.mapLibraryName("javaHeclib"));

        // Migrator side (isolated CL, 7-IU-0)
        String migratorVersion = migrator.getHeclibVersion();
        IsolatedClassLoader isolatedCL = migrator.isolatedClassLoader();
        Class<?> isolatedHeclib = isolatedCL.loadClass("hec.heclib.util.Heclib");
        var findLibrary = IsolatedClassLoader.class.getDeclaredMethod("findLibrary", String.class);
        Path migratorNativePath = Path.of(findLibrary.invoke(isolatedCL, "javaHeclib").toString());

        assertNotNull(clientVersion);
        assertFalse(clientVersion.isEmpty());
        assertNotNull(migratorVersion);
        assertFalse(migratorVersion.isEmpty());

        assertNotEquals(clientNativePath.toAbsolutePath().toString(),
                migratorNativePath.toAbsolutePath().toString(),
                "Native libraries should load from different paths");
        assertNotEquals(sha256(clientNativePath), sha256(migratorNativePath),
                "Native libraries should be different binaries");

        assertNotSame(Heclib.class, isolatedHeclib);
        assertNotSame(Heclib.class.getClassLoader(), isolatedHeclib.getClassLoader());
    }

    @Test
    void clientAndMigratorMaintainIndependentNativeState() throws Exception {
        Path clientFile = Files.createTempFile("dss-client-", ".dss");
        Path migratorFile = Files.createTempFile("dss-migrator-", ".dss");
        Files.delete(clientFile);
        Files.delete(migratorFile);
        try {
            ClassLoader isolatedCL = migrator.isolatedClassLoader();

            // Client opens a v7 file via the host's Heclib.
            int[] clientIfltab = new int[800];
            Heclib.zopen(clientIfltab, clientFile.toString(), new int[]{0});
            stringContainer clientSC = new stringContainer();
            int[] clientNREC = new int[1];
            Heclib.zinqir(clientIfltab, "VERS", clientSC, clientNREC);
            int clientVersion = clientNREC[0];
            Heclib.zinqir(clientIfltab, "NREC", clientSC, clientNREC);
            int clientRecords = clientNREC[0];

            // Migrator opens a different v7 file via its isolated Heclib.
            Class<?> isoHeclib = isolatedCL.loadClass("hec.heclib.util.Heclib");
            Class<?> isoSC = isolatedCL.loadClass("hec.heclib.util.stringContainer");
            var zopen = isoHeclib.getMethod("zopen", int[].class, String.class, int[].class);
            var zinqir = isoHeclib.getMethod("zinqir", int[].class, String.class, isoSC, int[].class);
            var zclose = isoHeclib.getMethod("zclose", int[].class);

            int[] migratorIfltab = new int[800];
            zopen.invoke(null, migratorIfltab, migratorFile.toString(), new int[]{0});
            Object migratorSC = isoSC.getConstructor().newInstance();
            int[] migratorNREC = new int[1];
            zinqir.invoke(null, migratorIfltab, "VERS", migratorSC, migratorNREC);
            int migratorVersion = migratorNREC[0];
            zinqir.invoke(null, migratorIfltab, "NREC", migratorSC, migratorNREC);
            int migratorRecords = migratorNREC[0];

            assertEquals(7, clientVersion);
            assertEquals(7, migratorVersion);
            assertEquals(0, clientRecords);
            assertEquals(0, migratorRecords);

            // Closing migrator's file must not disturb the client's still-open one.
            zclose.invoke(null, migratorIfltab);
            Heclib.zinqir(clientIfltab, "NREC", clientSC, clientNREC);
            assertEquals(0, clientNREC[0]);
            Heclib.zclose(clientIfltab);
        } finally {
            Files.deleteIfExists(clientFile);
            Files.deleteIfExists(migratorFile);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) digest.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
