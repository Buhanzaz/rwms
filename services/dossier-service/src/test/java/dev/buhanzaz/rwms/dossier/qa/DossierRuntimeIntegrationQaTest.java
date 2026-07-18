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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

  private static String tamper(String cursor) {
    int index = cursor.length() - 1;
    char replacement = cursor.charAt(index) == 'A' ? 'B' : 'A';
    return cursor.substring(0, index) + replacement;
  }

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
