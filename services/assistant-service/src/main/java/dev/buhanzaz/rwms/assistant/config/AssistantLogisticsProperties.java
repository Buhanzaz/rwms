package dev.buhanzaz.rwms.assistant.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rwms.assistant.logistics")
public record AssistantLogisticsProperties(
    String baseUrl, Duration connectTimeout, Duration requestTimeout) {

  public AssistantLogisticsProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("rwms.assistant.logistics.base-url is required");
    }
    if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
      throw new IllegalArgumentException("rwms.assistant.logistics.connect-timeout must be positive");
    }
    if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
      throw new IllegalArgumentException("rwms.assistant.logistics.request-timeout must be positive");
    }
  }
}
