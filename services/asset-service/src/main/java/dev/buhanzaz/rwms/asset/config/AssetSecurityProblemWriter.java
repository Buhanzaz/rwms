package dev.buhanzaz.rwms.asset.config;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes asset authentication and authorization failures as safe Problem Details responses.
 */
@Component
public class AssetSecurityProblemWriter {
  private final ObjectMapper objectMapper;
  private final RwmsProblemDetailFactory problems;

  public AssetSecurityProblemWriter(ObjectMapper objectMapper, RwmsProblemDetailFactory problems) {
    this.objectMapper = objectMapper;
    this.problems = problems;
  }

  public void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
    write(request, response, HttpStatus.UNAUTHORIZED, "ASSET_UNAUTHORIZED", "Bearer token is missing or invalid");
  }

  public void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
    write(request, response, HttpStatus.FORBIDDEN, "ASSET_FORBIDDEN", "Access is denied");
  }

  private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String detail)
      throws IOException {
    UUID correlationId;
    try {
      correlationId = UUID.fromString(String.valueOf(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)));
    } catch (IllegalArgumentException exception) {
      correlationId = UUID.randomUUID();
      request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, correlationId.toString());
    }
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId.toString());
    ApiProblem body = problems.create(
        URI.create("urn:rwms:problem:asset:" + code.toLowerCase(Locale.ROOT).replace('_', '-')),
        status.getReasonPhrase(), status, detail, URI.create(request.getRequestURI()), code,
        new CorrelationContext(correlationId, null));
    objectMapper.writeValue(response.getOutputStream(), body);
  }
}
