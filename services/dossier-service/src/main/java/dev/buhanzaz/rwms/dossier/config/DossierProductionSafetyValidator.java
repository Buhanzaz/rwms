package dev.buhanzaz.rwms.dossier.config;

import java.net.URI;
import java.util.Arrays;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class DossierProductionSafetyValidator implements SmartInitializingSingleton {
  private static final String DEV_CURSOR_SECRET = "dev-only-dossier-cursor-secret-change-me";
  private final Environment environment;

  public DossierProductionSafetyValidator(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void afterSingletonsInstantiated() {
    if (!production()) return;
    require(nonBlank("spring.datasource.url"));
    require(nonBlank("spring.datasource.username"));
    require(nonBlank("spring.datasource.password"));
    require(environment.getProperty("rwms.platform.kafka.enabled", Boolean.class, false));
    require(nonBlank("spring.cloud.stream.kafka.binder.brokers"));
    require(
        environment.getProperty(
            "spring.cloud.stream.kafka.default.producer.sync", Boolean.class, false));
    require(!environment.containsProperty("spring.cloud.stream.default.producer.sync"));
    require(nonBlank("spring.security.oauth2.resourceserver.jwt.issuer-uri"));
    require(nonBlank("spring.security.oauth2.resourceserver.jwt.audiences"));
    require(nonBlank("rwms.cors.allowed-origins"));
    String cursorSecret = environment.getProperty("rwms.dossier.cursor.secret", "");
    require(
        cursorSecret.length() >= 32
            && !DEV_CURSOR_SECRET.equals(cursorSecret)
            && !"rwms-dossier-dev-cursor-secret-change-me".equals(cursorSecret));
    require("validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto")));
    require(environment.getProperty("spring.flyway.enabled", Boolean.class, false));
    require(!environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, true));
    require(!environment.getProperty("rwms.dossier.security.dev-auth-bypass", Boolean.class, true));
    require(
        !environment.getProperty(
            "spring.cloud.stream.kafka.binder.auto-create-topics", Boolean.class, true));
    require(nonLoopbackUri(environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri")));
    require(nonLoopbackOrigin(environment.getProperty("rwms.cors.allowed-origins")));
  }

  private boolean production() {
    return Arrays.stream(environment.getActiveProfiles())
        .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
  }

  private boolean nonBlank(String key) {
    String value = environment.getProperty(key);
    return value != null && !value.isBlank();
  }

  private static boolean nonLoopbackUri(String value) {
    try {
      URI uri = URI.create(value);
      return "https".equalsIgnoreCase(uri.getScheme()) && !loopback(uri.getHost());
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private static boolean nonLoopbackOrigin(String value) {
    if (value == null) return false;
    return Arrays.stream(value.split(","))
        .map(String::strip)
        .filter(item -> !item.isBlank())
        .allMatch(DossierProductionSafetyValidator::nonLoopbackUri);
  }

  private static boolean loopback(String host) {
    return host == null
        || host.equalsIgnoreCase("localhost")
        || host.equals("127.0.0.1")
        || host.equals("::1");
  }

  private static void require(boolean condition) {
    if (!condition) {
      throw new IllegalStateException(
          "Production dossier-service requires fail-closed database, Kafka, OIDC, CORS, cursor and migration configuration");
    }
  }
}
