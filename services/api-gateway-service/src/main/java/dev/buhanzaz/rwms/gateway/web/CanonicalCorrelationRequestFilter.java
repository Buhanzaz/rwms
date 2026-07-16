package dev.buhanzaz.rwms.gateway.web;

import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

public final class CanonicalCorrelationRequestFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId =
        String.valueOf(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE));
    filterChain.doFilter(new CanonicalRequest(request, correlationId), response);
  }

  private static final class CanonicalRequest extends HttpServletRequestWrapper {
    private final String correlationId;

    private CanonicalRequest(HttpServletRequest request, String correlationId) {
      super(request);
      this.correlationId = correlationId;
    }

    @Override
    public String getHeader(String name) {
      return CorrelationIdFilter.HEADER_NAME.equalsIgnoreCase(name)
          ? correlationId
          : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return CorrelationIdFilter.HEADER_NAME.equalsIgnoreCase(name)
          ? Collections.enumeration(Collections.singleton(correlationId))
          : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
      if (names.stream().noneMatch(CorrelationIdFilter.HEADER_NAME::equalsIgnoreCase)) {
        names.add(CorrelationIdFilter.HEADER_NAME);
      }
      return Collections.enumeration(names);
    }
  }
}
