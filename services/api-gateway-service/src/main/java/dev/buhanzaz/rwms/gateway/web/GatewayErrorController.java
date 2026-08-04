package dev.buhanzaz.rwms.gateway.web;

import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GatewayErrorController implements ErrorController {

  private final RwmsProblemDetailFactory problems;

  @RequestMapping(value = "/error", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
  ResponseEntity<ApiProblem> error(HttpServletRequest request, HttpServletResponse response) {
    HttpStatus status = resolveStatus(request);
    if (response.isCommitted()) {
      return ResponseEntity.status(status).build();
    }
    String code = switch (status) {
      case BAD_GATEWAY -> "GATEWAY_UPSTREAM_UNAVAILABLE";
      case GATEWAY_TIMEOUT -> "GATEWAY_UPSTREAM_TIMEOUT";
      case NOT_FOUND -> "GATEWAY_ROUTE_NOT_FOUND";
      default -> "GATEWAY_REQUEST_FAILED";
    };
    UUID correlationId = correlationId(request);
    ApiProblem problem =
        problems.create(
            URI.create("urn:rwms:problem:gateway:" + code.toLowerCase(Locale.ROOT).replace('_', '-')),
            status.getReasonPhrase(),
            status,
            safeDetail(status),
            URI.create(originalPath(request)),
            code,
            new CorrelationContext(correlationId, null));
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .header(CorrelationIdFilter.HEADER_NAME, correlationId.toString())
        .body(problem);
  }

  private static HttpStatus resolveStatus(HttpServletRequest request) {
    Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    if (value instanceof Integer status) {
      HttpStatus resolved = HttpStatus.resolve(status);
      if (resolved != null) {
        return resolved;
      }
    }
    return HttpStatus.INTERNAL_SERVER_ERROR;
  }

  private static String safeDetail(HttpStatus status) {
    return switch (status) {
      case BAD_GATEWAY -> "The downstream service is unavailable";
      case GATEWAY_TIMEOUT -> "The downstream service did not respond in time";
      case NOT_FOUND -> "No public gateway route matched the request";
      default -> "The gateway could not complete the request";
    };
  }

  private static UUID correlationId(HttpServletRequest request) {
    try {
      return UUID.fromString(
          String.valueOf(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)));
    } catch (IllegalArgumentException exception) {
      return UUID.randomUUID();
    }
  }

  private static String originalPath(HttpServletRequest request) {
    Object value = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    return value == null ? request.getRequestURI() : value.toString();
  }
}
