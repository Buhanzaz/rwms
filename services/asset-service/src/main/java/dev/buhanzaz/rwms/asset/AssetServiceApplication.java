package dev.buhanzaz.rwms.asset;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the asset-service deployable.
 */
@SpringBootApplication
@EnableScheduling
public class AssetServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AssetServiceApplication.class, args);
  }
}
