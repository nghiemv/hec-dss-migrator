package mil.army.usace.hec.dss.migrator;

/**
 * Unchecked exception wrapping errors from the isolated DSS conversion.
 * No hec-monolith types leak through this exception.
 */
public class DssMigrationException extends RuntimeException {

    public DssMigrationException(String message) {
        super(message);
    }

    public DssMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
