package dev.buhanzaz.rwms.assistant.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class AssistantSecurityProblemWriter {
  private final ObjectMapper mapper;

  AssistantSecurityProblemWriter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
    write(request, response, 401, "ASSISTANT_UNAUTHORIZED", "Authentication is required");
  }

  void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
    write(request, response, 403, "ASSISTANT_FORBIDDEN", "Assistant access is forbidden");
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
    String correlationId = response.getHeader(AssistantCorrelationIdFilter.HEADER_NAME);
    if (correlationId == null) {
      Object value = request.getAttribute(AssistantCorrelationIdFilter.REQUEST_ATTRIBUTE);
      correlationId = value instanceof String text ? text : null;
    }
    try {
      correlationId = UUID.fromString(correlationId).toString();
    } catch (IllegalArgumentException | NullPointerException ignored) {
      correlationId = UUID.randomUUID().toString();
    }
    response.setHeader(AssistantCorrelationIdFilter.HEADER_NAME, correlationId);
    Map<String, Object> correlation = new LinkedHashMap<>();
    correlation.put("correlationId", correlationId);
    correlation.put("causationId", null);
    Map<String, Object> problem = new LinkedHashMap<>();
    problem.put("type", "urn:rwms:problem:" + code.toLowerCase(java.util.Locale.ROOT));
    problem.put("title", status == 401 ? "Unauthorized" : "Forbidden");
    problem.put("status", status);
    problem.put("detail", detail);
    problem.put("instance", request.getRequestURI());
    problem.put("code", code);
    problem.put("correlation", correlation);
    mapper.writeValue(response.getOutputStream(), problem);
  }
}
