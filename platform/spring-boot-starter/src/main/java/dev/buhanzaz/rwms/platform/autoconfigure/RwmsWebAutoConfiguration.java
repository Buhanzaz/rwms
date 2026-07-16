package dev.buhanzaz.rwms.platform.autoconfigure;

import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = RwmsCoreAutoConfiguration.class)
@ConditionalOnClass(Filter.class)
public class RwmsWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CorrelationIdFilter rwmsCorrelationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    RwmsProblemDetailFactory rwmsProblemDetailFactory() {
        return new RwmsProblemDetailFactory();
    }
}
