package dev.buhanzaz.rwms.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External configuration that establishes the auth-service public identity, bootstrap account, and
 * signing-key source.
 *
 * <p>Redirect and origin values are treated as part of the OAuth trust boundary. Password-bearing
 * properties identify secure configuration inputs only; their values must never be logged or exposed
 * through diagnostics.</p>
 *
 * @param issuer public OIDC issuer advertised in metadata and placed in issued tokens
 * @param panelOrigin trusted web-panel origin
 * @param panelRedirectUri authorized panel sign-in callback
 * @param panelPostLogoutRedirectUri authorized panel post-logout target
 * @param workerOrigin trusted worker Android App Link origin
 * @param workerRedirectUri dedicated Android sign-in App Link
 * @param workerPostLogoutRedirectUri authorized Android post-logout App Link
 * @param devDefaultCredentials whether development-only fallback credentials and ephemeral signing
 *     keys are allowed
 * @param bootstrapAdminUsername configured bootstrap system-administrator name
 * @param bootstrapAdminPassword configured bootstrap system-administrator password
 * @param signingKeyStore PKCS#12 keystore path used outside development
 * @param signingKeyStorePassword password used to unlock the configured keystore
 * @param signingKeyAlias alias of the RSA signing key in the configured keystore
 */
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
