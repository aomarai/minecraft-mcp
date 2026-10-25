package dev.aomarai.minecraftmcp.core;

/**
 * Append-only trail of tool decisions and results.
 *
 * <p>Implementations throw an unchecked exception when an entry cannot be
 * recorded. The dispatcher treats that as a failure and does not run the tool:
 * an action that cannot be audited does not happen.</p>
 */
public interface AuditLog {

    void record(AuditEntry entry);

    /** An audit log that keeps nothing. For tests and for setups without a log file. */
    static AuditLog discarding() {
        return entry -> {
        };
    }
}
