package dev.buhanzaz.rwms.maintenance.integration;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MaintenanceDependencyProperties.class)
class MaintenanceDependencyConfiguration {
  @Bean
  @Profile({"dev", "test"})
  @ConditionalOnProperty(
      prefix = "rwms.maintenance.dependencies", name = "enabled", havingValue = "false",
      matchIfMissing = true)
  MaintenanceDependencyGateway noOpMaintenanceDependencyGateway() {
    return new NoOpMaintenanceDependencyGateway();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "rwms.maintenance.dependencies", name = "enabled", havingValue = "true")
  MaintenanceDependencyGateway httpMaintenanceDependencyGateway(
      MaintenanceDependencyProperties properties) {
    MaintenanceDependencyProperties.Validated validated = properties.validated();
    ClientRegistration asset = registration(validated, "maintenance-asset", "asset.maintenance");
    ClientRegistration task = registration(
        validated, "maintenance-task-board", "task-board.task-sync");
    var registrations = new InMemoryClientRegistrationRepository(asset, task);
    var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
        registrations, new InMemoryOAuth2AuthorizedClientService(registrations));
    manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
        .clientCredentials().build());
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(validated.connectTimeout()).build();
    var requestFactory = new JdkClientHttpRequestFactory(client);
    requestFactory.setReadTimeout(validated.readTimeout());
    RestClient rest = RestClient.builder().requestFactory(requestFactory).build();
    return new HttpMaintenanceDependencyGateway(rest, manager, validated);
  }

  private static ClientRegistration registration(
      MaintenanceDependencyProperties.Validated properties, String id, String scope) {
    return ClientRegistration.withRegistrationId(id)
        .tokenUri(properties.tokenUri().toString())
        .clientId(properties.clientId())
        .clientSecret(properties.clientSecret())
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .scope(scope)
        .build();
  }
}
