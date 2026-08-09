package dev.buhanzaz.rwms.auth.eventing;

/**
 * Signals that an auth event failed strict envelope, payload, or authoritative-source validation.
 *
 * <p>Kafka consumers treat this exception as a validation rejection and record sanitized
 * dead-letter metadata instead of exposing or republishing the rejected message body.
 */
public class AuthEventValidationException extends RuntimeException {

    /** Creates a generic validation failure without retaining rejected event content. */
    public AuthEventValidationException() {
        super("Auth event failed schema validation");
    }
}
