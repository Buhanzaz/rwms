package dev.buhanzaz.rwms.dossier.eventing;

import dev.buhanzaz.rwms.dossier.domain.DossierDltFailureCode;
import dev.buhanzaz.rwms.dossier.domain.DossierSanitizedDeadLetter;
import dev.buhanzaz.rwms.dossier.repository.DossierSanitizedDeadLetterRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists only fixed codes, coordinates and hashes; rejected source bodies are discarded. */
@Service
public class DossierDeadLetterService {
  private final DossierSanitizedDeadLetterRepository repository;

  public DossierDeadLetterService(DossierSanitizedDeadLetterRepository repository) {
    this.repository = repository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void validationFailure(
      String topic, int partition, long offset, Object recordKey, byte[] raw, String internalCode) {
    save(
        null,
        null,
        topic,
        partition,
        offset,
        recordKey,
        DossierEventHash.sha256(raw == null ? new byte[0] : raw),
        mapValidation(internalCode),
        null,
        null);
  }

  /**
   * Records a validated processing failure against the generation locked by the caller. A null
   * subject cabin retains operational audit evidence without making any cabin read partial.
   */
  @Transactional
  public void processingFailure(
      DossierValidatedEvent event,
      Object recordKey,
      DossierDltFailureCode failureCode,
      UUID generationId,
      UUID subjectCabinId) {
    if (generationId == null) {
      throw new IllegalArgumentException("generationId is required");
    }
    save(
        event.eventId(),
        event.aggregateId(),
        event.topic(),
        event.partition(),
        event.offset(),
        recordKey,
        event.payloadSha256(),
        failureCode,
        subjectCabinId == null ? null : generationId,
        subjectCabinId);
  }

  /**
   * Resolves exact source-failure coverage without deleting or republishing its audit rows. Locked
   * reads serialize this coverage mutation with concurrent DLT relay-state transitions.
   */
  @Transactional
  public void resolveProcessingCoverage(
      UUID sourceEventId, UUID generationId, OffsetDateTime resolvedAt) {
    repository
        .findAllForUpdateBySourceEventIdAndCoverageGenerationIdAndCoverageResolvedAtIsNull(
            sourceEventId, generationId)
        .forEach(failure -> failure.resolveCoverage(resolvedAt));
  }

  private void save(
      UUID eventId,
      UUID aggregateId,
      String topic,
      int partition,
      long offset,
      Object recordKey,
      String messageHash,
      DossierDltFailureCode code,
      UUID coverageGenerationId,
      UUID coverageSubjectCabinId) {
    if (!DossierSourceTopics.inputs().contains(topic)) {
      throw new IllegalStateException("DOSSIER_SOURCE_TOPIC_HEADER_INVALID");
    }
    DossierSanitizedDeadLetter failure =
        DossierSanitizedDeadLetter.pending(
            eventId,
            aggregateId,
            topic,
            partition,
            offset,
            DossierEventHash.sha256(keyBytes(recordKey)),
            messageHash,
            code,
            coverageGenerationId,
            coverageSubjectCabinId,
            OffsetDateTime.now(ZoneOffset.UTC));
    repository
        .findForUpdateById(failure.getId())
        .ifPresentOrElse(
            existing ->
                existing.markCoverageUnresolved(
                    coverageGenerationId, coverageSubjectCabinId),
            () -> repository.save(failure));
  }

  private static byte[] keyBytes(Object recordKey) {
    if (recordKey == null) return new byte[0];
    if (recordKey instanceof byte[] bytes) return bytes.clone();
    return recordKey.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static DossierDltFailureCode mapValidation(String internalCode) {
    return switch (internalCode) {
      case "SOURCE_TOPIC_REJECTED" -> DossierDltFailureCode.UNSUPPORTED_PRODUCER;
      case "SOURCE_SCHEMA_REJECTED", "SOURCE_RECORD_INVALID" ->
          DossierDltFailureCode.INVALID_ENVELOPE;
      case "SOURCE_RECORD_KEY_MISMATCH" -> DossierDltFailureCode.RECORD_KEY_MISMATCH;
      case "SOURCE_PAYLOAD_REJECTED" -> DossierDltFailureCode.INVALID_PAYLOAD;
      case "SOURCE_PRODUCER_UNSUPPORTED" -> DossierDltFailureCode.UNSUPPORTED_PRODUCER;
      case "SOURCE_AGGREGATE_UNSUPPORTED" ->
          DossierDltFailureCode.UNSUPPORTED_AGGREGATE_TYPE;
      case "SOURCE_EVENT_TYPE_UNSUPPORTED" -> DossierDltFailureCode.UNSUPPORTED_EVENT_TYPE;
      case "SOURCE_EVENT_VERSION_UNSUPPORTED" ->
          DossierDltFailureCode.UNSUPPORTED_EVENT_VERSION;
      default -> DossierDltFailureCode.INVALID_PAYLOAD;
    };
  }
}
