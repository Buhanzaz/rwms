package dev.buhanzaz.rwms.dossier.eventing;

import org.springframework.stereotype.Component;

/** Provides bounded retry delays for source-consumer work without encoding retry policy in an individual projection. */
@Component
public class DossierRetryDelayer {
  public void delay(long milliseconds) {
    try {
      Thread.sleep(milliseconds);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("DOSSIER_RETRY_INTERRUPTED", exception);
    }
  }
}
