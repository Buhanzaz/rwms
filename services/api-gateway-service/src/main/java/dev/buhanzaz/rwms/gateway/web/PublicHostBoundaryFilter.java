package dev.buhanzaz.rwms.gateway.web;

import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Enforces the configured browser-visible {@code Host} boundary before public routing.
 *
 * <p>A request for another host receives a correlation-aware {@code 400} problem rather than
 * being proxied to a service. Health endpoints remain reachable for local orchestration probes.
 */
public final class PublicHostBoundaryFilter extends OncePerRequestFilter {

  private final String publicHost;
  private final int publicPort;
  private final ObjectMapper objectMapper;
  private final RwmsProblemDetailFactory problems;

  /**
   * Creates the host boundary from the validated public gateway origin.
   *
   * @param publicBase configured browser-visible origin
   * @param objectMapper JSON writer for the rejection problem
   * @param problems RWMS Problem Details factory
   */
  public PublicHostBoundaryFilter(
      URI publicBase, ObjectMapper objectMapper, RwmsProblemDetailFactory problems) {
    this.publicHost = publicBase.getHost().toLowerCase(Locale.ROOT);
    this.publicPort =
        publicBase.getPort() >= 0
            ? publicBase.getPort()
            : ("https".equalsIgnoreCase(publicBase.getScheme()) ? 443 : 80);
    this.objectMapper = objectMapper;
    this.problems = problems;
  }

  /** Passes trusted hosts through and rejects every other public request before routing. */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String host = request.getHeader("Host");
    if (request.getRequestURI().equals("/actuator/health")
        || request.getRequestURI().startsWith("/actuator/health/")
        || matchesPublicAuthority(host)) {
      filterChain.doFilter(request, response);
      return;
    }
    UUID correlationId = correlation(request.getHeader(CorrelationIdFilter.HEADER_NAME));
    request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, correlationId.toString());
    response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId.toString());
    response.setStatus(HttpStatus.BAD_REQUEST.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        problems.create(
            URI.create("urn:rwms:problem:gateway:invalid-host"),
            "Bad Request",
            HttpStatus.BAD_REQUEST,
            "The request host is not accepted by this gateway",
            URI.create(request.getRequestURI()),
            "GATEWAY_INVALID_HOST",
            new CorrelationContext(correlationId, null)));
  }

  private boolean matchesPublicAuthority(String hostHeader) {
    if (hostHeader == null) {
      return false;
    }
    try {
      String value = "http://" + hostHeader;
      URI authority = URI.create(value);
      String host = authority.getHost();
      int port = authority.getPort();
      int effectivePort = port >= 0 ? port : publicPort;
      return host != null
          && authority.getUserInfo() == null
          && host.toLowerCase(Locale.ROOT).equals(publicHost)
          && effectivePort == publicPort;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static UUID correlation(String candidate) {
    try {
      return UUID.fromString(candidate);
    } catch (IllegalArgumentException | NullPointerException exception) {
      return UUID.randomUUID();
    }
  }
}
