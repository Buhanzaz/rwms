package dev.buhanzaz.rwms.inventory.config;

import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Defines the module stateless JWT resource-server, CORS and correlation-ID security boundary.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain inventorySecurityFilterChain(
      HttpSecurity http,
      Environment environment,
      CorrelationIdFilter correlationIdFilter,
      InventorySecurityProblemWriter problemWriter,
      @Value("${rwms.inventory.security.dev-auth-bypass:false}") boolean configuredBypass)
      throws Exception {
    boolean production = environment.matchesProfiles("prod", "production");
    boolean bypass = configuredBypass && environment.matchesProfiles("dev") && !production;
    http.authorizeHttpRequests(
        authorize -> {
          authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
          authorize.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
          if (bypass) authorize.requestMatchers("/api/inventory/**").permitAll();
          authorize.anyRequest().authenticated();
        });
    http.addFilterBefore(correlationIdFilter, BearerTokenAuthenticationFilter.class);
    http.exceptionHandling(
        handling ->
            handling
                .authenticationEntryPoint(
                    (request, response, failure) -> problemWriter.unauthorized(request, response))
                .accessDeniedHandler(
                    (request, response, failure) -> problemWriter.forbidden(request, response)));
    http.oauth2ResourceServer(
        resource ->
            resource
                .jwt(Customizer.withDefaults())
                .authenticationEntryPoint(
                    (request, response, failure) -> problemWriter.unauthorized(request, response)));
    http.sessionManagement(
        sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.requestCache(AbstractHttpConfigurer::disable);
    http.csrf(AbstractHttpConfigurer::disable);
    http.cors(Customizer.withDefaults());
    return http.build();
  }

  @Bean
  CorsConfigurationSource inventoryCorsConfigurationSource(
      @Value("${rwms.cors.allowed-origins}") List<String> allowedOrigins) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(allowedOrigins);
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.IF_NONE_MATCH,
            CorrelationIdFilter.HEADER_NAME,
            "Idempotency-Key"));
    configuration.setExposedHeaders(
        List.of(CorrelationIdFilter.HEADER_NAME, HttpHeaders.ETAG, "Idempotency-Replayed"));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
