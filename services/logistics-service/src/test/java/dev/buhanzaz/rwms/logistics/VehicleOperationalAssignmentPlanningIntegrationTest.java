package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateTransferRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferPlanRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferResourceRepositionRequest;
import dev.buhanzaz.rwms.logistics.domain.TransferResourceRepositionMode;
import dev.buhanzaz.rwms.logistics.driver.service.DocumentDriverTaskPlanner;
import dev.buhanzaz.rwms.logistics.driver.service.TransferDriverTaskContentService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseOperationDirection;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycleStore.AdmissionRequirement;
import dev.buhanzaz.rwms.logistics.vehicle.service.VehicleOperationalAssignmentService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises durable vehicle chains, Flyway/JPA validation, and the private planner boundary. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.return-registration.relay-enabled=false",
      "rwms.logistics.return-completion.relay-enabled=false",
      "rwms.logistics.shipment.relay-enabled=false",
      "rwms.logistics.transfer.relay-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VehicleOperationalAssignmentPlanningIntegrationTest {
  private static final UUID SUBJECT =
      UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final UUID ORIGIN =
      UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final UUID DESTINATION =
      UUID.fromString("20000000-0000-0000-0000-000000000003");
  private static final UUID THIRD_WAREHOUSE =
      UUID.fromString("20000000-0000-0000-0000-000000000004");
  private static final UUID FOURTH_WAREHOUSE =
      UUID.fromString("20000000-0000-0000-0000-000000000005");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired LogisticsDocumentService documents;
  @Autowired LogisticsWarehouseLifecycle warehouseLifecycle;
  @Autowired VehicleOperationalAssignmentService vehicleAssignments;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean LogisticsDependencyGateway dependencies;
  @MockitoBean DocumentDriverTaskPlanner driverTaskPlanner;
  @MockitoBean TransferDriverTaskContentService driverTaskContent;

  @BeforeEach
  void reset() {
    jdbc.execute(
        """
        truncate table
          logistics_document,
          event_stream_head,
          domain_event,
          aggregate_snapshot,
          projection_checkpoint,
          outbox_event
        cascade
        """);
  }

  @Test
  void exposesOnlyLiveChainFactsToTheExactPlannerIdentity() throws Exception {
    OffsetDateTime departure =
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0);
    OffsetDateTime arrival = departure.plusHours(4);
    UUID tripVehicle = UUID.randomUUID();
    UUID repositionedVehicle = UUID.randomUUID();
    UUID transferId =
        createAndConfirm(
            ORIGIN,
            DESTINATION,
            departure,
            arrival,
            tripVehicle,
            new TransferResourceRepositionRequest(
                repositionedVehicle,
                TransferResourceRepositionMode.TEMPORARY,
                arrival.plusDays(2)));

    String response =
        mvc.perform(
                get("/api/internal/logistics/v1/planning/vehicle-assignments")
                    .param("warehouseId", ORIGIN.toString())
                    .param("windowStart", departure.minusMinutes(1).toString())
                    .param("windowEnd", arrival.plusDays(1).toString())
                    .with(planner()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode rows = objectMapper.readTree(response);
    assertThat(rows.size()).isEqualTo(2);
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row.get("transferId").asText()).isEqualTo(transferId.toString());
              assertThat(row.get("sourceWarehouseId").asText()).isEqualTo(ORIGIN.toString());
              assertThat(row.get("destinationWarehouseId").asText())
                  .isEqualTo(DESTINATION.toString());
              assertThat(row.get("status").asText()).isEqualTo("PLANNED");
            });
    assertThat(values(rows, "mode"))
        .containsExactlyInAnyOrder("TRIP_ONLY", "TEMPORARY");

    String boundaryResponse =
        mvc.perform(
                get("/api/internal/logistics/v1/planning/vehicle-assignments")
                    .param("warehouseId", DESTINATION.toString())
                    .param("windowStart", arrival.plusMinutes(40).toString())
                    .param("windowEnd", arrival.plusHours(1).toString())
                    .with(planner()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(values(objectMapper.readTree(boundaryResponse), "mode"))
        .containsExactlyInAnyOrder("TRIP_ONLY", "TEMPORARY");

    mvc.perform(
            get("/api/internal/logistics/v1/planning/vehicle-assignments")
                .param("warehouseId", ORIGIN.toString())
                .param("windowStart", arrival.toString())
                .param("windowEnd", arrival.toString())
                .with(planner()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/internal/logistics/v1/planning/vehicle-assignments")
                .param("warehouseId", ORIGIN.toString())
                .param("windowStart", departure.toString())
                .param("windowEnd", arrival.toString())
                .with(user()))
        .andExpect(status().isForbidden());

    vehicleAssignments.cancelBeforeStart(transferId);
    String afterCancellation =
        mvc.perform(
                get("/api/internal/logistics/v1/planning/vehicle-assignments")
                    .param("warehouseId", ORIGIN.toString())
                    .param("windowStart", departure.toString())
                    .param("windowEnd", arrival.plusDays(1).toString())
                    .with(planner()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(objectMapper.readTree(afterCancellation).size()).isZero();
  }

  @Test
  void chainsPermanentPlacementsOnlyAtArrivalAndClosesProjectionAncestry() throws Exception {
    OffsetDateTime firstDeparture =
        OffsetDateTime.now(ZoneOffset.UTC)
            .plusDays(1)
            .withHour(8)
            .withMinute(0)
            .withSecond(0)
            .withNano(0);
    OffsetDateTime firstArrival = firstDeparture.plusHours(4);
    UUID vehicleId = UUID.randomUUID();
    UUID firstTransfer =
        createAndConfirm(
            ORIGIN,
            DESTINATION,
            firstDeparture,
            firstArrival,
            vehicleId,
            new TransferResourceRepositionRequest(
                vehicleId, TransferResourceRepositionMode.PERMANENT, null));
    vehicleAssignments.beginTransit(firstTransfer);
    vehicleAssignments.arrive(firstTransfer);
    OffsetDateTime nextDeparture = firstArrival.plusDays(1);
    OffsetDateTime nextArrival = nextDeparture.plusHours(3);

    assertThatThrownBy(
            () ->
                createAndConfirm(
                    ORIGIN,
                    THIRD_WAREHOUSE,
                    nextDeparture,
                    nextArrival,
                    vehicleId,
                    new TransferResourceRepositionRequest(
                        vehicleId, TransferResourceRepositionMode.PERMANENT, null)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("VEHICLE_OPERATIONAL_ASSIGNMENT_OVERLAP");

    UUID successor =
        createAndConfirm(
            DESTINATION,
            THIRD_WAREHOUSE,
            nextDeparture,
            nextArrival,
            vehicleId,
            new TransferResourceRepositionRequest(
                vehicleId, TransferResourceRepositionMode.PERMANENT, null));

    assertThat(
            jdbc.queryForList(
                """
                select transfer_id, status, effective_until
                from vehicle_operational_assignment
                where vehicle_id=?
                order by travel_starts_at
                """,
                vehicleId))
        .satisfiesExactly(
            first -> {
              assertThat(first.get("transfer_id")).isEqualTo(firstTransfer);
              assertThat(first.get("status")).isEqualTo("ACTIVE");
              assertThat(first.get("effective_until")).isNull();
            },
            second -> {
              assertThat(second.get("transfer_id")).isEqualTo(successor);
              assertThat(second.get("status")).isEqualTo("PLANNED");
              assertThat(second.get("effective_until")).isNull();
            });

    assertThatThrownBy(
            () ->
                createAndConfirm(
                    THIRD_WAREHOUSE,
                    FOURTH_WAREHOUSE,
                    nextDeparture.plusHours(1),
                    nextArrival.plusHours(1),
                    vehicleId,
                    new TransferResourceRepositionRequest(
                        vehicleId, TransferResourceRepositionMode.PERMANENT, null)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("VEHICLE_OPERATIONAL_ASSIGNMENT_OVERLAP");

    vehicleAssignments.cancelBeforeStart(successor);
    assertThat(
            jdbc.queryForObject(
                "select status from vehicle_operational_assignment where transfer_id=?",
                String.class,
                firstTransfer))
        .isEqualTo("ACTIVE");

    UUID arrivingSuccessor =
        createAndConfirm(
            DESTINATION,
            THIRD_WAREHOUSE,
            nextDeparture,
            nextArrival,
            vehicleId,
            new TransferResourceRepositionRequest(
                vehicleId, TransferResourceRepositionMode.PERMANENT, null));
    vehicleAssignments.beginTransit(arrivingSuccessor);
    vehicleAssignments.arrive(arrivingSuccessor);

    assertThat(
            jdbc.queryForObject(
                "select status from vehicle_operational_assignment where transfer_id=?",
                String.class,
                firstTransfer))
        .isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "select effective_until from vehicle_operational_assignment where transfer_id=?",
                OffsetDateTime.class,
                firstTransfer))
        .isEqualTo(nextArrival.plusMinutes(40));
    assertThat(
            jdbc.queryForObject(
                "select status from vehicle_operational_assignment where transfer_id=?",
                String.class,
                arrivingSuccessor))
        .isEqualTo("ACTIVE");

    String ancestryResponse =
        mvc.perform(
                get("/api/internal/logistics/v1/planning/vehicle-assignments")
                    .param("warehouseId", ORIGIN.toString())
                    .param("windowStart", nextDeparture.minusHours(1).toString())
                    .param("windowEnd", nextArrival.plusDays(1).toString())
                    .with(planner()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode ancestry = objectMapper.readTree(ancestryResponse);
    assertThat(ancestry.size()).isEqualTo(1);
    assertThat(ancestry.get(0).get("transferId").asText())
        .isEqualTo(arrivingSuccessor.toString());
    assertThat(ancestry.get(0).get("sourceWarehouseId").asText())
        .isEqualTo(DESTINATION.toString());
    assertThat(ancestry.get(0).get("destinationWarehouseId").asText())
        .isEqualTo(THIRD_WAREHOUSE.toString());
    assertThat(ancestry.get(0).get("status").asText()).isEqualTo("ACTIVE");
  }

  private UUID createAndConfirm(
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      OffsetDateTime departure,
      OffsetDateTime arrival,
      UUID tripVehicleId,
      TransferResourceRepositionRequest vehicleReposition) {
    TransferPlanRequest plan =
        new TransferPlanRequest(
            departure,
            arrival,
            null,
            null,
            tripVehicleId,
            new TransferResourceRepositionRequest(
                null, TransferResourceRepositionMode.NONE, null),
            vehicleReposition,
            List.of(),
            List.of());
    var created =
        documents.createTransfer(
            SUBJECT,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateTransferRequest(
                sourceWarehouseId,
                destinationWarehouseId,
                departure.toLocalDate(),
                List.of(),
                List.of(),
                plan));
    UUID confirmationKey = UUID.randomUUID();
    var admission =
        warehouseLifecycle.disabledTicket(
            SUBJECT,
            "CONFIRM_TRANSFER_PLAN",
            confirmationKey,
            List.of(
                new AdmissionRequirement(sourceWarehouseId, WarehouseOperationDirection.OUTGOING),
                new AdmissionRequirement(
                    destinationWarehouseId, WarehouseOperationDirection.INCOMING)));
    documents.confirmTransferPlan(
        SUBJECT,
        confirmationKey,
        UUID.randomUUID(),
        created.response().id(),
        created.response().version(),
        admission);
    return created.response().id();
  }

  private static JwtRequestPostProcessor planner() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(
            value ->
                value
                    .subject("logistics-planner")
                    .audience(List.of("rwms-services"))
                    .claim("principal_type", "SERVICE")
                    .claim("client_id", "logistics-planner")
                    .claim("scope", "logistics.planning"));
  }

  private static JwtRequestPostProcessor user() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(
            value ->
                value
                    .subject(SUBJECT.toString())
                    .audience(List.of("rwms-services"))
                    .claim("principal_type", "USER")
                    .claim("client_id", "rwms-panel")
                    .claim("scope", "rwms.read"));
  }

  private static List<String> values(JsonNode rows, String field) {
    List<String> values = new ArrayList<>();
    rows.forEach(row -> values.add(row.get(field).asText()));
    return values;
  }
}
