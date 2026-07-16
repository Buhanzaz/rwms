package dev.buhanzaz.rwms.auth.api;

public record CsrfTokenResponse(String token, String parameterName, String headerName) {
}
