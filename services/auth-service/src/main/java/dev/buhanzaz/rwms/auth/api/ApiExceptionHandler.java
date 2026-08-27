package dev.buhanzaz.rwms.auth.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Converts public API failures into sanitized RFC 9457 Problem Details responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Reports bean-validation failures without echoing password-bearing request values.
     *
     * @return a {@code 400 Bad Request} problem response
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Некорректные данные запроса");
    }

    /**
     * Preserves an owning service's explicit status and sanitized reason as Problem Details.
     *
     * @param exception application-layer HTTP failure
     * @return problem response with the requested status
     */
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail responseStatus(ResponseStatusException exception) {
        String detail = exception.getReason() == null ? "Запрос отклонён" : exception.getReason();
        return ProblemDetail.forStatusAndDetail(exception.getStatusCode(), detail);
    }

    /**
     * Hides persistence-specific constraint details while reporting a client-visible conflict.
     *
     * @return a {@code 409 Conflict} problem response
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail dataIntegrityViolation() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Конфликт уникальных или связанных данных");
    }
}
