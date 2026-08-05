package dev.buhanzaz.rwms.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.InventoryServiceApplication;
import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryMediaFactProjection;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.domain.ReconciliationState;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.eventing.InventoryMediaInboxProcessor;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryMediaFactProjectionRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
    classes = InventoryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.inventory.dependencies.enabled=false",
      "spring.cloud.function.definition=",
      "spring.task.scheduling.enabled=false"
    })
@ActiveProfiles("test")
class InventoryInspectionApiContractIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");
  private static final String TOKEN = "inventory-inspection-api-token";
  private static final String ACTOR =
      "{\"subjectId\":\"00000000-0000-0000-0000-000000000701\","
          + "\"principalType\":\"USER\",\"profileRevision\":null}";
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  static {
    POSTGRES.start();
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired InventoryFindingRepository findings;
  @Autowired InventoryMediaFactProjectionRepository mediaFacts;
  @Autowired InventoryEventStore events;
  @Autowired InventoryMediaInboxProcessor mediaInbox;

  @MockitoBean InventoryDependencyGateway dependencies;
  @MockitoBean JwtDecoder jwtDecoder;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @BeforeEach
  void clearRowsAndAuthenticate() {
    jdbc.execute(
        """
        truncate table inventory_session,domain_event,event_stream_head,aggregate_snapshot,
          projection_checkpoint,outbox_event,inventory_media_fact_projection
        restart identity cascade
        """);
    when(jwtDecoder.decode(TOKEN)).thenReturn(jwt());
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @ParameterizedTest(name = "READY title media with {0} equipment snapshot")
  @EnumSource(ObservationPresence.class)
  void acceptsReadyTitleMediaAndPersistsEquipmentSnapshot(ObservationPresence presence)
      throws Exception {
    Fixture fixture = fixture("READY");
    JsonNode equipment = equipmentValue(presence);
    String path = inspectionPath(fixture);

    HttpResponse<String> response =
        request(path, requestBody(fixture, presence, equipment).toString());
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow())
        .startsWith("application/json");
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.required("id").asText()).isEqualTo(fixture.findingId().toString());
    assertThat(body.required("findingRevision").asLong()).isOne();
    assertThat(body.required("inspection").asText()).isEqualTo("READY");
    assertThat(body.required("inspectionSource").asText()).isEqualTo("INVENTORY");
    assertThat(body.required("coverMediaId").asText()).isEqualTo(fixture.mediaId().toString());
    assertThat(body.required("equipmentObservation").required("presence").asText())
        .isEqualTo(presence.name());
    assertThat(body.required("media").get(0).required("mediaId").asText())
        .isEqualTo(fixture.mediaId().toString());
    assertThat(body.required("media").get(0).required("generation").asLong()).isOne();

    JsonNode returnedEquipment = body.required("equipmentObservation").get("value");
    if (presence == ObservationPresence.ABSENT) {
      assertThat(returnedEquipment == null || returnedEquipment.isNull()).isTrue();
    } else {
      assertThat(returnedEquipment).isEqualTo(equipment);
    }
    assertThat(
            jdbc.queryForObject(
                "select equipment_observation_state from inventory_finding where id=?",
                String.class,
                fixture.findingId()))
        .isEqualTo(presence.name());
    assertThat(
            jdbc.queryForObject(
                "select cover_media_id from inventory_finding where id=?",
                UUID.class,
                fixture.findingId()))
        .isEqualTo(fixture.mediaId());
    String storedEquipment =
        jdbc.queryForObject(
            "select equipment_observation::text from inventory_finding where id=?",
            String.class,
            fixture.findingId());
    if (presence == ObservationPresence.ABSENT) {
      assertThat(storedEquipment).isNull();
    } else {
      assertThat(mapper.readTree(storedEquipment)).isEqualTo(equipment);
    }
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from finding_media_reference
                 where finding_id=? and finding_revision=1 and media_id=? and generation=1
                   and media_kind='IMAGE' and media_status='READY'
                """,
                Integer.class,
                fixture.findingId(),
                fixture.mediaId()))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from domain_event
                 where aggregate_type='FINDING' and aggregate_id=?
                   and event_type='inventory.finding.inspection-saved.v1'
                """,
                Integer.class,
                fixture.findingId().toString()))
        .isOne();

    jdbc.update(
        "update inventory_finding set cover_media_id=null where id=?",
        fixture.findingId());
    HttpResponse<String> legacyRead =
        get("/api/inventory/v1/sessions/" + fixture.inventoryId() + "/findings");
    assertThat(legacyRead.statusCode()).withFailMessage(legacyRead.body()).isEqualTo(200);
    assertThat(
            mapper
                .readTree(legacyRead.body())
                .required("content")
                .get(0)
                .required("coverMediaId")
                .asText())
        .isEqualTo(fixture.mediaId().toString());
  }

  @Test
  void rejectsMissingOrDetachedTitleMediaWithoutMutatingInspection() throws Exception {
    Fixture fixture = fixture("READY");
    ObjectNode missing =
        requestBody(fixture, ObservationPresence.EXPLICIT_EMPTY, mapper.createArrayNode());
    missing.putNull("coverMediaId");

    HttpResponse<String> missingResponse =
        request(inspectionPath(fixture), missing.toString());
    assertThat(missingResponse.statusCode()).isEqualTo(422);
    assertThat(mapper.readTree(missingResponse.body()).required("detail").asText())
        .isEqualTo("Выберите титульную фотографию");

    ObjectNode detached =
        requestBody(fixture, ObservationPresence.EXPLICIT_EMPTY, mapper.createArrayNode());
    detached.put("coverMediaId", UUID.randomUUID().toString());
    HttpResponse<String> detachedResponse =
        request(inspectionPath(fixture), detached.toString());
    assertThat(detachedResponse.statusCode()).isEqualTo(422);
    assertThat(mapper.readTree(detachedResponse.body()).required("detail").asText())
        .isEqualTo("Титульная фотография отсутствует среди фотографий проверки");

    assertThat(
            jdbc.queryForMap(
                "select finding_revision,inspection,cover_media_id from inventory_finding where id=?",
                fixture.findingId()))
        .containsEntry("finding_revision", 0L)
        .containsEntry("inspection", "NOT_INSPECTED")
        .containsEntry("cover_media_id", null);
  }

  @Test
  void previewExposesRegistryConflictBeforeFurnitureSnapshotStalenessAndResolutionReturnsToCabins()
      throws Exception {
    Fixture fixture = fixture("READY");
    HttpResponse<String> inspection =
        request(
            inspectionPath(fixture),
            requestBody(fixture, ObservationPresence.EXPLICIT_EMPTY, mapper.createArrayNode())
                .toString());
    assertThat(inspection.statusCode()).withFailMessage(inspection.body()).isEqualTo(200);
    long findingRevision = mapper.readTree(inspection.body()).required("findingRevision").asLong();

    String snapshotSha256 = "3".repeat(64);
    when(dependencies.furnitureSnapshot(fixture.warehouseId(), List.of(fixture.assetId())))
        .thenReturn(
            new InventoryDependencyGateway.FurnitureSnapshot(
                fixture.warehouseId(),
                snapshotSha256,
                List.of()));
    ObjectNode start = mapper.createObjectNode();
    start.put("expectedSessionRevision", 0);
    start.put("acknowledgeIncomplete", false);
    start.putArray("findingRevisions")
        .addObject()
        .put("findingId", fixture.findingId().toString())
        .put("expectedFindingRevision", findingRevision);
    HttpResponse<String> started =
        post(
            "/api/inventory/v1/sessions/"
                + fixture.inventoryId()
                + "/furniture-review/start",
            start.toString());
    assertThat(started.statusCode()).withFailMessage(started.body()).isEqualTo(200);
    long furnitureSessionRevision =
        mapper.readTree(started.body()).required("sessionRevision").asLong();

    ObjectNode save = mapper.createObjectNode();
    save.put("expectedSessionRevision", furnitureSessionRevision);
    save.put("assetSnapshotSha256", snapshotSha256);
    save.putArray("items");
    HttpResponse<String> reviewed =
        request(
            "/api/inventory/v1/sessions/" + fixture.inventoryId() + "/furniture-review",
            save.toString());
    assertThat(reviewed.statusCode()).withFailMessage(reviewed.body()).isEqualTo(200);
    long reviewedSessionRevision =
        mapper.readTree(reviewed.body()).required("sessionRevision").asLong();
    assertThat(
            jdbc.queryForObject(
                "select equipment_observation_state from inventory_finding where id=?",
                String.class,
                fixture.findingId()))
        .isEqualTo("EXPLICIT_EMPTY");
    assertThat(
            jdbc.queryForObject(
                "select equipment_observation::text from inventory_finding where id=?",
                String.class,
                fixture.findingId()))
        .isEqualTo("[]");

    when(dependencies.validateAssets(List.of(fixture.assetId())))
        .thenReturn(
            changedValidation(fixture.assetId(), fixture.warehouseId(), "БЫТ-API", "БЫТAPI"));
    when(dependencies.furnitureSnapshot(fixture.warehouseId(), List.of(fixture.assetId())))
        .thenReturn(
            new InventoryDependencyGateway.FurnitureSnapshot(
                fixture.warehouseId(), "8".repeat(64), List.of()));
    long reviewedFindingRevision =
        jdbc.queryForObject(
            "select finding_revision from inventory_finding where id=?",
            Long.class,
            fixture.findingId());
    FinalPlanFixture finalPlan = prepareFinalPlan(fixture, reviewedSessionRevision);
    ObjectNode preview = mapper.createObjectNode();
    preview.put("expectedSessionRevision", reviewedSessionRevision);
    preview.put("finalPlanVersion", finalPlan.version());
    preview.put("finalPlanSha256", finalPlan.sha256());
    preview
        .putArray("findingRevisions")
        .addObject()
        .put("findingId", fixture.findingId().toString())
        .put("expectedFindingRevision", reviewedFindingRevision);
    HttpResponse<String> previewResponse =
        post(
            "/api/inventory/v1/sessions/"
                + fixture.inventoryId()
                + "/completion-preview",
            preview.toString());
    assertThat(previewResponse.statusCode()).withFailMessage(previewResponse.body()).isEqualTo(200);
    assertThat(mapper.readTree(previewResponse.body()).required("risks").toString())
        .contains("\"code\":\"CONFLICT\"");

    ObjectNode resolve = mapper.createObjectNode();
    resolve.put("expectedSessionRevision", reviewedSessionRevision);
    resolve.put("expectedFindingRevision", reviewedFindingRevision);
    resolve.put("strategy", "KEEP_INSPECTION");
    resolve.put("reason", "Реестр обновился после сверки мебели");
    HttpResponse<String> resolved =
        request(
            "/api/inventory/v1/sessions/"
                + fixture.inventoryId()
                + "/findings/"
                + fixture.findingId()
                + "/conflict-resolution",
            resolve.toString());
    assertThat(resolved.statusCode()).withFailMessage(resolved.body()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "select review_stage from inventory_session where id=?",
                String.class,
                fixture.inventoryId()))
        .isEqualTo("CABINS");
    assertThat(
            jdbc.queryForObject(
                "select furniture_asset_snapshot_sha256 from inventory_session where id=?",
                String.class,
                fixture.inventoryId()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select furniture_review_sha256 from inventory_session where id=?",
                String.class,
                fixture.inventoryId()))
        .isNull();
  }

  @Test
  void confirmedFurnitureReviewCannotBeRestartedWithAnotherIdempotencyKey() throws Exception {
    Fixture fixture = fixture("READY");
    ReviewFixture review = confirmEmptyFurnitureReview(fixture, "9".repeat(64));
    String confirmedReviewSha256 =
        jdbc.queryForObject(
            "select furniture_review_sha256 from inventory_session where id=?",
            String.class,
            fixture.inventoryId());
    ObjectNode restart = mapper.createObjectNode();
    restart.put("expectedSessionRevision", review.sessionRevision());
    restart.put("acknowledgeIncomplete", false);
    restart
        .putArray("findingRevisions")
        .addObject()
        .put("findingId", fixture.findingId().toString())
        .put("expectedFindingRevision", review.findingRevision());

    HttpResponse<String> response =
        post(
            "/api/inventory/v1/sessions/"
                + fixture.inventoryId()
                + "/furniture-review/start",
            restart.toString());

    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(409);
    assertThat(mapper.readTree(response.body()).required("detail").asText())
        .isEqualTo("Furniture review is already confirmed");
    assertThat(
            jdbc.queryForObject(
                "select furniture_review_sha256 from inventory_session where id=?",
                String.class,
                fixture.inventoryId()))
        .isEqualTo(confirmedReviewSha256);
  }

  @Test
  void acceptedTransferredCabinIsExcludedFromFurnitureSnapshotScope() throws Exception {
    Fixture fixture = fixture("READY");
    HttpResponse<String> inspection =
        request(
            inspectionPath(fixture),
            requestBody(fixture, ObservationPresence.EXPLICIT_EMPTY, mapper.createArrayNode())
                .toString());
    assertThat(inspection.statusCode()).withFailMessage(inspection.body()).isEqualTo(200);
    long inspectedFindingRevision =
        mapper.readTree(inspection.body()).required("findingRevision").asLong();
    UUID destinationWarehouseId = UUID.randomUUID();
    when(dependencies.validateAssets(List.of(fixture.assetId())))
        .thenReturn(
            statusValidation(
                fixture.assetId(), destinationWarehouseId, "IN_TRANSFER", "БК-1"));
    ObjectNode acceptRegistry = mapper.createObjectNode();
    acceptRegistry.put("expectedSessionRevision", 0);
    acceptRegistry.put("expectedFindingRevision", inspectedFindingRevision);
    acceptRegistry.put("strategy", "ACCEPT_REGISTRY");
    acceptRegistry.putNull("reason");
    HttpResponse<String> accepted =
        request(
            "/api/inventory/v1/sessions/"
                + fixture.inventoryId()
                + "/findings/"
                + fixture.findingId()
                + "/conflict-resolution",
            acceptRegistry.toString());
    assertThat(accepted.statusCode()).withFailMessage(accepted.body()).isEqualTo(200);
    long acceptedFindingRevision =
        mapper.readTree(accepted.body()).required("findingRevision").asLong();

    String emptySnapshotSha256 = "a".repeat(64);
    when(dependencies.furnitureSnapshot(fixture.warehouseId(), List.of()))
        .thenReturn(
            new InventoryDependencyGateway.FurnitureSnapshot(
                fixture.warehouseId(), emptySnapshotSha256, List.of()));
    ObjectNode start = mapper.createObjectNode();
    start.put("expectedSessionRevision", 0);
    start.put("acknowledgeIncomplete", false);
    start
        .putArray("findingRevisions")
        .addObject()
        .put("findingId", fixture.findingId().toString())
        .put("expectedFindingRevision", acceptedFindingRevision);
    HttpResponse<String> started =
        post(
            "/api/inventory/v1/sessions/"
                + fixture.inventoryId()
                + "/furniture-review/start",
            start.toString());

    assertThat(started.statusCode()).withFailMessage(started.body()).isEqualTo(200);
    assertThat(mapper.readTree(started.body()).required("items").isEmpty()).isTrue();
    verify(dependencies).furnitureSnapshot(fixture.warehouseId(), List.of());
  }

  @Test
  void savingCabinInspectionDuringFurnitureReviewReturnsSessionToCabins() throws Exception {
    Fixture fixture = fixture("READY");
    ReviewFixture review = confirmEmptyFurnitureReview(fixture, "7".repeat(64));
    ObjectNode update =
        requestBody(fixture, ObservationPresence.EXPLICIT_EMPTY, mapper.createArrayNode());
    update.put("expectedSessionRevision", review.sessionRevision());
    update.put("expectedFindingRevision", review.findingRevision());
    update.put("comment", "Дополнен осмотр после сверки мебели");

    HttpResponse<String> response = request(inspectionPath(fixture), update.toString());

    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    assertThat(mapper.readTree(response.body()).required("findingRevision").asLong())
        .isEqualTo(review.findingRevision() + 1);
    assertThat(
            jdbc.queryForObject(
                "select review_stage from inventory_session where id=?",
                String.class,
                fixture.inventoryId()))
        .isEqualTo("CABINS");
    assertThat(
            jdbc.queryForObject(
                "select furniture_asset_snapshot_sha256 from inventory_session where id=?",
                String.class,
                fixture.inventoryId()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select furniture_review_sha256 from inventory_session where id=?",
                String.class,
                fixture.inventoryId()))
        .isNull();
  }

  @Test
  void rejectsFurnitureSnapshotThatOmitsASelectedCabin() throws Exception {
    Fixture fixture = fixture("READY");
    UUID equipmentId = UUID.randomUUID();
    when(dependencies.furnitureSnapshot(fixture.warehouseId(), List.of(fixture.assetId())))
        .thenReturn(
            new InventoryDependencyGateway.FurnitureSnapshot(
                fixture.warehouseId(),
                "5".repeat(64),
                List.of(
                    new InventoryDependencyGateway.FurnitureSnapshotItem(
                        equipmentId, 1L, "Стол", 0L, 0L, List.of()))));
    ObjectNode start = mapper.createObjectNode();
    start.put("expectedSessionRevision", 0);
    start.put("acknowledgeIncomplete", true);
    start.putArray("findingRevisions")
        .addObject()
        .put("findingId", fixture.findingId().toString())
        .put("expectedFindingRevision", 0);

    HttpResponse<String> response =
        post(
            "/api/inventory/v1/sessions/"
                + fixture.inventoryId()
                + "/furniture-review/start",
            start.toString());

    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(503);
    assertThat(mapper.readTree(response.body()).required("code").asText())
        .isEqualTo("INVENTORY_DEPENDENCY_UNAVAILABLE");
    assertThat(
            jdbc.queryForObject(
                "select review_stage from inventory_session where id=?",
                String.class,
                fixture.inventoryId()))
        .isEqualTo("CABINS");
  }

  @Test
  void emptyConfirmedFurnitureReviewCompletesWithoutReconciliationIntent() throws Exception {
    Fixture fixture = fixture("READY");
    ReviewFixture review = confirmEmptyFurnitureReview(fixture, "6".repeat(64));
    FinalPlanFixture finalPlan = prepareFinalPlan(fixture, review.sessionRevision());
    ObjectNode previewRequest = mapper.createObjectNode();
    previewRequest.put("expectedSessionRevision", review.sessionRevision());
    previewRequest.put("finalPlanVersion", finalPlan.version());
    previewRequest.put("finalPlanSha256", finalPlan.sha256());
    previewRequest
        .putArray("findingRevisions")
        .addObject()
        .put("findingId", fixture.findingId().toString())
        .put("expectedFindingRevision", review.findingRevision());
    HttpResponse<String> preview =
        post(
            "/api/inventory/v1/sessions/"
                + fixture.inventoryId()
                + "/completion-preview",
            previewRequest.toString());
    assertThat(preview.statusCode()).withFailMessage(preview.body()).isEqualTo(200);
    JsonNode previewBody = mapper.readTree(preview.body());

    ObjectNode completeRequest = mapper.createObjectNode();
    completeRequest.put("expectedSessionRevision", review.sessionRevision());
    completeRequest.put("finalPlanVersion", previewBody.required("finalPlanVersion").asLong());
    completeRequest.put("finalPlanSha256", previewBody.required("finalPlanSha256").asText());
    completeRequest.put("acknowledgementSha256", previewBody.required("acknowledgementSha256").asText());
    completeRequest.put("validationSha256", previewBody.required("validationSha256").asText());
    completeRequest
        .putArray("findingRevisions")
        .addObject()
        .put("findingId", fixture.findingId().toString())
        .put("expectedFindingRevision", review.findingRevision());
    HttpResponse<String> completed =
        post(
            "/api/inventory/v1/sessions/" + fixture.inventoryId() + "/complete",
            completeRequest.toString());

    assertThat(completed.statusCode()).withFailMessage(completed.body()).isEqualTo(200);
    assertThat(
            mapper
                .readTree(completed.body())
                .required("furnitureReconciliationState")
                .asText())
        .isEqualTo("NOT_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_furniture_reconciliation_intent where inventory_id=?",
                Integer.class,
                fixture.inventoryId()))
        .isZero();
    verify(dependencies, never()).reconcileFurniture(any(), any(), any());
  }

  @Test
  void furnitureShortageCreatesLossDecisionAndDoesNotDirectlyDecreaseStock() throws Exception {
    Fixture fixture = fixture("READY");
    UUID equipmentId = UUID.randomUUID();
    String snapshotSha256 = "7".repeat(64);
    InventoryDependencyGateway.FurnitureSnapshot snapshot =
        new InventoryDependencyGateway.FurnitureSnapshot(
            fixture.warehouseId(),
            snapshotSha256,
            List.of(
                new InventoryDependencyGateway.FurnitureSnapshotItem(
                    equipmentId,
                    4,
                    "Стул",
                    5,
                    9L,
                    List.of(
                        new InventoryDependencyGateway.FurnitureSnapshotCabin(
                            fixture.assetId(), 7, "БЫТ-API", "WAREHOUSE", 0)))));
    when(dependencies.furnitureSnapshot(fixture.warehouseId(), List.of(fixture.assetId())))
        .thenReturn(snapshot);
    UUID decisionId = UUID.randomUUID();
    when(dependencies.createInventoryLossDisposition(any(), any()))
        .thenAnswer(
            invocation -> {
              InventoryDependencyGateway.InventoryLossDispositionRequest request =
                  invocation.getArgument(1);
              return new InventoryDependencyGateway.InventoryLossDisposition(
                  decisionId,
                  request.inventorySessionId(),
                  request.findingId(),
                  request.warehouseId(),
                  request.equipmentId(),
                  "LOSS",
                  "PENDING_APPROVAL");
            });

    HttpResponse<String> inspection =
        request(
            inspectionPath(fixture),
            requestBody(fixture, ObservationPresence.EXPLICIT_EMPTY, mapper.createArrayNode())
                .toString());
    assertThat(inspection.statusCode()).withFailMessage(inspection.body()).isEqualTo(200);
    long inspectionFindingRevision =
        mapper.readTree(inspection.body()).required("findingRevision").asLong();

    ObjectNode start = mapper.createObjectNode();
    start.put("expectedSessionRevision", 0);
    start.put("acknowledgeIncomplete", false);
    start
        .putArray("findingRevisions")
        .addObject()
        .put("findingId", fixture.findingId().toString())
        .put("expectedFindingRevision", inspectionFindingRevision);
    HttpResponse<String> started =
        post(
            "/api/inventory/v1/sessions/"
                + fixture.inventoryId()
                + "/furniture-review/start",
            start.toString());
    assertThat(started.statusCode()).withFailMessage(started.body()).isEqualTo(200);

    ObjectNode save = mapper.createObjectNode();
    save.put(
        "expectedSessionRevision",
        mapper.readTree(started.body()).required("sessionRevision").asLong());
    save.put("assetSnapshotSha256", snapshotSha256);
    ObjectNode item = save.putArray("items").addObject();
    item.put("equipmentId", equipmentId.toString());
    item.put("catalogVersion", 4);
    item.put("observedStockQuantity", 3);
    item
        .putArray("cabins")
        .addObject()
        .put("findingId", fixture.findingId().toString())
        .put("expectedFindingRevision", inspectionFindingRevision)
        .put("observedQuantity", 0);
    HttpResponse<String> reviewed =
        request(
            "/api/inventory/v1/sessions/" + fixture.inventoryId() + "/furniture-review",
            save.toString());
    assertThat(reviewed.statusCode()).withFailMessage(reviewed.body()).isEqualTo(200);
    long reviewedSessionRevision =
        mapper.readTree(reviewed.body()).required("sessionRevision").asLong();
    long reviewedFindingRevision =
        jdbc.queryForObject(
            "select finding_revision from inventory_finding where id=?",
            Long.class,
            fixture.findingId());
    FinalPlanFixture finalPlan = prepareFinalPlan(fixture, reviewedSessionRevision);

    ObjectNode previewRequest = mapper.createObjectNode();
    previewRequest.put("expectedSessionRevision", reviewedSessionRevision);
    previewRequest.put("finalPlanVersion", finalPlan.version());
    previewRequest.put("finalPlanSha256", finalPlan.sha256());
    previewRequest
        .putArray("findingRevisions")
        .addObject()
        .put("findingId", fixture.findingId().toString())
        .put("expectedFindingRevision", reviewedFindingRevision);
    HttpResponse<String> preview =
        post(
            "/api/inventory/v1/sessions/"
                + fixture.inventoryId()
                + "/completion-preview",
            previewRequest.toString());
    assertThat(preview.statusCode()).withFailMessage(preview.body()).isEqualTo(200);
    JsonNode previewBody = mapper.readTree(preview.body());

    ObjectNode completeRequest = mapper.createObjectNode();
    completeRequest.put("expectedSessionRevision", reviewedSessionRevision);
    completeRequest.put("finalPlanVersion", finalPlan.version());
    completeRequest.put("finalPlanSha256", finalPlan.sha256());
    completeRequest.put(
        "acknowledgementSha256", previewBody.required("acknowledgementSha256").asText());
    completeRequest.put("validationSha256", previewBody.required("validationSha256").asText());
    completeRequest
        .putArray("findingRevisions")
        .addObject()
        .put("findingId", fixture.findingId().toString())
        .put("expectedFindingRevision", reviewedFindingRevision);
    HttpResponse<String> completed =
        post(
            "/api/inventory/v1/sessions/" + fixture.inventoryId() + "/complete",
            completeRequest.toString());

    assertThat(completed.statusCode()).withFailMessage(completed.body()).isEqualTo(200);
    verify(dependencies)
        .createInventoryLossDisposition(
            any(),
            argThat(
                request ->
                    request.inventorySessionId().equals(fixture.inventoryId())
                        && request.equipmentId().equals(equipmentId)
                        && request.expectedAssetVersion() == 4
                        && request.expectedSourceBalanceVersion() == 9
                        && request.quantity() == 2));
    verify(dependencies)
        .reconcileFurniture(
            org.mockito.ArgumentMatchers.eq(fixture.inventoryId()),
            any(),
            argThat(
                request ->
                    request.items().size() == 1
                        && request.items().getFirst().stockQuantity() == 5));
    assertThat(
            jdbc.queryForObject(
                "select state from inventory_furniture_loss_intent where inventory_id=? and equipment_id=?",
                String.class,
                fixture.inventoryId(),
                equipmentId))
        .isEqualTo("SUCCEEDED");
    assertThat(
            jdbc.queryForObject(
                "select decision_id from inventory_furniture_loss_intent where inventory_id=? and equipment_id=?",
                UUID.class,
                fixture.inventoryId(),
                equipmentId))
        .isEqualTo(decisionId);
  }

  @ParameterizedTest(name = "{0} title media returns canonical Problem Details")
  @EnumSource(value = NonReadyMediaStatus.class)
  void rejectsNonReadyTitleMediaWithoutMutatingInspection(NonReadyMediaStatus mediaStatus)
      throws Exception {
    Fixture fixture = fixture(mediaStatus.name());
    String path = inspectionPath(fixture);

    HttpResponse<String> response =
        request(
            path,
            requestBody(
                    fixture, ObservationPresence.EXPLICIT_EMPTY, mapper.createArrayNode())
                .toString());
    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow())
        .startsWith("application/problem+json");
    JsonNode problem = mapper.readTree(response.body());
    assertThat(problem.required("type").asText())
        .isEqualTo("urn:rwms:problem:inventory_media_not_ready");
    assertThat(problem.required("title").asText()).isEqualTo("Unprocessable Entity");
    assertThat(problem.required("status").asInt()).isEqualTo(422);
    assertThat(problem.required("detail").asText())
        .isEqualTo("Referenced media generation is not READY for this finding");
    assertThat(problem.required("instance").asText()).isEqualTo(path);
    assertThat(problem.required("code").asText()).isEqualTo("INVENTORY_MEDIA_NOT_READY");
    assertThat(problem.required("violations").isArray()).isTrue();
    assertThat(problem.required("violations").isEmpty()).isTrue();
    assertThat(problem.required("correlation").required("correlationId").asText()).isNotBlank();
    assertThat(problem.required("correlation").get("causationId").isNull()).isTrue();

    assertThat(
            jdbc.queryForMap(
                "select finding_revision,inspection from inventory_finding where id=?",
                fixture.findingId()))
        .containsEntry("finding_revision", 0L)
        .containsEntry("inspection", "NOT_INSPECTED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from finding_media_reference where finding_id=?",
                Integer.class,
                fixture.findingId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from domain_event
                 where aggregate_type='FINDING' and aggregate_id=?
                   and event_type='inventory.finding.inspection-saved.v1'
                """,
                Integer.class,
                fixture.findingId().toString()))
        .isZero();
  }

  @Test
  void acceptsReadyInventoryImageAfterTheCanonicalMediaFactReachesTheProjection()
      throws Exception {
    Fixture fixture = fixtureWithoutMediaProjection();

    // This is the real public shape emitted by media-service after the private create/finalize
    // commands: folderId is present and the public READY snapshot has advanced beyond version 1.
    mediaInbox.initial(
        readyMediaFact(fixture),
        fixture.mediaId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertThat(mediaFacts.findByMediaIdAndGeneration(fixture.mediaId(), 1L))
        .hasValueSatisfying(
            fact -> {
              assertThat(fact.getMediaStatus()).isEqualTo("READY");
              assertThat(fact.getAggregateVersion()).isEqualTo(3L);
            });

    HttpResponse<String> response =
        request(
            inspectionPath(fixture),
            requestBody(
                    fixture, ObservationPresence.EXPLICIT_EMPTY, mapper.createArrayNode())
                .toString());

    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.required("inspection").asText()).isEqualTo("READY");
    assertThat(body.required("media").get(0).required("mediaId").asText())
        .isEqualTo(fixture.mediaId().toString());
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from finding_media_reference
                 where finding_id=? and media_id=? and generation=1 and media_status='READY'
                """,
                Integer.class,
                fixture.findingId(),
                fixture.mediaId()))
        .isOne();
  }

  private Fixture fixture(String mediaStatus) {
    return fixture(mediaStatus, true);
  }

  private Fixture fixtureWithoutMediaProjection() {
    return fixture("READY", false);
  }

  private Fixture fixture(String mediaStatus, boolean seedMediaProjection) {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID startOperationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_session(
          id,session_revision,warehouse_id,warehouse_version_snapshot,warehouse_time_zone,
          business_date,lifecycle,start_operation_id,start_idempotency_key,start_request_sha256,
          expected_population_count,expected_population_sha256,started_by_subject_id,
          started_by_display_name,started_actor_ref,started_at,created_at,updated_at)
        values (?,0,?,0,'Europe/Moscow',current_date,'ACTIVE',?,?,?,0,?,?,'API operator',
          ?::jsonb,?,?,?)
        """,
        inventoryId,
        warehouseId,
        startOperationId,
        startOperationId,
        "0".repeat(64),
        "1".repeat(64),
        UUID.fromString("00000000-0000-0000-0000-000000000701"),
        ACTOR,
        now,
        now,
        now);
    events.initialize(
        "SESSION",
        inventoryId,
        "inventory.session.started.v1",
        "rwms.inventory.session.v1",
        mapper.createObjectNode().put("inventoryId", inventoryId.toString()),
        UUID.randomUUID(),
        null,
        null);

    InventoryFinding finding =
        findings.saveAndFlush(
            InventoryFinding.unexpected(
                inventoryId,
                FindingOrigin.UNEXPECTED_EXISTING,
                assetId,
                7L,
                warehouseId,
                "WAREHOUSE",
                null,
                "БЫТ-API",
                "БЫТAPI",
                ReconciliationState.MATCHED,
                ACTOR));
    events.initialize(
        "FINDING",
        finding.getId(),
        "inventory.finding.added.v1",
        "rwms.inventory.session.v1",
        mapper.createObjectNode().put("origin", "UNEXPECTED_EXISTING"),
        UUID.randomUUID(),
        null,
        null);
    if (seedMediaProjection) {
      mediaFacts.saveAndFlush(
          InventoryMediaFactProjection.create(
              mediaId,
              1,
              2,
              finding.getId(),
              warehouseId,
              "IMAGE",
              mediaStatus,
              0));
    }

    ObjectNode passport = mapper.createObjectNode().put("type", "БК-1");
    ArrayNode contents = mapper.createArrayNode();
    InventoryDependencyGateway.ValidationItem current =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            7L,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-API",
            "БЫТAPI",
            null,
            passport,
            contents);
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC), "2".repeat(64), List.of(current)));
    when(dependencies.repairSnapshots(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.RepairSnapshots(
                List.of(
                    new InventoryDependencyGateway.RepairAssetSnapshot(assetId, List.of()))));
    return new Fixture(inventoryId, finding.getId(), mediaId, warehouseId, assetId);
  }

  private InventoryDependencyGateway.Validation changedValidation(
      UUID assetId, UUID warehouseId, String number, String matchKey) {
    return statusValidation(assetId, warehouseId, "WAREHOUSE", "БК-2", number, matchKey);
  }

  private InventoryDependencyGateway.Validation statusValidation(
      UUID assetId, UUID warehouseId, String status, String passportType) {
    return statusValidation(assetId, warehouseId, status, passportType, "БЫТ-API", "БЫТAPI");
  }

  private InventoryDependencyGateway.Validation statusValidation(
      UUID assetId,
      UUID warehouseId,
      String status,
      String passportType,
      String number,
      String matchKey) {
    return new InventoryDependencyGateway.Validation(
        OffsetDateTime.now(ZoneOffset.UTC),
        "4".repeat(64),
        List.of(
            new InventoryDependencyGateway.ValidationItem(
                assetId,
                true,
                8L,
                warehouseId,
                status,
                number,
                matchKey,
                null,
                mapper.createObjectNode().put("type", passportType),
                mapper.createArrayNode())));
  }

  private ReviewFixture confirmEmptyFurnitureReview(Fixture fixture, String snapshotSha256)
      throws Exception {
    HttpResponse<String> inspection =
        request(
            inspectionPath(fixture),
            requestBody(fixture, ObservationPresence.EXPLICIT_EMPTY, mapper.createArrayNode())
                .toString());
    assertThat(inspection.statusCode()).withFailMessage(inspection.body()).isEqualTo(200);
    long inspectionFindingRevision =
        mapper.readTree(inspection.body()).required("findingRevision").asLong();
    when(dependencies.furnitureSnapshot(fixture.warehouseId(), List.of(fixture.assetId())))
        .thenReturn(
            new InventoryDependencyGateway.FurnitureSnapshot(
                fixture.warehouseId(), snapshotSha256, List.of()));
    ObjectNode start = mapper.createObjectNode();
    start.put("expectedSessionRevision", 0);
    start.put("acknowledgeIncomplete", false);
    start.putArray("findingRevisions")
        .addObject()
        .put("findingId", fixture.findingId().toString())
        .put("expectedFindingRevision", inspectionFindingRevision);
    HttpResponse<String> started =
        post(
            "/api/inventory/v1/sessions/"
                + fixture.inventoryId()
                + "/furniture-review/start",
            start.toString());
    assertThat(started.statusCode()).withFailMessage(started.body()).isEqualTo(200);
    long furnitureSessionRevision =
        mapper.readTree(started.body()).required("sessionRevision").asLong();
    ObjectNode save = mapper.createObjectNode();
    save.put("expectedSessionRevision", furnitureSessionRevision);
    save.put("assetSnapshotSha256", snapshotSha256);
    save.putArray("items");
    HttpResponse<String> reviewed =
        request(
            "/api/inventory/v1/sessions/" + fixture.inventoryId() + "/furniture-review",
            save.toString());
    assertThat(reviewed.statusCode()).withFailMessage(reviewed.body()).isEqualTo(200);
    return new ReviewFixture(
        mapper.readTree(reviewed.body()).required("sessionRevision").asLong(),
        jdbc.queryForObject(
            "select finding_revision from inventory_finding where id=?",
            Long.class,
            fixture.findingId()));
  }

  private FinalPlanFixture prepareFinalPlan(Fixture fixture, long expectedSessionRevision)
      throws Exception {
    doAnswer(
            invocation -> {
              JsonNode request = invocation.getArgument(1);
              ObjectNode response = mapper.createObjectNode();
              response.put("inventoryId", request.required("inventoryId").asText());
              response.put("finalPlanVersion", request.required("finalPlanVersion").asLong());
              response.put("finalPlanSha256", request.required("finalPlanSha256").asText());
              ArrayNode findings = response.putArray("findings");
              for (JsonNode finding : request.required("findings")) {
                findings
                    .addObject()
                    .put("findingId", finding.required("findingId").asText())
                    .putArray("candidates");
              }
              return response;
            })
        .when(dependencies)
        .preflightReconciliation(any(), any());
    ObjectNode request = mapper.createObjectNode();
    request.put("expectedSessionRevision", expectedSessionRevision);
    request.put("expectedSettingsRevision", 0);
    request.put("movementScheduleMode", "AUTO");
    request.put("repairScheduleMode", "AUTO");
    HttpResponse<String> response =
        post(
            "/api/inventory/v1/sessions/" + fixture.inventoryId() + "/final-plan/prepare",
            request.toString());
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    JsonNode body = mapper.readTree(response.body());
    return new FinalPlanFixture(
        body.required("finalPlanVersion").asLong(), body.required("finalPlanSha256").asText());
  }

  private byte[] readyMediaFact(Fixture fixture) {
    ObjectNode root = mapper.createObjectNode();
    root.put("envelopeVersion", 2);
    root.put("eventId", UUID.randomUUID().toString());
    root.put("eventType", "media.media.ready.v1");
    root.put("eventVersion", 1);
    root.putNull("occurredAt");
    root.put("recordedAt", "2026-07-28T12:00:00Z");
    root.put("producer", "media-service");
    root.put("aggregateType", "MEDIA");
    root.put("aggregateId", fixture.mediaId().toString());
    root.put("aggregateVersion", 3);
    root
        .putObject("correlation")
        .put("correlationId", UUID.randomUUID().toString())
        .putNull("causationId");
    root.putNull("actorRef");
    root
        .putObject("payload")
        .put("mediaId", fixture.mediaId().toString())
        .put("folderId", UUID.randomUUID().toString())
        .put("ownerType", "INVENTORY_FINDING")
        .put("ownerId", fixture.findingId().toString())
        .put("warehouseId", fixture.warehouseId().toString())
        .put("kind", "IMAGE")
        .put("status", "READY")
        .put("generation", 1)
        .put("rotationDegrees", 0);
    return mapper.writeValueAsBytes(root);
  }

  private ObjectNode requestBody(
      Fixture fixture, ObservationPresence equipmentPresence, JsonNode equipmentValue) {
    ObjectNode request = mapper.createObjectNode();
    request.put("expectedSessionRevision", 0);
    request.put("expectedFindingRevision", 0);
    request.put("inspection", "READY");
    request.put("comment", "Проверка API");
    request
        .putObject("passportObservation")
        .put("presence", "ABSENT")
        .putNull("value");
    ObjectNode equipment = request.putObject("equipmentObservation");
    equipment.put("presence", equipmentPresence.name());
    if (equipmentValue == null) equipment.putNull("value");
    else equipment.set("value", equipmentValue);
    request
        .putArray("media")
        .addObject()
        .put("mediaId", fixture.mediaId().toString())
        .put("generation", 1);
    request.put("coverMediaId", fixture.mediaId().toString());
    request.putNull("planSelection");
    return request;
  }

  private JsonNode equipmentValue(ObservationPresence presence) {
    return switch (presence) {
      case ABSENT -> null;
      case EXPLICIT_EMPTY -> mapper.createArrayNode();
      case PRESENT -> {
        ArrayNode snapshot = mapper.createArrayNode();
        snapshot
            .addObject()
            .put("equipmentId", UUID.randomUUID().toString())
            .put("equipmentName", "Стул")
            .put("equipmentCategory", "FURNITURE")
            .put("catalogVersion", 3)
            .put("quantity", 4);
        yield snapshot;
      }
    };
  }

  private String inspectionPath(Fixture fixture) {
    return "/api/inventory/v1/sessions/"
        + fixture.inventoryId()
        + "/findings/"
        + fixture.findingId()
        + "/inspection";
  }

  private HttpResponse<String> request(String path, String body) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private Jwt jwt() {
    Instant now = Instant.now();
    return Jwt.withTokenValue(TOKEN)
        .header("alg", "none")
        .subject("00000000-0000-0000-0000-000000000701")
        .issuedAt(now)
        .expiresAt(now.plusSeconds(300))
        .claim("principal_type", "USER")
        .claim("scope", "rwms.read rwms.write")
        .claim("global_role", "SYSTEM_ADMIN")
        .claim("preferred_username", "api-operator")
        .build();
  }

  private enum NonReadyMediaStatus {
    PROCESSING,
    FAILED,
    DELETED
  }

  private record Fixture(
      UUID inventoryId, UUID findingId, UUID mediaId, UUID warehouseId, UUID assetId) {}

  private record ReviewFixture(long sessionRevision, long findingRevision) {}

  private record FinalPlanFixture(long version, String sha256) {}
}
