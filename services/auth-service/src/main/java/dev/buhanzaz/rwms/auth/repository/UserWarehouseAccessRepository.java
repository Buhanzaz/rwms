package dev.buhanzaz.rwms.auth.repository;

import dev.buhanzaz.rwms.auth.domain.UserWarehouseAccess;
import dev.buhanzaz.rwms.auth.domain.WarehouseAccessLevel;
import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for warehouse-scoped grants belonging to authorization subjects. */
public interface UserWarehouseAccessRepository extends JpaRepository<UserWarehouseAccess, UUID> {

    /**
     * Lists every configured grant for a user in stable warehouse-identifier order.
     *
     * @param userId authorization-subject identifier
     * @return all configured grants ordered by warehouse identifier
     */
    List<UserWarehouseAccess> findAllByUserIdOrderByWarehouseId(UUID userId);

    /**
     * Lists only active grants for a user in stable warehouse-identifier order.
     *
     * @param userId authorization-subject identifier
     * @return active grants ordered by warehouse identifier
     */
    List<UserWarehouseAccess> findAllByUserIdAndActiveTrueOrderByWarehouseId(UUID userId);

    /**
     * Reads response fields for the complete interactive-user list without initializing the
     * grant's lazy subject association for every result.
     *
     * @param principalType interactive-user principal type
     * @return grant rows ordered by owner then warehouse identifier
     */
    @Query("""
            select new dev.buhanzaz.rwms.auth.repository.UserWarehouseAccessRepository$UserAccessRow(
                access.id, subject.id, access.warehouseId, access.accessLevel, access.active)
              from UserWarehouseAccess access
              join access.user subject
             where subject.principalType = :principalType
             order by subject.id asc, access.warehouseId asc
            """)
    List<UserAccessRow> findAllResponseRowsByPrincipalTypeOrderByUserIdAndWarehouseId(
            @Param("principalType") PrincipalType principalType);

    /** Narrow response projection that never exposes the grant entity graph. */
    record UserAccessRow(
            UUID accessId,
            UUID userId,
            String warehouseId,
            WarehouseAccessLevel accessLevel,
            boolean active) {}

    /**
     * Finds the unique configured grant for one user and warehouse.
     *
     * @param userId authorization-subject identifier
     * @param warehouseId canonical warehouse identifier
     * @return the matching grant, if present
     */
    Optional<UserWarehouseAccess> findByUserIdAndWarehouseId(UUID userId, String warehouseId);

    /**
     * Determines whether a user has at least one configured warehouse grant.
     *
     * @param userId authorization-subject identifier
     * @return whether the user has a configured grant
     */
    boolean existsByUserId(UUID userId);
}
