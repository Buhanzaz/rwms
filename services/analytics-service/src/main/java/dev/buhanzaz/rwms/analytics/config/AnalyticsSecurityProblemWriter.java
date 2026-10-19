package dev.buhanzaz.rwms.analytics.config;

import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Writes safe unauthenticated and forbidden Problem Details responses before analytics controllers are reached. */
@Component
final class AnalyticsSecurityProblemWriter {
  private final ObjectMapper mapper;

  AnalyticsSecurityProblemWriter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
    write(request, response, 401, "ANALYTICS_UNAUTHORIZED", "Authentication is required");
  }

  void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
    write(request, response, 403, "ANALYTICS_FORBIDDEN", "Analytics access is forbidden");
  }

  private void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String detail)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    String correlationId = response.getHeader(CorrelationIdFilter.HEADER_NAME);
    if (correlationId == null) {
      Object requestCorrelation = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
      correlationId = requestCorrelation == null ? null : requestCorrelation.toString();
    }
    try {
      correlationId = UUID.fromString(correlationId).toString();
    } catch (IllegalArgumentException | NullPointerException ignored) {
      correlationId = UUID.randomUUID().toString();
    }
    response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
    Map<String, Object> correlation = new LinkedHashMap<>();
    correlation.put("correlationId", correlationId);
    correlation.put("causationId", null);
    mapper.writeValue(
        response.getOutputStream(),
        Map.of(
            "type", "urn:rwms:problem:" + code.toLowerCase(java.util.Locale.ROOT),
            "title", status == 401 ? "Unauthorized" : "Forbidden",
            "status", status,
            "detail", detail,
            "instance", request.getRequestURI(),
            "code", code,
            "correlation", correlation));
  }
}
