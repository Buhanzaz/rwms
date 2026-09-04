package dev.buhanzaz.rwms.logistics.retention.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlacePlanningRetentionLegalHoldRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.RecordPlanningArchiveManifestRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.ReleasePlanningRetentionLegalHoldRequest;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.VerifyPlanningArchiveManifestRequest;
import dev.buhanzaz.rwms.logistics.retention.LogisticsRetentionService;
import dev.buhanzaz.rwms.logistics.retention.domain.LogisticsRetentionDataset;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** Prevents compliance commands from regressing to the planner machine-identity boundary. */
class LogisticsRetentionControllerTest {
  @Test
  void dryRunRequiresTheExistingGlobalAdministratorWriteGuard() {
    LogisticsAuthorizer access = mock(LogisticsAuthorizer.class);
    LogisticsRetentionService retention = mock(LogisticsRetentionService.class);
    Jwt jwt = mock(Jwt.class);

    new LogisticsRetentionController(access, retention).dryRun(jwt);

    verify(access).requireWarehouseOperationRecoveryAdministrator(jwt);
    verify(retention).dryRun();
  }

  @Test
  void legalHoldAuditIdentityAlwaysComesFromTheVerifiedJwtPrincipal() {
    LogisticsAuthorizer access = mock(LogisticsAuthorizer.class);
    LogisticsRetentionService retention = mock(LogisticsRetentionService.class);
    Jwt jwt = mock(Jwt.class);
    UUID authenticatedSubjectId = UUID.randomUUID();
    when(access.subjectId(jwt)).thenReturn(authenticatedSubjectId);
    PlacePlanningRetentionLegalHoldRequest request =
        new PlacePlanningRetentionLegalHoldRequest(
            LogisticsRetentionDataset.EVENT_OUTBOX, "order:42", "Судебный запрос");

    new LogisticsRetentionController(access, retention).placeLegalHold(jwt, request);

    verify(access).requireWarehouseOperationRecoveryAdministrator(jwt);
    verify(access).subjectId(jwt);
    verify(retention).place(request, authenticatedSubjectId);
  }

  @Test
  void releaseAndManifestAuditIdentitiesAlsoComeOnlyFromTheVerifiedJwtPrincipal() {
    LogisticsAuthorizer access = mock(LogisticsAuthorizer.class);
    LogisticsRetentionService retention = mock(LogisticsRetentionService.class);
    Jwt jwt = mock(Jwt.class);
    UUID authenticatedSubjectId = UUID.randomUUID();
    UUID holdId = UUID.randomUUID();
    UUID manifestId = UUID.randomUUID();
    when(access.subjectId(jwt)).thenReturn(authenticatedSubjectId);
    ReleasePlanningRetentionLegalHoldRequest release =
        new ReleasePlanningRetentionLegalHoldRequest(3L, "Официальное снятие");
    RecordPlanningArchiveManifestRequest record =
        new RecordPlanningArchiveManifestRequest(
            LogisticsRetentionDataset.EVENT_INBOX,
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            OffsetDateTime.parse("2026-02-01T00:00:00Z"),
            "private/logistics/inbox-2026-01.ndjson.enc",
            "a".repeat(64),
            42L);
    VerifyPlanningArchiveManifestRequest verification =
        new VerifyPlanningArchiveManifestRequest(4L);
    LogisticsRetentionController controller = new LogisticsRetentionController(access, retention);

    controller.releaseLegalHold(jwt, holdId, release);
    controller.recordArchiveManifest(jwt, record);
    controller.verifyArchiveManifest(jwt, manifestId, verification);

    verify(access, times(3)).requireWarehouseOperationRecoveryAdministrator(jwt);
    verify(access, times(3)).subjectId(jwt);
    verify(retention).release(holdId, release, authenticatedSubjectId);
    verify(retention).record(record, authenticatedSubjectId);
    verify(retention).verify(manifestId, verification, authenticatedSubjectId);
  }
}
