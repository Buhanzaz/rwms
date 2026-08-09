package dev.buhanzaz.rwms.assistant.eventing;

import java.time.Duration;
import org.springframework.stereotype.Component;

/** Interruptible production delay boundary for the assistant's bounded event retries. */
@Component
public class AssistantRetryDelayer {
  /** Waits for one configured retry delay and propagates interruption to the Kafka listener. */
  public void delay(Duration duration) throws InterruptedException {
    Thread.sleep(duration);
  }
}
