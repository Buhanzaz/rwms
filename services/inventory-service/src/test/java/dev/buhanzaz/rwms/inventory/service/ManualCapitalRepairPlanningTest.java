package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FinalPlanEntryUpdate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.domain.FinalPlanTargetKind;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryAssetOutcomeStatus;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionKind;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlan;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanEntry;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationIntent;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPlanningSettingsRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationAttemptRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationAttemptResultRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Verifies that inventory planning and publication retain the manager's capital-repair choice. */
class ManualCapitalRepairPlanningTest {
  private static final String PLAN_HASH = "a".repeat(64);
  private static final String FINAL_PLAN_HASH = "b".repeat(64);

  @Test
  void finalPlanUpdateRoutesAfterRentWorkDirectlyToRepairAndRetainsCapitalChoice() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    InventoryFinding finding = stagedFinding(inventoryId, findingId, 2);
    FindingPlanSnapshot snapshot = frozenPlan(inventoryId, findingId, 2);
    InventoryFindingRepository findings = mock(InventoryFindingRepository.class);
    FindingPlanSnapshotRepository snapshots = mock(FindingPlanSnapshotRepository.class);
    InventoryFrozenPlanFingerprint fingerprint = mock(InventoryFrozenPlanFingerprint.class);
    InventorySession session = mock(InventorySession.class);
    when(session.getId()).thenReturn(inventoryId);
    when(findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(inventoryId))
        .thenReturn(List.of(finding));
    when(snapshots.findFirstByFindingIdAndFingerprintOrderByFindingRevisionDesc(
            findingId, PLAN_HASH))
        .thenReturn(Optional.of(snapshot));
    when(fingerprint.sha256(org.mockito.ArgumentMatchers.any(JsonNode.class)))
        .thenReturn(PLAN_HASH);
    InventoryCabinDispositionService cabinDispositions =
        mock(InventoryCabinDispositionService.class);
    UUID assetId = finding.getAssetId();
    when(cabinDispositions.requireCompleted(session, List.of(finding)))
        .thenReturn(
            Map.of(
                findingId,
                new InventoryCabinDispositionService.DispositionSnapshot(
                    findingId,
                    2,
                    assetId,
                    7L,
                    InventoryCabinDispositionKind.LOCAL,
                    "{\"formerRental\":null}")));
    InventoryPlanningService service =
        planningService(findings, snapshots, fingerprint, cabinDispositions);

    List<InventoryPlanningService.FinalPlanDraft> updated =
        service.updateFinalPlanDrafts(
            session,
            List.of(
                new FinalPlanEntryUpdate(
                    findingId,
                    2,
                    0,
                    4,
                    false,
                    null,
                    LocalDate.parse("2026-08-20"),
                    null)));
    InventoryFinalPlanEntry persisted = service.finalPlanEntry(inventoryId, 3, updated.getFirst());

    assertThat(persisted.getTargetKind()).isEqualTo(FinalPlanTargetKind.REPAIR);
    assertThat(persisted.isForceCapitalRepair()).isTrue();
    assertThat(service.finalPlanEntryView(persisted).forceCapitalRepair()).isTrue();
  }

  @Test
  void finalPlanPublicationPassesFrozenCapitalRepairChoiceToMaintenance() {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    FindingPlanSnapshot snapshot = frozenPlan(inventoryId, findingId, 2);
    InventoryFinalPlanEntry entry =
        new InventoryFinalPlanEntry(
            inventoryId,
            3,
            findingId,
            2,
            assetId,
            7L,
            PLAN_HASH,
            true,
            FinalPlanTargetKind.REPAIR,
            0,
            4,
            false,
            true,
            null,
            LocalDate.parse("2026-08-20"),
            "[]",
            null);
    InventorySession session = mock(InventorySession.class);
    when(session.getId()).thenReturn(inventoryId);
    when(session.getWarehouseId()).thenReturn(warehouseId);
    when(session.getCompletedAt()).thenReturn(OffsetDateTime.parse("2026-08-19T10:00:00Z"));
    InventoryPublicationIntent intent =
        InventoryPublicationIntent.readyForOutcome(
            inventoryId,
            findingId,
            2,
            3,
            FINAL_PLAN_HASH,
            FinalPlanTargetKind.REPAIR,
            InventoryAssetOutcomeStatus.CAPITAL_REPAIR,
            """
            {"presence":"PRESENT","value":{"rentalType":" БК-2 ",
             "dimensions":"2.4x6","finishing":"ЛДСП","category":"Обычная",
             "characteristics":["Электрика КК, Пластиковое окно","Электрика КК"],
             "linoleum":null}}
            """);
    String frozenPassport = intent.getAssetPassportObservation();
    InventoryFinalPlan plan = mock(InventoryFinalPlan.class);
    when(plan.getFinalPlanVersion()).thenReturn(3L);
    when(plan.getFinalPlanSha256()).thenReturn(FINAL_PLAN_HASH);
    InventoryFinding finding = mock(InventoryFinding.class);
    when(finding.getId()).thenReturn(findingId);
    when(finding.getMaintenancePlanFingerprintSha256()).thenReturn(PLAN_HASH);
    InventoryFinalPlanRepository finalPlans = mock(InventoryFinalPlanRepository.class);
    InventoryFinalPlanEntryRepository entries = mock(InventoryFinalPlanEntryRepository.class);
    InventoryFindingRepository findings = mock(InventoryFindingRepository.class);
    FindingPlanSnapshotRepository snapshots = mock(FindingPlanSnapshotRepository.class);
    when(finalPlans.findById(inventoryId)).thenReturn(Optional.of(plan));
    when(entries.findByInventoryIdAndFinalPlanVersionAndFindingId(inventoryId, 3, findingId))
        .thenReturn(Optional.of(entry));
    when(entries.findByInventoryIdAndFinalPlanVersionOrderByOrderAscFindingIdAsc(inventoryId, 3))
        .thenReturn(List.of(entry));
    when(findings.findByIdAndInventoryIdAndMembershipActiveTrue(findingId, inventoryId))
        .thenReturn(Optional.of(finding));
    when(snapshots.findFirstByFindingIdAndFingerprintOrderByFindingRevisionDesc(
            findingId, PLAN_HASH))
        .thenReturn(Optional.of(snapshot));
    InventoryPlanningService planning = mock(InventoryPlanningService.class);
    when(planning.frozenPlanMedia(snapshot))
        .thenReturn(JsonMapper.builder().build().createArrayNode());
    InventoryPublicationService service =
        publicationService(finalPlans, entries, findings, snapshots, planning);

    JsonNode request = service.finalPlanPublicationRequest(session, intent);

    assertThat(request.path("assetOutcome").path("desiredStatus").asText())
        .isEqualTo("CAPITAL_REPAIR");
    assertThat(request.path("assetOutcome").path("passportObservation").path("presence").asText())
        .isEqualTo("PRESENT");
    assertThat(
            request
                .path("assetOutcome")
                .path("passportObservation")
                .path("value")
                .path("rentalType")
                .asText())
        .isEqualTo("БК-2");
    assertThat(
            request
                .path("assetOutcome")
                .path("passportObservation")
                .path("value")
                .path("characteristics")
                .toString())
        .isEqualTo("[\"Электрика КК\",\"Пластиковое окно\"]");
    assertThat(request.path("assetOutcome").path("passportObservationSha256").asText())
        .matches("^[0-9a-f]{64}$");
    assertThat(request.path("maintenance").path("forceCapitalRepair").isBoolean()).isTrue();
    assertThat(request.path("maintenance").path("forceCapitalRepair").booleanValue()).isTrue();

    intent.requeueForAuthoritativeOutcome(
        2,
        3,
        FINAL_PLAN_HASH,
        FinalPlanTargetKind.REPAIR,
        InventoryAssetOutcomeStatus.CAPITAL_REPAIR);
    when(finding.getPassportObservationState()).thenReturn(ObservationPresence.ABSENT);
    when(finding.getPassportObservation()).thenReturn(null);
    JsonNode reapplied = service.finalPlanPublicationRequest(session, intent);
    assertThat(intent.getAssetPassportObservation()).isEqualTo(frozenPassport);
    assertThat(intent.getOutcomeReapplicationNo()).isEqualTo(1);
    assertThat(reapplied.path("assetOutcome").path("passportObservation"))
        .isEqualTo(request.path("assetOutcome").path("passportObservation"));
    assertThat(reapplied.path("assetOutcome").path("passportObservationSha256"))
        .isEqualTo(request.path("assetOutcome").path("passportObservationSha256"));
    verify(finding, never()).getPassportObservationState();
    verify(finding, never()).getPassportObservation();
  }

  private InventoryFinding stagedFinding(UUID inventoryId, UUID findingId, long revision) {
    InventoryFinding finding = mock(InventoryFinding.class);
    when(finding.getInventoryId()).thenReturn(inventoryId);
    when(finding.getId()).thenReturn(findingId);
    when(finding.getRevision()).thenReturn(revision);
    when(finding.getInspection()).thenReturn(InspectionState.WORK_STAGED);
    when(finding.getCurrentStatus()).thenReturn("AFTER_RENT");
    when(finding.getAssetId()).thenReturn(UUID.randomUUID());
    when(finding.getAssetVersion()).thenReturn(7L);
    when(finding.getMaintenancePlanFingerprintSha256()).thenReturn(PLAN_HASH);
    return finding;
  }

  private FindingPlanSnapshot frozenPlan(UUID inventoryId, UUID findingId, long revision) {
    return new FindingPlanSnapshot(
        findingId,
        revision,
        inventoryId,
        "AUTO",
        false,
        null,
        null,
        UUID.randomUUID(),
        PLAN_HASH,
        "{\"priority\":3,\"forceCapitalRepair\":true,\"lines\":[]}",
        true,
        2);
  }

  private InventoryPlanningService planningService(
      InventoryFindingRepository findings,
      FindingPlanSnapshotRepository snapshots,
      InventoryFrozenPlanFingerprint fingerprint,
      InventoryCabinDispositionService cabinDispositions) {
    return new InventoryPlanningService(
        mock(InventorySessionRepository.class),
        findings,
        mock(InventoryPlanningSettingsRepository.class),
        mock(InventoryFinalPlanRepository.class),
        mock(InventoryFinalPlanEntryRepository.class),
        mock(FindingMediaReferenceRepository.class),
        snapshots,
        mock(InventoryDependencyGateway.class),
        mock(InventoryIdempotencyPort.class),
        fingerprint,
        cabinDispositions,
        JsonMapper.builder().findAndAddModules().build(),
        mock(InventoryCanonicalJsonPort.class),
        mock(InventoryAuthorizer.class),
        mock(PlatformTransactionManager.class));
  }

  private InventoryPublicationService publicationService(
      InventoryFinalPlanRepository finalPlans,
      InventoryFinalPlanEntryRepository entries,
      InventoryFindingRepository findings,
      FindingPlanSnapshotRepository snapshots,
      InventoryPlanningService planning) {
    JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    return new InventoryPublicationService(
        mock(InventorySessionRepository.class),
        findings,
        finalPlans,
        entries,
        mock(InventoryPublicationIntentRepository.class),
        mock(dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureReconciliationIntentRepository.class),
        mock(InventoryPublicationAttemptRepository.class),
        mock(InventoryPublicationAttemptResultRepository.class),
        snapshots,
        mock(FindingMediaReferenceRepository.class),
        mock(InventoryDependencyGateway.class),
        mock(InventoryPlanLogisticsReconciliationService.class),
        mock(InventoryEventStore.class),
        mock(InventoryIdempotencyPort.class),
        planning,
        mock(InventoryProjectionService.class),
        mapper,
        new InventoryCanonicalJsonService(mapper),
        mock(InventoryAuthorizer.class),
        mock(PlatformTransactionManager.class));
  }
}
