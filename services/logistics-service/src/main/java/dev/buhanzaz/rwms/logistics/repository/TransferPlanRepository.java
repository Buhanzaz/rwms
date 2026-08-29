package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.TransferPlan;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for the planning projection of an existing transfer document. */
public interface TransferPlanRepository extends JpaRepository<TransferPlan, UUID> {
  Optional<TransferPlan> findByDocument_Id(UUID documentId);

  /** Locks the complete planning aggregate before a fenced draft mutation or confirmation. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select plan from TransferPlan plan where plan.document.id = :documentId")
  Optional<TransferPlan> findForUpdateByDocumentId(@Param("documentId") UUID documentId);
}
