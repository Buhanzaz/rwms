package dev.buhanzaz.rwms.assistant.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.assistant.AssistantServiceApplication;
import dev.buhanzaz.rwms.assistant.domain.AssistantConversation;
import dev.buhanzaz.rwms.assistant.domain.AssistantEventInboxState;
import dev.buhanzaz.rwms.assistant.domain.AssistantEventReplayState;
import dev.buhanzaz.rwms.assistant.repository.AssistantConversationRepository;
import dev.buhanzaz.rwms.assistant.repository.AssistantEventDeadLetterRepository;
import dev.buhanzaz.rwms.assistant.repository.AssistantEventInboxRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL evidence for lifetime attempts, source-receipt conflicts and version-fenced reviewed
 * replay of only a previously staged canonical envelope.
 */
@SpringBootTest(
    classes = AssistantServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.assistant.llm.api-key=test-key",
      "rwms.assistant.llm.require-api-key-on-startup=false",
      "rwms.assistant.kafka.enabled=false"
    })
@ActiveProfiles("test")
class AssistantDltRecoveryServiceIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssistantConversationRepository conversations;
  @Autowired AssistantEventInboxRepository inbox;
  @Autowired AssistantEventDeadLetterRepository deadLetters;
  @Autowired RentalInquiryArchiveService archive;
  @Autowired RentalInquiryBookedEventParser parser;
  @Autowired AssistantEventDeadLetterService deadLetterService;
  @Autowired AssistantDltRecoveryService recovery;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void attemptsRemainLifetimeBoundedAndReviewedReplayIsVersionFencedAndIdempotent() {
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    conversations.saveAndFlush(
        AssistantConversation.create(
            conversationId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            inquiryId,
            "PERSON",
            "Client summary"));
    var parsed =
        parse(eventId, inquiryId, conversationId, UUID.randomUUID(), 4, 81);
    assertThat(archive.stage(parsed)).isEqualTo(RentalInquiryArchiveService.StageOutcome.READY);

    for (int attempt = 1; attempt <= 4; attempt++) {
      assertThat(archive.claimNextAttempt(eventId).attemptNumber()).isEqualTo(attempt);
      if (attempt < 4) {
        archive.scheduleRetry(eventId, attempt);
        jdbc.update(
            "update assistant_event_inbox set next_attempt_at=clock_timestamp() where event_id=?",
            eventId);
      }
    }
    UUID dltId = deadLetterService.recordProcessingFailure(parsed);

    var receipt = inbox.findById(eventId).orElseThrow();
    assertThat(receipt.getAttemptCount()).isEqualTo(4);
    assertThat(receipt.getProcessingState()).isEqualTo(AssistantEventInboxState.DLT);
    var evidence = deadLetters.findById(dltId).orElseThrow();
    assertThat(evidence.getReplayState()).isEqualTo(AssistantEventReplayState.AWAITING_REVIEW);
    assertThat(evidence.getSourceTopic()).isEqualTo(parsed.sourceTopic());
    assertThat(evidence.getSourcePartition()).isEqualTo(4);
    assertThat(evidence.getSourceOffset()).isEqualTo(81);

    UUID reviewer = UUID.randomUUID();
    assertThat(recovery.approveAndReplay(dltId, 0, reviewer)).isTrue();
    assertThat(recovery.approveAndReplay(dltId, 0, reviewer)).isTrue();
    assertThat(recovery.resumeApprovedReplay(dltId, 1)).isTrue();
    assertThat(recovery.reject(dltId, 0, UUID.randomUUID())).isFalse();
    assertThat(conversations.findById(conversationId).orElseThrow().isArchived()).isTrue();
    assertThat(inbox.findById(eventId).orElseThrow().getAttemptCount()).isEqualTo(4);
    assertThat(inbox.findById(eventId).orElseThrow().getProcessingState())
        .isEqualTo(AssistantEventInboxState.PROCESSED);
    assertThat(deadLetters.findById(dltId).orElseThrow().getReplayState())
        .isEqualTo(AssistantEventReplayState.REPLAYED);
    assertThat(deadLetters.findById(dltId).orElseThrow().getReviewVersion()).isOne();
  }

  @Test
  void malformedReceiptIsCoordinateUniqueAndNeverRetainsRejectedRawValue() {
    String rejected = "Bearer top.secret.value";
    String hash = AssistantCanonicalEventHasher.sha256(rejected);

    UUID first =
        deadLetterService.recordRejected(
            RentalInquiryBookedEvent.SOURCE_TOPIC,
            7,
            909,
            hash,
            "SOURCE_SCHEMA_REJECTED");
    UUID duplicate =
        deadLetterService.recordRejected(
            RentalInquiryBookedEvent.SOURCE_TOPIC,
            7,
            909,
            hash,
            "SOURCE_SCHEMA_REJECTED");

    assertThat(duplicate).isEqualTo(first);
    assertThat(deadLetters.count()).isGreaterThanOrEqualTo(1);
    assertThat(deadLetters.findById(first).orElseThrow().getSourceEventId()).isNull();
    assertThat(deadLetters.findById(first).orElseThrow().getReplayState())
        .isEqualTo(AssistantEventReplayState.NOT_REPLAYABLE);
    assertThat(
            jdbc.queryForObject(
                "select row_to_json(value)::text from assistant_event_dead_letter value where dlt_id=?",
                String.class,
                first))
        .doesNotContain(rejected, "Bearer", "secret");
    assertThatThrownBy(
            () ->
                deadLetterService.recordRejected(
                    RentalInquiryBookedEvent.SOURCE_TOPIC,
                    7,
                    909,
                    AssistantCanonicalEventHasher.sha256("changed"),
                    "SOURCE_SCHEMA_REJECTED"))
        .isInstanceOf(AssistantEventIdentityConflictException.class);
  }

  @Test
  void eventIdAndSourceCoordinateCannotBeReboundToChangedCanonicalEvidence() {
    UUID eventId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    var original = parse(eventId, inquiryId, conversationId, UUID.randomUUID(), 8, 1001);
    archive.stage(original);

    var changedPayload =
        parse(eventId, inquiryId, conversationId, UUID.randomUUID(), 8, 1002);
    assertThatThrownBy(() -> archive.stage(changedPayload))
        .isInstanceOf(AssistantEventIdentityConflictException.class);
    var changedSourceReceipt =
        parse(UUID.randomUUID(), inquiryId, conversationId, original.event().orderId(), 8, 1001);
    assertThatThrownBy(() -> archive.stage(changedSourceReceipt))
        .isInstanceOf(AssistantEventIdentityConflictException.class);
    assertThat(inbox.findById(eventId).orElseThrow().getCanonicalEnvelopeSha256())
        .isEqualTo(original.canonicalSha256());
  }

  private RentalInquiryBookedEventParser.ParsedEvent parse(
      UUID eventId,
      UUID inquiryId,
      UUID conversationId,
      UUID orderId,
      int partition,
      long offset) {
    String envelope =
        """
        {"envelopeVersion":2,"eventId":"%s",
         "eventType":"logistics.rental-inquiry.booked.v1","eventVersion":1,
         "occurredAt":"2026-08-09T10:00:00Z","recordedAt":"2026-08-09T10:00:01Z",
         "producer":"logistics-service","aggregateType":"RENTAL_INQUIRY",
         "aggregateId":"%s","aggregateVersion":5,
         "correlation":{"correlationId":"%s","causationId":null},"actorRef":null,
         "payload":{"conversationId":"%s","orderId":"%s"}}
        """
            .formatted(
                eventId,
                inquiryId,
                conversationId,
                conversationId,
                orderId);
    return parser.parse(
        RentalInquiryBookedEvent.SOURCE_TOPIC,
        partition,
        offset,
        conversationId.toString(),
        envelope);
  }
}
