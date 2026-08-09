package dev.buhanzaz.rwms.auth.api;

/**
 * CSRF token and the client-side names required to submit it to Spring Security.
 *
 * @param token token value bound to the current request/session
 * @param parameterName form parameter name accepted by Spring Security
 * @param headerName HTTP header name accepted by Spring Security
 */
public record CsrfTokenResponse(String token, String parameterName, String headerName) {
}
