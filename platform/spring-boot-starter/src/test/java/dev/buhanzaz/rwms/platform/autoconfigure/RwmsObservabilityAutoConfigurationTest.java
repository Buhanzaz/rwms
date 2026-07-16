package dev.buhanzaz.rwms.platform.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;

class RwmsObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RwmsObservabilityAutoConfiguration.class));

    @Test
    void providesOptionalObservationRegistryWithoutSecurityPolicy() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ObservationRegistry.class);
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
        });
    }

    @Test
    void backsOffWhenApplicationProvidesObservationRegistry() {
        ObservationRegistry applicationRegistry = ObservationRegistry.create();

        contextRunner
                .withBean(ObservationRegistry.class, () -> applicationRegistry)
                .run(context -> assertThat(context.getBean(ObservationRegistry.class)).isSameAs(applicationRegistry));
    }

    @Test
    void remainsAbsentWithoutMicrometerObservationClasspath() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("io.micrometer.observation"))
                .run(context -> assertThat(context).doesNotHaveBean(ObservationRegistry.class));
    }
}
