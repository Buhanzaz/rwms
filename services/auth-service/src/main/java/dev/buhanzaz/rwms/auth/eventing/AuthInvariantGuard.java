package dev.buhanzaz.rwms.auth.eventing;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serializes auth transitions that must preserve service-wide invariants.
 *
 * <p>PostgreSQL transaction-scoped advisory locks are used only while an existing caller
 * transaction is active; the guard neither starts a transaction nor owns business transitions.
 */
@Service
@RequiredArgsConstructor
public class AuthInvariantGuard {

    private static final long SYSTEM_ADMIN_GUARD_KEY = 0x52574d5341555448L;
    private static final long BOOTSTRAP_GUARD_KEY = 0x52574d53424f4f54L;

    private final JdbcTemplate jdbc;

    /** Acquires the transaction-scoped lock protecting the system-administrator invariant. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSystemAdminInvariant() {
        lock(SYSTEM_ADMIN_GUARD_KEY);
    }

    /** Acquires the transaction-scoped lock protecting bootstrap initialization. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockBootstrap() {
        lock(BOOTSTRAP_GUARD_KEY);
    }

    /**
     * Serializes self-registration attempts for the same case-insensitive login.
     *
     * <p>The lock is acquired before password hashing and projection writes. It prevents concurrent
     * requests from doing duplicate expensive credential work and makes the case-insensitive
     * availability check deterministic before the database uniqueness constraint is reached.
     *
     * @param username already trimmed customer login
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockCustomerRegistration(String username) {
        String normalized = username.toLowerCase(Locale.ROOT);
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, "rwms-auth:customer-registration:" + normalized),
                result -> null);
    }

    private void lock(long key) {
        jdbc.query(
                "select pg_advisory_xact_lock(?)",
                statement -> statement.setLong(1, key),
                result -> null);
    }
}
