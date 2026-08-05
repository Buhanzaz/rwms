package dev.buhanzaz.rwms.asset.administrative;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdministrativeAssetCorrectionRepository
    extends JpaRepository<AdministrativeAssetCorrection, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select correction
      from AdministrativeAssetCorrection correction
      where correction.actorSubjectId = :actorSubjectId
        and correction.idempotencyKey = :idempotencyKey
      """)
  Optional<AdministrativeAssetCorrection> findReplayForUpdate(
      @Param("actorSubjectId") UUID actorSubjectId,
      @Param("idempotencyKey") UUID idempotencyKey);
}
