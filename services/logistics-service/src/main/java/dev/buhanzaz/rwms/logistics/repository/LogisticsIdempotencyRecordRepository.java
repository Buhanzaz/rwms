package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsIdempotencyRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogisticsIdempotencyRecordRepository
    extends JpaRepository<LogisticsIdempotencyRecord, UUID> {
  Optional<LogisticsIdempotencyRecord> findBySubjectIdAndOperationNameAndIdempotencyKey(
      UUID subjectId, String operationName, UUID idempotencyKey);
}
