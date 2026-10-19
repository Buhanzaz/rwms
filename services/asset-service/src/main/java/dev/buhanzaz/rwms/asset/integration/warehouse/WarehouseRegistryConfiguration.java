package dev.buhanzaz.rwms.asset.integration.warehouse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Selects the enabled or disabled asset warehouse-registry client from configuration.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WarehouseRegistryProperties.class)
class WarehouseRegistryConfiguration {
  @Bean
  @ConditionalOnProperty(prefix = "rwms.asset.warehouse-registry", name = "enabled", havingValue = "true")
  WarehouseRegistryClient oauthWarehouseRegistryClient(WarehouseRegistryProperties properties, ObjectMapper mapper) {
    WarehouseRegistryProperties.Validated validated = properties.requireEnabledConfiguration();
    return new OAuthWarehouseRegistryClient(validated, mapper, OAuthWarehouseRegistryClient.httpClient(validated.connectTimeout()));
  }

  @Bean
  @ConditionalOnMissingBean(WarehouseRegistryClient.class)
  WarehouseRegistryClient noOpWarehouseRegistryClient() { return new NoOpWarehouseRegistryClient(); }
}
