package dev.buhanzaz.rwms.auth.service;

import dev.buhanzaz.rwms.auth.api.CustomerRegistrationRequest;
import dev.buhanzaz.rwms.auth.api.CustomerRegistrationResponse;
import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.eventing.AuthAggregateType;
import dev.buhanzaz.rwms.auth.eventing.AuthEventFactFactory;
import dev.buhanzaz.rwms.auth.eventing.AuthEventStore;
import dev.buhanzaz.rwms.auth.eventing.AuthEventTypes;
import dev.buhanzaz.rwms.auth.eventing.AuthInvariantGuard;
import dev.buhanzaz.rwms.auth.eventing.AuthProjectionWriter;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owns customer self-registration and its initial authorization event.
 *
 * <p>The projection, private profile and credential vaults, event stream head, event fact, and
 * outbox row are created in one transaction. Customer subjects receive no manager entitlement,
 * rental-manager entitlement, or warehouse grants; downstream customer access is granted only by
 * the dedicated OAuth client and {@code customer.rental} scope.
 */
@Service
@RequiredArgsConstructor
public class CustomerRegistrationService {

    private final AuthSubjectRepository subjects;
    private final PasswordEncoder passwordEncoder;
    private final PrincipalNamePolicy principalNames;
    private final AuthInvariantGuard invariantGuard;
    private final AuthProjectionWriter projectionWriter;
    private final AuthEventStore eventStore;
    private final AuthEventFactFactory eventFacts;

    /**
     * Creates one active customer subject after validating the repeated password and login.
     *
     * @param request registration command already validated at the transport boundary
     * @return canonical created-subject identity
     * @throws ResponseStatusException with {@code 400} when passwords differ
     * @throws ResponseStatusException with {@code 409} when the login is reserved or occupied
     */
    @Transactional
    public CustomerRegistrationResponse register(CustomerRegistrationRequest request) {
        if (!request.password().equals(request.passwordConfirmation())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Пароли не совпадают");
        }
        String username = request.username().trim();
        invariantGuard.lockCustomerRegistration(username);
        principalNames.requireAvailableForHumanPrincipal(username);
        if (subjects.existsByUsernameIgnoreCase(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Логин уже используется");
        }
        AuthSubject subject = projectionWriter.insertUser(
                username,
                passwordEncoder.encode(request.password()),
                null,
                null,
                null,
                null,
                UserGlobalRole.CUSTOMER,
                true,
                false,
                false);
        eventStore.initialize(
                AuthAggregateType.USER_AUTHORIZATION,
                subject.getId(),
                subject.getVersion(),
                AuthEventTypes.USER_CREATED,
                eventFacts.userAuthorization(subject),
                null);
        return new CustomerRegistrationResponse(subject.getId(), subject.getUsername());
    }
}
