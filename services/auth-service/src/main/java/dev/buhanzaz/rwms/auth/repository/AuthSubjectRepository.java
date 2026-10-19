package dev.buhanzaz.rwms.auth.repository;

import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for authorization aggregates and authorization-oriented lookup predicates. */
public interface AuthSubjectRepository extends JpaRepository<AuthSubject, UUID> {

    /**
     * Finds a subject by login name without making login matching case-sensitive.
     *
     * @param username login name to look up
     * @return the matching subject, if present
     */
    Optional<AuthSubject> findByUsernameIgnoreCase(String username);

    /**
     * Finds a worker subject by the external worker identifier supplied by the owning service.
     *
     * @param externalWorkerId externally assigned worker identifier
     * @return the matching worker subject, if present
     */
    Optional<AuthSubject> findByExternalWorkerId(String externalWorkerId);

    /** Lists subjects of one principal type in stable login order. */
    List<AuthSubject> findAllByPrincipalTypeOrderByUsername(PrincipalType principalType);

    /**
     * Counts active subjects of a principal type and global role for authorization invariants.
     *
     * @param principalType identity kind to count
     * @param globalRole global role to count
     * @return number of matching active subjects
     */
    long countByPrincipalTypeAndGlobalRoleAndActiveTrue(PrincipalType principalType, UserGlobalRole globalRole);

    /**
     * Determines whether another subject already uses a login name, ignoring case.
     *
     * @param username login name to test
     * @param id subject to exclude from the uniqueness check
     * @return whether a different subject uses the login name
     */
    boolean existsByUsernameIgnoreCaseAndIdNot(String username, UUID id);

    /**
     * Determines whether any subject already uses a login name, ignoring case.
     *
     * @param username login name to test
     * @return whether a subject uses the login name
     */
    boolean existsByUsernameIgnoreCase(String username);
}
