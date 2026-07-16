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

@Service
@RequiredArgsConstructor
public class AuthUserDetailsService implements UserDetailsService {

    private final AuthSubjectRepository subjects;
    private final AuthSubjectProfileStore profiles;
    private final AuthSubjectCredentialStore credentials;

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
