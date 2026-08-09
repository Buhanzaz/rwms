package dev.buhanzaz.rwms.dossier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boot entry point for the dossier read projection and its service-local source consumer, replay and relay components. */
@SpringBootApplication
public class DossierServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(DossierServiceApplication.class, args);
  }
}
