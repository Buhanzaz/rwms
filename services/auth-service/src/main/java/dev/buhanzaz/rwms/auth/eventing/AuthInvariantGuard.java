package dev.buhanzaz.rwms.auth.eventing;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthInvariantGuard {

    private static final long SYSTEM_ADMIN_GUARD_KEY = 0x52574d5341555448L;
    private static final long BOOTSTRAP_GUARD_KEY = 0x52574d53424f4f54L;

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSystemAdminInvariant() {
        lock(SYSTEM_ADMIN_GUARD_KEY);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockBootstrap() {
        lock(BOOTSTRAP_GUARD_KEY);
    }

    private void lock(long key) {
        jdbc.query(
                "select pg_advisory_xact_lock(?)",
                statement -> statement.setLong(1, key),
                result -> null);
    }
}
