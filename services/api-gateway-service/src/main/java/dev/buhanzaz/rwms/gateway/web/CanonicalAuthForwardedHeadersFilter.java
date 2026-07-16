package dev.buhanzaz.rwms.gateway.web;

import java.net.URI;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.ServerRequest;

public final class CanonicalAuthForwardedHeadersFilter
    implements HttpHeadersFilter.RequestHttpHeadersFilter, Ordered {

  private final String host;
  private final String scheme;
  private final String port;

  public CanonicalAuthForwardedHeadersFilter(URI publicBase) {
    this.host = publicBase.getRawAuthority();
    this.scheme = publicBase.getScheme();
    this.port =
        Integer.toString(
            publicBase.getPort() >= 0
                ? publicBase.getPort()
                : ("https".equalsIgnoreCase(scheme) ? 443 : 80));
  }

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
    }
    return result;
  }

  @Override
  public int getOrder() {
    return 100;
  }
}
