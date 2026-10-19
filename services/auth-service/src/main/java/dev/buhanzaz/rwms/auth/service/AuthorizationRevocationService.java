package dev.buhanzaz.rwms.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Removes persisted Spring Authorization Server authorizations and consents after a credential or
 * access-right transition.
 *
 * <p>This invalidates stored authorization state. Self-contained access tokens remain valid until
 * their configured short expiry.
 */
@Service
@RequiredArgsConstructor
public class AuthorizationRevocationService {

    private final JdbcTemplate jdbc;

    /**
     * Revokes every persisted authorization and consent for one principal across all OAuth clients.
     *
     * @param principalName authorization-server principal name to revoke
     */
    public void revokePrincipal(String principalName) {
        requirePrincipalName(principalName);
        jdbc.update(
                """
                delete from oauth2_authorization_consent
                where principal_name = ?
                """,
                principalName);
        jdbc.update(
                """
                delete from oauth2_authorization
                where principal_name = ?
                """,
                principalName);
    }

    /**
     * Revokes persisted authorization and consent for one principal only at the named OAuth
     * client. This limits a client-specific access revocation to its proper audience.
     *
     * @param principalName authorization-server principal name to revoke
     * @param clientId public OAuth client identifier whose authorization is revoked
     */
    public void revokePrincipalClient(String principalName, String clientId) {
        requirePrincipalName(principalName);
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException(
                    "Client id is required for authorization revocation");
        }
        jdbc.update(
                """
                delete from oauth2_authorization_consent consent
                using oauth2_registered_client client
                where consent.registered_client_id = client.id
                  and consent.principal_name = ?
                  and client.client_id = ?
                """,
                principalName,
                clientId);
        jdbc.update(
                """
                delete from oauth2_authorization oa
                using oauth2_registered_client client
                where oa.registered_client_id = client.id
                  and oa.principal_name = ?
                  and client.client_id = ?
                """,
                principalName,
                clientId);
    }

    /** Rejects an empty principal name before it can produce an unsafe broad database operation. */
    private void requirePrincipalName(String principalName) {
        if (principalName == null || principalName.isBlank()) {
            throw new IllegalArgumentException(
                    "Principal name is required for authorization revocation");
        }
    }
}
