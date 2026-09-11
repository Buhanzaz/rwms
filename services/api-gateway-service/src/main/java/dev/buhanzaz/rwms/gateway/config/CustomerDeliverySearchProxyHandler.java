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

/** Allows 55 seconds for truck routing, leaving time for the public edge's 60-second deadline. */
@Component
final class CustomerDeliverySearchProxyHandler
    implements HandlerFunction<ServerResponse>, ApplicationListener<ContextRefreshedEvent> {
  private final ProxyExchangeHandlerFunction delegate;

  CustomerDeliverySearchProxyHandler(
      ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
      HttpClientSettings httpClientSettings,
      RestClient.Builder restClientBuilder,
      GatewayMvcProperties gatewayMvcProperties,
      ObjectProvider<RequestHttpHeadersFilter> requestHeaderFilters,
      ObjectProvider<ResponseHttpHeadersFilter> responseHeaderFilters) {
    var requestFactory =
        requestFactoryBuilder.build(httpClientSettings.withReadTimeout(Duration.ofSeconds(55)));
    var proxyExchange = new RestClientProxyExchange(
        restClientBuilder.clone().requestFactory(requestFactory).build(), gatewayMvcProperties);
    delegate = new ProxyExchangeHandlerFunction(proxyExchange, requestHeaderFilters, responseHeaderFilters);
  }

  /** Forwards the authenticated search and preserves downstream responses. */
  @Override
  public ServerResponse handle(ServerRequest request) throws Exception {
    return delegate.handle(request);
  }

  /** Initializes the delegate after ordered transport header filters are available. */
  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    delegate.onApplicationEvent(event);
  }
}
