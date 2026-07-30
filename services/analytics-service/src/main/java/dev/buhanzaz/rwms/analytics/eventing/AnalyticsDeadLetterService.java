package dev.buhanzaz.rwms.analytics.eventing;

import dev.buhanzaz.rwms.analytics.domain.AnalyticsDltFailureCode;
import dev.buhanzaz.rwms.analytics.domain.AnalyticsSanitizedDeadLetter;
import dev.buhanzaz.rwms.analytics.domain.AnalyticsSourceFact;
import dev.buhanzaz.rwms.analytics.repository.AnalyticsSanitizedDeadLetterRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsDeadLetterService {
  private final AnalyticsSanitizedDeadLetterRepository repository;
  private final Clock clock;

  public AnalyticsDeadLetterService(
      AnalyticsSanitizedDeadLetterRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void validationFailure(
      String topic, int partition, long offset, Object key, byte[] raw, String internalCode) {
    save(
        null,
        null,
        topic,
        partition,
        offset,
        keyHash(key),
        AnalyticsEventHash.sha256(raw == null ? new byte[0] : raw),
        validationCode(internalCode));
  }

  @Transactional
  public void processingFailure(
      AnalyticsValidatedEvent event, Object key, AnalyticsDltFailureCode code) {
    save(
        event.eventId(),
        event.aggregateId(),
        event.topic(),
        event.partition(),
        event.offset(),
        keyHash(key),
        event.envelopeSha256(),
        code);
  }

  @Transactional
  public void gapFailure(AnalyticsSourceFact fact) {
    save(
        fact.getEventId(),
        fact.getAggregateId(),
        fact.getSourceTopic(),
        fact.getSourcePartition(),
        fact.getSourceOffset(),
        AnalyticsEventHash.sha256(fact.getAggregateId().toString()),
        fact.getEnvelopeSha256(),
        AnalyticsDltFailureCode.MISSING_AGGREGATE_VERSION);
  }

  private void save(
      java.util.UUID eventId,
      java.util.UUID aggregateId,
      String topic,
      int partition,
      long offset,
      String keyHash,
      String messageHash,
      AnalyticsDltFailureCode code) {
    AnalyticsSanitizedDeadLetter failure =
        AnalyticsSanitizedDeadLetter.pending(
            eventId,
            aggregateId,
            topic,
            partition,
            offset,
            keyHash,
            messageHash,
            code,
            OffsetDateTime.now(clock));
    if (!repository.existsById(failure.getId())) repository.save(failure);
  }

  private static String keyHash(Object key) {
    byte[] bytes =
        key instanceof byte[] raw
            ? raw.clone()
            : (key == null ? new byte[0] : key.toString().getBytes(StandardCharsets.UTF_8));
    return AnalyticsEventHash.sha256(bytes);
  }

  private static AnalyticsDltFailureCode validationCode(String code) {
    return switch (code) {
      case "SOURCE_RECORD_KEY_MISMATCH" -> AnalyticsDltFailureCode.RECORD_KEY_MISMATCH;
      case "SOURCE_PAYLOAD_REJECTED" -> AnalyticsDltFailureCode.INVALID_PAYLOAD;
      default -> AnalyticsDltFailureCode.INVALID_ENVELOPE;
    };
  }
}
