package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceCatalogValidationException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceNotFoundException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException;
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
import lombok.RequiredArgsConstructor;
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

/** Translates maintenance failures into canonical HTTP problem responses. */
@RestControllerAdvice
@RequiredArgsConstructor
public class MaintenanceApiExceptionHandler {
  private static final String PREFIX = "urn:rwms:problem:maintenance:";
  private final RwmsProblemDetailFactory problems;

  @ExceptionHandler(MaintenanceNotFoundException.class)
  ResponseEntity<ApiProblem> notFound(
      MaintenanceNotFoundException exception, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "MAINTENANCE_NOT_FOUND", exception.getMessage(), request);
  }

  @ExceptionHandler(MaintenanceConflictException.class)
  ResponseEntity<ApiProblem> conflict(
      MaintenanceConflictException exception, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, exception.code(), exception.getMessage(), request);
  }

  @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
  ResponseEntity<ApiProblem> persistenceConflict(Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "MAINTENANCE_VERSION_CONFLICT",
        "Maintenance data changed concurrently or violates a canonical invariant",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<ApiProblem> stateConflict(
      IllegalStateException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT, "MAINTENANCE_STATE_CONFLICT", exception.getMessage(), request);
  }

  @ExceptionHandler(MaintenanceValidationException.class)
  ResponseEntity<ApiProblem> domainValidation(
      MaintenanceValidationException exception, HttpServletRequest request) {
    return problem(HttpStatus.UNPROCESSABLE_CONTENT, exception.code(), exception.getMessage(), request);
  }

  @ExceptionHandler(MaintenanceCatalogValidationException.class)
  ResponseEntity<ApiProblem> catalogValidation(
      MaintenanceCatalogValidationException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "MAINTENANCE_VALIDATION_FAILED",
        exception.getMessage(),
        exception.violations(),
        request);
  }

  @ExceptionHandler(MaintenanceDependencyException.class)
  ResponseEntity<ApiProblem> dependency(
      MaintenanceDependencyException exception, HttpServletRequest request) {
    return problem(
        exception.status(),
        exception.code(),
        exception.getMessage(),
        request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiProblem> forbidden(
      AccessDeniedException exception, HttpServletRequest request) {
    return problem(HttpStatus.FORBIDDEN, "MAINTENANCE_FORBIDDEN", exception.getMessage(), request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiProblem> validation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
        .map(error -> new FieldViolation(
            error.getField(),
            error.getCode() == null ? "INVALID" : error.getCode(),
            error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()))
        .toList();
    return problem(
        HttpStatus.BAD_REQUEST,
        "MAINTENANCE_VALIDATION_FAILED",
        "Maintenance request is invalid",
        violations,
        request);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ApiProblem> constraint(
      ConstraintViolationException exception, HttpServletRequest request) {
    List<FieldViolation> violations = exception.getConstraintViolations().stream()
        .map(violation -> new FieldViolation(
            violation.getPropertyPath().toString(),
            violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
            violation.getMessage()))
        .toList();
    return problem(
        HttpStatus.BAD_REQUEST,
        "MAINTENANCE_VALIDATION_FAILED",
        "Maintenance request is invalid",
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
    return problem(
        HttpStatus.BAD_REQUEST,
        "MAINTENANCE_VALIDATION_FAILED",
        "Maintenance request is invalid",
        request);
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
    ApiProblem body = violations.isEmpty()
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
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  private static CorrelationContext correlation(HttpServletRequest request) {
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    try {
      return new CorrelationContext(UUID.fromString(String.valueOf(value)), null);
    } catch (IllegalArgumentException exception) {
      return new CorrelationContext(UUID.randomUUID(), null);
    }
  }
}
