package dev.buhanzaz.rwms.platform.autoconfigure;

import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Provides small framework-neutral core beans, including a UTC Clock, only when an application has not supplied an equivalent bean. */
@AutoConfiguration
public class RwmsCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock rwmsUtcClock() {
        return Clock.systemUTC();
    }

}
