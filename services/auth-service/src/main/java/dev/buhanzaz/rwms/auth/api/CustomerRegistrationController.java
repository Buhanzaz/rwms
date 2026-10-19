package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.service.CustomerRegistrationService;
import dev.buhanzaz.rwms.auth.service.CustomerRegistrationThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous, CSRF-protected HTTP boundary for customer account registration.
 *
 * <p>The security filter chain permits only this exact mutation without authentication. The
 * controller consumes the durable client/global abuse budgets before delegating identity,
 * uniqueness, credentials, and eventing to the owning service.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/v1/registrations")
public class CustomerRegistrationController {

    private final CustomerRegistrationService registrations;
    private final CustomerRegistrationThrottle throttle;

    /**
     * Registers one customer and returns its canonical non-sensitive identity.
     *
     * @param request validated registration command
     * @param servletRequest trusted forwarding-aware servlet request
     * @return {@code 201 Created} with the created subject identifier and normalized login
     */
    @PostMapping
    ResponseEntity<CustomerRegistrationResponse> register(
            @Valid @RequestBody CustomerRegistrationRequest request,
            HttpServletRequest servletRequest) {
        throttle.requireAllowed(servletRequest.getRemoteAddr());
        CustomerRegistrationResponse created = registrations.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
