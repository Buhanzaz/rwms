package dev.buhanzaz.rwms.logistics.photo;

import dev.buhanzaz.rwms.logistics.photo.domain.CabinPhotoPresentation;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the short local transactions used for immutable inserts and replay reads. The insert uses
 * its own transaction so a concurrent unique-key failure can be caught outside the rolled-back
 * persistence context and resolved against the winning row.
 */
@Component
@RequiredArgsConstructor
public class CabinPhotoPresentationStore {
  private final CabinPhotoPresentationRepository presentations;

  /** Reads an existing result for one subject-bound public idempotency key. */
  @Transactional(readOnly = true)
  public Optional<CabinPhotoPresentation> findReplay(UUID subjectId, UUID idempotencyKey) {
    return presentations.findByCreatedBySubjectIdAndIdempotencyKey(subjectId, idempotencyKey);
  }

  /** Inserts and flushes one candidate in an independent transaction. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CabinPhotoPresentation insert(CabinPhotoPresentation candidate) {
    return presentations.saveAndFlush(candidate);
  }

  /** Resolves one anonymous presentation identity without exposing creator or warehouse data. */
  @Transactional(readOnly = true)
  public Optional<CabinPhotoPresentation> findById(UUID presentationId) {
    return presentations.findById(presentationId);
  }
}
