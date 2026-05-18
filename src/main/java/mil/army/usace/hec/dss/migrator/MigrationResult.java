package mil.army.usace.hec.dss.migrator;

/**
 * Result of a DSS file migration attempt.
 */
public enum MigrationResult {
    /** File was successfully migrated from version 6 to version 7. */
    MIGRATED,
    /** File is already version 7; no migration needed. */
    ALREADY_UP_TO_DATE,
    /** Migration failed (non-regular file, unsupported DSS version, or native conversion error — see logs). */
    FAILED
}
