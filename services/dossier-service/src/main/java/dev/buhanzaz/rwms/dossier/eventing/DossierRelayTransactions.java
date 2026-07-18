package dev.buhanzaz.rwms.dossier.eventing;

import dev.buhanzaz.rwms.dossier.domain.DossierOutboxEvent;
import dev.buhanzaz.rwms.dossier.domain.DossierOutboxState;
import dev.buhanzaz.rwms.dossier.domain.DossierSanitizedDeadLetter;
import dev.buhanzaz.rwms.dossier.repository.DossierOutboxEventRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSanitizedDeadLetterRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DossierRelayTransactions {
  private final DossierOutboxEventRepository outbox;
  private final DossierSanitizedDeadLetterRepository deadLetters;

  public DossierRelayTransactions(
      DossierOutboxEventRepository outbox,
      DossierSanitizedDeadLetterRepository deadLetters) {
    this.outbox = outbox;
    this.deadLetters = deadLetters;
  }

  @Transactional(readOnly = true)
  public Optional<DossierOutboxEvent> nextOutbox() {
    return outbox
        .findPublishableHeads(
            List.of(DossierOutboxState.PENDING, DossierOutboxState.RETRY),
            DossierOutboxState.PUBLISHED,
            OffsetDateTime.now(ZoneOffset.UTC),
            PageRequest.of(0, 1))
        .stream()
        .findFirst();
  }

  @Transactional(readOnly = true)
  public Optional<DossierSanitizedDeadLetter> nextDeadLetter() {
    return deadLetters
        .findAllByStatusInAndNextAttemptAtLessThanEqualOrderByFailedAtAscIdAsc(
            List.of(DossierOutboxState.PENDING, DossierOutboxState.RETRY),
            OffsetDateTime.now(ZoneOffset.UTC),
            PageRequest.of(0, 1))
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void outboxPublished(UUID eventId) {
    outbox.findById(eventId).orElseThrow().published(OffsetDateTime.now(ZoneOffset.UTC));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void outboxFailed(UUID eventId) {
    DossierOutboxEvent event = outbox.findById(eventId).orElseThrow();
    if (event.getAttemptCount() >= 3) {
      event.deadLetter();
    } else {
      event.retry(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(backoff(event.getAttemptCount())));
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deadLetterPublished(UUID id) {
    deadLetters.findById(id).orElseThrow().published(OffsetDateTime.now(ZoneOffset.UTC));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deadLetterFailed(UUID id) {
    DossierSanitizedDeadLetter value = deadLetters.findById(id).orElseThrow();
    if (value.getAttemptCount() >= 3) {
      value.deadLetter();
    } else {
      value.retry(
          OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(backoff(value.getAttemptCount())));
    }
  }

  /** Explicit service-local recovery after broker availability has been restored. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recoverOutbox(UUID eventId) {
    DossierOutboxEvent event = outbox.findById(eventId).orElseThrow();
    if (outbox.existsByCabinIdAndAggregateVersionLessThanAndStatusNot(
        event.getCabinId(), event.getAggregateVersion(), DossierOutboxState.PUBLISHED)) {
      throw new IllegalStateException("DOSSIER_OUTBOX_PREDECESSOR_UNPUBLISHED");
    }
    event.requeueFromDeadLetter(OffsetDateTime.now(ZoneOffset.UTC));
  }

  /** Explicit service-local recovery for a sanitized DLT relay after broker restoration. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recoverDeadLetter(UUID id) {
    deadLetters
        .findById(id)
        .orElseThrow()
        .requeueFromDeadLetter(OffsetDateTime.now(ZoneOffset.UTC));
  }

  private static long backoff(int attempts) {
    return switch (Math.min(attempts, 2)) {
      case 0 -> 1;
      case 1 -> 2;
      default -> 4;
    };
  }
}
