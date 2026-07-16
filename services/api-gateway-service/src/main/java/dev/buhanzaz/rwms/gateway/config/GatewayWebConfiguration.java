package dev.buhanzaz.rwms.gateway.config;

import dev.buhanzaz.rwms.gateway.web.TrustedForwardedHeaderFilter;
import dev.buhanzaz.rwms.gateway.web.PublicHostBoundaryFilter;
import dev.buhanzaz.rwms.gateway.web.CanonicalAuthForwardedHeadersFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class GatewayWebConfiguration {

  @Bean
  FilterRegistrationBean<TrustedForwardedHeaderFilter> trustedForwardedHeaderFilter(
      GatewayProperties properties) {
    var registration =
        new FilterRegistrationBean<>(
            new TrustedForwardedHeaderFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  @Bean
  FilterRegistrationBean<PublicHostBoundaryFilter> publicHostBoundaryFilter(
      GatewayProperties properties,
      ObjectMapper objectMapper,
      RwmsProblemDetailFactory problems) {
    var registration =
        new FilterRegistrationBean<>(
            new PublicHostBoundaryFilter(
                properties.getPublicBaseUri(), objectMapper, problems));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }

  @Bean
  CanonicalAuthForwardedHeadersFilter canonicalAuthForwardedHeadersFilter(
      GatewayProperties properties) {
    return new CanonicalAuthForwardedHeadersFilter(properties.getPublicBaseUri());
  }
}
