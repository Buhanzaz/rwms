package dev.buhanzaz.rwms.auth.repository;

import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthSubjectRepository extends JpaRepository<AuthSubject, UUID> {
    Optional<AuthSubject> findByUsernameIgnoreCase(String username);
    Optional<AuthSubject> findByExternalWorkerId(String externalWorkerId);
    List<AuthSubject> findAllByPrincipalTypeOrderByUsername(PrincipalType principalType);
    long countByPrincipalTypeAndGlobalRoleAndActiveTrue(PrincipalType principalType, UserGlobalRole globalRole);
    boolean existsByUsernameIgnoreCaseAndIdNot(String username, UUID id);
    boolean existsByUsernameIgnoreCase(String username);
}
