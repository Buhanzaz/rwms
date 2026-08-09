package dev.buhanzaz.rwms.inventory.config;

import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes inventory authentication and authorization failures as safe Problem Details responses.
 */
@Component
final class InventorySecurityProblemWriter {
  private final ObjectMapper mapper;

  InventorySecurityProblemWriter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
    write(request, response, 401, "INVENTORY_FORBIDDEN", "Authentication is required");
  }

  void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
    write(request, response, 403, "INVENTORY_FORBIDDEN", "Inventory access is forbidden");
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
    String correlation = response.getHeader(CorrelationIdFilter.HEADER_NAME);
    mapper.writeValue(
        response.getOutputStream(),
        Map.of(
            "type",
            "urn:rwms:problem:" + code.toLowerCase(),
            "title",
            status == 401 ? "Unauthorized" : "Forbidden",
            "status",
            status,
            "detail",
            detail,
            "instance",
            request.getRequestURI(),
            "code",
            code,
            "violations",
            List.of(),
            "correlation",
            Map.of("correlationId", correlation == null ? "" : correlation)));
  }
}
