package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.Observation;

import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.LogisticsPlanningMode;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.mapper.InventorySessionMapper;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanLineRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanStageRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryExpectedItemRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureReconciliationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryMembershipMovementRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryValidationSnapshotRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exact read-only inputs for mutable-session and completed-session projections.
 *
 * <p>Frozen-plan repositories remain private in {@link InventoryPlanProjectionSupport}; this
 * support can read grouped plan facts but cannot access plan stores independently.
 */
abstract class InventoryProjectionWorkflowSupport extends InventoryPlanProjectionSupport {
  protected final InventorySessionRepository sessions;
  protected final InventoryFindingRepository findings;
  protected final InventoryFurnitureReconciliationIntentRepository furnitureReconciliations;
  protected final InventoryPublicationIntentRepository publications;
  protected final InventoryExpectedItemRepository expectedItems;
  protected final InventoryMembershipMovementRepository membershipMovements;
  protected final FindingMediaReferenceRepository mediaReferences;
  protected final InventoryValidationSnapshotRepository validationSnapshots;
  protected final InventorySessionMapper sessionMapper;
  protected final InventoryStatisticsService statisticsService;

  protected InventoryProjectionWorkflowSupport(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryFurnitureReconciliationIntentRepository furnitureReconciliations,
      InventoryPublicationIntentRepository publications,
      InventoryExpectedItemRepository expectedItems,
      InventoryMembershipMovementRepository membershipMovements,
      FindingMediaReferenceRepository mediaReferences,
      InventoryValidationSnapshotRepository validationSnapshots,
      InventorySessionMapper sessionMapper,
      InventoryStatisticsService statisticsService,
      FindingPlanSnapshotRepository planSnapshots,
      FindingPlanLineRepository planLines,
      FindingPlanStageRepository planStages,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(planSnapshots, planLines, planStages, mapper, canonicalJson, authorizer, transactionManager);
    this.sessions = sessions;
    this.findings = findings;
    this.furnitureReconciliations = furnitureReconciliations;
    this.publications = publications;
    this.expectedItems = expectedItems;
    this.membershipMovements = membershipMovements;
    this.mediaReferences = mediaReferences;
    this.validationSnapshots = validationSnapshots;
    this.sessionMapper = sessionMapper;
    this.statisticsService = statisticsService;
  }

  protected OffsetDateTime terminalAt(InventorySession session) {
    return session.getCompletedAt() != null ? session.getCompletedAt() : session.getCancelledAt();
  }

  protected LogisticsPlanningMode nullableLogisticsPlanningMode(
      JsonNode value, String field, String name) {
    JsonNode result = value.get(field);
    if (result == null || result.isNull()) return null;
    if (!result.isTextual()) {
      throw new IllegalStateException("Persisted " + name + " is invalid");
    }
    try {
      return LogisticsPlanningMode.valueOf(result.stringValue());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Persisted " + name + " is invalid", exception);
    }
  }

  protected LocalDate nullableLocalDate(JsonNode value, String field, String name) {
    JsonNode result = value.get(field);
    if (result == null || result.isNull()) return null;
    if (!result.isTextual()) {
      throw new IllegalStateException("Persisted " + name + " is invalid");
    }
    try {
      return LocalDate.parse(result.stringValue());
    } catch (java.time.format.DateTimeParseException exception) {
      throw new IllegalStateException("Persisted " + name + " is invalid", exception);
    }
  }

  protected String tenantSnapshot(JsonNode passportSnapshot) {
    if (passportSnapshot == null || !passportSnapshot.isObject()) return null;
    return nullableText(passportSnapshot.get("tenant"));
  }

  protected String exactDecimal(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  protected Observation observation(ObservationPresence presence, String value) {
    return new Observation(presence, value == null ? null : read(value));
  }
}
