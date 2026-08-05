package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportState;
import dev.buhanzaz.rwms.asset.repository.RentalItemHtmlImportRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Advances durable media jobs independently of a browser session. Each media
 * command is idempotent, so concurrent service instances may safely observe
 * the same import while the aggregate lock serializes local state changes.
 */
@Component
@ConditionalOnProperty(
    prefix = "rwms.asset.media-import",
    name = "enabled",
    havingValue = "true")
@RequiredArgsConstructor
public class RentalItemHtmlImportMediaCoordinator {
  private static final List<RentalItemHtmlImportState> ACTIVE_STATES =
      List.of(
          RentalItemHtmlImportState.COMMITTING,
          RentalItemHtmlImportState.ASSETS_COMMITTED,
          RentalItemHtmlImportState.MEDIA_IMPORTING);

  private final RentalItemHtmlImportRepository imports;
  private final RentalItemHtmlImportService service;

  @Scheduled(
      fixedDelayString = "${rwms.asset.html-import.media-poll-delay:PT2S}",
      initialDelayString = "${rwms.asset.html-import.media-poll-initial-delay:PT2S}")
  public void advance() {
    List<java.util.UUID> ids =
        imports.findTop50ByStateInOrderByUpdatedAtAscIdAsc(ACTIVE_STATES).stream()
            .map(value -> value.getId())
            .toList();
    for (java.util.UUID id : ids) {
      try {
        service.synchronizeMedia(id);
      } catch (AssetConflictException | AssetDependencyException ignored) {
        // Expected while owner proofs or a private dependency are catching up.
        // The next bounded poll reuses the exact idempotent media command.
      }
    }
  }
}
