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

final class PublicPkceClientAuthenticationProvider implements AuthenticationProvider {

    private final RegisteredClientRepository clients;

    PublicPkceClientAuthenticationProvider(RegisteredClientRepository clients) {
        this.clients = clients;
    }

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

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
