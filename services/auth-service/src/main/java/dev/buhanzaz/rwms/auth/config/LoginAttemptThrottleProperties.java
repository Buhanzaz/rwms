package dev.buhanzaz.rwms.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configures durable pre-authentication budgets for the interactive login form. */
@ConfigurationProperties("rwms.auth.login-attempt-throttle")
public record LoginAttemptThrottleProperties(
        Budget sourceIngress,
        Budget sourceAuth,
        Budget accountAuth,
        Duration retention,
        Duration cleanupDelay,
        Duration databaseTimeout) {

    /** Validates every login budget and cleanup interval during application startup. */
    public LoginAttemptThrottleProperties {
        if (sourceIngress == null || sourceAuth == null || accountAuth == null) {
            throw new IllegalArgumentException("all login attempt budgets must be configured");
        }
        requirePositive(retention, "retention");
        if (retention.toSeconds() < 1) {
            throw new IllegalArgumentException("retention must be at least one second");
        }
        requirePositive(cleanupDelay, "cleanup-delay");
        requirePositive(databaseTimeout, "database-timeout");
        if (databaseTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("database-timeout must be at least one millisecond");
        }
    }

    /** One fixed-window attempt budget. */
    public record Budget(int limit, Duration window) {

        /** Validates the limit and fixed-window duration. */
        public Budget {
            if (limit < 1) {
                throw new IllegalArgumentException("login attempt limit must be positive");
            }
            requirePositive(window, "login attempt window");
            if (window.toSeconds() < 1) {
                throw new IllegalArgumentException("login attempt window must be at least one second");
            }
        }
    }

    private static void requirePositive(Duration duration, String property) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }
}
