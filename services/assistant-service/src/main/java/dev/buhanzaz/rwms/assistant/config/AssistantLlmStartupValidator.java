package dev.buhanzaz.rwms.assistant.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** Fails closed before accepting turns when the provider credential is absent. */
@Component
public class AssistantLlmStartupValidator implements SmartInitializingSingleton {
  private final AssistantLlmProperties properties;

  public AssistantLlmStartupValidator(AssistantLlmProperties properties) {
    this.properties = properties;
  }

  @Override
  public void afterSingletonsInstantiated() {
    if (properties.requireApiKeyOnStartup() && !properties.apiKeyConfigured()) {
      throw new IllegalStateException("LLM_API_KEY is required for assistant-service startup");
    }
  }
}
