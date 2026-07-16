package dev.buhanzaz.rwms.auth.service;

import java.util.Locale;
import dev.buhanzaz.rwms.auth.config.OAuthClientProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class PrincipalNamePolicy {

    private final OAuthClientProperties oauthClients;

    public void requireAvailableForHumanPrincipal(String username) {
        if (username != null
                && oauthClients.reservedClientIds().contains(username.trim().toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Логин зарезервирован для OAuth2 client");
        }
    }
}
