package dev.buhanzaz.rwms.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/** Verifies the configured encoding format and compatibility with retained credential hashes. */
class PasswordEncoderCompatibilityTest {

    private final AuthorizationServerConfiguration configuration =
            new AuthorizationServerConfiguration();

    @Test
    void newHashesUseVersionedPbkdf2WithoutBcryptByteLengthLimits() {
        var encoder = configuration.passwordEncoder();

        for (String password : new String[] {"a".repeat(73), "я".repeat(37)}) {
            String hash = encoder.encode(password);

            assertThat(hash).startsWith("{pbkdf2@SpringSecurity_v5_8}");
            assertThat(encoder.matches(password, hash)).isTrue();
            assertThat(encoder.matches(password + "x", hash)).isFalse();
        }
    }

    @Test
    void existingFactoryHashesRemainVerifiable() {
        var encoder = configuration.passwordEncoder();
        String password = "legacy-password";
        String factoryBcrypt = PasswordEncoderFactories.createDelegatingPasswordEncoder()
                .encode(password);
        String realBcrypt = "{bcrypt}" + new BCryptPasswordEncoder().encode(password);

        assertThat(factoryBcrypt).startsWith("{bcrypt}");
        assertThat(encoder.matches(password, factoryBcrypt)).isTrue();
        assertThat(encoder.matches(password, realBcrypt)).isTrue();
        assertThat(encoder.matches(password, "{noop}" + password)).isTrue();
    }
}
