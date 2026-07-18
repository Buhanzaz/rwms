package dev.buhanzaz.rwms.logistics.eventing.inbound;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Verifies that the live inbound evidence projection equals a deterministic shadow replay. */
@Service
@RequiredArgsConstructor
public class LogisticsInboundProjectionVerifier {
  private final LogisticsInboundObservationStore observations;

  @Transactional(readOnly = true)
  public void verify() {
    List<LogisticsInboundObservationStore.Observation> live = observations.liveProjection();
    List<LogisticsInboundObservationStore.Observation> shadow = observations.shadowReplay();
    if (!live.equals(shadow)) {
      throw new IllegalStateException("Logistics inbound live projection differs from shadow replay");
    }
  }
}
