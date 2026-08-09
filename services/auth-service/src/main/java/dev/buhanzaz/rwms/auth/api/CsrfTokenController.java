package dev.buhanzaz.rwms.auth.api;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the current request's CSRF token metadata to the browser login surface. */
@RestController
@RequestMapping("/api/auth")
public class CsrfTokenController {

    /**
     * Returns the token value together with the request parameter and header names Spring expects.
     *
     * @param token token resolved by Spring Security for the current request
     * @return token data safe for the login surface to submit back to this service
     */
    @GetMapping("/csrf")
    CsrfTokenResponse csrf(CsrfToken token) {
        return new CsrfTokenResponse(token.getToken(), token.getParameterName(), token.getHeaderName());
    }
}
