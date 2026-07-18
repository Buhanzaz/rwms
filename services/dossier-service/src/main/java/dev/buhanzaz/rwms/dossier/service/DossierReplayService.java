package dev.buhanzaz.rwms.dossier.service;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "rwms.dossier.replay", name = "enabled", havingValue = "true")
public class DossierReplayService {
  private final DossierReplayTransactions transactions;

  public DossierReplayService(DossierReplayTransactions transactions) {
    this.transactions = transactions;
  }

  @Scheduled(fixedDelayString = "${rwms.dossier.replay.poll-delay:1h}")
  public void scheduledReplay() {
    runOnce();
  }

  public UUID runOnce() {
    DossierReplayTransactions.ReplayClaim claim = transactions.start();
    try {
      transactions.build(claim.runId());
      transactions.tailVerifyAndActivate(claim.runId());
      return claim.runId();
    } catch (RuntimeException failure) {
      try {
        transactions.rejectFailed(claim.runId());
      } catch (RuntimeException rejectionFailure) {
        failure.addSuppressed(rejectionFailure);
      }
      throw failure;
    }
  }
}
