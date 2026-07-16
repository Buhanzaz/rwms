package dev.buhanzaz.rwms.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.filter.OncePerRequestFilter;

public final class TrustedForwardedHeaderFilter extends OncePerRequestFilter {

  private static final Set<String> FORWARDED_HEADERS =
      Set.of("forwarded", "front-end-https", "x-url-scheme");

  public TrustedForwardedHeaderFilter() {}

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    filterChain.doFilter(new SanitizedRequest(request), response);
  }

  private static boolean forwarded(String name) {
    if (name == null) {
      return false;
    }
    String normalized = name.toLowerCase(Locale.ROOT);
    return normalized.startsWith("x-forwarded-") || FORWARDED_HEADERS.contains(normalized);
  }

  private static final class SanitizedRequest extends HttpServletRequestWrapper {
    private SanitizedRequest(HttpServletRequest request) {
      super(request);
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
