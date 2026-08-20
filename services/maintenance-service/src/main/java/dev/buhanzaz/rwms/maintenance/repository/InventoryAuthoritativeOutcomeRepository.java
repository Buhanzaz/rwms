package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcome;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for durable authoritative inventory outcome coordinators. */
public interface InventoryAuthoritativeOutcomeRepository
    extends JpaRepository<InventoryAuthoritativeOutcome, InventoryPublicationSourceId> {
  /** Resolves the immutable inventory finding that currently owns a repair target. */
  Optional<InventoryAuthoritativeOutcome> findByTargetRepairId(UUID targetRepairId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from InventoryAuthoritativeOutcome value where value.id = :id")
  Optional<InventoryAuthoritativeOutcome> findByIdForUpdate(
      @Param("id") InventoryPublicationSourceId id);
}
