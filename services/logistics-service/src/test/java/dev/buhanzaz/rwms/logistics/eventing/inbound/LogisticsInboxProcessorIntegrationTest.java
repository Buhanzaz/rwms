package dev.buhanzaz.rwms.logistics.eventing.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LogisticsInboxProcessorIntegrationTest {
  private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000811");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsInboxProcessor inbox;
  @Autowired LogisticsInboundStagingStore staging;
  @Autowired LogisticsInboundGapRecoveryService gapRecovery;
  @Autowired LogisticsInboundProjectionVerifier projectionVerifier;
  @Autowired LogisticsInboundEnvelopeValidator validator;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.execute(
        """
        truncate table
          logistics_inbound_observation,
          logistics_inbound_replay_message,
          inbox_message,
          consumer_aggregate_checkpoint,
          version_gap_quarantine,
          sanitized_dead_letter
        """);
  }

  @Test
  void deduplicatesFactsAndProvesLiveProjectionParityWithShadowReplay() {
    UUID eventId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event =
        event(eventId, assetId, 0, "FREE");

    staging.stage(event);
    assertThat(inbox.process(event)).isEqualTo(LogisticsInboxProcessor.Outcome.PROCESSED);
    assertThat(inbox.process(event)).isEqualTo(LogisticsInboxProcessor.Outcome.DUPLICATE);

    projectionVerifier.verify();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_inbound_observation", Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select status from inbox_message where consumer_group=? and event_id=?",
                String.class,
                LogisticsInboundTransportTopics.CONSUMER_GROUP,
                eventId))
        .isEqualTo("PROCESSED");
  }

  @Test
  void quarantinesAVersionGapThenReplaysEveryMissingFactInOrder() {
    UUID assetId = UUID.randomUUID();
    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent first =
        event(UUID.randomUUID(), assetId, 0, "FREE");
    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent gap =
        event(UUID.randomUUID(), assetId, 2, "IN_TRANSFER");
    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent missing =
        event(UUID.randomUUID(), assetId, 1, "IN_TRANSFER");

    staging.stage(first);
    assertThat(inbox.process(first)).isEqualTo(LogisticsInboxProcessor.Outcome.PROCESSED);
    staging.stage(gap);
    assertThat(inbox.process(gap)).isEqualTo(LogisticsInboxProcessor.Outcome.VERSION_GAP);
    staging.markDlt(gap.eventId());
    staging.stage(missing);

    UUID quarantineId =
        jdbc.queryForObject(
            "select quarantine_id from version_gap_quarantine where received_event_id=?",
            UUID.class,
            gap.eventId());
    assertThat(gapRecovery.reconcile(quarantineId, List.of(missing.eventId()), REVIEWER)).isTrue();
    assertThat(staging.approveReplay(gap.eventId())).isTrue();
    assertThat(inbox.replay(gap.eventId())).isEqualTo(LogisticsInboxProcessor.Outcome.PROCESSED);

    projectionVerifier.verify();
    assertThat(
            jdbc.queryForObject(
                "select status from version_gap_quarantine where quarantine_id=?", String.class, quarantineId))
        .isEqualTo("RESOLVED");
    assertThat(
            jdbc.queryForObject(
                "select last_aggregate_version from consumer_aggregate_checkpoint where consumer_group=? and aggregate_id=?",
                Long.class,
                LogisticsInboundTransportTopics.CONSUMER_GROUP,
                assetId.toString()))
        .isEqualTo(2L);
  }

  @Test
  void appliesNonContiguousPublicReturnMediaFactsWithoutCreatingAVersionGap() {
    UUID mediaId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent ready =
        mediaEvent(
            UUID.randomUUID(),
            mediaId,
            2,
            "media.media.ready.v1",
            "LOGISTICS_RETURN",
            documentId + ":" + lineId,
            "READY");
    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent rotated =
        mediaEvent(
            UUID.randomUUID(),
            mediaId,
            5,
            "media.media.rotated.v1",
            "LOGISTICS_RETURN",
            documentId + ":" + lineId,
            "READY");

    staging.stage(ready);
    assertThat(inbox.process(ready)).isEqualTo(LogisticsInboxProcessor.Outcome.PROCESSED);
    staging.stage(rotated);
    assertThat(inbox.process(rotated)).isEqualTo(LogisticsInboxProcessor.Outcome.PROCESSED);

    assertThat(
            jdbc.queryForObject(
                """
                select last_aggregate_version from consumer_aggregate_checkpoint
                 where consumer_group=? and aggregate_type='MEDIA' and aggregate_id=? and not blocked
                """,
                Long.class,
                LogisticsInboundTransportTopics.CONSUMER_GROUP,
                mediaId.toString()))
        .isEqualTo(5L);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from version_gap_quarantine
                 where consumer_group=? and aggregate_type='MEDIA' and aggregate_id=?
                """,
                Long.class,
                LogisticsInboundTransportTopics.CONSUMER_GROUP,
                mediaId.toString()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from logistics_inbound_observation
                 where consumer_group=? and source_topic=? and aggregate_id=?
                """,
                Long.class,
                LogisticsInboundTransportTopics.CONSUMER_GROUP,
                LogisticsInboundTransportTopics.MEDIA,
                mediaId.toString()))
        .isEqualTo(2L);
  }

  @Test
  void acknowledgesAForeignMediaFactWithoutCreatingLogisticsDomainEvidence() {
    UUID mediaId = UUID.randomUUID();
    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent foreign =
        mediaEvent(
            UUID.randomUUID(),
            mediaId,
            2,
            "media.media.ready.v1",
            "CABIN",
            "foreign-cabin-owner",
            "READY");

    staging.stage(foreign);
    assertThat(inbox.process(foreign)).isEqualTo(LogisticsInboxProcessor.Outcome.PROCESSED);

    assertThat(
            jdbc.queryForObject(
                "select status from inbox_message where consumer_group=? and event_id=?",
                String.class,
                LogisticsInboundTransportTopics.CONSUMER_GROUP,
                foreign.eventId()))
        .isEqualTo("PROCESSED");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from consumer_aggregate_checkpoint
                 where consumer_group=? and aggregate_type='MEDIA' and aggregate_id=?
                """,
                Long.class,
                LogisticsInboundTransportTopics.CONSUMER_GROUP,
                mediaId.toString()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_inbound_observation where event_id=?",
                Long.class,
                foreign.eventId()))
        .isZero();
  }

  private LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event(
      UUID eventId, UUID assetId, long version, String status) {
    byte[] raw = LogisticsInboundEnvelopeValidatorTest.rentalEvent(eventId, assetId, version, status);
    return validator.validate(
        LogisticsInboundTransportTopics.RENTAL_ITEM,
        assetId.toString().getBytes(StandardCharsets.UTF_8),
        raw);
  }

  private LogisticsInboundEnvelopeValidator.ValidatedInboundEvent mediaEvent(
      UUID eventId,
      UUID mediaId,
      long version,
      String eventType,
      String ownerType,
      String ownerId,
      String status) {
    byte[] raw =
        LogisticsInboundEnvelopeValidatorTest.mediaEvent(
            eventId, mediaId, version, eventType, ownerType, ownerId, status);
    return validator.validate(
        LogisticsInboundTransportTopics.MEDIA,
        mediaId.toString().getBytes(StandardCharsets.UTF_8),
        raw);
  }
}
