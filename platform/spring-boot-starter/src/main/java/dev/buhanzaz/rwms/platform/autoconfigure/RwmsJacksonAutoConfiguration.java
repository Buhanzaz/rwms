package dev.buhanzaz.rwms.platform.autoconfigure;

import java.util.TimeZone;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/** Adds UTC JSON mapper defaults only when the Jackson runtime is present, keeping non-JSON consumers free of a Jackson linkage. */
@AutoConfiguration(after = RwmsCoreAutoConfiguration.class)
@ConditionalOnClass(name = "tools.jackson.databind.json.JsonMapper")
public class RwmsJacksonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "rwmsUtcJsonMapperCustomizer")
    JsonMapperBuilderCustomizer rwmsUtcJsonMapperCustomizer() {
        return builder -> builder.defaultTimeZone(TimeZone.getTimeZone("UTC"));
    }
}
