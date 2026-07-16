package dev.buhanzaz.rwms.taskboard.config;

import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class TaskBoardSecurityProblemWriter {
  private final ObjectMapper objectMapper;
  private final RwmsProblemDetailFactory problems;

  public void unauthorized(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    write(
        request,
        response,
        HttpStatus.UNAUTHORIZED,
        "TASK_BOARD_UNAUTHORIZED",
        "Bearer token is missing or invalid");
  }

  public void forbidden(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    write(
        request,
        response,
        HttpStatus.FORBIDDEN,
        "TASK_BOARD_FORBIDDEN",
        "Access is denied");
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
            URI.create(
                "urn:rwms:problem:task-board:"
                    + code.toLowerCase(Locale.ROOT).replace('_', '-')),
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
    Object existing = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    UUID value;
    try {
      value = UUID.fromString(String.valueOf(existing));
    } catch (IllegalArgumentException exception) {
      value = UUID.randomUUID();
      request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, value.toString());
    }
    response.setHeader(CorrelationIdFilter.HEADER_NAME, value.toString());
    return value;
  }
}
