package dev.buhanzaz.rwms.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthorizationRevocationService {

    private final JdbcTemplate jdbc;

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

    private void requirePrincipalName(String principalName) {
        if (principalName == null || principalName.isBlank()) {
            throw new IllegalArgumentException(
                    "Principal name is required for authorization revocation");
        }
    }
}
