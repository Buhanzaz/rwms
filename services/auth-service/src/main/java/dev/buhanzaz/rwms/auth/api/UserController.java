package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.service.UserAdministrationService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public authenticated-user API for effective authorization and compact actor identity lookups. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserAdministrationService users;

    /**
     * Returns the effective authorization projection for the currently authenticated user.
     *
     * @param authentication current bearer-token authentication
     * @return effective current-user authorization projection
     */
    @GetMapping("/me")
    CurrentUserResponse me(Authentication authentication) {
        return users.currentUser(authentication);
    }

    /**
     * Resolves up to the service-defined limit of subject identifiers to compact display data.
     *
     * @param subjectIds identifiers to resolve, retaining caller order after de-duplication
     * @return compact actor display data for subjects that exist
     */
    @GetMapping("/actor-displays")
    List<ActorDisplayResponse> actorDisplays(
            @RequestParam(name = "subjectId") List<UUID> subjectIds) {
        return users.actorDisplays(subjectIds);
    }
}
