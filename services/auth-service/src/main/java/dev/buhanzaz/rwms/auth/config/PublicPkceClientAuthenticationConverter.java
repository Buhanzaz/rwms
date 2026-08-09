package dev.buhanzaz.rwms.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Recognizes secretless refresh-token and revocation requests made by a public PKCE client.
 *
 * <p>Only a POST request with exactly one client identifier, no Authorization header, and no client
 * secret is marked for the paired provider. All other requests are deliberately left to Spring
 * Authorization Server's standard converters, preventing this compatibility path from weakening
 * confidential-client authentication.</p>
 */
final class PublicPkceClientAuthenticationConverter implements AuthenticationConverter {

    /** Marker consumed by {@link PublicPkceClientAuthenticationProvider}. */
    static final String AUTHENTICATION_MARKER = "rwms.public-pkce-client";

    /**
     * Converts an eligible public-client refresh or revoke request into a marked authentication token.
     *
     * @param request incoming OAuth endpoint request
     * @return marked client authentication for eligible requests, or {@code null} for standard
     *     processing
     */
    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                || request.getParameter("client_secret") != null
                || !singleValuePresent(request, "client_id")) {
            return null;
        }
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        boolean refresh = path.endsWith("/oauth2/token")
                && singleValue(
                        request,
                        "grant_type",
                        AuthorizationGrantType.REFRESH_TOKEN.getValue())
                && singleValuePresent(request, "refresh_token");
        boolean revocation = path.endsWith("/oauth2/revoke")
                && singleValuePresent(request, "token");
        if (!refresh && !revocation) {
            return null;
        }
        String clientId = request.getParameter("client_id");
        return new OAuth2ClientAuthenticationToken(
                clientId,
                ClientAuthenticationMethod.NONE,
                null,
                Map.of(AUTHENTICATION_MARKER, Boolean.TRUE));
    }

    private boolean singleValue(
            HttpServletRequest request, String parameter, String expected) {
        String[] values = request.getParameterValues(parameter);
        return values != null && values.length == 1 && expected.equals(values[0]);
    }

    private boolean singleValuePresent(HttpServletRequest request, String parameter) {
        String[] values = request.getParameterValues(parameter);
        return values != null && values.length == 1 && !values[0].isBlank();
    }
}
