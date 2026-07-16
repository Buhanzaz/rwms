package dev.buhanzaz.rwms.taskboard.api;

import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.FieldViolation;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.ExternalServiceException;
import dev.buhanzaz.rwms.taskboard.service.NotFoundException;
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
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final String PROBLEM_PREFIX = "urn:rwms:problem:task-board:";
  private final RwmsProblemDetailFactory problems;

  public ApiExceptionHandler(RwmsProblemDetailFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<ApiProblem> notFound(NotFoundException exception, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "TASK_BOARD_NOT_FOUND", exception.getMessage(), request);
  }

  @ExceptionHandler(ConflictException.class)
  ResponseEntity<ApiProblem> conflict(ConflictException exception, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "TASK_BOARD_CONFLICT", exception.getMessage(), request);
  }

  @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
  ResponseEntity<ApiProblem> persistenceConflict(Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "TASK_BOARD_PERSISTENCE_CONFLICT",
        "Данные были изменены или нарушают уникальность",
        request);
  }

  @ExceptionHandler(ExternalServiceException.class)
  ResponseEntity<ApiProblem> external(
      ExternalServiceException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_GATEWAY,
        "TASK_BOARD_DEPENDENCY_UNAVAILABLE",
        exception.getMessage(),
        request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiProblem> forbidden(
      AccessDeniedException exception, HttpServletRequest request) {
    return problem(HttpStatus.FORBIDDEN, "TASK_BOARD_FORBIDDEN", exception.getMessage(), request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiProblem> bodyValidation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<FieldViolation> violations =
        exception.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    new FieldViolation(
                        error.getField(),
                        error.getCode() == null ? "INVALID" : error.getCode(),
                        error.getDefaultMessage() == null
                            ? "Некорректное значение"
                            : error.getDefaultMessage()))
            .toList();
    return problem(
        HttpStatus.BAD_REQUEST,
        "TASK_BOARD_VALIDATION_FAILED",
        "Некорректные данные запроса",
        violations,
        request);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ApiProblem> constraintValidation(
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
        "TASK_BOARD_VALIDATION_FAILED",
        "Некорректные данные запроса",
        violations,
        request);
  }

  @ExceptionHandler({
    HandlerMethodValidationException.class,
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ApiProblem> badRequest(Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "TASK_BOARD_INVALID_REQUEST",
        "Некорректные данные запроса",
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
    URI type = URI.create(PROBLEM_PREFIX + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    URI instance = URI.create(request.getRequestURI());
    CorrelationContext correlation = correlation(request);
    String safeDetail = detail == null ? status.getReasonPhrase() : detail;
    ApiProblem body =
        violations.isEmpty()
            ? problems.create(
                type,
                status.getReasonPhrase(),
                status,
                safeDetail,
                instance,
                code,
                correlation)
            : new ApiProblem(
                type,
                status.getReasonPhrase(),
                status.value(),
                safeDetail,
                instance,
                code,
                violations,
                correlation);
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
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
