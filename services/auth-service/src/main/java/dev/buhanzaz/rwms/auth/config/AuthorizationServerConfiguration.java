package dev.buhanzaz.rwms.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectCredentialStore;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectProfileStore;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import dev.buhanzaz.rwms.auth.repository.UserWarehouseAccessRepository;
import java.io.FileInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
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
import org.springframework.security.crypto.password.PasswordEncoder;
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
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({AuthProperties.class, OAuthClientProperties.class})
public class AuthorizationServerConfiguration {
    private static final String MAINTENANCE_CLIENT_ID = "maintenance-service";
    private static final Set<String> MAINTENANCE_DOWNSTREAM_SCOPES =
            Set.of("asset.maintenance", "task-board.task-sync");

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationProvider authenticationProvider(
            dev.buhanzaz.rwms.auth.security.AuthUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    JdbcRegisteredClientRepository jdbcRegisteredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    @Primary
    RegisteredClientRepository registeredClientRepository(
            JdbcRegisteredClientRepository delegate, OAuthClientProperties properties) {
        return new ConfiguredRegisteredClientRepository(delegate, properties);
    }

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
                .allowIfSubType("java.util.ImmutableCollections$ListN");
        return JsonMapper.builder()
                .addModules(SecurityJacksonModules.getModules(
                        AuthorizationServerConfiguration.class.getClassLoader(), allowedTypes))
                .build();
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, clients);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerChain(HttpSecurity http) throws Exception {
        var authorizationServer = new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpoints = authorizationServer.getEndpointsMatcher();
        http.securityMatcher(endpoints)
                .with(authorizationServer, server -> server
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

    private Consumer<List<AuthenticationProvider>> downstreamClientCredentialsValidators() {
        return providers -> providers.forEach(provider -> {
            if (provider instanceof OAuth2ClientCredentialsAuthenticationProvider clientCredentials) {
                clientCredentials.setAuthenticationValidator(
                        OAuth2ClientCredentialsAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR
                                .andThen(AuthorizationServerConfiguration::validateMaintenanceDownstreamScope)
                                .andThen(AuthorizationServerConfiguration::validateInventoryDownstreamRequest));
            }
        });
    }

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

    static void validateInventoryDownstreamRequest(OAuth2ClientCredentialsAuthenticationContext context) {
        validateExactDownstreamRequest(
                context,
                OAuthClientProperties.INVENTORY_CLIENT_ID,
                OAuthClientProperties.INVENTORY_SCOPES,
                OAuthClientProperties.INVENTORY_AUDIENCE);
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

    @Bean
    @Order(2)
    SecurityFilterChain csrfBootstrapChain(HttpSecurity http) throws Exception {
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
        http.securityMatcher("/api/auth/csrf")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(configurer -> configurer.csrfTokenRepository(csrf))
                .cors(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain applicationChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtConverter,
            @Qualifier("resourceServerJwtDecoder") JwtDecoder resourceServerJwtDecoder) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
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
                        .requestMatchers("/api/admin/**").hasAnyRole("SYSTEM_ADMIN", "WMS_ADMIN")
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

    @Bean
    @Order(4)
    SecurityFilterChain webChain(HttpSecurity http) throws Exception {
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
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
                .formLogin(form -> form.loginPage("/login").permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .csrf(configurer -> configurer.csrfTokenRepository(csrf))
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

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var scopeConverter = new JwtGrantedAuthoritiesConverter();
        var converter = new JwtAuthenticationConverter();
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
            if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
                context.getClaims().claim("principal_type", "SERVICE");
                context.getClaims().claim("client_id", clientId);
                return;
            }
            if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())) {
                return;
            }
            Authentication principal = context.getPrincipal();
            String username = principal == null ? null : principal.getName();
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
            context.getClaims().claim("preferred_username", profile.username());
            context.getClaims().claim("principal_type", subject.getPrincipalType().name());
            if (subject.getPrincipalType() == PrincipalType.USER) {
                context.getClaims().claim("global_role", subject.getGlobalRole().name());
                context.getClaims().claim("warehouse_access_all",
                        subject.getGlobalRole() == dev.buhanzaz.rwms.auth.domain.UserGlobalRole.SYSTEM_ADMIN
                                || subject.getGlobalRole() == dev.buhanzaz.rwms.auth.domain.UserGlobalRole.WMS_ADMIN);
                var warehouseClaims = accesses.findAllByUserIdAndActiveTrueOrderByWarehouseId(subject.getId())
                        .stream()
                        .map(access -> java.util.Map.of(
                                "warehouseId", access.getWarehouseId(),
                                "level", access.getAccessLevel().effectiveFor(subject.getGlobalRole()).name()))
                        .toList();
                context.getClaims().claim("warehouse_access", warehouseClaims);
            } else {
                context.getClaims().claim("worker_id", profile.externalWorkerId());
                context.getClaims().claim("warehouse_id", subject.getWarehouseId());
            }
        };
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(AuthProperties properties) {
        RSAKey rsaKey = loadOrCreateRsaKey(properties);
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    @Primary
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, AuthProperties properties) {
        NimbusJwtDecoder decoder = newJwtDecoder(jwkSource);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

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

    @Bean
    AuthorizationServerSettings authorizationServerSettings(AuthProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.issuer()).build();
    }

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
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось создать ключ подписи auth-service", exception);
        }
    }

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
}
