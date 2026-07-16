package dev.buhanzaz.rwms.auth.security;

import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectCredentialStore.Credential;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectProfileStore.Profile;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class AuthPrincipal implements UserDetails, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final String username;
    private final String password;
    private final boolean active;
    private final PrincipalType principalType;
    private final UserGlobalRole globalRole;
    private final String externalWorkerId;
    private final String warehouseId;
    private final List<GrantedAuthority> authorities;

    private AuthPrincipal(AuthSubject subject, Profile profile, Credential credential) {
        id = subject.getId();
        username = profile.username();
        password = credential.passwordHash();
        active = subject.isActive() && "ACTIVE".equals(credential.status());
        principalType = subject.getPrincipalType();
        globalRole = subject.getGlobalRole();
        externalWorkerId = profile.externalWorkerId();
        warehouseId = subject.getWarehouseId();
        authorities = principalType == PrincipalType.WORKER
                ? List.of(new SimpleGrantedAuthority("ROLE_WORKER"))
                : List.of(new SimpleGrantedAuthority("ROLE_" + globalRole.name()));
    }

    public static AuthPrincipal from(AuthSubject subject, Profile profile, Credential credential) {
        return new AuthPrincipal(subject, profile, credential);
    }

    public UUID id() { return id; }
    public PrincipalType principalType() { return principalType; }
    public UserGlobalRole globalRole() { return globalRole; }
    public String externalWorkerId() { return externalWorkerId; }
    public String warehouseId() { return warehouseId; }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return active; }
}
