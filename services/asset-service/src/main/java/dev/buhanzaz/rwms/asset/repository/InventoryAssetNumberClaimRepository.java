package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetNumberClaim;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryAssetNumberClaimRepository
    extends JpaRepository<InventoryAssetNumberClaim, String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from InventoryAssetNumberClaim value where value.identityMatchKey = :key")
  Optional<InventoryAssetNumberClaim> findByIdForUpdate(@Param("key") String key);
}
