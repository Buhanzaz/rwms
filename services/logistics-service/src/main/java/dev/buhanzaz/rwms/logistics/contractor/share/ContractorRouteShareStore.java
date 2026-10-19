package dev.buhanzaz.rwms.logistics.contractor.share;

import dev.buhanzaz.rwms.logistics.contractor.share.domain.ContractorRouteShare;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns short local transactions for share creation, public reads and expected-version revocation.
 * No task-board call is made while a logistics database transaction is open.
 */
@Component
@RequiredArgsConstructor
public class ContractorRouteShareStore {
  private final ContractorRouteShareRepository shares;

  /** Reads an existing creator-scoped command result for exact idempotent replay. */
  @Transactional(readOnly = true)
  public Optional<ContractorRouteShare> findReplay(UUID subjectId, UUID idempotencyKey) {
    return shares.findByCreatedBySubjectIdAndIdempotencyKey(subjectId, idempotencyKey);
  }

  /** Inserts and flushes a candidate independently so a concurrent winner can be reread safely. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ContractorRouteShare insert(ContractorRouteShare candidate) {
    return shares.saveAndFlush(candidate);
  }

  /** Reads one share with immutable task membership for public revalidation. */
  @Transactional(readOnly = true)
  public Optional<ContractorRouteShare> findById(UUID shareId) {
    return shares.findWithTasksById(shareId);
  }

  /** Applies one monotonic revocation while holding the aggregate row lock. */
  @Transactional
  public ContractorRouteShare revoke(
      UUID shareId, UUID warehouseId, long expectedVersion, OffsetDateTime timestamp) {
    ContractorRouteShare share =
        shares.findForUpdate(shareId).orElseThrow(ContractorRouteShareProblem::notFound);
    if (!share.getWarehouseId().equals(warehouseId)) {
      throw ContractorRouteShareProblem.notFound();
    }
    if (share.getVersion() != expectedVersion) {
      throw ContractorRouteShareProblem.conflict(
          "CONTRACTOR_ROUTE_SHARE_VERSION_CONFLICT",
          "Ссылка уже изменена; обновите данные и повторите действие");
    }
    share.revoke(timestamp);
    return shares.saveAndFlush(share);
  }
}
