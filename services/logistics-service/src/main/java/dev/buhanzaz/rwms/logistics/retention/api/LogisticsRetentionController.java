package dev.buhanzaz.rwms.logistics.retention.api;

import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlacePlanningRetentionLegalHoldRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningArchiveManifestResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRetentionDryRunResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningRetentionLegalHoldResponse;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.RecordPlanningArchiveManifestRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReleasePlanningRetentionLegalHoldRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.VerifyPlanningArchiveManifestRequest;
import dev.buhanzaz.rwms.logistics.retention.LogisticsRetentionService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only retention policy, legal-hold, and archive-manifest boundary. */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/logistics/v1/retention")
public class LogisticsRetentionController {
  private final LogisticsAuthorizer access;
  private final LogisticsRetentionService retention;

  /** Reports terminal rows eligible for archiving without mutating any online record. */
  @GetMapping("/dry-run")
  public PlanningRetentionDryRunResponse dryRun(@AuthenticationPrincipal Jwt jwt) {
    access.requireWarehouseOperationRecoveryAdministrator(jwt);
    return retention.dryRun();
  }

  /** Lists active legal holds that block any future retention executor. */
  @GetMapping("/legal-holds")
  public List<PlanningRetentionLegalHoldResponse> legalHolds(@AuthenticationPrincipal Jwt jwt) {
    access.requireWarehouseOperationRecoveryAdministrator(jwt);
    return retention.activeHolds();
  }

  /** Places a durable legal hold without deleting or moving data. */
  @PostMapping("/legal-holds")
  public PlanningRetentionLegalHoldResponse placeLegalHold(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody PlacePlanningRetentionLegalHoldRequest request) {
    access.requireWarehouseOperationRecoveryAdministrator(jwt);
    return retention.place(request, access.subjectId(jwt));
  }

  /** Releases a legal hold under its explicit optimistic fence. */
  @PostMapping("/legal-holds/{holdId}/release")
  public PlanningRetentionLegalHoldResponse releaseLegalHold(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID holdId,
      @Valid @RequestBody ReleasePlanningRetentionLegalHoldRequest request) {
    access.requireWarehouseOperationRecoveryAdministrator(jwt);
    return retention.release(holdId, request, access.subjectId(jwt));
  }

  /** Records an immutable private-archive checksum manifest; it does not run an export. */
  @PostMapping("/archive-manifests")
  public PlanningArchiveManifestResponse recordArchiveManifest(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody RecordPlanningArchiveManifestRequest request) {
    access.requireWarehouseOperationRecoveryAdministrator(jwt);
    return retention.record(request, access.subjectId(jwt));
  }

  /** Records independent checksum verification without authorizing deletion. */
  @PostMapping("/archive-manifests/{manifestId}/verify")
  public PlanningArchiveManifestResponse verifyArchiveManifest(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID manifestId,
      @Valid @RequestBody VerifyPlanningArchiveManifestRequest request) {
    access.requireWarehouseOperationRecoveryAdministrator(jwt);
    return retention.verify(manifestId, request, access.subjectId(jwt));
  }
}
