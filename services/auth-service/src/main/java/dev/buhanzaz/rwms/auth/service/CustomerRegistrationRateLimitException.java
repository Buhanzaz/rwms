package dev.buhanzaz.rwms.auth.service;

/** Signals that a durable anonymous-registration budget is exhausted. */
public final class CustomerRegistrationRateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    /**
     * Creates a sanitized rejection carrying only the wait duration exposed to the caller.
     *
     * @param retryAfterSeconds positive whole seconds until the exhausted window expires
     */
    public CustomerRegistrationRateLimitException(long retryAfterSeconds) {
        super("Customer registration rate limit exceeded");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** Returns the positive HTTP Retry-After delay in whole seconds. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
