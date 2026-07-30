package dev.buhanzaz.rwms.platform.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.function.StreamBridge;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RwmsKafkaAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RwmsKafkaAutoConfiguration.class))
            .withBean(StreamBridge.class, () -> org.mockito.Mockito.mock(StreamBridge.class))
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

    @Test
    void remainsDisabledUnlessExplicitlyEnabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(RwmsKafkaProperties.class);
            assertThat(context).doesNotHaveBean(RwmsKafkaOutboundEventPublisher.class);
        });
    }

    @Test
    void createsPublisherOnlyForConfiguredExactDestinations() {
        contextRunner
                .withPropertyValues(
                        "rwms.platform.kafka.enabled=true",
                        "rwms.platform.kafka.destinations[0]=rwms.task-board.board-task.v1",
                        "rwms.platform.kafka.destinations[1]=rwms.task-board.queue-entry.v1")
                .run(context -> {
                    assertThat(context).hasSingleBean(RwmsKafkaProperties.class);
                    assertThat(context).hasSingleBean(RwmsKafkaOutboundEventPublisher.class);
                    assertThat(context.getBean(RwmsKafkaProperties.class).destinations())
                            .containsExactly(
                                    "rwms.task-board.board-task.v1", "rwms.task-board.queue-entry.v1");
                });
    }

    @Test
    void supportsKafkaConsumersThatDoNotPublishCanonicalDomainEvents() {
        contextRunner
                .withPropertyValues(
                        "rwms.platform.kafka.enabled=true",
                        "rwms.platform.kafka.publisher-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RwmsKafkaProperties.class);
                    assertThat(context).doesNotHaveBean(RwmsKafkaOutboundEventPublisher.class);
                });
    }

    @Test
    void refusesStartupWithoutAnExactDestinationAllowList() {
        contextRunner
                .withPropertyValues("rwms.platform.kafka.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesWildcardAndMalformedDestinations() {
        contextRunner
                .withPropertyValues(
                        "rwms.platform.kafka.enabled=true", "rwms.platform.kafka.destinations[0]=rwms.*.events.v1")
                .run(context -> assertThat(context).hasFailed());

        contextRunner
                .withPropertyValues(
                        "rwms.platform.kafka.enabled=true", "rwms.platform.kafka.destinations[0]=board-task")
                .run(context -> assertThat(context).hasFailed());

        contextRunner
                .withPropertyValues(
                        "rwms.platform.kafka.enabled=true",
                        "rwms.platform.kafka.destinations[0]=rwms.task-board.board-task.created.v1")
                .run(context -> assertThat(context).hasFailed());
    }
}
