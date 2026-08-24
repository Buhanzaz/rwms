package dev.buhanzaz.rwms.logistics.photo;

import dev.buhanzaz.rwms.logistics.photo.domain.CabinPhotoPresentation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for immutable cabin photo presentations and creator-scoped replay lookup. */
public interface CabinPhotoPresentationRepository
    extends JpaRepository<CabinPhotoPresentation, UUID> {
  Optional<CabinPhotoPresentation> findByCreatedBySubjectIdAndIdempotencyKey(
      UUID createdBySubjectId, UUID idempotencyKey);
}
