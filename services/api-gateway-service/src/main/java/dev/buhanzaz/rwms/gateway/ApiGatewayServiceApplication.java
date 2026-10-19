package dev.buhanzaz.rwms.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Boots the RWMS public API gateway.
 *
 * <p>The application is a stateless transport edge: it routes public HTTP traffic, applies edge
 * security policy, and relays transport failures. It deliberately owns no domain workflow,
 * persistence, cache, token storage, or message-broker participation.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGatewayServiceApplication {

  /** Starts the Spring application context. */
  public static void main(String[] args) {
    SpringApplication.run(ApiGatewayServiceApplication.class, args);
  }
}
