package dev.buhanzaz.rwms.maintenance;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
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
class MaintenanceFurnitureCatalogAutoLinkIntegrationTest {
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MaintenanceApplicationService service;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean MaintenanceDependencyGateway dependencies;

  @BeforeEach
  void resetDatabaseAndDependency() {
    jdbc.execute("""
        truncate table
          catalog_version,
          integration_reconciliation,
          event_stream_head
        cascade
        """);
    reset(dependencies);
  }

  @Test
  void replaceNodesAutoFillsFurnitureEquipmentOutsideTheLocalMutationTransaction() {
    UUID catalogId = insertDraftCatalog();
    UUID furnitureId = UUID.randomUUID();
    UUID chairId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    when(dependencies.ensureFurnitureEquipment(chairId, "Chair"))
        .thenAnswer(invocation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          return new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
              chairId, equipmentId, "Chair", 0, null);
        });
    when(dependencies.furnitureEquipmentSnapshots(List.of(chairId)))
        .thenReturn(
            List.of(
                new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
                    chairId, equipmentId, "Chair", 0, null)));

    var changed = service.replaceCatalogNodes(
        catalogId,
        new ReplaceCatalogNodesRequest(
            0L,
            List.of(
                furnitureCategory(furnitureId),
                material(chairId, "Chair", furnitureId, null))));

    assertThat(changed.version()).isOne();
    assertThat(service.catalogNodes(catalogId))
        .filteredOn(node -> node.id().equals(chairId))
        .singleElement()
        .extracting(CatalogNodeResponse::furnitureEquipment)
        .isEqualTo(new FurnitureEquipmentReference(equipmentId, "Chair", 0L, null));

    clearInvocations(dependencies);
    var unchanged = service.replaceCatalogNodes(
        catalogId,
        new ReplaceCatalogNodesRequest(
            1L,
            List.of(
                furnitureCategory(furnitureId),
                material(
                    chairId,
                    "Chair",
                    furnitureId,
                    new FurnitureEquipmentReference(equipmentId, "Chair")))));

    assertThat(unchanged.version()).isOne();
    verify(dependencies, never()).ensureFurnitureEquipment(any(), any());
    verify(dependencies, never()).ensureFurnitureEquipment(any(), any(), any(), any());
    verify(dependencies, never()).furnitureEquipmentSnapshots(any());
    assertThat(jdbc.queryForObject("""
        select count(*) from domain_event
        where aggregate_type='CATALOG_VERSION' and aggregate_id=?
        """, Integer.class, catalogId.toString())).isOne();
    assertThat(service.catalogNodes(catalogId))
        .filteredOn(node -> node.furnitureEquipment() != null)
        .singleElement()
        .satisfies(node -> {
          assertThat(node.id()).isEqualTo(chairId);
          assertThat(node.furnitureEquipment()).isEqualTo(
              new FurnitureEquipmentReference(equipmentId, "Chair", 0L, null));
        });
  }

  @Test
  void invalidFurnitureGraphIsRejectedBeforeAnyAssetCall() {
    UUID catalogId = insertDraftCatalog();
    UUID missingParentId = UUID.randomUUID();

    assertThatThrownBy(() -> service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(
            0L,
            List.of(material(
                UUID.randomUUID(), "Chair", missingParentId, null)),
            List.of())))
        .isInstanceOf(dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException.class)
        .hasMessageContaining("parent node is missing");

    verify(dependencies, never()).ensureFurnitureEquipment(any(), any());
    verify(dependencies, never()).ensureFurnitureEquipment(any(), any(), any(), any());
    assertThat(service.catalogVersion(catalogId).version()).isZero();
    assertThat(service.catalogNodes(catalogId)).isEmpty();
  }

  @Test
  void existingFurnitureReferenceIsConfirmedAndOtherMaterialsDoNotCallAsset() {
    UUID catalogId = insertDraftCatalog();
    UUID furnitureId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID chairId = UUID.randomUUID();
    UUID ordinaryMaterialId = UUID.randomUUID();
    FurnitureEquipmentReference existing =
        new FurnitureEquipmentReference(equipmentId, "Chair");
    when(dependencies.ensureFurnitureEquipment(chairId, "Chair"))
        .thenReturn(
            new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
                chairId, equipmentId, "Chair", 0, null));
    when(dependencies.furnitureEquipmentSnapshots(List.of(chairId)))
        .thenReturn(
            List.of(
                new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
                    chairId, equipmentId, "Chair", 0, null)));

    service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(
            0L,
            List.of(
                furnitureCategory(furnitureId),
                material(chairId, "Chair", furnitureId, existing),
                material(
                    ordinaryMaterialId,
                    "Paint",
                    null,
                    null)),
            List.of()));

    verify(dependencies).ensureFurnitureEquipment(chairId, "Chair");
    assertThat(service.catalogNodes(catalogId))
        .filteredOn(node -> node.id().equals(chairId))
        .singleElement()
        .extracting(CatalogNodeResponse::furnitureEquipment)
        .isEqualTo(new FurnitureEquipmentReference(equipmentId, "Chair", 0L, null));
    assertThat(service.catalogNodes(catalogId))
        .filteredOn(node -> node.id().equals(ordinaryMaterialId))
        .singleElement()
        .extracting(CatalogNodeResponse::furnitureEquipment)
        .isNull();
  }

  @Test
  void multipleMissingFurnitureItemsAreEnsuredDeterministicallyByName() {
    UUID catalogId = insertDraftCatalog();
    UUID furnitureId = UUID.randomUUID();
    UUID chairEquipmentId = UUID.randomUUID();
    UUID tableEquipmentId = UUID.randomUUID();
    UUID tableId = UUID.randomUUID();
    UUID chairId = UUID.randomUUID();
    when(dependencies.ensureFurnitureEquipment(chairId, "Chair"))
        .thenReturn(new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            chairEquipmentId, "Chair"));
    when(dependencies.ensureFurnitureEquipment(tableId, "Table"))
        .thenReturn(new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            tableEquipmentId, "Table"));

    service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(
            0L,
            List.of(
                furnitureCategory(furnitureId),
                material(tableId, "Table", furnitureId, null),
                material(chairId, "Chair", furnitureId, null)),
            List.of()));

    var ordered = inOrder(dependencies);
    ordered.verify(dependencies).ensureFurnitureEquipment(chairId, "Chair");
    ordered.verify(dependencies).ensureFurnitureEquipment(tableId, "Table");
  }

  @ParameterizedTest
  @ValueSource(ints = {409, 503})
  void dependencyConflictOrFailureLeavesTheCatalogVersionAndNodesUntouched(int statusCode) {
    UUID catalogId = insertDraftCatalog();
    UUID originalId = UUID.randomUUID();
    service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(
            0L,
            List.of(plainCategory(originalId)),
            List.of()));
    UUID furnitureId = UUID.randomUUID();
    UUID chairId = UUID.randomUUID();
    UUID tableId = UUID.randomUUID();
    when(dependencies.ensureFurnitureEquipment(chairId, "Chair"))
        .thenReturn(new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            UUID.randomUUID(), "Chair"));
    when(dependencies.ensureFurnitureEquipment(tableId, "Table"))
        .thenThrow(new MaintenanceDependencyException(
            HttpStatus.valueOf(statusCode), "Asset furniture synchronization failed"));

    assertThatThrownBy(() -> service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(
            1L,
            List.of(
                furnitureCategory(furnitureId),
                material(tableId, "Table", furnitureId, null),
                material(chairId, "Chair", furnitureId, null)),
            List.of())))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.valueOf(statusCode)));

    assertThat(service.catalogVersion(catalogId).version()).isOne();
    assertThat(service.catalogNodes(catalogId))
        .extracting(CatalogNodeResponse::id, CatalogNodeResponse::name)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(originalId, "Root"));
  }

  private UUID insertDraftCatalog() {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into catalog_version(
          id,version,warehouse_id,state,source_sha256,node_count,link_count,
          validation_report,created_at,updated_at)
        values (?,0,?,'DRAFT',?,0,0,?::jsonb::text,clock_timestamp(),clock_timestamp())
        """,
        id,
        UUID.randomUUID(),
        UUID.randomUUID().toString().replace("-", "").repeat(2),
        """
        {"valid":true,"errorCount":0,"warningCount":0,
         "reportSha256":"0000000000000000000000000000000000000000000000000000000000000000"}
        """);
    jdbc.update("""
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('CATALOG_VERSION',?,0,?,clock_timestamp())
        """, id.toString(), UUID.randomUUID());
    return id;
  }

  private static CatalogNodeInput furnitureCategory(UUID id) {
    return new CatalogNodeInput(
        id,
        CatalogNodeType.CATEGORY,
        "Furniture",
        true,
        null,
        true,
        null,
        null,
        null,
        0,
        false,
        false,
        true,
        null,
        null,
        null,
        null,
        null,
        false,
        null);
  }

  private static CatalogNodeInput plainCategory(UUID id) {
    return new CatalogNodeInput(
        id,
        CatalogNodeType.CATEGORY,
        "Root",
        true,
        null,
        false,
        null,
        null,
        null,
        0,
        false,
        false,
        true,
        null,
        null,
        null,
        null,
        null,
        false,
        null);
  }

  private static CatalogNodeInput material(
      UUID id,
      String name,
      UUID parentId,
      FurnitureEquipmentReference furnitureEquipment) {
    return new CatalogNodeInput(
        id,
        CatalogNodeType.MATERIAL,
        name,
        true,
        parentId,
        false,
        furnitureEquipment,
        "piece",
        "100.00",
        0,
        true,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        false,
        null);
  }
}
