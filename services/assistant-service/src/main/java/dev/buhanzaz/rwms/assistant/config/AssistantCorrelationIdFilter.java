package dev.buhanzaz.rwms.assistant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-local correlation transport. It mirrors the shared service contract
 * while this independently buildable module is awaiting root inclusion.
 */
@Component
public class AssistantCorrelationIdFilter extends OncePerRequestFilter {
  public static final String HEADER_NAME = "X-Correlation-Id";
  public static final String REQUEST_ATTRIBUTE =
      AssistantCorrelationIdFilter.class.getName() + ".correlationId";
  private static final String MDC_KEY = "correlationId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = resolve(request.getHeader(HEADER_NAME));
    request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
    response.setHeader(HEADER_NAME, correlationId);
    String previous = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      if (previous == null) {
        MDC.remove(MDC_KEY);
      } else {
        MDC.put(MDC_KEY, previous);
      }
    }
  }

  private static String resolve(String candidate) {
    if (candidate != null && candidate.length() == 36) {
      try {
        return UUID.fromString(candidate).toString();
      } catch (IllegalArgumentException ignored) {
        // Do not propagate arbitrary external correlation ids.
      }
    }
    return UUID.randomUUID().toString();
  }
}
