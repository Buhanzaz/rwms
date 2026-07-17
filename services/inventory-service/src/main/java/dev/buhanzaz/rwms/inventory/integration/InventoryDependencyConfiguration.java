package dev.buhanzaz.rwms.inventory.integration;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InventoryDependencyProperties.class)
class InventoryDependencyConfiguration {
  @Bean
  @Profile({"dev", "test"})
  @ConditionalOnProperty(
      prefix = "rwms.inventory.dependencies",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  InventoryDependencyGateway disabledInventoryDependencyGateway() {
    return new DisabledInventoryDependencyGateway();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "rwms.inventory.dependencies",
      name = "enabled",
      havingValue = "true")
  InventoryDependencyGateway httpInventoryDependencyGateway(
      InventoryDependencyProperties properties) {
    InventoryDependencyProperties.Validated validated = properties.validated();
    var registrations =
        new InMemoryClientRegistrationRepository(
            registration(validated, "inventory-warehouse", "warehouse.read"),
            registration(validated, "inventory-asset", "asset.inventory"),
            registration(validated, "inventory-maintenance", "maintenance.inventory"));
    var manager =
        new AuthorizedClientServiceOAuth2AuthorizedClientManager(
            registrations, new InMemoryOAuth2AuthorizedClientService(registrations));
    manager.setAuthorizedClientProvider(
        OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
    HttpClient client = HttpClient.newBuilder().connectTimeout(validated.connectTimeout()).build();
    var requestFactory = new JdkClientHttpRequestFactory(client);
    requestFactory.setReadTimeout(validated.readTimeout());
    return new HttpInventoryDependencyGateway(
        RestClient.builder().requestFactory(requestFactory).build(), manager, validated);
  }

  private static ClientRegistration registration(
      InventoryDependencyProperties.Validated properties, String id, String scope) {
    return ClientRegistration.withRegistrationId(id)
        .tokenUri(properties.tokenUri().toString())
        .clientId(properties.clientId())
        .clientSecret(properties.clientSecret())
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .scope(scope)
        .build();
  }
}
