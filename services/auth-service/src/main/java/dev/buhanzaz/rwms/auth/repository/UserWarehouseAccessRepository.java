package dev.buhanzaz.rwms.auth.repository;

import dev.buhanzaz.rwms.auth.domain.UserWarehouseAccess;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserWarehouseAccessRepository extends JpaRepository<UserWarehouseAccess, UUID> {
    List<UserWarehouseAccess> findAllByUserIdOrderByWarehouseId(UUID userId);
    List<UserWarehouseAccess> findAllByUserIdAndActiveTrueOrderByWarehouseId(UUID userId);
    Optional<UserWarehouseAccess> findByUserIdAndWarehouseId(UUID userId, String warehouseId);
    boolean existsByUserId(UUID userId);
}
