package dev.buhanzaz.rwms.auth.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts persistence constraint violations into the public conflict Problem Details response. */
@RestControllerAdvice
public class ApiExceptionHandler {

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
