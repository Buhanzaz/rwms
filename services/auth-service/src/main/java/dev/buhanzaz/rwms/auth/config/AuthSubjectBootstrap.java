package dev.buhanzaz.rwms.auth.config;

import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.eventing.AuthAggregateType;
import dev.buhanzaz.rwms.auth.eventing.AuthEventFactFactory;
import dev.buhanzaz.rwms.auth.eventing.AuthEventStore;
import dev.buhanzaz.rwms.auth.eventing.AuthEventTypes;
import dev.buhanzaz.rwms.auth.eventing.AuthInvariantGuard;
import dev.buhanzaz.rwms.auth.eventing.AuthProjectionWriter;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectProfileStore;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectCredentialStore;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import dev.buhanzaz.rwms.auth.service.PrincipalNamePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AuthSubjectBootstrap implements ApplicationRunner {

    private final AuthSubjectRepository subjects;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final PrincipalNamePolicy principalNames;
    private final AuthSubjectProfileStore profiles;
    private final AuthProjectionWriter projectionWriter;
    private final AuthEventStore eventStore;
    private final AuthEventFactFactory eventFacts;
    private final AuthInvariantGuard invariantGuard;
    private final AuthSubjectCredentialStore credentials;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String username = requiredCredential(
                properties.bootstrapAdminUsername(), "admin", "AUTH_BOOTSTRAP_ADMIN_USERNAME");
        principalNames.requireAvailableForHumanPrincipal(username);
        String password = requiredCredential(
                properties.bootstrapAdminPassword(), "admin", "AUTH_BOOTSTRAP_ADMIN_PASSWORD");
        if (!properties.devDefaultCredentials() && password.length() < 12) {
            throw new IllegalStateException("AUTH_BOOTSTRAP_ADMIN_PASSWORD должен содержать не менее 12 символов");
        }
        invariantGuard.lockBootstrap();
        profiles.findSubjectIdByUsername(username).flatMap(subjects::findById).ifPresentOrElse(subject -> {
            if (subject.getPrincipalType() != PrincipalType.USER
                    || subject.getGlobalRole() != UserGlobalRole.SYSTEM_ADMIN
                    || !subject.isActive()
                    || !"ACTIVE".equals(credentials.require(subject.getId()).status())) {
                throw new IllegalStateException(
                        "Bootstrap admin должен быть активным SYSTEM_ADMIN с активным credential vault");
            }
            long streamVersion = eventStore.lockCurrentVersion(
                    AuthAggregateType.USER_AUTHORIZATION, subject.getId());
            if (streamVersion != subject.getVersion()) {
                throw new OptimisticLockingFailureException(
                        "Bootstrap admin authorization projection is stale");
            }
        }, () -> {
            AuthSubject admin = projectionWriter.insertUser(
                    username,
                    passwordEncoder.encode(password),
                    null,
                    null,
                    null,
                    null,
                    UserGlobalRole.SYSTEM_ADMIN,
                    true);
            eventStore.initialize(
                    AuthAggregateType.USER_AUTHORIZATION,
                    admin.getId(),
                    admin.getVersion(),
                    AuthEventTypes.USER_BOOTSTRAPPED,
                    eventFacts.userAuthorization(admin),
                    null);
        });
    }

    private String requiredCredential(String configured, String devValue, String environmentName) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        if (properties.devDefaultCredentials()) {
            return devValue;
        }
        throw new IllegalStateException(environmentName + " обязателен вне dev/test");
    }
}
