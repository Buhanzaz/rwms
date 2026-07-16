package dev.buhanzaz.rwms.auth.api;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class CsrfTokenController {

    @GetMapping("/csrf")
    CsrfTokenResponse csrf(CsrfToken token) {
        return new CsrfTokenResponse(token.getToken(), token.getParameterName(), token.getHeaderName());
    }
}
