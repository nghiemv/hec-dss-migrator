package mil.army.usace.hec.dss.migrator;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class IsolatedRuntimeTest {

    private static final Logger LOGGER = Logger.getLogger(IsolatedRuntimeTest.class.getName());
    private static final Dss7Migrator MIGRATOR = Dss7Migrator.usingDefaultCache();

    @Test
    void runRethrowsDssMigrationException() throws Exception {
        IsolatedRuntime.Session session = sessionOf(MIGRATOR);
        DssMigrationException expected = new DssMigrationException("preserve me");

        DssMigrationException actual = assertThrows(DssMigrationException.class,
                () -> IsolatedRuntime.run(session, () -> { throw expected; },
                        "ignored", LOGGER, "ctx"));

        assertSame(expected, actual,
                "DssMigrationException must propagate verbatim, not get logged-and-swallowed");
    }

    @Test
    void runStillSwallowsOtherExceptions() throws Exception {
        IsolatedRuntime.Session session = sessionOf(MIGRATOR);

        String result = IsolatedRuntime.run(session, () -> { throw new RuntimeException("oops"); },
                "errorValue", LOGGER, "ctx");

        assertEquals("errorValue", result);
    }

    private static IsolatedRuntime.Session sessionOf(Dss7Migrator m) throws Exception {
        Field f = Dss7Migrator.class.getDeclaredField("session");
        f.setAccessible(true);
        return (IsolatedRuntime.Session) f.get(m);
    }
}
