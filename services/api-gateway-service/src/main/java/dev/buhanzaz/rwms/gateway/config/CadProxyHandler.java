package dev.buhanzaz.rwms.gateway.config;

import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.RequestHttpHeadersFilter;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.ResponseHttpHeadersFilter;
import org.springframework.cloud.gateway.server.mvc.handler.ProxyExchangeHandlerFunction;
import org.springframework.cloud.gateway.server.mvc.handler.RestClientProxyExchange;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Proxies CAD project and document requests with a forty-five-second downstream read deadline.
 *
 * <p>The Spring Gateway exchange streams request bodies instead of aggregating them, so supported
 * CAD documents retain their bytes through the gateway. Deployment ingress remains responsible
 * for its configured body-size boundary.
 */
@Component
final class CadProxyHandler
    implements HandlerFunction<ServerResponse>, ApplicationListener<ContextRefreshedEvent> {

  private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);

  private final ProxyExchangeHandlerFunction delegate;

  CadProxyHandler(
      ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
      HttpClientSettings httpClientSettings,
      RestClient.Builder restClientBuilder,
      GatewayMvcProperties gatewayMvcProperties,
      ObjectProvider<RequestHttpHeadersFilter> requestHeaderFilters,
      ObjectProvider<ResponseHttpHeadersFilter> responseHeaderFilters) {
    var requestFactory =
        requestFactoryBuilder.build(httpClientSettings.withReadTimeout(READ_TIMEOUT));
    var proxyExchange =
        new RestClientProxyExchange(
            restClientBuilder.clone().requestFactory(requestFactory).build(),
            gatewayMvcProperties);
    delegate =
        new ProxyExchangeHandlerFunction(
            proxyExchange, requestHeaderFilters, responseHeaderFilters);
  }

  /**
   * Delegates the matched CAD request to Spring Cloud Gateway's streaming proxy exchange.
   *
   * @param request matched public CAD request
   * @return proxied downstream response
   * @throws Exception when proxy setup or exchange fails before the response is committed
   */
  @Override
  public ServerResponse handle(ServerRequest request) throws Exception {
    return delegate.handle(request);
  }

  /** Completes delegate initialization after all ordered gateway header filters are available. */
  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    delegate.onApplicationEvent(event);
  }
}
