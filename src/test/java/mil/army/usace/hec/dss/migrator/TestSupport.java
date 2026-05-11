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
}
