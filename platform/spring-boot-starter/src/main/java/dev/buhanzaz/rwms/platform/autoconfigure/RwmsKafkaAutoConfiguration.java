package dev.buhanzaz.rwms.platform.autoconfigure;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaPayloadSafetyValidator;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration
@ConditionalOnClass({StreamBridge.class, ObjectMapper.class})
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
@ConditionalOnProperty(
        prefix = "rwms.platform.kafka",
        name = "publisher-enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(RwmsKafkaProperties.class)
public class RwmsKafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RwmsKafkaOutboundEventPublisher rwmsKafkaOutboundEventPublisher(
            StreamBridge streamBridge,
            ObjectMapper objectMapper,
            RwmsKafkaProperties properties,
            List<RwmsKafkaPayloadSafetyValidator> payloadSafetyValidators) {
        return new RwmsKafkaOutboundEventPublisher(
                streamBridge, objectMapper, properties, payloadSafetyValidators);
    }
}
