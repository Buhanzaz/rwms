package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsIdempotencyRecord;
import dev.buhanzaz.rwms.logistics.repository.LogisticsIdempotencyRecordRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
  private final ObjectMapper objectMapper;

  void acquireLock(UUID subjectId, String operation, UUID idempotencyKey) {
    if (subjectId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("subjectId and Idempotency-Key are required");
    }
    String lockKey = subjectId + "\u001f" + operation + "\u001f" + idempotencyKey;
    transactionLock.acquire(lockKey);
  }

  LogisticsDocument replay(UUID subjectId, UUID idempotencyKey, String operation, String checksum) {
    return receipt(subjectId, idempotencyKey, operation, checksum)
        .map(LogisticsIdempotencyRecord::getDocument)
        .orElse(null);
  }

  /**
   * Reads the immutable response stored by a command rather than rematerializing its now-mutable
   * document. A matching legacy receipt without a response is rejected instead of fabricating a
   * different replay.
   */
  <T> T replayResponse(
      UUID subjectId,
      UUID idempotencyKey,
      String operation,
      String checksum,
      Class<T> responseType) {
    return receipt(subjectId, idempotencyKey, operation, checksum)
        .map(
            record -> {
              if (record.getResponseJson() == null) {
                throw new IllegalStateException("Idempotency receipt has no immutable response");
              }
              try {
                return objectMapper.readValue(record.getResponseJson(), responseType);
              } catch (JacksonException exception) {
                throw new IllegalStateException("Idempotency response is invalid", exception);
              }
            })
        .orElse(null);
  }

  /**
   * Reads an additive command receipt when present while retaining replay compatibility with
   * records written before that receipt existed.
   */
  <T> T replayOptionalResponse(
      UUID subjectId,
      UUID idempotencyKey,
      String operation,
      String checksum,
      Class<T> responseType) {
    return receipt(subjectId, idempotencyKey, operation, checksum)
        .map(
            record -> {
              if (record.getResponseJson() == null) return null;
              try {
                return objectMapper.readValue(record.getResponseJson(), responseType);
              } catch (JacksonException exception) {
                throw new IllegalStateException("Idempotency response is invalid", exception);
              }
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

  /** Stores an immutable response alongside the ordinary document/version command fence. */
  void remember(
      UUID subjectId,
      UUID idempotencyKey,
      String operation,
      String checksum,
      LogisticsDocument document,
      Object response) {
    OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      idempotencyRepository.save(
          LogisticsIdempotencyRecord.create(
              subjectId,
              operation,
              idempotencyKey,
              checksum,
              document,
              objectMapper.writeValueAsString(response),
              createdAt,
              createdAt.plus(idempotencyProperties.retention())));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Idempotency response could not be serialized", exception);
    }
  }

  private Optional<LogisticsIdempotencyRecord> receipt(
      UUID subjectId, UUID idempotencyKey, String operation, String checksum) {
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
              return record;
            });
  }
}
