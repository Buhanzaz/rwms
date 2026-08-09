package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttempt;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTargetService;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Writes durable external-effect attempts without issuing network calls. It also keeps the shared
 * owner-proof digest stable for return and transfer recovery relays.
 */
@Service
@RequiredArgsConstructor
class LogisticsDocumentAttemptWriter {
  private final LogisticsExternalAttemptRepository externalAttemptRepository;

  void createDocumentAttempt(
      LogisticsDocument document,
      LogisticsTargetService target,
      String operationType,
      String requestDigest,
      OffsetDateTime createdAt) {
    externalAttemptRepository.save(
        LogisticsExternalAttempt.create(
            document,
            null,
            target,
            operationType,
            requestDigest,
            document.getCorrelationId(),
            null,
            createdAt));
  }

  void createLineAttempt(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      LogisticsTargetService target,
      String operationType,
      String requestDigest,
      OffsetDateTime createdAt) {
    if (externalAttemptRepository
        .findByDocument_IdAndLine_IdAndOperationType(document.getId(), line.getId(), operationType)
        .isPresent()) {
      throw new LogisticsConflictException("Return external attempt already exists");
    }
    externalAttemptRepository.save(
        LogisticsExternalAttempt.create(
            document,
            line,
            target,
            operationType,
            requestDigest,
            document.getCorrelationId(),
            null,
            createdAt));
  }

  String ownerProofDigest(
      String operation,
      LogisticsDocument document,
      LogisticsDocumentLine line,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      boolean active) {
    return LogisticsCommandChecksum.sha256(
        operation,
        List.of(
            document.getDocumentType().name(),
            document.getId().toString(),
            line.getId().toString(),
            warehouseId.toString(),
            Long.toString(ownerRevision),
            Long.toString(aggregateVersion),
            Boolean.toString(active)));
  }
}
