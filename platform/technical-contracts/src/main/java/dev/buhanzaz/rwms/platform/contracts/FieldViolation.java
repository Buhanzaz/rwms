package dev.buhanzaz.rwms.platform.contracts;

/** A transport-safe description of one invalid request field. */
public record FieldViolation(String field, String code, String message) {

    public FieldViolation {
        requireText(field, "field");
        requireText(code, "code");
        requireText(message, "message");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
