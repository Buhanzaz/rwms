package dev.buhanzaz.rwms.auth.integration.warehouse;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WarehouseValidationProperties.class)
class WarehouseValidationConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "rwms.auth.warehouse-validation", name = "enabled", havingValue = "true")
    WarehouseExistenceClient oauthWarehouseExistenceClient(
            WarehouseValidationProperties properties, ObjectMapper objectMapper) {
        WarehouseValidationProperties.Validated validated = properties.validateEnabledConfiguration();
        return new OAuthWarehouseExistenceClient(
                validated, objectMapper, OAuthWarehouseExistenceClient.httpClient(validated.connectTimeout()));
    }

    @Bean
    @ConditionalOnMissingBean(WarehouseExistenceClient.class)
    WarehouseExistenceClient noOpWarehouseExistenceClient() {
        return new NoOpWarehouseExistenceClient();
    }
}
