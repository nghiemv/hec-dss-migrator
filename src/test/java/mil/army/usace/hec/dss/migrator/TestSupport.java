package mil.army.usace.hec.dss.migrator;

import java.nio.file.Files;
import java.nio.file.Path;

final class TestSupport {

    private TestSupport() {
    }

    /** Creates an empty v7 DSS file at {@code path} via the migrator's isolated CL. */
    static void createEmptyV7File(Dss7Migrator migrator, Path path) throws Exception {
        Files.deleteIfExists(path);
        Class<?> mgr = migrator.isolatedClassLoader().loadClass("hec.heclib.dss.HecDataManager");
        Object instance = mgr.getConstructor(String.class).newInstance(path.toString());
        mgr.getMethod("open").invoke(instance);
        mgr.getMethod("close").invoke(instance);
    }

    /**
     * Creates an empty v6 DSS file at {@code path} by minting an empty v7 nearby
     * and converting it down via the isolated CL's {@code HecDSSUtilities}. The
     * bundled javaHeclib (7-IU-0) still understands v6 writes, so this is the
     * only test-side way to produce a v6 file without checking one in.
     */
    static void createEmptyV6File(Dss7Migrator migrator, Path path) throws Exception {
        Files.deleteIfExists(path);
        Path v7 = path.resolveSibling(path.getFileName() + ".seed-v7.dss");
        createEmptyV7File(migrator, v7);
        try {
            Class<?> util = migrator.isolatedClassLoader()
                    .loadClass("hec.heclib.dss.HecDSSUtilities");
            Object utilities = util.getConstructor().newInstance();
            util.getMethod("setDSSFileName", String.class).invoke(utilities, v7.toString());
            int status = (int) util.getMethod("convertVersion", String.class)
                    .invoke(utilities, path.toString());
            util.getMethod("closeDSSFile").invoke(utilities);
            util.getMethod("setDSSFileName", String.class).invoke(utilities, path.toString());
            util.getMethod("closeDSSFile").invoke(utilities);
            if (status != 0) {
                throw new IllegalStateException(
                        "v7→v6 seeding failed (status=" + status + ") for: " + path);
            }
        } finally {
            Files.deleteIfExists(v7);
        }
    }
}
