package dev.buhanzaz.rwms.auth.config;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Authenticates only the public-PKCE requests marked by
 * {@link PublicPkceClientAuthenticationConverter}.
 *
 * <p>The provider does not authenticate a secret. Instead, it proves that the requested client is
 * registered as a public client with PKCE, rotating refresh-token support, and the
 * {@code offline_access} scope before allowing the authorization server to handle refresh or
 * revocation.</p>
 */
final class PublicPkceClientAuthenticationProvider implements AuthenticationProvider {

    private final RegisteredClientRepository clients;

    /**
     * Creates the provider using the repository whose lookups hide disabled clients.
     *
     * @param clients configured registered-client repository
     */
    PublicPkceClientAuthenticationProvider(RegisteredClientRepository clients) {
        this.clients = clients;
    }

    /**
     * Validates the marked client as an eligible public PKCE refresh-token client.
     *
     * @param authentication marker-bearing OAuth client authentication token
     * @return an authenticated public-client token, or {@code null} when this provider does not own
     *     the request
     * @throws OAuth2AuthenticationException when the marked client does not meet the public-PKCE
     *     contract
     */
    @Override
    public Authentication authenticate(Authentication authentication)
            throws AuthenticationException {
        OAuth2ClientAuthenticationToken clientAuthentication =
                (OAuth2ClientAuthenticationToken) authentication;
        if (!Boolean.TRUE.equals(clientAuthentication
                .getAdditionalParameters()
                .get(PublicPkceClientAuthenticationConverter.AUTHENTICATION_MARKER))) {
            return null;
        }
        String clientId = clientAuthentication.getPrincipal().toString();
        var client = clients.findByClientId(clientId);
        if (client == null
                || !client.getClientAuthenticationMethods()
                        .contains(ClientAuthenticationMethod.NONE)
                || !client.getAuthorizationGrantTypes()
                        .contains(AuthorizationGrantType.REFRESH_TOKEN)
                || !client.getClientSettings().isRequireProofKey()
                || !client.getScopes().contains("offline_access")) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT));
        }
        return new OAuth2ClientAuthenticationToken(
                client, ClientAuthenticationMethod.NONE, null);
    }

    /**
     * Declares support for OAuth client-authentication tokens; ownership of a particular token is
     * decided by the converter marker in {@link #authenticate(Authentication)}.
     *
     * @param authentication authentication implementation type
     * @return whether the type is an OAuth client-authentication token
     */
    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
