package dev.buhanzaz.rwms.analytics;

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

class AnalyticsPersistenceArchitectureTest {
  private final com.tngtech.archunit.core.domain.JavaClasses analytics =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("dev.buhanzaz.rwms.analytics");

  @Test
  void projectionUsesOnlyItsOwnJpaDatabase() {
    noClasses()
        .that()
        .resideInAPackage("dev.buhanzaz.rwms.analytics..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.jdbc..",
            "org.springframework.data.jdbc..",
            "org.springframework.data.relational..",
            "dev.buhanzaz.rwms.taskboard..",
            "dev.buhanzaz.rwms.warehouse..",
            "dev.buhanzaz.rwms.maintenance..")
        .allowEmptyShould(true)
        .check(analytics);

    classes()
        .that()
        .areAnnotatedWith(Entity.class)
        .should()
        .resideInAPackage("dev.buhanzaz.rwms.analytics.domain..")
        .check(analytics);
    classes()
        .that()
        .areAssignableTo(JpaRepository.class)
        .should()
        .resideInAPackage("dev.buhanzaz.rwms.analytics.repository..")
        .allowEmptyShould(true)
        .check(analytics);
  }

  @Test
  void repositoryQueriesRemainPortableJpql() {
    methods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("dev.buhanzaz.rwms.analytics.repository..")
        .and()
        .areAnnotatedWith(Query.class)
        .should(notUseNativeSql())
        .allowEmptyShould(true)
        .check(analytics);
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
