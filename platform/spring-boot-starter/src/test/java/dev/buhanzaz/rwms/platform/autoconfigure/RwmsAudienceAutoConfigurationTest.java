package dev.buhanzaz.rwms.platform.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.platform.security.JwtAudienceValidatorFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.Jwt;

class RwmsAudienceAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RwmsAudienceAutoConfiguration.class));

    @Test
    void providesAudienceValidatorFactoryWithoutSecurityFilterChain() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(JwtAudienceValidatorFactory.class);
            assertThat(context).doesNotHaveBean("securityFilterChain");

            JwtAudienceValidatorFactory factory = context.getBean(JwtAudienceValidatorFactory.class);
            Jwt accepted = jwt(List.of("rwms-services"));
            Jwt rejected = jwt(List.of("another-service"));

            assertThat(factory.forAudience("rwms-services").validate(accepted).hasErrors()).isFalse();
            assertThat(factory.forAudience("rwms-services").validate(rejected).hasErrors()).isTrue();
        });
    }

    private Jwt jwt(List<String> audience) {
        Instant now = Instant.now();
        return new Jwt(
                "token",
                now,
                now.plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", "test", "aud", audience));
    }
}
