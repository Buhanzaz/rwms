package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.service.UserAdministrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
