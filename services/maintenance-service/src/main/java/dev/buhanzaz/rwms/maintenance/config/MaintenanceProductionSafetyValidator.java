package dev.buhanzaz.rwms.maintenance.config;

import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyProperties;
import java.util.Arrays;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceProductionSafetyValidator implements SmartInitializingSingleton {
  private final Environment environment;
  private final boolean dependenciesEnabled;
  private final boolean authBypass;
  private final MaintenanceDependencyGateway dependencyGateway;
  private final MaintenanceDependencyProperties dependencyProperties;

  public MaintenanceProductionSafetyValidator(
      Environment environment,
      @Value("${rwms.maintenance.dependencies.enabled:false}") boolean dependenciesEnabled,
      @Value("${rwms.maintenance.security.dev-auth-bypass:false}") boolean authBypass,
      MaintenanceDependencyGateway dependencyGateway,
      MaintenanceDependencyProperties dependencyProperties) {
    this.environment = environment;
    this.dependenciesEnabled = dependenciesEnabled;
    this.authBypass = authBypass;
    this.dependencyGateway = dependencyGateway;
    this.dependencyProperties = dependencyProperties;
  }

  @Override
  public void afterSingletonsInstantiated() {
    boolean production = Arrays.stream(environment.getActiveProfiles())
        .anyMatch(value -> value.equals("prod") || value.equals("production"));
    if (production && (!dependenciesEnabled || authBypass || !dependencyGateway.productionReady())) {
      throw new IllegalStateException(
          "Production maintenance-service requires real dependencies and fail-closed authentication");
    }
    if (production) dependencyProperties.validated();
  }
}
