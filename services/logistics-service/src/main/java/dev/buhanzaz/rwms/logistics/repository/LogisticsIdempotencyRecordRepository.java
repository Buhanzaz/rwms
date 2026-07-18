package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsIdempotencyRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LogisticsIdempotencyRecordRepository
    extends JpaRepository<LogisticsIdempotencyRecord, UUID> {
  @Query(
      value =
          "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text), 0))",
      nativeQuery = true)
  int acquireTransactionLock(@Param("lockKey") String lockKey);

  Optional<LogisticsIdempotencyRecord> findBySubjectIdAndOperationNameAndIdempotencyKey(
      UUID subjectId, String operationName, UUID idempotencyKey);
}
