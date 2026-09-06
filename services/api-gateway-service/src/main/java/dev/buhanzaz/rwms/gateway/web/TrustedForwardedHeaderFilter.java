package dev.buhanzaz.rwms.gateway.web;

import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Removes all client-controlled forwarding headers at the public boundary.
 *
 * <p>The gateway reconstructs the few forwarding headers auth-service needs from trusted
 * configuration. No caller can choose a host, scheme, prefix, or client IP by submitting an
 * {@code X-Forwarded-*} or {@code Forwarded} header.
 * A configured immediate proxy may supply one overwritten {@code X-Real-IP}; its numeric value
 * is canonicalized before all forwarding headers are hidden from downstream routing.
 */
public final class TrustedForwardedHeaderFilter extends OncePerRequestFilter {

  private static final Set<String> FORWARDED_HEADERS =
      Set.of("forwarded", "front-end-https", "x-real-ip", "x-url-scheme");

  private final Set<String> trustedProxyAddresses;
  private final ObjectMapper objectMapper;
  private final RwmsProblemDetailFactory problems;

  /** Creates the stateless forwarding-header sanitizer for configured numeric proxy peers. */
  public TrustedForwardedHeaderFilter(
      List<String> trustedProxyAddresses,
      ObjectMapper objectMapper,
      RwmsProblemDetailFactory problems) {
    try {
      this.trustedProxyAddresses =
          trustedProxyAddresses.stream()
              .map(TrustedForwardedHeaderFilter::canonicalIpLiteral)
              .collect(Collectors.toUnmodifiableSet());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Trusted proxy addresses must contain only IP literals");
    }
    this.objectMapper = objectMapper;
    this.problems = problems;
  }

  /** Wraps the request in a view that hides all forwarding-related headers. */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String sourceAddress = canonicalIpLiteral(request.getRemoteAddr());
    if (trustedProxyAddresses.contains(sourceAddress)) {
      List<String> forwardedAddresses = Collections.list(request.getHeaders("X-Real-IP"));
      if (!forwardedAddresses.isEmpty()) {
        if (forwardedAddresses.size() != 1) {
          rejectInvalidClientAddress(request, response);
          return;
        }
        try {
          sourceAddress = canonicalIpLiteral(forwardedAddresses.getFirst());
        } catch (IllegalArgumentException exception) {
          rejectInvalidClientAddress(request, response);
          return;
        }
      }
    }
    filterChain.doFilter(new SanitizedRequest(request, sourceAddress), response);
  }

  private void rejectInvalidClientAddress(
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    UUID correlationId = correlation(request.getHeader(CorrelationIdFilter.HEADER_NAME));
    request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, correlationId.toString());
    response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId.toString());
    response.setStatus(HttpStatus.BAD_REQUEST.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        problems.create(
            URI.create("urn:rwms:problem:gateway:invalid-client-address"),
            "Bad Request",
            HttpStatus.BAD_REQUEST,
            "The trusted proxy supplied an invalid client address",
            URI.create(request.getRequestURI()),
            "GATEWAY_INVALID_CLIENT_ADDRESS",
            new CorrelationContext(correlationId, null)));
  }

  private static String canonicalIpLiteral(String candidate) {
    if (candidate == null || candidate.isEmpty() || !candidate.equals(candidate.trim())) {
      throw new IllegalArgumentException("IP literal is blank or padded");
    }
    return InetAddress.ofLiteral(candidate).getHostAddress();
  }

  private static UUID correlation(String candidate) {
    try {
      return UUID.fromString(candidate);
    } catch (IllegalArgumentException | NullPointerException exception) {
      return UUID.randomUUID();
    }
  }

  private static boolean forwarded(String name) {
    if (name == null) {
      return false;
    }
    String normalized = name.toLowerCase(Locale.ROOT);
    return normalized.startsWith("x-forwarded-") || FORWARDED_HEADERS.contains(normalized);
  }

  private static final class SanitizedRequest extends HttpServletRequestWrapper {
    private final String sourceAddress;

    private SanitizedRequest(HttpServletRequest request, String sourceAddress) {
      super(request);
      this.sourceAddress = sourceAddress;
    }

    @Override
    public String getRemoteAddr() {
      return sourceAddress;
    }

    @Override
    public String getHeader(String name) {
      return forwarded(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return forwarded(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      List<String> names =
          Collections.list(super.getHeaderNames()).stream()
              .filter(name -> !forwarded(name))
              .collect(Collectors.toList());
      return Collections.enumeration(names);
    }
  }
}
