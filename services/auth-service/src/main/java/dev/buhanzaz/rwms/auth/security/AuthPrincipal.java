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

/**
 * Immutable Spring Security principal assembled from the authorization aggregate and its
 * read-side credential/profile records.
 *
 * <p>It exposes the minimal identity attributes needed by authorization while converting the
 * principal type or global role into one Spring Security authority.
 */
public final class AuthPrincipal implements UserDetails, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Stable identifier of the authenticated authorization subject. */
    private final UUID id;
    /** Canonical login name from the profile projection. */
    private final String username;
    /** Encoded credential hash used only by Spring Security's authentication comparison. */
    private final String password;
    /** Whether both the subject and credential records permit authentication. */
    private final boolean active;
    /** Identity kind used to derive the principal's authority. */
    private final PrincipalType principalType;
    /** Interactive-user role, absent for a worker identity. */
    private final UserGlobalRole globalRole;
    /** Externally assigned worker identifier, absent for an interactive user. */
    private final String externalWorkerId;
    /** Warehouse bound to a worker identity, absent for an interactive user. */
    private final String warehouseId;
    /** Immutable Spring Security authorities derived during principal construction. */
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

    /**
     * Creates a security principal from the authoritative subject, profile, and credential state.
     *
     * @param subject canonical authorization aggregate
     * @param profile read-side profile data for the subject
     * @param credential read-side credential data for the subject
     * @return an immutable principal suitable for Spring Security authentication
     */
    public static AuthPrincipal from(AuthSubject subject, Profile profile, Credential credential) {
        return new AuthPrincipal(subject, profile, credential);
    }

    /**
     * Returns the stable identifier of the authenticated authorization subject.
     *
     * @return authorization-subject identifier
     */
    public UUID id() { return id; }

    /**
     * Returns whether this principal represents an interactive user or a worker identity.
     *
     * @return principal type
     */
    public PrincipalType principalType() { return principalType; }

    /**
     * Returns the global role of an interactive user, or {@code null} for a worker.
     *
     * @return global role or {@code null}
     */
    public UserGlobalRole globalRole() { return globalRole; }

    /**
     * Returns the external worker identifier, or {@code null} for an interactive user.
     *
     * @return external worker identifier or {@code null}
     */
    public String externalWorkerId() { return externalWorkerId; }

    /**
     * Returns the worker's bound warehouse, or {@code null} for an interactive user.
     *
     * @return bound warehouse identifier or {@code null}
     */
    public String warehouseId() { return warehouseId; }

    /**
     * Returns the role authority derived from the principal type or global role.
     *
     * @return granted authorities used for authorization
     */
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }

    /**
     * Returns the credential hash only for Spring Security's authentication comparison.
     *
     * @return encoded credential hash
     */
    @Override public String getPassword() { return password; }

    /**
     * Returns the canonical login name from the profile projection.
     *
     * @return login name
     */
    @Override public String getUsername() { return username; }

    /**
     * Returns {@code true}; this service does not model account expiry.
     *
     * @return {@code true}
     */
    @Override public boolean isAccountNonExpired() { return true; }

    /**
     * Returns {@code true}; this service does not model account locking.
     *
     * @return {@code true}
     */
    @Override public boolean isAccountNonLocked() { return true; }

    /**
     * Returns {@code true}; credential expiry is not modelled separately.
     *
     * @return {@code true}
     */
    @Override public boolean isCredentialsNonExpired() { return true; }

    /**
     * Returns whether both the subject and its credential record are active.
     *
     * @return whether the principal may authenticate
     */
    @Override public boolean isEnabled() { return active; }
}
