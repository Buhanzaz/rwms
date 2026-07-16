package dev.buhanzaz.rwms.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rwms.auth")
public record AuthProperties(
        String issuer,
        String panelOrigin,
        String panelRedirectUri,
        String panelPostLogoutRedirectUri,
        String workerOrigin,
        String workerRedirectUri,
        String workerPostLogoutRedirectUri,
        boolean devDefaultCredentials,
        String bootstrapAdminUsername,
        String bootstrapAdminPassword,
        String signingKeyStore,
        String signingKeyStorePassword,
        String signingKeyAlias) {
}
