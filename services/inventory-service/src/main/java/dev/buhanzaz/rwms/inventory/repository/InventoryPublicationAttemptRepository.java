package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationAttempt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local inventory publication attempt persistence.
 */
public interface InventoryPublicationAttemptRepository
    extends JpaRepository<InventoryPublicationAttempt, UUID> {
  Optional<InventoryPublicationAttempt> findByPublicationIntentIdAndAttemptNo(
      UUID publicationIntentId, int attemptNo);

  boolean existsByPublicationIntentIdAndIdempotencyKey(
      UUID publicationIntentId, UUID idempotencyKey);
}
