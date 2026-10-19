package dev.buhanzaz.rwms.auth.service;

import dev.buhanzaz.rwms.auth.config.CustomerRegistrationThrottleProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Enforces cluster-coherent per-client and global budgets before password hashing starts.
 *
 * <p>Only a SHA-256 fingerprint of the gateway-canonicalized source address is persisted. The
 * transaction commits an exhausted counter before this service raises {@code 429}, so repeated
 * rejected attempts cannot reset their own budget by rolling the row back.
 */
@Service
public class CustomerRegistrationThrottle {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomerRegistrationThrottle.class);
    private static final String CLIENT_SCOPE = "CLIENT";
    private static final String GLOBAL_SCOPE = "GLOBAL";
    private static final String GLOBAL_FINGERPRINT = fingerprint("rwms-customer-registration-global");
    private static final String CONSUME_WINDOW_SQL = """
            with consumed as (
                insert into customer_registration_throttle (
                    scope_key,
                    subject_fingerprint,
                    window_started_at,
                    request_count,
                    expires_at,
                    updated_at
                ) values (
                    ?, ?, clock_timestamp(), 1,
                    clock_timestamp() + cast(? as bigint) * interval '1 second',
                    clock_timestamp()
                )
                on conflict (scope_key, subject_fingerprint) do update
                set window_started_at = case
                        when customer_registration_throttle.expires_at <= clock_timestamp()
                        then clock_timestamp()
                        else customer_registration_throttle.window_started_at
                    end,
                    request_count = case
                        when customer_registration_throttle.expires_at <= clock_timestamp()
                        then 1
                        else customer_registration_throttle.request_count + 1
                    end,
                    expires_at = case
                        when customer_registration_throttle.expires_at <= clock_timestamp()
                        then clock_timestamp() + cast(? as bigint) * interval '1 second'
                        else customer_registration_throttle.expires_at
                    end,
                    updated_at = clock_timestamp()
                returning request_count, expires_at
            )
            select request_count,
                   greatest(
                       1,
                       ceil(extract(epoch from (expires_at - clock_timestamp())))
                   )::bigint as retry_after_seconds
              from consumed
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final CustomerRegistrationThrottleProperties properties;
    private final Counter clientRejections;
    private final Counter globalRejections;
    private final Counter unavailableFailures;

    /**
     * Creates the durable throttle and its bounded observability counters.
     *
     * @param jdbc auth-service database access
     * @param transactionManager owner transaction manager shared with registration persistence
     * @param properties configured limits, windows, and retention
     * @param metrics application meter registry
     */
    public CustomerRegistrationThrottle(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            CustomerRegistrationThrottleProperties properties,
            MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clientRejections = Counter.builder("rwms.auth.customer.registration.throttle.rejected")
                .description("Anonymous customer registrations rejected by a durable budget")
                .tag("scope", "client")
                .register(metrics);
        this.globalRejections = Counter.builder("rwms.auth.customer.registration.throttle.rejected")
                .description("Anonymous customer registrations rejected by a durable budget")
                .tag("scope", "global")
                .register(metrics);
        this.unavailableFailures = Counter.builder("rwms.auth.customer.registration.throttle.unavailable")
                .description("Registration throttle checks that failed closed")
                .register(metrics);
    }

    /**
     * Consumes the source and global budgets or rejects before the registration transaction.
     *
     * @param sourceAddress canonical client address supplied by the trusted gateway boundary
     * @throws CustomerRegistrationRateLimitException when either budget is exhausted
     * @throws ResponseStatusException with {@code 503} when the durable budget cannot be checked
     */
    public void requireAllowed(String sourceAddress) {
        ThrottleDecision decision;
        try {
            decision = Objects.requireNonNull(transactions.execute(status -> consume(sourceAddress)));
        } catch (DataAccessException | TransactionException exception) {
            unavailableFailures.increment();
            LOGGER.warn("Customer registration throttle failed closed: {}", exception.getClass().getSimpleName());
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Регистрация временно недоступна. Повторите попытку позже");
        }
        if (!decision.allowed()) {
            if (CLIENT_SCOPE.equals(decision.scope())) {
                clientRejections.increment();
            } else {
                globalRejections.increment();
            }
            throw new CustomerRegistrationRateLimitException(decision.retryAfterSeconds());
        }
    }

    /** Removes only counter rows whose enforcement window and retention period have both elapsed. */
    @Scheduled(fixedDelayString = "${rwms.auth.customer-registration-throttle.cleanup-delay:PT1H}")
    public void deleteExpiredCounters() {
        try {
            jdbc.update(
                    "delete from customer_registration_throttle "
                            + "where expires_at < clock_timestamp() - cast(? as bigint) * interval '1 second'",
                    properties.retention().toSeconds());
        } catch (DataAccessException exception) {
            LOGGER.warn("Customer registration throttle cleanup failed: {}", exception.getClass().getSimpleName());
        }
    }

    private ThrottleDecision consume(String sourceAddress) {
        WindowState client = consumeWindow(
                CLIENT_SCOPE,
                fingerprint(normalizeSource(sourceAddress)),
                properties.perClientWindow());
        if (client.requestCount() > properties.perClientLimit()) {
            return ThrottleDecision.rejected(CLIENT_SCOPE, client.retryAfterSeconds());
        }

        WindowState global = consumeWindow(
                GLOBAL_SCOPE, GLOBAL_FINGERPRINT, properties.globalWindow());
        if (global.requestCount() > properties.globalLimit()) {
            return ThrottleDecision.rejected(GLOBAL_SCOPE, global.retryAfterSeconds());
        }
        return ThrottleDecision.permitted();
    }

    private WindowState consumeWindow(String scope, String subjectFingerprint, Duration window) {
        return Objects.requireNonNull(jdbc.queryForObject(
                CONSUME_WINDOW_SQL,
                (result, rowNumber) -> new WindowState(result.getLong(1), result.getLong(2)),
                scope,
                subjectFingerprint,
                window.toSeconds(),
                window.toSeconds()));
    }

    private static String normalizeSource(String sourceAddress) {
        if (sourceAddress == null || sourceAddress.isBlank()) {
            return "unknown";
        }
        return sourceAddress.trim().toLowerCase(Locale.ROOT);
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Atomic database result for one fixed-window counter. */
    private record WindowState(long requestCount, long retryAfterSeconds) {}

    /** Complete outcome of consuming the client and service-wide budgets. */
    private record ThrottleDecision(boolean allowed, String scope, long retryAfterSeconds) {

        private static ThrottleDecision permitted() {
            return new ThrottleDecision(true, "", 0);
        }

        private static ThrottleDecision rejected(String scope, long retryAfterSeconds) {
            return new ThrottleDecision(false, scope, Math.max(1, retryAfterSeconds));
        }
    }
}
