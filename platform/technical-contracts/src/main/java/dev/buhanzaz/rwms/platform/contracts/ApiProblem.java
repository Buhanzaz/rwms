package dev.buhanzaz.rwms.platform.contracts;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/** RFC 9457-compatible error payload with stable RWMS error metadata. */
public record ApiProblem(
        URI type,
        String title,
        int status,
        String detail,
        URI instance,
        String code,
        List<FieldViolation> violations,
        CorrelationContext correlation) {

    public ApiProblem {
        Objects.requireNonNull(type, "type must not be null");
        requireText(title, "title");
        requireText(code, "code");
        violations = List.copyOf(Objects.requireNonNull(violations, "violations must not be null"));
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be a valid HTTP status code");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
