package dev.buhanzaz.rwms.logistics.api;

import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.FieldViolation;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class LogisticsProblemHandler {
  private static final String PREFIX = "urn:rwms:problem:logistics:";
  private final RwmsProblemDetailFactory problems;

  public LogisticsProblemHandler(RwmsProblemDetailFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(OrderProblemException.class)
  ResponseEntity<ApiProblem> orderProblem(
      OrderProblemException exception, HttpServletRequest request) {
    return problem(
        exception.status(), exception.code(), exception.getMessage(), request);
  }

  @ExceptionHandler(LogisticsNotFoundException.class)
  ResponseEntity<ApiProblem> notFound(LogisticsNotFoundException exception, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "LOGISTICS_DOCUMENT_NOT_FOUND", exception.getMessage(), request);
  }

  @ExceptionHandler(LogisticsConflictException.class)
  ResponseEntity<ApiProblem> conflict(LogisticsConflictException exception, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "LOGISTICS_CONFLICT", exception.getMessage(), request);
  }

  @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
  ResponseEntity<ApiProblem> persistenceConflict(Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "LOGISTICS_PERSISTENCE_CONFLICT",
        "Logistics data changed concurrently or violates a local uniqueness constraint",
        request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiProblem> forbidden(AccessDeniedException exception, HttpServletRequest request) {
    return problem(HttpStatus.FORBIDDEN, "LOGISTICS_FORBIDDEN", exception.getMessage(), request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiProblem> validation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<FieldViolation> violations =
        exception.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    new FieldViolation(
                        error.getField(),
                        error.getCode() == null ? "INVALID" : error.getCode(),
                        error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()))
            .toList();
    return problem(
        HttpStatus.BAD_REQUEST,
        "LOGISTICS_VALIDATION_FAILED",
        "Logistics request is invalid",
        violations,
        request);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ApiProblem> constraint(
      ConstraintViolationException exception, HttpServletRequest request) {
    List<FieldViolation> violations =
        exception.getConstraintViolations().stream()
            .map(
                violation ->
                    new FieldViolation(
                        violation.getPropertyPath().toString(),
                        violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                        violation.getMessage()))
            .toList();
    return problem(
        HttpStatus.BAD_REQUEST,
        "LOGISTICS_VALIDATION_FAILED",
        "Logistics request is invalid",
        violations,
        request);
  }

  @ExceptionHandler({
    HandlerMethodValidationException.class,
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class,
    IllegalStateException.class
  })
  ResponseEntity<ApiProblem> invalid(Exception exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "LOGISTICS_INVALID_REQUEST", "Logistics request is invalid", request);
  }

  private ResponseEntity<ApiProblem> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    return problem(status, code, detail, List.of(), request);
  }

  private ResponseEntity<ApiProblem> problem(
      HttpStatus status,
      String code,
      String detail,
      List<FieldViolation> violations,
      HttpServletRequest request) {
    CorrelationContext correlation = correlation(request);
    URI type = URI.create(PREFIX + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    ApiProblem body =
        violations.isEmpty()
            ? problems.create(
                type,
                status.getReasonPhrase(),
                status,
                detail,
                URI.create(request.getRequestURI()),
                code,
                correlation)
            : new ApiProblem(
                type,
                status.getReasonPhrase(),
                status.value(),
                detail,
                URI.create(request.getRequestURI()),
                code,
                violations,
                correlation);
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  private CorrelationContext correlation(HttpServletRequest request) {
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    try {
      return new CorrelationContext(UUID.fromString(String.valueOf(value)), null);
    } catch (IllegalArgumentException exception) {
      return new CorrelationContext(UUID.randomUUID(), null);
    }
  }
}
