package dev.buhanzaz.rwms.asset.config;

import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Production must not silently use the no-op warehouse registry client. */
@Component
public class AssetProductionSafetyValidator implements ApplicationRunner {
  private final Environment environment;
  private final WarehouseRegistryProperties registry;

  public AssetProductionSafetyValidator(Environment environment, WarehouseRegistryProperties registry) {
    this.environment = environment;
    this.registry = registry;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    if (environment.matchesProfiles("prod", "production") && !registry.enabled()) {
      throw new IllegalStateException("Production asset-service requires the warehouse registry client");
    }
  }
}
