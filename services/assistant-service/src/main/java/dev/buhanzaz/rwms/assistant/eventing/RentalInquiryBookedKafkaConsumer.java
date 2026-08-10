package dev.buhanzaz.rwms.assistant.eventing;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes canonical logistics booking facts with four lifetime-persisted attempts and sanitized
 * coordinate-unique dead-letter evidence.
 */
@Component
@ConditionalOnProperty(
    prefix = "rwms.assistant.kafka",
    name = "enabled",
    havingValue = "true")
public class RentalInquiryBookedKafkaConsumer {
  private final RentalInquiryBookedEventParser parser;
  private final RentalInquiryArchiveService archive;
  private final AssistantEventDeadLetterService deadLetters;
  private final AssistantRetryDelayer delayer;

  public RentalInquiryBookedKafkaConsumer(
      RentalInquiryBookedEventParser parser,
      RentalInquiryArchiveService archive,
      AssistantEventDeadLetterService deadLetters,
      AssistantRetryDelayer delayer) {
    this.parser = parser;
    this.archive = archive;
    this.deadLetters = deadLetters;
    this.delayer = delayer;
  }

  /**
   * Processes attempts at t=0/1/3/7 seconds in the uninterrupted case. Interrupted waits and DLT
   * persistence failures propagate so Kafka cannot acknowledge incomplete recovery evidence.
   */
  @KafkaListener(
      id = "assistantRentalInquiryBookedListener",
      topics = "${rwms.assistant.kafka.rental-inquiry-topic}",
      groupId = "assistant-service-rental-inquiry-v1")
  public void consume(ConsumerRecord<String, String> record) throws InterruptedException {
    String raw = record.value();
    String rawSha256 =
        AssistantCanonicalEventHasher.sha256(
            raw == null ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8));
    RentalInquiryBookedEventParser.ParsedEvent parsed;
    try {
      parsed =
          parser.parse(
              record.topic(),
              record.partition(),
              record.offset(),
              record.key(),
              raw);
    } catch (AssistantEventValidationException failure) {
      if (!RentalInquiryBookedEventParser.hasDurableSource(
          record.topic(), record.partition(), record.offset())) {
        throw failure;
      }
      deadLetters.recordRejected(
          record.topic(),
          record.partition(),
          record.offset(),
          rawSha256,
          failure.code());
      return;
    }

    RentalInquiryArchiveService.StageOutcome stage;
    try {
      stage = archive.stage(parsed);
    } catch (AssistantEventIdentityConflictException conflict) {
      deadLetters.recordRejected(
          record.topic(),
          record.partition(),
          record.offset(),
          rawSha256,
          "EVENT_ID_CONFLICT");
      return;
    }
    if (stage != RentalInquiryArchiveService.StageOutcome.READY) return;

    while (true) {
      RentalInquiryArchiveService.AttemptDecision decision =
          archive.claimNextAttempt(parsed.event().eventId());
      switch (decision.status()) {
        case PROCESSED, DEAD_LETTERED -> {
          return;
        }
        case EXHAUSTED -> {
          deadLetters.recordProcessingFailure(parsed);
          return;
        }
        case WAIT -> delay(decision.delay());
        case CLAIMED -> {
          try {
            archive.process(parsed);
            return;
          } catch (RuntimeException failure) {
            if (decision.attemptNumber() == 4) {
              deadLetters.recordProcessingFailure(parsed);
              return;
            }
            archive.scheduleRetry(parsed.event().eventId(), decision.attemptNumber());
            delay(Duration.ofSeconds(1L << (decision.attemptNumber() - 1)));
          }
        }
      }
    }
  }

  private void delay(Duration duration) throws InterruptedException {
    try {
      delayer.delay(duration);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw interrupted;
    }
  }
}
