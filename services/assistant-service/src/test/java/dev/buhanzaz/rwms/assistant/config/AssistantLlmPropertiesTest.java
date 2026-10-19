package dev.buhanzaz.rwms.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AssistantLlmPropertiesTest {
  private final ApplicationContextRunner contexts =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void bindsSeparateOverallAndStreamIdleTimeouts() {
    contexts
        .withPropertyValues(
            "rwms.assistant.llm.base-url=http://provider.test/v1",
            "rwms.assistant.llm.model=test-model",
            "rwms.assistant.llm.connect-timeout=2s",
            "rwms.assistant.llm.request-timeout=60s",
            "rwms.assistant.llm.stream-idle-timeout=15s",
            "rwms.assistant.llm.require-api-key-on-startup=false")
        .run(
            context -> {
              assertThat(context).hasSingleBean(AssistantLlmProperties.class);
              AssistantLlmProperties properties = context.getBean(AssistantLlmProperties.class);
              assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(60));
              assertThat(properties.streamIdleTimeout()).isEqualTo(Duration.ofSeconds(15));
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AssistantLlmProperties.class)
  static class PropertiesConfiguration {}
}
