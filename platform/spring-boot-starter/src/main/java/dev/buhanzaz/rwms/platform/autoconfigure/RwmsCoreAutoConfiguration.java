package dev.buhanzaz.rwms.platform.autoconfigure;

import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class RwmsCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock rwmsUtcClock() {
        return Clock.systemUTC();
    }

}
