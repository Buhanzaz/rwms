package dev.buhanzaz.rwms.auth.integration.warehouse;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Maps warehouse-validation failures to the API status that callers can safely act on. */
final class WarehouseValidationException extends ResponseStatusException {

    /** Creates a classified validation exception without exposing any upstream response body. */
    private WarehouseValidationException(HttpStatus status, String reason, Throwable cause) {
        super(status, reason, cause);
    }

    /** Creates a bad-request failure for an invalid caller-supplied warehouse value. */
    static WarehouseValidationException invalid(String reason) {
        return new WarehouseValidationException(HttpStatus.BAD_REQUEST, reason, null);
    }

    /** Creates a failure for an invalid or rejected upstream integration response. */
    static WarehouseValidationException badGateway(String reason) {
        return new WarehouseValidationException(HttpStatus.BAD_GATEWAY, reason, null);
    }

    /** Creates a semantic not-found failure for a warehouse absent from the owner service. */
    static WarehouseValidationException notFound(String reason) {
        return new WarehouseValidationException(HttpStatus.UNPROCESSABLE_CONTENT, reason, null);
    }

    /** Creates a conflict failure for a warehouse that cannot receive a grant in its current state. */
    static WarehouseValidationException conflict(String reason) {
        return new WarehouseValidationException(HttpStatus.CONFLICT, reason, null);
    }

    /** Creates a retryable-unavailable failure while retaining the cause only for server handling. */
    static WarehouseValidationException unavailable(String reason, Throwable cause) {
        return new WarehouseValidationException(HttpStatus.SERVICE_UNAVAILABLE, reason, cause);
    }
}
