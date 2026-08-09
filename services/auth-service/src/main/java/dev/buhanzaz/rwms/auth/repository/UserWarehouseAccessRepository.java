package dev.buhanzaz.rwms.auth.repository;

import dev.buhanzaz.rwms.auth.domain.UserWarehouseAccess;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
