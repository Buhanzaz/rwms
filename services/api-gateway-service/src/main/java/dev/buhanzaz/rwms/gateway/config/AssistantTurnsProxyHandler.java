package dev.buhanzaz.rwms.gateway.config;

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
 * Proxies a long-lived assistant-turn response without the ordinary gateway read deadline.
 *
 * <p>The route remains transport-only: this handler forwards the request and streaming response
 * but does not accumulate assistant state or interpret tool-call events.
 */
@Component
final class AssistantTurnsProxyHandler
    implements HandlerFunction<ServerResponse>, ApplicationListener<ContextRefreshedEvent> {

  private final ProxyExchangeHandlerFunction delegate;

  AssistantTurnsProxyHandler(
      ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
      HttpClientSettings httpClientSettings,
      RestClient.Builder restClientBuilder,
      GatewayMvcProperties gatewayMvcProperties,
      ObjectProvider<RequestHttpHeadersFilter> requestHeaderFilters,
      ObjectProvider<ResponseHttpHeadersFilter> responseHeaderFilters) {
    var requestFactory = requestFactoryBuilder.build(httpClientSettings.withReadTimeout(null));
    var proxyExchange =
        new RestClientProxyExchange(
            restClientBuilder.clone().requestFactory(requestFactory).build(),
            gatewayMvcProperties);
    delegate =
        new ProxyExchangeHandlerFunction(
            proxyExchange, requestHeaderFilters, responseHeaderFilters);
  }

  /**
   * Delegates the matched assistant-turn request to Spring Cloud Gateway's proxy exchange.
   *
   * @param request matched public assistant request
   * @return proxied streaming response
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
