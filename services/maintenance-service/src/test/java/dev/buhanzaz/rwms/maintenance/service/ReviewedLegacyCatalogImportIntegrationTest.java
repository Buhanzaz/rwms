package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService.CreateResult;
import dev.buhanzaz.rwms.platform.contracts.FieldViolation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.maintenance.task-reconciliation.initial-delay=1h",
      "rwms.maintenance.task-reconciliation.delay=1h",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReviewedLegacyCatalogImportIntegrationTest {
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MaintenanceApplicationService service;
  @Autowired ReviewedLegacyCatalogManifest reviewedCatalog;
  @Autowired RentalItemFactProjectionRepository rentalItemFacts;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @BeforeEach
  void resetDatabase() {
    jdbc.execute(
        """
        truncate table
          catalog_version,
          maintenance_estimate,
          rental_item_fact_projection,
          maintenance_idempotency_record,
          event_stream_head
        cascade
        """);
  }

  @Test
  void packagedArtifactPinsApprovedPolicyEvidenceAndSanitizedDefaults() throws IOException {
    ImportCatalogRequest request = reviewedCatalog.approvedRequest();
    ReviewedLegacyCatalogManifest.Review review = reviewedCatalog.validate(request);

    assertThat(request.warehouseId()).isEqualTo(ReviewedLegacyCatalogManifest.SOURCE_WAREHOUSE_ID);
    assertThat(request.sourceSha256()).isEqualTo(ReviewedLegacyCatalogManifest.SOURCE_SHA256);
    assertThat(request.nodes()).hasSize(232);
    assertThat(request.links()).hasSize(254);
    assertThat(review.mappingSha256())
        .isEqualTo(ReviewedLegacyCatalogManifest.APPROVED_MAPPING_SHA256);
    assertThat(review.sourceEvidenceSha256())
        .isEqualTo(ReviewedLegacyCatalogManifest.SOURCE_SHA256);
    assertThat(review.nodeEvidenceSha256())
        .isEqualTo(ReviewedLegacyCatalogManifest.NODE_SHA256);
    assertThat(review.linkEvidenceSha256())
        .isEqualTo(ReviewedLegacyCatalogManifest.LINK_SHA256);
    assertThat(review.queueEvidenceSha256())
        .isEqualTo(ReviewedLegacyCatalogManifest.QUEUE_SHA256);
    assertThat(request.nodes()).allSatisfy(node -> {
      assertThat(node.comment()).isNull();
      assertThat(node.references()).isEmpty();
      assertThat(node.mediaReferences()).isEmpty();
      assertThat(node.durationMinutes()).isNotNegative();
    });
    assertThat(request.nodes()).filteredOn(node -> node.durationMinutes() == 0).hasSize(144);
    assertThat(request.links()).allSatisfy(link -> assertThat(link.sortOrder()).isZero());
    assertThat(request.nodes()).filteredOn(node -> node.routing() != null).hasSize(10);
    assertThat(request.nodes().stream()
            .map(CatalogNodeInput::routing)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet()))
        .hasSize(6);

    JsonNode artifact;
    try (var input =
        new ClassPathResource(ReviewedLegacyCatalogManifest.ARTIFACT_RESOURCE).getInputStream()) {
      artifact = mapper.readTree(input);
    }
    assertThat(artifact.path("sourceWarehouseId").asText())
        .isEqualTo(ReviewedLegacyCatalogManifest.SOURCE_WAREHOUSE_ID.toString());
    assertThat(artifact.path("approvedMappingPolicySha256").asText())
        .isEqualTo(ReviewedLegacyCatalogManifest.APPROVED_MAPPING_POLICY_SHA256);
    assertThat(artifact.path("mappingPolicy").path("piiAllowed").asBoolean()).isFalse();
    assertThat(textValues(artifact.path("mappingPolicy").path("excludedLegacyFields")))
        .contains(
            "NODE.SORT_ORDER",
            "NODE.DEFAULT_QUANTITY",
            "NODE.ADDITIONAL_OPTION",
            "NODE.FURNITURE_CATEGORY",
            "LINK.ACTIVE",
            "NODE.COMMENT_",
            "LINK.COMMENT_");
    assertThat(artifact.path("mappingPolicy").path("defaults").path("nullDurationMinutes").asText())
        .isEqualTo("0");
    assertThat(artifact.path("mappingPolicy").path("defaults").path("nullLinkSortOrder").asText())
        .isEqualTo("0");
    assertStrictResourceReaderRejectsUnknownFields();
  }

  private void assertStrictResourceReaderRejectsUnknownFields() throws IOException {
    String manifestJson = readResource(ReviewedLegacyCatalogManifest.MANIFEST_RESOURCE);
    String artifactJson = readResource(ReviewedLegacyCatalogManifest.ARTIFACT_RESOURCE);

    ObjectNode unknownTopLevel = (ObjectNode) mapper.readTree(manifestJson);
    unknownTopLevel.put("rawLegacySql", "must never be ignored");
    String unknownTopLevelJson = mapper.writeValueAsString(unknownTopLevel);
    assertThatThrownBy(
            () ->
                ReviewedLegacyCatalogManifest.assertStrictResourceShape(
                    mapper, unknownTopLevelJson, artifactJson))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ReviewedLegacyCatalogManifest.MANIFEST_RESOURCE);

    ObjectNode unknownNested = (ObjectNode) mapper.readTree(artifactJson);
    ((ObjectNode) unknownNested.path("nodes").get(0)).put("createdBy", "legacy-admin");
    String unknownNestedJson = mapper.writeValueAsString(unknownNested);
    assertThatThrownBy(
            () ->
                ReviewedLegacyCatalogManifest.assertStrictResourceShape(
                    mapper, manifestJson, unknownNestedJson))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ReviewedLegacyCatalogManifest.ARTIFACT_RESOURCE);
  }

  @Test
  void strictResourceReaderRejectsDuplicateJsonKeys() throws IOException {
    String manifestJson = readResource(ReviewedLegacyCatalogManifest.MANIFEST_RESOURCE);
    String artifactJson = readResource(ReviewedLegacyCatalogManifest.ARTIFACT_RESOURCE);
    String approvedMappingEntry =
        "\"approvedMappingSha256\":\""
            + ReviewedLegacyCatalogManifest.APPROVED_MAPPING_SHA256
            + "\"";
    String duplicateKeyArtifactJson =
        artifactJson.replace(
            approvedMappingEntry, approvedMappingEntry + "," + approvedMappingEntry);

    assertThat(duplicateKeyArtifactJson).isNotEqualTo(artifactJson);
    assertThatThrownBy(
            () ->
                ReviewedLegacyCatalogManifest.assertStrictResourceShape(
                    mapper, manifestJson, duplicateKeyArtifactJson))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ReviewedLegacyCatalogManifest.ARTIFACT_RESOURCE);
  }

  @Test
  void approvedImportPersistsDraftActivatesAndSuppliesEstimateWithIdempotentRetry() {
    ImportCatalogRequest request = reviewedCatalog.approvedRequest();
    UUID subjectId = UUID.randomUUID();
    UUID importKey = UUID.randomUUID();

    CreateResult<CatalogVersionResponse> imported =
        service.importCatalog(subjectId, importKey, request);
    CreateResult<CatalogVersionResponse> retry =
        service.importCatalog(subjectId, importKey, request);
    CreateResult<CatalogVersionResponse> sameSourceRetry =
        service.importCatalog(subjectId, UUID.randomUUID(), request);

    assertThat(imported.replayed()).isFalse();
    assertThat(imported.response().lifecycle()).isEqualTo(CatalogVersionState.DRAFT);
    assertThat(imported.response().counts()).isEqualTo(new CatalogCounts(232, 254));
    assertThat(retry.replayed()).isTrue();
    assertThat(sameSourceRetry.replayed()).isTrue();
    assertThat(retry.response().id()).isEqualTo(imported.response().id());
    assertThat(sameSourceRetry.response().id()).isEqualTo(imported.response().id());
    assertThat(tableCount("catalog_version")).isOne();
    assertThat(tableCount("catalog_node")).isEqualTo(232);
    assertThat(tableCount("catalog_link")).isEqualTo(254);
    String validationReport =
        jdbc.queryForObject(
            "select validation_report from catalog_version where id=?",
            String.class,
            imported.response().id());
    assertThat(validationReport)
        .contains(ReviewedLegacyCatalogManifest.APPROVED_MAPPING_SHA256)
        .contains(ReviewedLegacyCatalogManifest.QUEUE_SHA256);

    CreateResult<CatalogVersionResponse> activated =
        service.activateCatalog(
            subjectId,
            UUID.randomUUID(),
            imported.response().id(),
            new VersionCommand(imported.response().version()));
    assertThat(activated.replayed()).isFalse();
    assertThat(activated.response().lifecycle()).isEqualTo(CatalogVersionState.ACTIVE);

    CatalogNodeInput work =
        request.nodes().stream()
            .filter(node -> node.nodeType() == CatalogNodeType.WORK)
            .filter(CatalogNodeInput::includeInEstimate)
            .filter(node -> node.unitPrice() != null)
            .findFirst()
            .orElseThrow();
    Map<UUID, CatalogNodeInput> nodesById =
        request.nodes().stream()
            .collect(
                java.util.stream.Collectors.toMap(CatalogNodeInput::id, node -> node));
    CatalogNodeInput routedAncestor = work;
    Set<UUID> visited = new HashSet<>();
    while (routedAncestor.routing() == null && routedAncestor.parentNodeId() != null) {
      assertThat(visited.add(routedAncestor.id())).isTrue();
      routedAncestor = nodesById.get(routedAncestor.parentNodeId());
      assertThat(routedAncestor).isNotNull();
    }
    assertThat(work.code()).isEqualTo("SANITARY_WORKS");
    assertThat(routedAncestor.code()).isEqualTo("DISINFECTION_WORKS");
    RoutingSnapshot reviewedRouting = routedAncestor.routing();
    assertThat(reviewedRouting).isNotNull();
    assertThat(reviewedRouting.queueId())
        .isEqualTo(UUID.fromString("019f21ed-eb53-782d-a73f-a24357e262b2"));
    assertThat(reviewedRouting.queueCode()).isEqualTo("SANITARY_DISINFECTION");
    assertThat(reviewedRouting.queueKind()).isEqualTo("HOLDING");
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, request.warehouseId(), "FREE", 7));
    CatalogNodeSnapshot snapshot =
        new CatalogNodeSnapshot(
            imported.response().id(),
            work.id(),
            work.code(),
            work.nodeType(),
            work.name(),
            work.unit(),
            work.unitPrice(),
            work.durationMinutes(),
            work.routing());
    EstimateLineInput line =
        new EstimateLineInput(
            UUID.randomUUID(),
            snapshot,
            work.name(),
            "1",
            work.unitPrice() == null ? "0.00" : work.unitPrice(),
            null,
            List.of());
    PlanStageInput stage =
        new PlanStageInput(
            UUID.randomUUID(), RepairStageKind.REPAIR_WORK, 0, reviewedRouting, null);
    CreateResult<EstimateResponse> estimate =
        service.createEstimate(
            subjectId,
            UUID.randomUUID(),
            new CreateEstimateRequest(
                request.warehouseId(),
                rentalItemId,
                LocalDate.of(2026, 7, 17),
                "reviewed catalog import",
                List.of(line),
                List.of(stage),
                List.of()));

    assertThat(estimate.response().lifecycle())
        .isEqualTo(dev.buhanzaz.rwms.maintenance.domain.EstimateState.DRAFT);
    UUID persistedCatalogVersionId =
        jdbc.queryForObject(
            "select catalog_version_id from maintenance_estimate where id=?",
            UUID.class,
            estimate.response().id());
    UUID persistedCatalogNodeId =
        jdbc.queryForObject(
            "select catalog_node_id from estimate_line where estimate_id=?",
            UUID.class,
            estimate.response().id());
    assertThat(persistedCatalogVersionId).isEqualTo(imported.response().id());
    assertThat(persistedCatalogNodeId).isEqualTo(work.id());
    assertThat(tableCount("domain_event")).isEqualTo(3);
    assertThat(tableCount("outbox_event")).isEqualTo(3);
  }

  @Test
  void wrongSourceHashProducesNoWrites() {
    ImportCatalogRequest approved = reviewedCatalog.approvedRequest();
    assertRejectedNoWrites(
        new ImportCatalogRequest(
            approved.warehouseId(), "0".repeat(64), approved.nodes(), approved.links()),
        "REVIEWED_SOURCE_HASH_MISMATCH");
  }

  @Test
  void wrongSourceWarehouseProducesNoWrites() {
    ImportCatalogRequest approved = reviewedCatalog.approvedRequest();
    assertRejectedNoWrites(
        new ImportCatalogRequest(
            UUID.randomUUID(), approved.sourceSha256(), approved.nodes(), approved.links()),
        "REVIEWED_SOURCE_WAREHOUSE_MISMATCH");
  }

  @Test
  void wrongMappingHashAndPreservedValueProduceNoWrites() {
    ImportCatalogRequest approved = reviewedCatalog.approvedRequest();
    CatalogNodeInput first = approved.nodes().getFirst();
    assertRejectedNoWrites(
        withNode(approved, 0, copyNode(first, first.id(), first.nodeType(), first.parentNodeId(),
            first.name() + " changed", first.routing(), List.of(), null, List.of())),
        "REVIEWED_MAPPING_HASH_MISMATCH");
  }

  @Test
  void wrongCountsProduceNoWrites() {
    ImportCatalogRequest approved = reviewedCatalog.approvedRequest();
    assertRejectedNoWrites(
        new ImportCatalogRequest(
            approved.warehouseId(),
            approved.sourceSha256(),
            approved.nodes().subList(0, approved.nodes().size() - 1),
            approved.links()),
        "REVIEWED_NODE_COUNT_MISMATCH");
    assertRejectedNoWrites(
        new ImportCatalogRequest(
            approved.warehouseId(),
            approved.sourceSha256(),
            approved.nodes(),
            approved.links().subList(0, approved.links().size() - 1)),
        "REVIEWED_LINK_COUNT_MISMATCH");
  }

  @Test
  void wrongNodeAndLinkIdentitiesProduceNoWrites() {
    ImportCatalogRequest approved = reviewedCatalog.approvedRequest();
    CatalogNodeInput firstNode = approved.nodes().getFirst();
    assertRejectedNoWrites(
        withNode(
            approved,
            0,
            copyNode(
                firstNode,
                UUID.randomUUID(),
                firstNode.nodeType(),
                firstNode.parentNodeId(),
                firstNode.name(),
                firstNode.routing(),
                List.of(),
                null,
                List.of())),
        "REVIEWED_NODE_ID_MISMATCH");

    CatalogLinkInput firstLink = approved.links().getFirst();
    assertRejectedNoWrites(
        withLink(
            approved,
            0,
            new CatalogLinkInput(
                UUID.randomUUID(),
                firstLink.fromNodeId(),
                firstLink.toNodeId(),
                firstLink.linkType(),
                firstLink.sortOrder())),
        "REVIEWED_LINK_ID_MISMATCH");
  }

  @Test
  void wrongReferencesAndRoutingProduceNoWrites() {
    ImportCatalogRequest approved = reviewedCatalog.approvedRequest();
    int parentedIndex = indexOf(approved.nodes(), node -> node.parentNodeId() != null);
    CatalogNodeInput parented = approved.nodes().get(parentedIndex);
    UUID otherParent =
        approved.nodes().stream()
            .map(CatalogNodeInput::id)
            .filter(id -> !id.equals(parented.parentNodeId()))
            .filter(id -> !id.equals(parented.id()))
            .findFirst()
            .orElseThrow();
    assertRejectedNoWrites(
        withNode(
            approved,
            parentedIndex,
            copyNode(
                parented,
                parented.id(),
                parented.nodeType(),
                otherParent,
                parented.name(),
                parented.routing(),
                List.of(),
                null,
                List.of())),
        "REVIEWED_NODE_REFERENCE_MISMATCH");

    int routedIndex = indexOf(approved.nodes(), node -> node.routing() != null);
    CatalogNodeInput routed = approved.nodes().get(routedIndex);
    RoutingSnapshot otherRouting =
        approved.nodes().stream()
            .map(CatalogNodeInput::routing)
            .filter(java.util.Objects::nonNull)
            .filter(value -> !value.equals(routed.routing()))
            .findFirst()
            .orElseThrow();
    assertRejectedNoWrites(
        withNode(
            approved,
            routedIndex,
            copyNode(
                routed,
                routed.id(),
                routed.nodeType(),
                routed.parentNodeId(),
                routed.name(),
                otherRouting,
                List.of(),
                null,
                List.of())),
        "REVIEWED_ROUTING_MISMATCH");

    CatalogLinkInput link = approved.links().getFirst();
    UUID otherEndpoint =
        approved.nodes().stream()
            .map(CatalogNodeInput::id)
            .filter(id -> !id.equals(link.toNodeId()))
            .filter(id -> !id.equals(link.fromNodeId()))
            .findFirst()
            .orElseThrow();
    assertRejectedNoWrites(
        withLink(
            approved,
            0,
            new CatalogLinkInput(
                link.id(),
                link.fromNodeId(),
                otherEndpoint,
                link.linkType(),
                link.sortOrder())),
        "REVIEWED_LINK_REFERENCE_MISMATCH");
  }

  @Test
  void wrongTypesWithPreservedDistributionsProduceNoWrites() {
    ImportCatalogRequest approved = reviewedCatalog.approvedRequest();
    CatalogNodeInput first = approved.nodes().getFirst();
    int otherIndex = indexOf(approved.nodes(), node -> node.nodeType() != first.nodeType());
    CatalogNodeInput other = approved.nodes().get(otherIndex);
    List<CatalogNodeInput> changed = new ArrayList<>(approved.nodes());
    changed.set(
        0,
        copyNode(
            first,
            first.id(),
            other.nodeType(),
            first.parentNodeId(),
            first.name(),
            first.routing(),
            List.of(),
            null,
            List.of()));
    changed.set(
        otherIndex,
        copyNode(
            other,
            other.id(),
            first.nodeType(),
            other.parentNodeId(),
            other.name(),
            other.routing(),
            List.of(),
            null,
            List.of()));
    assertRejectedNoWrites(
        new ImportCatalogRequest(
            approved.warehouseId(), approved.sourceSha256(), changed, approved.links()),
        "REVIEWED_NODE_TYPE_MISMATCH");

    CatalogLinkInput dependency =
        approved.links().stream()
            .filter(link -> link.linkType() == CatalogLinkType.DEPENDENCY)
            .findFirst()
            .orElseThrow();
    CatalogLinkInput followUp =
        approved.links().stream()
            .filter(link -> link.linkType() == CatalogLinkType.FOLLOW_UP)
            .findFirst()
            .orElseThrow();
    List<CatalogLinkInput> changedLinks = new ArrayList<>(approved.links());
    int dependencyIndex = changedLinks.indexOf(dependency);
    int followUpIndex = changedLinks.indexOf(followUp);
    changedLinks.set(
        dependencyIndex,
        new CatalogLinkInput(
            dependency.id(),
            dependency.fromNodeId(),
            dependency.toNodeId(),
            CatalogLinkType.FOLLOW_UP,
            dependency.sortOrder()));
    changedLinks.set(
        followUpIndex,
        new CatalogLinkInput(
            followUp.id(),
            followUp.fromNodeId(),
            followUp.toNodeId(),
            CatalogLinkType.DEPENDENCY,
            followUp.sortOrder()));
    assertRejectedNoWrites(
        new ImportCatalogRequest(
            approved.warehouseId(), approved.sourceSha256(), approved.nodes(), changedLinks),
        "REVIEWED_LINK_TYPE_MISMATCH");
  }

  @Test
  void unsafeExcludedFieldsAndInvalidSelfLinkProduceNoWrites() {
    ImportCatalogRequest approved = reviewedCatalog.approvedRequest();
    CatalogNodeInput first = approved.nodes().getFirst();
    assertRejectedNoWrites(
        withNode(
            approved,
            0,
            copyNode(
                first,
                first.id(),
                first.nodeType(),
                first.parentNodeId(),
                first.name(),
                first.routing(),
                List.of(new OpaqueCatalogReference("legacy-audit", "LEGACY")),
                "legacy comment must not cross the boundary",
                List.of(new MediaReferenceInput(UUID.randomUUID(), 0L)))),
        "REVIEWED_EXCLUDED_FIELD_PRESENT");

    CatalogLinkInput link = approved.links().getFirst();
    assertRejectedNoWrites(
        withLink(
            approved,
            0,
            new CatalogLinkInput(
                link.id(),
                link.fromNodeId(),
                link.fromNodeId(),
                link.linkType(),
                link.sortOrder())),
        "REVIEWED_LINK_REFERENCE_MISMATCH");
  }

  private void assertRejectedNoWrites(ImportCatalogRequest request, String expectedCode) {
    assertThatThrownBy(
            () -> service.importCatalog(UUID.randomUUID(), UUID.randomUUID(), request))
        .isInstanceOfSatisfying(
            MaintenanceCatalogImportValidationException.class,
            exception ->
                assertThat(exception.violations())
                    .extracting(FieldViolation::code)
                    .contains(expectedCode));
    assertThat(tableCount("catalog_version")).isZero();
    assertThat(tableCount("catalog_node")).isZero();
    assertThat(tableCount("catalog_link")).isZero();
    assertThat(tableCount("event_stream_head")).isZero();
    assertThat(tableCount("domain_event")).isZero();
    assertThat(tableCount("outbox_event")).isZero();
    assertThat(tableCount("maintenance_idempotency_record")).isZero();
  }

  private int tableCount(String table) {
    if (!Set.of(
            "catalog_version",
            "catalog_node",
            "catalog_link",
            "event_stream_head",
            "domain_event",
            "outbox_event",
            "maintenance_idempotency_record")
        .contains(table)) {
      throw new IllegalArgumentException("Unexpected table");
    }
    return jdbc.queryForObject("select count(*) from " + table, Integer.class);
  }

  private static ImportCatalogRequest withNode(
      ImportCatalogRequest request, int index, CatalogNodeInput node) {
    List<CatalogNodeInput> nodes = new ArrayList<>(request.nodes());
    nodes.set(index, node);
    return new ImportCatalogRequest(
        request.warehouseId(), request.sourceSha256(), nodes, request.links());
  }

  private static ImportCatalogRequest withLink(
      ImportCatalogRequest request, int index, CatalogLinkInput link) {
    List<CatalogLinkInput> links = new ArrayList<>(request.links());
    links.set(index, link);
    return new ImportCatalogRequest(
        request.warehouseId(), request.sourceSha256(), request.nodes(), links);
  }

  private static CatalogNodeInput copyNode(
      CatalogNodeInput source,
      UUID id,
      CatalogNodeType nodeType,
      UUID parentNodeId,
      String name,
      RoutingSnapshot routing,
      List<OpaqueCatalogReference> references,
      String comment,
      List<MediaReferenceInput> mediaReferences) {
    return new CatalogNodeInput(
        id,
        source.code(),
        nodeType,
        name,
        source.active(),
        parentNodeId,
        source.unit(),
        source.unitPrice(),
        source.durationMinutes(),
        source.includeInEstimate(),
        source.commonItem(),
        source.showInMainMenu(),
        source.photoRequired(),
        routing,
        references,
        comment,
        mediaReferences);
  }

  private static int indexOf(
      List<CatalogNodeInput> nodes,
      java.util.function.Predicate<CatalogNodeInput> predicate) {
    for (int index = 0; index < nodes.size(); index++) {
      if (predicate.test(nodes.get(index))) {
        return index;
      }
    }
    throw new IllegalStateException("Matching catalog node not found");
  }

  private static Set<String> textValues(JsonNode array) {
    Set<String> values = new HashSet<>();
    array.forEach(value -> values.add(value.asText()));
    return values;
  }

  private static String readResource(String resource) throws IOException {
    try (var input = new ClassPathResource(resource).getInputStream()) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
