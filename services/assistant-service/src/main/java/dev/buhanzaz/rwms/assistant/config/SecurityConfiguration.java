package dev.buhanzaz.rwms.assistant.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

/** Configures stateless JWT protection and CORS for the public assistant API without granting rental access by default. */
@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain assistantSecurityFilterChain(
      HttpSecurity http,
      AssistantCorrelationIdFilter correlationIdFilter,
      AssistantSecurityProblemWriter problemWriter)
      throws Exception {
    http.authorizeHttpRequests(
        authorize -> {
          authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
          authorize.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
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
  CorsConfigurationSource assistantCorsConfigurationSource(
      @Value("${rwms.cors.allowed-origins}") List<String> allowedOrigins) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(allowedOrigins);
    configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            AssistantCorrelationIdFilter.HEADER_NAME,
            "Idempotency-Key"));
    configuration.setExposedHeaders(List.of(AssistantCorrelationIdFilter.HEADER_NAME));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
