package dev.buhanzaz.rwms.auth.integration.warehouse;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the remote warehouse-validation adapter only for an explicitly enabled deployment and
 * otherwise provides the dependency-free no-op port.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WarehouseValidationProperties.class)
class WarehouseValidationConfiguration {

    /**
     * Builds the OAuth-backed validator after all enabled-only configuration checks have passed.
     *
     * @param properties bound warehouse-validation configuration
     * @param objectMapper application JSON mapper used as the strict mapper base
     * @return remote Warehouse Service validator
     */
    @Bean
    @ConditionalOnProperty(prefix = "rwms.auth.warehouse-validation", name = "enabled", havingValue = "true")
    WarehouseExistenceClient oauthWarehouseExistenceClient(
            WarehouseValidationProperties properties, ObjectMapper objectMapper) {
        WarehouseValidationProperties.Validated validated = properties.validateEnabledConfiguration();
        return new OAuthWarehouseExistenceClient(
                validated, objectMapper, OAuthWarehouseExistenceClient.httpClient(validated.connectTimeout()));
    }

    /**
     * Supplies the no-op port when remote validation is disabled or no explicit implementation is
     * registered.
     *
     * @return local validation bypass used only in the disabled configuration
     */
    @Bean
    @ConditionalOnMissingBean(WarehouseExistenceClient.class)
    WarehouseExistenceClient noOpWarehouseExistenceClient() {
        return new NoOpWarehouseExistenceClient();
    }
}
