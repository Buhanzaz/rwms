package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcomeReceipt;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for permanent authoritative-inventory idempotency receipts. */
public interface InventoryAuthoritativeOutcomeReceiptRepository
    extends JpaRepository<InventoryAuthoritativeOutcomeReceipt, UUID> {
  /**
   * Resolves immutable source evidence for the newest completed receipt whose frozen response
   * names the supplied repair.
   */
  @Query(
      value =
          """
          select outcome.inventory_id as "inventoryId",
                 outcome.finding_id as "findingId",
                 outcome.finding_revision as "findingRevision",
                 outcome.final_plan_sha256 as "finalPlanSha256",
                 outcome.request_sha256 as "requestSha256"
            from inventory_authoritative_outcome_receipt receipt
            join inventory_authoritative_outcome outcome
              on outcome.inventory_id = receipt.inventory_id
             and outcome.final_plan_version = receipt.final_plan_version
             and outcome.finding_id = receipt.finding_id
           where receipt.completed_at is not null
             and receipt.response_snapshot ->> 'repairId' = cast(:repairId as text)
           order by receipt.completed_at desc, receipt.idempotency_key desc
           limit 1
          """,
      nativeQuery = true)
  Optional<AuthoritativeRepairSourceEvidence> findNewestCompletedSourceByRepairId(
      @Param("repairId") UUID repairId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select value from InventoryAuthoritativeOutcomeReceipt value"
          + " where value.idempotencyKey = :idempotencyKey")
  Optional<InventoryAuthoritativeOutcomeReceipt> findByIdForUpdate(
      @Param("idempotencyKey") UUID idempotencyKey);

  /** Immutable authoritative-inventory fields required by the public repair read model. */
  interface AuthoritativeRepairSourceEvidence {
    UUID getInventoryId();

    UUID getFindingId();

    long getFindingRevision();

    String getFinalPlanSha256();

    String getRequestSha256();
  }
}
