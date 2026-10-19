package dev.buhanzaz.rwms.auth.security;

import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectCredentialStore;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectProfileStore;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads login credentials for Spring Security from the canonical authorization subject and its
 * profile and credential projections.
 *
 * <p>The lookup fails closed when the projections are missing or their active state disagrees
 * with the subject aggregate.
 */
@Service
@RequiredArgsConstructor
public class AuthUserDetailsService implements UserDetailsService {

    private final AuthSubjectRepository subjects;
    private final AuthSubjectProfileStore profiles;
    private final AuthSubjectCredentialStore credentials;

    /**
     * Resolves a login name to Spring Security credentials and one role authority.
     *
     * @param username login name supplied by the authentication flow
     * @return Spring Security user details for a consistent active account
     * @throws UsernameNotFoundException when the account is missing or its authorization state is
     *     inconsistent
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var profile = profiles.findSubjectIdByUsername(username)
                .map(profiles::require)
                .orElseThrow(() -> new UsernameNotFoundException("Учётная запись не найдена"));
        var subject = subjects.findById(profile.subjectId())
                .orElseThrow(() -> new UsernameNotFoundException("Учётная запись не найдена"));
        var credential = credentials.require(subject.getId());
        boolean credentialActive = "ACTIVE".equals(credential.status());
        if (credentialActive != subject.isActive()) {
            throw new UsernameNotFoundException("Учётная запись имеет несогласованное состояние");
        }
        return User.withUsername(profile.username())
                .password(credential.passwordHash())
                .disabled(!credentialActive)
                .authorities(new SimpleGrantedAuthority(subject.getPrincipalType() == PrincipalType.WORKER
                        ? "ROLE_WORKER"
                        : "ROLE_" + subject.getGlobalRole().name()))
                .build();
    }
}
