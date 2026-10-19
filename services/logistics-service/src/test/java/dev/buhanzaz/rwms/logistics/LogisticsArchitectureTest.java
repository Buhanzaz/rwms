package dev.buhanzaz.rwms.logistics;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

class LogisticsArchitectureTest {
  private static final String[] OTHER_SERVICE_PACKAGES = {
    "dev.buhanzaz.rwms.auth..",
    "dev.buhanzaz.rwms.taskboard..",
    "dev.buhanzaz.rwms.warehouse..",
    "dev.buhanzaz.rwms.asset..",
    "dev.buhanzaz.rwms.maintenance..",
    "dev.buhanzaz.rwms.inventory..",
    "dev.buhanzaz.rwms.media.."
  };

  private final com.tngtech.archunit.core.domain.JavaClasses logistics =
      new ClassFileImporter().importPackages("dev.buhanzaz.rwms.logistics");

  @Test
  void logisticsUsesOnlyVersionedTransportBoundariesForOtherServices() {
    noClasses()
        .that()
        .resideInAPackage("dev.buhanzaz.rwms.logistics..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(OTHER_SERVICE_PACKAGES)
        .because("logistics owns its domain model and must not share another service's Java model")
        .allowEmptyShould(true)
        .check(logistics);
  }

  @Test
  void logisticsUsesConstructorInjectionAndMappingPackages() {
    noFields()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("dev.buhanzaz.rwms.logistics..")
        .and()
        .areDeclaredInClassesThat()
        .haveSimpleNameNotEndingWith("Test")
        .should()
        .beAnnotatedWith(Autowired.class)
        .allowEmptyShould(true)
        .check(logistics);
    classes()
        .that()
        .areAnnotatedWith(Mapper.class)
        .should()
        .resideInAPackage("..mapper..")
        .allowEmptyShould(true)
        .check(logistics);
    classes()
        .that()
        .areAnnotatedWith(Entity.class)
        .should()
        .resideInAPackage("..domain..")
        .allowEmptyShould(true)
        .check(logistics);
  }
}
