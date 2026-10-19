package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsTaskReference;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data persistence boundary for logistics-owned Logistics Task Reference Repository; it does
 * not own cross-service workflow decisions.
 */
public interface LogisticsTaskReferenceRepository
    extends JpaRepository<LogisticsTaskReference, UUID> {
  List<LogisticsTaskReference> findAllByDocument_IdOrderByCreatedAtAsc(UUID documentId);

  Optional<LogisticsTaskReference> findByLine_Id(UUID lineId);

  Optional<LogisticsTaskReference> findByExternalTaskId(UUID externalTaskId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select reference from LogisticsTaskReference reference where reference.id=:id")
  Optional<LogisticsTaskReference> findForUpdate(@Param("id") UUID id);

  @Query(
      """
      select reference.id
      from LogisticsTaskReference reference
      where reference.line.id in :lineIds
        and reference.taskState in (
          dev.buhanzaz.rwms.logistics.domain.LogisticsTaskReferenceState.PENDING,
          dev.buhanzaz.rwms.logistics.domain.LogisticsTaskReferenceState.REGISTERED,
          dev.buhanzaz.rwms.logistics.domain.LogisticsTaskReferenceState.READY,
          dev.buhanzaz.rwms.logistics.domain.LogisticsTaskReferenceState.CONFLICT,
          dev.buhanzaz.rwms.logistics.domain.LogisticsTaskReferenceState.RECONCILIATION_REQUIRED)
      order by reference.id
      """)
  List<UUID> findActiveIdsByLineIdIn(@Param("lineIds") Collection<UUID> lineIds);
}
