package dev.buhanzaz.rwms.dossier;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

class DossierPersistenceArchitectureTest {
  private final com.tngtech.archunit.core.domain.JavaClasses dossier =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("dev.buhanzaz.rwms.dossier");

  @Test
  void runtimePersistenceIsJpaOnly() {
    noClasses()
        .that()
        .resideInAPackage("dev.buhanzaz.rwms.dossier..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.jdbc..",
            "org.springframework.data.jdbc..",
            "org.springframework.data.relational..")
        .because("Stage 9 runtime persistence is JPA-only")
        .allowEmptyShould(true)
        .check(dossier);

    methods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("dev.buhanzaz.rwms.dossier.repository..")
        .and()
        .areAnnotatedWith(Query.class)
        .should(notUseNativeSql())
        .because("JPQL is allowed but native runtime SQL is forbidden")
        .allowEmptyShould(true)
        .check(dossier);
  }

  @Test
  void entitiesAndRepositoriesStayInsideTheirServiceBoundary() {
    classes()
        .that()
        .areAnnotatedWith(Entity.class)
        .should()
        .resideInAPackage("dev.buhanzaz.rwms.dossier.domain..")
        .check(dossier);
    classes()
        .that()
        .areAssignableTo(JpaRepository.class)
        .should()
        .resideInAPackage("dev.buhanzaz.rwms.dossier.repository..")
        .allowEmptyShould(true)
        .check(dossier);
    noClasses()
        .that()
        .resideInAPackage("dev.buhanzaz.rwms.dossier..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "dev.buhanzaz.rwms.asset..",
            "dev.buhanzaz.rwms.maintenance..",
            "dev.buhanzaz.rwms.inventory..",
            "dev.buhanzaz.rwms.logistics..",
            "dev.buhanzaz.rwms.taskboard..",
            "dev.buhanzaz.rwms.media..")
        .allowEmptyShould(true)
        .check(dossier);
  }

  private static ArchCondition<JavaMethod> notUseNativeSql() {
    return new ArchCondition<>("not use native SQL") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        if (method.getAnnotationOfType(Query.class).nativeQuery()) {
          events.add(
              SimpleConditionEvent.violated(
                  method, method.getFullName() + " declares nativeQuery=true"));
        }
      }
    };
  }
}
