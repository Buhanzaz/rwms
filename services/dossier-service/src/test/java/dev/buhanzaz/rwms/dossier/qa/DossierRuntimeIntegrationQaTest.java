package dev.buhanzaz.rwms.dossier.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.buhanzaz.rwms.dossier.domain.DossierActiveGeneration;
import dev.buhanzaz.rwms.dossier.domain.DossierActivity;
import dev.buhanzaz.rwms.dossier.domain.DossierActivityCode;
import dev.buhanzaz.rwms.dossier.domain.DossierAggregateBlockReason;
import dev.buhanzaz.rwms.dossier.domain.DossierDltFailureCode;
import dev.buhanzaz.rwms.dossier.domain.DossierInboxDecision;
import dev.buhanzaz.rwms.dossier.domain.DossierMediaState;
import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import dev.buhanzaz.rwms.dossier.domain.DossierReplayState;
import dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedReason;
import dev.buhanzaz.rwms.dossier.eventing.DossierEnvelopeValidator;
import dev.buhanzaz.rwms.dossier.eventing.DossierValidatedEvent;
import dev.buhanzaz.rwms.dossier.repository.DossierActiveGenerationRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierActivityRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierAggregateCheckpointRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierInboxRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierMediaProjectionRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierOutboxEventRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierPartitionCheckpointRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierReplayRunRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSanitizedDeadLetterRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSourceFactRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierUnlinkedFactRepository;
import dev.buhanzaz.rwms.dossier.service.DossierInboxProcessor;
import dev.buhanzaz.rwms.dossier.service.DossierReplayTransactions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "rwms.platform.kafka.enabled=false",
      "rwms.dossier.security.dev-auth-bypass=false",
      "spring.cloud.function.definition=",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:65535/jwks"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
@Import(DossierRuntimeIntegrationQaTest.JwtTestConfiguration.class)
class DossierRuntimeIntegrationQaTest {
  private static final UUID WAREHOUSE_A =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID WAREHOUSE_B =
      UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID WAREHOUSE_C =
      UUID.fromString("10000000-0000-0000-0000-000000000003");
  private static final UUID V5_SPB_WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID V5_MOSCOW_WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID V5_SPB_CABIN =
      UUID.fromString("51000000-0000-4000-8000-000000000001");
  private static final UUID V5_MOSCOW_CABIN =
      UUID.fromString("51000000-0000-4000-8000-000000000121");
  private static final UUID CORRELATION_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000001");

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("dossier_stage9_qa");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("DOSSIER_DB_URL", POSTGRES::getJdbcUrl);
    registry.add("DOSSIER_DB_USERNAME", POSTGRES::getUsername);
    registry.add("DOSSIER_DB_PASSWORD", POSTGRES::getPassword);
    registry.add("AUTH_ISSUER", () -> "http://issuer.invalid");
    registry.add("AUTH_AUDIENCE", () -> "rwms-services");
    registry.add("PANEL_ORIGIN", () -> "http://localhost:5173");
    registry.add(
        "DOSSIER_CURSOR_SECRET", () -> "stage-9-integration-cursor-secret-at-least-32-bytes");
  }

  @Autowired DossierEnvelopeValidator validator;
  @Autowired DossierInboxProcessor processor;
  @Autowired DossierInboxRepository inboxes;
  @Autowired DossierAggregateCheckpointRepository aggregates;
  @Autowired DossierPartitionCheckpointRepository partitions;
  @Autowired DossierActivityRepository activities;
  @Autowired DossierMediaProjectionRepository media;
  @Autowired DossierUnlinkedFactRepository unlinked;
  @Autowired DossierOutboxEventRepository outbox;
  @Autowired DossierActiveGenerationRepository activeGenerations;
  @Autowired DossierReplayRunRepository replayRuns;
  @Autowired DossierSanitizedDeadLetterRepository deadLetters;
  @Autowired DossierSourceFactRepository sourceFacts;
  @Autowired DossierReplayTransactions replay;
  @Autowired MockMvc mvc;

  @Test
  void gapRecoveryDrainsInOrderWhileUnrelatedAggregatesAndOldRedeliveryRemainSafe() {
    UUID cabin = UUID.fromString("30000000-0000-0000-0000-000000000001");
    UUID unrelated = UUID.fromString("30000000-0000-0000-0000-000000000002");
    DossierValidatedEvent versionTwo =
        asset(cabin, WAREHOUSE_A, 2, 20, "asset.rental-item.status-changed.v1", true);
    DossierValidatedEvent unrelatedZero =
        asset(unrelated, WAREHOUSE_A, 0, 21, "asset.rental-item.created.v1", true);
    DossierValidatedEvent versionZero =
        asset(cabin, WAREHOUSE_A, 0, 10, "asset.rental-item.created.v1", true);
    DossierValidatedEvent versionOne =
        asset(cabin, WAREHOUSE_A, 1, 11, "asset.rental-item.passport-changed.v1", false);

    assertThat(processor.process(versionTwo))
        .isEqualTo(DossierInboxProcessor.Outcome.VERSION_GAP);
    assertThat(processor.process(unrelatedZero))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(processor.process(versionZero)).isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(processor.process(versionOne)).isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(processor.process(versionZero)).isEqualTo(DossierInboxProcessor.Outcome.DUPLICATE);

    var checkpoint =
        aggregates
            .findByConsumerGroupAndProducerAndSourceTopicAndAggregateTypeAndAggregateId(
                "dossier-projection-v1",
                DossierProducer.ASSET,
                "rwms.asset.rental-item.v1",
                "RENTAL_ITEM",
                cabin)
            .orElseThrow();
    assertThat(checkpoint.isBlocked()).isFalse();
    assertThat(checkpoint.getAppliedVersion()).isEqualTo(2);
    assertThat(inboxes.findById(versionTwo.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.PROCESSED);
    assertThat(
            unlinked
                .findBySourceEventIdAndGenerationId(versionTwo.eventId(), activeGeneration())
                .orElseThrow()
                .getResolvedAt())
        .isNotNull();
    assertThat(
            activities.findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
                cabin, activeGeneration(), org.springframework.data.domain.Pageable.unpaged()))
        .hasSize(3);
    assertThat(
            partitions
                .findByConsumerGroupAndSourceTopicAndSourcePartition(
                    "dossier-projection-v1", "rwms.asset.rental-item.v1", 0)
                .orElseThrow()
                .getLastAcceptedOffset())
        .isEqualTo(21);
  }

  @Test
  void canonicalCommentAndManualNoteResolveOnlyFromAnExistingAssetProof() {
    UUID cabin = UUID.randomUUID();
    DossierValidatedEvent created =
        asset(cabin, WAREHOUSE_A, 0, 22, "asset.rental-item.created.v1", true);
    DossierValidatedEvent status =
        asset(cabin, WAREHOUSE_A, 1, 23, "asset.rental-item.status-changed.v1", true);
    DossierValidatedEvent comment = assetComment(cabin, 2, 24, 2);
    DossierValidatedEvent note = assetManualNote(cabin, 3, 25, UUID.randomUUID());

    assertThat(
            List.of(
                processor.process(created),
                processor.process(status),
                processor.process(comment),
                processor.process(note)))
        .containsOnly(DossierInboxProcessor.Outcome.PROCESSED);

    assertThat(
            activities.findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
                cabin,
                activeGeneration(),
                org.springframework.data.domain.Pageable.unpaged()))
        .extracting(DossierActivity::getActivityCode)
        .containsExactlyInAnyOrder(
            DossierActivityCode.CABIN_CREATED,
            DossierActivityCode.CABIN_STATUS_CHANGED,
            DossierActivityCode.CABIN_COMMENT_REVISION_CHANGED,
            DossierActivityCode.CABIN_MANUAL_NOTE_ADDED);
    assertThat(
            activities.findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
                cabin,
                activeGeneration(),
                org.springframework.data.domain.Pageable.unpaged()))
        .extracting(DossierActivity::getWarehouseId)
        .containsOnly(WAREHOUSE_A);

    var commentFact = sourceFacts.findByEventId(comment.eventId()).orElseThrow();
    assertThat(commentFact.getSubjectCabinId()).isNull();
    assertThat(commentFact.getSubjectWarehouseId()).isNull();
    assertThat(commentFact.getCanonicalEnvelope())
        .contains("\"rentalItemId\":\"" + cabin + "\"")
        .contains("\"commentRevision\":2");
    var noteFact = sourceFacts.findByEventId(note.eventId()).orElseThrow();
    assertThat(noteFact.getSubjectCabinId()).isNull();
    assertThat(noteFact.getSubjectWarehouseId()).isNull();
    assertThat(noteFact.getCanonicalEnvelope()).contains("\"noteId\"");

    UUID sourceGeneration = activeGeneration();
    DossierReplayTransactions.ReplayClaim claim = replay.start();
    replay.build(claim.runId());
    replay.tailVerifyAndActivate(claim.runId());
    assertThat(activeGeneration()).isNotEqualTo(sourceGeneration);
    assertThat(
            activities.findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
                cabin,
                activeGeneration(),
                org.springframework.data.domain.Pageable.unpaged()))
        .extracting(DossierActivity::getActivityCode)
        .containsExactlyInAnyOrder(
            DossierActivityCode.CABIN_CREATED,
            DossierActivityCode.CABIN_STATUS_CHANGED,
            DossierActivityCode.CABIN_COMMENT_REVISION_CHANGED,
            DossierActivityCode.CABIN_MANUAL_NOTE_ADDED);

    UUID missingCommentCabin = UUID.randomUUID();
    UUID missingNoteCabin = UUID.randomUUID();
    DossierValidatedEvent missingComment = assetComment(missingCommentCabin, 0, 26, 1);
    DossierValidatedEvent missingNote =
        assetManualNote(missingNoteCabin, 0, 27, UUID.randomUUID());

    assertThat(processor.process(missingComment))
        .isEqualTo(DossierInboxProcessor.Outcome.EVENT_IDENTITY_CONFLICT);
    assertThat(processor.process(missingNote))
        .isEqualTo(DossierInboxProcessor.Outcome.EVENT_IDENTITY_CONFLICT);
    assertThat(inboxes.findById(missingComment.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.DLT);
    assertThat(inboxes.findById(missingNote.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.DLT);
    assertThat(sourceFacts.findByEventId(missingComment.eventId())).isPresent();
    assertThat(sourceFacts.findByEventId(missingNote.eventId())).isPresent();
    assertThat(deadLetters.findAll())
        .filteredOn(value -> value.getFailureCode() == DossierDltFailureCode.EVENT_IDENTITY_CONFLICT)
        .extracting(value -> value.getSourceEventId())
        .contains(missingComment.eventId(), missingNote.eventId());
  }

  @Test
  void actualAssetV5CreatedStatusAndCommentWiresProjectAndReplayForBothWarehouses()
      throws Exception {
    String databaseName = "asset_v5_" + UUID.randomUUID().toString().replace("-", "");
    createDatabase(databaseName);
    try {
      migrateAssetDatabase(databaseName);
      assertAssetV5GlobalNumbering(databaseName);
      List<AssetOutboxWire> wires = readAssetV5Wires(databaseName);
      assertThat(wires)
          .extracting(
              wire ->
                  wire.aggregateId()
                      + ":"
                      + wire.aggregateVersion()
                      + ":"
                      + wire.eventType())
          .containsExactly(
              V5_SPB_CABIN + ":0:asset.rental-item.created.v1",
              V5_SPB_CABIN + ":1:asset.rental-item.status-changed.v1",
              V5_SPB_CABIN + ":2:asset.rental-item.general-comment-changed.v1",
              V5_MOSCOW_CABIN + ":0:asset.rental-item.created.v1",
              V5_MOSCOW_CABIN + ":1:asset.rental-item.status-changed.v1",
              V5_MOSCOW_CABIN + ":2:asset.rental-item.general-comment-changed.v1");

      long offset = 300;
      for (AssetOutboxWire wire : wires) {
        DossierValidatedEvent event =
            validator.validate(
                wire.topic(),
                5,
                offset++,
                wire.aggregateId().toString(),
                wire.envelope().getBytes(StandardCharsets.UTF_8));
        assertThat(processor.process(event)).isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
      }

      UUID sourceGeneration = activeGeneration();
      assertV5CabinHistory(V5_SPB_CABIN, V5_SPB_WAREHOUSE, sourceGeneration);
      assertV5CabinHistory(V5_MOSCOW_CABIN, V5_MOSCOW_WAREHOUSE, sourceGeneration);
      for (UUID cabin : List.of(V5_SPB_CABIN, V5_MOSCOW_CABIN)) {
        var commentFact =
            sourceFacts.findAll().stream()
                .filter(value -> value.getAggregateId().equals(cabin))
                .filter(
                    value ->
                        value
                            .getEventType()
                            .equals("asset.rental-item.general-comment-changed.v1"))
                .findFirst()
                .orElseThrow();
        assertThat(commentFact.getSubjectCabinId()).isNull();
        assertThat(commentFact.getSubjectWarehouseId()).isNull();
        assertThat(commentFact.getCanonicalEnvelope())
            .contains("\"rentalItemId\":\"" + cabin + "\"")
            .contains("\"commentRevision\":2")
            .doesNotContain("\"warehouseId\"");
      }

      DossierReplayTransactions.ReplayClaim claim = replay.start();
      replay.build(claim.runId());
      replay.tailVerifyAndActivate(claim.runId());
      UUID replayGeneration = activeGeneration();
      assertThat(replayGeneration).isNotEqualTo(sourceGeneration);
      assertV5CabinHistory(V5_SPB_CABIN, V5_SPB_WAREHOUSE, replayGeneration);
      assertV5CabinHistory(V5_MOSCOW_CABIN, V5_MOSCOW_WAREHOUSE, replayGeneration);
    } finally {
      dropDatabase(databaseName);
    }
  }

  @Test
  void mediaAndFindingArrivalOrdersConvergeAndReplayIncludesTheTailBeforeCasActivation() {
    UUID cabinBefore = UUID.fromString("31000000-0000-0000-0000-000000000001");
    UUID findingBefore = UUID.fromString("32000000-0000-0000-0000-000000000001");
    UUID mediaBefore = UUID.fromString("33000000-0000-0000-0000-000000000001");
    UUID cabinAfter = UUID.fromString("31000000-0000-0000-0000-000000000002");
    UUID findingAfter = UUID.fromString("32000000-0000-0000-0000-000000000002");
    UUID mediaAfter = UUID.fromString("33000000-0000-0000-0000-000000000002");

    processor.process(media(mediaBefore, findingBefore, WAREHOUSE_A, 1, 0, "PROCESSING"));
    processor.process(media(mediaBefore, findingBefore, WAREHOUSE_A, 2, 1, "READY"));
    processor.process(finding(findingBefore, cabinBefore, WAREHOUSE_A, 0, 0));

    processor.process(finding(findingAfter, cabinAfter, WAREHOUSE_A, 1, 0));
    processor.process(media(mediaAfter, findingAfter, WAREHOUSE_A, 1, 2, "PROCESSING"));
    processor.process(media(mediaAfter, findingAfter, WAREHOUSE_A, 2, 3, "READY"));

    UUID sourceGeneration = activeGeneration();
    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(cabinBefore, sourceGeneration))
        .singleElement()
        .satisfies(value -> assertThat(value.getState()).isEqualTo(DossierMediaState.READY));
    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(cabinAfter, sourceGeneration))
        .singleElement()
        .satisfies(value -> assertThat(value.getState()).isEqualTo(DossierMediaState.READY));
    assertThat(unlinked.countByGenerationIdAndResolvedAtIsNull(sourceGeneration)).isZero();

    DossierReplayTransactions.ReplayClaim claim = replay.start();
    UUID targetGeneration = replayRuns.findById(claim.runId()).orElseThrow().getTargetGenerationId();
    UUID tailCabin = UUID.fromString("34000000-0000-0000-0000-000000000001");
    processor.process(asset(tailCabin, WAREHOUSE_A, 0, 50, "asset.rental-item.created.v1", true));
    long outboxBeforeReplay = outbox.count();

    replay.build(claim.runId());
    replay.tailVerifyAndActivate(claim.runId());

    assertThat(activeGeneration()).isEqualTo(targetGeneration).isNotEqualTo(sourceGeneration);
    assertThat(replayRuns.findById(claim.runId()).orElseThrow().getState())
        .isEqualTo(DossierReplayState.ACTIVATED);
    assertThat(activities.countByGenerationId(targetGeneration))
        .isEqualTo(activities.countByGenerationId(sourceGeneration));
    assertThat(media.countByGenerationId(targetGeneration))
        .isEqualTo(media.countByGenerationId(sourceGeneration));
    assertThat(outbox.count()).isEqualTo(outboxBeforeReplay);
  }

  @Test
  void mediaAndPublicationRejectAContradictoryWarehouseOwnerProof() {
    UUID findingId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    UUID publicationId = UUID.randomUUID();
    DossierValidatedEvent finding = finding(findingId, cabinId, WAREHOUSE_A, 100, 0);
    DossierValidatedEvent conflictingMedia =
        media(mediaId, findingId, WAREHOUSE_B, 1, 101, "PROCESSING");
    DossierValidatedEvent conflictingPublication =
        publication(publicationId, findingId, WAREHOUSE_B, 102);

    assertThat(processor.process(finding)).isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(processor.process(conflictingMedia))
        .isEqualTo(DossierInboxProcessor.Outcome.EVENT_IDENTITY_CONFLICT);
    assertThat(processor.process(conflictingPublication))
        .isEqualTo(DossierInboxProcessor.Outcome.EVENT_IDENTITY_CONFLICT);

    assertThat(inboxes.findById(conflictingMedia.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.DLT);
    assertThat(inboxes.findById(conflictingPublication.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.DLT);
    assertThat(deadLetters.findAll())
        .filteredOn(value -> value.getFailureCode() == DossierDltFailureCode.EVENT_IDENTITY_CONFLICT)
        .extracting(value -> value.getSourceEventId())
        .contains(conflictingMedia.eventId(), conflictingPublication.eventId());
    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(cabinId, activeGeneration()))
        .isEmpty();
  }

  @Test
  void directCabinMediaRequiresTheAssetWarehouseProofInBothArrivalOrders() {
    UUID deferredCabin = UUID.randomUUID();
    UUID deferredMediaId = UUID.randomUUID();
    DossierValidatedEvent deferredMedia =
        cabinMedia(deferredMediaId, deferredCabin, WAREHOUSE_A, 1, 130, "READY");

    assertThat(processor.process(deferredMedia))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(deferredCabin, activeGeneration()))
        .isEmpty();
    assertThat(
            unlinked
                .findBySourceEventIdAndGenerationId(deferredMedia.eventId(), activeGeneration())
                .orElseThrow()
                .getResolvedAt())
        .isNull();

    assertThat(
            processor.process(
                asset(
                    deferredCabin,
                    WAREHOUSE_A,
                    0,
                    131,
                    "asset.rental-item.created.v1",
                    true)))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(deferredCabin, activeGeneration()))
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.getMediaId()).isEqualTo(deferredMediaId);
              assertThat(value.getState()).isEqualTo(DossierMediaState.READY);
              assertThat(value.getWarehouseId()).isEqualTo(WAREHOUSE_A);
            });
    assertThat(
            unlinked
                .findBySourceEventIdAndGenerationId(deferredMedia.eventId(), activeGeneration())
                .orElseThrow()
                .getResolvedAt())
        .isNotNull();

    UUID provenCabin = UUID.randomUUID();
    assertThat(
            processor.process(
                asset(
                    provenCabin,
                    WAREHOUSE_A,
                    0,
                    132,
                    "asset.rental-item.created.v1",
                    true)))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    DossierValidatedEvent directMedia =
        cabinMedia(UUID.randomUUID(), provenCabin, WAREHOUSE_A, 1, 133, "READY");
    assertThat(processor.process(directMedia))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(provenCabin, activeGeneration()))
        .singleElement();
    assertThat(
            activities
                .findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
                    provenCabin,
                    activeGeneration(),
                    org.springframework.data.domain.Pageable.unpaged()))
        .extracting(DossierActivity::getActivityCode)
        .contains(DossierActivityCode.MEDIA_READY);
  }

  @Test
  void directCabinMediaCannotCrossTheAssetWarehouseBoundary() {
    UUID provenCabin = UUID.randomUUID();
    assertThat(
            processor.process(
                asset(
                    provenCabin,
                    WAREHOUSE_A,
                    0,
                    140,
                    "asset.rental-item.created.v1",
                    true)))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    DossierValidatedEvent conflicting =
        cabinMedia(UUID.randomUUID(), provenCabin, WAREHOUSE_B, 1, 141, "READY");

    assertThat(processor.process(conflicting))
        .isEqualTo(DossierInboxProcessor.Outcome.EVENT_IDENTITY_CONFLICT);
    assertThat(inboxes.findById(conflicting.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.DLT);
    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(provenCabin, activeGeneration()))
        .isEmpty();

    UUID deferredCabin = UUID.randomUUID();
    DossierValidatedEvent deferredConflict =
        cabinMedia(UUID.randomUUID(), deferredCabin, WAREHOUSE_B, 1, 142, "READY");
    assertThat(processor.process(deferredConflict))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(
            processor.process(
                asset(
                    deferredCabin,
                    WAREHOUSE_A,
                    0,
                    143,
                    "asset.rental-item.created.v1",
                    true)))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(inboxes.findById(deferredConflict.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.DLT);
    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(deferredCabin, activeGeneration()))
        .isEmpty();
  }

  @Test
  void cabinBatchFolderGroupsAssetsWithoutMultiplyingReadyHistoryFacts() {
    UUID cabinId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    UUID firstMediaId = UUID.randomUUID();
    UUID secondMediaId = UUID.randomUUID();
    assertThat(
            processor.process(
                asset(
                    cabinId,
                    WAREHOUSE_A,
                    0,
                    150,
                    "asset.rental-item.created.v1",
                    true)))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);

    assertThat(
            processor.process(
                cabinMedia(firstMediaId, folderId, cabinId, WAREHOUSE_A, 1, 151, "READY")))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(
            processor.process(
                cabinMedia(secondMediaId, folderId, cabinId, WAREHOUSE_A, 1, 152, "READY")))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);

    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(cabinId, activeGeneration()))
        .hasSize(2)
        .allSatisfy(value -> assertThat(value.getFolderId()).isEqualTo(folderId));
    assertThat(
            activities
                .findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
                    cabinId,
                    activeGeneration(),
                    org.springframework.data.domain.Pageable.unpaged()))
        .filteredOn(value -> value.getActivityCode() == DossierActivityCode.MEDIA_READY)
        .hasSize(2);
  }

  @Test
  void canonicalPublicMediaBaselineRecoversReadyAndCarriesTheCorrelatedUploadActor()
      throws Exception {
    UUID cabinId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    assertThat(
            processor.process(
                asset(
                    cabinId,
                    WAREHOUSE_A,
                    0,
                    160,
                    "asset.rental-item.created.v1",
                    true)))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);

    DossierValidatedEvent ready =
        cabinMediaFact(
            mediaId,
            folderId,
            cabinId,
            WAREHOUSE_A,
            3,
            162,
            "media.media.ready.v1",
            "READY",
            correlationId,
            null);
    DossierValidatedEvent uploaded =
        cabinMediaFact(
            mediaId,
            folderId,
            cabinId,
            WAREHOUSE_A,
            2,
            161,
            "media.media.uploaded.v1",
            "PROCESSING",
            correlationId,
            actorId);

    assertThat(processor.process(ready)).isEqualTo(DossierInboxProcessor.Outcome.VERSION_GAP);
    assertThat(processor.process(uploaded)).isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);

    assertThat(inboxes.findById(ready.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.PROCESSED);
    assertThat(
            unlinked
                .findBySourceEventIdAndGenerationId(ready.eventId(), activeGeneration())
                .orElseThrow()
                .getResolvedAt())
        .isNotNull();
    var uploadedFact = sourceFacts.findByEventId(uploaded.eventId()).orElseThrow();
    assertThat(uploadedFact.getAggregateVersion()).isEqualTo(2);
    assertThat(uploadedFact.getEventType()).isEqualTo("media.media.uploaded.v1");
    assertThat(uploadedFact.getActivityCode()).isNull();
    assertThat(uploadedFact.getActorSubjectId()).isEqualTo(actorId);
    assertThat(uploadedFact.getActorPrincipalType()).isEqualTo("USER");
    assertThat(uploadedFact.getActorProfileRevision()).isNull();
    assertThat(uploadedFact.getCorrelationId()).isEqualTo(correlationId);
    assertThat(uploadedFact.getSubjectCabinId()).isEqualTo(cabinId);
    assertThat(uploadedFact.getSubjectWarehouseId()).isEqualTo(WAREHOUSE_A);
    assertThat(uploadedFact.getSubjectSecondaryId()).isEqualTo(cabinId);

    var readyFact = sourceFacts.findByEventId(ready.eventId()).orElseThrow();
    assertThat(readyFact.getActorSubjectId()).isNull();
    assertThat(readyFact.getActivityCode()).isEqualTo(DossierActivityCode.MEDIA_READY);
    assertThat(readyFact.getCorrelationId()).isEqualTo(correlationId);
    assertThat(readyFact.getSubjectCabinId()).isEqualTo(cabinId);
    assertThat(readyFact.getSubjectWarehouseId()).isEqualTo(WAREHOUSE_A);
    assertThat(readyFact.getSubjectSecondaryId()).isEqualTo(cabinId);

    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(cabinId, activeGeneration()))
        .singleElement()
        .satisfies(
            projection -> {
              assertThat(projection.getMediaId()).isEqualTo(mediaId);
              assertThat(projection.getFolderId()).isEqualTo(folderId);
              assertThat(projection.getState()).isEqualTo(DossierMediaState.READY);
              assertThat(projection.getSourceAggregateVersion()).isEqualTo(3);
            });
    DossierActivity readyActivity =
        activities
            .findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
                cabinId,
                activeGeneration(),
                org.springframework.data.domain.Pageable.unpaged())
            .stream()
            .filter(value -> value.getActivityCode() == DossierActivityCode.MEDIA_READY)
            .reduce((first, duplicate) -> {
              throw new AssertionError("MEDIA_READY must be projected exactly once");
            })
            .orElseThrow();
    assertThat(readyActivity.getSourceEventId()).isEqualTo(ready.eventId());
    assertThat(readyActivity.getSourceProducer()).isEqualTo(DossierProducer.MEDIA);
    assertThat(readyActivity.getSourceAggregateType()).isEqualTo("MEDIA");
    assertThat(readyActivity.getSourceAggregateId()).isEqualTo(mediaId);
    assertThat(readyActivity.getSourceSecondaryId()).isEqualTo(cabinId);
    assertThat(readyActivity.getActorSubjectId()).isEqualTo(actorId);
    assertThat(readyActivity.getActorPrincipalType()).isEqualTo("USER");
    assertThat(readyActivity.getActorProfileRevision()).isNull();
    assertThat(readyActivity.getCorrelationId()).isEqualTo(correlationId);

    JsonNode outbound =
        new ObjectMapper()
            .readTree(
                outbox
                    .findBySourceEventIdAndCabinId(ready.eventId(), cabinId)
                    .orElseThrow()
                    .getCanonicalPayload());
    assertThat(outbound.required("actorRef").required("subjectId").textValue())
        .isEqualTo(actorId.toString());
    assertThat(outbound.required("actorRef").required("principalType").textValue())
        .isEqualTo("USER");
    assertThat(outbound.required("payload").required("sourceRef").required("producer").textValue())
        .isEqualTo("media-service");
    assertThat(
            outbound
                .required("payload")
                .required("sourceRef")
                .required("aggregateType")
                .textValue())
        .isEqualTo("MEDIA");
    assertThat(
            outbound
                .required("payload")
                .required("sourceRef")
                .required("aggregateId")
                .textValue())
        .isEqualTo(mediaId.toString());
    assertThat(
            outbound
                .required("payload")
                .required("sourceRef")
                .required("secondaryId")
                .textValue())
        .isEqualTo(cabinId.toString());

    var checkpoint = mediaCheckpoint(mediaId);
    assertThat(checkpoint.getAppliedVersion()).isEqualTo(3);
    assertThat(checkpoint.isBlocked()).isFalse();

    DossierValidatedEvent futureGap =
        cabinMediaFact(
            mediaId,
            folderId,
            cabinId,
            WAREHOUSE_A,
            5,
            163,
            "media.media.rotated.v1",
            "READY",
            correlationId,
            null);
    assertThat(processor.process(futureGap))
        .isEqualTo(DossierInboxProcessor.Outcome.VERSION_GAP);
    assertThat(inboxes.findById(futureGap.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.QUARANTINED);
    assertThat(
            unlinked
                .findBySourceEventIdAndGenerationId(futureGap.eventId(), activeGeneration())
                .orElseThrow()
                .getReason())
        .isEqualTo(DossierUnlinkedReason.MISSING_PREFIX);
    checkpoint = mediaCheckpoint(mediaId);
    assertThat(checkpoint.getAppliedVersion()).isEqualTo(3);
    assertThat(checkpoint.isBlocked()).isTrue();
    assertThat(checkpoint.getBlockedReason()).isEqualTo(DossierAggregateBlockReason.MISSING_PREFIX);
    assertThat(checkpoint.getExpectedVersion()).isEqualTo(4L);
    assertThat(checkpoint.getObservedVersion()).isEqualTo(5L);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void persistedPublicMediaProcessingFailureRecoversWhenTheUploadedFactReplays() {
    UUID cabinId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    assertThat(
            processor.process(
                asset(
                    cabinId,
                    WAREHOUSE_A,
                    0,
                    164,
                    "asset.rental-item.created.v1",
                    true)))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);

    DossierValidatedEvent ready =
        cabinMediaFact(
            mediaId,
            null,
            cabinId,
            WAREHOUSE_A,
            3,
            166,
            "media.media.ready.v1",
            "READY",
            correlationId,
            null);
    DossierValidatedEvent uploaded =
        cabinMediaFact(
            mediaId,
            folderId,
            cabinId,
            WAREHOUSE_A,
            2,
            165,
            "media.media.uploaded.v1",
            "PROCESSING",
            correlationId,
            actorId);

    assertThat(processor.process(ready)).isEqualTo(DossierInboxProcessor.Outcome.VERSION_GAP);
    var blocked = mediaCheckpoint(mediaId);
    assertThat(blocked.getAppliedVersion()).isZero();
    assertThat(blocked.getBlockedReason()).isEqualTo(DossierAggregateBlockReason.MISSING_PREFIX);
    assertThat(blocked.getExpectedVersion()).isEqualTo(1L);
    assertThat(blocked.getObservedVersion()).isEqualTo(3L);
    assertThat(
            unlinked
                .findBySourceEventIdAndGenerationId(ready.eventId(), activeGeneration())
                .orElseThrow())
        .satisfies(
            value -> {
              assertThat(value.getReason()).isEqualTo(DossierUnlinkedReason.MISSING_PREFIX);
              assertThat(value.getResolvedAt()).isNull();
            });

    processor.deadLetterAfterRetries(uploaded, mediaId);
    assertThat(inboxes.findById(uploaded.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.DLT);
    assertThat(sourceFacts.findByEventId(uploaded.eventId())).isPresent();
    assertThat(
            unlinked
                .findBySourceEventIdAndGenerationId(uploaded.eventId(), activeGeneration())
                .orElseThrow())
        .satisfies(
            value -> {
              assertThat(value.getReason())
                  .isEqualTo(DossierUnlinkedReason.AGGREGATE_QUARANTINED);
              assertThat(value.getResolvedAt()).isNull();
            });
    blocked = mediaCheckpoint(mediaId);
    assertThat(blocked.getAppliedVersion()).isZero();
    assertThat(blocked.getBlockedReason())
        .isEqualTo(DossierAggregateBlockReason.PROCESSING_FAILED);
    assertThat(blocked.getExpectedVersion()).isNull();
    assertThat(blocked.getObservedVersion()).isNull();
    var persistedUploadedFact = sourceFacts.findByEventId(uploaded.eventId()).orElseThrow();
    assertThat(deadLetters.findAll())
        .filteredOn(value -> uploaded.eventId().equals(value.getSourceEventId()))
        .singleElement()
        .satisfies(
            value ->
                assertThat(value.getFailureCode())
                    .isEqualTo(DossierDltFailureCode.PROCESSING_FAILED));

    DossierValidatedEvent replayedUploaded =
        validator.validate(
            uploaded.topic(),
            uploaded.partition(),
            167,
            mediaId.toString(),
            uploaded.canonicalEnvelope().getBytes(StandardCharsets.UTF_8));

    assertThat(processor.process(replayedUploaded))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(inboxes.findById(uploaded.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.PROCESSED);
    assertThat(inboxes.findById(ready.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.PROCESSED);
    assertThat(
            unlinked
                .findBySourceEventIdAndGenerationId(uploaded.eventId(), activeGeneration())
                .orElseThrow()
                .getResolvedAt())
        .isNotNull();
    assertThat(
            unlinked
                .findBySourceEventIdAndGenerationId(ready.eventId(), activeGeneration())
                .orElseThrow())
        .satisfies(
            value -> {
              assertThat(value.getReason()).isEqualTo(DossierUnlinkedReason.MISSING_PREFIX);
              assertThat(value.getResolvedAt()).isNotNull();
            });

    var uploadedFact = sourceFacts.findByEventId(uploaded.eventId()).orElseThrow();
    assertThat(uploadedFact.getActorSubjectId()).isEqualTo(actorId);
    assertThat(uploadedFact.getActorPrincipalType()).isEqualTo("USER");
    assertThat(uploadedFact.getCorrelationId()).isEqualTo(correlationId);
    var readyFact = sourceFacts.findByEventId(ready.eventId()).orElseThrow();
    assertThat(readyFact.getActorSubjectId()).isNull();
    assertThat(readyFact.getActorPrincipalType()).isNull();
    assertThat(readyFact.getCorrelationId()).isEqualTo(correlationId);

    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(cabinId, activeGeneration()))
        .singleElement()
        .satisfies(
            projection -> {
              assertThat(projection.getMediaId()).isEqualTo(mediaId);
              assertThat(projection.getFolderId()).isEqualTo(folderId);
              assertThat(projection.getState()).isEqualTo(DossierMediaState.READY);
              assertThat(projection.getSourceAggregateVersion()).isEqualTo(3);
            });
    assertThat(
            activities
                .findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
                    cabinId,
                    activeGeneration(),
                    org.springframework.data.domain.Pageable.unpaged()))
        .filteredOn(value -> value.getActivityCode() == DossierActivityCode.MEDIA_READY)
        .singleElement()
        .satisfies(
            activity -> {
              assertThat(activity.getSourceEventId()).isEqualTo(ready.eventId());
              assertThat(activity.getSourceAggregateId()).isEqualTo(mediaId);
              assertThat(activity.getActorSubjectId()).isEqualTo(actorId);
              assertThat(activity.getActorPrincipalType()).isEqualTo("USER");
              assertThat(activity.getCorrelationId()).isEqualTo(correlationId);
            });
    assertThat(outbox.findBySourceEventIdAndCabinId(ready.eventId(), cabinId)).isPresent();
    assertThat(sourceFacts.findByEventId(uploaded.eventId()).orElseThrow().getId())
        .isEqualTo(persistedUploadedFact.getId());
    assertThat(
            sourceFacts.findBySourceTopicAndSourcePartitionAndSourceOffset(
                uploaded.topic(), uploaded.partition(), replayedUploaded.offset()))
        .isEmpty();
    assertThat(deadLetters.findAll())
        .filteredOn(value -> uploaded.eventId().equals(value.getSourceEventId()))
        .singleElement()
        .satisfies(
            value ->
                assertThat(value.getFailureCode())
                    .isEqualTo(DossierDltFailureCode.PROCESSING_FAILED));

    var recovered = mediaCheckpoint(mediaId);
    assertThat(recovered.getAppliedVersion()).isEqualTo(3);
    assertThat(recovered.isBlocked()).isFalse();
    DossierValidatedEvent duplicateUploaded =
        validator.validate(
            uploaded.topic(),
            uploaded.partition(),
            168,
            mediaId.toString(),
            uploaded.canonicalEnvelope().getBytes(StandardCharsets.UTF_8));
    assertThat(processor.process(duplicateUploaded))
        .isEqualTo(DossierInboxProcessor.Outcome.DUPLICATE);
    assertThat(
            activities
                .findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
                    cabinId,
                    activeGeneration(),
                    org.springframework.data.domain.Pageable.unpaged()))
        .filteredOn(value -> value.getActivityCode() == DossierActivityCode.MEDIA_READY)
        .hasSize(1);
  }

  @Test
  void mediaReadyDoesNotInheritAnUnrelatedOrMissingUploadActor() throws Exception {
    UUID cabinId = UUID.randomUUID();
    assertThat(
            processor.process(
                asset(
                    cabinId,
                    WAREHOUSE_A,
                    0,
                    170,
                    "asset.rental-item.created.v1",
                    true)))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);

    UUID mismatchedMediaId = UUID.randomUUID();
    UUID mismatchedFolderId = UUID.randomUUID();
    UUID uploadCorrelation = UUID.randomUUID();
    UUID readyCorrelation = UUID.randomUUID();
    assertThat(
            processor.process(
                cabinMediaFact(
                    mismatchedMediaId,
                    mismatchedFolderId,
                    cabinId,
                    WAREHOUSE_A,
                    2,
                    171,
                    "media.media.uploaded.v1",
                    "PROCESSING",
                    uploadCorrelation,
                    UUID.randomUUID())))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    DossierValidatedEvent mismatchedReady =
        cabinMediaFact(
            mismatchedMediaId,
            mismatchedFolderId,
            cabinId,
            WAREHOUSE_A,
            3,
            172,
            "media.media.ready.v1",
            "READY",
            readyCorrelation,
            null);
    assertThat(processor.process(mismatchedReady))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);

    UUID actorlessMediaId = UUID.randomUUID();
    UUID actorlessFolderId = UUID.randomUUID();
    UUID sharedCorrelation = UUID.randomUUID();
    assertThat(
            processor.process(
                cabinMediaFact(
                    actorlessMediaId,
                    actorlessFolderId,
                    cabinId,
                    WAREHOUSE_A,
                    2,
                    173,
                    "media.media.uploaded.v1",
                    "PROCESSING",
                    sharedCorrelation,
                    null)))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    DossierValidatedEvent actorlessReady =
        cabinMediaFact(
            actorlessMediaId,
            actorlessFolderId,
            cabinId,
            WAREHOUSE_A,
            3,
            174,
            "media.media.ready.v1",
            "READY",
            sharedCorrelation,
            null);
    assertThat(processor.process(actorlessReady))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);

    assertThat(
            activities
                .findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
                    cabinId,
                    activeGeneration(),
                    org.springframework.data.domain.Pageable.unpaged())
                .stream()
                .filter(value -> value.getSourceEventId().equals(mismatchedReady.eventId()))
                .findFirst()
                .orElseThrow()
                .getActorSubjectId())
        .isNull();
    assertThat(
            activities
                .findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
                    cabinId,
                    activeGeneration(),
                    org.springframework.data.domain.Pageable.unpaged())
                .stream()
                .filter(value -> value.getSourceEventId().equals(actorlessReady.eventId()))
                .findFirst()
                .orElseThrow()
                .getActorSubjectId())
        .isNull();
    assertThat(
            new ObjectMapper()
                .readTree(
                    outbox
                        .findBySourceEventIdAndCabinId(mismatchedReady.eventId(), cabinId)
                        .orElseThrow()
                        .getCanonicalPayload())
                .required("actorRef")
                .isNull())
        .isTrue();
    assertThat(
            new ObjectMapper()
                .readTree(
                    outbox
                        .findBySourceEventIdAndCabinId(actorlessReady.eventId(), cabinId)
                        .orElseThrow()
                        .getCanonicalPayload())
                .required("actorRef")
                .isNull())
        .isTrue();
  }

  @Test
  void lateFindingProofTerminalizesDeferredWarehouseConflictsWithoutRejectingTheProof() {
    UUID findingId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    DossierValidatedEvent deferredMedia =
        media(UUID.randomUUID(), findingId, WAREHOUSE_B, 1, 110, "PROCESSING");
    DossierValidatedEvent deferredPublication =
        publication(UUID.randomUUID(), findingId, WAREHOUSE_B, 111);
    DossierValidatedEvent finding = finding(findingId, cabinId, WAREHOUSE_A, 112, 0);

    assertThat(processor.process(deferredMedia))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(processor.process(deferredPublication))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(processor.process(finding)).isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);

    assertThat(inboxes.findById(finding.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.PROCESSED);
    assertThat(inboxes.findById(deferredMedia.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.DLT);
    assertThat(inboxes.findById(deferredPublication.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.DLT);
    assertThat(deadLetters.findAll())
        .filteredOn(value -> value.getFailureCode() == DossierDltFailureCode.EVENT_IDENTITY_CONFLICT)
        .extracting(value -> value.getSourceEventId())
        .contains(deferredMedia.eventId(), deferredPublication.eventId());
    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(cabinId, activeGeneration()))
        .isEmpty();
    assertThat(unlinked.countByGenerationIdAndResolvedAtIsNull(activeGeneration()))
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void mediaLifecycleConflictsAndDeferredLinksRemainDeterministicAcrossReplay() throws Exception {
    UUID cabin = UUID.fromString("38000000-0000-0000-0000-000000000001");
    UUID finding = UUID.fromString("38100000-0000-0000-0000-000000000001");
    UUID rotatedMedia = UUID.fromString("38200000-0000-0000-0000-000000000001");
    UUID failedMedia = UUID.fromString("38200000-0000-0000-0000-000000000002");

    DossierValidatedEvent uploaded =
        mediaFact(
            rotatedMedia, finding, WAREHOUSE_A, 1, 100, "media.media.uploaded.v1", 0, "PROCESSING");
    DossierValidatedEvent ready =
        mediaFact(
            rotatedMedia, finding, WAREHOUSE_A, 2, 101, "media.media.ready.v1", 0, "READY");
    DossierValidatedEvent rotated =
        mediaFact(
            rotatedMedia, finding, WAREHOUSE_A, 3, 102, "media.media.rotated.v1", 1, "READY");
    DossierValidatedEvent deleted =
        mediaFact(
            rotatedMedia, finding, WAREHOUSE_A, 4, 103, "media.media.deleted.v1", 1, "DELETED");
    DossierValidatedEvent stale =
        mediaFact(
            rotatedMedia, finding, WAREHOUSE_A, 2, 104, "media.media.ready.v1", 0, "READY");
    DossierValidatedEvent failedUpload =
        mediaFact(
            failedMedia, finding, WAREHOUSE_A, 1, 110, "media.media.uploaded.v1", 0, "PROCESSING");
    DossierValidatedEvent failed =
        mediaFact(
            failedMedia, finding, WAREHOUSE_A, 2, 111, "media.media.failed.v1", 0, "FAILED");

    for (DossierValidatedEvent event :
        List.of(uploaded, ready, rotated, deleted, failedUpload, failed)) {
      assertThat(processor.process(event)).isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
      assertThat(
              unlinked
                  .findBySourceEventIdAndGenerationId(event.eventId(), activeGeneration())
                  .orElseThrow()
                  .getResolvedAt())
          .isNull();
    }
    assertThat(processor.process(stale)).isEqualTo(DossierInboxProcessor.Outcome.DUPLICATE);

    assertThat(processor.process(finding(finding, cabin, WAREHOUSE_A, 120, 0)))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    UUID sourceGeneration = activeGeneration();
    for (DossierValidatedEvent event :
        List.of(uploaded, ready, rotated, deleted, failedUpload, failed)) {
      assertThat(
              unlinked
                  .findBySourceEventIdAndGenerationId(event.eventId(), sourceGeneration)
                  .orElseThrow()
                  .getResolvedAt())
          .isNotNull();
    }
    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(cabin, sourceGeneration))
        .extracting(
            value -> value.getMediaId() + ":" + value.getMediaGeneration() + ":" + value.getState())
        .containsExactly(
            rotatedMedia + ":1:DELETED",
            failedMedia + ":0:FAILED");
    assertThat(
            activities.findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
                cabin, sourceGeneration, org.springframework.data.domain.Pageable.unpaged()))
        .extracting(DossierActivity::getActivityCode)
        .contains(
            DossierActivityCode.MEDIA_READY,
            DossierActivityCode.MEDIA_ROTATED,
            DossierActivityCode.MEDIA_DELETED,
            DossierActivityCode.MEDIA_FAILED);

    DossierValidatedEvent conflictingGeneration =
        mediaFact(
            rotatedMedia, finding, WAREHOUSE_A, 5, 121, "media.media.rotated.v1", 0, "READY");
    assertThat(processor.process(conflictingGeneration))
        .isEqualTo(DossierInboxProcessor.Outcome.MEDIA_GENERATION_CONFLICT);
    assertThat(inboxes.findById(conflictingGeneration.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.DLT);
    assertThat(deadLetters.findAll())
        .anySatisfy(
            value -> {
              assertThat(value.getSourceEventId()).isEqualTo(conflictingGeneration.eventId());
              assertThat(value.getFailureCode())
                  .isEqualTo(DossierDltFailureCode.MEDIA_GENERATION_CONFLICT);
            });

    DossierReplayTransactions.ReplayClaim claim = replay.start();
    replay.build(claim.runId());
    replay.tailVerifyAndActivate(claim.runId());
    UUID targetGeneration = activeGeneration();

    assertThat(targetGeneration).isNotEqualTo(sourceGeneration);
    assertThat(replayRuns.findById(claim.runId()).orElseThrow().getState())
        .isEqualTo(DossierReplayState.ACTIVATED);
    assertThat(media.findAllByCabinIdAndGenerationIdOrderByMediaIdAsc(cabin, targetGeneration))
        .extracting(
            value -> value.getMediaId() + ":" + value.getMediaGeneration() + ":" + value.getState())
        .containsExactly(
            rotatedMedia + ":1:DELETED",
            failedMedia + ":0:FAILED");
    assertThat(activities.countByGenerationId(targetGeneration))
        .isEqualTo(activities.countByGenerationId(sourceGeneration));

    JsonNode response =
        new ObjectMapper()
            .readTree(
                mvc.perform(
                        get("/api/dossier/v1/cabins/{cabinId}", cabin)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer read-a"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
    assertThat(response.required("activities").toString()).contains("MEDIA_DELETED");
    List<String> visibleMediaIds = new ArrayList<>();
    response
        .required("activities")
        .forEach(
            activity ->
                activity
                    .required("media")
                    .forEach(item -> visibleMediaIds.add(item.required("mediaId").textValue())));
    assertThat(visibleMediaIds).isNotEmpty().containsOnly(failedMedia.toString());
  }

  @Test
  void httpFiltersUseBusinessTimeInclusiveExclusiveBoundsAndComposeWithoutRecordedTimeFallback()
      throws Exception {
    UUID cabin = UUID.fromString("39000000-0000-0000-0000-000000000001");
    UUID actorOne = UUID.fromString("39100000-0000-0000-0000-000000000001");
    UUID actorTwo = UUID.fromString("39100000-0000-0000-0000-000000000002");
    assertThat(
            List.of(
                processor.process(
                    assetAt(
                        cabin,
                        0,
                        200,
                        "asset.rental-item.created.v1",
                        actorOne,
                        "2026-07-18T10:00:00Z",
                        "2026-07-18T12:00:00Z")),
                processor.process(
                    assetAt(
                        cabin,
                        1,
                        201,
                        "asset.rental-item.passport-changed.v1",
                        actorOne,
                        null,
                        "2026-07-18T10:15:00Z")),
                processor.process(
                    estimateAt(
                        UUID.fromString("39200000-0000-0000-0000-000000000001"),
                        cabin,
                        210,
                        actorOne,
                        "2026-07-18T10:30:00Z",
                        "2026-07-18T12:01:00Z")),
                processor.process(
                    findingAt(
                        UUID.fromString("39300000-0000-0000-0000-000000000001"),
                        cabin,
                        220,
                        actorTwo,
                        "2026-07-18T11:00:00Z",
                        "2026-07-18T12:02:00Z"))))
        .containsOnly(DossierInboxProcessor.Outcome.PROCESSED);

    JsonNode filtered =
        new ObjectMapper()
            .readTree(
                mvc.perform(
                        get("/api/dossier/v1/cabins/{cabinId}", cabin)
                            .queryParam("occurredFrom", "2026-07-18T13:00:00+03:00")
                            .queryParam("occurredBefore", "2026-07-18T14:00:00+03:00")
                            .queryParam("sourceType", "ASSET", "MAINTENANCE")
                            .queryParam("actorSubjectId", actorOne.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer read-a"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.activities.length()").value(2))
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
    List<String> activityCodes = new ArrayList<>();
    List<String> actorIds = new ArrayList<>();
    filtered
        .required("activities")
        .forEach(
            activity -> {
              activityCodes.add(activity.required("activityCode").textValue());
              actorIds.add(activity.required("actorRef").required("subjectId").textValue());
            });
    assertThat(activityCodes).containsExactly("ESTIMATE_CREATED", "CABIN_CREATED");
    assertThat(actorIds).containsOnly(actorOne.toString());

    mvc.perform(
            get("/api/dossier/v1/cabins/{cabinId}", cabin)
                .queryParam("occurredFrom", "2026-07-18T10:00:00Z")
                .queryParam("occurredBefore", "2026-07-18T11:00:00Z")
                .queryParam("sourceType", "INVENTORY")
                .header(HttpHeaders.AUTHORIZATION, "Bearer read-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activities").isEmpty());

    mvc.perform(
            get("/api/dossier/v1/cabins/{cabinId}", cabin)
                .queryParam("occurredFrom", "2026-07-18T10:10:00Z")
                .queryParam("occurredBefore", "2026-07-18T10:20:00Z")
                .queryParam("sourceType", "ASSET")
                .queryParam("actorSubjectId", actorOne.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer read-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activities").isEmpty());

    mvc.perform(
            get("/api/dossier/v1/cabins/{cabinId}", cabin)
                .queryParam("occurredFrom", "2026-07-18T11:00:00Z")
                .queryParam("occurredBefore", "2026-07-18T11:00:00Z")
                .header(HttpHeaders.AUTHORIZATION, "Bearer read-a"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DOSSIER_INVALID_FILTER"));
  }

  @Test
  void directSubjectIdentityConflictBlocksAggregateAndAdvancesOnlyAsSanitizedDlt() {
    UUID findingId = UUID.fromString("37000000-0000-0000-0000-000000000001");
    UUID firstCabin = UUID.fromString("37100000-0000-0000-0000-000000000001");
    UUID conflictingCabin = UUID.fromString("37100000-0000-0000-0000-000000000002");
    DossierValidatedEvent first = finding(findingId, firstCabin, WAREHOUSE_A, 80, 0);
    DossierValidatedEvent conflicting = finding(findingId, conflictingCabin, WAREHOUSE_A, 81, 1);

    assertThat(processor.process(first)).isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(processor.process(conflicting))
        .isEqualTo(DossierInboxProcessor.Outcome.EVENT_IDENTITY_CONFLICT);

    var checkpoint = inventoryCheckpoint(findingId);
    assertThat(checkpoint.isBlocked()).isTrue();
    assertThat(checkpoint.getBlockedReason())
        .isEqualTo(DossierAggregateBlockReason.EVENT_IDENTITY_CONFLICT);
    assertThat(checkpoint.getAppliedVersion()).isZero();
    assertThat(inboxes.findById(conflicting.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.DLT);
    assertThat(
            unlinked
                .findBySourceEventIdAndGenerationId(conflicting.eventId(), activeGeneration())
                .orElseThrow()
                .getReason())
        .isEqualTo(
            dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedReason.AGGREGATE_QUARANTINED);
    assertThat(deadLetters.findAll())
        .anySatisfy(
            failure -> {
              assertThat(failure.getSourceEventId()).isEqualTo(conflicting.eventId());
              assertThat(failure.getFailureCode())
                  .isEqualTo(DossierDltFailureCode.EVENT_IDENTITY_CONFLICT);
            });
    assertThat(
            partitions
                .findByConsumerGroupAndSourceTopicAndSourcePartition(
                    "dossier-projection-v1", "rwms.inventory.session.v1", 0)
                .orElseThrow()
                .getLastAcceptedOffset())
        .isEqualTo(81);
  }

  @Test
  void gapDrainKeepsAnchorAppliedWhenTheQuarantinedCandidateHasSubjectConflict() {
    UUID findingId = UUID.fromString("37200000-0000-0000-0000-000000000001");
    UUID provenCabin = UUID.fromString("37300000-0000-0000-0000-000000000001");
    UUID conflictingCabin = UUID.fromString("37300000-0000-0000-0000-000000000002");
    DossierValidatedEvent quarantined = finding(findingId, conflictingCabin, WAREHOUSE_A, 90, 2);
    DossierValidatedEvent versionZero = finding(findingId, provenCabin, WAREHOUSE_A, 91, 0);
    DossierValidatedEvent anchor = finding(findingId, provenCabin, WAREHOUSE_A, 92, 1);

    assertThat(processor.process(quarantined))
        .isEqualTo(DossierInboxProcessor.Outcome.VERSION_GAP);
    assertThat(processor.process(versionZero))
        .isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);
    assertThat(processor.process(anchor)).isEqualTo(DossierInboxProcessor.Outcome.PROCESSED);

    var checkpoint = inventoryCheckpoint(findingId);
    assertThat(checkpoint.getAppliedVersion()).isOne();
    assertThat(checkpoint.isBlocked()).isTrue();
    assertThat(checkpoint.getBlockedReason())
        .isEqualTo(DossierAggregateBlockReason.EVENT_IDENTITY_CONFLICT);
    assertThat(inboxes.findById(anchor.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.PROCESSED);
    assertThat(inboxes.findById(quarantined.eventId()).orElseThrow().getDecision())
        .isEqualTo(DossierInboxDecision.DLT);
    assertThat(deadLetters.findAll())
        .anySatisfy(
            failure -> {
              assertThat(failure.getSourceEventId()).isEqualTo(quarantined.eventId());
              assertThat(failure.getFailureCode())
                  .isEqualTo(DossierDltFailureCode.EVENT_IDENTITY_CONFLICT);
            });
  }

  @Test
  void failedReplayParityNeverMovesTheActivePointer() {
    UUID cabin = UUID.fromString("35000000-0000-0000-0000-000000000001");
    processor.process(asset(cabin, WAREHOUSE_A, 0, 60, "asset.rental-item.created.v1", true));
    UUID sourceGeneration = activeGeneration();
    DossierReplayTransactions.ReplayClaim claim = replay.start();
    replay.build(claim.runId());
    UUID targetGeneration = replayRuns.findById(claim.runId()).orElseThrow().getTargetGenerationId();
    var source = sourceFacts.findAll().getFirst();
    activities.saveAndFlush(
        DossierActivity.project(
            UUID.randomUUID(),
            targetGeneration,
            source.getEventId(),
            UUID.randomUUID(),
            WAREHOUSE_A,
            DossierActivityCode.CABIN_CREATED,
            DossierProducer.ASSET,
            "RENTAL_ITEM",
            UUID.randomUUID(),
            null,
            OffsetDateTime.now(ZoneOffset.UTC),
            OffsetDateTime.now(ZoneOffset.UTC),
            null,
            null,
            null,
            CORRELATION_ID,
            null,
            OffsetDateTime.now(ZoneOffset.UTC)));

    replay.tailVerifyAndActivate(claim.runId());

    assertThat(activeGeneration()).isEqualTo(sourceGeneration);
    assertThat(replayRuns.findById(claim.runId()).orElseThrow().getState())
        .isEqualTo(DossierReplayState.REJECTED);
  }

  @Test
  void httpEnforcesAntiEnumerationRowFilteringPartialAndStableNullLastCursor() throws Exception {
    UUID cabin = UUID.fromString("36000000-0000-0000-0000-000000000001");
    processor.process(asset(cabin, WAREHOUSE_A, 0, 70, "asset.rental-item.created.v1", true));
    processor.process(
        asset(cabin, WAREHOUSE_A, 1, 71, "asset.rental-item.passport-changed.v1", false));
    processor.process(
        asset(cabin, WAREHOUSE_B, 2, 72, "asset.rental-item.status-changed.v1", true));

    mvc.perform(get("/api/dossier/v1/cabins/{cabinId}", cabin))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("DOSSIER_UNAUTHORIZED"))
        .andExpect(jsonPath("$.correlation.correlationId").isNotEmpty());

    mvc.perform(
            get("/api/dossier/v1/cabins/{cabinId}", cabin)
                .header(HttpHeaders.AUTHORIZATION, "Bearer write-a"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("DOSSIER_FORBIDDEN"));

    mvc.perform(
            get("/api/dossier/v1/cabins/{cabinId}", cabin)
                .header(HttpHeaders.AUTHORIZATION, "Bearer read-c"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DOSSIER_NOT_FOUND"));

    String firstPage =
        mvc.perform(
                get("/api/dossier/v1/cabins/{cabinId}", cabin)
                    .queryParam("limit", "1")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer read-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activities.length()").value(1))
            .andExpect(jsonPath("$.activities[0].occurredAt").isNotEmpty())
            .andExpect(jsonPath("$.visibility").value("PARTIAL"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode body = new ObjectMapper().readTree(firstPage);
    String cursor = body.required("nextCursor").textValue();
    assertThat(cursor).isNotBlank();

    mvc.perform(
            get("/api/dossier/v1/cabins/{cabinId}", cabin)
                .queryParam("limit", "1")
                .queryParam("after", cursor)
                .header(HttpHeaders.AUTHORIZATION, "Bearer read-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activities.length()").value(1))
        .andExpect(jsonPath("$.activities[0].occurredAt").isEmpty())
        .andExpect(jsonPath("$.visibility").value("PARTIAL"));

    mvc.perform(
            get("/api/dossier/v1/cabins/{cabinId}", cabin)
                .queryParam("limit", "1")
                .queryParam("after", tamper(cursor))
                .header(HttpHeaders.AUTHORIZATION, "Bearer read-a"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DOSSIER_INVALID_CURSOR"));

    mvc.perform(
            get("/api/dossier/v1/cabins/{cabinId}", cabin)
                .queryParam("limit", "1")
                .queryParam("after", cursor)
                .queryParam("activityCode", "CABIN_CREATED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer read-a"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DOSSIER_INVALID_CURSOR"));

    mvc.perform(
                get("/api/dossier/v1/cabins/{cabinId}", cabin)
                .queryParam("limit", "0")
                .header(HttpHeaders.AUTHORIZATION, "Bearer read-a"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DOSSIER_INVALID_FILTER"));
  }

  private DossierValidatedEvent asset(
      UUID cabin,
      UUID warehouse,
      long version,
      long offset,
      String eventType,
      boolean dated) {
    String payload =
        """
        {"rentalItemId":"%s","warehouseId":"%s","status":"AVAILABLE","numberSha256":"%s"}
        """
            .formatted(cabin, warehouse, "a".repeat(64));
    return validate(
        "rwms.asset.rental-item.v1",
        offset,
        cabin,
        envelope(eventType, "asset-service", "RENTAL_ITEM", cabin, version, payload, dated));
  }

  private DossierValidatedEvent assetComment(
      UUID cabin, long version, long offset, long commentRevision) {
    String payload =
        """
        {"rentalItemId":"%s","commentRevision":%d}
        """
            .formatted(cabin, commentRevision);
    return validate(
        "rwms.asset.rental-item.v1",
        offset,
        cabin,
        envelope(
            "asset.rental-item.general-comment-changed.v1",
            "asset-service",
            "RENTAL_ITEM",
            cabin,
            version,
            payload,
            true));
  }

  private DossierValidatedEvent assetManualNote(
      UUID cabin, long version, long offset, UUID noteId) {
    String payload =
        """
        {"rentalItemId":"%s","noteId":"%s"}
        """
            .formatted(cabin, noteId);
    return validate(
        "rwms.asset.rental-item.v1",
        offset,
        cabin,
        envelope(
            "asset.rental-item.manual-note-added.v1",
            "asset-service",
            "RENTAL_ITEM",
            cabin,
            version,
            payload,
            true));
  }

  private DossierValidatedEvent finding(
      UUID findingId, UUID cabin, UUID warehouse, long offset, long aggregateVersion) {
    String payload =
        """
        {"inventoryId":"%s","findingId":"%s","warehouseId":"%s","sessionRevision":0,"findingRevision":0,"origin":"EXPECTED","inspection":"READY","reconciliation":"MATCHED","assetId":"%s","sourceAttached":true,"mediaCount":1,"planFingerprintSha256":null}
        """
            .formatted(UUID.randomUUID(), findingId, warehouse, cabin);
    return validate(
        "rwms.inventory.session.v1",
        offset,
        findingId,
        envelope(
            aggregateVersion == 0
                ? "inventory.finding.added.v1"
                : "inventory.finding.inspection-saved.v1",
            "inventory-service",
            "FINDING",
            findingId,
            aggregateVersion,
            payload,
            true));
  }

  private DossierValidatedEvent media(
      UUID mediaId,
      UUID findingId,
      UUID warehouse,
      long aggregateVersion,
      long offset,
      String status) {
    String eventType =
        "PROCESSING".equals(status) ? "media.media.uploaded.v1" : "media.media.ready.v1";
    String payload =
        """
        {"mediaId":"%s","ownerType":"INVENTORY_FINDING","ownerId":"%s","warehouseId":"%s","kind":"IMAGE","status":"%s","generation":0,"rotationDegrees":0}
        """
            .formatted(mediaId, findingId, warehouse, status);
    return validate(
        "rwms.media.media.v1",
        offset,
        mediaId,
        envelope(
            eventType,
            "media-service",
            "MEDIA",
            mediaId,
            aggregateVersion,
            payload,
            true));
  }

  private DossierValidatedEvent cabinMedia(
      UUID mediaId,
      UUID cabinId,
      UUID warehouse,
      long aggregateVersion,
      long offset,
      String status) {
    return cabinMedia(
        mediaId, mediaId, cabinId, warehouse, aggregateVersion, offset, status);
  }

  private DossierValidatedEvent cabinMedia(
      UUID mediaId,
      UUID folderId,
      UUID cabinId,
      UUID warehouse,
      long aggregateVersion,
      long offset,
      String status) {
    String eventType =
        "PROCESSING".equals(status) ? "media.media.uploaded.v1" : "media.media.ready.v1";
    String payload =
        """
        {"mediaId":"%s","folderId":"%s","ownerType":"CABIN","ownerId":"%s","warehouseId":"%s","kind":"IMAGE","status":"%s","generation":1,"rotationDegrees":0}
        """
            .formatted(mediaId, folderId, cabinId, warehouse, status);
    return validate(
        "rwms.media.media.v1",
        offset,
        mediaId,
        envelope(
            eventType,
            "media-service",
            "MEDIA",
            mediaId,
            aggregateVersion,
            payload,
            true));
  }

  private DossierValidatedEvent cabinMediaFact(
      UUID mediaId,
      UUID folderId,
      UUID cabinId,
      UUID warehouse,
      long aggregateVersion,
      long offset,
      String eventType,
      String status,
      UUID correlationId,
      UUID actorId) {
    String actorRef =
        actorId == null
            ? "null"
            : """
              {"subjectId":"%s","principalType":"USER","profileRevision":null}
              """
                .formatted(actorId)
                .strip();
    String payload =
        """
        {"mediaId":"%s",%s"ownerType":"CABIN","ownerId":"%s","warehouseId":"%s","kind":"IMAGE","status":"%s","generation":1,"rotationDegrees":0}
        """
            .formatted(
                mediaId,
                folderId == null ? "" : "\"folderId\":\"%s\",".formatted(folderId),
                cabinId,
                warehouse,
                status);
    String envelope =
        """
        {"envelopeVersion":2,"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"2026-07-18T10:00:00Z","recordedAt":"2026-07-18T12:00:00Z","producer":"media-service","aggregateType":"MEDIA","aggregateId":"%s","aggregateVersion":%d,"correlation":{"correlationId":"%s","causationId":null},"actorRef":%s,"payload":%s}
        """
            .formatted(
                UUID.randomUUID(),
                eventType,
                mediaId,
                aggregateVersion,
                correlationId,
                actorRef,
                payload.strip());
    return validate("rwms.media.media.v1", offset, mediaId, envelope);
  }

  private DossierValidatedEvent publication(
      UUID publicationId, UUID findingId, UUID warehouse, long offset) {
    UUID inventoryId = UUID.randomUUID();
    String payload =
        """
        {"inventoryId":"%s","findingId":"%s","publicationIntentId":"%s","warehouseId":"%s","publicationRevision":0,"state":"READY","attemptCount":0,"maintenanceRepairId":null,"failureCode":null,"sourceReference":{"inventoryId":"%s","findingId":"%s","sourceRevision":1,"requestSha256":"%s"}}
        """
            .formatted(
                inventoryId,
                findingId,
                publicationId,
                warehouse,
                inventoryId,
                findingId,
                "c".repeat(64));
    return validate(
        "rwms.inventory.publication.v1",
        offset,
        publicationId,
        envelope(
            "inventory.publication.ready.v1",
            "inventory-service",
            "PUBLICATION",
            publicationId,
            0,
            payload,
            true));
  }

  private DossierValidatedEvent mediaFact(
      UUID mediaId,
      UUID findingId,
      UUID warehouse,
      long aggregateVersion,
      long offset,
      String eventType,
      long generation,
      String status) {
    String payload =
        """
        {"mediaId":"%s","ownerType":"INVENTORY_FINDING","ownerId":"%s","warehouseId":"%s","kind":"IMAGE","status":"%s","generation":%d,"rotationDegrees":0}
        """
            .formatted(mediaId, findingId, warehouse, status, generation);
    return validate(
        "rwms.media.media.v1",
        offset,
        mediaId,
        envelope(
            eventType,
            "media-service",
            "MEDIA",
            mediaId,
            aggregateVersion,
            payload,
            true));
  }

  private DossierValidatedEvent assetAt(
      UUID cabin,
      long version,
      long offset,
      String eventType,
      UUID actor,
      String occurredAt,
      String recordedAt) {
    String payload =
        """
        {"rentalItemId":"%s","warehouseId":"%s","status":"AVAILABLE","numberSha256":"%s"}
        """
            .formatted(cabin, WAREHOUSE_A, "a".repeat(64));
    return validate(
        "rwms.asset.rental-item.v1",
        offset,
        cabin,
        envelopeAt(
            eventType,
            "asset-service",
            "RENTAL_ITEM",
            cabin,
            version,
            payload,
            actor,
            occurredAt,
            recordedAt));
  }

  private DossierValidatedEvent estimateAt(
      UUID estimateId,
      UUID cabin,
      long offset,
      UUID actor,
      String occurredAt,
      String recordedAt) {
    String payload =
        """
        {"estimateId":"%s","warehouseId":"%s","rentalItemId":"%s","lifecycle":"DRAFT","revision":1,"dispatchDate":"2026-07-18","lineCount":0,"completionKind":"NOT_COMPLETED","repairId":null}
        """
            .formatted(estimateId, WAREHOUSE_A, cabin);
    return validate(
        "rwms.maintenance.estimate.v1",
        offset,
        estimateId,
        envelopeAt(
            "maintenance.estimate.created.v1",
            "maintenance-service",
            "ESTIMATE",
            estimateId,
            0,
            payload,
            actor,
            occurredAt,
            recordedAt));
  }

  private DossierValidatedEvent findingAt(
      UUID findingId,
      UUID cabin,
      long offset,
      UUID actor,
      String occurredAt,
      String recordedAt) {
    String payload =
        """
        {"inventoryId":"%s","findingId":"%s","warehouseId":"%s","sessionRevision":0,"findingRevision":0,"origin":"EXPECTED","inspection":"READY","reconciliation":"MATCHED","assetId":"%s","sourceAttached":true,"mediaCount":0,"planFingerprintSha256":null}
        """
            .formatted(UUID.randomUUID(), findingId, WAREHOUSE_A, cabin);
    return validate(
        "rwms.inventory.session.v1",
        offset,
        findingId,
        envelopeAt(
            "inventory.finding.added.v1",
            "inventory-service",
            "FINDING",
            findingId,
            0,
            payload,
            actor,
            occurredAt,
            recordedAt));
  }

  private static String envelopeAt(
      String eventType,
      String producer,
      String aggregateType,
      UUID aggregateId,
      long aggregateVersion,
      String payload,
      UUID actor,
      String occurredAt,
      String recordedAt) {
    String occurred = occurredAt == null ? "null" : "\"" + occurredAt + "\"";
    String actorRef =
        """
        {"subjectId":"%s","principalType":"USER","profileRevision":null}
        """
            .formatted(actor)
            .strip();
    return """
        {"envelopeVersion":2,"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":%s,"recordedAt":"%s","producer":"%s","aggregateType":"%s","aggregateId":"%s","aggregateVersion":%d,"correlation":{"correlationId":"%s","causationId":null},"actorRef":%s,"payload":%s}
        """
        .formatted(
            UUID.randomUUID(),
            eventType,
            occurred,
            recordedAt,
            producer,
            aggregateType,
            aggregateId,
            aggregateVersion,
            CORRELATION_ID,
            actorRef,
            payload.strip());
  }

  private DossierValidatedEvent validate(String topic, long offset, UUID key, String envelope) {
    return validator.validate(
        topic, 0, offset, key.toString(), envelope.getBytes(StandardCharsets.UTF_8));
  }

  private static String envelope(
      String eventType,
      String producer,
      String aggregateType,
      UUID aggregateId,
      long aggregateVersion,
      String payload,
      boolean dated) {
    UUID eventId = UUID.randomUUID();
    String occurredAt = dated ? "\"2026-07-18T10:00:00Z\"" : "null";
    return """
        {"envelopeVersion":2,"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":%s,"recordedAt":"2026-07-18T12:00:00Z","producer":"%s","aggregateType":"%s","aggregateId":"%s","aggregateVersion":%d,"correlation":{"correlationId":"%s","causationId":null},"actorRef":null,"payload":%s}
        """
        .formatted(
            eventId,
            eventType,
            occurredAt,
            producer,
            aggregateType,
            aggregateId,
            aggregateVersion,
            CORRELATION_ID,
            payload.strip());
  }

  private void assertV5CabinHistory(UUID cabin, UUID warehouse, UUID generation) {
    List<DossierActivity> history =
        activities.findAllByCabinIdAndGenerationIdOrderByOccurredAtDescRecordedAtDescSourceEventIdDesc(
            cabin, generation, org.springframework.data.domain.Pageable.unpaged());
    assertThat(history)
        .extracting(DossierActivity::getActivityCode)
        .containsExactlyInAnyOrder(
            DossierActivityCode.CABIN_CREATED,
            DossierActivityCode.CABIN_STATUS_CHANGED,
            DossierActivityCode.CABIN_COMMENT_REVISION_CHANGED);
    assertThat(history).extracting(DossierActivity::getWarehouseId).containsOnly(warehouse);
  }

  private static void createDatabase(String databaseName) throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(true);
      statement.execute("CREATE DATABASE " + quotedIdentifier(databaseName));
    }
  }

  private static void migrateAssetDatabase(String databaseName) {
    Path migrationDirectory =
        Path.of(System.getProperty("rwms.contracts.dir"))
            .getParent()
            .resolve("services/asset-service/src/main/resources/db/migration")
            .toAbsolutePath();
    Flyway.configure()
        .dataSource(
            databaseJdbcUrl(databaseName), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("filesystem:" + migrationDirectory)
        .load()
        .migrate();
  }

  private static List<AssetOutboxWire> readAssetV5Wires(String databaseName) throws Exception {
    List<AssetOutboxWire> wires = new ArrayList<>();
    try (Connection connection =
            DriverManager.getConnection(
                databaseJdbcUrl(databaseName), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT aggregate_id, aggregate_version, event_type, topic, envelope_body::text
                FROM public.outbox_event
                WHERE topic = 'rwms.asset.rental-item.v1'
                  AND aggregate_id IN (?, ?)
                ORDER BY aggregate_id, aggregate_version
                """)) {
      statement.setString(1, V5_SPB_CABIN.toString());
      statement.setString(2, V5_MOSCOW_CABIN.toString());
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          wires.add(
              new AssetOutboxWire(
                  UUID.fromString(result.getString("aggregate_id")),
                  result.getLong("aggregate_version"),
                  result.getString("event_type"),
                  result.getString("topic"),
                  result.getString("envelope_body")));
        }
      }
    }
    return wires;
  }

  private static void assertAssetV5GlobalNumbering(String databaseName) throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            databaseJdbcUrl(databaseName), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      try (Statement statement = connection.createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT count(*), count(DISTINCT display_canonical_number) FROM public.rental_item")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getLong(1)).isEqualTo(195);
        assertThat(result.getLong(2)).isEqualTo(195);
      }
      assertAssetV5WarehouseNumberRange(connection, V5_SPB_WAREHOUSE, 120, 1, 120);
      assertAssetV5WarehouseNumberRange(connection, V5_MOSCOW_WAREHOUSE, 75, 121, 195);
    }
  }

  private static void assertAssetV5WarehouseNumberRange(
      Connection connection, UUID warehouseId, long count, int firstNumber, int lastNumber)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT count(*), min(right(display_canonical_number, 3)::integer),
                   max(right(display_canonical_number, 3)::integer)
            FROM public.rental_item
            WHERE warehouse_id = ?
            """)) {
      statement.setObject(1, warehouseId);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getLong(1)).isEqualTo(count);
        assertThat(result.getInt(2)).isEqualTo(firstNumber);
        assertThat(result.getInt(3)).isEqualTo(lastNumber);
      }
    }
  }

  private static void dropDatabase(String databaseName) throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(true);
      statement.execute("DROP DATABASE " + quotedIdentifier(databaseName) + " WITH (FORCE)");
    }
  }

  private static String databaseJdbcUrl(String databaseName) {
    String jdbcUrl = POSTGRES.getJdbcUrl();
    int queryStart = jdbcUrl.indexOf('?');
    String query = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
    String path = queryStart < 0 ? jdbcUrl : jdbcUrl.substring(0, queryStart);
    return path.substring(0, path.lastIndexOf('/') + 1) + databaseName + query;
  }

  private static String quotedIdentifier(String value) {
    if (!value.matches("[a-z0-9_]{1,63}")) {
      throw new IllegalArgumentException("Invalid PostgreSQL identifier");
    }
    return "\"" + value + "\"";
  }

  private UUID activeGeneration() {
    return activeGenerations
        .findByPointerName(DossierActiveGeneration.POINTER_NAME)
        .orElseThrow()
        .getGenerationId();
  }

  private dev.buhanzaz.rwms.dossier.domain.DossierAggregateCheckpoint inventoryCheckpoint(
      UUID findingId) {
    return aggregates
        .findByConsumerGroupAndProducerAndSourceTopicAndAggregateTypeAndAggregateId(
            "dossier-projection-v1",
            DossierProducer.INVENTORY,
            "rwms.inventory.session.v1",
            "FINDING",
            findingId)
        .orElseThrow();
  }

  private dev.buhanzaz.rwms.dossier.domain.DossierAggregateCheckpoint mediaCheckpoint(
      UUID mediaId) {
    return aggregates
        .findByConsumerGroupAndProducerAndSourceTopicAndAggregateTypeAndAggregateId(
            "dossier-projection-v1",
            DossierProducer.MEDIA,
            "rwms.media.media.v1",
            "MEDIA",
            mediaId)
        .orElseThrow();
  }

  private static String tamper(String cursor) {
    int index = cursor.length() - 1;
    char replacement = cursor.charAt(index) == 'A' ? 'B' : 'A';
    return cursor.substring(0, index) + replacement;
  }

  private record AssetOutboxWire(
      UUID aggregateId, long aggregateVersion, String eventType, String topic, String envelope) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class JwtTestConfiguration {
    @Bean
    @Primary
    JwtDecoder dossierQaJwtDecoder() {
      return token ->
          switch (token) {
            case "read-a" -> jwt(WAREHOUSE_A, "rwms.read");
            case "read-c" -> jwt(WAREHOUSE_C, "rwms.read");
            case "write-a" -> jwt(WAREHOUSE_A, "rwms.write");
            default -> throw new org.springframework.security.oauth2.jwt.JwtException("invalid");
          };
    }

    private static Jwt jwt(UUID warehouseId, String scope) {
      return Jwt.withTokenValue("qa")
          .header("alg", "none")
          .subject(UUID.randomUUID().toString())
          .issuedAt(java.time.Instant.parse("2026-07-18T00:00:00Z"))
          .expiresAt(java.time.Instant.parse("2026-07-19T00:00:00Z"))
          .claim("principal_type", "USER")
          .claim("scope", scope)
          .claim(
              "warehouse_access",
              List.of(Map.of("warehouseId", warehouseId.toString(), "level", "VIEW")))
          .build();
    }
  }
}
