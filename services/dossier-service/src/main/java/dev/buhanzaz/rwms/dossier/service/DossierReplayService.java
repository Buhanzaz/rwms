package dev.buhanzaz.rwms.dossier.service;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Optionally schedules and runs a controlled new-generation replay from dossier's local authoritative source journal. */
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

  /**
   * Runs one isolated generation replay: claim the active pointer, build from its high-water
   * snapshot, tail and verify parity, then activate atomically. A failure rejects the target in a
   * separate recovery transaction before it is rethrown.
   */
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
