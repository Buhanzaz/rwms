package dev.buhanzaz.rwms.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configures the durable budgets protecting anonymous customer registration.
 *
 * <p>The per-client budget limits one canonical source address, while the global budget bounds
 * password hashing and database growth under a distributed attack. Both windows are enforced by
 * auth-service PostgreSQL so every running instance observes the same counters.
 *
 * @param perClientLimit maximum attempts from one canonical source during its window
 * @param perClientWindow duration of the per-client fixed window
 * @param globalLimit maximum attempts accepted service-wide during its window
 * @param globalWindow duration of the service-wide fixed window
 * @param retention how long expired counter rows remain available before cleanup
 */
@ConfigurationProperties("rwms.auth.customer-registration-throttle")
public record CustomerRegistrationThrottleProperties(
        int perClientLimit,
        Duration perClientWindow,
        int globalLimit,
        Duration globalWindow,
        Duration retention) {

    /** Validates that every configured budget and retention interval is strictly positive. */
    public CustomerRegistrationThrottleProperties {
        if (perClientLimit < 1) {
            throw new IllegalArgumentException("per-client-limit must be positive");
        }
        requirePositive(perClientWindow, "per-client-window");
        if (globalLimit < 1) {
            throw new IllegalArgumentException("global-limit must be positive");
        }
        requirePositive(globalWindow, "global-window");
        requirePositive(retention, "retention");
    }

    private static void requirePositive(Duration duration, String property) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }
}
