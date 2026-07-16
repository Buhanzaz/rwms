package dev.buhanzaz.rwms.gateway.security;

import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class GatewaySecurityProblemWriter {

  private final ObjectMapper objectMapper;
  private final RwmsProblemDetailFactory problems;

  public void unauthorized(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    write(request, response, HttpStatus.UNAUTHORIZED, "GATEWAY_UNAUTHORIZED", "Bearer token is missing or invalid");
  }

  public void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
    write(request, response, HttpStatus.FORBIDDEN, "GATEWAY_FORBIDDEN", "Access is denied");
  }

  private void write(
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      String code,
      String detail)
      throws IOException {
    UUID correlationId = correlation(request, response);
    ApiProblem problem =
        problems.create(
            URI.create("urn:rwms:problem:gateway:" + code.toLowerCase(Locale.ROOT).replace('_', '-')),
            status.getReasonPhrase(),
            status,
            detail,
            URI.create(request.getRequestURI()),
            code,
            new CorrelationContext(correlationId, null));
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), problem);
  }

  private UUID correlation(HttpServletRequest request, HttpServletResponse response) {
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    UUID correlationId;
    try {
      correlationId = UUID.fromString(String.valueOf(value));
    } catch (IllegalArgumentException exception) {
      correlationId = UUID.randomUUID();
      request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, correlationId.toString());
    }
    response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId.toString());
    return correlationId;
  }
}
