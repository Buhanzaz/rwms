package dev.buhanzaz.rwms.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Boot entry point for the analytics read projection and its scheduled local gap-recovery work. */
@EnableScheduling
@SpringBootApplication
public class AnalyticsServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AnalyticsServiceApplication.class, args);
  }
}
