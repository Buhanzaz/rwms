package dev.buhanzaz.rwms.logistics.integration;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;

/**
 * Builds the private logistics dependency gateway from local configuration. Enabled clients use
 * service credentials; incoming user credentials never cross this boundary.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LogisticsDependencyProperties.class)
class LogisticsDependencyConfiguration {

  @Bean
  LogisticsDependencyGateway logisticsDependencyGateway(LogisticsDependencyProperties properties) {
    if (!properties.enabled()) return new DisabledLogisticsDependencyGateway();

    LogisticsDependencyProperties.Validated validated = properties.validated();
    ClientRegistration asset =
        registration(validated, "logistics-asset", "asset.logistics");
    ClientRegistration warehouse =
        registration(validated, "logistics-warehouse", "warehouse.logistics");
    ClientRegistration warehouseTimeZone =
        registration(validated, "logistics-warehouse-timezone", "warehouse.timezone.read");
    ClientRegistration warehouseOperation =
        registration(validated, "logistics-warehouse-operation", "warehouse.operation.mark");
    ClientRegistration warehouseLifecycleRead =
        registration(validated, "logistics-warehouse-lifecycle-read", "warehouse.lifecycle.read");
    ClientRegistration warehouseLifecycleConfirm =
        registration(
            validated, "logistics-warehouse-lifecycle-confirm", "warehouse.lifecycle.confirm");
    ClientRegistration maintenance =
        registration(validated, "logistics-maintenance", "maintenance.logistics");
    ClientRegistration media = registration(validated, "logistics-media", "media.logistics");
    ClientRegistration taskBoard =
        registration(validated, "logistics-task-board", "task-board.logistics");
    var registrations =
        new InMemoryClientRegistrationRepository(
            asset,
            warehouse,
            warehouseTimeZone,
            warehouseOperation,
            warehouseLifecycleRead,
            warehouseLifecycleConfirm,
            maintenance,
            media,
            taskBoard);
    var manager =
        new AuthorizedClientServiceOAuth2AuthorizedClientManager(
            registrations, new InMemoryOAuth2AuthorizedClientService(registrations));
    manager.setAuthorizedClientProvider(
        OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());

    HttpClient client =
        HttpClient.newBuilder().connectTimeout(validated.connectTimeout()).build();
    var requestFactory = new JdkClientHttpRequestFactory(client);
    requestFactory.setReadTimeout(validated.readTimeout());
    RestClient rest = RestClient.builder().requestFactory(requestFactory).build();
    return new HttpLogisticsDependencyGateway(rest, manager, validated);
  }

  private static ClientRegistration registration(
      LogisticsDependencyProperties.Validated properties, String id, String scope) {
    return ClientRegistration.withRegistrationId(id)
        .tokenUri(properties.tokenUri().toString())
        .clientId(properties.clientId())
        .clientSecret(properties.clientSecret())
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .scope(scope)
        .build();
  }
}
