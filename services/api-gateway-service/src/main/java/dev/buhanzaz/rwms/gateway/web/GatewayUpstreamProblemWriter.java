package dev.buhanzaz.rwms.gateway.web;

import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes one sanitized upstream-failure contract for synchronous and asynchronous proxy routes.
 *
 * <p>Timeouts map to {@code 504}; other connectivity failures map to {@code 502}. The original
 * exception, destination, and request body are never returned to callers.
 */
@Component
@RequiredArgsConstructor
public class GatewayUpstreamProblemWriter {

  private final ObjectMapper objectMapper;
  private final RwmsProblemDetailFactory problems;

  /**
   * Creates a functional-router Problem Details response for a failed proxy exchange.
   *
   * @param error upstream failure
   * @param request failed functional request
   * @return sanitized {@code 502} or {@code 504} response
   */
  public ServerResponse response(Throwable error, ServerRequest request) {
    return response(failure(error), request);
  }

  /**
   * Creates the stable public response used when the optional CAD downstream is not configured.
   *
   * <p>The response intentionally identifies the missing capability without disclosing target
   * topology or configuration values.
   *
   * @param request matched public CAD request
   * @return sanitized {@code 503} Problem Details response
   */
  public ServerResponse cadServiceUnconfigured(ServerRequest request) {
    return response(
        new Failure(
            HttpStatus.SERVICE_UNAVAILABLE,
            "GATEWAY_CAD_SERVICE_UNCONFIGURED",
            "The CAD service is not configured"),
        request);
  }

  private ServerResponse response(Failure failure, ServerRequest request) {
    UUID correlationId = correlation(request.servletRequest());
    return ServerResponse.status(failure.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .header(CorrelationIdFilter.HEADER_NAME, correlationId.toString())
        .body(problem(failure, URI.create(request.servletRequest().getRequestURI()), correlationId));
  }

  /**
   * Writes the equivalent Problem Details payload directly to an asynchronous servlet response.
   *
   * @param error upstream failure
   * @param request failed servlet request
   * @param response servlet response to populate
   * @throws IOException if the response payload cannot be written
   */
  public void write(Throwable error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    Failure failure = failure(error);
    UUID correlationId = correlation(request);
    response.setStatus(failure.status().value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId.toString());
    this.objectMapper.writeValue(
        response.getOutputStream(),
        problem(failure, URI.create(request.getRequestURI()), correlationId));
  }

  private ApiProblem problem(Failure failure, URI instance, UUID correlationId) {
    return this.problems.create(
        URI.create(
            "urn:rwms:problem:gateway:"
                + failure.code().toLowerCase(Locale.ROOT).replace('_', '-')),
        failure.status().getReasonPhrase(),
        failure.status(),
        failure.detail(),
        instance,
        failure.code(),
        new CorrelationContext(correlationId, null));
  }

  private static Failure failure(Throwable error) {
    if (contains(error, HttpTimeoutException.class)
        || contains(error, SocketTimeoutException.class)
        || contains(error, TimeoutException.class)) {
      return new Failure(
          HttpStatus.GATEWAY_TIMEOUT,
          "GATEWAY_UPSTREAM_TIMEOUT",
          "The downstream service did not respond in time");
    }
    return new Failure(
        HttpStatus.BAD_GATEWAY,
        "GATEWAY_UPSTREAM_UNAVAILABLE",
        "The downstream service is unavailable");
  }

  private static boolean contains(Throwable error, Class<? extends Throwable> type) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (type.isInstance(current)) {
        return true;
      }
    }
    return false;
  }

  private static UUID correlation(HttpServletRequest request) {
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    try {
      return UUID.fromString(String.valueOf(value));
    } catch (IllegalArgumentException exception) {
      return UUID.randomUUID();
    }
  }

  private record Failure(HttpStatus status, String code, String detail) {}
}
