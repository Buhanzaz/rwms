package dev.buhanzaz.rwms.logistics.contractor.share;

import dev.buhanzaz.rwms.logistics.contractor.share.domain.ContractorRouteShare;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data boundary for durable contractor route-link lifecycle and replay reads. */
public interface ContractorRouteShareRepository extends JpaRepository<ContractorRouteShare, UUID> {
  /** Reads one creator-scoped idempotency result with its immutable task membership. */
  @EntityGraph(attributePaths = "tasks")
  Optional<ContractorRouteShare> findByCreatedBySubjectIdAndIdempotencyKey(
      UUID createdBySubjectId, UUID idempotencyKey);

  /** Resolves a public link and its task membership in one bounded read. */
  @EntityGraph(attributePaths = "tasks")
  @Query("select share from ContractorRouteShare share where share.id = :id")
  Optional<ContractorRouteShare> findWithTasksById(@Param("id") UUID id);

  /** Serializes revocation against the aggregate version and token-revision fence. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = "tasks")
  @Query("select share from ContractorRouteShare share where share.id = :id")
  Optional<ContractorRouteShare> findForUpdate(@Param("id") UUID id);
}
