package dev.buhanzaz.rwms.assistant.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rwms.assistant.sse")
public record AssistantSseProperties(Duration timeout) {
  public AssistantSseProperties {
    if (timeout == null || timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("rwms.assistant.sse.timeout must be positive");
    }
  }
}
