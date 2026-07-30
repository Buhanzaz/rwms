package dev.buhanzaz.rwms.auth.config;

import java.time.Instant;
import java.util.Set;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

final class PublicPkceRefreshTokenGenerator
        implements OAuth2TokenGenerator<OAuth2RefreshToken> {

    private static final Base64StringKeyGenerator TOKEN_GENERATOR =
            new Base64StringKeyGenerator(java.util.Base64.getUrlEncoder().withoutPadding(), 96);

    private final OAuth2RefreshTokenGenerator delegate = new OAuth2RefreshTokenGenerator();

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        OAuth2RefreshToken generated = delegate.generate(context);
        if (generated != null || !OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
            return generated;
        }
        var client = context.getRegisteredClient();
        if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(
                        context.getAuthorizationGrantType())
                || !client.getClientAuthenticationMethods()
                        .equals(Set.of(ClientAuthenticationMethod.NONE))
                || !client.getClientSettings().isRequireProofKey()
                || !client.getAuthorizationGrantTypes()
                        .contains(AuthorizationGrantType.REFRESH_TOKEN)
                || !context.getAuthorizedScopes().contains("offline_access")) {
            return null;
        }
        Instant issuedAt = Instant.now();
        return new OAuth2RefreshToken(
                TOKEN_GENERATOR.generateKey(),
                issuedAt,
                issuedAt.plus(client.getTokenSettings().getRefreshTokenTimeToLive()));
    }
}
