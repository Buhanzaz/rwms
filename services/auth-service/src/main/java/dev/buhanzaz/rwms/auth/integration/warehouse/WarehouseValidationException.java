package dev.buhanzaz.rwms.auth.integration.warehouse;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class WarehouseValidationException extends ResponseStatusException {

    private WarehouseValidationException(HttpStatus status, String reason, Throwable cause) {
        super(status, reason, cause);
    }

    static WarehouseValidationException invalid(String reason) {
        return new WarehouseValidationException(HttpStatus.BAD_REQUEST, reason, null);
    }

    static WarehouseValidationException badGateway(String reason) {
        return new WarehouseValidationException(HttpStatus.BAD_GATEWAY, reason, null);
    }

    static WarehouseValidationException notFound(String reason) {
        return new WarehouseValidationException(HttpStatus.UNPROCESSABLE_CONTENT, reason, null);
    }

    static WarehouseValidationException conflict(String reason) {
        return new WarehouseValidationException(HttpStatus.CONFLICT, reason, null);
    }

    static WarehouseValidationException unavailable(String reason, Throwable cause) {
        return new WarehouseValidationException(HttpStatus.SERVICE_UNAVAILABLE, reason, cause);
    }
}
