package dev.buhanzaz.rwms.auth.config;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Read-time policy wrapper around the persistent OAuth client repository.
 *
 * <p>A client disabled in declarative auth configuration is invisible to authorization-server
 * lookups even if its historical row remains in the database. Keeping the row preserves audit and
 * revision data while preventing new authorization, refresh, or client-credentials use. Returned
 * registrations must also retain the configured scope set, so a database row changed between
 * provisioner runs cannot broaden a client's token authority.</p>
 */
@RequiredArgsConstructor
final class ConfiguredRegisteredClientRepository implements RegisteredClientRepository {

    private final RegisteredClientRepository delegate;
    private final OAuthClientProperties properties;

    /**
     * Persists a client without applying read-time enablement filtering.
     *
     * @param registeredClient persistent client representation produced by provisioning
     */
    @Override
    public void save(RegisteredClient registeredClient) {
        delegate.save(registeredClient);
    }

    /**
     * Finds a client by storage identifier only when it remains enabled and scope-consistent with
     * current configuration.
     *
     * @param id persistent registered-client identifier
     * @return the enabled client, or {@code null} when it is absent or disabled
     */
    @Override
    public RegisteredClient findById(String id) {
        RegisteredClient client = delegate.findById(id);
        return usable(client) ? client : null;
    }

    /**
     * Finds a client by public client identifier only when it remains enabled and scope-consistent
     * with current configuration.
     *
     * @param clientId OAuth client identifier supplied by a protocol request
     * @return the enabled client, or {@code null} when it is absent or disabled
     */
    @Override
    public RegisteredClient findByClientId(String clientId) {
        if (properties.find(clientId).filter(OAuthClientProperties.Client::enabled).isEmpty()) {
            return null;
        }
        RegisteredClient client = delegate.findByClientId(clientId);
        return usable(client) ? client : null;
    }

    private boolean usable(RegisteredClient client) {
        return client != null
                && properties.find(client.getClientId())
                        .filter(OAuthClientProperties.Client::enabled)
                        .filter(configuration -> configuration.scopes().equals(client.getScopes()))
                        .isPresent();
    }
}
