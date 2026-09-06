package dev.buhanzaz.rwms.auth.eventing;

/** Reports an expected operator-visible outcome of guarded auth-shadow recovery. */
public final class AuthShadowRecoveryException extends RuntimeException {

    private final Kind kind;

    public AuthShadowRecoveryException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** Stable recovery outcome used by the HTTP boundary without exposing persistence failures. */
    public enum Kind {
        CHECKPOINT_NOT_FOUND,
        STALE_OR_INELIGIBLE
    }
}
