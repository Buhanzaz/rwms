package dev.buhanzaz.rwms.auth.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectCredentialStore;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectProfileStore;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import dev.buhanzaz.rwms.auth.repository.UserWarehouseAccessRepository;
import dev.buhanzaz.rwms.auth.security.LoginAttemptAdmissionFilter;
import dev.buhanzaz.rwms.auth.service.LoginAttemptThrottle;
import java.io.FileInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Configures auth-service as the RWMS OAuth/OIDC authorization server and as the protected API
 * resource server for its own management endpoints.
 *
 * <p>Ordered filter chains isolate authorization-server protocol endpoints, CSRF bootstrap, bearer
 * token API access, and interactive web pages. Token issuance is kept bound to configured clients,
 * authenticated subject state, and least-privilege service scopes rather than trusting request
 * parameters supplied by a caller.</p>
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({
    AuthProperties.class,
    OAuthClientProperties.class,
    LoginAttemptThrottleProperties.class
})
public class AuthorizationServerConfiguration {
    private static final String MAINTENANCE_CLIENT_ID = "maintenance-service";
    private static final Set<String> MAINTENANCE_DOWNSTREAM_SCOPES =
            Set.of(
                    "asset.maintenance",
                    "task-board.task-sync",
                    "queue-registry.write",
                    "media.maintenance",
                    "logistics.maintenance",
                    "warehouse.timezone.read",
                    "warehouse.operation.mark",
                    "warehouse.lifecycle.read",
                    "warehouse.lifecycle.confirm");

    /**
     * Supplies the application's delegating password encoder.
     *
     * <p>New credentials use Spring Security's versioned PBKDF2 parameters. The factory encoder
     * remains the matching fallback so credentials stored with any previously supported algorithm
     * identifier, including bcrypt, continue to authenticate.</p>
     *
     * @return password encoder used for human and confidential-client credentials
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        var encoders = new LinkedHashMap<String, PasswordEncoder>();
        encoders.put(
                "pbkdf2@SpringSecurity_v5_8",
                Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        var encoder = new DelegatingPasswordEncoder("pbkdf2@SpringSecurity_v5_8", encoders);
        encoder.setDefaultPasswordEncoderForMatches(
                PasswordEncoderFactories.createDelegatingPasswordEncoder());
        return encoder;
    }

    /**
     * Creates DAO authentication for the interactive login form.
     *
     * @param userDetailsService auth-specific subject lookup service
     * @param passwordEncoder encoder used to verify stored password hashes
     * @return provider that authenticates eligible auth subjects
     */
    @Bean
    AuthenticationProvider authenticationProvider(
            dev.buhanzaz.rwms.auth.security.AuthUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    /**
     * Exposes the raw JDBC registered-client repository for startup reconciliation.
     *
     * @param jdbcTemplate authorization-server database access
     * @return persistent repository without runtime enablement filtering
     */
    @Bean
    JdbcRegisteredClientRepository jdbcRegisteredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    /**
     * Exposes the runtime registered-client repository that hides disabled declarative clients.
     *
     * @param delegate raw persistent registered-client repository
     * @param properties declarative client policy
     * @return repository used by protocol endpoints
     */
    @Bean
    @Primary
    RegisteredClientRepository registeredClientRepository(
            JdbcRegisteredClientRepository delegate, OAuthClientProperties properties) {
        return new ConfiguredRegisteredClientRepository(delegate, properties);
    }

    /**
     * Persists OAuth authorizations with a constrained JSON mapper for historical authorization data.
     *
     * <p>The mapper permits only the precise immutable collection shapes needed by existing rows,
     * avoiding unrestricted polymorphic deserialization from database content.</p>
     *
     * @param jdbcTemplate authorization-server database access
     * @param clients runtime client repository used to reconstruct authorization rows
     * @return JDBC-backed authorization service
     */
    @Bean
    OAuth2AuthorizationService authorizationService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository clients) {
        var service = new JdbcOAuth2AuthorizationService(jdbcTemplate, clients);
        JsonMapper jsonMapper = authorizationJsonMapper();
        service.setAuthorizationRowMapper(
                new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper(clients, jsonMapper));
        service.setAuthorizationParametersMapper(
                new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationParametersMapper(jsonMapper));
        return service;
    }

    private JsonMapper authorizationJsonMapper() {
        var allowedTypes = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("java.util.ImmutableCollections$List12")
                .allowIfSubType("java.util.ImmutableCollections$ListN")
                // Older authorization rows used Map.of(...) for warehouse_access entries.
                // Retain only this precise legacy collection type for refresh-token reads.
                .allowIfSubType("java.util.ImmutableCollections$MapN");
        return JsonMapper.builder()
                .addModules(SecurityJacksonModules.getModules(
                        AuthorizationServerConfiguration.class.getClassLoader(), allowedTypes))
                .build();
    }

    /**
     * Persists user consent decisions for OAuth clients.
     *
     * @param jdbcTemplate authorization-server database access
     * @param clients runtime client repository used when loading consent
     * @return JDBC-backed consent service
     */
    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, clients);
    }

    /**
     * Secures OAuth/OIDC protocol endpoints before all other application filter chains.
     *
     * <p>The chain adds secretless public-PKCE refresh/revocation authentication, enforces Android
     * S256 PKCE at the authorization endpoint, constrains service-client credentials requests, and
     * delegates browser login to the local login page. JWT resource-server support lets protocol
     * endpoints authenticate their own protected requests without introducing server-side token
     * sessions.</p>
     *
     * @param http security builder for authorization-server endpoints
     * @param clients runtime client repository
     * @return highest-priority protocol security filter chain
     * @throws Exception when Spring Security cannot build the chain
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerChain(
            HttpSecurity http, RegisteredClientRepository clients) throws Exception {
        var authorizationServer = new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpoints = authorizationServer.getEndpointsMatcher();
        http.securityMatcher(endpoints)
                .with(authorizationServer, server -> server
                        .clientAuthentication(clientAuthentication -> clientAuthentication
                                .authenticationConverters(converters -> converters.add(
                                        0, new PublicPkceClientAuthenticationConverter()))
                                .authenticationProviders(providers -> providers.add(
                                        0, new PublicPkceClientAuthenticationProvider(clients))))
                        .authorizationEndpoint(endpoint -> endpoint.authenticationProviders(
                                publicPkceAuthorizationCodeValidators()))
                        .tokenEndpoint(tokenEndpoint -> tokenEndpoint.authenticationProviders(
                                downstreamClientCredentialsValidators()))
                        .oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .cors(Customizer.withDefaults());
        return http.build();
    }

    private Consumer<List<AuthenticationProvider>> publicPkceAuthorizationCodeValidators() {
        return providers -> providers.forEach(provider -> {
            if (provider
                    instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider authorizationCode) {
                authorizationCode.setAuthenticationValidator(
                        new OAuth2AuthorizationCodeRequestAuthenticationValidator()
                                .andThen(AuthorizationServerConfiguration::validatePublicPkceS256));
            }
        });
    }

    /**
     * Requires dedicated public application clients to use the S256 PKCE transformation.
     *
     * <p>Plain PKCE is intentionally not accepted for these clients: S256 prevents a party that
     * observes an authorization request from recovering the verifier needed at token exchange.</p>
     *
     * @param context authorization-code request validation context
     * @throws OAuth2AuthorizationCodeRequestAuthenticationException when a dedicated client omits
     *     S256
     */
    static void validatePublicPkceS256(
            OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        String clientId = context.getRegisteredClient().getClientId();
        if (!OAuthClientProperties.WORKER_ANDROID_CLIENT_ID.equals(clientId)
                && !OAuthClientProperties.DRIVER_ANDROID_CLIENT_ID.equals(clientId)
                && !OAuthClientProperties.MANAGER_ANDROID_CLIENT_ID.equals(clientId)
                && !OAuthClientProperties.CAD_CLIENT_ID.equals(clientId)
                && !OAuthClientProperties.RENTAL_MANAGER_WEB_CLIENT_ID.equals(clientId)
                && !OAuthClientProperties.RENTAL_MANAGER_ANDROID_CLIENT_ID.equals(clientId)
                && !OAuthClientProperties.ADMIN_WEB_CLIENT_ID.equals(clientId)
                && !OAuthClientProperties.CUSTOMER_ANDROID_CLIENT_ID.equals(clientId)) {
            return;
        }
        OAuth2AuthorizationCodeRequestAuthenticationToken authentication =
                context.getAuthentication();
        Object method = context.getAuthorizationRequest() == null
                ? authentication.getAdditionalParameters().get("code_challenge_method")
                : context.getAuthorizationRequest()
                        .getAdditionalParameters()
                        .get("code_challenge_method");
        if (!"S256".equals(method)) {
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                    new OAuth2Error(
                            OAuth2ErrorCodes.INVALID_REQUEST,
                            clientId + " requires PKCE S256",
                            null),
                    authentication);
        }
    }

    private Consumer<List<AuthenticationProvider>> downstreamClientCredentialsValidators() {
        return providers -> providers.forEach(provider -> {
            if (provider instanceof OAuth2ClientCredentialsAuthenticationProvider clientCredentials) {
                clientCredentials.setAuthenticationValidator(
                        OAuth2ClientCredentialsAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR
                                .andThen(AuthorizationServerConfiguration::validateMaintenanceDownstreamScope)
                                .andThen(AuthorizationServerConfiguration::validateAssetDownstreamRequest)
                                .andThen(AuthorizationServerConfiguration::validateInventoryDownstreamRequest)
                                .andThen(AuthorizationServerConfiguration::validateLogisticsDownstreamRequest)
                                .andThen(AuthorizationServerConfiguration::validateLogisticsPlannerRequest)
                                .andThen(AuthorizationServerConfiguration::validateTaskBoardDownstreamRequest));
            }
        });
    }

    /**
     * Limits maintenance-service to one approved downstream scope per client-credentials token.
     *
     * <p>Requiring one scope makes the intended inter-service action explicit and avoids issuing a
     * broad token simply because the client is entitled to several independent maintenance actions.</p>
     *
     * @param context client-credentials validation context
     * @throws OAuth2AuthenticationException when the request asks for an unapproved or combined
     *     scope
     */
    static void validateMaintenanceDownstreamScope(OAuth2ClientCredentialsAuthenticationContext context) {
        if (!MAINTENANCE_CLIENT_ID.equals(context.getRegisteredClient().getClientId())) {
            return;
        }
        OAuth2ClientCredentialsAuthenticationToken clientCredentialsAuthentication =
                context.getAuthentication();
        Set<String> requestedScopes = clientCredentialsAuthentication.getScopes();
        if (requestedScopes.size() != 1 || !MAINTENANCE_DOWNSTREAM_SCOPES.containsAll(requestedScopes)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_SCOPE,
                    "maintenance-service must request exactly one approved downstream scope",
                    null));
        }
    }

    /**
     * Enforces the exact machine-token request contract for asset-service.
     *
     * @param context client-credentials validation context
     * @throws OAuth2AuthenticationException when a request attempts to override asset-service
     *     identity, audience, or approved scope
     */
    static void validateAssetDownstreamRequest(OAuth2ClientCredentialsAuthenticationContext context) {
        validateExactDownstreamRequest(
                context,
                OAuthClientProperties.ASSET_CLIENT_ID,
                OAuthClientProperties.ASSET_SCOPES,
                OAuthClientProperties.ASSET_AUDIENCE);
    }

    /**
     * Enforces the exact machine-token request contract for inventory-service.
     *
     * @param context client-credentials validation context
     * @throws OAuth2AuthenticationException when a request attempts to override inventory-service
     *     identity, audience, or approved scope
     */
    static void validateInventoryDownstreamRequest(OAuth2ClientCredentialsAuthenticationContext context) {
        validateExactDownstreamRequest(
                context,
                OAuthClientProperties.INVENTORY_CLIENT_ID,
                OAuthClientProperties.INVENTORY_SCOPES,
                OAuthClientProperties.INVENTORY_AUDIENCE);
    }

    /**
     * Enforces the exact machine-token request contract for logistics-service.
     *
     * @param context client-credentials validation context
     * @throws OAuth2AuthenticationException when a request attempts to override logistics-service
     *     identity, audience, or approved scope
     */
    static void validateLogisticsDownstreamRequest(OAuth2ClientCredentialsAuthenticationContext context) {
        validateExactDownstreamRequest(
                context,
                OAuthClientProperties.LOGISTICS_CLIENT_ID,
                OAuthClientProperties.LOGISTICS_SCOPES,
                OAuthClientProperties.LOGISTICS_AUDIENCE);
    }

    /**
     * Enforces the exact single-purpose token request contract for the standalone logistics planner.
     *
     * @param context client-credentials validation context
     * @throws OAuth2AuthenticationException when a request attempts to override planner identity,
     *     audience, or the sole planning scope
     */
    static void validateLogisticsPlannerRequest(
            OAuth2ClientCredentialsAuthenticationContext context) {
        validateExactDownstreamRequest(
                context,
                OAuthClientProperties.LOGISTICS_PLANNER_CLIENT_ID,
                OAuthClientProperties.LOGISTICS_PLANNER_SCOPES,
                OAuthClientProperties.LOGISTICS_PLANNER_AUDIENCE);
    }

    /**
     * Enforces the exact machine-token request contract for task-board-service.
     *
     * @param context client-credentials validation context
     * @throws OAuth2AuthenticationException when a request attempts to override task-board-service
     *     identity, audience, or approved scope
     */
    static void validateTaskBoardDownstreamRequest(
            OAuth2ClientCredentialsAuthenticationContext context) {
        validateExactDownstreamRequest(
                context,
                OAuthClientProperties.TASK_BOARD_CLIENT_ID,
                OAuthClientProperties.TASK_BOARD_SCOPES,
                OAuthClientProperties.TASK_BOARD_AUDIENCE);
    }

    private static void validateExactDownstreamRequest(
            OAuth2ClientCredentialsAuthenticationContext context,
            String clientId,
            Set<String> approvedScopes,
            String audience) {
        if (!clientId.equals(context.getRegisteredClient().getClientId())) {
            return;
        }
        OAuth2ClientCredentialsAuthenticationToken authentication = context.getAuthentication();
        Set<String> requestedScopes = authentication.getScopes();
        if (requestedScopes.size() != 1 || !approvedScopes.containsAll(requestedScopes)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_SCOPE,
                    clientId + " must request exactly one approved downstream scope",
                    null));
        }
        validateExactDownstreamOverride(authentication, clientId, "principal_type", "SERVICE");
        validateExactDownstreamOverride(authentication, clientId, "sub", clientId);
        validateExactDownstreamOverride(authentication, clientId, "subject", clientId);
        validateExactDownstreamOverride(authentication, clientId, "client_id", clientId);
        validateExactDownstreamOverride(authentication, clientId, "audience", audience);
        validateExactDownstreamOverride(authentication, clientId, "resource", audience);
    }

    private static void validateExactDownstreamOverride(
            OAuth2ClientCredentialsAuthenticationToken authentication,
            String clientId,
            String parameter,
            String expected) {
        Object actual = authentication.getAdditionalParameters().get(parameter);
        if (actual != null && (!(actual instanceof String value) || !expected.equals(value))) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_REQUEST,
                    clientId + " token request contains an invalid " + parameter + " override",
                    null));
        }
    }

    /**
     * Serves a readable CSRF token cookie and protects anonymous customer registration.
     *
     * <p>Only the token bootstrap read and exact registration mutation are anonymous. Registration
     * therefore requires the cookie/header pair obtained from the bootstrap endpoint, while every
     * other API remains in the bearer-only chain.
     *
     * @param http security builder for the CSRF bootstrap endpoint
     * @return dedicated CSRF bootstrap filter chain
     * @throws Exception when Spring Security cannot build the chain
     */
    @Bean
    @Order(2)
    SecurityFilterChain csrfBootstrapChain(HttpSecurity http) throws Exception {
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
        http.securityMatcher("/api/auth/csrf", "/api/customer/v1/registrations")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/customer/v1/registrations").permitAll()
                        .anyRequest().denyAll())
                .csrf(configurer -> configurer.csrfTokenRepository(csrf))
                .cors(Customizer.withDefaults());
        return http.build();
    }

    /**
     * Protects auth-service management APIs with locally validated bearer JWTs.
     *
     * <p>The chain is stateless. It maps claims to authorities, applies the narrowly scoped internal
     * worker-credential policy, reserves administration routes for the appropriate roles, and
     * denies unmatched API paths.</p>
     *
     * @param http security builder for {@code /api/**}
     * @param jwtConverter converter from JWT claims to Spring authorities
     * @param resourceServerJwtDecoder decoder that also requires the service audience
     * @return API security filter chain
     * @throws Exception when Spring Security cannot build the chain
     */
    @Bean
    @Order(3)
    SecurityFilterChain applicationChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtConverter,
            @Qualifier("resourceServerJwtDecoder") JwtDecoder resourceServerJwtDecoder) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/users/actor-displays")
                        .hasRole("USER")
                        .requestMatchers("/api/internal/worker-credentials/**")
                        .access((authentication, context) -> {
                            Set<String> authorities = authentication.get().getAuthorities().stream()
                                    .map(GrantedAuthority::getAuthority)
                                    .collect(java.util.stream.Collectors.toSet());
                            return new AuthorizationDecision(
                                    authorities.contains("SCOPE_worker-credentials.manage")
                                            && authorities.contains("ROLE_SERVICE")
                                            && authorities.contains("CLIENT_task-board-service"));
                        })
                        .requestMatchers("/api/admin/eventing/**")
                        .hasRole("SYSTEM_ADMIN")
                        .requestMatchers("/api/admin/**")
                        .access((authentication, context) ->
                                adminApplicationAccess(authentication.get()))
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt
                                .decoder(resourceServerJwtDecoder)
                                .jwtAuthenticationConverter(jwtConverter)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults());
        return http.build();
    }

    /**
     * Secures the interactive login surface, static assets, and local operational probes.
     *
     * <p>Actuator requests forwarded through the public {@code /auth} gateway prefix are denied so
     * health and metrics remain local/private. Browser pages retain CSRF protection and use the
     * login form rather than a token-bearing session API.</p>
     *
     * @param http security builder for non-protocol web requests
     * @return web security filter chain
     * @throws Exception when Spring Security cannot build the chain
     */
    @Bean
    @Order(4)
    SecurityFilterChain webChain(HttpSecurity http, LoginAttemptThrottle loginAttemptThrottle)
            throws Exception {
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
        var loginAttemptFilter = new LoginAttemptAdmissionFilter(loginAttemptThrottle);
        RequestMatcher gatewayForwardedActuator = request ->
                isGatewayActuatorPath(request.getRequestURI())
                        || (("/auth".equals(request.getHeader("X-Forwarded-Prefix"))
                                        || "/auth".equals(request.getContextPath()))
                                && (isActuatorPath(request.getRequestURI())
                                        || isActuatorPath(request.getServletPath())));
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(gatewayForwardedActuator)
                        .denyAll()
                        .requestMatchers(
                                "/login",
                                "/login/**",
                                "/index.html",
                                "/assets/**",
                                "/wms-login-cover.png",
                                "/error")
                        .permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/prometheus")
                        .permitAll()
                .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.FORBIDDEN),
                        gatewayForwardedActuator))
                .formLogin(form -> form.loginPage("/login")
                        .successHandler(loginAttemptFilter.settlingSuccessHandler(
                                new SavedRequestAwareAuthenticationSuccessHandler()))
                        .failureHandler(loginAttemptFilter.settlingFailureHandler(
                                new SimpleUrlAuthenticationFailureHandler("/login?error")))
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .csrf(configurer -> configurer.csrfTokenRepository(csrf))
                .addFilterBefore(loginAttemptFilter, UsernamePasswordAuthenticationFilter.class)
                .cors(Customizer.withDefaults());
        return http.build();
    }

    private static boolean isActuatorPath(String path) {
        return path != null
                && ("/actuator".equals(path)
                        || path.startsWith("/actuator/"));
    }

    private static boolean isGatewayActuatorPath(String path) {
        return path != null
                && ("/auth/actuator".equals(path)
                        || path.startsWith("/auth/actuator/"));
    }

    /** Requires the dedicated administration client, scope, user principal, and administrator role. */
    private static AuthorizationDecision adminApplicationAccess(Authentication authentication) {
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        boolean administratorRole = authorities.contains("ROLE_SYSTEM_ADMIN")
                || authorities.contains("ROLE_WMS_ADMIN");
        return new AuthorizationDecision(
                authentication.isAuthenticated()
                        && authorities.contains("ROLE_USER")
                        && authorities.contains("CLIENT_" + OAuthClientProperties.ADMIN_WEB_CLIENT_ID)
                        && authorities.contains("SCOPE_admin.manage")
                        && administratorRole);
    }

    /**
     * Converts validated JWT claims into the authority model used by auth-service APIs.
     *
     * <p>OAuth scopes retain Spring's {@code SCOPE_} form. Global role, principal type, and client
     * identifier claims become explicit role/client authorities so endpoint policy can require both
     * a scope and the correct machine principal where necessary.</p>
     *
     * @return JWT authentication converter for the resource-server API chain
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var scopeConverter = new JwtGrantedAuthoritiesConverter();
        var converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("preferred_username");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>(scopeConverter.convert(jwt));
            String role = jwt.getClaimAsString("global_role");
            if (role != null && !role.isBlank()) {
                authorities.add(() -> "ROLE_" + role);
            }
            String type = jwt.getClaimAsString("principal_type");
            if (type != null && !type.isBlank()) {
                authorities.add(() -> "ROLE_" + type);
            }
            String clientId = jwt.getClaimAsString("client_id");
            if (clientId != null && !clientId.isBlank()) {
                authorities.add(() -> "CLIENT_" + clientId);
            }
            return authorities;
        });
        return converter;
    }

    /**
     * Adds authoritative RWMS identity and access claims to access and ID tokens.
     *
     * <p>For service tokens the registered client becomes the service principal. For public
     * authorization-code and refresh flows the customizer reloads the subject, profile, credential
     * state, and warehouse access from auth-owned storage. It therefore refuses disabled subjects or
     * clients and cannot be tricked into issuing another principal's claims by request parameters.</p>
     *
     * @param subjects authoritative auth-subject repository
     * @param accesses active warehouse-access repository for user claims
     * @param profiles profile projection used to resolve usernames
     * @param credentials credential-state store used to reject inactive credentials
     * @param oauthClients declarative enabled-client and principal-type policy
     * @return JWT claim customizer for access and ID tokens
     */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(
            AuthSubjectRepository subjects,
            UserWarehouseAccessRepository accesses,
            AuthSubjectProfileStore profiles,
            AuthSubjectCredentialStore credentials,
            OAuthClientProperties oauthClients) {
        return context -> {
            boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());
            boolean idToken = "id_token".equals(context.getTokenType().getValue());
            if (!accessToken && !idToken) {
                return;
            }
            String clientId = context.getRegisteredClient().getClientId();
            OAuthClientProperties.Client client = oauthClients.find(clientId)
                    .filter(OAuthClientProperties.Client::enabled)
                    .orElseThrow(() -> new OAuth2AuthenticationException(
                            new OAuth2Error("access_denied"), "OAuth client is not configured or disabled", null));
            if (accessToken) {
                context.getClaims().audience(List.copyOf(client.audiences()));
            }
            context.getClaims().claim("client_id", clientId);
            if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
                context.getClaims().claim("principal_type", "SERVICE");
                return;
            }
            boolean authorizationCode =
                    AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType());
            boolean refreshToken =
                    AuthorizationGrantType.REFRESH_TOKEN.equals(context.getAuthorizationGrantType());
            if (!authorizationCode && !refreshToken) {
                return;
            }
            Authentication principal = context.getPrincipal();
            String username = authorizationCode
                    ? principal == null ? null : principal.getName()
                    : context.getAuthorization() == null
                            ? null
                            : context.getAuthorization().getPrincipalName();
            if (username == null) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("access_denied"), "Authenticated subject is missing", null);
            }
            var profile = profiles.findSubjectIdByUsername(username)
                    .map(profiles::require)
                    .orElseThrow(() -> new OAuth2AuthenticationException(
                            new OAuth2Error("access_denied"), "Authenticated subject is missing or disabled", null));
            var subject = subjects.findById(profile.subjectId())
                    .orElseThrow(() -> new OAuth2AuthenticationException(
                            new OAuth2Error("access_denied"), "Authenticated subject is missing or disabled", null));
            var credential = credentials.require(subject.getId());
            if (!subject.isActive() || !"ACTIVE".equals(credential.status())) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("access_denied"), "Authenticated subject is missing or disabled", null);
            }
            validateClientPrincipal(
                    clientId, subject.getPrincipalType(), oauthClients);
            validateInteractiveClientAccess(
                    clientId, subject.getGlobalRole(), subject.isRentalAccess());
            if (OAuthClientProperties.MANAGER_ANDROID_CLIENT_ID.equals(clientId)
                    && (subject.getPrincipalType() != PrincipalType.USER
                            || !subject.getGlobalRole().isManagerAppEligible()
                            || !subject.isMobileAppAccess())) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("access_denied"),
                        "Manager app access is not allowed for this user",
                        null);
            }
            context.getClaims().subject(subject.getId().toString());
            context.getClaims().claim("preferred_username", profile.username());
            context.getClaims().claim("principal_type", subject.getPrincipalType().name());
            if (subject.getPrincipalType() == PrincipalType.USER) {
                context.getClaims().claim("global_role", subject.getGlobalRole().name());
                context.getClaims().claim("rentalAccess", subject.isRentalAccess());
                context.getClaims().claim("warehouse_access_all",
                        subject.getGlobalRole() == dev.buhanzaz.rwms.auth.domain.UserGlobalRole.SYSTEM_ADMIN
                                || subject.getGlobalRole() == dev.buhanzaz.rwms.auth.domain.UserGlobalRole.WMS_ADMIN);
                var warehouseClaims = new ArrayList<java.util.Map<String, Object>>();
                for (var access : accesses.findAllByUserIdAndActiveTrueOrderByWarehouseId(subject.getId())) {
                    var warehouseClaim = new LinkedHashMap<String, Object>();
                    warehouseClaim.put("warehouseId", access.getWarehouseId());
                    warehouseClaim.put(
                            "level", access.getAccessLevel().effectiveFor(subject.getGlobalRole()).name());
                    warehouseClaims.add(warehouseClaim);
                }
                context.getClaims().claim("warehouse_access", warehouseClaims);
            } else {
                context.getClaims().claim("worker_id", profile.externalWorkerId());
                context.getClaims().claim("warehouse_id", subject.getWarehouseId());
            }
        };
    }

    /**
     * Composes signed JWT access/ID tokens, the standard access-token generator, and the restricted
     * public-PKCE refresh-token generator.
     *
     * @param jwkSource RSA signing-key source
     * @param tokenCustomizer authoritative claim customizer
     * @return authorization-server token generator chain
     */
    @Bean
    OAuth2TokenGenerator<?> tokenGenerator(
            JWKSource<SecurityContext> jwkSource,
            OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer) {
        var jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        jwtGenerator.setJwtCustomizer(tokenCustomizer);
        return new DelegatingOAuth2TokenGenerator(
                jwtGenerator,
                new OAuth2AccessTokenGenerator(),
                new PublicPkceRefreshTokenGenerator());
    }

    /**
     * Publishes the RSA signing key as an immutable JWK source for token signing and JWKS exposure.
     *
     * @param properties signing-key configuration
     * @return JWK source backed by the configured key or a development-only ephemeral key
     */
    @Bean
    JWKSource<SecurityContext> jwkSource(AuthProperties properties) {
        RSAKey rsaKey = loadOrCreateRsaKey(properties);
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    /**
     * Decodes tokens issued by this authorization server using issuer validation.
     *
     * @param jwkSource local RSA key source
     * @param properties expected public issuer
     * @return primary JWT decoder for authorization-server protocol support
     */
    @Bean
    @Primary
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, AuthProperties properties) {
        NimbusJwtDecoder decoder = newJwtDecoder(jwkSource);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /**
     * Decodes bearer tokens for auth-service's own APIs and requires the RWMS service audience.
     *
     * <p>The separate decoder prevents a valid token intended for another audience from becoming an
     * authorization credential for this service.</p>
     *
     * @param jwkSource local RSA key source
     * @param properties expected public issuer
     * @return API resource-server JWT decoder
     */
    @Bean
    JwtDecoder resourceServerJwtDecoder(JWKSource<SecurityContext> jwkSource, AuthProperties properties) {
        NimbusJwtDecoder decoder = newJwtDecoder(jwkSource);
        OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(properties.issuer());
        OAuth2TokenValidator<Jwt> audience = token -> token.getAudience().contains("rwms-services")
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", "Required audience rwms-services is missing", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
        return decoder;
    }

    private NimbusJwtDecoder newJwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return (NimbusJwtDecoder) OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /**
     * Sets the canonical public issuer used by OIDC discovery and issued-token validation.
     *
     * @param properties auth-service public identity configuration
     * @return authorization-server endpoint settings
     */
    @Bean
    AuthorizationServerSettings authorizationServerSettings(AuthProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.issuer()).build();
    }

    /**
     * Builds CORS policy only from origins declared by enabled OAuth clients.
     *
     * <p>There is no wildcard origin: credentialed browser requests can originate only from an
     * explicit, currently enabled client origin.</p>
     *
     * @param oauthClients declarative OAuth client configuration
     * @return global CORS configuration source
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(OAuthClientProperties oauthClients) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(oauthClients.clients().stream()
                .filter(OAuthClientProperties.Client::enabled)
                .flatMap(client -> client.allowedOrigins().stream())
                .distinct()
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private RSAKey loadOrCreateRsaKey(AuthProperties properties) {
        if (properties.signingKeyStore() == null || properties.signingKeyStore().isBlank()) {
            if (properties.devDefaultCredentials()) {
                return generateRsaKey();
            }
            throw new IllegalStateException(
                    "AUTH_SIGNING_KEY_STORE обязателен вне dev/test; ephemeral signing key запрещён");
        }
        try (var input = new FileInputStream(properties.signingKeyStore())) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            char[] password = properties.signingKeyStorePassword().toCharArray();
            keyStore.load(input, password);
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(properties.signingKeyAlias(), password);
            var certificate = keyStore.getCertificate(properties.signingKeyAlias());
            return new RSAKey.Builder((RSAPublicKey) certificate.getPublicKey())
                    .privateKey((RSAPrivateKey) privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(properties.signingKeyAlias())
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось загрузить ключ подписи auth-service", exception);
        }
    }

    private RSAKey generateRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось создать ключ подписи auth-service", exception);
        }
    }

    /**
     * Rejects token issuance when the authenticated subject type is not authorized for the client.
     *
     * <p>This is checked after loading the authoritative subject, so a caller cannot use a client
     * intended for workers with a human principal, or vice versa.</p>
     *
     * @param clientId registered OAuth client identifier
     * @param principalType authoritative authenticated subject type
     * @param oauthClients declarative client/principal-type policy
     * @throws OAuth2AuthenticationException when the client is absent, disabled, or incompatible
     *     with the subject type
     */
    void validateClientPrincipal(
            String clientId, PrincipalType principalType, OAuthClientProperties oauthClients) {
        PrincipalType allowed = oauthClients.find(clientId)
                .filter(OAuthClientProperties.Client::enabled)
                .filter(client -> client.allowedPrincipalTypes().contains(principalType))
                .map(client -> principalType)
                .orElse(null);
        if (allowed == null) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("access_denied"),
                    "Principal type cannot use OAuth client " + clientId,
                    null);
        }
    }

    /**
     * Enforces the authoritative role boundary for each dedicated interactive application.
     *
     * <p>Customer subjects remain isolated to their application except for the CAD client, whose
     * project membership is enforced by cad-service. Rental access is an independent entitlement
     * for staff roles, so any staff user may use a dedicated rental-manager client; the
     * {@code RENTAL_MANAGER} role remains isolated from unrelated applications other than CAD.
     *
     * @param clientId registered OAuth client identifier
     * @param globalRole authoritative user role, or {@code null} for non-user subjects
     * @throws OAuth2AuthenticationException when an application role boundary would be crossed
     */
    void validateInteractiveClientRole(String clientId, UserGlobalRole globalRole) {
        boolean cadClient = OAuthClientProperties.CAD_CLIENT_ID.equals(clientId);
        boolean customerClient = OAuthClientProperties.CUSTOMER_ANDROID_CLIENT_ID.equals(clientId);
        boolean customerRole = globalRole == UserGlobalRole.CUSTOMER;
        if (!cadClient && customerClient != customerRole) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("access_denied"),
                    "Customer accounts and customer OAuth client are isolated",
                    null);
        }
        if (customerClient) {
            return;
        }
        boolean rentalManagerClient =
                OAuthClientProperties.RENTAL_MANAGER_WEB_CLIENT_ID.equals(clientId)
                        || OAuthClientProperties.RENTAL_MANAGER_ANDROID_CLIENT_ID.equals(clientId);
        if (rentalManagerClient && globalRole == null) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("access_denied"),
                    "Dedicated rental manager applications require a staff user",
                    null);
        }
        if (!rentalManagerClient && !cadClient && globalRole == UserGlobalRole.RENTAL_MANAGER) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("access_denied"),
                    "Rental managers may use only the dedicated manager applications",
                    null);
        }
        boolean adminClient = OAuthClientProperties.ADMIN_WEB_CLIENT_ID.equals(clientId);
        boolean adminRole = globalRole == UserGlobalRole.SYSTEM_ADMIN
                || globalRole == UserGlobalRole.WMS_ADMIN;
        if (adminClient && !adminRole) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("access_denied"),
                    "Administration application access is not allowed for this user",
                    null);
        }
    }

    /**
     * Applies mutable application entitlements after the immutable role/client boundary. A staff
     * user whose rental access was not granted or was revoked cannot obtain a fresh or refreshed
     * token for either dedicated manager application.
     */
    void validateInteractiveClientAccess(
            String clientId, UserGlobalRole globalRole, boolean rentalAccess) {
        validateInteractiveClientRole(clientId, globalRole);
        boolean rentalManagerClient =
                OAuthClientProperties.RENTAL_MANAGER_WEB_CLIENT_ID.equals(clientId)
                        || OAuthClientProperties.RENTAL_MANAGER_ANDROID_CLIENT_ID.equals(clientId);
        if (rentalManagerClient && !rentalAccess) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("access_denied"),
                    "Rental manager application access is revoked",
                    null);
        }
    }
}
