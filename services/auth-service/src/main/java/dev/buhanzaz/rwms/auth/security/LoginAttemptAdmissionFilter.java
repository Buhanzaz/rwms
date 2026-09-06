package dev.buhanzaz.rwms.auth.security;

import dev.buhanzaz.rwms.auth.service.LoginAttemptThrottle;
import dev.buhanzaz.rwms.auth.service.LoginAttemptThrottle.Admission;
import dev.buhanzaz.rwms.auth.service.LoginAttemptThrottle.LoginAttemptThrottleUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reserves durable budgets immediately before Spring verifies a form-login password. */
public final class LoginAttemptAdmissionFilter extends OncePerRequestFilter {
    private static final String ADMISSION_ATTRIBUTE =
            LoginAttemptAdmissionFilter.class.getName() + ".admission";
    private static final String THROTTLED_PAGE = "static/login-throttled.html";
    private static final String RETRY_PLACEHOLDER = "__RETRY_AFTER_SECONDS__";
    private static final RequestMatcher LOGIN_REQUEST = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, "/login");
    private static final byte[] UNAVAILABLE_BODY = """
            {"type":"about:blank","title":"Service Unavailable","status":503,
             "detail":"Вход временно недоступен. Повторите попытку позже",
             "code":"LOGIN_ATTEMPT_SERVICE_UNAVAILABLE"}
            """.getBytes(StandardCharsets.UTF_8);

    private final LoginAttemptThrottle throttle;

    /** Creates a security-chain-local filter so the servlet container cannot register it twice. */
    public LoginAttemptAdmissionFilter(LoginAttemptThrottle throttle) {
        this.throttle = throttle;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LOGIN_REQUEST.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        Admission admission;
        try {
            admission = throttle.requireAllowed(
                    request.getRemoteAddr(), request.getParameter("username"));
        } catch (LoginAttemptThrottleUnavailableException unavailable) {
            writeUnavailable(response);
            return;
        }
        if (!admission.allowed()) {
            writeRateLimited(request, response, admission.retryAfterSeconds());
            return;
        }
        request.setAttribute(ADMISSION_ATTRIBUTE, admission);
        filterChain.doFilter(request, response);
    }

    /** Wraps the normal saved-request success handler with generation-fenced budget settlement. */
    public AuthenticationSuccessHandler settlingSuccessHandler(
            AuthenticationSuccessHandler delegate) {
        return (request, response, authentication) -> {
            throttle.refund(admission(request));
            delegate.onAuthenticationSuccess(request, response, authentication);
        };
    }

    /**
     * Wraps the normal login failure handler and refunds only authentication infrastructure aborts.
     */
    public AuthenticationFailureHandler settlingFailureHandler(
            AuthenticationFailureHandler delegate) {
        return (request, response, exception) -> {
            if (exception instanceof AuthenticationServiceException) {
                throttle.refund(admission(request));
            }
            delegate.onAuthenticationFailure(request, response, exception);
        };
    }

    private void writeRateLimited(
            HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        long retryAfter = Math.max(1, retryAfterSeconds);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (acceptsHtml(request)) {
            ClassPathResource page = new ClassPathResource(THROTTLED_PAGE);
            if (!page.exists()) {
                writeUnavailable(response);
                return;
            }
            String template;
            try (var input = page.getInputStream()) {
                template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException unreadablePage) {
                writeUnavailable(response);
                return;
            }
            if (!template.contains(RETRY_PLACEHOLDER)) {
                writeUnavailable(response);
                return;
            }
            byte[] body = template.replace(RETRY_PLACEHOLDER, Long.toString(retryAfter))
                    .getBytes(StandardCharsets.UTF_8);
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(body);
            return;
        }

        byte[] body = ("""
                {"type":"about:blank","title":"Too Many Requests","status":429,
                 "detail":"Слишком много попыток входа. Повторите попытку позже",
                 "code":"LOGIN_ATTEMPT_RATE_LIMITED","retryAfterSeconds":%d}
                """).formatted(retryAfter).getBytes(StandardCharsets.UTF_8);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(body);
    }

    private static void writeUnavailable(HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(UNAVAILABLE_BODY);
    }

    private static Admission admission(HttpServletRequest request) {
        Object admission = request.getAttribute(ADMISSION_ATTRIBUTE);
        return admission instanceof Admission value ? value : null;
    }

    private static boolean acceptsHtml(HttpServletRequest request) {
        try {
            List<MediaType> accepted = MediaType.parseMediaTypes(request.getHeader(HttpHeaders.ACCEPT));
            return accepted.stream().anyMatch(mediaType -> mediaType.getQualityValue() > 0
                    && !mediaType.isWildcardType()
                    && !mediaType.isWildcardSubtype()
                    && mediaType.isCompatibleWith(MediaType.TEXT_HTML));
        } catch (IllegalArgumentException invalidAcceptHeader) {
            return false;
        }
    }
}
