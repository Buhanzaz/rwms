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

/** Keeps the media warehouse invalidation stream open beyond the normal proxy read timeout. */
@Component
final class MediaEventsProxyHandler
    implements HandlerFunction<ServerResponse>, ApplicationListener<ContextRefreshedEvent> {
  private final ProxyExchangeHandlerFunction delegate;

  MediaEventsProxyHandler(
      ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
      HttpClientSettings httpClientSettings,
      RestClient.Builder restClientBuilder,
      GatewayMvcProperties gatewayMvcProperties,
      ObjectProvider<RequestHttpHeadersFilter> requestHeaderFilters,
      ObjectProvider<ResponseHttpHeadersFilter> responseHeaderFilters) {
    var requestFactory = requestFactoryBuilder.build(httpClientSettings.withReadTimeout(null));
    var proxyExchange = new RestClientProxyExchange(
        restClientBuilder.clone().requestFactory(requestFactory).build(), gatewayMvcProperties);
    delegate = new ProxyExchangeHandlerFunction(proxyExchange, requestHeaderFilters, responseHeaderFilters);
  }

  @Override
  public ServerResponse handle(ServerRequest request) throws Exception {
    return delegate.handle(request);
  }

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    delegate.onApplicationEvent(event);
  }
}
