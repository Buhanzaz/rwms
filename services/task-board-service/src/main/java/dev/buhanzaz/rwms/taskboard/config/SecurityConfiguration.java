package dev.buhanzaz.rwms.taskboard.config;

import java.util.List;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Enforces JWT, scope and dev-only bypass policy for public worker and private task-board routes. */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http,
      Environment environment,
      CorrelationIdFilter correlationIdFilter,
      TaskBoardSecurityProblemWriter securityProblems,
      @Value("${rwms.security.dev-auth-bypass:false}") boolean developmentAuthBypass)
      throws Exception {
    boolean production = environment.matchesProfiles("prod", "production");
    boolean bypassEnabled =
        developmentAuthBypass && environment.matchesProfiles("dev") && !production;
    http.authorizeHttpRequests(
        authorize -> {
          authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
          authorize
              .requestMatchers("/api/internal/task-board/v1/logistics/**")
              .hasAuthority("SCOPE_task-board.logistics");
          authorize.requestMatchers("/api/internal/**").authenticated();
          authorize
              .requestMatchers("/api/worker/v1/**")
              .hasAuthority("SCOPE_worker.tasks");
          authorize
              .requestMatchers("/api/driver/v1/**")
              .hasAuthority("SCOPE_driver.tasks");
          if (bypassEnabled) {
            authorize.anyRequest().permitAll();
          } else {
            authorize.anyRequest().authenticated();
          }
        });
    http.headers(Customizer.withDefaults());
    http.addFilterBefore(correlationIdFilter, BearerTokenAuthenticationFilter.class);
    http.exceptionHandling(
        handling ->
            handling
                .authenticationEntryPoint(
                    (request, response, exception) ->
                        securityProblems.unauthorized(request, response))
                .accessDeniedHandler(
                    (request, response, exception) -> securityProblems.forbidden(request, response)));
    http.oauth2ResourceServer(
        resourceServer ->
            resourceServer
                .jwt(Customizer.withDefaults())
                .authenticationEntryPoint(
                    (request, response, exception) ->
                        securityProblems.unauthorized(request, response)));
    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.requestCache(AbstractHttpConfigurer::disable);
    http.anonymous(Customizer.withDefaults());
    http.csrf(csrf -> csrf.disable());
    http.cors(Customizer.withDefaults());
    return http.build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${rwms.cors.allowed-origins}") List<String> allowedOrigins) {
    var configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(allowedOrigins);
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.IF_NONE_MATCH,
            CorrelationIdFilter.HEADER_NAME,
            "Idempotency-Key",
            "Last-Event-ID"));
    configuration.setExposedHeaders(
        List.of(CorrelationIdFilter.HEADER_NAME, HttpHeaders.ETAG, HttpHeaders.RETRY_AFTER));
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
