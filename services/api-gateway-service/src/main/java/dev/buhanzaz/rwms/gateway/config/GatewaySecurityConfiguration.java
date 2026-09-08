package dev.buhanzaz.rwms.gateway.config;

import dev.buhanzaz.rwms.gateway.security.GatewaySecurityProblemWriter;
import dev.buhanzaz.rwms.gateway.web.CanonicalCorrelationRequestFilter;
import dev.buhanzaz.rwms.platform.security.JwtAudienceValidatorFactory;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Declares the public gateway's stateless authentication, authorization, and CORS policy.
 *
 * <p>Private service routes are denied at the edge, worker task routes require their dedicated
 * scope, explicitly published presentation reads remain anonymous, and all other public API routes
 * require a locally validated Bearer JWT. Authorization decisions inside individual domain
 * services are deliberately not replicated here.
 */
@Configuration
public class GatewaySecurityConfiguration {

  /**
   * Builds the ordered gateway security chain and maps authentication failures to RWMS Problem
   * Details responses.
   */
  @Bean
  SecurityFilterChain gatewaySecurityFilterChain(
      HttpSecurity http,
      CorrelationIdFilter correlationIdFilter,
      GatewaySecurityProblemWriter securityProblems)
      throws Exception {
    http.authorizeHttpRequests(
        authorize ->
            authorize
                .requestMatchers(HttpMethod.OPTIONS, "/**")
                .permitAll()
                .requestMatchers(
                    "/api/task-board/internal/**",
                    "/api/warehouse/internal/**",
                    "/api/asset/internal/**",
                    "/api/maintenance/internal/**",
                    "/api/cad/internal/**",
                    "/api/cad/private/**",
                    "/api/media/internal/**",
                    "/api/media/private/**",
                    "/api/inventory/internal/**",
                    "/api/inventory/private/**",
                    "/api/logistics/internal/**",
                    "/api/logistics/private/**",
                    "/api/dossier/internal/**",
                    "/api/dossier/private/**",
                    "/api/analytics/internal/**",
                    "/api/analytics/private/**",
                    "/api/assistant/internal/**",
                    "/api/assistant/private/**",
                    "/auth/api/internal/**")
                .denyAll()
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/logistics/public/v1/catalog/warehouses",
                    "/api/logistics/public/v1/catalog/warehouses/*/facets",
                    "/api/logistics/public/v1/catalog/warehouses/*/cabins",
                    "/api/logistics/public/v1/catalog/warehouses/*/cabins/*/photos/*")
                .permitAll()
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/logistics/public/v1/cabin-photo-presentations/**")
                .permitAll()
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/logistics/public/v1/contractor-route-shares/*",
                    "/api/logistics/public/v1/contractor-route-shares/*/tasks/*/entries/*/media/*/generations/*/variants/*/content")
                .permitAll()
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/logistics/public/v1/contractor-route-shares/*/tasks/*/entries/*/actions",
                    "/api/logistics/public/v1/contractor-route-shares/*/tasks/*/entries/*/evidence/*")
                .permitAll()
                .requestMatchers(
                    "/auth/**",
                    "/api/logistics/public/v1/client-presentations/**",
                    "/.well-known/assetlinks.json",
                    "/actuator/health",
                    "/actuator/health/**",
                    "/error")
                .permitAll()
                .requestMatchers("/api/task-board/worker/v1/**")
                .access(exactTaskScope("SCOPE_worker.tasks", "SCOPE_driver.tasks"))
                .requestMatchers("/api/task-board/driver/v1/**")
                .access(exactTaskScope("SCOPE_driver.tasks", "SCOPE_worker.tasks"))
                .requestMatchers("/api/**", "/actuator/prometheus")
                .authenticated()
                .anyRequest()
                .denyAll());
    http.addFilterBefore(correlationIdFilter, BearerTokenAuthenticationFilter.class);
    http.addFilterAfter(
        new CanonicalCorrelationRequestFilter(), CorrelationIdFilter.class);
    http.oauth2ResourceServer(
        resourceServer ->
            resourceServer
                .jwt(Customizer.withDefaults())
                .authenticationEntryPoint(
                    (request, response, exception) ->
                        securityProblems.unauthorized(request, response)));
    http.exceptionHandling(
        handling ->
            handling
                .authenticationEntryPoint(
                    (request, response, exception) ->
                        securityProblems.unauthorized(request, response))
                .accessDeniedHandler(
                    (request, response, exception) ->
                        securityProblems.forbidden(request, response)));
    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.requestCache(AbstractHttpConfigurer::disable);
    http.csrf(AbstractHttpConfigurer::disable);
    http.cors(Customizer.withDefaults());
    return http.build();
  }

  /**
   * Requires one native task scope while rejecting the other application scope.
   *
   * <p>This keeps WorkerApp and DriverApp tokens non-interchangeable even when a misconfigured
   * identity happens to contain both authorities.
   */
  private static AuthorizationManager<RequestAuthorizationContext> exactTaskScope(
      String requiredAuthority, String forbiddenAuthority) {
    return (authentication, context) -> {
      var principal = authentication.get();
      boolean required =
          principal.getAuthorities().stream()
              .anyMatch(authority -> requiredAuthority.equals(authority.getAuthority()));
      boolean forbidden =
          principal.getAuthorities().stream()
              .anyMatch(authority -> forbiddenAuthority.equals(authority.getAuthority()));
      return new AuthorizationDecision(principal.isAuthenticated() && required && !forbidden);
    };
  }

  /**
   * Creates the local JWT decoder from the private auth-service JWKS endpoint.
   *
   * <p>The decoder validates token timestamps, the configured public issuer, and the configured
   * service audience; it never resolves JWKS through the browser-visible gateway route.
   */
  @Bean
  @ConditionalOnMissingBean(JwtDecoder.class)
  JwtDecoder gatewayJwtDecoder(
      GatewayProperties properties, JwtAudienceValidatorFactory audienceValidators) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(
                properties.getRoutes().getAuthUri().resolve("/oauth2/jwks").toString())
            .build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(),
            new JwtIssuerValidator(properties.getSecurity().getIssuer()),
            audienceValidators.forAudience(properties.getSecurity().getAudience())));
    return decoder;
  }

  /**
   * Creates the credentialed CORS policy for the explicitly configured panel origins.
   *
   * <p>The allow-list is validated at startup; wildcard origins are rejected by
   * {@link GatewayProductionSafetyValidator}.
   */
  @Bean
  CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
    var configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(properties.getCors().getAllowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.IF_NONE_MATCH,
            CorrelationIdFilter.HEADER_NAME,
            "Idempotency-Key",
            "Last-Event-ID",
            "X-Captured-At",
            "X-Content-SHA256",
            "X-XSRF-TOKEN"));
    configuration.setExposedHeaders(
        List.of(CorrelationIdFilter.HEADER_NAME, HttpHeaders.ETAG, HttpHeaders.RETRY_AFTER));
    configuration.setAllowCredentials(true);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
