package dev.buhanzaz.rwms.logistics.eventing.inbound;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Internal reviewed recovery for a safely staged inbound DLT event. */
@Service
@RequiredArgsConstructor
public class LogisticsInboundReplayService {
  private final LogisticsInboundStagingStore staging;
  private final LogisticsInboxProcessor inbox;

  public boolean approveAndReplay(UUID eventId) {
    if (!staging.approveReplay(eventId)) {
      return false;
    }
    LogisticsInboxProcessor.Outcome outcome = inbox.replay(eventId);
    return outcome == LogisticsInboxProcessor.Outcome.PROCESSED
        || outcome == LogisticsInboxProcessor.Outcome.DUPLICATE;
  }
}
