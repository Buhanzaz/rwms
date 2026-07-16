package dev.buhanzaz.rwms.asset;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AssetServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(AssetServiceApplication.class, args);
  }
}
