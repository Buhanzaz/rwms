package dev.buhanzaz.rwms.assistant.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rwms.assistant.llm")
public record AssistantLlmProperties(
    String baseUrl,
    String apiKey,
    String model,
    Duration connectTimeout,
    Duration requestTimeout,
    boolean requireApiKeyOnStartup) {

  public AssistantLlmProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("rwms.assistant.llm.base-url is required");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("rwms.assistant.llm.model is required");
    }
    if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
      throw new IllegalArgumentException("rwms.assistant.llm.connect-timeout must be positive");
    }
    if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
      throw new IllegalArgumentException("rwms.assistant.llm.request-timeout must be positive");
    }
  }

  public boolean apiKeyConfigured() {
    return apiKey != null && !apiKey.isBlank();
  }
}
