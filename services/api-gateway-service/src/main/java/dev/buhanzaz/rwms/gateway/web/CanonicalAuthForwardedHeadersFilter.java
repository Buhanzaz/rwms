package dev.buhanzaz.rwms.gateway.web;

import java.net.URI;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Supplies auth-service with forwarding metadata derived solely from the configured public URI.
 *
 * <p>Only the {@code auth-service} route receives these headers. Incoming client-supplied
 * forwarding headers have already been removed by {@link TrustedForwardedHeaderFilter}, so auth
 * redirects and registration client identity cannot be influenced by a spoofed request header.
 */
public final class CanonicalAuthForwardedHeadersFilter
    implements HttpHeadersFilter.RequestHttpHeadersFilter, Ordered {

  private final String host;
  private final String scheme;
  private final String port;

  /**
   * Derives canonical authority metadata from the browser-visible gateway base URI.
   *
   * @param publicBase validated public gateway origin
   */
  public CanonicalAuthForwardedHeadersFilter(URI publicBase) {
    this.host = publicBase.getRawAuthority();
    this.scheme = publicBase.getScheme();
    this.port =
        Integer.toString(
            publicBase.getPort() >= 0
                ? publicBase.getPort()
                : ("https".equalsIgnoreCase(scheme) ? 443 : 80));
  }

  /**
   * Adds canonical forwarding headers to auth-service requests and leaves all other routes
   * unchanged.
   */
  @Override
  public HttpHeaders apply(HttpHeaders headers, ServerRequest request) {
    HttpHeaders result = new HttpHeaders();
    result.putAll(headers);
    String routeId = MvcUtils.getAttribute(request, MvcUtils.GATEWAY_ROUTE_ID_ATTR);
    if ("auth-service".equals(routeId)) {
      result.set("X-Forwarded-Host", host);
      result.set("X-Forwarded-Proto", scheme);
      result.set("X-Forwarded-Port", port);
      result.set("X-Forwarded-Prefix", "/auth");
      result.set("X-Forwarded-For", request.servletRequest().getRemoteAddr());
    }
    return result;
  }

  /** Runs after route header sanitization but before the downstream auth request is sent. */
  @Override
  public int getOrder() {
    return 100;
  }
}
