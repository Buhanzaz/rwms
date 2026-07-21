package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LogisticsExternalAttemptRepository
    extends JpaRepository<LogisticsExternalAttempt, UUID> {
  List<LogisticsExternalAttempt> findAllByDocument_IdOrderByCreatedAtAsc(UUID documentId);

  Optional<LogisticsExternalAttempt> findByOperationId(UUID operationId);

  Optional<LogisticsExternalAttempt> findByDocument_IdAndLine_IdAndOperationType(
      UUID documentId, UUID lineId, String operationType);

  @Query(
      """
      select distinct attempt.document.id
      from LogisticsExternalAttempt attempt
      where attempt.result in :results
        and (attempt.nextAttemptAt is null or attempt.nextAttemptAt <= :now)
      order by attempt.document.id
      """)
  List<UUID> findDueDocumentIds(
      @Param("results")
          List<dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult> results,
      @Param("now") java.time.OffsetDateTime now);

  @Query(
      """
      select distinct attempt.document.id
      from LogisticsExternalAttempt attempt
      where attempt.operationType in :operationTypes
        and attempt.result in :results
        and (attempt.nextAttemptAt is null or attempt.nextAttemptAt <= :now)
      order by attempt.document.id
      """)
  List<UUID> findDueDocumentIdsByOperationTypes(
      @Param("operationTypes") List<String> operationTypes,
      @Param("results")
          List<dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult> results,
      @Param("now") java.time.OffsetDateTime now);
}
