package dev.buhanzaz.rwms.auth.eventing;

public class AuthEventValidationException extends RuntimeException {

    public AuthEventValidationException() {
        super("Auth event failed schema validation");
    }
}
