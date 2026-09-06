package dev.buhanzaz.rwms.auth.service;

import dev.buhanzaz.rwms.auth.config.LoginAttemptThrottleProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Enforces durable fixed-window budgets before interactive password verification.
 *
 * <p>All instances serialize matching source and account rows through auth-service PostgreSQL. A
 * successful authentication refunds only the source-auth and account-auth units reserved by that
 * request. The ingress unit is never refunded, so repeated successful or infrastructure-aborted
 * requests remain bounded.
 */
@Service
public class LoginAttemptThrottle {
    static final String SOURCE_INGRESS = "SOURCE_INGRESS";
    static final String SOURCE_AUTH = "SOURCE_AUTH";
    static final String ACCOUNT_AUTH = "ACCOUNT_AUTH";

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginAttemptThrottle.class);
    private static final String INSERT_WINDOW_SQL = """
            insert into login_attempt_budget (
                scope_key,
                subject_fingerprint,
                window_generation,
                window_started_at,
                attempt_count,
                expires_at,
                updated_at
            )
            select ?, ?, ?, observed_at, 0,
                   observed_at + cast(? as bigint) * interval '1 second', observed_at
              from (select clock_timestamp() as observed_at) database_clock
            on conflict (scope_key, subject_fingerprint) do nothing
            """;
    private static final String LOCK_WINDOW_SQL = """
            select window_generation, attempt_count, expires_at
              from login_attempt_budget
             where scope_key = ? and subject_fingerprint = ?
             for update
            """;
    private static final String RESET_WINDOW_SQL = """
            update login_attempt_budget
               set window_generation = ?,
                   window_started_at = ?,
                   attempt_count = 0,
                   expires_at = ?,
                   updated_at = ?
             where scope_key = ? and subject_fingerprint = ?
            """;
    private static final String INCREMENT_WINDOW_SQL = """
            update login_attempt_budget
               set attempt_count = attempt_count + 1,
                   updated_at = ?
             where scope_key = ? and subject_fingerprint = ?
            """;
    private static final String REFUND_WINDOW_SQL = """
            update login_attempt_budget
               set attempt_count = attempt_count - 1,
                   updated_at = clock_timestamp()
             where scope_key = ?
               and subject_fingerprint = ?
               and window_generation = ?
               and attempt_count > 0
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LoginAttemptThrottleProperties properties;

    /** Creates a durable login throttle backed by the auth-service transaction manager. */
    public LoginAttemptThrottle(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            LoginAttemptThrottleProperties properties) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    /**
     * Atomically reserves all login budgets, or returns a retry interval without allowing a hash.
     *
     * @param sourceAddress trusted servlet remote address
     * @param username login-form username
     * @return allowed admission token or a rejected decision
     * @throws LoginAttemptThrottleUnavailableException when PostgreSQL cannot make the decision
     */
    public Admission requireAllowed(String sourceAddress, String username) {
        String source = sourceAddress == null ? "unknown" : sourceAddress;
        String account = username == null ? "" : username.trim();
        List<WindowKey> sourceKeys = List.of(
                new WindowKey(
                        SOURCE_INGRESS,
                        fingerprint("rwms-login-source-ingress\0", source),
                        properties.sourceIngress()),
                new WindowKey(
                        SOURCE_AUTH,
                        fingerprint("rwms-login-source-auth\0", source),
                        properties.sourceAuth()));
        try {
            return Objects.requireNonNull(
                    transactions.execute(status -> reserve(sourceKeys, account)));
        } catch (DataAccessException | TransactionException exception) {
            LOGGER.warn("Login attempt admission failed closed: {}", exception.getClass().getSimpleName());
            throw new LoginAttemptThrottleUnavailableException(exception);
        }
    }

    /**
     * Refunds one admitted request after success or an authentication infrastructure failure.
     *
     * <p>The request-local guard makes repeated settlement harmless. Generation predicates prevent
     * a late result from changing a newer fixed window.</p>
     */
    public void refund(Admission admission) {
        if (admission == null || !admission.beginRefund()) {
            return;
        }
        try {
            transactions.executeWithoutResult(status -> {
                configureDatabaseTimeout();
                refund(admission.sourceAuth());
                refund(admission.accountAuth());
            });
        } catch (DataAccessException | TransactionException exception) {
            LOGGER.warn("Login attempt settlement remained conservative: {}", exception.getClass().getSimpleName());
        }
    }

    /** Removes only rows whose enforcement window and configured retention have both elapsed. */
    @Scheduled(fixedDelayString = "${rwms.auth.login-attempt-throttle.cleanup-delay:PT1H}")
    public void deleteExpiredBudgets() {
        try {
            jdbc.update(
                    "delete from login_attempt_budget "
                            + "where expires_at < clock_timestamp() - cast(? as bigint) * interval '1 second'",
                    properties.retention().toSeconds());
        } catch (DataAccessException exception) {
            LOGGER.warn("Login attempt budget cleanup failed: {}", exception.getClass().getSimpleName());
        }
    }

    private Admission reserve(List<WindowKey> sourceKeys, String account) {
        configureDatabaseTimeout();
        String canonicalAccount = jdbc.queryForObject("select lower(?)", String.class, account);
        if (canonicalAccount == null) {
            throw new IllegalStateException("Database username normalization returned null");
        }
        List<WindowKey> keys = List.of(
                sourceKeys.get(0),
                sourceKeys.get(1),
                new WindowKey(
                        ACCOUNT_AUTH,
                        fingerprint("rwms-login-account-auth\0", canonicalAccount),
                        properties.accountAuth()));
        WindowState ingress = lockWindow(keys.get(0));
        WindowState sourceAuth = lockWindow(keys.get(1));
        WindowState accountAuth = lockWindow(keys.get(2));
        OffsetDateTime now = databaseClock();
        ingress = resetIfExpired(ingress, now);
        sourceAuth = resetIfExpired(sourceAuth, now);
        accountAuth = resetIfExpired(accountAuth, now);
        List<WindowState> states = List.of(ingress, sourceAuth, accountAuth);

        long retryAfterSeconds = states.stream()
                .filter(WindowState::atLimit)
                .mapToLong(state -> retryAfterSeconds(now, state.expiresAt()))
                .max()
                .orElse(0);
        if (retryAfterSeconds > 0) {
            return Admission.rejected(retryAfterSeconds);
        }

        for (WindowState state : states) {
            jdbc.update(
                    INCREMENT_WINDOW_SQL,
                    now,
                    state.key().scope(),
                    state.key().fingerprint());
        }
        return Admission.allowed(
                new ReservedWindow(SOURCE_AUTH, keys.get(1).fingerprint(), sourceAuth.generation()),
                new ReservedWindow(ACCOUNT_AUTH, keys.get(2).fingerprint(), accountAuth.generation()));
    }

    private WindowState lockWindow(WindowKey key) {
        UUID initialGeneration = UUID.randomUUID();
        jdbc.update(
                INSERT_WINDOW_SQL,
                key.scope(),
                key.fingerprint(),
                initialGeneration,
                key.budget().window().toSeconds());
        return Objects.requireNonNull(jdbc.queryForObject(
                LOCK_WINDOW_SQL,
                (result, rowNumber) -> new WindowState(
                        key,
                        result.getObject("window_generation", UUID.class),
                        result.getLong("attempt_count"),
                        result.getObject("expires_at", OffsetDateTime.class)),
                key.scope(),
                key.fingerprint()));
    }

    private WindowState resetIfExpired(WindowState current, OffsetDateTime now) {
        if (current.expiresAt().isAfter(now)) {
            return current;
        }

        UUID nextGeneration = UUID.randomUUID();
        OffsetDateTime nextExpiry = now.plus(current.key().budget().window());
        jdbc.update(
                RESET_WINDOW_SQL,
                nextGeneration,
                now,
                nextExpiry,
                now,
                current.key().scope(),
                current.key().fingerprint());
        return new WindowState(current.key(), nextGeneration, 0, nextExpiry);
    }

    private void configureDatabaseTimeout() {
        String timeout = Math.max(1, properties.databaseTimeout().toMillis()) + "ms";
        jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class, timeout);
        jdbc.queryForObject("select set_config('statement_timeout', ?, true)", String.class, timeout);
    }

    private OffsetDateTime databaseClock() {
        OffsetDateTime now = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
        if (now == null) {
            throw new IllegalStateException("Database clock did not return a value");
        }
        return now;
    }

    private void refund(ReservedWindow reservation) {
        jdbc.update(
                REFUND_WINDOW_SQL,
                reservation.scope(),
                reservation.fingerprint(),
                reservation.generation());
    }

    private static long retryAfterSeconds(OffsetDateTime now, OffsetDateTime expiresAt) {
        Duration remaining = Duration.between(now, expiresAt);
        long wholeSeconds = remaining.getSeconds();
        return Math.max(1, remaining.getNano() == 0 ? wholeSeconds : wholeSeconds + 1);
    }

    private static String fingerprint(String domain, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record WindowKey(
            String scope,
            String fingerprint,
            LoginAttemptThrottleProperties.Budget budget) {}

    private record WindowState(
            WindowKey key,
            UUID generation,
            long attemptCount,
            OffsetDateTime expiresAt) {

        private boolean atLimit() {
            return attemptCount >= key.budget().limit();
        }
    }

    private record ReservedWindow(String scope, String fingerprint, UUID generation) {}

    /** Opaque request-local admission decision used by the security handlers for settlement. */
    public static final class Admission {
        private final boolean allowed;
        private final long retryAfterSeconds;
        private final ReservedWindow sourceAuth;
        private final ReservedWindow accountAuth;
        private final AtomicBoolean settled = new AtomicBoolean();

        private Admission(
                boolean allowed,
                long retryAfterSeconds,
                ReservedWindow sourceAuth,
                ReservedWindow accountAuth) {
            this.allowed = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
            this.sourceAuth = sourceAuth;
            this.accountAuth = accountAuth;
        }

        private static Admission allowed(
                ReservedWindow sourceAuth, ReservedWindow accountAuth) {
            return new Admission(true, 0, sourceAuth, accountAuth);
        }

        private static Admission rejected(long retryAfterSeconds) {
            return new Admission(false, Math.max(1, retryAfterSeconds), null, null);
        }

        public boolean allowed() {
            return allowed;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }

        private boolean beginRefund() {
            return allowed && settled.compareAndSet(false, true);
        }

        private ReservedWindow sourceAuth() {
            return sourceAuth;
        }

        private ReservedWindow accountAuth() {
            return accountAuth;
        }
    }

    /** Indicates that the durable admission decision could not be made and login must fail closed. */
    public static final class LoginAttemptThrottleUnavailableException extends RuntimeException {

        private LoginAttemptThrottleUnavailableException(Throwable cause) {
            super("Login attempt throttle unavailable", cause);
        }
    }
}
