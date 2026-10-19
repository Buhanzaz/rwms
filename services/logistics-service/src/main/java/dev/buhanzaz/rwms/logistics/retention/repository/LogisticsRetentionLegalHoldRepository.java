package dev.buhanzaz.rwms.logistics.retention.repository;

import dev.buhanzaz.rwms.logistics.retention.domain.LogisticsRetentionDataset;
import dev.buhanzaz.rwms.logistics.retention.domain.LogisticsRetentionLegalHold;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persists legal-hold fences independently from any future retention executor. */
public interface LogisticsRetentionLegalHoldRepository
    extends JpaRepository<LogisticsRetentionLegalHold, UUID> {
  List<LogisticsRetentionLegalHold> findAllByReleasedAtIsNullOrderByPlacedAtAscIdAsc();

  long countByDatasetAndReleasedAtIsNull(LogisticsRetentionDataset dataset);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select hold from LogisticsRetentionLegalHold hold where hold.id = :id")
  Optional<LogisticsRetentionLegalHold> findForUpdate(@Param("id") UUID id);
}
