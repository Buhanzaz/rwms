package dev.buhanzaz.rwms.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Selects the login-page surface for browser and worker Android authorization requests.
 *
 * <p>The controller preserves OAuth error/logout flags when it redirects a worker authorization
 * request to the worker-specific login surface.
 */
@Controller
public class LoginPageController {
    private static final String WORKER_CLIENT_ID = "rwms-worker-android";
    private static final String DRIVER_CLIENT_ID = "rwms-driver-android";
    private static final String LOGIN_SURFACE_PARAMETER = "surface";
    private static final String WORKER_LOGIN_SURFACE = "worker";

    private final RequestCache requestCache = new HttpSessionRequestCache();

    /**
     * Serves the SPA login page, or redirects a saved worker authorization request to its dedicated
     * login surface.
     *
     * @param request current browser request, including saved OAuth authorization context
     * @param response current browser response used to resolve the saved request
     * @return a redirect to the worker login surface or a forward to the SPA login page
     */
    @GetMapping("/login")
    String loginPage(HttpServletRequest request, HttpServletResponse response) {
        if (isWorkerAuthorization(request, response)
                && !WORKER_LOGIN_SURFACE.equals(request.getParameter(LOGIN_SURFACE_PARAMETER))) {
            UriComponentsBuilder location = UriComponentsBuilder.fromPath("/login")
                    .queryParam(LOGIN_SURFACE_PARAMETER, WORKER_LOGIN_SURFACE);
            preserveFlag(request, location, "error");
            preserveFlag(request, location, "logout");
            return "redirect:" + location.build().encode().toUriString();
        }
        return "forward:/index.html";
    }

    private boolean isWorkerAuthorization(
            HttpServletRequest request, HttpServletResponse response) {
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest == null) {
            return false;
        }
        String[] clientIds = savedRequest.getParameterValues("client_id");
        return clientIds != null
                && Arrays.stream(clientIds)
                        .anyMatch(clientId -> WORKER_CLIENT_ID.equals(clientId)
                                || DRIVER_CLIENT_ID.equals(clientId));
    }

    private void preserveFlag(
            HttpServletRequest request, UriComponentsBuilder location, String parameter) {
        if (request.getParameterMap().containsKey(parameter)) {
            location.queryParam(parameter);
        }
    }
}
