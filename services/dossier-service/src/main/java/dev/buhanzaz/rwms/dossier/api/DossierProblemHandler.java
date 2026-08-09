package dev.buhanzaz.rwms.dossier.api;

import dev.buhanzaz.rwms.dossier.service.DossierQueryException;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps invalid queries and safe service failures to the canonical dossier Problem Details responses. */
@RestControllerAdvice
public class DossierProblemHandler {
  @ExceptionHandler(DossierQueryException.class)
  ProblemDetail dossier(DossierQueryException exception, HttpServletRequest request) {
    return problem(exception.status(), exception.code(), exception.getMessage(), request);
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    MethodArgumentTypeMismatchException.class,
    MethodArgumentNotValidException.class
  })
  ProblemDetail filter(Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "DOSSIER_INVALID_FILTER",
        "Dossier filter is invalid",
        request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail forbidden(AccessDeniedException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.FORBIDDEN, "DOSSIER_FORBIDDEN", "Dossier access is forbidden", request);
  }

  private static ProblemDetail problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail value = ProblemDetail.forStatusAndDetail(status, detail);
    value.setType(URI.create("urn:rwms:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
    value.setTitle(status.getReasonPhrase());
    value.setInstance(URI.create(request.getRequestURI()));
    value.setProperty("code", code);
    Object correlation = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put(
        "correlationId",
        correlation == null ? UUID.randomUUID().toString() : correlation.toString());
    metadata.put("causationId", null);
    value.setProperty("correlation", metadata);
    return value;
  }
}
