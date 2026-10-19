package dev.buhanzaz.rwms.platform.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;

class RwmsJpaSafetyAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RwmsJpaSafetyAutoConfiguration.class));

    @Test
    void rejectsUnsafeProductionDdlMode() {
        contextRunner
                .withPropertyValues("spring.profiles.active=production", "spring.jpa.hibernate.ddl-auto=update")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void allowsValidateInProduction() {
        contextRunner
                .withPropertyValues("spring.profiles.active=production", "spring.jpa.hibernate.ddl-auto=validate")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void allowsUpdateInDevelopment() {
        contextRunner
                .withPropertyValues("spring.profiles.active=dev", "spring.jpa.hibernate.ddl-auto=update")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void allowsCreateDropOnlyWithExplicitTestProfile() {
        contextRunner
                .withPropertyValues("spring.profiles.active=test", "spring.jpa.hibernate.ddl-auto=create-drop")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsMutationWithoutExplicitDevelopmentOrTestProfile() {
        contextRunner
                .withPropertyValues("spring.jpa.hibernate.ddl-auto=update")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsMissingValidateModeInProduction() {
        contextRunner
                .withPropertyValues("spring.profiles.active=production")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsUnsafeNativeHibernatePropertyInProduction() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod", "spring.jpa.properties.hibernate.hbm2ddl.auto=create-drop")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionSafetyCannotBeDisabledByConfiguration() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=production",
                        "spring.jpa.hibernate.ddl-auto=update",
                        "rwms.platform.jpa-safety.enabled=false")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void doesNotApplyJpaPolicyWhenJpaIsNotOnTheClasspath() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("jakarta.persistence"))
                .withPropertyValues("spring.profiles.active=production", "spring.jpa.hibernate.ddl-auto=update")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
