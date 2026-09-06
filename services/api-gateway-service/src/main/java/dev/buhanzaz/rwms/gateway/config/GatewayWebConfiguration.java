package dev.buhanzaz.rwms.gateway.config;

import dev.buhanzaz.rwms.gateway.web.TrustedForwardedHeaderFilter;
import dev.buhanzaz.rwms.gateway.web.PublicHostBoundaryFilter;
import dev.buhanzaz.rwms.gateway.web.CanonicalAuthForwardedHeadersFilter;
import dev.buhanzaz.rwms.gateway.web.ContractorEvidenceUploadBoundaryFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

/**
 * Registers servlet filters that establish the trusted public-request boundary before routing.
 *
 * <p>The ordering is intentional: untrusted forwarding metadata is discarded first, the request
 * host is then checked against the configured public origin, anonymous contractor evidence is
 * bounded before proxying, and only auth requests receive gateway-derived forwarding metadata.
 */
@Configuration
public class GatewayWebConfiguration {

  /** Registers the earliest filter that removes client-supplied forwarding headers. */
  @Bean
  FilterRegistrationBean<TrustedForwardedHeaderFilter> trustedForwardedHeaderFilter(
      GatewayProperties properties,
      ObjectMapper objectMapper,
      RwmsProblemDetailFactory problems) {
    var registration =
        new FilterRegistrationBean<>(
            new TrustedForwardedHeaderFilter(
                properties.getTrustedProxyAddresses(), objectMapper, problems));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  /** Registers the host allow-list immediately after forwarding-header sanitization. */
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

  /** Rejects oversized or streaming anonymous evidence before a proxy can consume its body. */
  @Bean
  FilterRegistrationBean<ContractorEvidenceUploadBoundaryFilter>
      contractorEvidenceUploadBoundaryFilter(
          ObjectMapper objectMapper, RwmsProblemDetailFactory problems) {
    var registration =
        new FilterRegistrationBean<>(
            new ContractorEvidenceUploadBoundaryFilter(objectMapper, problems));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
    return registration;
  }

  /** Supplies canonical forwarding metadata only to the internal auth-service route. */
  @Bean
  CanonicalAuthForwardedHeadersFilter canonicalAuthForwardedHeadersFilter(
      GatewayProperties properties) {
    return new CanonicalAuthForwardedHeadersFilter(properties.getPublicBaseUri());
  }
}
