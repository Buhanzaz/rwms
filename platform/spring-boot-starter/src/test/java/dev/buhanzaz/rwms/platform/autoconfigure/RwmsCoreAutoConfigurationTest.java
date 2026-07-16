package dev.buhanzaz.rwms.platform.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;

class RwmsCoreAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RwmsCoreAutoConfiguration.class,
                    RwmsJacksonAutoConfiguration.class,
                    RwmsWebAutoConfiguration.class));

    @Test
    void providesUtcTechnicalDefaultsWithoutProvidingSecurityChain() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(context).hasSingleBean(JsonMapperBuilderCustomizer.class);
            assertThat(context).hasSingleBean(CorrelationIdFilter.class);
            assertThat(context).hasSingleBean(RwmsProblemDetailFactory.class);
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
        });
    }

    @Test
    void backsOffWhenApplicationProvidesClock() {
        Clock applicationClock = Clock.system(ZoneOffset.ofHours(3));

        contextRunner.withBean(Clock.class, () -> applicationClock).run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(context.getBean(Clock.class)).isSameAs(applicationClock);
        });
    }
}
