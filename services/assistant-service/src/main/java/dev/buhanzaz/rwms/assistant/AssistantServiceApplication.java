package dev.buhanzaz.rwms.assistant;

import dev.buhanzaz.rwms.assistant.config.AssistantLlmProperties;
import dev.buhanzaz.rwms.assistant.config.AssistantLogisticsProperties;
import dev.buhanzaz.rwms.assistant.config.AssistantSseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Boot entry point for the stateful assistant service and its explicitly bound LLM, logistics and SSE configuration. */
@SpringBootApplication
@EnableConfigurationProperties({
  AssistantLlmProperties.class,
  AssistantLogisticsProperties.class,
  AssistantSseProperties.class
})
public class AssistantServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AssistantServiceApplication.class, args);
  }
}
