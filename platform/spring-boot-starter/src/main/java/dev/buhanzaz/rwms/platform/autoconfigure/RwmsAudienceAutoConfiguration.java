package dev.buhanzaz.rwms.platform.autoconfigure;

import dev.buhanzaz.rwms.platform.security.JwtAudienceValidatorFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;

@AutoConfiguration
@ConditionalOnClass(Jwt.class)
public class RwmsAudienceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JwtAudienceValidatorFactory rwmsJwtAudienceValidatorFactory() {
        return new JwtAudienceValidatorFactory();
    }
}
