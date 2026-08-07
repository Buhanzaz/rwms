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
 * Proxies a bounded HTML-import commit with a five-minute downstream read deadline.
 *
 * <p>The dedicated timeout protects ordinary gateway worker threads while still allowing the
 * downstream asset-service import operation to complete within its supported public contract.
 */
@Component
final class HtmlImportCommitProxyHandler
    implements HandlerFunction<ServerResponse>, ApplicationListener<ContextRefreshedEvent> {

  private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);

  private final ProxyExchangeHandlerFunction delegate;

  HtmlImportCommitProxyHandler(
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
   * Delegates the matched bounded import-commit request to Spring Cloud Gateway's proxy exchange.
   *
   * @param request matched public asset import request
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
