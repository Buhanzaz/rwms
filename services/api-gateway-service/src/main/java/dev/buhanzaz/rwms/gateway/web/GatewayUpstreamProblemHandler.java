package dev.buhanzaz.rwms.gateway.web;

import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Component
@RequiredArgsConstructor
public class GatewayUpstreamProblemHandler {

  private final RwmsProblemDetailFactory problems;

  public boolean supports(Throwable error) {
    return hasCause(error, ResourceAccessException.class);
  }

  public ServerResponse handle(Throwable error, ServerRequest request) {
    HttpStatus status = isTimeout(error) ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
    String code =
        status == HttpStatus.GATEWAY_TIMEOUT
            ? "GATEWAY_UPSTREAM_TIMEOUT"
            : "GATEWAY_UPSTREAM_UNAVAILABLE";
    String detail =
        status == HttpStatus.GATEWAY_TIMEOUT
            ? "The downstream service did not respond in time"
            : "The downstream service is unavailable";
    UUID correlationId = correlation(request);
    ApiProblem problem =
        problems.create(
            URI.create(
                "urn:rwms:problem:gateway:"
                    + code.toLowerCase(Locale.ROOT).replace('_', '-')),
            status.getReasonPhrase(),
            status,
            detail,
            URI.create(request.servletRequest().getRequestURI()),
            code,
            new CorrelationContext(correlationId, null));
    return ServerResponse.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .header(CorrelationIdFilter.HEADER_NAME, correlationId.toString())
        .body(problem);
  }

  private static boolean isTimeout(Throwable error) {
    return hasCause(error, HttpTimeoutException.class)
        || hasCause(error, SocketTimeoutException.class);
  }

  private static <T extends Throwable> boolean hasCause(Throwable error, Class<T> type) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (type.isInstance(current)) {
        return true;
      }
    }
    return false;
  }

  private static UUID correlation(ServerRequest request) {
    Object value = request.servletRequest().getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    try {
      return UUID.fromString(String.valueOf(value));
    } catch (IllegalArgumentException exception) {
      return UUID.randomUUID();
    }
  }
}
