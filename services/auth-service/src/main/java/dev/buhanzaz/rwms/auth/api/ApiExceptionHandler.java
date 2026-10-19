package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.eventing.AuthShadowRecoveryException;
import dev.buhanzaz.rwms.auth.service.CustomerRegistrationRateLimitException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Converts public API failures into sanitized RFC 9457 Problem Details responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Reports only typed auth-shadow recovery absence and fencing outcomes. */
    @ExceptionHandler(AuthShadowRecoveryException.class)
    ProblemDetail authShadowRecovery(AuthShadowRecoveryException exception) {
        return switch (exception.kind()) {
            case CHECKPOINT_NOT_FOUND -> ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND,
                    "Контрольная точка теневой проекции авторизации не найдена");
            case STALE_OR_INELIGIBLE -> ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "Контрольная точка теневой проекции авторизации изменилась "
                            + "или недоступна для восстановления");
        };
    }

    /**
     * Reports a durable anonymous-registration budget rejection with an actionable retry delay.
     *
     * @param exception sanitized rate-limit outcome
     * @return a {@code 429 Too Many Requests} problem response with {@code Retry-After}
     */
    @ExceptionHandler(CustomerRegistrationRateLimitException.class)
    ResponseEntity<ProblemDetail> customerRegistrationRateLimited(
            CustomerRegistrationRateLimitException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Слишком много попыток регистрации. Повторите попытку позже");
        problem.setProperty("code", "CUSTOMER_REGISTRATION_RATE_LIMITED");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(problem);
    }

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
