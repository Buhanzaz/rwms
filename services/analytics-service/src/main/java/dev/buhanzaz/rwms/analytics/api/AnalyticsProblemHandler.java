package dev.buhanzaz.rwms.analytics.api;

import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class AnalyticsProblemHandler {
  @ExceptionHandler({
    IllegalArgumentException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  ProblemDetail invalid(Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "ANALYTICS_INVALID_PERIOD",
        "KPI period filter is invalid",
        request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail forbidden(AccessDeniedException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.FORBIDDEN,
        "ANALYTICS_FORBIDDEN",
        "Analytics warehouse access is forbidden",
        request);
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
