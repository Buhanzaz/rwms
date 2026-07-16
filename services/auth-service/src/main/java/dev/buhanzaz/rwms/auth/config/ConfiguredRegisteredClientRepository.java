package dev.buhanzaz.rwms.auth.config;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

@RequiredArgsConstructor
final class ConfiguredRegisteredClientRepository implements RegisteredClientRepository {

    private final RegisteredClientRepository delegate;
    private final OAuthClientProperties properties;

    @Override
    public void save(RegisteredClient registeredClient) {
        delegate.save(registeredClient);
    }

    @Override
    public RegisteredClient findById(String id) {
        RegisteredClient client = delegate.findById(id);
        return enabled(client) ? client : null;
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        if (properties.find(clientId).filter(OAuthClientProperties.Client::enabled).isEmpty()) {
            return null;
        }
        return delegate.findByClientId(clientId);
    }

    private boolean enabled(RegisteredClient client) {
        return client != null
                && properties.find(client.getClientId())
                        .filter(OAuthClientProperties.Client::enabled)
                        .isPresent();
    }
}
