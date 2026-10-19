package dev.buhanzaz.rwms.auth.service;

import java.util.Locale;
import dev.buhanzaz.rwms.auth.config.OAuthClientProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Protects the human-principal namespace from collisions with managed OAuth client identifiers.
 */
@Component
@RequiredArgsConstructor
public class PrincipalNamePolicy {

    private final OAuthClientProperties oauthClients;

    /**
     * Rejects a human login that would collide with a registered OAuth client ID, ignoring case and
     * surrounding whitespace.
     *
     * @param username proposed human login
     */
    public void requireAvailableForHumanPrincipal(String username) {
        if (username != null
                && oauthClients.reservedClientIds().contains(username.trim().toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Логин зарезервирован для OAuth2 client");
        }
    }
}
