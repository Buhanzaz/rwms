package dev.buhanzaz.rwms.maintenance.api;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.maintenance.task-reconciliation.initial-delay=1h",
      "rwms.maintenance.task-reconciliation.delay=1h",
      "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://auth.test",
      "rwms.cors.allowed-origins=http://panel.test"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MaintenanceRepairLeaseHttpIntegrationTest {
  private static final UUID SUBJECT_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired MaintenanceRepairRepository repairs;
  @Autowired MaintenanceApplicationService service;
  @Autowired PlatformTransactionManager transactionManager;

  @MockitoBean MaintenanceDependencyGateway dependencies;

  @BeforeEach
  void resetDatabase() {
    jdbc.execute(
        """
        truncate table
          catalog_version,
          maintenance_estimate,
          maintenance_repair,
          media_fact_projection,
          rental_item_fact_projection,
          operation_lease_fact_projection,
          repair_capacity_settings,
          maintenance_idempotency_record,
          integration_reconciliation,
          event_stream_head
        cascade
        """);
    reset(dependencies);
    when(dependencies.preflightMaintenanceRouting(any(UUID.class), anyList()))
        .thenAnswer(
            invocation -> {
              List<MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
                  invocation.getArgument(1);
              return new MaintenanceDependencyGateway.RoutingPreflight(
                  invocation.getArgument(0),
                  true,
                  List.of(),
                  List.of(),
                  List.of(),
                  requirements.stream()
                      .map(
                          requirement ->
                              new MaintenanceDependencyGateway.RoutingQueueSnapshot(
                                  requirement.queueDefinitionId(),
                                  requirement.queueDefinitionId(),
                                  requirement.queueDefinitionId().toString(),
                                  requirement.type()))
                      .toList());
            });
  }

  @Test
  void acceptanceRejectsACommandWithoutPhotos() throws Exception {
    RepairFixture fixture = pendingAcceptanceRepair(true, Duration.ofMinutes(15));

    mvc.perform(
            post("/api/maintenance/v1/repairs/{id}/accept", fixture.repairId())
                .queryParam("warehouseId", fixture.warehouseId().toString())
                .header("Idempotency-Key", UUID.randomUUID())
                .with(userJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new RepairDecisionRequest(fixture.version(), "accepted", List.of()))))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);
    verifyNoInteractions(dependencies);
  }

  @Test
  void acceptanceRenewsNearExpiryLeaseAndReturnsCanonicalSuccess() throws Exception {
    RepairFixture fixture = pendingAcceptanceRepair(true);
    OffsetDateTime renewedExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15);
    when(dependencies.renewLease(
            any(),
            eq(fixture.leaseId()),
            eq(fixture.leaseVersion()),
            eq(fixture.fencingToken()),
            eq("MAINTENANCE_REPAIR"),
            eq(fixture.repairId().toString())))
        .thenReturn(fixture.renewedLease(renewedExpiry));

    mvc.perform(
            post("/api/maintenance/v1/repairs/{id}/accept", fixture.repairId())
                .queryParam("warehouseId", fixture.warehouseId().toString())
                .header("Idempotency-Key", UUID.randomUUID())
                .with(userJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        acceptanceRequest(fixture, "accepted"))))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.repair.id").value(fixture.repairId().toString()))
        .andExpect(jsonPath("$.repair.acceptanceState").value("ACCEPTED"))
        .andExpect(jsonPath("$.repair.lease.leaseId").value(fixture.leaseId().toString()))
        .andExpect(jsonPath("$.repair.lease.fencingToken").value(fixture.fencingToken()))
        .andExpect(
            jsonPath("$.repair.lease.reconciliationState")
                .value("RECONCILIATION_REQUIRED"))
        .andExpect(jsonPath("$.affectedSourceRepairs").isEmpty())
        .andExpect(jsonPath("$.delivery.state").value("RETRY_PENDING"));

    assertThat(repairs.findById(fixture.repairId()).orElseThrow())
        .satisfies(
            repair -> {
              assertThat(repair.getAcceptanceState()).isEqualTo(RepairAcceptanceState.ACCEPTED);
              assertThat(repair.getLeaseVersion()).isEqualTo(fixture.leaseVersion() + 1);
              assertThat(repair.getLeaseExpiresAt())
                  .isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10));
            });
    verify(dependencies)
        .renewLease(
            any(),
            eq(fixture.leaseId()),
            eq(fixture.leaseVersion()),
            eq(fixture.fencingToken()),
            eq("MAINTENANCE_REPAIR"),
            eq(fixture.repairId().toString()));
  }

  @Test
  void acceptanceWithFreshLeasePersistsAfterReloadWithoutDependencyCall() throws Exception {
    RepairFixture fixture = pendingAcceptanceRepair(true, Duration.ofMinutes(15));

    mvc.perform(
            post("/api/maintenance/v1/repairs/{id}/accept", fixture.repairId())
                .queryParam("warehouseId", fixture.warehouseId().toString())
                .header("Idempotency-Key", UUID.randomUUID())
                .with(userJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        acceptanceRequest(fixture, "accepted"))))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.repair.id").value(fixture.repairId().toString()))
        .andExpect(jsonPath("$.repair.acceptanceState").value("ACCEPTED"))
        .andExpect(jsonPath("$.affectedSourceRepairs").isEmpty())
        .andExpect(jsonPath("$.delivery.state").value("RETRY_PENDING"));

    assertThat(repairs.findById(fixture.repairId()).orElseThrow())
        .satisfies(
            repair -> {
              assertThat(repair.getAcceptanceState()).isEqualTo(RepairAcceptanceState.ACCEPTED);
              assertThat(repair.getLeaseVersion()).isEqualTo(fixture.leaseVersion());
              assertThat(repair.getLeaseReconciliationState())
                  .isEqualTo("RECONCILIATION_REQUIRED");
            });
    assertThat(
            jdbc.queryForObject(
                "select acceptance_state from maintenance_repair where id=?",
                String.class,
                fixture.repairId()))
        .isEqualTo("ACCEPTED");
    verifyNoLeaseRenewal();
  }

  @Test
  void acceptanceOfCompletedReworkTaskCascadesToSourceAndSurvivesReload() throws Exception {
    RepairFixture source = pendingAcceptanceRepair(true, Duration.ofMinutes(15));
    JsonNode created =
        mapper.readTree(
            mvc.perform(
                    post("/api/maintenance/v1/repairs/{id}/reworks", source.repairId())
                        .queryParam("warehouseId", source.warehouseId().toString())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(reworkRequest(source.version()))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    UUID reworkId = UUID.fromString(created.get("id").asText());
    long reworkVersion = created.get("version").asLong();

    mvc.perform(
            post("/api/maintenance/v1/repairs/{id}/plan", reworkId)
                .queryParam("warehouseId", source.warehouseId().toString())
                .header("Idempotency-Key", UUID.randomUUID())
                .with(userJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new QueueRepairRequest(reworkVersion, 2))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repair.executionState").value("QUEUED"))
        .andExpect(jsonPath("$.affectedSourceRepairs[0].acceptanceState").value("IN_REWORK"));

    UUID queueEntryId = UUID.randomUUID();
    jdbc.update(
        "update repair_stage set external_queue_entry_id=? where repair_id=?",
        queueEntryId,
        reworkId);
    MaintenanceRepair queuedRework = repairs.findById(reworkId).orElseThrow();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                service.applyInboundTaskOutcome(
                    UUID.randomUUID(),
                    "task-board.queue-entry.completed.v1",
                    queuedRework.getExternalTaskId(),
                    queueEntryId,
                    1,
                    OffsetDateTime.now(ZoneOffset.UTC)));

    MaintenanceRepair completedRework = repairs.findById(reworkId).orElseThrow();
    assertThat(completedRework.getAcceptanceState()).isEqualTo(RepairAcceptanceState.PENDING);
    assertThat(repairs.findById(source.repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);

    mvc.perform(
            post("/api/maintenance/v1/repairs/{id}/accept", reworkId)
                .queryParam("warehouseId", source.warehouseId().toString())
                .header("Idempotency-Key", UUID.randomUUID())
                .with(userJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        acceptanceRequest(
                            new RepairFixture(
                                reworkId,
                                completedRework.getVersion(),
                                source.warehouseId(),
                                source.rentalItemId(),
                                completedRework.getLeaseId(),
                                completedRework.getLeaseVersion(),
                                completedRework.getFencingToken()),
                            "accepted after rework"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repair.id").value(reworkId.toString()))
        .andExpect(jsonPath("$.repair.acceptanceState").value("ACCEPTED"))
        .andExpect(jsonPath("$.affectedSourceRepairs[0].id").value(source.repairId().toString()))
        .andExpect(jsonPath("$.affectedSourceRepairs[0].acceptanceState").value("ACCEPTED"));

    assertThat(repairs.findById(reworkId).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.ACCEPTED);
    assertThat(repairs.findById(source.repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.ACCEPTED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from integration_reconciliation "
                    + "where repair_id=? and operation_type='ACCEPT_TO_FREE'",
                Integer.class,
                reworkId))
        .isOne();
    verifyNoLeaseRenewal();
  }

  @Test
  void reworkQueueRenewsNearExpiryRootLeaseAndReturnsCanonicalSuccess() throws Exception {
    RepairFixture source = pendingAcceptanceRepair(true);
    OffsetDateTime renewedExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15);
    when(dependencies.renewLease(
            any(),
            eq(source.leaseId()),
            eq(source.leaseVersion()),
            eq(source.fencingToken()),
            eq("MAINTENANCE_REPAIR"),
            eq(source.repairId().toString())))
        .thenReturn(source.renewedLease(renewedExpiry));

    JsonNode created =
        mapper.readTree(
            mvc.perform(
                    post("/api/maintenance/v1/repairs/{id}/reworks", source.repairId())
                        .queryParam("warehouseId", source.warehouseId().toString())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(reworkRequest(source.version()))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.kind").value("REWORK"))
                .andExpect(jsonPath("$.executionState").value("DRAFT"))
                .andExpect(jsonPath("$.rootRepairId").value(source.repairId().toString()))
                .andExpect(jsonPath("$.sourceRepairId").value(source.repairId().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString());
    UUID reworkId = UUID.fromString(created.get("id").asText());
    long reworkVersion = created.get("version").asLong();

    mvc.perform(
            post("/api/maintenance/v1/repairs/{id}/plan", reworkId)
                .queryParam("warehouseId", source.warehouseId().toString())
                .header("Idempotency-Key", UUID.randomUUID())
                .with(userJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new QueueRepairRequest(reworkVersion, 2))))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.repair.id").value(reworkId.toString()))
        .andExpect(jsonPath("$.repair.kind").value("REWORK"))
        .andExpect(jsonPath("$.repair.executionState").value("QUEUED"))
        .andExpect(jsonPath("$.repair.lease.leaseId").value(source.leaseId().toString()))
        .andExpect(jsonPath("$.repair.lease.fencingToken").value(source.fencingToken()))
        .andExpect(jsonPath("$.affectedSourceRepairs[0].id").value(source.repairId().toString()))
        .andExpect(jsonPath("$.affectedSourceRepairs[0].acceptanceState").value("IN_REWORK"))
        .andExpect(jsonPath("$.delivery.state").value("RETRY_PENDING"));

    assertThat(repairs.findById(source.repairId()).orElseThrow())
        .satisfies(
            root -> {
              assertThat(root.getAcceptanceState()).isEqualTo(RepairAcceptanceState.IN_REWORK);
              assertThat(root.getLeaseVersion()).isEqualTo(source.leaseVersion() + 1);
              assertThat(root.getLeaseExpiresAt())
                  .isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10));
            });
    assertThat(repairs.findById(reworkId).orElseThrow())
        .satisfies(
            rework -> {
              assertThat(rework.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
              assertThat(rework.getLeaseId()).isEqualTo(source.leaseId());
              assertThat(rework.getLeaseVersion()).isEqualTo(source.leaseVersion() + 1);
              assertThat(rework.getLeaseExpiresAt())
                  .isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10));
            });
    verify(dependencies)
        .renewLease(
            any(),
            eq(source.leaseId()),
            eq(source.leaseVersion()),
            eq(source.fencingToken()),
            eq("MAINTENANCE_REPAIR"),
            eq(source.repairId().toString()));
  }

  @Test
  void acceptanceWithoutLeaseReturnsCanonicalConflictProblem() throws Exception {
    RepairFixture fixture = pendingAcceptanceRepair(false);
    UUID correlationId = UUID.randomUUID();

    mvc.perform(
            post("/api/maintenance/v1/repairs/{id}/accept", fixture.repairId())
                .queryParam("warehouseId", fixture.warehouseId().toString())
                .header("Idempotency-Key", UUID.randomUUID())
                .header(CorrelationIdFilter.HEADER_NAME, correlationId)
                .with(userJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        acceptanceRequest(fixture, "accepted"))))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, correlationId.toString()))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("MAINTENANCE_LEASE_CONFLICT"))
        .andExpect(
            jsonPath("$.detail")
                .value("Repair does not have an active renewable lease snapshot"))
        .andExpect(jsonPath("$.correlation.correlationId").value(correlationId.toString()));

    verifyNoLeaseRenewal();
  }

  @Test
  void unrenewableReworkLeaseReturnsCanonicalDependencyProblem() throws Exception {
    RepairFixture source = pendingAcceptanceRepair(true);
    JsonNode created =
        mapper.readTree(
            mvc.perform(
                    post("/api/maintenance/v1/repairs/{id}/reworks", source.repairId())
                        .queryParam("warehouseId", source.warehouseId().toString())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(reworkRequest(source.version()))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    UUID reworkId = UUID.fromString(created.get("id").asText());
    long reworkVersion = created.get("version").asLong();
    when(dependencies.renewLease(
            any(),
            eq(source.leaseId()),
            eq(source.leaseVersion()),
            eq(source.fencingToken()),
            eq("MAINTENANCE_REPAIR"),
            eq(source.repairId().toString())))
        .thenThrow(
            new MaintenanceDependencyException(
                HttpStatus.CONFLICT, "asset lease is no longer renewable"));
    UUID correlationId = UUID.randomUUID();

    mvc.perform(
            post("/api/maintenance/v1/repairs/{id}/plan", reworkId)
                .queryParam("warehouseId", source.warehouseId().toString())
                .header("Idempotency-Key", UUID.randomUUID())
                .header(CorrelationIdFilter.HEADER_NAME, correlationId)
                .with(userJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new QueueRepairRequest(reworkVersion, 3))))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, correlationId.toString()))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("MAINTENANCE_DEPENDENCY_UNAVAILABLE"))
        .andExpect(jsonPath("$.detail").value("asset lease is no longer renewable"))
        .andExpect(jsonPath("$.correlation.correlationId").value(correlationId.toString()));

    assertThat(repairs.findById(reworkId).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.DRAFT);
  }

  private RepairFixture pendingAcceptanceRepair(boolean withLease) {
    return pendingAcceptanceRepair(withLease, Duration.ofMinutes(4));
  }

  private RepairFixture pendingAcceptanceRepair(boolean withLease, Duration leaseTtl) {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID leaseId = UUID.randomUUID();
    long leaseVersion = 7;
    long fencingToken = 19;
    MaintenanceRepair repair =
        MaintenanceRepair.primary(
            warehouseId,
            rentalItemId,
            11,
            null,
            RepairOrigin.DIRECT_REPAIR,
            LocalDate.of(2026, 7, 28),
            null,
            "{}");
    repair.queue(
        leaseId,
        leaseVersion,
        fencingToken,
        OffsetDateTime.now(ZoneOffset.UTC).plus(leaseTtl));
    repair.completeForAcceptance();
    repair = repairs.saveAndFlush(repair);
    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('REPAIR',?,?,?,clock_timestamp())
        """,
        repair.getId().toString(),
        repair.getVersion(),
        UUID.randomUUID());
    if (!withLease) {
      jdbc.update(
          """
          update maintenance_repair
          set lease_id=null,
              lease_version=null,
              fencing_token=null,
              lease_expires_at=null,
              lease_reconciliation_state='NOT_REQUIRED'
          where id=?
          """,
          repair.getId());
    }
    return new RepairFixture(
        repair.getId(),
        repair.getVersion(),
        warehouseId,
        rentalItemId,
        leaseId,
        leaseVersion,
        fencingToken);
  }

  private static CreateReworkRequest reworkRequest(long sourceVersion) {
    PlanStageInput stage =
        new PlanStageInput(
            UUID.randomUUID(),
            dev.buhanzaz.rwms.maintenance.domain.RepairStageKind.REPAIR_WORK,
            0,
            new RoutingSnapshot(UUID.randomUUID(), "INTERNAL_WORKS", "REPAIR"),
            List.of(),
            null,
            "",
            null);
    return new CreateReworkRequest(
        sourceVersion, "corrective work", List.of(), List.of(stage), List.of());
  }

  private static RequestPostProcessor userJwt() {
    return jwt()
        .jwt(
            token ->
                token.subject(SUBJECT_ID.toString())
                    .audience(List.of("rwms-services"))
                    .claim("principal_type", "USER")
                    .claim("scope", "rwms.read rwms.write")
                    .claim("global_role", "WMS_ADMIN")
                    .claim("warehouse_access", List.of(Map.of())))
        .authorities(
            new SimpleGrantedAuthority("SCOPE_rwms.read"),
            new SimpleGrantedAuthority("SCOPE_rwms.write"));
  }

  private RepairDecisionRequest acceptanceRequest(RepairFixture fixture, String comment) {
    UUID mediaId = UUID.randomUUID();
    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        service.applyInboundMediaFact(
            mediaId,
            1,
            "MAINTENANCE_ACCEPTANCE",
            fixture.repairId(),
            fixture.warehouseId(),
            "READY",
            "{}",
            1));
    return new RepairDecisionRequest(
        fixture.version(),
        comment,
        List.of(new MediaReferenceInput(mediaId, 1L)));
  }

  private void verifyNoLeaseRenewal() {
    verify(dependencies, never())
        .renewLease(any(), any(), anyLong(), anyLong(), anyString(), anyString());
  }

  private record RepairFixture(
      UUID repairId,
      long version,
      UUID warehouseId,
      UUID rentalItemId,
      UUID leaseId,
      long leaseVersion,
      long fencingToken) {
    MaintenanceDependencyGateway.LeaseSnapshot renewedLease(OffsetDateTime expiresAt) {
      return new MaintenanceDependencyGateway.LeaseSnapshot(
          leaseId,
          leaseVersion + 1,
          rentalItemId,
          "MAINTENANCE_REPAIR",
          repairId,
          fencingToken,
          expiresAt);
    }
  }
}
