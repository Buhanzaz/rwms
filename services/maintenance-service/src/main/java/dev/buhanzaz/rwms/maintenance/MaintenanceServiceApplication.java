package dev.buhanzaz.rwms.maintenance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bootstraps maintenance-service and scans its service-local configuration. */
@SpringBootApplication
@EnableScheduling
public class MaintenanceServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(MaintenanceServiceApplication.class, args);
  }
}
