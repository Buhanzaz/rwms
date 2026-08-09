package dev.buhanzaz.rwms.analytics.config;

import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Configures stateless resource-server protection for the analytics read API; warehouse authorization remains service-local. */
@Configuration
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain analyticsSecurityFilterChain(
      HttpSecurity http,
      Environment environment,
      CorrelationIdFilter correlationIdFilter,
      AnalyticsSecurityProblemWriter problemWriter,
      @Value("${rwms.analytics.security.dev-auth-bypass:false}") boolean configuredBypass)
      throws Exception {
    boolean production = environment.matchesProfiles("prod", "production");
    boolean bypass = configuredBypass && environment.matchesProfiles("dev") && !production;
    http.authorizeHttpRequests(
        authorize -> {
          authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
          authorize.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
          if (bypass) authorize.requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll();
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
  CorsConfigurationSource analyticsCorsConfigurationSource(
      @Value("${rwms.cors.allowed-origins}") List<String> allowedOrigins) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(allowedOrigins);
    configuration.setAllowedMethods(List.of("GET", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of(HttpHeaders.AUTHORIZATION, CorrelationIdFilter.HEADER_NAME));
    configuration.setExposedHeaders(List.of(CorrelationIdFilter.HEADER_NAME));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
