package dev.buhanzaz.rwms.maintenance.disposition.application;

import dev.buhanzaz.rwms.maintenance.disposition.application.PropertyDispositionApplicationService.LeaseReleaseCommand;
import dev.buhanzaz.rwms.maintenance.disposition.application.PropertyDispositionApplicationService.ProcessingView;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Server-owned retry loop for prepared asset effects and worker-confirmed furniture movement. */
@Component
public class PropertyDispositionProcessor {
  private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);
  private static final Duration MOVEMENT_RECHECK = Duration.ofSeconds(30);
  private static final Duration RETRY_DELAY = Duration.ofSeconds(10);
  private static final String OWNER = "maintenance-property-disposition";

  private final PropertyDispositionProcessingStore processing;
  private final PropertyDispositionApplicationService dispositions;
  private final MaintenanceDependencyGateway dependencies;

  public PropertyDispositionProcessor(
      PropertyDispositionProcessingStore processing,
      PropertyDispositionApplicationService dispositions,
      MaintenanceDependencyGateway dependencies) {
    this.processing = processing;
    this.dispositions = dispositions;
    this.dependencies = dependencies;
  }

  @Scheduled(
      fixedDelayString = "${rwms.maintenance.property-disposition.delay:2s}",
      initialDelayString = "${rwms.maintenance.property-disposition.initial-delay:2s}")
  public void processOne() {
    processing.claimOne(OWNER, CLAIM_LEASE).ifPresent(this::process);
  }

  private void process(PropertyDispositionProcessingStore.Claim claim) {
    String phase = "READ";
    ProcessingView view = null;
    try {
      view = dispositions.processingView(claim.decisionId());
      phase = phase(view.state());
      switch (view.state()) {
        case APPROVED -> processApproved(claim, view);
        case MOVEMENT_PENDING -> processMovement(claim, view);
        case EFFECT_PENDING -> processEffect(claim, view);
        case EFFECTIVE -> processEffective(claim, view);
        case PENDING_APPROVAL, REJECTED, QUARANTINED -> processing.completed(claim, phase);
      }
    } catch (MaintenanceDependencyException exception) {
      handleDependencyFailure(claim, view, phase, exception);
    } catch (RuntimeException exception) {
      // Local stream fencing can race another processor/node. It is safe to re-read shortly;
      // a business-invalid remote response is handled above as a quarantined decision.
      if (claim.nextFailureExhaustsRetries()) {
        String detail = safeDetail(exception.getMessage());
        if (view == null) {
          processing.quarantined(claim, phase, "LOCAL_OR_UNKNOWN", detail);
        } else {
          quarantineExhausted(claim, view, phase, "LOCAL_OR_UNKNOWN", detail);
        }
      } else {
        processing.retryableFailure(
            claim,
            phase,
            "LOCAL_OR_UNKNOWN",
            safeDetail(exception.getMessage()),
            RETRY_DELAY);
      }
    }
  }

  private void processApproved(
      PropertyDispositionProcessingStore.Claim claim, ProcessingView view) {
    String phase = "PREPARE";
    MaintenanceDependencyGateway.PropertyDispositionFence fence = dependencies.preparePropertyDisposition(
        key(view.decisionId(), phase), view.decisionId(), view.preparation());
    if (!matchesPreparedFence(view, fence)) {
      quarantine(
          claim,
          view,
          phase,
          "PREPARE_FENCE_MISMATCH",
          "Asset prepare response does not match the approved property disposition");
      return;
    }
    if (view.requiresMovement()) {
      MaintenanceDependencyGateway.PropertyEquipmentMovementTask task =
          dependencies.createPropertyEquipmentMovementTask(
              key(view.decisionId(), "CREATE_MOVEMENT"), view.movementCommand());
      if (task == null || !view.warehouseId().equals(task.warehouseId())) {
        quarantine(
            claim,
            view,
            phase,
            "MOVEMENT_TASK_MISMATCH",
            "Created furniture movement task belongs to another warehouse");
        return;
      }
      if (!task.isOwnedByMaintenanceDisposition(view.decisionId())) {
        quarantine(
            claim,
            view,
            phase,
            "MOVEMENT_TASK_OWNER_MISMATCH",
            "Created furniture movement task belongs to another disposition");
        return;
      }
      if (task.failedTerminally()) {
        quarantine(
            claim,
            view,
            phase,
            "MOVEMENT_TASK_" + safeCode(task.terminalState()),
            "Created furniture movement task is already terminally failed");
        return;
      }
      dispositions.startMovement(view.decisionId(), task.id());
      if (task.completed()) {
        dispositions.completeMovement(view.decisionId());
      }
    } else {
      dispositions.startAssetEffect(view.decisionId());
    }
    processing.retrySoon(claim, phase);
  }

  private void processMovement(
      PropertyDispositionProcessingStore.Claim claim, ProcessingView view) {
    String phase = "MOVEMENT";
    if (view.movementTaskId() == null) {
      quarantine(claim, view, phase, "MOVEMENT_TASK_MISSING", "Movement-pending decision has no task ID");
      return;
    }
    MaintenanceDependencyGateway.PropertyEquipmentMovementTask task =
        dependencies.getPropertyEquipmentMovementTask(view.movementTaskId());
    if (!view.warehouseId().equals(task.warehouseId())) {
      quarantine(claim, view, phase, "MOVEMENT_TASK_MISMATCH", "Movement task belongs to another warehouse");
      return;
    }
    if (!task.isOwnedByMaintenanceDisposition(view.decisionId())) {
      quarantine(
          claim,
          view,
          phase,
          "MOVEMENT_TASK_OWNER_MISMATCH",
          "Furniture movement task belongs to another disposition");
      return;
    }
    if (task.completed()) {
      dispositions.completeMovement(view.decisionId());
      processing.retrySoon(claim, phase);
      return;
    }
    if (task.failedTerminally()) {
      quarantine(
          claim,
          view,
          phase,
          "MOVEMENT_TASK_" + safeCode(task.terminalState()),
          "Furniture movement did not complete");
      return;
    }
    processing.await(claim, phase, MOVEMENT_RECHECK);
  }

  private void processEffect(
      PropertyDispositionProcessingStore.Claim claim, ProcessingView view) {
    String phase = "APPLY";
    MaintenanceDependencyGateway.PropertyDispositionEffect effect =
        dependencies.applyPropertyDisposition(
            key(view.decisionId(), phase), view.decisionId(), view.movementTaskId());
    if (!view.decisionId().equals(effect.decisionId())
        || !view.preparation().assetId().equals(effect.assetId())
        || view.preparation().assetKind() != effect.assetKind()
        || view.preparation().disposition() != effect.disposition()) {
      quarantine(claim, view, phase, "ASSET_EFFECT_MISMATCH", "Asset effect does not match the prepared decision");
      return;
    }
    dispositions.markEffective(view.decisionId(), effect.effectId());
    processing.retrySoon(claim, phase);
  }

  private void processEffective(
      PropertyDispositionProcessingStore.Claim claim, ProcessingView view) {
    String phase = "RELEASE_LEASE";
    LeaseReleaseCommand release = view.leaseRelease();
    if (release == null) {
      processing.completed(claim, phase);
      return;
    }
    dependencies.releaseLease(
        key(view.decisionId(), phase),
        release.leaseId(),
        release.expectedLeaseVersion(),
        release.fencingToken(),
        release.ownerType(),
        release.ownerId().toString());
    dispositions.confirmLeaseReleased(view.decisionId(), release.leaseId());
    processing.completed(claim, phase);
  }

  private void quarantine(
      PropertyDispositionProcessingStore.Claim claim,
      ProcessingView view,
      String phase,
      String code,
      String detail) {
    dispositions.quarantine(view.decisionId(), code, detail);
    processing.quarantined(claim, phase, code, detail);
  }

  private void handleDependencyFailure(
      PropertyDispositionProcessingStore.Claim claim,
      ProcessingView view,
      String phase,
      MaintenanceDependencyException exception) {
    HttpStatus status = exception.status();
    // Once the asset effect is effective, lease release is reconciliation only. A failed or
    // ambiguous release cannot honestly roll the decision back into a quarantined pre-effect state.
    if ((view != null && view.state() == PropertyDispositionState.EFFECTIVE)
        || status == HttpStatus.TOO_MANY_REQUESTS
        || status == HttpStatus.SERVICE_UNAVAILABLE
        || status.is5xxServerError()) {
      String code = "DEPENDENCY_" + status.value();
      String detail = safeDetail(exception.getMessage());
      if (view != null && claim.nextFailureExhaustsRetries()) {
        quarantineExhausted(claim, view, phase, code, detail);
      } else {
        processing.retryableFailure(claim, phase, code, detail, RETRY_DELAY);
      }
      return;
    }
    try {
      dispositions.quarantine(
          claim.decisionId(),
          "DEPENDENCY_" + status.value(),
          safeDetail(exception.getMessage()));
      processing.quarantined(
          claim,
          phase,
          "DEPENDENCY_" + status.value(),
          safeDetail(exception.getMessage()));
    } catch (RuntimeException localFailure) {
      if (claim.nextFailureExhaustsRetries()) {
        processing.quarantined(
            claim,
            phase,
            "LOCAL_OR_UNKNOWN",
            safeDetail(localFailure.getMessage()));
      } else {
        processing.retryableFailure(
            claim,
            phase,
            "LOCAL_OR_UNKNOWN",
            safeDetail(localFailure.getMessage()),
            RETRY_DELAY);
      }
    }
  }

  private void quarantineExhausted(
      PropertyDispositionProcessingStore.Claim claim,
      ProcessingView view,
      String phase,
      String code,
      String detail) {
    // An already effective decision stays truthful: only its remaining lease-release
    // reconciliation is quarantined. All pre-effect states quarantine the business decision too.
    if (view.state() != PropertyDispositionState.EFFECTIVE) {
      try {
        dispositions.quarantine(view.decisionId(), code, detail);
      } catch (RuntimeException localFailure) {
        processing.quarantined(
            claim,
            phase,
            "LOCAL_OR_UNKNOWN",
            safeDetail(localFailure.getMessage()));
        return;
      }
    }
    processing.quarantined(claim, phase, code, detail);
  }

  private static UUID key(UUID decisionId, String phase) {
    return UUID.nameUUIDFromBytes(
        ("property-disposition:" + decisionId + ':' + phase).getBytes(StandardCharsets.UTF_8));
  }

  private static boolean matchesPreparedFence(
      ProcessingView view, MaintenanceDependencyGateway.PropertyDispositionFence fence) {
    if (fence == null || !"PREPARED".equals(fence.state()) || fence.appliedAt() != null) {
      return false;
    }
    MaintenanceDependencyGateway.PropertyDispositionPreparation preparation = view.preparation();
    return view.decisionId().equals(fence.decisionId())
        && preparation.warehouseId().equals(fence.warehouseId())
        && preparation.assetKind() == fence.assetKind()
        && preparation.assetId().equals(fence.assetId())
        && preparation.disposition() == fence.disposition()
        && java.util.Objects.equals(
            preparation.maintenanceCustodyClaimId(), fence.maintenanceCustodyClaimId())
        && java.util.Objects.equals(
            preparation.maintenanceCustodyVersion(), fence.maintenanceCustodyVersion())
        && sameContents(preparation.contents(), fence.contents());
  }

  private static boolean sameContents(
      java.util.List<MaintenanceDependencyGateway.PropertyDispositionContent> expected,
      java.util.List<MaintenanceDependencyGateway.PropertyDispositionContent> actual) {
    return expected.size() == actual.size()
        && new HashSet<>(expected).equals(new HashSet<>(actual));
  }

  private static String phase(PropertyDispositionState state) {
    return switch (state) {
      case APPROVED -> "PREPARE";
      case MOVEMENT_PENDING -> "MOVEMENT";
      case EFFECT_PENDING -> "APPLY";
      case EFFECTIVE -> "RELEASE_LEASE";
      case PENDING_APPROVAL, REJECTED, QUARANTINED -> "READ";
    };
  }

  private static String safeCode(String value) {
    if (value == null || value.isBlank()) return "UNKNOWN";
    String normalized = value.trim().replaceAll("[^A-Z0-9_]", "_");
    return normalized.substring(0, Math.min(96, normalized.length()));
  }

  private static String safeDetail(String value) {
    if (value == null || value.isBlank()) return "Dependency outcome did not provide a safe detail";
    String normalized = value.trim();
    return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
  }
}
