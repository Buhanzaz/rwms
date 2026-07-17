package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceReconciliation;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceReconciliationRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Typed JPA coordinator for durable post-commit asset and task-board effects. */
@Repository
public class MaintenanceReconciliationStore {
  static final int MAX_ATTEMPTS = 4;

  private final MaintenanceReconciliationRepository reconciliations;
  private final ObjectMapper mapper;

  public MaintenanceReconciliationStore(
      MaintenanceReconciliationRepository reconciliations, ObjectMapper mapper) {
    this.reconciliations = reconciliations;
    this.mapper = mapper;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(
      UUID repairId,
      String dependency,
      String operation,
      UUID idempotencyKey,
      Object payload) {
    enqueue(repairId, dependency, operation, idempotencyKey, payload, false);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueueRequired(
      UUID repairId,
      String dependency,
      String operation,
      UUID idempotencyKey,
      Object payload) {
    enqueue(repairId, dependency, operation, idempotencyKey, payload, true);
  }

  private void enqueue(
      UUID repairId,
      String dependency,
      String operation,
      UUID idempotencyKey,
      Object payload,
      boolean reviewRequired) {
    if (dependency == null || operation == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Reconciliation identity is required");
    }
    MaintenanceReconciliation existing =
        reconciliations
            .findByStableKeyForUpdate(dependency, operation, idempotencyKey)
            .orElse(null);
    if (existing != null) {
      try {
        existing.requireStableIdentity(repairId);
      } catch (IllegalArgumentException exception) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_IDEMPOTENCY_CONFLICT",
            "Stable reconciliation identity is bound to another repair");
      }
      rejectQuarantined(existing);
      return;
    }
    OffsetDateTime now = now();
    MaintenanceReconciliation created =
        reviewRequired
            ? MaintenanceReconciliation.reconciliationRequired(
                repairId, dependency, operation, idempotencyKey, write(payload), now)
            : MaintenanceReconciliation.pending(
                repairId, dependency, operation, idempotencyKey, write(payload), now);
    reconciliations.saveAndFlush(created);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public ResumeResult resumeQuarantined(
      UUID reconciliationId,
      long expectedReviewVersion,
      UUID reviewSubjectId,
      String reviewReason) {
    if (reconciliationId == null
        || expectedReviewVersion < 0
        || reviewSubjectId == null
        || reviewReason == null
        || reviewReason.isBlank()
        || reviewReason.trim().length() > 2000) {
      throw new IllegalArgumentException("Reviewed reconciliation resume metadata is invalid");
    }
    MaintenanceReconciliation reconciliation =
        reconciliations
            .findByIdForUpdate(reconciliationId)
            .orElseThrow(() -> new MaintenanceNotFoundException("Reconciliation record not found"));
    try {
      reconciliation.resume(expectedReviewVersion, reviewSubjectId, reviewReason, now());
      reconciliations.flush();
    } catch (IllegalArgumentException exception) {
      if ("REVIEW_VERSION".equals(exception.getMessage())) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_VERSION_CONFLICT", "Reconciliation review version conflict");
      }
      if ("REVIEW_STATE".equals(exception.getMessage())) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT",
            "Only a quarantined reconciliation can be resumed");
      }
      throw exception;
    }
    return resumeResult(reconciliation);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<WorkItem> lockNextDue() {
    return reconciliations
        .findDueForUpdateSkipLocked(MAX_ATTEMPTS, now(), PageRequest.of(0, 1))
        .stream()
        .findFirst()
        .map(this::workItem);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void confirmed(WorkItem item, Object response) {
    MaintenanceReconciliation reconciliation = requireLocked(item.id());
    try {
      reconciliation.confirm(item.attemptCount(), write(response), now());
      reconciliations.flush();
    } catch (IllegalArgumentException exception) {
      throw claimConflict();
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean failed(WorkItem item, RuntimeException failure) {
    MaintenanceReconciliation reconciliation = requireLocked(item.id());
    try {
      boolean quarantined =
          reconciliation.fail(
              item.attemptCount(), MAX_ATTEMPTS, failureCode(failure), now());
      reconciliations.flush();
      return quarantined;
    } catch (IllegalArgumentException exception) {
      throw claimConflict();
    }
  }

  private MaintenanceReconciliation requireLocked(UUID reconciliationId) {
    return reconciliations
        .findByIdForUpdate(reconciliationId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Reconciliation record not found"));
  }

  private WorkItem workItem(MaintenanceReconciliation value) {
    return new WorkItem(
        value.getId(),
        value.getRepairId(),
        value.getDependencyType(),
        value.getOperationType(),
        value.getIdempotencyKey(),
        value.getState(),
        value.getAttemptCount(),
        value.getNextAttemptAt(),
        read(value.getResponseSnapshot()));
  }

  private ResumeResult resumeResult(MaintenanceReconciliation value) {
    return new ResumeResult(
        value.getId(),
        value.getIdempotencyKey(),
        value.getState(),
        value.getReviewVersion(),
        value.getReviewSubjectId(),
        value.getReviewReason(),
        value.getReviewedAt());
  }

  private static void rejectQuarantined(MaintenanceReconciliation existing) {
    if ("QUARANTINED".equals(existing.getState())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_RECONCILIATION_QUARANTINED",
          "Stable reconciliation work is quarantined and requires reviewed resume");
    }
  }

  private static MaintenanceConflictException claimConflict() {
    return new MaintenanceConflictException(
        "MAINTENANCE_STATE_CONFLICT", "Reconciliation claim changed concurrently");
  }

  private static String failureCode(RuntimeException failure) {
    String value = failure.getClass().getSimpleName();
    return value.length() <= 64 ? value : value.substring(0, 64);
  }

  private JsonNode read(String value) {
    try {
      return mapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored reconciliation payload is invalid", exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Reconciliation payload cannot be serialized", exception);
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  public record WorkItem(
      UUID id,
      UUID repairId,
      String dependency,
      String operation,
      UUID idempotencyKey,
      String state,
      int attemptCount,
      OffsetDateTime nextAttemptAt,
      JsonNode payload) {}

  public record ResumeResult(
      UUID reconciliationId,
      UUID idempotencyKey,
      String state,
      long reviewVersion,
      UUID reviewSubjectId,
      String reviewReason,
      OffsetDateTime reviewedAt) {}
}
