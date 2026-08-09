package dev.buhanzaz.rwms.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the warehouse metadata, timezone and lifecycle owner service. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class WarehouseServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(WarehouseServiceApplication.class, args);
  }
}
