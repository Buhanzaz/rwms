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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserAdministrationService users;

    @GetMapping("/me")
    CurrentUserResponse me(Authentication authentication) {
        return users.currentUser(authentication);
    }

    @GetMapping("/actor-displays")
    List<ActorDisplayResponse> actorDisplays(
            @RequestParam(name = "subjectId") List<UUID> subjectIds) {
        return users.actorDisplays(subjectIds);
    }
}
