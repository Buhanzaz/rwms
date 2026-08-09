package dev.buhanzaz.rwms.inventory.config;

import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyProperties;
import java.util.Arrays;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Production startup guard for inventory dependencies, Kafka and fail-closed authentication.
 */
@Component
public final class InventoryProductionSafetyValidator implements SmartInitializingSingleton {
  private final Environment environment;
  private final boolean dependenciesEnabled;
  private final boolean kafkaEnabled;
  private final boolean authBypass;
  private final InventoryDependencyGateway dependencies;
  private final InventoryDependencyProperties dependencyProperties;

  public InventoryProductionSafetyValidator(
      Environment environment,
      @Value("${rwms.inventory.dependencies.enabled:false}") boolean dependenciesEnabled,
      @Value("${rwms.platform.kafka.enabled:false}") boolean kafkaEnabled,
      @Value("${rwms.inventory.security.dev-auth-bypass:false}") boolean authBypass,
      InventoryDependencyGateway dependencies,
      InventoryDependencyProperties dependencyProperties) {
    this.environment = environment;
    this.dependenciesEnabled = dependenciesEnabled;
    this.kafkaEnabled = kafkaEnabled;
    this.authBypass = authBypass;
    this.dependencies = dependencies;
    this.dependencyProperties = dependencyProperties;
  }

  @Override
  public void afterSingletonsInstantiated() {
    boolean production =
        Arrays.stream(environment.getActiveProfiles())
            .anyMatch(value -> value.equals("prod") || value.equals("production"));
    if (production
        && (!dependenciesEnabled || !kafkaEnabled || authBypass || !dependencies.productionReady())) {
      throw new IllegalStateException(
          "Production inventory-service requires dependencies, Kafka and fail-closed authentication");
    }
    if (production) dependencyProperties.validated();
  }
}
