package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Public API contract for reconciling the complete observed cabin furniture composition. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.return-registration.relay-enabled=false",
      "rwms.logistics.return-completion.relay-enabled=false",
      "rwms.logistics.shipment.relay-enabled=false",
      "rwms.logistics.transfer.relay-enabled=false",
      "rwms.logistics.owner-proof.relay-enabled=false",
      "rwms.logistics.equipment-movement.relay-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CabinFurnitureTaskApiIntegrationTest {
  private static final UUID ACTOR =
      UUID.fromString("00000000-0000-0000-0000-000000009401");
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000009402");
  private static final UUID RENTAL_ITEM =
      UUID.fromString("00000000-0000-0000-0000-000000009403");
  private static final UUID EQUIPMENT =
      UUID.fromString("00000000-0000-0000-0000-000000009404");
  private static final UUID BALANCE =
      UUID.fromString("00000000-0000-0000-0000-000000009405");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean LogisticsDependencyGateway dependencies;

  @BeforeEach
  void resetState() {
    jdbc.execute(
        """
        truncate table
          logistics_warehouse_admission_intent,
          warehouse_operation_mark_outbox,
          equipment_movement_task_line,
          equipment_movement_task
        cascade
        """);
    when(dependencies.productionReady()).thenReturn(true);
    when(
            dependencies.warehouseAdmission(
                WAREHOUSE,
                LogisticsDependencyGateway.WarehouseOperationDirection.OUTGOING))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseOperationAdmission(
                WAREHOUSE,
                17,
                LogisticsDependencyGateway.WarehouseLifecycleState.ACTIVE,
                LogisticsDependencyGateway.WarehouseOperationDirection.OUTGOING,
                true));
    when(dependencies.warehouseTimeZoneAt(eq(WAREHOUSE), any(OffsetDateTime.class)))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.WarehouseTimeZone(
                    WAREHOUSE, "UTC", invocation.getArgument(1)));
  }

  @Test
  void changedObservedCompositionCreatesAWorkerTaskWithoutMovingFurnitureImmediately()
      throws Exception {
    when(
            dependencies.planCabinFurnitureMovements(
                eq(WAREHOUSE),
                eq(RENTAL_ITEM),
                eq(List.of(new LogisticsDependencyGateway.CabinFurnitureRequirement(EQUIPMENT, 2L)))))
        .thenReturn(changedPlan());

    mvc.perform(
            post("/api/logistics/v1/rental-items/{rentalItemId}/furniture-tasks", RENTAL_ITEM)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody())
                .with(actor()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.rentalItemId").value(RENTAL_ITEM.toString()))
        .andExpect(jsonPath("$.unitNumber").value("БЫТ-940"))
        .andExpect(jsonPath("$.taskId").isNotEmpty())
        .andExpect(jsonPath("$.lineCount").value(1));

    assertThat(
            jdbc.queryForObject("select count(*) from equipment_movement_task", Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForMap(
                """
                select admission_direction,admission_warehouse_version
                  from warehouse_operation_mark_outbox
                 where operation_id=(select id from equipment_movement_task) and warehouse_id=?
                """,
                WAREHOUSE))
        .containsEntry("admission_direction", "OUTGOING")
        .containsEntry("admission_warehouse_version", 17L);
    verify(dependencies)
        .planCabinFurnitureMovements(
            WAREHOUSE,
            RENTAL_ITEM,
            List.of(new LogisticsDependencyGateway.CabinFurnitureRequirement(EQUIPMENT, 2L)));
    verify(dependencies, never()).executeEquipmentMovement(any(), any(), any());
  }

  @Test
  void matchingObservedCompositionDoesNotCreateAWorkerTask() throws Exception {
    when(
            dependencies.planCabinFurnitureMovements(
                eq(WAREHOUSE),
                eq(RENTAL_ITEM),
                eq(List.of(new LogisticsDependencyGateway.CabinFurnitureRequirement(EQUIPMENT, 2L)))))
        .thenReturn(
            new LogisticsDependencyGateway.CabinFurnitureMovementPlan(
                RENTAL_ITEM, "БЫТ-940", List.of()));

    mvc.perform(
            post("/api/logistics/v1/rental-items/{rentalItemId}/furniture-tasks", RENTAL_ITEM)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody())
                .with(actor()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rentalItemId").value(RENTAL_ITEM.toString()))
        .andExpect(jsonPath("$.taskId").value(nullValue()))
        .andExpect(jsonPath("$.lineCount").value(0));

    assertThat(
            jdbc.queryForObject("select count(*) from equipment_movement_task", Integer.class))
        .isZero();
  }

  private static LogisticsDependencyGateway.CabinFurnitureMovementPlan changedPlan() {
    return new LogisticsDependencyGateway.CabinFurnitureMovementPlan(
        RENTAL_ITEM,
        "БЫТ-940",
        List.of(
            new LogisticsDependencyGateway.CabinFurnitureMovementPlanLine(
                EQUIPMENT,
                "Стул",
                BALANCE,
                WAREHOUSE,
                null,
                "STOCK",
                4L,
                WAREHOUSE,
                RENTAL_ITEM,
                "CABIN_NON_RENTED",
                2L)));
  }

  private static String requestBody() {
    return """
        {"warehouseId":"%s","scheduledDate":"%s",
        "contents":[{"equipmentId":"%s","quantity":2}]}
        """
        .formatted(WAREHOUSE, LocalDate.now().plusDays(1), EQUIPMENT);
  }

  private static JwtRequestPostProcessor actor() {
    return jwt()
        .jwt(
            value ->
                value
                    .subject(ACTOR.toString())
                    .claim("principal_type", "USER")
                    .claim("scope", "rwms.read rwms.write")
                    .claim(
                        "warehouse_access",
                        List.of(Map.of("warehouseId", WAREHOUSE.toString(), "level", "MANAGE"))));
  }
}
