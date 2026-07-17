package dev.buhanzaz.rwms.inventory.api;

import dev.buhanzaz.rwms.inventory.service.InventoryException;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class InventoryProblemHandler {
  @ExceptionHandler(InventoryException.class)
  ProblemDetail inventory(InventoryException exception, HttpServletRequest request) {
    return problem(exception.status(), exception.code(), exception.getMessage(), request);
  }

  @ExceptionHandler({
    ObjectOptimisticLockingFailureException.class,
    DataIntegrityViolationException.class
  })
  ProblemDetail conflict(RuntimeException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "INVENTORY_VERSION_CONFLICT",
        "Inventory state changed concurrently",
        request);
  }

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ProblemDetail validation(Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "INVENTORY_VALIDATION_FAILED",
        exception.getMessage(),
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  ProblemDetail state(IllegalStateException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT, "INVENTORY_VERSION_CONFLICT", exception.getMessage(), request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail forbidden(AccessDeniedException exception, HttpServletRequest request) {
    return problem(HttpStatus.FORBIDDEN, "INVENTORY_FORBIDDEN", exception.getMessage(), request);
  }

  private ProblemDetail problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail value = ProblemDetail.forStatusAndDetail(status, detail == null ? code : detail);
    value.setType(URI.create("urn:rwms:problem:" + code.toLowerCase()));
    value.setTitle(status.getReasonPhrase());
    value.setInstance(URI.create(request.getRequestURI()));
    value.setProperty("code", code);
    value.setProperty("violations", List.of());
    Object correlation = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    Map<String, Object> correlationValue = new LinkedHashMap<>();
    correlationValue.put(
        "correlationId", correlation == null ? java.util.UUID.randomUUID().toString() : correlation.toString());
    correlationValue.put("causationId", null);
    value.setProperty("correlation", correlationValue);
    return value;
  }
}
