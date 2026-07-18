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

/** Bounded recovery loop for committed return-registration work. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "rwms.logistics.return-registration",
    name = "relay-enabled",
    havingValue = "true")
class ReturnRegistrationRelay {
  private final LogisticsExternalAttemptRepository attempts;
  private final ReturnRegistrationProcessor processor;

  @Scheduled(
      fixedDelayString = "${rwms.logistics.return-registration.relay-delay:1s}",
      initialDelayString = "${rwms.logistics.return-registration.relay-initial-delay:1s}")
  void relayDueAttempts() {
    List<UUID> documentIds =
        attempts.findDueDocumentIds(
            List.of(
                LogisticsExternalAttemptResult.PENDING,
                LogisticsExternalAttemptResult.RETRY),
            OffsetDateTime.now(ZoneOffset.UTC));
    for (UUID documentId : documentIds) {
      processor.processUntilIdle(documentId);
    }
  }
}
