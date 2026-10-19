package dev.buhanzaz.rwms.platform.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Builds local JWT audience validators for a service security chain without selecting that chain's authorization policy. */
public final class JwtAudienceValidatorFactory {

    /**
     * Creates a local validator that requires one exact audience while leaving issuer, expiry and
     * authorization policy composition to the consuming service security configuration.
     */
    public OAuth2TokenValidator<Jwt> forAudience(String expectedAudience) {
        if (expectedAudience == null || expectedAudience.isBlank()) {
            throw new IllegalArgumentException("Expected JWT audience must not be blank");
        }
        return token -> token.getAudience().contains(expectedAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", "Required audience is missing: " + expectedAudience, null));
    }
}
