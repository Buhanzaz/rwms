package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded relay for transfer departure and arrival attempts. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.logistics.transfer", name = "relay-enabled", havingValue = "true")
class TransferRelay {
  private final LogisticsExternalAttemptRepository attempts;
  private final TransferProcessor processor;

  @Scheduled(
      fixedDelayString = "${rwms.logistics.transfer.relay-delay:1s}",
      initialDelayString = "${rwms.logistics.transfer.relay-initial-delay:1s}")
  void relayDueAttempts() {
    List<UUID> documentIds =
        attempts.findDueDocumentIds(
            List.of(
                LogisticsExternalAttemptResult.PENDING,
                LogisticsExternalAttemptResult.RETRY),
            OffsetDateTime.now(ZoneOffset.UTC));
    for (UUID documentId : documentIds) processor.processUntilIdle(documentId);
  }
}
