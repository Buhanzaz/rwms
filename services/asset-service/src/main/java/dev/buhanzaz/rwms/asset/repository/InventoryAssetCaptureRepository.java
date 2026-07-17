package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetCapture;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureState;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryAssetCaptureRepository extends JpaRepository<InventoryAssetCapture, UUID> {
  Optional<InventoryAssetCapture> findByOperationIdAndTechnicalAttempt(
      UUID operationId, long technicalAttempt);

  Optional<InventoryAssetCapture> findFirstByOperationIdOrderByTechnicalAttemptDesc(UUID operationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from InventoryAssetCapture value where value.captureId = :id")
  Optional<InventoryAssetCapture> findByIdForUpdate(@Param("id") UUID id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      update InventoryAssetCapture value
      set value.state = :expired, value.releasedAt = value.expiresAt
      where value.state = :active and value.expiresAt <= :now
      """)
  int expireActiveBefore(
      @Param("active") InventoryAssetCaptureState active,
      @Param("expired") InventoryAssetCaptureState expired,
      @Param("now") OffsetDateTime now);
}
