package dev.buhanzaz.rwms.logistics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bootstraps the logistics service and its locally owned Spring components.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LogisticsServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(LogisticsServiceApplication.class, args);
  }
}
