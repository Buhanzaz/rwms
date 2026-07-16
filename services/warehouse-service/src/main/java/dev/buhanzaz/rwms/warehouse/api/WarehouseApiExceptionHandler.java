package dev.buhanzaz.rwms.warehouse.api;

import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.FieldViolation;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import dev.buhanzaz.rwms.warehouse.service.WarehouseConflictException;
import dev.buhanzaz.rwms.warehouse.service.WarehouseNotFoundException;
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
public class WarehouseApiExceptionHandler {
  private static final String PREFIX = "urn:rwms:problem:warehouse:";
  private final RwmsProblemDetailFactory problems;

  public WarehouseApiExceptionHandler(RwmsProblemDetailFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(WarehouseNotFoundException.class)
  ResponseEntity<ApiProblem> notFound(WarehouseNotFoundException exception, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "WAREHOUSE_NOT_FOUND", exception.getMessage(), request);
  }

  @ExceptionHandler(WarehouseConflictException.class)
  ResponseEntity<ApiProblem> conflict(WarehouseConflictException exception, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "WAREHOUSE_CONFLICT", exception.getMessage(), request);
  }

  @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
  ResponseEntity<ApiProblem> persistenceConflict(Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "WAREHOUSE_PERSISTENCE_CONFLICT",
        "Warehouse data changed concurrently or violates uniqueness",
        request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiProblem> forbidden(AccessDeniedException exception, HttpServletRequest request) {
    return problem(HttpStatus.FORBIDDEN, "WAREHOUSE_FORBIDDEN", exception.getMessage(), request);
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
        "WAREHOUSE_VALIDATION_FAILED",
        "Warehouse request is invalid",
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
        "WAREHOUSE_VALIDATION_FAILED",
        "Warehouse request is invalid",
        violations,
        request);
  }

  @ExceptionHandler({
    HandlerMethodValidationException.class,
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ApiProblem> invalid(Exception exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "WAREHOUSE_INVALID_REQUEST", "Warehouse request is invalid", request);
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
