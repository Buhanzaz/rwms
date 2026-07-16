package dev.buhanzaz.rwms.platform.autoconfigure;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(ObservationRegistry.class)
public class RwmsObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObservationRegistry rwmsObservationRegistry() {
        return ObservationRegistry.create();
    }
}
