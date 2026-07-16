package dev.buhanzaz.rwms.platform.autoconfigure;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnClass(name = "jakarta.persistence.EntityManager")
public class RwmsJpaSafetyAutoConfiguration {

    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "development", "test");
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");
    private static final Set<String> MUTATING_DDL_MODES = Set.of("update", "create", "create-drop", "drop");

    @Bean
    static BeanFactoryPostProcessor rwmsProductionJpaDdlSafety(Environment environment) {
        validateProductionDdlMode(environment);
        return beanFactory -> {
            // Validation intentionally happens while BeanFactoryPostProcessors are created,
            // before an EntityManagerFactory can apply schema changes.
        };
    }

    private static void validateProductionDdlMode(Environment environment) {
        Set<String> activeProfiles = Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String springMode = environment.getProperty("spring.jpa.hibernate.ddl-auto");
        String nativeMode = environment.getProperty("spring.jpa.properties.hibernate.hbm2ddl.auto");
        boolean production = activeProfiles.stream().anyMatch(PRODUCTION_PROFILES::contains);
        if (production) {
            if (!isValidate(springMode) || (nativeMode != null && !isValidate(nativeMode))) {
                throw new IllegalStateException(
                        "Production requires spring.jpa.hibernate.ddl-auto=validate and forbids automatic schema mutation");
            }
            return;
        }
        if (activeProfiles.stream().anyMatch(DEVELOPMENT_PROFILES::contains)) {
            return;
        }
        if (isMutating(springMode) || isMutating(nativeMode)) {
            throw new IllegalStateException(
                    "Automatic JPA schema mutation is allowed only with an explicit dev, development, or test profile");
        }
    }

    private static boolean isValidate(String value) {
        return value != null && "validate".equalsIgnoreCase(value.trim());
    }

    private static boolean isMutating(String value) {
        return value != null && MUTATING_DDL_MODES.contains(value.trim().toLowerCase(Locale.ROOT));
    }
}
