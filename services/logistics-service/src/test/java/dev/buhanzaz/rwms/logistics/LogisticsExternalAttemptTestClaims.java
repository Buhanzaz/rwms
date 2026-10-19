package dev.buhanzaz.rwms.logistics;

import dev.buhanzaz.rwms.logistics.service.LogisticsExternalAttemptClaimService;
import dev.buhanzaz.rwms.logistics.service.MediaOwnerProofProcessor;
import dev.buhanzaz.rwms.logistics.service.ReturnCompletionProcessor;
import dev.buhanzaz.rwms.logistics.service.ReturnRegistrationProcessor;
import dev.buhanzaz.rwms.logistics.service.ShipmentProcessor;
import dev.buhanzaz.rwms.logistics.service.TransferProcessor;
import dev.buhanzaz.rwms.logistics.service.TransferPlanProcessor;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Drives the production one-claim processor API in saga tests without restoring a document-wide
 * runtime drain. Each iteration acquires a real fenced claim and the finite test bound fails fast
 * if a workflow does not reach its expected stable state.
 */
final class LogisticsExternalAttemptTestClaims {
  private static final int MAX_TEST_STEPS = 1_000;
  private static final List<String> RETURN_REGISTRATION_OPERATIONS =
      List.of(
          "RETURN_WAREHOUSE_IDENTITY",
          "RETURN_ASSET_SNAPSHOT",
          "RETURN_ASSET_LEASE_ACQUIRE",
          "RETURN_ASSET_INTAKE");
  private static final List<String> RETURN_COMPLETION_OPERATIONS =
      List.of(
          "RETURN_MEDIA_VALIDATE",
          "RETURN_ASSET_SETTLE_FREE",
          "RETURN_ASSET_SETTLE_ESTIMATE",
          "RETURN_MAINTENANCE_ESTIMATE_SOURCE_UPSERT",
          "RETURN_ASSET_ADDITIONAL_EQUIPMENT_RECEIVE",
          "RETURN_ASSET_LEASE_RELEASE");
  private static final List<String> TRANSFER_OPERATIONS =
      List.of(
          "TRANSFER_ORIGIN_WAREHOUSE_IDENTITY",
          "TRANSFER_DESTINATION_WAREHOUSE_IDENTITY",
          "TRANSFER_ASSET_SNAPSHOT",
          "TRANSFER_MAINTENANCE_PREPARE_DEPARTURE",
          "TRANSFER_ASSET_LEASE_ACQUIRE",
          "TRANSFER_ASSET_DEPART",
          "TRANSFER_ARRIVAL_DESTINATION_WAREHOUSE_IDENTITY",
          "TRANSFER_MEDIA_VALIDATE",
          "TRANSFER_ASSET_ARRIVAL_SNAPSHOT",
          "TRANSFER_ASSET_ARRIVE",
          "TRANSFER_ASSET_LEASE_RELEASE",
          "TRANSFER_MAINTENANCE_COMPLETE_ARRIVAL");
  private static final List<String> MEDIA_OWNER_PROOF_OPERATIONS =
      List.of(
          "RETURN_MEDIA_OWNER_PROOF_REGISTER",
          "MEDIA_SHIPMENT_OWNER_PROOF_REGISTER",
          "TRANSFER_MEDIA_OWNER_PROOF_REGISTER",
          "TRANSFER_MEDIA_OWNER_PROOF_DEACTIVATE");

  private LogisticsExternalAttemptTestClaims() {}

  /** Processes every currently due return-registration claim through the one-claim processor API. */
  static int drainReturnRegistration(
      LogisticsExternalAttemptClaimService claims, ReturnRegistrationProcessor processor) {
    return drain(
        () ->
            claims.claimNext(
                LogisticsExternalAttemptClaimService.Owner.RETURN_REGISTRATION,
                RETURN_REGISTRATION_OPERATIONS),
        processor::process);
  }

  /** Processes every currently due return-completion claim through the one-claim processor API. */
  static int drainReturnCompletion(
      LogisticsExternalAttemptClaimService claims, ReturnCompletionProcessor processor) {
    return drain(
        () ->
            claims.claimNext(
                LogisticsExternalAttemptClaimService.Owner.RETURN_COMPLETION,
                RETURN_COMPLETION_OPERATIONS),
        processor::process);
  }

  /** Processes every currently due shipment claim through the one-claim processor API. */
  static int drainShipment(
      LogisticsExternalAttemptClaimService claims, ShipmentProcessor processor) {
    return drain(
        () ->
            claims.claimNextByOperationPrefix(
                LogisticsExternalAttemptClaimService.Owner.SHIPMENT, "SHIPMENT_"),
        processor::process);
  }

  /**
   * Processes transfer claims through the one-claim processor API. Test-only PostgreSQL time
   * advancement skips a scheduler delay only for a lease-free, never-retried local deferral; it
   * never advances a remote retry, terminal attempt, active lease, or another owner family.
   */
  static int drainTransfer(
      LogisticsExternalAttemptClaimService claims, TransferProcessor processor, JdbcTemplate jdbc) {
    int processed = 0;
    while (processed < MAX_TEST_STEPS) {
      var claim =
          claims.claimNext(
              LogisticsExternalAttemptClaimService.Owner.TRANSFER, TRANSFER_OPERATIONS);
      if (claim.isPresent()) {
        processor.process(claim.get());
        processed++;
        continue;
      }
      if (advanceDeferredTransferAttempt(jdbc) == 0) return processed;
    }
    throw new AssertionError("Claim-driven transfer workflow did not reach a stable test state");
  }

  /** Drives all durable reservation/resource effects of the transfer-plan workflow. */
  static int drainTransferPlan(
      LogisticsExternalAttemptClaimService claims,
      TransferPlanProcessor processor,
      JdbcTemplate jdbc) {
    int processed = 0;
    while (processed < MAX_TEST_STEPS) {
      var claim =
          claims.claimNextByOperationPrefix(
              LogisticsExternalAttemptClaimService.Owner.TRANSFER, "XFER_PLAN_");
      if (claim.isPresent()) {
        processor.process(claim.get());
        processed++;
        continue;
      }
      if (advanceDeferredTransferPlanAttempt(jdbc) == 0) return processed;
    }
    throw new AssertionError("Transfer-plan workflow did not reach a stable test state");
  }

  /** Processes every currently due media-owner proof claim through the one-claim processor API. */
  static int drainMediaOwnerProof(
      LogisticsExternalAttemptClaimService claims, MediaOwnerProofProcessor processor) {
    return drain(
        () ->
            claims.claimNext(
                LogisticsExternalAttemptClaimService.Owner.MEDIA_OWNER_PROOF,
                MEDIA_OWNER_PROOF_OPERATIONS),
        processor::process);
  }

  private static int drain(
      java.util.function.Supplier<java.util.Optional<LogisticsExternalAttemptClaimService.Claim>>
          nextClaim,
      Consumer<LogisticsExternalAttemptClaimService.Claim> processor) {
    int processed = 0;
    while (processed < MAX_TEST_STEPS) {
      var claim = nextClaim.get();
      if (claim.isEmpty()) return processed;
      processor.accept(claim.get());
      processed++;
    }
    throw new AssertionError("Claim-driven workflow did not reach a stable test state");
  }

  /**
   * Makes only a test driver's prerequisite deferral eligible immediately according to the same
   * PostgreSQL clock used by production claims. A retained positive fence proves that this row was
   * previously claimed, while the cleared token and expiry prove no worker owns it now.
   */
  private static int advanceDeferredTransferAttempt(JdbcTemplate jdbc) {
    if (jdbc == null) throw new IllegalArgumentException("jdbc is required");
    String placeholders =
        String.join(
            ", ", java.util.Collections.nCopies(TRANSFER_OPERATIONS.size(), "?"));
    String sql =
        """
        update logistics_external_attempt
        set next_attempt_at = current_timestamp,
            row_version = row_version + 1
        where operation_type in (%s)
          and result in ('PENDING', 'RETRY')
          and retry_count = 0
          and lease_token is null
          and lease_expires_at is null
          and lease_fence > 0
          and next_attempt_at > current_timestamp
        """
            .formatted(placeholders);
    return jdbc.update(sql, TRANSFER_OPERATIONS.toArray());
  }

  private static int advanceDeferredTransferPlanAttempt(JdbcTemplate jdbc) {
    if (jdbc == null) throw new IllegalArgumentException("jdbc is required");
    return jdbc.update(
        """
        update logistics_external_attempt
        set next_attempt_at = current_timestamp,
            row_version = row_version + 1
        where operation_type like 'XFER\\_PLAN\\_%' escape '\\'
          and result in ('PENDING', 'RETRY')
          and retry_count = 0
          and lease_token is null
          and lease_expires_at is null
          and lease_fence > 0
          and next_attempt_at > current_timestamp
        """);
  }
}
