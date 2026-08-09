package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsIdempotencyRecord;
import dev.buhanzaz.rwms.logistics.repository.LogisticsIdempotencyRecordRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owns the durable idempotency tuple protocol for document commands. Callers keep their existing
 * transaction boundary, so the tuple lock spans the same command work as before.
 */
@Service
@RequiredArgsConstructor
class LogisticsDocumentIdempotency {
  private final LogisticsIdempotencyRecordRepository idempotencyRepository;
  private final LogisticsIdempotencyProperties idempotencyProperties;
  private final LogisticsTransactionLock transactionLock;

  void acquireLock(UUID subjectId, String operation, UUID idempotencyKey) {
    if (subjectId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("subjectId and Idempotency-Key are required");
    }
    String lockKey = subjectId + "\u001f" + operation + "\u001f" + idempotencyKey;
    transactionLock.acquire(lockKey);
  }

  LogisticsDocument replay(UUID subjectId, UUID idempotencyKey, String operation, String checksum) {
    if (subjectId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("subjectId and Idempotency-Key are required");
    }
    return idempotencyRepository
        .findBySubjectIdAndOperationNameAndIdempotencyKey(subjectId, operation, idempotencyKey)
        .map(
            record -> {
              if (!record.matches(checksum)) {
                throw new LogisticsConflictException(
                    "Idempotency key was reused with a different command");
              }
              return record.getDocument();
            })
        .orElse(null);
  }

  void remember(
      UUID subjectId,
      UUID idempotencyKey,
      String operation,
      String checksum,
      LogisticsDocument document) {
    OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    idempotencyRepository.save(
        LogisticsIdempotencyRecord.create(
            subjectId,
            operation,
            idempotencyKey,
            checksum,
            document,
            createdAt,
            createdAt.plus(idempotencyProperties.retention())));
  }
}
