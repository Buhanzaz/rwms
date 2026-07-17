package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceReconciliation;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface MaintenanceReconciliationRepository
    extends JpaRepository<MaintenanceReconciliation, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from MaintenanceReconciliation value where value.id = :id")
  Optional<MaintenanceReconciliation> findByIdForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select value from MaintenanceReconciliation value
       where value.dependencyType = :dependencyType
         and value.operationType = :operationType
         and value.idempotencyKey = :idempotencyKey
      """)
  Optional<MaintenanceReconciliation> findByStableKeyForUpdate(
      @Param("dependencyType") String dependencyType,
      @Param("operationType") String operationType,
      @Param("idempotencyKey") UUID idempotencyKey);

  Optional<MaintenanceReconciliation>
      findByDependencyTypeAndOperationTypeAndIdempotencyKey(
          String dependencyType, String operationType, UUID idempotencyKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      select value from MaintenanceReconciliation value
       where value.state in ('PENDING', 'RETRY_PENDING', 'RECONCILIATION_REQUIRED')
         and value.attemptCount < :maximumAttempts
         and value.nextAttemptAt <= :now
       order by value.nextAttemptAt, value.id
      """)
  List<MaintenanceReconciliation> findDueForUpdateSkipLocked(
      @Param("maximumAttempts") int maximumAttempts,
      @Param("now") OffsetDateTime now,
      Pageable pageable);
}
